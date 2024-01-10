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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Date;
import java.util.Objects;

/**
 * Details for a specific "association" that has been established between an app and companion
 * device.
 * <p>
 * An association gives an app the ability to interact with a companion device without needing to
 * acquire broader runtime permissions. An association only exists after the user has confirmed that
 * an app should have access to a companion device.
 */
public final class AssociationInfo implements Parcelable {
    /**
     * A String indicates the selfManaged device is not connected.
     */
    private static final String LAST_TIME_CONNECTED_NONE = "None";
    /**
     * A unique ID of this Association record.
     * Disclosed to the clients (i.e. companion applications) for referring to this record (e.g. in
     * {@code disassociate()} API call).
     */
    private final int mId;
    @UserIdInt
    private final int mUserId;
    @NonNull
    private final String mPackageName;
    @Nullable
    private final String mTag;
    @Nullable
    private final MacAddress mDeviceMacAddress;
    @Nullable
    private final CharSequence mDisplayName;
    @Nullable
    private final String mDeviceProfile;
    @Nullable
    private final AssociatedDevice mAssociatedDevice;
    private final boolean mSelfManaged;
    private final boolean mNotifyOnDeviceNearby;
    /**
     * Indicates that the association has been revoked (removed), but we keep the association
     * record for final clean up (e.g. removing the app from the list of the role holders).
     *
     * @see CompanionDeviceManager#disassociate(int)
     */
    private final boolean mRevoked;
    /**
     * Indicates that the association is waiting for its corresponding companion app to be installed
     * before it can be added to CDM. This is likely because it was restored onto the device from a
     * backup.
     */
    private final boolean mPending;
    private final long mTimeApprovedMs;
    /**
     * A long value indicates the last time connected reported by selfManaged devices
     * Default value is Long.MAX_VALUE.
     */
    private final long mLastTimeConnectedMs;
    private final int mSystemDataSyncFlags;

    /**
     * Creates a new Association.
     *
     * @hide
     */
    public AssociationInfo(int id, @UserIdInt int userId, @NonNull String packageName,
            @Nullable String tag, @Nullable MacAddress macAddress,
            @Nullable CharSequence displayName, @Nullable String deviceProfile,
            @Nullable AssociatedDevice associatedDevice, boolean selfManaged,
            boolean notifyOnDeviceNearby, boolean revoked, boolean pending, long timeApprovedMs,
            long lastTimeConnectedMs, int systemDataSyncFlags) {
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
        mTag = tag;
        mDeviceProfile = deviceProfile;
        mAssociatedDevice = associatedDevice;
        mSelfManaged = selfManaged;
        mNotifyOnDeviceNearby = notifyOnDeviceNearby;
        mRevoked = revoked;
        mPending = pending;
        mTimeApprovedMs = timeApprovedMs;
        mLastTimeConnectedMs = lastTimeConnectedMs;
        mSystemDataSyncFlags = systemDataSyncFlags;
    }

    /**
     * @return the unique ID of this association record.
     */
    public int getId() {
        return mId;
    }

    /**
     * @return the ID of the user who "owns" this association.
     * @hide
     */
    @UserIdInt
    public int getUserId() {
        return mUserId;
    }

    /**
     * @return the package name of the app which this association refers to.
     * @hide
     */
    @SystemApi
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * @return the tag of this association.
     * @see CompanionDeviceManager#setAssociationTag(int, String)
     */
    @FlaggedApi(Flags.FLAG_ASSOCIATION_TAG)
    @Nullable
    public String getTag() {
        return mTag;
    }

    /**
     * @return the MAC address of the device.
     */
    @Nullable
    public MacAddress getDeviceMacAddress() {
        return mDeviceMacAddress;
    }

    /** @hide */
    @Nullable
    public String getDeviceMacAddressAsString() {
        return mDeviceMacAddress != null ? mDeviceMacAddress.toString().toUpperCase() : null;
    }

