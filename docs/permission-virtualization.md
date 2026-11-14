# Permission virtualization

ZettaBridge does not install an imported APK as a separate Android package. The imported app's
Java code runs in the ZettaBridge process and therefore shares ZettaBridge's Android UID and
installed-package identity. Android's permission controller and Settings consequently cannot
present the imported app as an independent installed app.

This change adds a per-plugin permission boundary on top of that architecture:

1. During import, ZettaBridge records the permission names declared in the APK manifest.
2. A dangerous permission is available to guest Java code only when Android has granted it to
   ZettaBridge and the user has separately enabled it for this imported app in
   Library -> long-press app -> Permissions.
3. Revoking a guest grant removes only that app's ZettaBridge grant. It does not revoke the
   launcher's Android permission, which may be used by another imported app.
4. Revoking the Android permission in system Settings remains authoritative and makes the
   permission unavailable to every guest.
5. Guest calls to the platform runtime-permission request flow receive a result computed from
   that guest's virtual grant state. They cannot turn a guest request into a shared launcher-wide
   permission grant. Use the Permissions screen to enable access; if Android has not granted the
   permission yet, ZettaBridge requests it using the real Android permission UI.

The managed dangerous-permission set currently includes camera, microphone, foreground location,
contacts, calendar, legacy storage where the OS still supports it, Android 13+ media access,
notifications, Bluetooth nearby-device access, nearby Wi-Fi, and activity recognition. The
supported list is intentionally explicit and must stay aligned with the launcher's
uses-permission entries.

## Important limits

This is permission mediation, not a second Android package sandbox. Android's system UI still
identifies ZettaBridge as the permission owner. Special access controlled through AppOps or
Settings (for example overlay access, all-files access, exact alarms, and background-location
flows) is not virtualized by this change. Native code that talks directly to system services may
also bypass Java Context permission checks. Package/UID-specific services, independent app
settings, accounts, push registration, and package installation semantics remain outside the
current plugin architecture.

A permission is not considered granted merely because the launcher holds it. The explicit
per-plugin record and the real Android grant must both be present.
