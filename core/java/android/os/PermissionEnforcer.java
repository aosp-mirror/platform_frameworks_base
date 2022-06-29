/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.AttributionSource;
import android.content.Context;
import android.content.PermissionChecker;
import android.permission.PermissionCheckerManager;

/**
 * PermissionEnforcer check permissions for AIDL-generated services which use
 * the @EnforcePermission annotation.
 *
 * <p>AIDL services may be annotated with @EnforcePermission which will trigger
 * the generation of permission check code. This generated code relies on
 * PermissionEnforcer to validate the permissions. The methods available are
 * purposely similar to the AIDL annotation syntax.
 *
 * @see android.permission.PermissionManager
 *
 * @hide
 */
@SystemService(Context.PERMISSION_ENFORCER_SERVICE)
public class PermissionEnforcer {

    private final Context mContext;

    /** Protected constructor. Allows subclasses to instantiate an object
     *  without using a Context.
     */
    protected PermissionEnforcer() {
        mContext = null;
    }

    /** Constructor, prefer using the fromContext static method when possible */
    public PermissionEnforcer(@NonNull Context context) {
        mContext = context;
    }

    @PermissionCheckerManager.PermissionResult
    protected int checkPermission(@NonNull String permission, @NonNull AttributionSource source) {
        return PermissionChecker.checkPermissionForDataDelivery(
            mContext, permission, PermissionChecker.PID_UNKNOWN, source, "" /* message */);
    }

    public void enforcePermission(@NonNull String permission, @NonNull
            AttributionSource source) throws SecurityException {
        int result = checkPermission(permission, source);
        if (result != PermissionCheckerManager.PERMISSION_GRANTED) {
            throw new SecurityException("Access denied, requires: " + permission);
        }
    }

    public void enforcePermissionAllOf(@NonNull String[] permissions,
            @NonNull AttributionSource source) throws SecurityException {
        for (String permission : permissions) {
            int result = checkPermission(permission, source);
            if (result != PermissionCheckerManager.PERMISSION_GRANTED) {
                throw new SecurityException("Access denied, requires: allOf={"
                        + String.join(", ", permissions) + "}");
            }
        }
    }

    public void enforcePermissionAnyOf(@NonNull String[] permissions,
            @NonNull AttributionSource source) throws SecurityException {
        for (String permission : permissions) {
            int result = checkPermission(permission, source);
            if (result == PermissionCheckerManager.PERMISSION_GRANTED) {
                return;
            }
        }
        throw new SecurityException("Access denied, requires: anyOf={"
                + String.join(", ", permissions) + "}");
    }

    /**
     * Returns a new PermissionEnforcer based on a Context.
     *
     * @hide
     */
    public static PermissionEnforcer fromContext(@NonNull Context context) {
        return context.getSystemService(PermissionEnforcer.class);
    }
}
