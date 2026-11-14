package com.zettabridge.launcher;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Permission status and user-consent screen for an imported APK.
 *
 * Android permission grants remain attached to the installed ZettaBridge package. This screen
 * never claims to grant a permission to an isolated plugin; it displays the plugin's declaration
 * beside the host package's actual runtime/special-access state.
 */
public final class PluginPermissionsActivity extends Activity {
    private static final String EXTRA_PACKAGE = "com.zettabridge.launcher.PERMISSION_PACKAGE";
    private static final int REQUEST_RUNTIME_PERMISSION = 4101;

    private PluginRecord record;
    private PackageInfo pluginInfo;
    private Set<String> hostPermissions = new HashSet<>();
    private LinearLayout list;

    static Intent intent(android.content.Context context, String packageName) {
        return new Intent(context, PluginPermissionsActivity.class).putExtra(EXTRA_PACKAGE, packageName);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        record = PluginStore.find(this, packageName);
        if (record == null || !record.apk().isFile()) {
            Toast.makeText(this, "Imported APK not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            pluginInfo = getPackageManager().getPackageArchiveInfo(record.apk().getAbsolutePath(),
                    PluginStore.ARCHIVE_FLAGS);
            PackageInfo hostInfo = getPackageManager().getPackageInfo(getPackageName(),
                    PackageManager.GET_PERMISSIONS);
            if (hostInfo.requestedPermissions != null) {
                hostPermissions.addAll(Arrays.asList(hostInfo.requestedPermissions));
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Toast.makeText(this, "Cannot read permission metadata: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        buildScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) refreshRows();
    }

    private void buildScreen() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText(record.label + " — Permissions");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText(
                "This list shows permissions declared by the imported APK. Android grants access "
                + "to ZettaBridge, not to a separate installed copy of this APK. A grant shown here "
                + "is therefore shared by the ZettaBridge host process and is not an isolated "
                + "per-plugin Android grant. The guest compatibility layer filters supported "
                + "Java permission checks against this APK's manifest.");
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        explanation.setPadding(0, dp(10), 0, dp(14));
        root.addView(explanation);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> finish());
        root.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refreshRows();
    }

    private void refreshRows() {
        list.removeAllViews();
        String[] requested = pluginInfo != null ? pluginInfo.requestedPermissions : null;
        if (requested == null || requested.length == 0) {
            addText("This APK does not declare any permissions.", 15, true);
            return;
        }

        String[] sorted = requested.clone();
        Arrays.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        for (String permission : sorted) {
            if (permission != null && !permission.isEmpty()) addPermissionRow(permission);
        }
    }

    private void addPermissionRow(String permission) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView name = new TextView(this);
        name.setText(permissionLabel(permission));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(name);

        TextView id = new TextView(this);
        id.setText(permission);
        id.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        card.addView(id);

        TextView status = new TextView(this);
        status.setText(permissionStatus(permission));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(0, dp(4), 0, dp(4));
        card.addView(status);

        Button action = new Button(this);
        configureAction(action, permission);
        card.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView divider = new TextView(this);
        divider.setHeight(dp(1));
        divider.setBackgroundColor(0x33808080);
        list.addView(divider);
    }

    private String permissionStatus(String permission) {
        if (!hostPermissions.contains(permission)) {
            return "Unavailable: this permission is not declared by ZettaBridge's host manifest.";
        }

        String special = specialPermissionStatus(permission);
        if (special != null) return special;

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            String appOp = appOpRestriction(permission);
            return appOp == null ? "Granted to ZettaBridge (host-level grant)." :
                    "Host permission granted, but AppOps reports it blocked: " + appOp;
        }

