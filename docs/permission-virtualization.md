# Permission virtualization

ZettaBridge does not install an imported APK as a separate Android package. The imported app's
Java code runs in the ZettaBridge process and therefore shares ZettaBridge's Android UID and
installed-package identity. Android's permission controller and Settings consequently cannot
present the imported app as an independent installed app.

This change adds best-effort per-plugin permission mediation on top of that architecture. It also
makes package-info and UID lookups return the current imported app's metadata where the launcher
can safely emulate those APIs.

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
notifications, Bluetooth nearby-device access, nearby Wi-Fi, and activity recognition. Availability
is checked against both the Android API level and the launcher's installed manifest permissions,
so the broker does not request permissions that the host APK did not declare for that OS version.
The supported list must stay aligned with the launcher's uses-permission entries.

Guest apps that open Android's app-details Settings page for their own package are redirected to
the ZettaBridge per-plugin Permissions screen; Android cannot open a normal package-details page
for an APK that was not installed.

## Important limits

This is compatibility mediation, not a security boundary or a second Android package sandbox.
Android's system UI still identifies ZettaBridge as the permission owner. The imported app shares
the launcher's UID and process; system services receive the launcher's operation-package identity.
Consequently, a plugin can still reach APIs whose enforcement happens only inside a system service
rather than through Context.checkSelfPermission. The current change improves the normal Java
permission-check and request flow, but it does not provide enforceable isolation between plugins.
Do not treat the per-plugin toggles as a security sandbox.

Special access controlled through AppOps or Settings (for example overlay access, all-files access,
exact alarms, and background-location flows) is not virtualized by this change. Package/UID-specific
services, independent app settings, accounts, push registration, and package installation semantics
remain outside the current plugin architecture. The actual Android permission prompt is issued to
ZettaBridge and therefore uses the launcher's installed package identity and target SDK, not the
guest APK's independent target SDK.

A permission is considered available to guest Java permission checks only when the imported APK
declares it, the per-plugin record enables it, and Android has granted it to ZettaBridge. This
three-part check does not prevent every possible direct system-service access, as described above.
