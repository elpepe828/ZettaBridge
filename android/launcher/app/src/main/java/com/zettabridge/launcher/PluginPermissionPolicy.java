package com.zettabridge.launcher;

/**
 * Permission view presented to an imported APK.
 *
 * Android still grants permissions to the installed ZettaBridge package/UID. This policy makes
 * package-manager and Context permission checks reflect the imported APK's own manifest; it does
 * not create a separate Android UID or a security boundary between plugins.
 */
final class PluginPermissionPolicy {
    private PluginPermissionPolicy() {}

    static boolean requests(String[] requestedPermissions, String permission) {
        if (requestedPermissions == null || permission == null || permission.isEmpty()) return false;
        for (String requested : requestedPermissions) {
            if (permission.equals(requested)) return true;
        }
        return false;
    }

}