        if (isDangerousPermission(permission)) {
            return "Not granted to ZettaBridge. The Android runtime prompt can be requested below.";
        }
        return "Not granted. Android may require a system role, installer privilege, or special access.";
    }

    /** Returns null for ordinary runtime/normal permissions, otherwise a special-access status. */
    private String specialPermissionStatus(String permission) {
        switch (permission) {
            case "android.permission.SYSTEM_ALERT_WINDOW":
                return Settings.canDrawOverlays(this)
                        ? "Special access enabled for ZettaBridge."
                        : "Special access not enabled for ZettaBridge.";
            case "android.permission.WRITE_SETTINGS":
                return Settings.System.canWrite(this)
                        ? "Special access enabled for ZettaBridge."
                        : "Special access not enabled for ZettaBridge.";
            case "android.permission.MANAGE_EXTERNAL_STORAGE":
                if (Build.VERSION.SDK_INT < 30) return "Not applicable on this Android version.";
                return Environment.isExternalStorageManager()
                        ? "All-files special access enabled for ZettaBridge."
                        : "All-files special access not enabled for ZettaBridge.";
            case "android.permission.SCHEDULE_EXACT_ALARM":
                if (Build.VERSION.SDK_INT < 31) return "Not applicable on this Android version.";
                AlarmManager alarms = getSystemService(AlarmManager.class);
                return alarms != null && alarms.canScheduleExactAlarms()
                        ? "Exact-alarm access enabled for ZettaBridge."
                        : "Exact-alarm access not enabled for ZettaBridge.";
            case "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS":
                if (Build.VERSION.SDK_INT < 23) return "Not applicable on this Android version.";
                PowerManager power = getSystemService(PowerManager.class);
                return power != null && power.isIgnoringBatteryOptimizations(getPackageName())
                        ? "Battery-optimization exemption enabled for ZettaBridge."
                        : "Battery-optimization exemption not enabled for ZettaBridge.";
            default:
                return null;
        }
    }

    private void configureAction(Button button, String permission) {
        if (!hostPermissions.contains(permission)) {
            button.setText("Unavailable");
            button.setEnabled(false);
            return;
        }

        if (isSpecialPermission(permission)) {
            button.setText("Open Android settings");
            button.setOnClickListener(v -> openSpecialAccessSettings(permission));
            return;
        }

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            button.setText("Granted");
            button.setEnabled(false);
            return;
        }

        if (isDangerousPermission(permission)) {
            button.setText("Request Android permission");
            button.setOnClickListener(v -> requestRuntimePermission(permission));
            return;
        }

        button.setText("Managed by Android");
        button.setEnabled(false);
    }

    private void requestRuntimePermission(String permission) {
        if (!hostPermissions.contains(permission) || !isDangerousPermission(permission)) {
            Toast.makeText(this, "This permission cannot be requested through the runtime dialog.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        requestPermissions(new String[] {permission}, REQUEST_RUNTIME_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RUNTIME_PERMISSION) {
            refreshRows();
        }
    }

    private void openSpecialAccessSettings(String permission) {
        Intent intent = specialSettingsIntent(permission);
        if (intent == null) return;
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(fallback);
            } catch (RuntimeException fallbackError) {
                Toast.makeText(this, "Android settings could not be opened: "
                        + fallbackError.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private Intent specialSettingsIntent(String permission) {
        Uri hostUri = Uri.parse("package:" + getPackageName());
        switch (permission) {
            case "android.permission.SYSTEM_ALERT_WINDOW":
                return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, hostUri);
            case "android.permission.WRITE_SETTINGS":
                return new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, hostUri);
            case "android.permission.MANAGE_EXTERNAL_STORAGE":
                return Build.VERSION.SDK_INT >= 30
                        ? new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, hostUri)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, hostUri);
            case "android.permission.SCHEDULE_EXACT_ALARM":
                return Build.VERSION.SDK_INT >= 31
                        ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, hostUri)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, hostUri);
            case "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS":
                return Build.VERSION.SDK_INT >= 23
                        ? new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, hostUri)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, hostUri);
            default:
                return null;
        }
    }

    private boolean isSpecialPermission(String permission) {
        return "android.permission.SYSTEM_ALERT_WINDOW".equals(permission)
                || "android.permission.WRITE_SETTINGS".equals(permission)
                || "android.permission.MANAGE_EXTERNAL_STORAGE".equals(permission)
                || "android.permission.SCHEDULE_EXACT_ALARM".equals(permission)
                || "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS".equals(permission);
    }

    private boolean isDangerousPermission(String permission) {
        try {
            PermissionInfo info = getPackageManager().getPermissionInfo(permission, 0);
            return (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                    == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private String appOpRestriction(String permission) {
        String op = AppOpsManager.permissionToOp(permission);
        if (op == null) return null;
        AppOpsManager manager = getSystemService(AppOpsManager.class);
        if (manager == null) return null;
        try {
            int mode = manager.checkOpNoThrow(op, Process.myUid(), getPackageName());
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) return op;
        } catch (RuntimeException ignored) {
            // Some OEMs do not expose every permission-to-app-op mapping.
        }
        return null;
    }

    private String permissionLabel(String permission) {
        try {
            PermissionInfo info = getPackageManager().getPermissionInfo(permission, 0);
            CharSequence label = info.loadLabel(getPackageManager());
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Custom or unavailable permissions are shown by their manifest name.
        }
        int dot = permission.lastIndexOf('.');
        String simple = dot >= 0 ? permission.substring(dot + 1) : permission;
        return simple.replace('_', ' ');
    }

    private void addText(String text, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(8), 0, dp(8));
        list.addView(view);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
