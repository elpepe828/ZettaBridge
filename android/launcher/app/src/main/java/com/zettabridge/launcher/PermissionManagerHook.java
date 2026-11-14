package com.zettabridge.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * PermissionManager binder compatibility for Android versions that route permission state
 * through android.permission.IPermissionManager rather than IPackageManager.
 *
 * The imported APK still has no Android package identity. Queries are mapped to the host package
 * only when the requested package is the active plugin or ZettaBridge and the active APK declared
 * the permission.
 */
final class PermissionManagerHook implements InvocationHandler {
    private static final String TAG = "zb-launcher";

    private final Object real;
    private final String hostPackage;
    private final GuestRuntime runtime;

    private PermissionManagerHook(Object real, String hostPackage, GuestRuntime runtime) {
        this.real = real;
        this.hostPackage = hostPackage;
        this.runtime = runtime;
    }

    static void install(Context host, GuestRuntime runtime) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object current = Reflect.method(activityThread, "getPermissionManager").invoke(null);
            if (current == null || Proxy.isProxyClass(current.getClass())) return;

            Class<?> permissionManager = Class.forName("android.permission.IPermissionManager");
            if (!permissionManager.isInstance(current)) {
                Log.w(TAG, "ActivityThread returned an unexpected permission manager: "
                        + current.getClass().getName());
                return;
            }

            Object proxy = Proxy.newProxyInstance(permissionManager.getClassLoader(),
                    new Class<?>[] {permissionManager},
                    new PermissionManagerHook(current, host.getPackageName(), runtime));
            Field field = Reflect.field(activityThread, "sPermissionManager");
            field.set(null, proxy);
            Log.i(TAG, "permission manager virtualization installed");
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
            // Older Android releases keep these methods on IPackageManager instead.
            Log.i(TAG, "separate permission manager is unavailable on this Android release: " + e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Diagnostics.report(host, "cannot install permission manager virtualization", e, false);
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args == null || args.length == 0) return invokeReal(method, args);

        LoadedPlugin plugin = runtime.current();
        if (plugin == null) return invokeReal(method, args);

        String name = method.getName();
        switch (name) {
            case "getPermissionFlags":
                return packagePermissionCall(method, args, plugin, 0, 1, 0);
            case "shouldShowRequestPermissionRationale":
                return packagePermissionCall(method, args, plugin, 0, 1, Boolean.FALSE);
            case "checkPermission":
                return packagePermissionCall(method, args, plugin, 0, 1, PackageManager.PERMISSION_DENIED);
            case "isPermissionRevokedByPolicy":
                return packagePermissionCall(method, args, plugin, 0, 1, Boolean.FALSE);
            case "checkUidPermission":
                if (args.length >= 2 && args[0] instanceof Integer && args[1] instanceof String
                        && ((Integer) args[0]) == android.os.Process.myUid()
                        && !plugin.requestsPermission((String) args[1])) {
                    return PackageManager.PERMISSION_DENIED;
                }
                return invokeReal(method, args);
            default:
                return invokeReal(method, args);
        }
    }

    /**
     * Package-based IPermissionManager calls use (packageName, permissionName, ...).
     * For a guest package, forward to the real service as the installed host package.
     */
    private Object packagePermissionCall(Method method, Object[] args, LoadedPlugin plugin,
                                         int packageIndex, int permissionIndex, Object undeclaredResult)
            throws Throwable {
        if (args.length <= Math.max(packageIndex, permissionIndex)
                || !(args[packageIndex] instanceof String)
                || !(args[permissionIndex] instanceof String)) {
            return invokeReal(method, args);
        }

        String requestedPackage = (String) args[packageIndex];
        String permission = (String) args[permissionIndex];
        String mappedPackage = PluginPermissionPolicy.mapPackageToHost(
                requestedPackage, hostPackage, plugin.packageName);
        if (mappedPackage == null) return invokeReal(method, args);

        if (!plugin.requestsPermission(permission)) return undeclaredResult;

        Object[] mapped = args.clone();
        mapped[packageIndex] = mappedPackage;
        return invokeReal(method, mapped);
    }

    private Object invokeReal(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
