/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.companion.DeviceId.TYPE_MAC_ADDRESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A record indicating that a device with a given address was confirmed by the user to be
 * associated to a given companion app
 *
 * @hide
 * TODO(b/1979395): un-hide and rename to AssociationInfo when implementing public APIs that use
 *                  this class.
 */
public final class AssociationInfo implements Parcelable {
    /**
     * A unique ID of this Association record.
     * Disclosed to the clients (ie. companion applications) for referring to this record (eg. in
     * {@code disassociate()} API call).
     */
    private final int mAssociationId;

    private final @UserIdInt int mUserId;
    private final @NonNull String mPackageName;

    private final @NonNull List<DeviceId> mDeviceIds;
    private final @Nullable String mDeviceProfile;

    private final boolean mManagedByCompanionApp;
    private boolean mNotifyOnDeviceNearby;
    private final long mTimeApprovedMs;

    /**
     * Creates a new Association.
     * Only to be used by the CompanionDeviceManagerService.
     *
     * @hide
     */
    public AssociationInfo(int associationId, @UserIdInt int userId, @NonNull String packageName,
            @NonNull List<DeviceId> deviceIds, @Nullable String deviceProfile,
            boolean managedByCompanionApp, boolean notifyOnDeviceNearby, long timeApprovedMs) {
        if (associationId <= 0) {
            throw new IllegalArgumentException("Association ID should be greater than 0");
        }
        validateDeviceIds(deviceIds);

        mAssociationId = associationId;

        mUserId = userId;
        mPackageName = packageName;

        mDeviceProfile = deviceProfile;
        mDeviceIds = new ArrayList<>(deviceIds);

        mManagedByCompanionApp = managedByCompanionApp;
        mNotifyOnDeviceNearby = notifyOnDeviceNearby;
        mTimeApprovedMs = timeApprovedMs;
    }

    /**
     * @return the unique ID of this association record.
     */
    public int getAssociationId() {
        return mAssociationId;
    }

    /** @hide */
    public int getUserId() {
        return mUserId;
    }

    /** @hide */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * @return list of the device's IDs. At any time a device has at least 1 ID.
     */
    public @NonNull List<DeviceId> getDeviceIds() {
        return Collections.unmodifiableList(mDeviceIds);
    }

    /**
     * @param type type of the ID.
     * @return ID of the type if the device has such ID, {@code null} otherwise.
     */
    public @Nullable String getIdOfType(@NonNull String type) {
        for (int i = mDeviceIds.size() - 1; i >= 0; i--) {
            final DeviceId id = mDeviceIds.get(i);
            if (Objects.equals(mDeviceIds.get(i).getType(), type)) return id.getValue();
        }
        return null;
    }

    /** @hide */
    public @NonNull String getDeviceMacAddress() {
        return Objects.requireNonNull(getIdOfType(TYPE_MAC_ADDRESS),
                "MAC address of this device is not specified.");
    }

    /**
     * @return the profile of the device.
     */
    public @Nullable String getDeviceProfile() {
        return mDeviceProfile;
    }

    /** @hide */
    public boolean isManagedByCompanionApp() {
        return mManagedByCompanionApp;
    }

    /**
     * Should only be used by the CdmService.
     * @hide
     */
    public void setNotifyOnDeviceNearby(boolean notifyOnDeviceNearby) {
        mNotifyOnDeviceNearby = notifyOnDeviceNearby;
    }

    /** @hide */
    public boolean isNotifyOnDeviceNearby() {
        return mNotifyOnDeviceNearby;
    }

    /** @hide */
    public long getTimeApprovedMs() {
        return mTimeApprovedMs;
    }

    /** @hide */
    public boolean belongsToPackage(@UserIdInt int userId, String packageName) {
        return mUserId == userId && Objects.equals(mPackageName, packageName);
    }

    @Override
    public String toString() {
        return "Association{"
                + "mAssociationId=" + mAssociationId
                + ", mUserId=" + mUserId
                + ", mPackageName='" + mPackageName + '\''
                + ", mDeviceIds=" + mDeviceIds
                + ", mDeviceProfile='" + mDeviceProfile + '\''
                + ", mManagedByCompanionApp=" + mManagedByCompanionApp
                + ", mNotifyOnDeviceNearby=" + mNotifyOnDeviceNearby
                + ", mTimeApprovedMs=" + new Date(mTimeApprovedMs)
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssociationInfo)) return false;
        final AssociationInfo that = (AssociationInfo) o;
        return mAssociationId == that.mAssociationId
                && mUserId == that.mUserId
                && mManagedByCompanionApp == that.mManagedByCompanionApp
                && mNotifyOnDeviceNearby == that.mNotifyOnDeviceNearby
                && mTimeApprovedMs == that.mTimeApprovedMs
                && Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mDeviceProfile, that.mDeviceProfile)
                && Objects.equals(mDeviceIds, that.mDeviceIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAssociationId, mUserId, mPackageName, mDeviceIds, mDeviceProfile,
                mManagedByCompanionApp, mNotifyOnDeviceNearby, mTimeApprovedMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAssociationId);

        dest.writeInt(mUserId);
        dest.writeString(mPackageName);

        dest.writeParcelableList(mDeviceIds, 0);
        dest.writeString(mDeviceProfile);

        dest.writeBoolean(mManagedByCompanionApp);
        dest.writeBoolean(mNotifyOnDeviceNearby);
        dest.writeLong(mTimeApprovedMs);
    }

    private AssociationInfo(@NonNull Parcel in) {
        mAssociationId = in.readInt();

        mUserId = in.readInt();
        mPackageName = in.readString();

        mDeviceIds = in.readParcelableList(new ArrayList<>(), DeviceId.class.getClassLoader());
        mDeviceProfile = in.readString();

        mManagedByCompanionApp = in.readBoolean();
        mNotifyOnDeviceNearby = in.readBoolean();
        mTimeApprovedMs = in.readLong();
    }

    public static final Parcelable.Creator<AssociationInfo> CREATOR =
            new Parcelable.Creator<AssociationInfo>() {
        @Override
        public AssociationInfo[] newArray(int size) {
            return new AssociationInfo[size];
        }

        @Override
        public AssociationInfo createFromParcel(@NonNull Parcel in) {
            return new AssociationInfo(in);
        }
    };

    private static void validateDeviceIds(@NonNull List<DeviceId> ids) {
        if (ids.isEmpty()) throw new IllegalArgumentException("Device must have at least 1 id.");

        // Make sure none of the IDs are null, and they all have different types.
        final Set<String> types = new HashSet<>(ids.size());
        for (int i = ids.size() - 1; i >= 0; i--) {
            final DeviceId deviceId = ids.get(i);
            if (deviceId == null) throw new IllegalArgumentException("DeviceId must not be null");
            if (!types.add(deviceId.getType())) {
                throw new IllegalArgumentException(
                        "DeviceId cannot have multiple IDs of the same type");
            }
        }
    }
}
