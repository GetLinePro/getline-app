## Privacy Policy — GetLine VPN (Android)

**Last updated:** 2026-08-25

This document describes how the **GetLine VPN** Android app handles information. It applies to the mobile client (`pro.getline.vpn`). The broader GetLine online service is also covered by the service policy at [https://getline.pro/privacy.html](https://getline.pro/privacy.html).

Contact: [support@getline.pro](mailto:support@getline.pro) · Telegram [@GetLinePro](https://t.me/GetLinePro)

### What this app is

GetLine VPN is a local Android VPN client. It:

- imports and stores VPN profile / subscription data on the device;
- starts a system VPN session (Android `VpnService`) and routes traffic according to the active profile;
- can open the GetLine web cabinet (`https://app.getline.pro/`) in a browser or Custom Tab so you can manage your account on the website.

Opening the cabinet does **not** by itself import a subscription into the app. You still need to add a subscription URL (or QR / import path that yields one) for VPN profiles.

The app is based on open-source CMFA and Mihomo components (GPLv3). Using those components does not mean third-party project authors operate GetLine.

### Information the app processes

**On the device (local storage)**

- Imported subscription URLs and downloaded configuration profiles
- App preferences (theme, routing overrides, selected profile, etc.)
- Local diagnostic logs, which include:
  - **Automatic crash capture:** after a process crash, the crash screen reads filtered Android logcat output (`SystemLogcat.dumpCrash`) and shows it for optional copy/share. That dump is created without a separate user “start logging” action; it remains on the device unless you export or share it.
  - **Manual VPN / service logs:** the in-app log recorder starts only when you open/start it, and history can be deleted or exported from the app.

These items stay on the device unless you export them, share them, or trigger a network action that sends them (for example refreshing a subscription).

**Over the network**

1. **Subscription / profile refresh**  
   When you add or update a subscription, the app requests the subscription URL you provided (typically a GetLine provider endpoint). The request includes a standard client identifier (`User-Agent`) for compatibility. On that same request the app may send an app-generated device identifier — a random GUID created and stored locally, not derived from the device, hardware, or your account — plus OS and model strings, so the GetLine panel can count this installation against the subscription device limit. This is not analytics. The response can include proxy nodes and optional subscription metadata (for example traffic counters or expiry from the `subscription-userinfo` header).

2. **VPN traffic**  
   While connected, application network traffic is carried through the VPN tunnel to the nodes and routes defined by your profile. Operators of those nodes (and any destination sites) may see connection metadata and content according to the protocols in use. GetLine service-side handling of accounts and payments is described in the service privacy policy.

3. **Account / support links**  
   Opening Support, Privacy, or Account from the app loads third-party destinations you choose (Telegram support, GetLine web pages). Those services process data under their own policies.

4. **QR import (optional)**  
   Scanning a profile QR code uses Android CameraX and the ZXing C++ barcode reader. Camera frames are decoded locally on the device: the scanner does not save or transmit the images, and returns only the decoded QR content to the app's normal import flow. Camera access is optional and this path is not required for URL paste import or core VPN operation.

### What this app does **not** do (in this fork)

- It does **not** embed a first-party App Center, Firebase Analytics, or Firebase Crashlytics product-analytics/crash-reporting integration for GetLine.
- It does **not** require Google Play Services for core VPN connect/disconnect when you import a subscription URL without QR scanning.
- It does **not** upload crash dumps or VPN log history to GetLine servers automatically. Local logs leave the device only if you export or share them.

If you install the app from Google Play or another store, that store may process install and update data under its own privacy policy.

### Permissions (high level)

Typical permissions are used for VPN service, network access, notifications (status), optional camera for QR import, and related Android features. Exact permissions are listed in the app manifest.

### Children

The app is not directed at children under 13. Do not use the service if you are not allowed to under applicable law.

### Changes

We may update this policy when app behavior changes. The current version is published with the source repository and may also be linked from GetLine sites.

### Contact

Questions about this Android app policy: **support@getline.pro** or [https://t.me/GetLinePro](https://t.me/GetLinePro).

For the online service (accounts, payments, website): see [https://getline.pro/privacy.html](https://getline.pro/privacy.html).
