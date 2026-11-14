package com.zettabridge.launcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Per-plugin permission checks layered over the installer's real Android permission state. */
final class PluginPermissionManager {
    static final String ACTION_REQUEST_PERMISSIONS =
            "android.content.pm.action.REQUEST_PERMISSIONS";
    private static final String ACTION_APPLICATION_DETAILS_SETTINGS =
            "android.settings.APPLICATION_DETAILS_SETTINGS";
    static final String EXTRA_REQUEST_PERMISSIONS_NAMES =
            "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    static final String EXTRA_REQUEST_PERMISSIONS_RESULTS =
            "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS";
    private static final String EXTRA_REQUEST_PERMISSIONS_DEVICE_ID =
            "android.content.pm.extra.REQUEST_PERMISSIONS_DEVICE_ID";

    private static final Set<String> SUPPORTED_RUNTIME_PERMISSIONS = new HashSet<>(Arrays.asList(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ACTIVITY_RECOGNITION"
    ));

    private PluginPermissionManager() {}

    static boolean isSupportedRuntimePermission(Context context, String permission) {
        if (context == null || permission == null || !SUPPORTED_RUNTIME_PERMISSIONS.contains(permission)) {
            return false;
        }

        int sdk = Build.VERSION.SDK_INT;
        if ("android.permission.READ_EXTERNAL_STORAGE".equals(permission) && sdk > 32) return false;
        if ("android.permission.WRITE_EXTERNAL_STORAGE".equals(permission) && sdk > 28) return false;
        if (("android.permission.READ_MEDIA_IMAGES".equals(permission)
                || "android.permission.READ_MEDIA_VIDEO".equals(permission)
                || "android.permission.READ_MEDIA_AUDIO".equals(permission)) && sdk < 33) return false;
        if ("android.permission.READ_MEDIA_VISUAL_USER_SELECTED".equals(permission) && sdk < 34) return false;
        if (("android.permission.BLUETOOTH_SCAN".equals(permission)
                || "android.permission.BLUETOOTH_CONNECT".equals(permission)
                || "android.permission.BLUETOOTH_ADVERTISE".equals(permission)) && sdk < 31) return false;
        if ("android.permission.NEARBY_WIFI_DEVICES".equals(permission) && sdk < 33) return false;
        if ("android.permission.ACTIVITY_RECOGNITION".equals(permission) && sdk < 29) return false;
        if ("android.permission.POST_NOTIFICATIONS".equals(permission) && sdk < 33) return false;

        // Never ask Android to grant a permission that this launcher APK did not actually declare.
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return false;
            for (String declared : info.requestedPermissions) {
                if (permission.equals(declared)) return true;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
        return false;
    }

    static boolean isDangerousPermission(Context context, String permission) {
        if (context == null || permission == null) return false;
        try {
            PermissionInfo info = context.getPackageManager().getPermissionInfo(permission, 0);
            return (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                    == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    static int checkSelfPermission(PluginContext context, LoadedPlugin plugin, String permission) {
        if (context == null || plugin == null || permission == null
                || !plugin.record.hasRequestedPermission(permission)) {
            return PackageManager.PERMISSION_DENIED;
        }

        Context hostContext = context.getBaseContext();
        if (isDangerousPermission(hostContext, permission)) {
            if (!isSupportedRuntimePermission(hostContext, permission)
                    || !plugin.record.hasGrantedPermission(permission)) {
                return PackageManager.PERMISSION_DENIED;
            }
        }

        // The actual Android grant is always authoritative, including revocation in Settings.
        return hostContext.checkSelfPermission(permission);
    }

    static int checkPermission(PluginContext context, LoadedPlugin plugin,
                               String permission, int pid, int uid) {
        if (pid == android.os.Process.myPid() && uid == android.os.Process.myUid()) {
            return checkSelfPermission(context, plugin, permission);
        }
        return context.getBaseContext().checkPermission(permission, pid, uid);
    }

    /**
     * Android Settings cannot show an imported APK as an installed package. Map its app-details
     * deep link to ZettaBridge's per-plugin permission screen instead.
     */
    static Intent redirectApplicationDetails(GuestRuntime runtime, Intent intent) {
        if (intent == null || !ACTION_APPLICATION_DETAILS_SETTINGS.equals(intent.getAction())) return null;
        Uri data = intent.getData();
        if (data == null || !"package".equals(data.getScheme())) return null;

        LoadedPlugin plugin = runtime.current();
        if (plugin == null || plugin.application == null
                || !plugin.packageName.equals(data.getSchemeSpecificPart())) return null;

        Context context = plugin.application.getBaseContext();
        if (context instanceof PluginContext) {
            context = ((PluginContext) context).getBaseContext();
        }
        Intent permissions = PluginPermissionsActivity.intent(context, plugin.packageName);
        permissions.setFlags(intent.getFlags());
        return permissions;
    }

    /**
     * A guest cannot turn its permission request into a launcher-wide Android grant.
     * Its result is computed from the guest's own grant state; users enable access from the
     * ZettaBridge Permissions screen, which uses the real Android permission UI when needed.
     */
    static Instrumentation.ActivityResult interceptRequest(GuestRuntime runtime, Intent intent) {
        if (intent == null || !ACTION_REQUEST_PERMISSIONS.equals(intent.getAction())) return null;

        LoadedPlugin plugin = runtime.current();
        if (plugin == null || plugin.application == null) return null;

        String[] requested = intent.getStringArrayExtra(EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (requested == null) return null;

        int[] results = new int[requested.length];
        boolean deniedAny = false;
        for (int i = 0; i < requested.length; i++) {
            results[i] = plugin.application.checkSelfPermission(requested[i]);
            deniedAny |= results[i] != PackageManager.PERMISSION_GRANTED;
        }

        Intent data = new Intent()
                .putExtra(EXTRA_REQUEST_PERMISSIONS_NAMES, requested)
                .putExtra(EXTRA_REQUEST_PERMISSIONS_RESULTS, results);
        if (intent.hasExtra(EXTRA_REQUEST_PERMISSIONS_DEVICE_ID)) {
            data.putExtra(EXTRA_REQUEST_PERMISSIONS_DEVICE_ID,
                    intent.getIntExtra(EXTRA_REQUEST_PERMISSIONS_DEVICE_ID, Context.DEVICE_ID_DEFAULT));
        }

        if (deniedAny) {
            Toast.makeText(plugin.application,
                    "Permission not enabled for this app. Open ZettaBridge > "
                            + plugin.label + " > Permissions.",
                    Toast.LENGTH_LONG).show();
        }
        return new Instrumentation.ActivityResult(Activity.RESULT_OK, data);
    }
}
