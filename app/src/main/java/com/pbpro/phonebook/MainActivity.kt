package com.pbpro.phonebook

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import androidx.work.*
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ==================== 1. DATABASE ENTITIES & DAOS ====================

@Entity(tableName = "contacts", indices = [Index(value = ["normalizedPhone"], unique = true), Index(value = ["name"])])
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phoneNumber: String,
    val normalizedPhone: String,
    val email: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pending_duplicates", indices = [Index(value = ["normalizedPhone", "incomingName"], unique = true)])
data class PendingDuplicateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val incomingName: String,
    val incomingPhone: String,
    val normalizedPhone: String,
    val existingName: String,
    val existingPhone: String,
    val email: String = "",
    val detectedAt: Long = System.currentTimeMillis()
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE ASC")
    fun getAllFlow(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE name LIKE '%' || :q || '%' OR phoneNumber LIKE '%' || :q || '%' OR email LIKE '%' || :q || '%' ORDER BY name COLLATE NOCASE ASC")
    fun searchFlow(q: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllSnapshot(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE normalizedPhone = :norm LIMIT 1")
    suspend fun findByNorm(norm: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: ContactEntity): Long

    @Update
    suspend fun update(c: ContactEntity)

    @Query("SELECT COUNT(*) FROM contacts")
    fun countFlow(): Flow<Int>
}

@Dao
interface DuplicateDao {
    @Query("SELECT * FROM pending_duplicates ORDER BY detectedAt DESC")
    fun getAllFlow(): Flow<List<PendingDuplicateEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(d: PendingDuplicateEntity): Long

    @Delete
    suspend fun delete(d: PendingDuplicateEntity)
}

@Database(entities = [ContactEntity::class, PendingDuplicateEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun duplicateDao(): DuplicateDao

    companion object {
        @Volatile private var INST: AppDb? = null
        fun get(ctx: Context): AppDb = INST ?: synchronized(this) {
            INST ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "pbpro_fast.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build().also { INST = it }
        }
    }
}

// ==================== 2. ZERO-COMMAND AUTO-SYNC ENGINE ====================

object PhoneNorm {
    fun clean(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return if (d.length >= 10) d.takeLast(10) else d
    }
}

object SyncEngine {
    suspend fun syncDeviceContacts(ctx: Context, db: AppDb) = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext
        val resolver = ctx.contentResolver
        val emails = mutableMapOf<String, String>()

        resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null)?.use { c ->
            val idI = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val emI = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) {
                val id = c.getString(idI) ?: continue
                val em = c.getString(emI) ?: ""
                if (em.isNotBlank() && !emails.containsKey(id)) emails[id] = em
            }
        }

        resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { c ->
            val idI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nmI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (c.moveToNext()) {
                val id = c.getString(idI) ?: continue
                val name = c.getString(nmI)?.trim() ?: "Unknown"
                val num = c.getString(phI)?.trim() ?: continue
                val norm = PhoneNorm.clean(num)
                if (norm.isBlank()) continue

                val email = emails[id] ?: ""
                val existing = db.contactDao().findByNorm(norm)
                if (existing == null) {
                    db.contactDao().insert(ContactEntity(name = name, phoneNumber = num, normalizedPhone = norm, email = email))
                } else if (!existing.name.equals(name, ignoreCase = true)) {
                    db.duplicateDao().insert(PendingDuplicateEntity(
                        incomingName = name, incomingPhone = num, normalizedPhone = norm,
                        existingName = existing.name, existingPhone = existing.phoneNumber, email = email
                    ))
                }
            }
        }
    }
}

class AutoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        SyncEngine.syncDeviceContacts(applicationContext, AppDb.get(applicationContext))
        return Result.success()
    }
}

// ==================== 3. EXCEL, PDF & ZIP EXPORT ENGINE ====================

