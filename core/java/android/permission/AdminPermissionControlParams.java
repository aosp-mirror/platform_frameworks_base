/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;

import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.admin.DevicePolicyManager;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * A data object representing an admin's request to control a certain permission
 * for a certain app.
 * This class is processed by the Permission Controller's
 * setRuntimePermissionGrantStateByDeviceAdmin method.
 *
 * @hide
 */
@SystemApi
public final class AdminPermissionControlParams implements Parcelable {
    // The package to grant/deny the permission to.
    private final @NonNull String mGranteePackageName;
    // The permission to grant/deny.
    private final @NonNull String mPermission;
    // The grant state (granted/denied/default).
    private final @DevicePolicyManager.PermissionGrantState int mGrantState;
    // Whether the admin can grant sensors-related permissions.
    private final boolean mCanAdminGrantSensorsPermissions;

    /**
     * @hide
     * A new instance is only created by the framework, so the constructor need not be visible
     * as system API.
     */
    public AdminPermissionControlParams(@NonNull String granteePackageName,
            @NonNull String permission,
            @DevicePolicyManager.PermissionGrantState int grantState,
            boolean canAdminGrantSensorsPermissions) {
        Preconditions.checkStringNotEmpty(granteePackageName, "Package name must not be empty.");
        Preconditions.checkStringNotEmpty(permission, "Permission must not be empty.");
        checkArgument(grantState == PERMISSION_GRANT_STATE_GRANTED
                || grantState == PERMISSION_GRANT_STATE_DENIED
                || grantState == PERMISSION_GRANT_STATE_DEFAULT);

        mGranteePackageName = granteePackageName;
        mPermission = permission;
        mGrantState = grantState;
        mCanAdminGrantSensorsPermissions = canAdminGrantSensorsPermissions;
    }

    public static final @NonNull Creator<AdminPermissionControlParams> CREATOR =
            new Creator<AdminPermissionControlParams>() {
                @Override
                public AdminPermissionControlParams createFromParcel(Parcel in) {
                    String granteePackageName = in.readString();
                    String permission = in.readString();
                    int grantState = in.readInt();
                    boolean mayAdminGrantSensorPermissions = in.readBoolean();

                    return new AdminPermissionControlParams(granteePackageName, permission,
                            grantState, mayAdminGrantSensorPermissions);
                }

                @Override
                public AdminPermissionControlParams[] newArray(int size) {
                    return new AdminPermissionControlParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mGranteePackageName);
        dest.writeString(mPermission);
        dest.writeInt(mGrantState);
        dest.writeBoolean(mCanAdminGrantSensorsPermissions);
    }

    /** Returns the name of the package the permission applies to */
    public @NonNull String getGranteePackageName() {
        return mGranteePackageName;
    }

    /** Returns the permission name */
    public @NonNull String getPermission() {
        return mPermission;
    }

    /** Returns the grant state */
    public @DevicePolicyManager.PermissionGrantState int getGrantState() {
        return mGrantState;
    }

    /**
     * return true if the admin may control grants of permissions related to sensors.
     */
    public boolean canAdminGrantSensorsPermissions() {
        return mCanAdminGrantSensorsPermissions;
    }

    @Override
    public String toString() {
        return String.format(
                "Grantee %s Permission %s state: %d admin grant of sensors permissions: %b",
                mGranteePackageName, mPermission, mGrantState, mCanAdminGrantSensorsPermissions);
    }
}
