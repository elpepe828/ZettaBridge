package com.zettabridge.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Per-imported-app permission controls. Runs in the launcher process, not :guest. */
public final class PluginPermissionsActivity extends Activity {
    private static final String EXTRA_PACKAGE =
            "com.zettabridge.launcher.permissions.PACKAGE";
    private static final int REQUEST_HOST_PERMISSION = 0x5A31;

    private String packageName;
    private String pendingPermission;
    private PluginRecord record;
    private LinearLayout rows;

    static Intent intent(Context context, String packageName) {
        return new Intent(context, PluginPermissionsActivity.class)
                .putExtra(EXTRA_PACKAGE, packageName);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (packageName == null || PluginStore.find(this, packageName) == null) {
            Toast.makeText(this, "Imported app not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView explanation = new TextView(this);
        explanation.setText(
                "Enable permissions separately for this imported app. A permission works only "
                + "when Android has granted it to ZettaBridge and this app has its own grant here. "
                + "Android Settings will still show ZettaBridge as the installed app.");
        explanation.setTextSize(14);
        root.addView(explanation);

        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (rows == null || packageName == null) return;
        record = PluginStore.find(this, packageName);
        rows.removeAllViews();
        if (record == null) {
            addInfo("This imported app is no longer available.");
            return;
        }
        if (record.requestedPermissions.isEmpty()) {
            addInfo("No permission list was saved for this APK. Re-import the APK to read its "
                    + "AndroidManifest.xml permissions.");
            return;
        }
        for (String permission : record.requestedPermissions) addPermissionRow(permission);
    }

    private void addPermissionRow(String permission) {
        boolean dangerous = PluginPermissionManager.isDangerousPermission(this, permission);
        boolean supported = dangerous
                && PluginPermissionManager.isSupportedRuntimePermission(this, permission);
        boolean androidGranted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        boolean guestGranted = record.hasGrantedPermission(permission);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView name = new TextView(this);
        name.setText(permissionLabel(permission));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextSize(16);
        row.addView(name);

        TextView detail = new TextView(this);
        detail.setText(permission + "\n" + permissionStatus(
                dangerous, supported, androidGranted, guestGranted));
        detail.setTextSize(12);
        row.addView(detail);

        if (supported) {
            Button action = new Button(this);
            action.setText(guestGranted ? "Revoke for this app" : "Allow for this app");
            action.setOnClickListener(v -> changeGuestGrant(permission));
            row.addView(action);
        } else {
            TextView unavailable = new TextView(this);
            unavailable.setText(dangerous
                    ? "Runtime permission is not supported by this ZettaBridge build."
                    : "Not managed here (normal, special, or unavailable permission).");
            unavailable.setTextSize(12);
            row.addView(unavailable);
        }

        View divider = new View(this);
        divider.setBackgroundColor(0x33000000);
        rows.addView(row);
        rows.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private String permissionStatus(boolean dangerous, boolean supported,
                                    boolean androidGranted, boolean guestGranted) {
        if (dangerous && !supported) return "Android permission: " + state(androidGranted)
                + " | ZettaBridge: unsupported";
        if (dangerous) {
            if (guestGranted && androidGranted) return "Android: allowed | This app: allowed";
            if (guestGranted) return "This app is enabled, but Android access is currently denied";
            if (androidGranted) return "Android: allowed | This app: blocked";
            return "Android: not granted | This app: blocked";
        }
        return androidGranted ? "Available through the host app Android permission"
                : "Not granted to ZettaBridge or not supported by Android";
    }

    private static String state(boolean granted) {
        return granted ? "allowed" : "not granted";
    }

    private String permissionLabel(String permission) {
        try {
            PermissionInfo info = getPackageManager().getPermissionInfo(permission, 0);
            CharSequence label = info.loadLabel(getPackageManager());
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Fall back to the manifest name.
        }
        int dot = permission.lastIndexOf('.');
        return permission.substring(dot + 1).replace('_', ' ').toLowerCase();
    }

    private void changeGuestGrant(String permission) {
        record = PluginStore.find(this, packageName);
        if (record == null || !record.hasRequestedPermission(permission)) {
            refresh();
            return;
        }
        if (record.hasGrantedPermission(permission)) {
            record.setPermissionGranted(permission, false);
            saveRecord("Permission revoked for this app. The Android grant was not changed.");
            return;
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            record.setPermissionGranted(permission, true);
            saveRecord("Permission enabled for this app.");
            return;
        }
        pendingPermission = permission;
        if ("android.permission.ACCESS_FINE_LOCATION".equals(permission)
                || "android.permission.ACCESS_COARSE_LOCATION".equals(permission)) {
            // Android 12+ requires coarse and fine to be requested together.
            requestPermissions(new String[] {
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.ACCESS_FINE_LOCATION"
            }, REQUEST_HOST_PERMISSION);
        } else {
            requestPermissions(new String[] {permission}, REQUEST_HOST_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_HOST_PERMISSION || pendingPermission == null) return;

        String permission = pendingPermission;
        pendingPermission = null;
        record = PluginStore.find(this, packageName);
        if (record == null) {
            refresh();
            return;
        }
        boolean grantedTarget = false;
        if (permissions != null && grantResults != null) {
            for (int i = 0; i < Math.min(permissions.length, grantResults.length); i++) {
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                record.setPermissionGranted(permissions[i], granted);
                if (permission.equals(permissions[i])) grantedTarget = granted;
            }
        }
        saveRecord(grantedTarget ? "Android and this app now have permission."
                : "Android did not grant the requested permission.");
    }

    private void saveRecord(String message) {
        try {
            record.save();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Could not save permission settings: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void addInfo(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setPadding(0, dp(16), 0, dp(16));
        rows.addView(text);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
