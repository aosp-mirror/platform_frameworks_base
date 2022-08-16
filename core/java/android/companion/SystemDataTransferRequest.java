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

package android.companion;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.OneTimeUseBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A request for users to allow the companion app to transfer system data to the companion devices.
 *
 * @hide
 */
public final class SystemDataTransferRequest implements Parcelable {

    private final int mAssociationId;
    private final boolean mPermissionSyncAllPackages;
    private final List<String> mPermissionSyncPackages;

    /**
     * @hide
     */
    public SystemDataTransferRequest(int associationId, boolean syncAllPackages,
            @Nullable List<String> permissionSyncPackages) {
        mAssociationId = associationId;
        mPermissionSyncAllPackages = syncAllPackages;
        mPermissionSyncPackages = permissionSyncPackages;
    }

    public int getAssociationId() {
        return mAssociationId;
    }

    @NonNull
    public boolean isPermissionSyncAllPackages() {
        return mPermissionSyncAllPackages;
    }

    @NonNull
    public List<String> getPermissionSyncPackages() {
        return mPermissionSyncPackages;
    }

    /**
     * A builder for {@link SystemDataTransferRequest}.
     *
     * <p>You have to call one of the below methods to create a valid request</p>
     * <br>1. {@link #setPermissionSyncAllPackages()}
     * <br>2. {@link #setPermissionSyncPackages(List)}
     */
    public static final class Builder extends OneTimeUseBuilder<SystemDataTransferRequest> {

        private final int mAssociationId;
        private boolean mPermissionSyncAllPackages;
        private List<String> mPermissionSyncPackages = new ArrayList<>();

        public Builder(int associationId) {
            mAssociationId = associationId;
        }

        /**
         * Call to sync permissions for all the packages. You can optionally call
         * {@link #setPermissionSyncPackages(List)} to specify the packages to sync permissions.
         *
         * <p>The system will only sync permissions that are explicitly granted by the user.</p>
         *
         * <p>If a permission is granted or revoked by the system or a policy, even if the user has
         * explicitly granted or revoked the permission earlier, the permission will be ignored.</p>
         *
         * <p>If a system or policy granted or revoked permission is granted or revoked by the user
         * later, the permission will be ignored.</p>
         *
         * @see #setPermissionSyncPackages(List)
         *
         * @return the builder
         */
        @NonNull
        public Builder setPermissionSyncAllPackages() {
            mPermissionSyncAllPackages = true;
            return this;
        }

        /**
         * Set a list of packages to sync permissions. You can optionally call
         * {@link #setPermissionSyncAllPackages()} to sync permissions for all the packages.
         *
         * @see #setPermissionSyncAllPackages()
         *
         * @param permissionSyncPackages packages to sync permissions
         * @return builder
         */
        @NonNull
        public Builder setPermissionSyncPackages(@NonNull List<String> permissionSyncPackages) {
            mPermissionSyncPackages = permissionSyncPackages;
            return this;
        }

        @Override
        @NonNull
        public SystemDataTransferRequest build() {
            return new SystemDataTransferRequest(mAssociationId, mPermissionSyncAllPackages,
                    mPermissionSyncPackages);
        }
    }

    SystemDataTransferRequest(Parcel in) {
        mAssociationId = in.readInt();
        mPermissionSyncAllPackages = in.readBoolean();
        mPermissionSyncPackages = Arrays.asList(in.createString8Array());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAssociationId);
        dest.writeBoolean(mPermissionSyncAllPackages);
        dest.writeString8Array(mPermissionSyncPackages.toArray(new String[0]));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<SystemDataTransferRequest> CREATOR =
            new Creator<SystemDataTransferRequest>() {
                @Override
                public SystemDataTransferRequest createFromParcel(Parcel in) {
                    return new SystemDataTransferRequest(in);
                }

                @Override
                public SystemDataTransferRequest[] newArray(int size) {
                    return new SystemDataTransferRequest[size];
                }
            };
}
