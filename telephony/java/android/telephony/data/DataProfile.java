/*
 * Copyright 2017 The Android Open Source Project
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

package android.telephony.data;

import static android.telephony.data.ApnSetting.ProtocolType;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.ApnType;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.NetworkTypeBitMask;
import android.telephony.data.ApnSetting.AuthType;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Description of a mobile data profile used for establishing
 * data connections.
 *
 * @hide
 */
@SystemApi
public final class DataProfile implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"TYPE_"},
            value = {
                    TYPE_COMMON,
                    TYPE_3GPP,
                    TYPE_3GPP2})
    public @interface Type {}

    /** Common data profile */
    public static final int TYPE_COMMON = 0;

    /** 3GPP type data profile */
    public static final int TYPE_3GPP = 1;

    /** 3GPP2 type data profile */
    public static final int TYPE_3GPP2 = 2;

    private final @Type int mType;

    private final @Nullable ApnSetting mApnSetting;

    private final @Nullable TrafficDescriptor mTrafficDescriptor;

    private final boolean mPreferred;

    private DataProfile(@NonNull Builder builder) {
        mApnSetting = builder.mApnSetting;
        mTrafficDescriptor = builder.mTrafficDescriptor;
        mPreferred = builder.mPreferred;

        if (builder.mType != -1) {
            mType = builder.mType;
        } else if (mApnSetting != null) {
            int networkTypes = mApnSetting.getNetworkTypeBitmask();

            if (networkTypes == 0) {
                mType = DataProfile.TYPE_COMMON;
            } else if ((networkTypes & TelephonyManager.NETWORK_STANDARDS_FAMILY_BITMASK_3GPP2)
                    == networkTypes) {
                mType = DataProfile.TYPE_3GPP2;
            } else if ((networkTypes & TelephonyManager.NETWORK_STANDARDS_FAMILY_BITMASK_3GPP)
                    == networkTypes) {
                mType = DataProfile.TYPE_3GPP;
            } else {
                mType = DataProfile.TYPE_COMMON;
            }
        } else {
            mType = DataProfile.TYPE_COMMON;
        }
    }

    private DataProfile(Parcel source) {
        mType = source.readInt();
        mApnSetting = source.readParcelable(ApnSetting.class.getClassLoader());
        mTrafficDescriptor = source.readParcelable(TrafficDescriptor.class.getClassLoader());
        mPreferred = source.readBoolean();
    }

    /**
     * @return Id of the data profile.
     */
    public int getProfileId() {
        if (mApnSetting != null) {
            return mApnSetting.getProfileId();
        }
        return 0;
    }

    /**
     * @return The APN (Access Point Name) to establish data connection. This is a string
     * specifically defined by the carrier.
     */
    @NonNull
    public String getApn() {
        if (mApnSetting != null) {
            return TextUtils.emptyIfNull(mApnSetting.getApnName());
        }
        return "";
    }

    /**
     * @return The connection protocol defined in 3GPP TS 27.007 section 10.1.1.
     */
    public @ProtocolType int getProtocolType() {
        if (mApnSetting != null) {
            return mApnSetting.getProtocol();
        }
        return ApnSetting.PROTOCOL_IP;
    }

    /**
     * @return The authentication protocol used for this PDP context.
     */
    public @AuthType int getAuthType() {
        if (mApnSetting != null) {
            return mApnSetting.getAuthType();
        }
        return ApnSetting.AUTH_TYPE_NONE;
    }

    /**
     * @return The username for APN. Can be null.
     */
    @Nullable
    public String getUserName() {
        if (mApnSetting != null) {
            return mApnSetting.getUser();
        }
        return null;
    }

    /**
     * @return The password for APN. Can be null.
     */
    @Nullable
    public String getPassword() {
        if (mApnSetting != null) {
            return mApnSetting.getPassword();
        }
        return null;
    }

    /**
     * @return The profile type.
     */
    public @Type int getType() {
        return mType;
    }

    /**
     * @return The period in seconds to limit the maximum connections.
     *
     * @hide
     */
    public int getMaxConnectionsTime() {
        if (mApnSetting != null) {
            return mApnSetting.getMaxConnsTime();
        }
        return 0;
    }

    /**
     * @return The maximum connections allowed.
     *
     * @hide
     */
    public int getMaxConnections() {
        if (mApnSetting != null) {
            return mApnSetting.getMaxConns();
        }
        return 0;
    }

    /**
     * @return The required wait time in seconds after a successful UE initiated disconnect of a
     * given PDN connection before the device can send a new PDN connection request for that given
     * PDN.
     *
     * @hide
     */
    public int getWaitTime() {
        if (mApnSetting != null) {
            return mApnSetting.getWaitTime();
        }
        return 0;
    }

    /**
     * @return True if the profile is enabled.
     */
    public boolean isEnabled() {
        if (mApnSetting != null) {
            return mApnSetting.isEnabled();
        }
        return false;
    }

    /**
     * @return The supported APN types bitmask.
     */
    public @ApnType int getSupportedApnTypesBitmask() {
        if (mApnSetting != null) {
            return mApnSetting.getApnTypeBitmask();
        }
        return ApnSetting.TYPE_NONE;
    }

    /**
     * @return The connection protocol on roaming network defined in 3GPP TS 27.007 section 10.1.1.
     */
    public @ProtocolType int getRoamingProtocolType() {
        if (mApnSetting != null) {
            return mApnSetting.getRoamingProtocol();
        }
        return ApnSetting.PROTOCOL_IP;
    }

    /**
     * @return The bearer bitmask indicating the applicable networks for this data profile.
     */
    public @NetworkTypeBitMask int getBearerBitmask() {
        if (mApnSetting != null) {
            return mApnSetting.getNetworkTypeBitmask();
        }
        return (int) TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN;
    }

    /**
     * @return The maximum transmission unit (MTU) size in bytes.
     * @deprecated use {@link #getMtuV4} or {@link #getMtuV6} instead.
     */
    @Deprecated
    public int getMtu() {
        return getMtuV4();
    }

    /**
     * This replaces the deprecated method getMtu.
     * @return The maximum transmission unit (MTU) size in bytes, for IPv4.
     */
    public int getMtuV4() {
        if (mApnSetting != null) {
            return mApnSetting.getMtuV4();
        }
        return 0;
    }

    /**
     * @return The maximum transmission unit (MTU) size in bytes, for IPv6.
     */
    public int getMtuV6() {
        if (mApnSetting != null) {
            return mApnSetting.getMtuV6();
        }
        return 0;
    }

    /**
     * @return {@code true} if modem must persist this data profile.
     */
    public boolean isPersistent() {
        if (mApnSetting != null) {
            return mApnSetting.isPersistent();
        }
        return false;
    }

    /**
     * @return {@code true} if this data profile was used to bring up the last default
     * (i.e internet) data connection successfully, or the one chosen by the user in Settings'
     * APN editor. For one carrier there can be only one profiled preferred.
     */
    public boolean isPreferred() {
        return mPreferred;
    }

    /**
     * @return The APN setting
     * @hide TODO: Remove before T is released.
     */
    public @Nullable ApnSetting getApnSetting() {
        return mApnSetting;
    }

    /**
     * @return The traffic descriptor
     * @hide TODO: Remove before T is released.
     */
    public @Nullable TrafficDescriptor getTrafficDescriptor() {
        return mTrafficDescriptor;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "DataProfile=" + mApnSetting + ", " + mTrafficDescriptor + ", preferred="
                + mPreferred;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeParcelable(mApnSetting, flags);
        dest.writeParcelable(mTrafficDescriptor, flags);
        dest.writeBoolean(mPreferred);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<DataProfile> CREATOR =
            new Parcelable.Creator<DataProfile>() {
        @Override
        public DataProfile createFromParcel(Parcel source) {
            return new DataProfile(source);
        }

        @Override
        public DataProfile[] newArray(int size) {
            return new DataProfile[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataProfile that = (DataProfile) o;
        return mType == that.mType
                && Objects.equals(mApnSetting, that.mApnSetting)
                && Objects.equals(mTrafficDescriptor, that.mTrafficDescriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mApnSetting, mTrafficDescriptor);
    }

    /**
     * Provides a convenient way to set the fields of a {@link DataProfile} when creating a new
     * instance.
     *
     * <p>The example below shows how you might create a new {@code DataProfile}:
     *
     * <pre><code>
     *
     * DataProfile dp = new DataProfile.Builder()
     *     .setApn("apn.xyz.com")
     *     .setProtocol(ApnSetting.PROTOCOL_IPV4V6)
     *     .build();
     * </code></pre>
     */
    public static final class Builder {
        private int mProfileId;

        private String mApn;

        @ProtocolType
        private int mProtocolType;

        @AuthType
        private int mAuthType;

        private String mUserName;

        private String mPassword;

        @Type
        private int mType = -1;

        private boolean mEnabled;

        @ApnType
        private int mSupportedApnTypesBitmask;

        @ProtocolType
        private int mRoamingProtocolType;

        @NetworkTypeBitMask
        private int mBearerBitmask;

        private int mMtuV4;

        private int mMtuV6;

        private boolean mPersistent;

        private boolean mPreferred;

        private ApnSetting mApnSetting;

        private TrafficDescriptor mTrafficDescriptor;

        /**
         * Default constructor for Builder.
         */
        public Builder() {
        }

        /**
         * Set profile id. Note that this is not a global unique id of the data profile. This id
         * is only used by certain CDMA carriers to identify the type of data profile.
         *
         * @param profileId Network domain.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setProfileId(int profileId) {
            mProfileId = profileId;
            return this;
        }

        /**
         * Set the APN (Access Point Name) to establish data connection. This is a string
         * specifically defined by the carrier.
         *
         * @param apn Access point name
         * @return The same instance of the builder.
         */
        public @NonNull Builder setApn(@NonNull String apn) {
            mApn = apn;
            return this;
        }

        /**
         * Set the connection protocol type.
         *
         * @param protocolType The connection protocol defined in 3GPP TS 27.007 section 10.1.1.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setProtocolType(@ProtocolType int protocolType) {
            mProtocolType = protocolType;
            return this;
        }

        /**
         * Set the authentication type.
         *
         * @param authType The authentication type
         * @return The same instance of the builder.
         */
        public @NonNull Builder setAuthType(@AuthType int authType) {
            mAuthType = authType;
            return this;
        }

        /**
         * Set the user name
         *
         * @param userName The user name
         * @return The same instance of the builder.
         */
        public @NonNull Builder setUserName(@NonNull String userName) {
            mUserName = userName;
            return this;
        }

        /**
         * Set the password
         *
         * @param password The password
         * @return The same instance of the builder.
         */
        public @NonNull Builder setPassword(@NonNull String password) {
            mPassword = password;
            return this;
        }

        /**
         * Set the type
         *
         * @param type The profile type
         * @return The same instance of the builder.
         */
        public @NonNull Builder setType(@Type int type) {
            mType = type;
            return this;
        }

        /**
         * Enable the data profile
         *
         * @param isEnabled {@code true} to enable the data profile, otherwise disable.
         * @return The same instance of the builder.
         */
        public @NonNull Builder enable(boolean isEnabled) {
            mEnabled = isEnabled;
            return this;
        }

        /**
         * Set the supported APN types bitmask.
         *
         * @param supportedApnTypesBitmask The supported APN types bitmask.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setSupportedApnTypesBitmask(@ApnType int supportedApnTypesBitmask) {
            mSupportedApnTypesBitmask = supportedApnTypesBitmask;
            return this;
        }

        /**
         * Set the connection protocol type for roaming.
         *
         * @param protocolType The connection protocol defined in 3GPP TS 27.007 section 10.1.1.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setRoamingProtocolType(@ProtocolType int protocolType) {
            mRoamingProtocolType = protocolType;
            return this;
        }

        /**
         * Set the bearer bitmask indicating the applicable networks for this data profile.
         *
         * @param bearerBitmask The bearer bitmask indicating the applicable networks for this data
         * profile.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setBearerBitmask(@NetworkTypeBitMask int bearerBitmask) {
            mBearerBitmask = bearerBitmask;
            return this;
        }

        /**
         * Set the maximum transmission unit (MTU) size in bytes.
         *
         * @param mtu The maximum transmission unit (MTU) size in bytes.
         * @return The same instance of the builder.
         * @deprecated use {@link #setApnSetting(ApnSetting)} instead.
         */
        @Deprecated
        public @NonNull Builder setMtu(int mtu) {
            mMtuV4 = mMtuV6 = mtu;
            return this;
        }

        /**
         * Set the maximum transmission unit (MTU) size in bytes, for IPv4.
         * This replaces the deprecated method setMtu.
         *
         * @param mtu The maximum transmission unit (MTU) size in bytes.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setMtuV4(int mtu) {
            mMtuV4 = mtu;
            return this;
        }

        /**
         * Set the maximum transmission unit (MTU) size in bytes, for IPv6.
         *
         * @param mtu The maximum transmission unit (MTU) size in bytes.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setMtuV6(int mtu) {
            mMtuV6 = mtu;
            return this;
        }

        /**
         * Set data profile as preferred/non-preferred.
         *
         * @param isPreferred {@code true} if this data profile was used to bring up the last
         * default (i.e internet) data connection successfully, or the one chosen by the user in
         * Settings' APN editor. For one carrier there can be only one profiled preferred.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setPreferred(boolean isPreferred) {
            mPreferred = isPreferred;
            return this;
        }

        /**
         * Set data profile as persistent/non-persistent.
         *
         * @param isPersistent {@code true} if this data profile was used to bring up the last
         * default (i.e internet) data connection successfully.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setPersistent(boolean isPersistent) {
            mPersistent = isPersistent;
            return this;
        }

        /**
         * Set APN setting.
         *
         * @param apnSetting APN setting
         * @return The same instance of the builder
         *
         * @hide // TODO: Remove before T is released.
         */
        public @NonNull Builder setApnSetting(@NonNull ApnSetting apnSetting) {
            mApnSetting = apnSetting;
            return this;
        }

        /**
         * Set traffic descriptor.
         *
         * @param trafficDescriptor Traffic descriptor
         * @return The same instance of the builder
         *
         * @hide // TODO: Remove before T is released.
         */
        public @NonNull Builder setTrafficDescriptor(@NonNull TrafficDescriptor trafficDescriptor) {
            mTrafficDescriptor = trafficDescriptor;
            return this;
        }

        /**
         * Build the DataProfile object
         *
         * @return The data profile object
         */
        public @NonNull DataProfile build() {
            if (mApnSetting == null && mApn != null) {
                // This is for backwards compatibility.
                mApnSetting = new ApnSetting.Builder()
                        .setEntryName(mApn)
                        .setApnName(mApn)
                        .setApnTypeBitmask(mSupportedApnTypesBitmask)
                        .setAuthType(mAuthType)
                        .setCarrierEnabled(mEnabled)
                        .setModemCognitive(mPersistent)
                        .setMtuV4(mMtuV4)
                        .setMtuV6(mMtuV6)
                        .setNetworkTypeBitmask(mBearerBitmask)
                        .setProfileId(mProfileId)
                        .setPassword(mPassword)
                        .setProtocol(mProtocolType)
                        .setRoamingProtocol(mRoamingProtocolType)
                        .setUser(mUserName)
                        .build();
            }

            if (mApnSetting == null && mTrafficDescriptor == null) {
                throw new IllegalArgumentException("APN setting and traffic descriptor can't be "
                        + "both null.");
            }

            return new DataProfile(this);
        }
    }
}