    /**
     * @return the display name of the companion device (optionally) provided by the companion
     * application.
     *
     * @see AssociationRequest.Builder#setDisplayName(CharSequence)
     */
    @Nullable
    public CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * @return the companion device profile used when establishing this
     *         association, or {@code null} if no specific profile was used.
     * @see AssociationRequest.Builder#setDeviceProfile(String)
     */
    @Nullable
    public String getDeviceProfile() {
        return mDeviceProfile;
    }

    /**
     * Companion device that was associated. Note that this field is not persisted across sessions.
     * Device can be one of the following types:
     *
     * <ul>
     *     <li>for classic Bluetooth - {@link AssociatedDevice#getBluetoothDevice()}</li>
     *     <li>for Bluetooth LE - {@link AssociatedDevice#getBleDevice()}</li>
     *     <li>for WiFi - {@link AssociatedDevice#getWifiDevice()}</li>
     * </ul>
     *
     * @return the companion device that was associated, or {@code null} if the device is
     *         self-managed or this association info was retrieved from persistent storage.
     */
    @Nullable
    public AssociatedDevice getAssociatedDevice() {
        return mAssociatedDevice;
    }

    /**
     * @return whether the association is managed by the companion application it belongs to.
     * @see AssociationRequest.Builder#setSelfManaged(boolean)
     */
    @SuppressLint("UnflaggedApi") // promoting from @SystemApi
    public boolean isSelfManaged() {
        return mSelfManaged;
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

    /**
     * @return if the association has been revoked (removed).
     * @hide
     */
    public boolean isRevoked() {
        return mRevoked;
    }

    /**
     * @return true if the association is waiting for its corresponding app to be installed
     * before it can be added to CDM.
     * @hide
     */
    public boolean isPending() {
        return mPending;
    }

    /**
     * @return the last time self reported disconnected for selfManaged only.
     * @hide
     */
    public long getLastTimeConnectedMs() {
        return mLastTimeConnectedMs;
    }

    /**
     * @return Enabled system data sync flags set via
     * {@link CompanionDeviceManager#enableSystemDataSyncForTypes(int, int)} (int, int)} and
     * {@link CompanionDeviceManager#disableSystemDataSyncForTypes(int, int)} (int, int)}.
     * Or by default all flags are 1 (enabled).
     */
    public int getSystemDataSyncFlags() {
        return mSystemDataSyncFlags;
    }

    /**
     * Utility method for checking if the association represents a device with the given MAC
     * address.
     *
     * @return {@code false} if the association is "self-managed".
     *         {@code false} if the {@code addr} is {@code null} or is not a valid MAC address.
     *         Otherwise - the result of {@link MacAddress#equals(Object)}
     *
     * @hide
     */
    public boolean isLinkedTo(@Nullable String addr) {
        if (mSelfManaged) return false;

        if (addr == null) return false;

        final MacAddress macAddress;
        try {
            macAddress = MacAddress.fromString(addr);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return macAddress.equals(mDeviceMacAddress);
    }

    /**
     * Utility method to be used by CdmService only.
     *
     * @return whether CdmService should bind the companion application that "owns" this association
     *         when the device is present.
     *
     * @hide
     */
    public boolean shouldBindWhenPresent() {
        return mNotifyOnDeviceNearby || mSelfManaged;
    }

    /** @hide */
    @NonNull
    public String toShortString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("id=").append(mId);
        if (mDeviceMacAddress != null) {
            sb.append(", addr=").append(getDeviceMacAddressAsString());
        }
        if (mSelfManaged) {
            sb.append(", self-managed");
        }
        sb.append(", pkg=u").append(mUserId).append('/').append(mPackageName);
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Association{"
                + "mId=" + mId
                + ", mUserId=" + mUserId
                + ", mPackageName='" + mPackageName + '\''
                + ", mTag='" + mTag + '\''
                + ", mDeviceMacAddress=" + mDeviceMacAddress
                + ", mDisplayName='" + mDisplayName + '\''
                + ", mDeviceProfile='" + mDeviceProfile + '\''
                + ", mSelfManaged=" + mSelfManaged
                + ", mAssociatedDevice=" + mAssociatedDevice
                + ", mNotifyOnDeviceNearby=" + mNotifyOnDeviceNearby
                + ", mRevoked=" + mRevoked
                + ", mPending=" + mPending
                + ", mTimeApprovedMs=" + new Date(mTimeApprovedMs)
                + ", mLastTimeConnectedMs=" + (
                    mLastTimeConnectedMs == Long.MAX_VALUE
                        ? LAST_TIME_CONNECTED_NONE : new Date(mLastTimeConnectedMs))
                + ", mSystemDataSyncFlags=" + mSystemDataSyncFlags
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
                && mRevoked == that.mRevoked
                && mPending == that.mPending
                && mTimeApprovedMs == that.mTimeApprovedMs
                && mLastTimeConnectedMs == that.mLastTimeConnectedMs
                && Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mTag, that.mTag)
                && Objects.equals(mDeviceMacAddress, that.mDeviceMacAddress)
                && Objects.equals(mDisplayName, that.mDisplayName)
                && Objects.equals(mDeviceProfile, that.mDeviceProfile)
                && Objects.equals(mAssociatedDevice, that.mAssociatedDevice)
                && mSystemDataSyncFlags == that.mSystemDataSyncFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mUserId, mPackageName, mTag, mDeviceMacAddress, mDisplayName,
                mDeviceProfile, mAssociatedDevice, mSelfManaged, mNotifyOnDeviceNearby, mRevoked,
                mPending, mTimeApprovedMs, mLastTimeConnectedMs, mSystemDataSyncFlags);
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
        dest.writeString(mTag);
        dest.writeTypedObject(mDeviceMacAddress, 0);
        dest.writeCharSequence(mDisplayName);
        dest.writeString(mDeviceProfile);
        dest.writeTypedObject(mAssociatedDevice, 0);
        dest.writeBoolean(mSelfManaged);
        dest.writeBoolean(mNotifyOnDeviceNearby);
        dest.writeBoolean(mRevoked);
        dest.writeBoolean(mPending);
        dest.writeLong(mTimeApprovedMs);
        dest.writeLong(mLastTimeConnectedMs);
        dest.writeInt(mSystemDataSyncFlags);
    }

