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
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final String LOG_TAG = PermissionChecker.class.getName();

    private static final String PLATFORM_PACKAGE_NAME = "android";

    /** The permission is granted. */
    public static final int PERMISSION_GRANTED = AppOpsManager.MODE_ALLOWED;

    /** Only for runtime permissions, its returned when the runtime permission
     * is granted, but the corresponding app op is denied. */
    public static final int PERMISSION_SOFT_DENIED = AppOpsManager.MODE_IGNORED;

    /** Returned when:
     * <ul>
     * <li>For non app op permissions, returned when the permission is denied.</li>
     * <li>For app op permissions, returned when the app op is denied or app op is
     * {@link AppOpsManager#MODE_DEFAULT} and permission is denied.</li>
     * </ul>
     *
     */
    public static final int PERMISSION_HARD_DENIED = AppOpsManager.MODE_ERRORED;

    /** Constant when the PID for which we check permissions is unknown. */
    public static final int PID_UNKNOWN = -1;

    // Cache for platform defined runtime permissions to avoid multi lookup (name -> info)
    private static final ConcurrentHashMap<String, PermissionInfo> sPlatformPermissions
            = new ConcurrentHashMap<>();

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
     * <p>For more details how to determine the {@code packageName}, {@code attributionTag}, and
     * {@code message}, please check the description in
     * {@link AppOpsManager#noteOp(String, int, String, String, String)}
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
     * @param startDataDelivery Whether this is the start of data delivery.
     *
     * @see #checkPermissionForPreflight(Context, String, int, int, String)
     */
    @PermissionResult
    public static int checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message, boolean startDataDelivery) {
        return checkPermissionForDataDelivery(context, permission, pid, new AttributionSource(uid,
                packageName, attributionTag), message, startDataDelivery);
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
     * <p>For more details how to determine the {@code packageName}, {@code attributionTag}, and
     * {@code message}, please check the description in
     * {@link AppOpsManager#noteOp(String, int, String, String, String)}
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
        return checkPermissionForDataDelivery(context, permission, pid, uid,
                packageName, attributionTag, message, false /*startDataDelivery*/);
    }

    /**
     * Checks whether a given data access chain described by the given {@link AttributionSource}
     * has a given permission and whether the app op that corresponds to this permission
     * is allowed. Call this method if you are the datasource which would not blame you for
     * access to the data since you are the data.
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
     * @param attributionSource the permission identity
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkPermissionForPreflight(Context, String, AttributionSource)
     */
    @PermissionResult
    public static int checkPermissionForDataDeliveryFromDataSource(@NonNull Context context,
            @NonNull String permission, int pid, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return checkPermissionForDataDeliveryCommon(context, permission, pid, attributionSource,
                message, false /*startDataDelivery*/, /*fromDatasource*/ true);
    }

    /**
     * Checks whether a given data access chain described by the given {@link AttributionSource}
     * has a given permission and whether the app op that corresponds to this permission
     * is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkPermissionForPreflight(Context, String, AttributionSource)}
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
     * @param attributionSource the permission identity
     * @param message A message describing the reason the permission was checked
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkPermissionForPreflight(Context, String, AttributionSource)
     */
    @PermissionResult
    public static int checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, int pid, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return checkPermissionForDataDelivery(context, permission, pid, attributionSource,
                message, false /*startDataDelivery*/);
    }

    /**
     * Checks whether a given data access chain described by the given {@link AttributionSource}
     * has a given permission and whether the app op that corresponds to this permission
     * is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a data listener it should have the required
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkPermissionForPreflight(Context, String,
     * AttributionSource)}
     * to determine if the app has or may have permission and this check will not
     * leave a trace that permission protected data was delivered. When you are about to
     * deliver the data to a registered listener you should use this method which
     * will evaluate the permission access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param pid The process id for which to check. Use {@link #PID_UNKNOWN} if the PID
     *    is not known.
     * @param attributionSource The identity for which to check the permission.
     * @param message A message describing the reason the permission was checked
     * @param startDataDelivery Whether this is the start of data delivery.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkPermissionForPreflight(Context, String, AttributionSource)
     */
    @PermissionResult
    public static int checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, int pid, @NonNull AttributionSource attributionSource,
            @Nullable String message, boolean startDataDelivery) {
        return checkPermissionForDataDeliveryCommon(context, permission, pid, attributionSource,
                message, startDataDelivery, /*fromDatasource*/ false);
    }

    private static int checkPermissionForDataDeliveryCommon(@NonNull Context context,
            @NonNull String permission, int pid, @NonNull AttributionSource attributionSource,
            @Nullable String message, boolean startDataDelivery, boolean fromDatasource) {
        // If the check failed in the middle of the chain, finish any started op.
        final int result = checkPermissionCommon(context, permission, attributionSource,
                message, true /*forDataDelivery*/, startDataDelivery, fromDatasource);
        if (startDataDelivery && result != PERMISSION_GRANTED) {
            finishDataDelivery(context, AppOpsManager.permissionToOp(permission),
                    attributionSource);
        }
        return result;
    }

    /**
     * Checks whether a given data access chain described by the given {@link AttributionSource}
     * has a given permission and whether the app op that corresponds to this permission
     * is allowed. The app ops area also marked as started. This is useful for long running
     * permissions like camera.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a data listener it should have the required
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkPermissionForPreflight(Context, String,
     * AttributionSource)}
     * to determine if the app has or may have permission and this check will not
     * leave a trace that permission protected data was delivered. When you are about to
     * deliver the data to a registered listener you should use this method which
     * will evaluate the permission access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param attributionSource The identity for which to check the permission.
     * @param message A message describing the reason the permission was checked
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkPermissionForPreflight(Context, String, AttributionSource)
     */
    @PermissionResult
    public static int checkPermissionAndStartDataDelivery(@NonNull Context context,
            @NonNull String permission, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return checkPermissionCommon(context, permission, attributionSource,
                message, true /*forDataDelivery*/, /*startDataDelivery*/ true,
                /*fromDatasource*/ false);
    }

    /**
     * Checks whether a given data access chain described by the given {@link
     * AttributionSource} has a given app op allowed and marks the op as started.
     *
     * <strong>NOTE:</strong> Use this method only for app op checks at the
     * point where you will deliver the protected data to clients.
     *
     * <p>For example, if an app registers a data listener it should have the data
     * op but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkOpForPreflight(Context, String, AttributionSource, String)}
     * to determine if the app has or may have op access and this check will not
     * leave a trace that op protected data was delivered. When you are about to
     * deliver the data to a registered listener you should use this method which
     * will evaluate the op access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param opName THe op to start.
     * @param attributionSource The identity for which to check the permission.
     * @param message A message describing the reason the permission was checked
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #finishDataDelivery(Context, String, AttributionSource)
     */
    @PermissionResult
    public static int startOpForDataDelivery(@NonNull Context context,
            @NonNull String opName, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        final int result = checkOp(context, AppOpsManager.strOpToOp(opName), attributionSource,
                message, true /*forDataDelivery*/, true /*startDataDelivery*/);
        // It is important to finish any started op if some step in the attribution chain failed.
        if (result != PERMISSION_GRANTED) {
            finishDataDelivery(context, opName, attributionSource);
        }
        return result;
    }

    /**
     * Finishes an ongoing op for data access chain described by the given {@link
     * AttributionSource}.
     *
     * @param context Context for accessing resources.
     * @param op The op to finish.
     * @param attributionSource The identity for which finish op.
     *
     * @see #startOpForDataDelivery(Context, String, AttributionSource, String)
     * @see #checkPermissionAndStartDataDelivery(Context, String, AttributionSource, String)
     */
    public static void finishDataDelivery(@NonNull Context context, @NonNull String op,
            @NonNull AttributionSource attributionSource) {
        if (op == null || attributionSource.getPackageName() == null) {
            return;
        }

        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        appOpsManager.finishProxyOp(op, attributionSource);

        if (attributionSource.getNext() != null) {
            finishDataDelivery(context, op, attributionSource.getNext());
        }
    }

    /**
     * Checks whether a given data access chain described by the given {@link
     * AttributionSource} has a given app op allowed.
     *
     * <strong>NOTE:</strong> Use this method only for op checks at the
     * preflight point where you will not deliver the protected data
     * to clients but schedule a data delivery, apps register listeners,
     * etc.
     *
     * <p>For example, if an app registers a data listener it should have the op
     * but no data is actually sent to the app at the moment of registration
     * and you should use this method to determine if the app has or may have data
     * access and this check will not leave a trace that protected data
     * was delivered. When you are about to deliver the data to a registered
     * listener you should use {@link #checkOpForDataDelivery(Context, String,
     * AttributionSource, String)} which will evaluate the op access based
     * on the current fg/bg state of the app and leave a record that the data was
     * accessed.
     *
     * @param context Context for accessing resources.
     * @param opName The op to check.
     * @param attributionSource The identity for which to check the permission.
     * @param message A message describing the reason the permission was checked
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkOpForDataDelivery(Context, String, AttributionSource, String)
     */
    @PermissionResult
    public static int checkOpForPreflight(@NonNull Context context,
            @NonNull String opName, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return checkOp(context, AppOpsManager.strOpToOp(opName), attributionSource,
                message,  false /*forDataDelivery*/, false /*startDataDelivery*/);
    }

    /**
     * Checks whether a given data access chain described by the given {@link AttributionSource}
     * has an allowed app op.
     *
     * <strong>NOTE:</strong> Use this method only for op checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a data listener it should have the data
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkOpForPreflight(Context, String, AttributionSource, String)}
     * to determine if the app has or may have data access and this check will not
     * leave a trace that op protected data was delivered. When you are about to
     * deliver the  data to a registered listener you should use this method which
     * will evaluate the op access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param opName The op to check.
     * @param attributionSource The identity for which to check the op.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkOpForPreflight(Context, String, AttributionSource, String)
     */
    @PermissionResult
    public static int checkOpForDataDelivery(@NonNull Context context,
            @NonNull String opName, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return checkOp(context, AppOpsManager.strOpToOp(opName), attributionSource,
                message,  true /*forDataDelivery*/, false /*startDataDelivery*/);
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
     * int, int, String, String, String)} which will evaluate the permission access based
     * on the currentfg/bg state of the app and leave a record that the data was accessed.
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
     * @see #checkPermissionForDataDelivery(Context, String, int, int, String, String, String)
     */
    @PermissionResult
    public static int checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName) {
        return checkPermissionForPreflight(context, permission, new AttributionSource(
                uid, packageName, null /*attributionTag*/));
    }

    /**
     * Checks whether a given data access chain described by the given {@link AttributionSource}
     * has a given permission and whether the app op that corresponds to this permission
     * is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * preflight point where you will not deliver the permission protected data
     * to clients but schedule permission data delivery, apps register listeners,
     * etc.
     *
     * <p>For example, if an app registers a data listener it should have the required
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use this method to determine if the app has or may have the
     * permission and this check will not leave a trace that permission protected data
     * was delivered. When you are about to deliver the protected data to a registered
     * listener you should use {@link #checkPermissionForDataDelivery(Context, String,
     * int, AttributionSource, String, boolean)} which will evaluate the permission access based
     * on the current fg/bg state of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param attributionSource The identity for which to check the permission.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkPermissionForDataDelivery(Context, String, int, AttributionSource,
     *     String, boolean)
     */
    @PermissionResult
    public static int checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, @NonNull AttributionSource attributionSource) {
        return checkPermissionCommon(context, permission, attributionSource,
                null /*message*/, false /*forDataDelivery*/, /*startDataDelivery*/ false,
                /*fromDatasource*/ false);
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
                Process.myUid(), context.getPackageName(), context.getAttributionTag(), message,
                /*startDataDelivery*/ false);
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
     * <p>For more details how to determine the {@code callingPackageName},
     * {@code callingAttributionTag}, and {@code message}, please check the description in
     * {@link AppOpsManager#noteOp(String, int, String, String, String)}
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param callingPackageName The package name making the IPC. If null the
     *     the first package for the calling UID will be used.
     * @param callingAttributionTag attribution tag
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkCallingPermissionForPreflight(Context, String, String)
     */
    @PermissionResult
    public static int checkCallingPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, @Nullable String callingPackageName,
            @Nullable String callingAttributionTag, @Nullable String message) {
        if (Binder.getCallingPid() == Process.myPid()) {
            return PERMISSION_HARD_DENIED;
        }
        return checkPermissionForDataDelivery(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), callingPackageName, callingAttributionTag, message,
                /*startDataDelivery*/ false);
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
     * String, String, String, String)} which will evaluate the permission access based on the
     * current fg/bg stateof the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param packageName The package name making the IPC. If null the
     *     the first package for the calling UID will be used.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkCallingPermissionForDataDelivery(Context, String, String, String, String)
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
     * <p>For more details how to determine the {@code callingPackageName},
     * {@code callingAttributionTag}, and {@code message}, please check the description in
     * {@link AppOpsManager#noteOp(String, int, String, String, String)}
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     * @param callingPackageName package name tag of caller (if not self)
     * @param callingAttributionTag attribution tag of caller (if not self)
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkCallingOrSelfPermissionForPreflight(Context, String)
     */
    @PermissionResult
    public static int checkCallingOrSelfPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, @Nullable String callingPackageName,
            @Nullable String callingAttributionTag, @Nullable String message) {
        callingPackageName = (Binder.getCallingPid() == Process.myPid())
                ? context.getPackageName() : callingPackageName;
        callingAttributionTag = (Binder.getCallingPid() == Process.myPid())
                ? context.getAttributionTag() : callingAttributionTag;
        return checkPermissionForDataDelivery(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), callingPackageName, callingAttributionTag, message,
                /*startDataDelivery*/ false);
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
     * String, String, String, String)} which will evaluate the permission access based on the
     * current fg/bg state of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @return The permission check result which is either {@link #PERMISSION_GRANTED}
     *     or {@link #PERMISSION_SOFT_DENIED} or {@link #PERMISSION_HARD_DENIED}.
     *
     * @see #checkCallingOrSelfPermissionForDataDelivery(Context, String, String, String, String)
     */
    @PermissionResult
    public static int checkCallingOrSelfPermissionForPreflight(@NonNull Context context,
            @NonNull String permission) {
        String packageName = (Binder.getCallingPid() == Process.myPid())
                ? context.getPackageName() : null;
        return checkPermissionForPreflight(context, permission, Binder.getCallingPid(),
                Binder.getCallingUid(), packageName);
    }

    @PermissionResult
    private static int checkPermissionCommon(@NonNull Context context, @NonNull String permission,
            @NonNull AttributionSource attributionSource,
            @Nullable String message, boolean forDataDelivery, boolean startDataDelivery,
            boolean fromDatasource) {
        PermissionInfo permissionInfo = sPlatformPermissions.get(permission);

        if (permissionInfo == null) {
            try {
                permissionInfo = context.getPackageManager().getPermissionInfo(permission, 0);
                if (PLATFORM_PACKAGE_NAME.equals(permissionInfo.packageName)) {
                    // Double addition due to concurrency is fine - the backing store is concurrent.
                    sPlatformPermissions.put(permission, permissionInfo);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                return PERMISSION_HARD_DENIED;
            }
        }

        if (permissionInfo.isAppOp()) {
            return checkAppOpPermission(context, permission, attributionSource, message,
                    forDataDelivery, fromDatasource);
        }
        if (permissionInfo.isRuntime()) {
            return checkRuntimePermission(context, permission, attributionSource, message,
                    forDataDelivery, startDataDelivery, fromDatasource);
        }

        if (!fromDatasource && !checkPermission(context, permission, attributionSource.getUid(),
                attributionSource.getRenouncedPermissions())) {
            return PERMISSION_HARD_DENIED;
        }

        if (attributionSource.getNext() != null) {
            return checkPermissionCommon(context, permission,
                    attributionSource.getNext(), message, forDataDelivery,
                    startDataDelivery, /*fromDatasource*/ false);
        }

        return PERMISSION_GRANTED;
    }

    @PermissionResult
    private static int checkAppOpPermission(@NonNull Context context, @NonNull String permission,
            @NonNull AttributionSource attributionSource, @Nullable String message,
            boolean forDataDelivery, boolean fromDatasource) {
        final int op = AppOpsManager.permissionToOpCode(permission);
        if (op < 0) {
            Slog.wtf(LOG_TAG, "Appop permission " + permission + "with no app op defined:!");
            return PERMISSION_HARD_DENIED;
        }

        AttributionSource current = attributionSource;
        AttributionSource next = null;

        while (true) {
            final boolean skipCurrentChecks = (fromDatasource || next != null);

            next = current.getNext();

            // If the call is from a datasource we need to vet only the chain before it. This
            // way we can avoid the datasource creating an attribution context for every call.
            if (!(fromDatasource && current == attributionSource)
                    && next != null && !current.isTrusted(context)) {
                return PERMISSION_HARD_DENIED;
            }

            // The access is for oneself if this is the single receiver of data
            // after the data source or if this is the single attribution source
            // in the chain if not from a datasource.
            final boolean singleReceiverFromDatasource = (fromDatasource
                    && current == attributionSource && next != null && next.getNext() == null);
            final boolean selfAccess = singleReceiverFromDatasource || next == null;

            final int opMode = performOpTransaction(context, op, current, message,
                    forDataDelivery, /*startDataDelivery*/ false, skipCurrentChecks,
                    selfAccess, singleReceiverFromDatasource);

            switch (opMode) {
                case AppOpsManager.MODE_IGNORED:
                case AppOpsManager.MODE_ERRORED: {
                    return PERMISSION_HARD_DENIED;
                }
                case AppOpsManager.MODE_DEFAULT: {
                    if (!skipCurrentChecks && !checkPermission(context, permission,
                            attributionSource.getUid(), attributionSource
                                    .getRenouncedPermissions())) {
                        return PERMISSION_HARD_DENIED;
                    }
                    if (next != null && !checkPermission(context, permission,
                            next.getUid(), next.getRenouncedPermissions())) {
                        return PERMISSION_HARD_DENIED;
                    }
                }
            }

            if (next == null || next.getNext() == null) {
                return PERMISSION_GRANTED;
            }

            current = next;
        }
    }

    private static int checkRuntimePermission(@NonNull Context context, @NonNull String permission,
            @NonNull AttributionSource attributionSource, @Nullable String message,
            boolean forDataDelivery, boolean startDataDelivery, boolean fromDatasource) {
        // Now let's check the identity chain...
        final int op = AppOpsManager.permissionToOpCode(permission);

        AttributionSource current = attributionSource;
        AttributionSource next = null;

        while (true) {
            final boolean skipCurrentChecks = (fromDatasource || next != null);
            next = current.getNext();

            // If the call is from a datasource we need to vet only the chain before it. This
            // way we can avoid the datasource creating an attribution context for every call.
            if (!(fromDatasource && current == attributionSource)
                    && next != null && !current.isTrusted(context)) {
                return PERMISSION_HARD_DENIED;
            }

            // If we already checked the permission for this one, skip the work
            if (!skipCurrentChecks && !checkPermission(context, permission,
                    current.getUid(), current.getRenouncedPermissions())) {
                return PERMISSION_HARD_DENIED;
            }

            if (next != null && !checkPermission(context, permission,
                    next.getUid(), next.getRenouncedPermissions())) {
                return PERMISSION_HARD_DENIED;
            }

            if (op < 0) {
                Slog.wtf(LOG_TAG, "Runtime permission " + permission + "with no app op defined:!");
                current = next;
                continue;
            }

            // The access is for oneself if this is the single receiver of data
            // after the data source or if this is the single attribution source
            // in the chain if not from a datasource.
            final boolean singleReceiverFromDatasource = (fromDatasource
                    && current == attributionSource && next != null && next.getNext() == null);
            final boolean selfAccess = singleReceiverFromDatasource || next == null;

            final int opMode = performOpTransaction(context, op, current, message,
                    forDataDelivery, startDataDelivery, skipCurrentChecks, selfAccess,
                    singleReceiverFromDatasource);

            switch (opMode) {
                case AppOpsManager.MODE_ERRORED: {
                    return PERMISSION_HARD_DENIED;
                }
                case AppOpsManager.MODE_IGNORED: {
                    return PERMISSION_SOFT_DENIED;
                }
            }

            if (next == null || next.getNext() == null) {
                return PERMISSION_GRANTED;
            }

            current = next;
        }
    }

    private static boolean checkPermission(@NonNull Context context, @NonNull String permission,
            int uid, @NonNull Set<String> renouncedPermissions) {
        final boolean permissionGranted = context.checkPermission(permission, /*pid*/ -1,
                uid) == PackageManager.PERMISSION_GRANTED;
        if (permissionGranted && renouncedPermissions.contains(permission)) {
            return false;
        }
        return permissionGranted;
    }

    private static int checkOp(@NonNull Context context, @NonNull int op,
            @NonNull AttributionSource attributionSource, @Nullable String message,
            boolean forDataDelivery, boolean startDataDelivery) {
        if (op < 0 || attributionSource.getPackageName() == null) {
            return PERMISSION_HARD_DENIED;
        }

        AttributionSource current = attributionSource;
        AttributionSource next = null;

        while (true) {
            final boolean skipCurrentChecks = (next != null);
            next = current.getNext();

            // If the call is from a datasource we need to vet only the chain before it. This
            // way we can avoid the datasource creating an attribution context for every call.
            if (next != null && !current.isTrusted(context)) {
                return PERMISSION_HARD_DENIED;
            }

            // The access is for oneself if this is the single attribution source in the chain.
            final boolean selfAccess = (next == null);

            final int opMode = performOpTransaction(context, op, current, message,
                    forDataDelivery, startDataDelivery, skipCurrentChecks, selfAccess,
                    /*fromDatasource*/ false);

            switch (opMode) {
                case AppOpsManager.MODE_ERRORED: {
                    return PERMISSION_HARD_DENIED;
                }
                case AppOpsManager.MODE_IGNORED: {
                    return PERMISSION_SOFT_DENIED;
                }
            }

            if (next == null || next.getNext() == null) {
                return PERMISSION_GRANTED;
            }

            current = next;
        }
    }

    private static int performOpTransaction(@NonNull Context context, int op,
            @NonNull AttributionSource attributionSource, @Nullable String message,
            boolean forDataDelivery, boolean startDataDelivery, boolean skipProxyOperation,
            boolean selfAccess, boolean singleReceiverFromDatasource) {
        // We cannot perform app ops transactions without a package name. In all relevant
        // places we pass the package name but just in case there is a bug somewhere we
        // do a best effort to resolve the package from the UID (pick first without a loss
        // of generality - they are in the same security sandbox).
        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final AttributionSource accessorSource = (!singleReceiverFromDatasource)
                ? attributionSource : attributionSource.getNext();
        if (!forDataDelivery) {
            final String resolvedAccessorPackageName = resolvePackageName(context, accessorSource);
            if (resolvedAccessorPackageName == null) {
                return AppOpsManager.MODE_ERRORED;
            }
            final int opMode = appOpsManager.unsafeCheckOpRawNoThrow(op,
                    accessorSource.getUid(), resolvedAccessorPackageName);
            final AttributionSource next = accessorSource.getNext();
            if (!selfAccess && opMode == AppOpsManager.MODE_ALLOWED && next != null) {
                final String resolvedNextPackageName = resolvePackageName(context, next);
                if (resolvedNextPackageName == null) {
                    return AppOpsManager.MODE_ERRORED;
                }
                return appOpsManager.unsafeCheckOpRawNoThrow(op, next.getUid(),
                        resolvedNextPackageName);
            }
            return opMode;
        } else if (startDataDelivery) {
            final AttributionSource resolvedAttributionSource = resolveAttributionSource(
                    context, accessorSource);
            if (resolvedAttributionSource.getPackageName() == null) {
                return AppOpsManager.MODE_ERRORED;
            }
            if (selfAccess) {
                return appOpsManager.startOpNoThrow(op, resolvedAttributionSource.getUid(),
                        resolvedAttributionSource.getPackageName(),
                        /*startIfModeDefault*/ false,
                        resolvedAttributionSource.getAttributionTag(),
                        message);
            } else {
                return appOpsManager.startProxyOpNoThrow(op, resolvedAttributionSource, message,
                        skipProxyOperation);
            }
        } else {
            final AttributionSource resolvedAttributionSource = resolveAttributionSource(
                    context, accessorSource);
            if (resolvedAttributionSource.getPackageName() == null) {
                return AppOpsManager.MODE_ERRORED;
            }
            if (selfAccess) {
                return appOpsManager.noteOpNoThrow(op, resolvedAttributionSource.getUid(),
                        resolvedAttributionSource.getPackageName(),
                        resolvedAttributionSource.getAttributionTag(),
                        message);
            } else {
                return appOpsManager.noteProxyOpNoThrow(op, resolvedAttributionSource, message,
                        skipProxyOperation);
            }
        }
    }

    private static @Nullable String resolvePackageName(@NonNull Context context,
            @NonNull AttributionSource attributionSource) {
        if (attributionSource.getPackageName() != null) {
            return attributionSource.getPackageName();
        }
        final String[] packageNames = context.getPackageManager().getPackagesForUid(
                attributionSource.getUid());
        if (packageNames != null) {
            // This is best effort if the caller doesn't pass a package. The security
            // sandbox is UID, therefore we pick an arbitrary package.
            return packageNames[0];
        }
        // Last resort to handle special UIDs like root, etc.
        return AppOpsManager.resolvePackageName(attributionSource.getUid(),
                attributionSource.getPackageName());
    }

    private static @NonNull AttributionSource resolveAttributionSource(
            @NonNull Context context, @NonNull AttributionSource attributionSource) {
        if (attributionSource.getPackageName() != null) {
            return attributionSource;
        }
        return new AttributionSource(attributionSource.getUid(),
                resolvePackageName(context, attributionSource),
                attributionSource.getAttributionTag(),
                attributionSource.getToken(),
                attributionSource.getRenouncedPermissions(),
                attributionSource.getNext());
    }
}