object ExportEngine {
    private fun dir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { if (!exists()) mkdirs() }
    private fun ts(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    suspend fun makeExcel(ctx: Context, list: List<ContactEntity>): File = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "PB_PRO_Contacts_${ts()}.xls")
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><?mso-application progid=\"Excel.Sheet\"?>")
        sb.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">")
        sb.append("<Worksheet ss:Name=\"Contacts\"><Table><Row><Cell><Data ss:Type=\"String\">SL</Data></Cell><Cell><Data ss:Type=\"String\">Name</Data></Cell><Cell><Data ss:Type=\"String\">Phone</Data></Cell><Cell><Data ss:Type=\"String\">Email</Data></Cell></Row>")
        list.forEachIndexed { i, c ->
            sb.append("<Row><Cell><Data ss:Type=\"Number\">${i + 1}</Data></Cell><Cell><Data ss:Type=\"String\">${esc(c.name)}</Data></Cell><Cell><Data ss:Type=\"String\">${esc(c.phoneNumber)}</Data></Cell><Cell><Data ss:Type=\"String\">${esc(c.email)}</Data></Cell></Row>")
        }
        sb.append("</Table></Worksheet></Workbook>")
        f.writeText(sb.toString(), Charsets.UTF_8)
        f
    }

    suspend fun makePdf(ctx: Context, list: List<ContactEntity>): File = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "PB_PRO_Contacts_${ts()}.pdf")
        val doc = PdfDocument()
        val pHeader = Paint().apply { color = Color.parseColor("#00F0FF"); textSize = 16f; isFakeBoldText = true }
        val pBg = Paint().apply { color = Color.parseColor("#0B0F19") }
        val pRow = Paint().apply { color = Color.BLACK; textSize = 10f }

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNum).create())
        var canvas = page.canvas
        canvas.drawRect(0f, 0f, 595f, 60f, pBg)
        canvas.drawText("PB PRO — Contact Directory (Total: ${list.size})", 30f, 38f, pHeader)
        var y = 85f

        list.forEachIndexed { i, c ->
            if (y > 800f) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNum).create())
                canvas = page.canvas
                y = 40f
            }
            canvas.drawText("${i + 1}. ${c.name.take(26)}  |  ${c.phoneNumber}  |  ${c.email.take(25)}", 30f, y, pRow)
            y += 22f
        }
        doc.finishPage(page)
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        f
    }

    suspend fun makeZip(ctx: Context, list: List<ContactEntity>): File = withContext(Dispatchers.IO) {
        val xls = makeExcel(ctx, list)
        val pdf = makePdf(ctx, list)
        val zip = File(dir(ctx), "PB_PRO_Backup_${ts()}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
            listOf(xls, pdf).forEach { file ->
                FileInputStream(file).use { fis ->
                    zos.putNextEntry(ZipEntry(file.name))
                    fis.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
        zip
    }

    fun share(ctx: Context, file: File, mime: String, pkg: String? = null) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PB PRO Contact Backup")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        } catch (_: Exception) {
            intent.setPackage(null)
            ctx.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        }
    }
}

