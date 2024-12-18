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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.os.Binder;
import android.os.Process;
import android.permission.IPermissionChecker;
import android.permission.PermissionCheckerManager;
import android.permission.PermissionCheckerManager.PermissionResult;

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
    /**
     * The permission is granted.
     *
     * @hide
     */
    public static final int PERMISSION_GRANTED = PermissionCheckerManager.PERMISSION_GRANTED;

    /**
     * The permission is denied. Applicable only to runtime permissions.
     *
     * <p>Returned when:
     * <ul>
     *   <li>the runtime permission is granted, but the corresponding app op is denied
     *       for runtime permissions.</li>
     * </ul>
     *
     * @hide
     */
    public static final int PERMISSION_SOFT_DENIED =
            PermissionCheckerManager.PERMISSION_SOFT_DENIED;

    /**
     * The permission is denied.
     *
     * <p>Returned when:
     * <ul>
     *   <li>the permission is denied for non app op permissions.</li>
     *   <li>the app op is denied or app op is {@link AppOpsManager#MODE_DEFAULT}
     *   and permission is denied.</li>
     * </ul>
     *
     * @hide
     */
    public static final int PERMISSION_HARD_DENIED =
            PermissionCheckerManager.PERMISSION_HARD_DENIED;

    /** Constant when the PID for which we check permissions is unknown. */
    public static final int PID_UNKNOWN = -1;

    private static volatile IPermissionChecker sService;

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
    @PermissionCheckerManager.PermissionResult
    public static int checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message, boolean startDataDelivery) {
        return checkPermissionForDataDelivery(context, permission, pid, new AttributionSource(uid,
                pid, packageName, attributionTag), message, startDataDelivery);
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
        return checkPermissionForDataDeliveryCommon(context, permission, attributionSource,
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
        return checkPermissionForDataDeliveryCommon(context, permission, attributionSource,
                message, startDataDelivery, /*fromDatasource*/ false);
    }

    @SuppressWarnings("ConstantConditions")
    private static int checkPermissionForDataDeliveryCommon(@NonNull Context context,
            @NonNull String permission, @NonNull AttributionSource attributionSource,
            @Nullable String message, boolean startDataDelivery, boolean fromDatasource) {
        return context.getSystemService(PermissionCheckerManager.class).checkPermission(permission,
                attributionSource.asState(), message, true /*forDataDelivery*/, startDataDelivery,
                fromDatasource, AppOpsManager.OP_NONE);
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
    @SuppressWarnings("ConstantConditions")
    public static int checkPermissionAndStartDataDelivery(@NonNull Context context,
            @NonNull String permission, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return context.getSystemService(PermissionCheckerManager.class).checkPermission(
                permission, attributionSource.asState(), message, true /*forDataDelivery*/,
                /*startDataDelivery*/ true, /*fromDatasource*/ false, AppOpsManager.OP_NONE);
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
    @SuppressWarnings("ConstantConditions")
    public static int startOpForDataDelivery(@NonNull Context context,
            @NonNull String opName, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return context.getSystemService(PermissionCheckerManager.class).checkOp(
                AppOpsManager.strOpToOp(opName), attributionSource.asState(), message,
                true /*forDataDelivery*/, true /*startDataDelivery*/);
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
    @SuppressWarnings("ConstantConditions")
    public static void finishDataDelivery(@NonNull Context context, @NonNull String op,
            @NonNull AttributionSource attributionSource) {
        context.getSystemService(PermissionCheckerManager.class).finishDataDelivery(
                AppOpsManager.strOpToOp(op), attributionSource.asState(),
                /*fromDatasource*/ false);
    }

    /**
     * Finishes an ongoing op for data access chain described by the given {@link
     * AttributionSource}. Call this method if you are the datasource which would
     * not finish an op for your attribution source as it was not started.
     *
     * @param context Context for accessing resources.
     * @param op The op to finish.
     * @param attributionSource The identity for which finish op.
     *
     * @see #startOpForDataDelivery(Context, String, AttributionSource, String)
     * @see #checkPermissionAndStartDataDelivery(Context, String, AttributionSource, String)
     */
    @SuppressWarnings("ConstantConditions")
    public static void finishDataDeliveryFromDatasource(@NonNull Context context,
            @NonNull String op, @NonNull AttributionSource attributionSource) {
        context.getSystemService(PermissionCheckerManager.class).finishDataDelivery(
                AppOpsManager.strOpToOp(op), attributionSource.asState(),
                /*fromDatasource*/ true);
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
    @SuppressWarnings("ConstantConditions")
    public static int checkOpForPreflight(@NonNull Context context,
            @NonNull String opName, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return context.getSystemService(PermissionCheckerManager.class).checkOp(
                AppOpsManager.strOpToOp(opName), attributionSource.asState(), message,
                false /*forDataDelivery*/, false /*startDataDelivery*/);
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
    @SuppressWarnings("ConstantConditions")
    public static int checkOpForDataDelivery(@NonNull Context context,
            @NonNull String opName, @NonNull AttributionSource attributionSource,
            @Nullable String message) {
        return context.getSystemService(PermissionCheckerManager.class).checkOp(
                AppOpsManager.strOpToOp(opName), attributionSource.asState(), message,
                true /*forDataDelivery*/, false /*startDataDelivery*/);
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
    @SuppressWarnings("ConstantConditions")
    public static int checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, @NonNull AttributionSource attributionSource) {
        return context.getSystemService(PermissionCheckerManager.class)
                .checkPermission(permission, attributionSource.asState(), null /*message*/,
                false /*forDataDelivery*/, /*startDataDelivery*/ false, /*fromDatasource*/ false,
                AppOpsManager.OP_NONE);
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
     * <p>This API assumes the {@link Binder#getCallingUid()} is the same as
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
     * <p>This API assumes the {@link Binder#getCallingUid()} is the same as
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
}
