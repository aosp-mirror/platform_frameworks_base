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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Date;
import java.util.Objects;

/**
 * A record indicating that a device with a given address was confirmed by the user to be
 * associated to a given companion app
 *
 * @hide
 * TODO(b/1979395): un-hide.
 */
public final class AssociationInfo implements Parcelable {
    /**
     * A unique ID of this Association record.
     * Disclosed to the clients (ie. companion applications) for referring to this record (eg. in
     * {@code disassociate()} API call).
     */
    private final int mId;

    private final @UserIdInt int mUserId;
    private final @NonNull String mPackageName;

    private final @Nullable MacAddress mDeviceMacAddress;
    private final @Nullable CharSequence mDisplayName;
    private final @Nullable String mDeviceProfile;

    private final boolean mSelfManaged;
    private boolean mNotifyOnDeviceNearby;
    private final long mTimeApprovedMs;

    /**
     * Creates a new Association.
     * Only to be used by the CompanionDeviceManagerService.
     *
     * @hide
     */
    public AssociationInfo(int id, @UserIdInt int userId, @NonNull String packageName,
            @Nullable MacAddress macAddress, @Nullable CharSequence displayName,
            @Nullable String deviceProfile, boolean selfManaged, boolean notifyOnDeviceNearby,
            long timeApprovedMs) {
        if (id <= 0) {
            throw new IllegalArgumentException("Association ID should be greater than 0");
        }
        if (macAddress == null && displayName == null) {
            throw new IllegalArgumentException("MAC address and the Display Name must NOT be null "
                    + "at the same time");
        }

        mId = id;

        mUserId = userId;
        mPackageName = packageName;

        mDeviceMacAddress = macAddress;
        mDisplayName = displayName;
        mDeviceProfile = deviceProfile;

        mSelfManaged = selfManaged;
        mNotifyOnDeviceNearby = notifyOnDeviceNearby;
        mTimeApprovedMs = timeApprovedMs;
    }

    /**
     * @return the unique ID of this association record.
     */
    public int getId() {
        return mId;
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
     * @return the MAC address of the device.
     */
    public @Nullable MacAddress getDeviceMacAddress() {
        return mDeviceMacAddress;
    }

    /** @hide */
    public @Nullable String getDeviceMacAddressAsString() {
        return mDeviceMacAddress != null ? mDeviceMacAddress.toString() : null;
    }

    /**
     * @return the display name of the device (only "self-managed" association are expected to
     * specify a non-null display name of the device).
     *
     * @see #isSelfManaged()
     */
    public @Nullable CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * @return the profile of the device.
     */
    public @Nullable String getDeviceProfile() {
        return mDeviceProfile;
    }

    /**
     * @return whether the association is managed by the companion application it belongs to.
     * @hide
     */
    public boolean isSelfManaged() {
        return mSelfManaged;
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
                + "mId=" + mId
                + ", mUserId=" + mUserId
                + ", mPackageName='" + mPackageName + '\''
                + ", mDeviceMacAddress=" + mDeviceMacAddress
                + ", mDisplayName='" + mDisplayName + '\''
                + ", mDeviceProfile='" + mDeviceProfile + '\''
                + ", mSelfManaged=" + mSelfManaged
                + ", mNotifyOnDeviceNearby=" + mNotifyOnDeviceNearby
                + ", mTimeApprovedMs=" + new Date(mTimeApprovedMs)
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssociationInfo)) return false;
        final AssociationInfo that = (AssociationInfo) o;
        return mId == that.mId
                && mUserId == that.mUserId
                && mSelfManaged == that.mSelfManaged
                && mNotifyOnDeviceNearby == that.mNotifyOnDeviceNearby
                && mTimeApprovedMs == that.mTimeApprovedMs
                && Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mDeviceMacAddress, that.mDeviceMacAddress)
                && Objects.equals(mDisplayName, that.mDisplayName)
                && Objects.equals(mDeviceProfile, that.mDeviceProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mUserId, mPackageName, mDeviceMacAddress, mDisplayName,
                mDeviceProfile, mSelfManaged, mNotifyOnDeviceNearby, mTimeApprovedMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);

        dest.writeInt(mUserId);
        dest.writeString(mPackageName);

        dest.writeTypedObject(mDeviceMacAddress, 0);
        dest.writeCharSequence(mDisplayName);
        dest.writeString(mDeviceProfile);

        dest.writeBoolean(mSelfManaged);
        dest.writeBoolean(mNotifyOnDeviceNearby);
        dest.writeLong(mTimeApprovedMs);
    }

    private AssociationInfo(@NonNull Parcel in) {
        mId = in.readInt();

        mUserId = in.readInt();
        mPackageName = in.readString();

        mDeviceMacAddress = in.readTypedObject(MacAddress.CREATOR);
        mDisplayName = in.readCharSequence();
        mDeviceProfile = in.readString();

        mSelfManaged = in.readBoolean();
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
}
