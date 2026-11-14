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

    static int[] filterGrantResults(String[] requestedPermissions, String[] returnedPermissions,
                                    int[] grantResults) {
        if (grantResults == null) return null;
        int[] filtered = grantResults.clone();
        if (returnedPermissions == null) return filtered;
        int count = Math.min(returnedPermissions.length, filtered.length);
        for (int i = 0; i < count; i++) {
            if (!requests(requestedPermissions, returnedPermissions[i])) filtered[i] = -1;
        }
        return filtered;
    }
}