// ==================== 4. MAIN ACTIVITY & UI ====================

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDb
    private lateinit var adapter: FastContactAdapter
    private var searchJob: Job? = null
    private var duplicates: List<PendingDuplicateEntity> = emptyList()

    private lateinit var tvTotal: TextView
    private lateinit var btnDupWarning: Button
    private lateinit var tvSelected: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnBulkSms: Button

    private val serviceId = "com.pbpro.phonebook.P2P_SYNC"
    private val gson = Gson()
    private var isHost = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lifecycleScope.launch { SyncEngine.syncDeviceContacts(this@MainActivity, db) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDb.get(this)
        buildUi()
        setupObserverAndWorker()
        requestPerms()
    }

    private fun buildUi() {
        val bgDark = Color.parseColor("#0B0F19")
        val cardDark = Color.parseColor("#131B2E")
        val cyan = Color.parseColor("#00F0FF")
        val purple = Color.parseColor("#B026FF")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgDark)
            setPadding(32, 36, 32, 24)
        }

        // Top Bar
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply { text = "PB PRO"; textSize = 22f; setTextColor(cyan); typeface = Typeface.DEFAULT_BOLD }
        tvTotal = TextView(this).apply { text = "Loading contacts..."; textSize = 12f; setTextColor(Color.LTGRAY) }
        titleBox.addView(tvTitle); titleBox.addView(tvTotal)

        val btnSync = Button(this).apply {
            text = "Phone Sync"
            textSize = 11f
            setTextColor(bgDark)
            setBackgroundColor(cyan)
            setOnClickListener { showSyncDialog() }
        }
        val btnExport = Button(this).apply {
            text = "Export/Share"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(purple)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 14 }
            layoutParams = lp
            setOnClickListener { showExportDialog() }
        }
        topBar.addView(titleBox); topBar.addView(btnSync); topBar.addView(btnExport)
        root.addView(topBar)

        // Duplicate Alert Button
        btnDupWarning = Button(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#F59E0B"))
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { showDuplicateDialog() }
        }
        root.addView(btnDupWarning)

        // Search + Add Row
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 18, 0, 18)
        }
        val etSearch = EditText(this).apply {
            hint = "Search name, number, email..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(cardDark)
            setPadding(28, 22, 28, 22)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { loadContacts(s?.toString()?.trim() ?: "") }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val btnAdd = Button(this).apply {
            text = "+ Add"
            setTextColor(bgDark)
            setBackgroundColor(cyan)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 14 }
            layoutParams = lp
            setOnClickListener { showAddDialog() }
        }
        searchRow.addView(etSearch); searchRow.addView(btnAdd)
        root.addView(searchRow)

        // Bulk Select & SMS Bar
        val bulkBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cardDark)
            setPadding(20, 12, 20, 12)
        }
        btnSelectAll = Button(this).apply {
            text = "Select All"
            textSize = 12f
            setTextColor(cyan)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { if (adapter.isAllSelected()) adapter.clearSelection() else adapter.selectAll() }
        }
        tvSelected = TextView(this).apply {
            text = "0 Selected"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnBulkSms = Button(this).apply {
            text = "Bulk SMS"
            textSize = 12f
            isEnabled = false
            setTextColor(bgDark)
            setBackgroundColor(cyan)
            setOnClickListener {
                val sel = adapter.getSelected()
                if (sel.isNotEmpty()) showSmsDialog(sel)
            }
        }
        bulkBar.addView(btnSelectAll); bulkBar.addView(tvSelected); bulkBar.addView(btnBulkSms)
        root.addView(bulkBar)

        // RecyclerView
        adapter = FastContactAdapter(
            onCall = { callNumber(it.phoneNumber) },
            onSms = { showSmsDialog(listOf(it)) },
            onSelectChange = { cnt ->
                tvSelected.text = "$cnt Selected"
                btnBulkSms.isEnabled = cnt > 0
                btnSelectAll.text = if (adapter.isAllSelected()) "Deselect All" else "Select All"
            }
        )
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(rv)
        setContentView(root)

        // Observe Flows
        loadContacts("")
        lifecycleScope.launch {
            db.contactDao().countFlow().collectLatest { tvTotal.text = "Total Numbers: $it  •  Auto-Sync ON" }
        }
        lifecycleScope.launch {
            db.duplicateDao().getAllFlow().collectLatest { list ->
                duplicates = list
                btnDupWarning.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
                if (list.isNotEmpty()) btnDupWarning.text = "⚠ ${list.size} Duplicate Found — Tap for Permission"
            }
        }
    }

    private fun loadContacts(q: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val flow = if (q.isBlank()) db.contactDao().getAllFlow() else db.contactDao().searchFlow(q)
            flow.collectLatest { adapter.submitList(it) }
        }
    }

    private fun setupObserverAndWorker() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                lifecycleScope.launch { SyncEngine.syncDeviceContacts(this@MainActivity, db) }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        }
        val req = PeriodicWorkRequestBuilder<AutoSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("pbpro_sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun requestPerms() {
        val list = mutableListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        } else list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permLauncher.launch(list.toTypedArray())
    }

    private fun callNumber(phone: String) {
        val act = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(act, Uri.parse("tel:$phone")))
    }

    private fun showSmsDialog(targets: List<ContactEntity>) {
        val et = EditText(this).apply { hint = "Type SMS message..."; minLines = 3 }
        AlertDialog.Builder(this)
            .setTitle("Send SMS to ${targets.size} Contact(s)")
            .setView(et)
            .setPositiveButton("Send") { _, _ ->
                val msg = et.text.toString().trim()
                if (msg.isNotEmpty()) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                            targets.forEach { sm.sendMultipartTextMessage(it.phoneNumber, null, sm.divideMessage(msg), null, null) }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "SMS Sent to ${targets.size} numbers!", Toast.LENGTH_SHORT).show()
                                adapter.clearSelection()
                            }
                        }
                    } else {
                        val nums = targets.joinToString(";") { it.phoneNumber }
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$nums")).apply { putExtra("sms_body", msg) })
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showDuplicateDialog() {
        val d = duplicates.firstOrNull() ?: return
        AlertDialog.Builder(this)
            .setTitle("Duplicate Contact Permission")
            .setMessage("Existing: ${d.existingName} (${d.existingPhone})\nNew: ${d.incomingName} (${d.incomingPhone})\n\nAllow adding/merging?")
            .setPositiveButton("Allow & Merge") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.contactDao().findByNorm(d.normalizedPhone)?.let {
                        db.contactDao().update(it.copy(name = "${it.name} / ${d.incomingName}"))
                    }
                    db.duplicateDao().delete(d)
                }
            }
            .setNegativeButton("Reject") { _, _ -> lifecycleScope.launch(Dispatchers.IO) { db.duplicateDao().delete(d) } }
            .show()
    }

    private fun showAddDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }
        val etN = EditText(this).apply { hint = "Name" }
        val etP = EditText(this).apply { hint = "Phone Number"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val etE = EditText(this).apply { hint = "Email (Optional)" }
        box.addView(etN); box.addView(etP); box.addView(etE)

        AlertDialog.Builder(this).setTitle("Add Contact").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = etN.text.toString().trim(); val p = etP.text.toString().trim(); val e = etE.text.toString().trim()
                val norm = PhoneNorm.clean(p)
                if (n.isNotEmpty() && norm.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val ex = db.contactDao().findByNorm(norm)
                        if (ex != null) {
                            db.duplicateDao().insert(PendingDuplicateEntity(incomingName = n, incomingPhone = p, normalizedPhone = norm, existingName = ex.name, existingPhone = ex.phoneNumber, email = e))
                        } else {
                            db.contactDao().insert(ContactEntity(name = n, phoneNumber = p, normalizedPhone = norm, email = e))
                        }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showExportDialog() {
        val items = arrayOf("Excel (.xls) — Share / WhatsApp / Mail", "PDF (.pdf) — Share / WhatsApp / Mail", "ZIP Backup (Excel + PDF) — Mail / WhatsApp")
        AlertDialog.Builder(this).setTitle("Export All Contacts").setItems(items) { _, w ->
            lifecycleScope.launch {
                val list = withContext(Dispatchers.IO) { db.contactDao().getAllSnapshot() }
                if (list.isEmpty()) return@launch
                when (w) {
                    0 -> ExportEngine.share(this@MainActivity, ExportEngine.makeExcel(this@MainActivity, list), "application/vnd.ms-excel")
                    1 -> ExportEngine.share(this@MainActivity, ExportEngine.makePdf(this@MainActivity, list), "application/pdf")
                    2 -> ExportEngine.share(this@MainActivity, ExportEngine.makeZip(this@MainActivity, list), "application/zip")
                }
            }
        }.show()
    }

    // ==================== 5. PERMISSION-GATED PHONE-TO-PHONE SYNC ====================

    private fun showSyncDialog() {
        val opts = arrayOf("1. Existing Phone (Host — Approve Requests)", "2. New Phone (Send Request to Existing Phone)")
        AlertDialog.Builder(this).setTitle("Secure Phone-to-Phone Sync").setItems(opts) { _, w ->
            val client = Nearby.getConnectionsClient(this)
            client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery()
            if (w == 0) {
                isHost = true
                client.startAdvertising("${Build.MODEL} (Host)", serviceId, connCallback, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                Toast.makeText(this, "Waiting for new phone sync request...", Toast.LENGTH_LONG).show()
            } else {
                isHost = false
                client.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
                    override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                        client.requestConnection("${Build.MODEL} (New)", id, connCallback)
                    }
                    override fun onEndpointLost(id: String) {}
                }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                Toast.makeText(this, "Searching for existing phone...", Toast.LENGTH_LONG).show()
            }
        }.show()
    }

    private val connCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            val client = Nearby.getConnectionsClient(this@MainActivity)
            if (isHost) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🔐 Permission Request")
                    .setMessage("'${info.endpointName}' wants to copy your PB PRO contacts.\nCode: ${info.authenticationDigits}\n\nAllow transfer?")
                    .setCancelable(false)
                    .setPositiveButton("Allow & Send") { _, _ -> client.acceptConnection(id, payloadCallback) }
                    .setNegativeButton("Deny") { _, _ -> client.rejectConnection(id) }
                    .show()
            } else {
                client.acceptConnection(id, payloadCallback)
            }
        }

        override fun onConnectionResult(id: String, res: ConnectionResolution) {
            if (res.status.isSuccess && isHost) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val json = gson.toJson(db.contactDao().getAllSnapshot())
                    Nearby.getConnectionsClient(this@MainActivity).sendPayload(id, Payload.fromBytes(json.toByteArray()))
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "All contacts sent!", Toast.LENGTH_LONG).show() }
                }
            }
        }

        override fun onDisconnected(id: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val json = String(payload.asBytes() ?: return)
            lifecycleScope.launch(Dispatchers.IO) {
                val type = object : TypeToken<List<ContactEntity>>() {}.type
                val items: List<ContactEntity> = gson.fromJson(json, type)
                var added = 0
                for (c in items) {
                    val norm = PhoneNorm.clean(c.phoneNumber)
                    if (norm.isNotEmpty() && db.contactDao().findByNorm(norm) == null) {
                        db.contactDao().insert(ContactEntity(name = c.name, phoneNumber = c.phoneNumber, normalizedPhone = norm, email = c.email))
                        added++
                    }
                }
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Received $added contacts!", Toast.LENGTH_LONG).show() }
            }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }
}

