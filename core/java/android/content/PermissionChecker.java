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
    /** Permission result: The permission is granted. */
    public static final int PERMISSION_GRANTED =  PackageManager.PERMISSION_GRANTED;

    /** Permission result: The permission is denied. */
    public static final int PERMISSION_DENIED =  PackageManager.PERMISSION_DENIED;

    /** Permission result: The permission is denied because the app op is not allowed. */
    public static final int PERMISSION_DENIED_APP_OP =  PackageManager.PERMISSION_DENIED  - 1;

    /** Constant when the PID for which we check permissions is unknown. */
    public static final int PID_UNKNOWN = -1;

    /** @hide */
    @IntDef({PERMISSION_GRANTED,
            PERMISSION_DENIED,
            PERMISSION_DENIED_APP_OP})
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
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_DENIED} or {@link #PERMISSION_DENIED_APP_OP}.
     *
     * @see #checkPermissionForPreflight(Context, String, int, int, String)
     */
    @PermissionResult
    public static int checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName) {
        return checkPermissionCommon(context, permission, pid, uid, packageName,
                true /*forDataDelivery*/);
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
     * int, int, String)} which will evaluate the permission access based on the current
     * fg/bg state of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param pid The process id for which to check.
     * @param uid The uid for which to check.
     * @param packageName The package name for which to check. If null the
     *     the first package for the calling UID will be used.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_DENIED} or {@link #PERMISSION_DENIED_APP_OP}.
     *
     * @see #checkPermissionForDataDelivery(Context, String, int, int, String)
     */
    @PermissionResult
    public static int checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName) {
        return checkPermissionCommon(context, permission, pid, uid, packageName,
                false /*forDataDelivery*/);
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
     *     or {@link #PERMISSION_DENIED} or {@link #PERMISSION_DENIED_APP_OP}.
     *
     * @see #checkSelfPermissionForPreflight(Context, String)
     */
    @PermissionResult
    public static int checkSelfPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission) {
        return checkPermissionForDataDelivery(context, permission, Process.myPid(),
                Process.myUid(), context.getPackageName());
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
     *     or {@link #PERMISSION_DENIED} or {@link #PERMISSION_DENIED_APP_OP}.
     *
     * @see #checkSelfPermissionForDataDelivery(Context, String)
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
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_DENIED} or {@link #PERMISSION_DENIED_APP_OP}.
     *
     * @see #checkCallingPermissionForPreflight(Context, String, String)
     */
    @PermissionResult
    public static int checkCallingPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, @Nullable String packageName) {
        if (Binder.getCallingPid() == Process.myPid()) {
            return PERMISSION_DENIED;
        }
        return checkPermissionForDataDelivery(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), packageName);
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
     * String)} which will evaluate the permission access based on the current fg/bg state
     * of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param packageName The package name making the IPC. If null the
     *     the first package for the calling UID will be used.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_DENIED} or {@link #PERMISSION_DENIED_APP_OP}.
     *
     * @see #checkCallingPermissionForDataDelivery(Context, String, String)
     */
    @PermissionResult
    public static int checkCallingPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, @Nullable String packageName) {
        if (Binder.getCallingPid() == Process.myPid()) {
            return PERMISSION_DENIED;
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
     *     or {@link #PERMISSION_DENIED} or {@link #PERMISSION_DENIED_APP_OP}.
     *
     * @see #checkCallingOrSelfPermissionForPreflight(Context, String)
     */
    @PermissionResult
    public static int checkCallingOrSelfPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission) {
        String packageName = (Binder.getCallingPid() == Process.myPid())
                ? context.getPackageName() : null;
        return checkPermissionForDataDelivery(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), packageName);
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
     * String)} which will evaluate the permission access based on the current fg/bg state
     * of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_DENIED} or {@link #PERMISSION_DENIED_APP_OP}.
     *
     * @see #checkCallingOrSelfPermissionForDataDelivery(Context, String)
     */
    @PermissionResult
    public static int checkCallingOrSelfPermissionForPreflight(@NonNull Context context,
            @NonNull String permission) {
        String packageName = (Binder.getCallingPid() == Process.myPid())
                ? context.getPackageName() : null;
        return checkPermissionForPreflight(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), packageName);
    }

    private static int checkPermissionCommon(@NonNull Context context, @NonNull String permission,
            int pid, int uid, @Nullable String packageName, boolean forDataDelivery) {
        if (context.checkPermission(permission, pid, uid) == PackageManager.PERMISSION_DENIED) {
            return PERMISSION_DENIED;
        }

        AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        String op = appOpsManager.permissionToOp(permission);
        if (op == null) {
            return PERMISSION_GRANTED;
        }

        if (packageName == null) {
            String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
            if (packageNames == null || packageNames.length <= 0) {
                return PERMISSION_DENIED;
            }
            packageName = packageNames[0];
        }

        if (forDataDelivery) {
            if (appOpsManager.noteProxyOpNoThrow(op, packageName, uid)
                    != AppOpsManager.MODE_ALLOWED) {
                return PERMISSION_DENIED_APP_OP;
            }
        } else {
            final int mode = appOpsManager.unsafeCheckOpRawNoThrow(op, uid, packageName);
            if (mode != AppOpsManager.MODE_ALLOWED && mode != AppOpsManager.MODE_FOREGROUND) {
                return PERMISSION_DENIED_APP_OP;
            }
        }

        return PERMISSION_GRANTED;
    }
}
