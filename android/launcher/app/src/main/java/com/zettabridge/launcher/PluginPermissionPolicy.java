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

    /** Maps a virtual plugin package to the installed host package for Android binder calls. */
    static String mapPackageToHost(String requestedPackage, String hostPackage, String pluginPackage) {
        if (requestedPackage == null || hostPackage == null || pluginPackage == null) return null;
        if (hostPackage.equals(requestedPackage) || pluginPackage.equals(requestedPackage)) {
            return hostPackage;
        }
        return null;
    }

    /** Returns the first permission the plugin did not declare, or null when all are declared. */
    static String firstUndeclared(String[] declared, String[] requested) {
        if (requested == null || requested.length == 0) return null;
        for (String permission : requested) {
            if (!requests(declared, permission)) return permission != null ? permission : "<null>";
        }
        return null;
    }

}
