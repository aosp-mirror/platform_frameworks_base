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
import android.annotation.SystemApi;
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
    private final boolean mNotifyOnDeviceNearby;
    private final long mTimeApprovedMs;
    /**
     * A long value indicates the last time connected reported by selfManaged devices
     * Default value is Long.MAX_VALUE.
     */
    private final long mLastTimeConnectedMs;

    /**
     * Creates a new Association.
     * Only to be used by the CompanionDeviceManagerService.
     *
     * @hide
     */
    public AssociationInfo(int id, @UserIdInt int userId, @NonNull String packageName,
            @Nullable MacAddress macAddress, @Nullable CharSequence displayName,
            @Nullable String deviceProfile, boolean selfManaged, boolean notifyOnDeviceNearby,
            long timeApprovedMs, long lastTimeConnectedMs) {
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
        mLastTimeConnectedMs = lastTimeConnectedMs;
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
    public @UserIdInt int getUserId() {
        return mUserId;
    }

    /**
     * @return the package name of the app which this association refers to.
     * @hide
     */
    @SystemApi
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
        return mDeviceMacAddress != null ? mDeviceMacAddress.toString().toUpperCase() : null;
    }

    /**
     * @return the display name of the companion device (optionally) provided by the companion
     * application.
     *
     * @see AssociationRequest.Builder#setDisplayName(CharSequence)
     */
    public @Nullable CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * @return the companion device profile used when establishing this
     *         association, or {@code null} if no specific profile was used.
     * @see AssociationRequest.Builder#setDeviceProfile(String)
     */
    public @Nullable String getDeviceProfile() {
        return mDeviceProfile;
    }

    /**
     * @return whether the association is managed by the companion application it belongs to.
     * @see AssociationRequest.Builder#setSelfManaged(boolean)
     * @hide
     */
    @SystemApi
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
     * @return the last time self reported disconnected for selfManaged only.
     * @hide
     */
    public Long getLastTimeConnectedMs() {
        return mLastTimeConnectedMs;
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
    public @NonNull String toShortString() {
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
                + ", mDeviceMacAddress=" + mDeviceMacAddress
                + ", mDisplayName='" + mDisplayName + '\''
                + ", mDeviceProfile='" + mDeviceProfile + '\''
                + ", mSelfManaged=" + mSelfManaged
                + ", mNotifyOnDeviceNearby=" + mNotifyOnDeviceNearby
                + ", mTimeApprovedMs=" + new Date(mTimeApprovedMs)
                + ", mLastTimeConnectedMs=" + (
                    mLastTimeConnectedMs == Long.MAX_VALUE
                        ? LAST_TIME_CONNECTED_NONE : new Date(mLastTimeConnectedMs))
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
                && mLastTimeConnectedMs == that.mLastTimeConnectedMs
                && Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mDeviceMacAddress, that.mDeviceMacAddress)
                && Objects.equals(mDisplayName, that.mDisplayName)
                && Objects.equals(mDeviceProfile, that.mDeviceProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mUserId, mPackageName, mDeviceMacAddress, mDisplayName,
                mDeviceProfile, mSelfManaged, mNotifyOnDeviceNearby, mTimeApprovedMs,
                mLastTimeConnectedMs);
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
        dest.writeLong(mLastTimeConnectedMs);
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
        mLastTimeConnectedMs = in.readLong();
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
     * Use this method to obtain a builder that you can use to create a copy of the
     * given {@link AssociationInfo} with modified values of {@code mLastTimeConnected}
     * or {@code mNotifyOnDeviceNearby}.
     * <p>
     *     Note that you <b>must</b> call either {@link Builder#setLastTimeConnected(long)
     *     setLastTimeConnected} or {@link Builder#setNotifyOnDeviceNearby(boolean)
     *     setNotifyOnDeviceNearby} before you will be able to call {@link Builder#build() build}.
     *
     *     This is ensured statically at compile time.
     *
     * @hide
     */
    @NonNull
    public static NonActionableBuilder builder(@NonNull AssociationInfo info) {
        return new Builder(info);
    }

    /**
     * @hide
     */
    public static final class Builder implements NonActionableBuilder {
        @NonNull
        private final AssociationInfo mOriginalInfo;
        private boolean mNotifyOnDeviceNearby;
        private long mLastTimeConnectedMs;

        private Builder(@NonNull AssociationInfo info) {
            mOriginalInfo = info;
            mNotifyOnDeviceNearby = info.mNotifyOnDeviceNearby;
            mLastTimeConnectedMs = info.mLastTimeConnectedMs;
        }

        /**
         * Should only be used by the CompanionDeviceManagerService.
         * @hide
         */
        @Override
        @NonNull
        public Builder setLastTimeConnected(long lastTimeConnectedMs) {
            if (lastTimeConnectedMs < 0) {
                throw new IllegalArgumentException(
                        "lastTimeConnectedMs must not be negative! (Given " + lastTimeConnectedMs
                                + " )");
            }
            mLastTimeConnectedMs = lastTimeConnectedMs;
            return this;
        }

        /**
         * Should only be used by the CompanionDeviceManagerService.
         * @hide
         */
        @Override
        @NonNull
        public Builder setNotifyOnDeviceNearby(boolean notifyOnDeviceNearby) {
            mNotifyOnDeviceNearby = notifyOnDeviceNearby;
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public AssociationInfo build() {
            return new AssociationInfo(
                    mOriginalInfo.mId,
                    mOriginalInfo.mUserId,
                    mOriginalInfo.mPackageName,
                    mOriginalInfo.mDeviceMacAddress,
                    mOriginalInfo.mDisplayName,
                    mOriginalInfo.mDeviceProfile,
                    mOriginalInfo.mSelfManaged,
                    mNotifyOnDeviceNearby,
                    mOriginalInfo.mTimeApprovedMs,
                    mLastTimeConnectedMs
            );
        }
    }

    /**
     * This interface is returned from the
     * {@link AssociationInfo#builder(android.companion.AssociationInfo) builder} entry point
     * to indicate that this builder is not yet in a state that can produce a meaningful
     * {@link AssociationInfo} object that is different from the one originally passed in.
     *
     * <p>
     * Only by calling one of the setter methods is this builder turned into one where calling
     * {@link Builder#build() build()} makes sense.
     *
     * @hide
     */
    public interface NonActionableBuilder {
        /**
         * Should only be used by the CompanionDeviceManagerService.
         * @hide
         */
        @NonNull
        Builder setNotifyOnDeviceNearby(boolean notifyOnDeviceNearby);

        /**
         * Should only be used by the CompanionDeviceManagerService.
         * @hide
         */
        @NonNull
        Builder setLastTimeConnected(long lastTimeConnectedMs);
    }
}
