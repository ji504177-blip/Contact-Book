# PB PRO — Smart Phone Book & Contact Sync App

## Features Included
1. **Zero-Command Auto-Copy (`ContactAutoSyncManager`)**: Automatically monitors `ContactsContract` and copies every existing and newly saved phone number into PB PRO's high-speed Room database in real time.
2. **Duplicate Detection & Permission Gate**: Automatically detects duplicate phone numbers and asks for your explicit approval before merging/adding.
3. **One-Tap Call & Direct/Bulk SMS**: Call or SMS any individual contact, or tap **Select All** to send a single group SMS to all contacts at once.
4. **Multi-Format Export & Share**: Export all contacts with full details as **Excel (`.xls`)**, **PDF (`.pdf`)**, or **ZIP archive** and share directly via **WhatsApp** or **Email**.
5. **Permission-Gated Phone-to-Phone Sync**: Install PB PRO on a new phone, send a sync request to your existing phone, and transfer all numbers only after the existing phone approves the request.
6. **Custom PB PRO App Icon**: Pre-configured across all Android launcher resolutions (`mdpi` to `xxxhdpi`).

## How to Build APK via GitHub Actions (No PC Software Required)
1. Extract this ZIP file.
2. Create a new repository on [GitHub](https://github.com) named `PB-PRO`.
3. Upload all extracted files and folders (including the `.github` folder) into the repository.
4. Open the **Actions** tab in your GitHub repository — it will automatically compile `app-debug.apk`.
5. Download **`PB-PRO-App-APK`** from the **Artifacts** section and install it on your Android phone!