    private AssociationInfo(@NonNull Parcel in) {
        mId = in.readInt();
        mUserId = in.readInt();
        mPackageName = in.readString();
        mTag = in.readString();
        mDeviceMacAddress = in.readTypedObject(MacAddress.CREATOR);
        mDisplayName = in.readCharSequence();
        mDeviceProfile = in.readString();
        mAssociatedDevice = in.readTypedObject(AssociatedDevice.CREATOR);
        mSelfManaged = in.readBoolean();
        mNotifyOnDeviceNearby = in.readBoolean();
        mRevoked = in.readBoolean();
        mPending = in.readBoolean();
        mTimeApprovedMs = in.readLong();
        mLastTimeConnectedMs = in.readLong();
        mSystemDataSyncFlags = in.readInt();
    }

    @NonNull
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

    /**
     * Builder for {@link AssociationInfo}
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NEW_ASSOCIATION_BUILDER)
    @TestApi
    public static final class Builder {
        private final int mId;
        private final int mUserId;
        private final String mPackageName;
        private String mTag;
        private MacAddress mDeviceMacAddress;
        private CharSequence mDisplayName;
        private String mDeviceProfile;
        private AssociatedDevice mAssociatedDevice;
        private boolean mSelfManaged;
        private boolean mNotifyOnDeviceNearby;
        private boolean mRevoked;
        private boolean mPending;
        private long mTimeApprovedMs;
        private long mLastTimeConnectedMs;
        private int mSystemDataSyncFlags;

        /** @hide */
        @TestApi
        public Builder(int id, int userId, @NonNull String packageName) {
            mId = id;
            mUserId = userId;
            mPackageName = packageName;
        }

