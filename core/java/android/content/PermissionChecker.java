/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Binder;
import android.os.Process;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class provides permission check APIs that verify both the
 * permission and the associated app op for this permission if
 * such is defined.
 * <p>
 * In the new permission model permissions with protection level
 * dangerous are runtime permissions. For apps targeting {@link android.os.Build.VERSION_CODES#M}
 * and above the user may not grant such permissions or revoke
 * them at any time. For apps targeting API lower than {@link android.os.Build.VERSION_CODES#M}
 * these permissions are always granted as such apps do not expect
 * permission revocations and would crash. Therefore, when the
 * user disables a permission for a legacy app in the UI the
 * platform disables the APIs guarded by this permission making
 * them a no-op which is doing nothing or returning an empty
 * result or default error.
 * </p>
 * <p>
 * It is important that when you perform an operation on behalf of
 * another app you use these APIs to check for permissions as the
 * app may be a legacy app that does not participate in the new
 * permission model for which the user had disabled the "permission"
 * which is achieved by disallowing the corresponding app op.
 * </p>
 * <p>
 * This class has two types of methods and you should be careful which
 * type to call based on whether permission protected data is being
 * passed to the app or you are just checking whether the app holds a
 * permission. The reason is that a permission check requires checking
 * the runtime permission and if it is granted checking the corresponding
 * app op as for apps not supporting the runtime mode we never revoke
 * permissions but disable app ops. Since there are two types of app op
 * checks, one that does not leave a record an action was performed and
 * another the does, one needs to call the preflight flavor of the checks
 * named xxxForPreflight only if no private data is being delivered but
 * a permission check is what is needed and the xxxForDataDelivery where
 * the permission check is right before private data delivery.
 *
 * @hide
 */
public final class PermissionChecker {
    /** The permission is granted. */
    public static final int PERMISSION_GRANTED =  PackageManager.PERMISSION_GRANTED;

    /** Returned when:
     * <ul>
     * <li>For non app op permissions, returned when the permission is denied.</li>
     * <li>For app op permissions, returned when the app op is denied or app op is
     * {@link AppOpsManager#MODE_DEFAULT} and permission is denied.</li>
     * </ul>
     *
     */
    public static final int PERMISSION_HARD_DENIED =  PackageManager.PERMISSION_DENIED;

    /** Only for runtime permissions, its returned when the runtime permission
     * is granted, but the corresponding app op is denied. */
    public static final int PERMISSION_SOFT_DENIED =  PackageManager.PERMISSION_DENIED - 1;

    /** Constant when the PID for which we check permissions is unknown. */
    public static final int PID_UNKNOWN = -1;

    /** @hide */
    @IntDef({PERMISSION_GRANTED,
            PERMISSION_SOFT_DENIED,
            PERMISSION_HARD_DENIED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionResult {}

    private PermissionChecker() {
        /* do nothing */
    }

    /**
     * Checks whether a given package in a UID and PID has a given permission
     * and whether the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkPermissionForPreflight(Context, String, int, int, String)}
     * to determine if the app has or may have location permission (if app has only foreground
     * location the grant state depends on the app's fg/gb state) and this check will not
     * leave a trace that permission protected data was delivered. When you are about to
     * deliver the location data to a registered listener you should use this method which
     * will evaluate the permission access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param pid The process id for which to check. Use {@link #PID_UNKNOWN} if the PID
     *    is not known.
     * @param uid The uid for which to check.
     * @param packageName The package name for which to check. If null the
     *     the first package for the calling UID will be used.
     * @param attributionTag attribution tag
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkPermissionForPreflight(Context, String, int, int, String)
     */
    @PermissionResult
    public static int checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        return checkPermissionCommon(context, permission, pid, uid, packageName, attributionTag,
                message, true /*forDataDelivery*/);
    }

    /**
     * Checks whether a given package in a UID and PID has a given permission
     * and whether the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * preflight point where you will not deliver the permission protected data
     * to clients but schedule permission data delivery, apps register listeners,
     * etc.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use this method to determine if the app has or may have location
     * permission (if app has only foreground location the grant state depends on the app's
     * fg/gb state) and this check will not leave a trace that permission protected data
     * was delivered. When you are about to deliver the location data to a registered
     * listener you should use {@link #checkPermissionForDataDelivery(Context, String,
     * int, int, String, String)} which will evaluate the permission access based on the current
     * fg/bg state of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param pid The process id for which to check.
     * @param uid The uid for which to check.
     * @param packageName The package name for which to check. If null the
     *     the first package for the calling UID will be used.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkPermissionForDataDelivery(Context, String, int, int, String, String)
     */
    @PermissionResult
    public static int checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName) {
        return checkPermissionCommon(context, permission, pid, uid, packageName,
                null /*attributionTag*/, null /*message*/, false /*forDataDelivery*/);
    }

    /**
     * Checks whether your app has a given permission and whether the app op
     * that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkSelfPermissionForPreflight(Context, String)}
     * to determine if the app has or may have location permission (if app has only foreground
     * location the grant state depends on the app's fg/gb state) and this check will not
     * leave a trace that permission protected data was delivered. When you are about to
     * deliver the location data to a registered listener you should use this method
     * which will evaluate the permission access based on the current fg/bg state of the
     * app and leave a record that the data was accessed.
     *
     * <p>This API assumes the the {@link Binder#getCallingUid()} is the same as
     * {@link Process#myUid()}.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkSelfPermissionForPreflight(Context, String)
     */
    @PermissionResult
    public static int checkSelfPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, @Nullable String message) {
        return checkPermissionForDataDelivery(context, permission, Process.myPid(),
                Process.myUid(), context.getPackageName(), context.getAttributionTag(), message);
    }

    /**
     * Checks whether your app has a given permission and whether the app op
     * that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * preflight point where you will not deliver the permission protected data
     * to clients but schedule permission data delivery, apps register listeners,
     * etc.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use this method to determine if the app has or may have location
     * permission (if app has only foreground location the grant state depends on the
     * app's fg/gb state) and this check will not leave a trace that permission protected
     * data was delivered. When you are about to deliver the location data to a registered
     * listener you should use this method which will evaluate the permission access based
     * on the current fg/bg state of the app and leave a record that the data was accessed.
     *
     * <p>This API assumes the the {@link Binder#getCallingUid()} is the same as
     * {@link Process#myUid()}.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkSelfPermissionForDataDelivery(Context, String, String)
     */
    @PermissionResult
    public static int checkSelfPermissionForPreflight(@NonNull Context context,
            @NonNull String permission) {
        return checkPermissionForPreflight(context, permission, Process.myPid(),
                Process.myUid(), context.getPackageName());
    }

    /**
     * Checks whether the IPC you are handling has a given permission and whether
     * the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkCallingPermissionForPreflight(Context, String, String)}
     * to determine if the app has or may have location permission (if app has only foreground
     * location the grant state depends on the app's fg/gb state) and this check will not
     * leave a trace that permission protected data was delivered. When you are about to
     * deliver the location data to a registered listener you should use this method which
     * will evaluate the permission access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param packageName The package name making the IPC. If null the
     *     the first package for the calling UID will be used.
     * @param attributionTag attribution tag
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkCallingPermissionForPreflight(Context, String, String)
     */
    @PermissionResult
    public static int checkCallingPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        if (Binder.getCallingPid() == Process.myPid()) {
            return PERMISSION_HARD_DENIED;
        }
        return checkPermissionForDataDelivery(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), packageName, attributionTag, message);
    }

    /**
     * Checks whether the IPC you are handling has a given permission and whether
     * the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * preflight point where you will not deliver the permission protected data
     * to clients but schedule permission data delivery, apps register listeners,
     * etc.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use this method to determine if the app has or may have location
     * permission (if app has only foreground location the grant state depends on the app's
     * fg/gb state) and this check will not leave a trace that permission protected data
     * was delivered. When you are about to deliver the location data to a registered
     * listener you should use {@link #checkCallingOrSelfPermissionForDataDelivery(Context,
     * String, String)} which will evaluate the permission access based on the current fg/bg state
     * of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param packageName The package name making the IPC. If null the
     *     the first package for the calling UID will be used.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkCallingPermissionForDataDelivery(Context, String, String, String)
     */
    @PermissionResult
    public static int checkCallingPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, @Nullable String packageName) {
        if (Binder.getCallingPid() == Process.myPid()) {
            return PERMISSION_HARD_DENIED;
        }
        return checkPermissionForPreflight(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), packageName);
    }

    /**
     * Checks whether the IPC you are handling or your app has a given permission
     * and whether the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkCallingOrSelfPermissionForPreflight(Context, String)}
     * to determine if the app has or may have location permission (if app has only foreground
     * location the grant state depends on the app's fg/gb state) and this check will not
     * leave a trace that permission protected data was delivered. When you are about to
     * deliver the location data to a registered listener you should use this method which
     * will evaluate the permission access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     * @param attributionTag attribution tag of caller (if not self)
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkCallingOrSelfPermissionForPreflight(Context, String)
     */
    @PermissionResult
    public static int checkCallingOrSelfPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, @Nullable String attributionTag, @Nullable String message) {
        String packageName = (Binder.getCallingPid() == Process.myPid())
                ? context.getPackageName() : null;
        attributionTag = (Binder.getCallingPid() == Process.myPid())
                ? context.getAttributionTag() : attributionTag;
        return checkPermissionForDataDelivery(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), packageName, attributionTag, message);
    }

    /**
     * Checks whether the IPC you are handling or your app has a given permission
     * and whether the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * preflight point where you will not deliver the permission protected data
     * to clients but schedule permission data delivery, apps register listeners,
     * etc.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use this method to determine if the app has or may have location
     * permission (if app has only foreground location the grant state depends on the
     * app's fg/gb state) and this check will not leave a trace that permission protected
     * data was delivered. When you are about to deliver the location data to a registered
     * listener you should use {@link #checkCallingOrSelfPermissionForDataDelivery(Context,
     * String, String, String)} which will evaluate the permission access based on the current
     * fg/bg state of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkCallingOrSelfPermissionForDataDelivery(Context, String, String, String)
     */
    @PermissionResult
    public static int checkCallingOrSelfPermissionForPreflight(@NonNull Context context,
            @NonNull String permission) {
        String packageName = (Binder.getCallingPid() == Process.myPid())
                ? context.getPackageName() : null;
        return checkPermissionForPreflight(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), packageName);
    }

    static int checkPermissionCommon(@NonNull Context context, @NonNull String permission,
            int pid, int uid, @Nullable String packageName, @Nullable String attributionTag,
            @Nullable String message, boolean forDataDelivery) {
        final PermissionInfo permissionInfo;
        try {
            // TODO(b/147869157): Cache platform defined app op and runtime permissions to avoid
            // calling into the package manager every time.
            permissionInfo = context.getPackageManager().getPermissionInfo(permission, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return PERMISSION_HARD_DENIED;
        }

        if (packageName == null) {
            String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
            if (packageNames != null && packageNames.length > 0) {
                packageName = packageNames[0];
            }
        }

        if (permissionInfo.isAppOp()) {
            return checkAppOpPermission(context, permission, pid, uid, packageName, attributionTag,
                    message, forDataDelivery);
        }
        if (permissionInfo.isRuntime()) {
            return checkRuntimePermission(context, permission, pid, uid, packageName,
                    attributionTag, message, forDataDelivery);
        }
        return context.checkPermission(permission, pid, uid);
    }

    private static int checkAppOpPermission(@NonNull Context context, @NonNull String permission,
            int pid, int uid, @Nullable String packageName, @Nullable String attributionTag,
            @Nullable String message, boolean forDataDelivery) {
        final String op = AppOpsManager.permissionToOp(permission);
        if (op == null || packageName == null) {
            return PERMISSION_HARD_DENIED;
        }

        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final int opMode = (forDataDelivery)
                ? appOpsManager.noteProxyOpNoThrow(op, packageName, uid, attributionTag, message)
                : appOpsManager.unsafeCheckOpRawNoThrow(op, uid, packageName);

        switch (opMode) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_FOREGROUND: {
                return PERMISSION_GRANTED;
            }
            case AppOpsManager.MODE_DEFAULT: {
                return context.checkPermission(permission, pid, uid)
                            == PackageManager.PERMISSION_GRANTED
                        ? PERMISSION_GRANTED : PERMISSION_HARD_DENIED;
            }
            default: {
                return PERMISSION_HARD_DENIED;
            }
        }
    }

    private static int checkRuntimePermission(@NonNull Context context, @NonNull String permission,
            int pid, int uid, @Nullable String packageName, @Nullable String attributionTag,
            @Nullable String message, boolean forDataDelivery) {
        if (context.checkPermission(permission, pid, uid) == PackageManager.PERMISSION_DENIED) {
            return PERMISSION_HARD_DENIED;
        }

        final String op = AppOpsManager.permissionToOp(permission);
        if (op == null || packageName == null) {
            return PERMISSION_GRANTED;
        }

        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final int opMode = (forDataDelivery)
                ? appOpsManager.noteProxyOpNoThrow(op, packageName, uid, attributionTag, message)
                : appOpsManager.unsafeCheckOpRawNoThrow(op, uid, packageName);

        switch (opMode) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_FOREGROUND:
                return PERMISSION_GRANTED;
            default:
                return PERMISSION_SOFT_DENIED;
        }
    }
}