// ==================== 6. HIGH-SPEED RECYCLERVIEW ADAPTER ====================

class FastContactAdapter(
    private val onCall: (ContactEntity) -> Unit,
    private val onSms: (ContactEntity) -> Unit,
    private val onSelectChange: (Int) -> Unit
) : ListAdapter<ContactEntity, FastContactAdapter.VH>(object : DiffUtil.ItemCallback<ContactEntity>() {
    override fun areItemsTheSame(a: ContactEntity, b: ContactEntity) = a.id == b.id
    override fun areContentsTheSame(a: ContactEntity, b: ContactEntity) = a == b
}) {
    private val selected = LinkedHashSet<Long>()
    fun getSelected() = currentList.filter { selected.contains(it.id) }
    fun selectAll() { currentList.forEach { selected.add(it.id) }; notifyDataSetChanged(); onSelectChange(selected.size) }
    fun clearSelection() { selected.clear(); notifyDataSetChanged(); onSelectChange(0) }
    fun isAllSelected() = currentList.isNotEmpty() && selected.size == currentList.size

    class VH(val root: LinearLayout, val cb: CheckBox, val tvName: TextView, val tvPhone: TextView, val btnCall: Button, val btnSms: Button) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH {
        val ctx = p.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 18, 16, 18)
            background = GradientDrawable().apply { setColor(Color.parseColor("#131B2E")); cornerRadius = 20f }
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14 }
        }
        val cb = CheckBox(ctx)
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 12 }
        }
        val tvN = TextView(ctx).apply { textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        val tvP = TextView(ctx).apply { textSize = 13f; setTextColor(Color.parseColor("#00F0FF")) }
        info.addView(tvN); info.addView(tvP)

        val bCall = Button(ctx).apply { text = "Call"; textSize = 11f; setTextColor(Color.BLACK); setBackgroundColor(Color.parseColor("#10B981")) }
        val bSms = Button(ctx).apply {
            text = "SMS"; textSize = 11f; setTextColor(Color.BLACK); setBackgroundColor(Color.parseColor("#00F0FF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 10 }
        }
        row.addView(cb); row.addView(info); row.addView(bCall); row.addView(bSms)
        return VH(row, cb, tvN, tvP, bCall, bSms)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        h.tvName.text = item.name
        h.tvPhone.text = if (item.email.isNotBlank()) "${item.phoneNumber} • ${item.email}" else item.phoneNumber
        h.cb.setOnCheckedChangeListener(null)
        h.cb.isChecked = selected.contains(item.id)
        h.cb.setOnCheckedChangeListener { _, chk ->
            if (chk) selected.add(item.id) else selected.remove(item.id)
            onSelectChange(selected.size)
        }
        h.root.setOnClickListener { h.cb.isChecked = !h.cb.isChecked }
        h.btnCall.setOnClickListener { onCall(item) }
        h.btnSms.setOnClickListener { onSms(item) }
    }
}
