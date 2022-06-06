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

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.NetworkCapabilities;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.NetCapability;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.NetworkTypeBitMask;
import android.telephony.data.ApnSetting.ApnType;
import android.telephony.data.ApnSetting.AuthType;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Description of a mobile data profile used for establishing data networks. The data profile
 * consist an {@link ApnSetting} which is needed for 2G/3G/4G networks bring up, and a
 * {@link TrafficDescriptor} contains additional information that can be used for 5G standalone
 * network bring up.
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

    private boolean mPreferred;

    /**
     * The last timestamp of this data profile being used for data network setup. Never add this
     * to {@link #equals(Object)} and {@link #hashCode()}.
     */
    private @ElapsedRealtimeLong long mSetupTimestamp;

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
        mApnSetting = source.readParcelable(ApnSetting.class.getClassLoader(), android.telephony.data.ApnSetting.class);
        mTrafficDescriptor = source.readParcelable(TrafficDescriptor.class.getClassLoader(), android.telephony.data.TrafficDescriptor.class);
        mPreferred = source.readBoolean();
        mSetupTimestamp = source.readLong();
    }

    /**
     * @return Id of the data profile.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#getProfileId()} instead.
     */
    @Deprecated
    public int getProfileId() {
        if (mApnSetting != null) {
            return mApnSetting.getProfileId();
        }
        return 0;
    }

    /**
     * @return The APN (Access Point Name) to establish data connection. This is a string
     * specifically defined by the carrier.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#getApnName()} instead.
     */
    @Deprecated
    public @NonNull String getApn() {
        if (mApnSetting != null) {
            return TextUtils.emptyIfNull(mApnSetting.getApnName());
        }
        return "";
    }

    /**
     * @return The connection protocol defined in 3GPP TS 27.007 section 10.1.1.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#getProtocol()} instead.
     */
    @Deprecated
    public @ProtocolType int getProtocolType() {
        if (mApnSetting != null) {
            return mApnSetting.getProtocol();
        }
        return ApnSetting.PROTOCOL_IP;
    }

    /**
     * @return The authentication protocol used for this PDP context.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#getAuthType()} instead.
     */
    @Deprecated
    public @AuthType int getAuthType() {
        if (mApnSetting != null) {
            return mApnSetting.getAuthType();
        }
        return ApnSetting.AUTH_TYPE_NONE;
    }

    /**
     * @return The username for APN.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#getUser()} instead.
     */
    @Deprecated
    public @Nullable String getUserName() {
        if (mApnSetting != null) {
            return mApnSetting.getUser();
        }
        return null;
    }

    /**
     * @return The password for APN.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#getPassword()} instead.
     */
    @Deprecated
    public @Nullable String getPassword() {
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
     * @return {@code true} if the profile is enabled. If the profile only has a
     * {@link TrafficDescriptor}, but no {@link ApnSetting}, then this profile is always enabled.
     */
    public boolean isEnabled() {
        if (mApnSetting != null) {
            return mApnSetting.isEnabled();
        }
        return true;
    }

    /**
     * @return The supported APN types bitmask.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#getApnTypeBitmask()} instead.
     */
    @Deprecated public @ApnType int getSupportedApnTypesBitmask() {
        if (mApnSetting != null) {
            return mApnSetting.getApnTypeBitmask();
        }
        return ApnSetting.TYPE_NONE;
    }

    /**
     * @return The connection protocol on roaming network defined in 3GPP TS 27.007 section 10.1.1.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#getRoamingProtocol()} instead.
     */
    @Deprecated
    public @ProtocolType int getRoamingProtocolType() {
        if (mApnSetting != null) {
            return mApnSetting.getRoamingProtocol();
        }
        return ApnSetting.PROTOCOL_IP;
    }

    /**
     * @return The bearer bitmask indicating the applicable networks for this data profile.
     * @deprecated use {@link #getApnSetting()} and {@link ApnSetting#getNetworkTypeBitmask()}
     * instead.
     */
    @Deprecated
    public @NetworkTypeBitMask int getBearerBitmask() {
        if (mApnSetting != null) {
            return mApnSetting.getNetworkTypeBitmask();
        }
        return (int) TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN;
    }

    /**
     * @return The maximum transmission unit (MTU) size in bytes.
     * @deprecated use {@link #getApnSetting()} and {@link ApnSetting#getMtuV4()}/
     * {@link ApnSetting#getMtuV6()} instead.
     */
    @Deprecated
    public int getMtu() {
        return getMtuV4();
    }

    /**
     * This replaces the deprecated method getMtu.
     * @return The maximum transmission unit (MTU) size in bytes, for IPv4.
     * @deprecated use {@link #getApnSetting()} and {@link ApnSetting#getMtuV4()} instead.
     */
    @Deprecated
    public int getMtuV4() {
        if (mApnSetting != null) {
            return mApnSetting.getMtuV4();
        }
        return 0;
    }

    /**
     * @return The maximum transmission unit (MTU) size in bytes, for IPv6.
     * @deprecated use {@link #getApnSetting()} and {@link ApnSetting#getMtuV6()} instead.
     */
    @Deprecated
    public int getMtuV6() {
        if (mApnSetting != null) {
            return mApnSetting.getMtuV6();
        }
        return 0;
    }

    /**
     * @return {@code true} if modem must persist this data profile.
     * @deprecated Use {@link #getApnSetting()} and {@link ApnSetting#isPersistent()} instead.
     */
    @Deprecated
    public boolean isPersistent() {
        if (mApnSetting != null) {
            return mApnSetting.isPersistent();
        }
        return false;
    }

    /**
     * Set the preferred flag for the data profile.
     *
     * @param preferred {@code true} if this data profile is preferred for internet.
     * @hide
     */
    public void setPreferred(boolean preferred) {
        mPreferred = preferred;
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
     * @return The APN setting {@link ApnSetting}, which is used to establish data network on
     * 2G/3G/4G.
     */
    public @Nullable ApnSetting getApnSetting() {
        return mApnSetting;
    }

    /**
     * @return The traffic descriptor {@link TrafficDescriptor}, which can be used to establish
     * data network on 5G.
     */
    public @Nullable TrafficDescriptor getTrafficDescriptor() {
        return mTrafficDescriptor;
    }

    /**
     * Check if this data profile can satisfy certain network capabilities
     *
     * @param networkCapabilities The network capabilities. Note that the non-APN-type capabilities
     * will be ignored.
     *
     * @return {@code true} if this data profile can satisfy the given network capabilities.
     * @hide
     */
    public boolean canSatisfy(@NonNull @NetCapability int[] networkCapabilities) {
        if (mApnSetting != null) {
            for (int netCap : networkCapabilities) {
                if (!canSatisfy(netCap)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Check if this data profile can satisfy a certain network capability.
     *
     * @param networkCapability The network capability. Note that the non-APN-type capability
     * will always be satisfied.
     * @return {@code true} if this data profile can satisfy the given network capability.
     * @hide
     */
    public boolean canSatisfy(@NetCapability int networkCapability) {
        return mApnSetting != null && mApnSetting.canHandleType(
                networkCapabilityToApnType(networkCapability));
    }

    /**
     * Convert network capability into APN type.
     *
     * @param networkCapability Network capability.
     * @return APN type.
     * @hide
     */
    private static @ApnType int networkCapabilityToApnType(@NetCapability int networkCapability) {
        switch (networkCapability) {
            case NetworkCapabilities.NET_CAPABILITY_MMS:
                return ApnSetting.TYPE_MMS;
            case NetworkCapabilities.NET_CAPABILITY_SUPL:
                return ApnSetting.TYPE_SUPL;
            case NetworkCapabilities.NET_CAPABILITY_DUN:
                return ApnSetting.TYPE_DUN;
            case NetworkCapabilities.NET_CAPABILITY_FOTA:
                return ApnSetting.TYPE_FOTA;
            case NetworkCapabilities.NET_CAPABILITY_IMS:
                return ApnSetting.TYPE_IMS;
            case NetworkCapabilities.NET_CAPABILITY_CBS:
                return ApnSetting.TYPE_CBS;
            case NetworkCapabilities.NET_CAPABILITY_XCAP:
                return ApnSetting.TYPE_XCAP;
            case NetworkCapabilities.NET_CAPABILITY_EIMS:
                return ApnSetting.TYPE_EMERGENCY;
            case NetworkCapabilities.NET_CAPABILITY_INTERNET:
                return ApnSetting.TYPE_DEFAULT;
            case NetworkCapabilities.NET_CAPABILITY_MCX:
                return ApnSetting.TYPE_MCX;
            case NetworkCapabilities.NET_CAPABILITY_IA:
                return ApnSetting.TYPE_IA;
            case NetworkCapabilities.NET_CAPABILITY_BIP:
                return ApnSetting.TYPE_BIP;
            case NetworkCapabilities.NET_CAPABILITY_VSIM:
                return ApnSetting.TYPE_VSIM;
            case NetworkCapabilities.NET_CAPABILITY_ENTERPRISE:
                return ApnSetting.TYPE_ENTERPRISE;
            default:
                return ApnSetting.TYPE_NONE;
        }
    }

    /**
     * Set the timestamp of this data profile being used for data network setup.
     *
     * @hide
     */
    public void setLastSetupTimestamp(@ElapsedRealtimeLong long timestamp) {
        mSetupTimestamp = timestamp;
    }

    /**
     * @return the timestamp of this data profile being used for data network setup.
     *
     * @hide
     */
    public @ElapsedRealtimeLong long getLastSetupTimestamp() {
        return mSetupTimestamp;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "[DataProfile=" + mApnSetting + ", " + mTrafficDescriptor + ", preferred="
                + mPreferred + "]";
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeParcelable(mApnSetting, flags);
        dest.writeParcelable(mTrafficDescriptor, flags);
        dest.writeBoolean(mPreferred);
        dest.writeLong(mSetupTimestamp);
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

        private boolean mEnabled = true;

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
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setProfileId(int)} instead.
         */
        @Deprecated
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
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setApnName(String)} instead.
         */
        @Deprecated
        public @NonNull Builder setApn(@NonNull String apn) {
            mApn = apn;
            return this;
        }

        /**
         * Set the connection protocol type.
         *
         * @param protocolType The connection protocol defined in 3GPP TS 27.007 section 10.1.1.
         * @return The same instance of the builder.
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setProtocol(int)} instead.
         */
        @Deprecated
        public @NonNull Builder setProtocolType(@ProtocolType int protocolType) {
            mProtocolType = protocolType;
            return this;
        }

        /**
         * Set the authentication type.
         *
         * @param authType The authentication type
         * @return The same instance of the builder.
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setAuthType(int)} instead.
         */
        @Deprecated
        public @NonNull Builder setAuthType(@AuthType int authType) {
            mAuthType = authType;
            return this;
        }

        /**
         * Set the user name
         *
         * @param userName The user name
         * @return The same instance of the builder.
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setUser(String)} instead.
         */
        @Deprecated
        public @NonNull Builder setUserName(@NonNull String userName) {
            mUserName = userName;
            return this;
        }

        /**
         * Set the password
         *
         * @param password The password
         * @return The same instance of the builder.
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setPassword(String)} (int)} instead.
         */
        @Deprecated
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
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setApnTypeBitmask(int)} instead.
         */
        @Deprecated
        public @NonNull Builder setSupportedApnTypesBitmask(@ApnType int supportedApnTypesBitmask) {
            mSupportedApnTypesBitmask = supportedApnTypesBitmask;
            return this;
        }

        /**
         * Set the connection protocol type for roaming.
         *
         * @param protocolType The connection protocol defined in 3GPP TS 27.007 section 10.1.1.
         * @return The same instance of the builder.
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setRoamingProtocol(int)} instead.
         */
        @Deprecated
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
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setNetworkTypeBitmask(int)} instead.
         */
        @Deprecated
        public @NonNull Builder setBearerBitmask(@NetworkTypeBitMask int bearerBitmask) {
            mBearerBitmask = bearerBitmask;
            return this;
        }

        /**
         * Set the maximum transmission unit (MTU) size in bytes.
         *
         * @param mtu The maximum transmission unit (MTU) size in bytes.
         * @return The same instance of the builder.
         * @deprecated use {@link #setApnSetting(ApnSetting)} and
         * {@link ApnSetting.Builder#setMtuV4(int)}/{@link ApnSetting.Builder#setMtuV6(int)}
         * instead.
         */
        @Deprecated
        public @NonNull Builder setMtu(int mtu) {
            mMtuV4 = mMtuV6 = mtu;
            return this;
        }

        /**
         * Set the maximum transmission unit (MTU) size in bytes, for IPv4.
         *
         * @param mtu The maximum transmission unit (MTU) size in bytes.
         * @return The same instance of the builder.
         * @deprecated Use {{@link #setApnSetting(ApnSetting)}} and
         * {@link ApnSetting.Builder#setMtuV4(int)} instead.
         */
        @Deprecated
        public @NonNull Builder setMtuV4(int mtu) {
            mMtuV4 = mtu;
            return this;
        }

        /**
         * Set the maximum transmission unit (MTU) size in bytes, for IPv6.
         *
         * @param mtu The maximum transmission unit (MTU) size in bytes.
         * @return The same instance of the builder.
         * @deprecated Use {{@link #setApnSetting(ApnSetting)}} and
         * {@link ApnSetting.Builder#setMtuV6(int)} instead.
         */
        @Deprecated
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
         * @deprecated Use {{@link #setApnSetting(ApnSetting)}} and
         * {@link ApnSetting.Builder#setPersistent(boolean)} instead.
         */
        @Deprecated
        public @NonNull Builder setPersistent(boolean isPersistent) {
            mPersistent = isPersistent;
            return this;
        }

        /**
         * Set the APN setting. Note that if an APN setting is not set here, then either
         * {@link #setApn(String)} or {@link #setTrafficDescriptor(TrafficDescriptor)} must be
         * called. Otherwise {@link IllegalArgumentException} will be thrown when {@link #build()}
         * the data profile.
         *
         * @param apnSetting The APN setting.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setApnSetting(@NonNull ApnSetting apnSetting) {
            mApnSetting = apnSetting;
            return this;
        }

        /**
         * Set the traffic descriptor. Note that if a traffic descriptor is not set here, then
         * either {@link #setApnSetting(ApnSetting)} or {@link #setApn(String)} must be called.
         * Otherwise {@link IllegalArgumentException} will be thrown when {@link #build()} the data
         * profile.
         *
         * @param trafficDescriptor The traffic descriptor.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setTrafficDescriptor(@NonNull TrafficDescriptor trafficDescriptor) {
            mTrafficDescriptor = trafficDescriptor;
            return this;
        }

        /**
         * Build the DataProfile object.
         *
         * @return The data profile object.
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
