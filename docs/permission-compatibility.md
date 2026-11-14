# Plugin permission compatibility

## What this layer does

Imported APKs are parsed with `GET_PERMISSIONS`, so ZettaBridge retains the original
`<uses-permission>` list. The launcher manifest declares the common Android runtime
permissions needed by legacy and modern APKs. This is necessary because the guest APK is
not installed as an Android package: Android only knows the launcher package when it
evaluates permission requests.

In the `:guest` process:

- `PluginContext.checkSelfPermission()`, `checkPermission()` and
  `checkCallingOrSelfPermission()` deny permissions that the current APK did not declare.
- `PackageManager.checkPermission()` and `checkUidPermission()` expose the same
  manifest-scoped result for the active plugin.
- `PackageInfo.requestedPermissions` and `requestedPermissionsFlags` are populated
  from the APK manifest and the launcher's actual grant state.
This covers the usual camera, microphone, foreground location, media, contacts,
calendar, phone, SMS, notification, sensor and nearby-device permission checks.
The standard Android permission dialog is still the platform dialog for **ZettaBridge**.
The user must approve the permission when Android requests it.

## Important platform boundary

This is compatibility mediation, **not per-plugin Android security isolation**. All
plugins execute inside ZettaBridge's process and UID. Android records runtime grants
against the installed ZettaBridge package, not against the imported APK. A permission
granted to ZettaBridge is therefore a host-UID grant; the Java checks above prevent
ordinary plugin code from observing undeclared grants, but they cannot stop native code
or a direct Binder/service call from using a permission already granted to the host.
Only import and run APKs you trust.

The host manifest must declare a permission before Android can grant it. The current
declaration list is an interoperability superset; it does not mean every permission is
automatically granted. Most dangerous permissions still require a platform prompt.

## Current limits

- `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, `MANAGE_EXTERNAL_STORAGE`,
  `SCHEDULE_EXACT_ALARM` and battery-optimization exemption are special app access.
  Merely declaring them is insufficient; dedicated Settings screens and return-state
  handling are not implemented yet.
- Background location, background sensors, phone/SMS and call-log access remain subject
  to Android version, role, installer and policy restrictions. Some are not grantable to
  an ordinary sideloaded launcher.
- Android displays ZettaBridge as the requesting app. The launcher cannot truthfully
  present the imported APK as an installed package because it has no package identity
  or UID of its own.
- Android behavior is still keyed to the launcher's installed target SDK for framework
  enforcement. The plugin's parsed `targetSdkVersion` and manifest are exposed to
  compatibility code, but this does not reproduce every historical platform behavior.
- This layer does not yet virtualize `AppOpsManager` attribution, notification permission
  state, role ownership, package installer APIs, URI grants, or runtime permission settings
  per plugin.

## Next compatibility priorities

1. Add explicit Settings flows for special app access and validate return state.
2. Virtualize permission rationale and permission flags consistently for all package-info
   query overloads and Android releases.
3. Add app-op attribution and tests on physical devices for camera, microphone and location.
4. Improve the package/component surface (services, broadcast receivers, providers and
   content resolver authorities), then storage/URI grants and notifications.
5. Expand native compatibility with Android API-level, syscall, EGL/GLES, sensor and
   audio regression tests.

A build passing on the host is not evidence that Android's permission controller or vendor
permission UI works on a device. Device acceptance must exercise both grant and deny paths,
including a second request after denial and a process restart.
