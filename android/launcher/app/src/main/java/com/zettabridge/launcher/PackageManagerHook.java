package com.zettabridge.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Package manager virtualization for the :guest process. Plugins run under the launcher's package
 * name, so SDKs that look up their own components or meta-data (Firebase component discovery,
 * AdMob/AppMetrica APPLICATION_ID, ...) would read the launcher's manifest. Queries for the
 * launcher package that name a plugin component, or ask for application/package info, are
 * answered from the plugin's parsed APK instead.
 */
final class PackageManagerHook implements InvocationHandler {
    private static final String TAG = "zb-launcher";

    private final Object real;
    private final String hostPackage;
    private final GuestRuntime runtime;

    private PackageManagerHook(Object real, String hostPackage, GuestRuntime runtime) {
        this.real = real;
        this.hostPackage = hostPackage;
        this.runtime = runtime;
    }

    static void install(Context host, GuestRuntime runtime) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object current = Reflect.method(activityThread, "getPackageManager").invoke(null);
            if (current == null || Proxy.isProxyClass(current.getClass())) return;
            Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
            Object proxy = Proxy.newProxyInstance(ipm.getClassLoader(), new Class<?>[] {ipm},
                    new PackageManagerHook(current, host.getPackageName(), runtime));
            Field field = Reflect.field(activityThread, "sPackageManager");
            field.set(null, proxy);
            // ContextImpl caches an ApplicationPackageManager that holds the binder proxy directly.
            PackageManager pm = host.getPackageManager();
            Reflect.trySet(pm.getClass(), pm, "mPM", proxy);
            disableCaches();
            Log.i(TAG, "package manager virtualization installed");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Diagnostics.report(host, "cannot install package manager virtualization", e, false);
        }
    }

    /** Android 11+ caches application/package info per process; stale entries would bypass the hook. */
    private static void disableCaches() {
        for (String name : new String[] {"disableApplicationInfoCache", "disablePackageInfoCache"}) {
            try {
                Reflect.method(PackageManager.class, name).invoke(null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // not present on this Android version
            }
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null && args.length > 0) {
            String name = method.getName();
            if ("checkPermission".equals(name) || "checkUidPermission".equals(name)) {
                return permissionCheck(method, args);
            }
            if ("getPermissionFlags".equals(name) || "shouldShowRequestPermissionRationale".equals(name)) {
                return permissionMetadata(method, args);
            }
        }
        try {
            Object result = method.invoke(real, args);
            return rewrite(method.getName(), args, result);
        } catch (InvocationTargetException e) {
            Object fallback = rewriteMissing(method.getName(), args);
            if (fallback != null) return fallback;
            throw e.getCause();
        }
    }

    private Object permissionCheck(Method method, Object[] args) throws Throwable {
        LoadedPlugin plugin = runtime.current();
        if (plugin == null || !(args[0] instanceof String)) return invokeReal(method, args);
        String permission = (String) args[0];
        if ("checkPermission".equals(method.getName()) && args.length > 1 && args[1] instanceof String) {
            String packageName = (String) args[1];
            if (hostPackage.equals(packageName) || plugin.packageName.equals(packageName)) {
                if (!plugin.requestsPermission(permission)) return PackageManager.PERMISSION_DENIED;
                Object[] mapped = args.clone();
                mapped[1] = hostPackage;
                return invokeReal(method, mapped);
            }
        }
        if ("checkUidPermission".equals(method.getName()) && args.length > 1
                && args[1] instanceof Integer && ((Integer) args[1]) == Process.myUid()
                && !plugin.requestsPermission(permission)) {
            return PackageManager.PERMISSION_DENIED;
        }
        return invokeReal(method, args);
    }

    private Object permissionMetadata(Method method, Object[] args) throws Throwable {
        LoadedPlugin plugin = runtime.current();
        if (plugin == null || !(args[0] instanceof String)) return invokeReal(method, args);
        String permission = (String) args[0];
        if (!plugin.requestsPermission(permission)) {
            return "shouldShowRequestPermissionRationale".equals(method.getName()) ? false : 0;
        }

        Object[] mapped = args.clone();
        if (mapped.length > 1 && mapped[1] instanceof String) {
            String packageName = (String) mapped[1];
            if (hostPackage.equals(packageName) || plugin.packageName.equals(packageName)) {
                mapped[1] = hostPackage;
            }
        }
        return invokeReal(method, mapped);
    }

    private Object invokeReal(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private boolean hostPermissionGranted(String permission) {
        for (Method candidate : real.getClass().getMethods()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (!"checkUidPermission".equals(candidate.getName()) || types.length != 2
                    || types[0] != String.class || types[1] != int.class) continue;
            try {
                Object result = candidate.invoke(real, permission, Process.myUid());
                return result instanceof Integer && ((Integer) result) == PackageManager.PERMISSION_GRANTED;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "cannot query host permission " + permission + ": " + e);
                return false;
            }
        }
        return false;
    }

    private Object rewrite(String name, Object[] args, Object result) {
        if (args == null || args.length == 0) return result;
        switch (name) {
            case "getServiceInfo":
            case "getActivityInfo":
            case "getReceiverInfo":
            case "getProviderInfo": {
                Object component = pluginComponent(name, args[0]);
                return component != null ? component : result;
            }
            case "queryIntentActivities": {
                // Explicit intents for a plugin activity: old AdMob refuses to show ads unless
                // queryIntentActivities finds AdActivity with the right configChanges.
                ResolveInfo resolved = pluginActivityResolve(args[0]);
                return resolved != null ? withResolved(result, resolved) : result;
            }
            case "resolveIntent": {
                if (result != null) return result;
                return pluginActivityResolve(args[0]);
            }
            case "getApplicationInfo":
                if (hostPackage.equals(args[0]) && result instanceof ApplicationInfo) {
                    return withPluginMetaData((ApplicationInfo) result);
                }
                return result;
            case "getPackageInfo":
                if (args[0] instanceof String && hostPackage.equals(args[0]) && result instanceof PackageInfo) {
                    return withPluginVersion((PackageInfo) result);
                }
                return result;
            default:
                return result;
        }
    }

    /** The real call threw (e.g. NameNotFound wrapped by the binder layer): answer plugin components anyway. */
    private Object rewriteMissing(String name, Object[] args) {
        if (args == null || args.length == 0) return null;
        switch (name) {
            case "getServiceInfo":
            case "getActivityInfo":
            case "getReceiverInfo":
            case "getProviderInfo":
                return pluginComponent(name, args[0]);
            default:
                return null;
        }
    }

    private Object pluginComponent(String method, Object arg) {
        if (!(arg instanceof ComponentName)) return null;
        ComponentName cn = (ComponentName) arg;
        if (!hostPackage.equals(cn.getPackageName()) && !isCurrentPluginPackage(cn.getPackageName())) return null;
        ComponentInfo info = runtime.findComponent(method, cn.getClassName());
        if (info == null) return null;
        if (info instanceof ServiceInfo) return new ServiceInfo((ServiceInfo) info);
        if (info instanceof ActivityInfo) return new ActivityInfo((ActivityInfo) info);
        if (info instanceof ProviderInfo) return new ProviderInfo((ProviderInfo) info);
        return info;
    }

    /** NativeActivity asks for its own intent's component, which names the plugin's package. */
    private boolean isCurrentPluginPackage(String packageName) {
        LoadedPlugin p = runtime.current();
        return p != null && p.info != null && p.info.packageName.equals(packageName);
    }

    private ResolveInfo pluginActivityResolve(Object arg) {
        if (!(arg instanceof Intent)) return null;
        Object info = pluginComponent("getActivityInfo", ((Intent) arg).getComponent());
        if (!(info instanceof ActivityInfo)) return null;
        ResolveInfo resolved = new ResolveInfo();
        resolved.activityInfo = (ActivityInfo) info;
        return resolved;
    }

    /** A query result (a ParceledListSlice, or a List on old releases) that holds `resolved` when it was empty. */
    private static Object withResolved(Object result, ResolveInfo resolved) {
        try {
            if (result instanceof List) {
                if (!((List<?>) result).isEmpty()) return result;
                List<ResolveInfo> list = new ArrayList<>();
                list.add(resolved);
                return list;
            }
            Class<?> slice = Class.forName("android.content.pm.ParceledListSlice");
            if (result != null && slice.isInstance(result)) {
                List<?> current = (List<?>) Reflect.method(slice, "getList").invoke(result);
                if (current != null && !current.isEmpty()) return result;
            }
            List<ResolveInfo> list = new ArrayList<>();
            list.add(resolved);
            return slice.getConstructor(List.class).newInstance(list);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot rewrite an activity query: " + e);
            return result;
        }
    }

    private ApplicationInfo withPluginMetaData(ApplicationInfo hostInfo) {
        LoadedPlugin p = runtime.current();
        if (p == null) return hostInfo;
        ApplicationInfo copy = new ApplicationInfo(hostInfo);
        copy.metaData = p.appInfo.metaData;
        return copy;
    }

    private PackageInfo withPluginVersion(PackageInfo hostInfo) {
        LoadedPlugin p = runtime.current();
        if (p == null) return hostInfo;
        PackageInfo copy = new PackageInfo(hostInfo);
        copy.versionName = p.info.versionName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            copy.setLongVersionCode(p.info.getLongVersionCode());
        } else {
            copy.versionCode = p.info.versionCode;
        }
        copy.requestedPermissions = p.info.requestedPermissions != null
                ? p.info.requestedPermissions.clone() : null;
        if (copy.requestedPermissions != null) {
            int[] flags = new int[copy.requestedPermissions.length];
            int[] archiveFlags = p.info.requestedPermissionsFlags;
            for (int i = 0; i < flags.length; i++) {
                if (archiveFlags != null && i < archiveFlags.length) {
                    flags[i] = archiveFlags[i] & ~PackageInfo.REQUESTED_PERMISSION_GRANTED;
                }
                if (hostPermissionGranted(copy.requestedPermissions[i])) {
                    flags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                }
            }
            copy.requestedPermissionsFlags = flags;
        } else {
            copy.requestedPermissionsFlags = null;
        }
        if (copy.applicationInfo != null) copy.applicationInfo = withPluginMetaData(copy.applicationInfo);
        return copy;
    }
}
