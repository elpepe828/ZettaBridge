package org.zettabridge.test.metadata;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.TextView;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class Probe extends Activity {
    private static final String PACKAGE = "org.zettabridge.test.metadata";
    private static final String KEY = "org.zettabridge.test.MARKER";
    private static String startupResult = "provider did not run";
    private static boolean applicationCreated;

    private static void verify(Context context, String packageName) throws PackageManager.NameNotFoundException {
        ServiceInfo service = context.getPackageManager().getServiceInfo(
                new ComponentName(packageName, MarkerService.class.getName()), PackageManager.GET_META_DATA);
        if (service.metaData == null || !"manifest-ok".equals(service.metaData.getString(KEY))) {
            throw new IllegalStateException("service metadata missing for " + packageName);
        }
    }

    public static final class App extends Application {
        @Override public void onCreate() {
            super.onCreate();
            applicationCreated = true;
        }
    }

    public static final class Startup extends ContentProvider {
        @Override public boolean onCreate() {
            if (applicationCreated) {
                startupResult = "provider ran after Application.onCreate";
                return true;
            }
            final Context context = getContext();
            FutureTask<Void> query = new FutureTask<>(() -> {
                verify(context, context.getPackageName());
                verify(context, PACKAGE);
                return null;
            });
            Thread worker = new Thread(query, "metadata-probe-worker");
            worker.setDaemon(true);
            worker.start();
            try {
                query.get(3, TimeUnit.SECONDS);
                startupResult = null;
            } catch (Exception failure) {
                query.cancel(true);
                startupResult = "provider worker: " + failure;
            }
            return true;
        }

        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }

    public static final class MarkerService extends Service {
        @Override public IBinder onBind(Intent intent) { return null; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String message;
        try {
            if (startupResult != null) throw new IllegalStateException(startupResult);
            if (!applicationCreated) throw new IllegalStateException("Application.onCreate did not run");
            verify(this, getPackageName());
            verify(this, PACKAGE);
            message = "PASS: provider and activity read their service metadata.";
        } catch (Exception failure) {
            message = "FAIL: " + failure.getMessage();
        }
        TextView text = new TextView(this);
        text.setPadding(32, 32, 32, 32);
        text.setTextSize(20);
        text.setText(message);
        setContentView(text);
    }
}
