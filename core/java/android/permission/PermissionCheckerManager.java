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

package android.permission;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.AttributionSourceState;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Manager for checking runtime and app op permissions. This is a temporary
 * class and we may fold its function in the PermissionManager once the
 * permission re-architecture starts falling into place. The main benefit
 * of this class is to allow context level caching.
 *
 * @hide
 */
public class PermissionCheckerManager {

    /**
     * The permission is granted.
     */
    public static final int PERMISSION_GRANTED = IPermissionChecker.PERMISSION_GRANTED;

    /**
     * The permission is denied. Applicable only to runtime and app op permissions.
     *
     * <p>Returned when:
     * <ul>
     *   <li>the runtime permission is granted, but the corresponding app op is denied
     *       for runtime permissions.</li>
     *   <li>the app ops is ignored for app op permissions.</li>
     * </ul>
     */
    public static final int PERMISSION_SOFT_DENIED = IPermissionChecker.PERMISSION_SOFT_DENIED;

    /**
     * The permission is denied.
     *
     * <p>Returned when:
     * <ul>
     *   <li>the permission is denied for non app op permissions.</li>
     *   <li>the app op is denied or app op is {@link AppOpsManager#MODE_DEFAULT}
     *   and permission is denied.</li>
     * </ul>
     */
    public static final int PERMISSION_HARD_DENIED = IPermissionChecker.PERMISSION_HARD_DENIED;

    /** @hide */
    @IntDef({PERMISSION_GRANTED,
            PERMISSION_SOFT_DENIED,
            PERMISSION_HARD_DENIED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionResult {}

    @NonNull
    private final Context mContext;

    @NonNull
    private final IPermissionChecker mService;

    @NonNull
    private final PackageManager mPackageManager;

    public PermissionCheckerManager(@NonNull Context context)
            throws ServiceManager.ServiceNotFoundException {
        mContext = context;
        mService = IPermissionChecker.Stub.asInterface(ServiceManager.getServiceOrThrow(
                Context.PERMISSION_CHECKER_SERVICE));
        mPackageManager = context.getPackageManager();
    }

    /**
     * Checks a permission by validating the entire attribution source chain. If the
     * permission is associated with an app op the op is also noted/started for the
     * entire attribution chain.
     *
     * @param permission The permission
     * @param attributionSource The attribution chain to check.
     * @param message Message associated with the permission if permission has an app op
     * @param forDataDelivery Whether the check is for delivering data if permission has an app op
     * @param startDataDelivery Whether to start data delivery (start op) if permission has
     *     an app op
     * @param fromDatasource Whether the check is by a datasource (skip checks for the
     *     first attribution source in the chain as this is the datasource)
     * @param attributedOp Alternative app op to attribute
     * @return The permission check result.
     */
    @PermissionResult
    public int checkPermission(@NonNull String permission,
            @NonNull AttributionSourceState attributionSource, @Nullable String message,
            boolean forDataDelivery, boolean startDataDelivery, boolean fromDatasource,
            int attributedOp) {
        Objects.requireNonNull(permission);
        Objects.requireNonNull(attributionSource);
        // Fast path for non-runtime, non-op permissions where the attribution chain has
        // length one. This is the majority of the cases and we want these to be fast by
        // hitting the local in process permission cache.
        if (AppOpsManager.permissionToOpCode(permission) == AppOpsManager.OP_NONE) {
            if (fromDatasource) {
                if (attributionSource.next != null && attributionSource.next.length > 0) {
                    return mContext.checkPermission(permission, attributionSource.next[0].pid,
                            attributionSource.next[0].uid) == PackageManager.PERMISSION_GRANTED
                            ? PERMISSION_GRANTED : PERMISSION_HARD_DENIED;
                }
            } else {
                return (mContext.checkPermission(permission, attributionSource.pid,
                            attributionSource.uid) == PackageManager.PERMISSION_GRANTED)
                        ? PERMISSION_GRANTED : PERMISSION_HARD_DENIED;
            }
        }
        try {
            return mService.checkPermission(permission, attributionSource, message, forDataDelivery,
                    startDataDelivery, fromDatasource, attributedOp);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return PERMISSION_HARD_DENIED;
    }

    /**
     * Finishes an app op by validating the entire attribution source chain.
     *
     * @param op The op to finish.
     * @param attributionSource The attribution chain to finish.
     * @param fromDatasource Whether the finish is by a datasource (skip finish for the
     *     first attribution source in the chain as this is the datasource)
     */
    public void finishDataDelivery(int op, @NonNull AttributionSourceState attributionSource,
            boolean fromDatasource) {
        Objects.requireNonNull(attributionSource);
        try {
            mService.finishDataDelivery(op, attributionSource, fromDatasource);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks an app op by validating the entire attribution source chain. The op is
     * also noted/started for the entire attribution chain.
     *
     * @param op The op to check.
     * @param attributionSource The attribution chain to check.
     * @param message Message associated with the permission if permission has an app op
     * @param forDataDelivery Whether the check is for delivering data if permission has an app op
     * @param startDataDelivery Whether to start data delivery (start op) if permission has
     *     an app op
     * @return The op check result.
     */
    @PermissionResult
    public int checkOp(int op, @NonNull AttributionSourceState attributionSource,
            @Nullable String message, boolean forDataDelivery, boolean startDataDelivery) {
        Objects.requireNonNull(attributionSource);
        try {
            return mService.checkOp(op, attributionSource, message, forDataDelivery,
                    startDataDelivery);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return PERMISSION_HARD_DENIED;
    }
}