        /** @hide */
        @TestApi
        public Builder(@NonNull AssociationInfo info) {
            mId = info.mId;
            mUserId = info.mUserId;
            mPackageName = info.mPackageName;
            mTag = info.mTag;
            mDeviceMacAddress = info.mDeviceMacAddress;
            mDisplayName = info.mDisplayName;
            mDeviceProfile = info.mDeviceProfile;
            mAssociatedDevice = info.mAssociatedDevice;
            mSelfManaged = info.mSelfManaged;
            mNotifyOnDeviceNearby = info.mNotifyOnDeviceNearby;
            mRevoked = info.mRevoked;
            mPending = info.mPending;
            mTimeApprovedMs = info.mTimeApprovedMs;
            mLastTimeConnectedMs = info.mLastTimeConnectedMs;
            mSystemDataSyncFlags = info.mSystemDataSyncFlags;
        }

        /**
         * This builder is used specifically to create a new association to be restored to a device
         * that is potentially using a different user ID from the backed-up device.
         *
         * @hide
         */
        public Builder(int id, int userId, @NonNull String packageName, AssociationInfo info) {
            mId = id;
            mUserId = userId;
            mPackageName = packageName;
            mTag = info.mTag;
            mDeviceMacAddress = info.mDeviceMacAddress;
            mDisplayName = info.mDisplayName;
            mDeviceProfile = info.mDeviceProfile;
            mAssociatedDevice = info.mAssociatedDevice;
            mSelfManaged = info.mSelfManaged;
            mNotifyOnDeviceNearby = info.mNotifyOnDeviceNearby;
            mRevoked = info.mRevoked;
            mPending = info.mPending;
            mTimeApprovedMs = info.mTimeApprovedMs;
            mLastTimeConnectedMs = info.mLastTimeConnectedMs;
            mSystemDataSyncFlags = info.mSystemDataSyncFlags;
        }

        /** @hide */
        @FlaggedApi(Flags.FLAG_ASSOCIATION_TAG)
        @TestApi
        @NonNull
        public Builder setTag(@Nullable String tag) {
            mTag = tag;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setDeviceMacAddress(@Nullable MacAddress deviceMacAddress) {
            mDeviceMacAddress = deviceMacAddress;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setDisplayName(@Nullable CharSequence displayName) {
            mDisplayName = displayName;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setDeviceProfile(@Nullable String deviceProfile) {
            mDeviceProfile = deviceProfile;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setAssociatedDevice(@Nullable AssociatedDevice associatedDevice) {
            mAssociatedDevice = associatedDevice;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setSelfManaged(boolean selfManaged) {
            mSelfManaged = selfManaged;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setNotifyOnDeviceNearby(boolean notifyOnDeviceNearby) {
            mNotifyOnDeviceNearby = notifyOnDeviceNearby;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setRevoked(boolean revoked) {
            mRevoked = revoked;
            return this;
        }

        /** @hide */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setPending(boolean pending) {
            mPending = pending;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setTimeApproved(long timeApprovedMs) {
            if (timeApprovedMs < 0) {
                throw new IllegalArgumentException("timeApprovedMs must be positive. Was given ("
                        + timeApprovedMs + ")");
            }
            mTimeApprovedMs = timeApprovedMs;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setLastTimeConnected(long lastTimeConnectedMs) {
            if (lastTimeConnectedMs < 0) {
                throw new IllegalArgumentException(
                        "lastTimeConnectedMs must not be negative! (Given " + lastTimeConnectedMs
                                + " )");
            }
            mLastTimeConnectedMs = lastTimeConnectedMs;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setSystemDataSyncFlags(int flags) {
            mSystemDataSyncFlags = flags;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public AssociationInfo build() {
            if (mId <= 0) {
                throw new IllegalArgumentException("Association ID should be greater than 0");
            }
            if (mDeviceMacAddress == null && mDisplayName == null) {
                throw new IllegalArgumentException("MAC address and the display name must NOT be "
                        + "null at the same time");
            }
            return new AssociationInfo(
                    mId,
                    mUserId,
                    mPackageName,
                    mTag,
                    mDeviceMacAddress,
                    mDisplayName,
                    mDeviceProfile,
                    mAssociatedDevice,
                    mSelfManaged,
                    mNotifyOnDeviceNearby,
                    mRevoked,
                    mPending,
                    mTimeApprovedMs,
                    mLastTimeConnectedMs,
                    mSystemDataSyncFlags
            );
        }
    }
}
