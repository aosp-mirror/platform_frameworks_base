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
import android.telephony.TelephonyManager.NetworkTypeBitMask;
import android.telephony.data.ApnSetting.AuthType;
import android.text.TextUtils;

import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.util.TelephonyUtils;

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

    private final int mProfileId;

    private final String mApn;

    @ProtocolType
    private final int mProtocolType;

    @AuthType
    private final int mAuthType;

    private final String mUserName;

    private final String mPassword;

    @Type
    private final int mType;

    private final int mMaxConnectionsTime;

    private final int mMaxConnections;

    private final int mWaitTime;

    private final boolean mEnabled;

    @ApnType
    private final int mSupportedApnTypesBitmask;

    @ProtocolType
    private final int mRoamingProtocolType;

    @NetworkTypeBitMask
    private final int mBearerBitmask;

    private final int mMtuV4;

    private final int mMtuV6;

    private final boolean mPersistent;

    private final boolean mPreferred;

    /** @hide */
    private DataProfile(int profileId, String apn, @ProtocolType int protocolType, int authType,
            String userName, String password, int type, int maxConnectionsTime,
            int maxConnections, int waitTime, boolean enabled,
            @ApnType int supportedApnTypesBitmask, @ProtocolType int roamingProtocolType,
            @NetworkTypeBitMask int bearerBitmask, int mtuV4, int mtuV6, boolean persistent,
            boolean preferred) {
        this.mProfileId = profileId;
        this.mApn = apn;
        this.mProtocolType = protocolType;
        if (authType == -1) {
            authType = TextUtils.isEmpty(userName) ? RILConstants.SETUP_DATA_AUTH_NONE
                    : RILConstants.SETUP_DATA_AUTH_PAP_CHAP;
        }
        this.mAuthType = authType;
        this.mUserName = userName;
        this.mPassword = password;
        this.mType = type;
        this.mMaxConnectionsTime = maxConnectionsTime;
        this.mMaxConnections = maxConnections;
        this.mWaitTime = waitTime;
        this.mEnabled = enabled;
        this.mSupportedApnTypesBitmask = supportedApnTypesBitmask;
        this.mRoamingProtocolType = roamingProtocolType;
        this.mBearerBitmask = bearerBitmask;
        this.mMtuV4 = mtuV4;
        this.mMtuV6 = mtuV6;
        this.mPersistent = persistent;
        this.mPreferred = preferred;
    }

    private DataProfile(Parcel source) {
        mProfileId = source.readInt();
        mApn = source.readString();
        mProtocolType = source.readInt();
        mAuthType = source.readInt();
        mUserName = source.readString();
        mPassword = source.readString();
        mType = source.readInt();
        mMaxConnectionsTime = source.readInt();
        mMaxConnections = source.readInt();
        mWaitTime = source.readInt();
        mEnabled = source.readBoolean();
        mSupportedApnTypesBitmask = source.readInt();
        mRoamingProtocolType = source.readInt();
        mBearerBitmask = source.readInt();
        mMtuV4 = source.readInt();
        mMtuV6 = source.readInt();
        mPersistent = source.readBoolean();
        mPreferred = source.readBoolean();
    }

    /**
     * @return Id of the data profile.
     */
    public int getProfileId() { return mProfileId; }

    /**
     * @return The APN (Access Point Name) to establish data connection. This is a string
     * specifically defined by the carrier.
     */
    @NonNull
    public String getApn() { return mApn; }

    /**
     * @return The connection protocol defined in 3GPP TS 27.007 section 10.1.1.
     */
    public @ProtocolType int getProtocolType() { return mProtocolType; }

    /**
     * @return The authentication protocol used for this PDP context.
     */
    public @AuthType int getAuthType() { return mAuthType; }

    /**
     * @return The username for APN. Can be null.
     */
    @Nullable
    public String getUserName() { return mUserName; }

    /**
     * @return The password for APN. Can be null.
     */
    @Nullable
    public String getPassword() { return mPassword; }

    /**
     * @return The profile type.
     */
    public @Type int getType() { return mType; }

    /**
     * @return The period in seconds to limit the maximum connections.
     *
     * @hide
     */
    public int getMaxConnectionsTime() { return mMaxConnectionsTime; }

    /**
     * @return The maximum connections allowed.
     *
     * @hide
     */
    public int getMaxConnections() { return mMaxConnections; }

    /**
     * @return The required wait time in seconds after a successful UE initiated disconnect of a
     * given PDN connection before the device can send a new PDN connection request for that given
     * PDN.
     *
     * @hide
     */
    public int getWaitTime() { return mWaitTime; }

    /**
     * @return True if the profile is enabled.
     */
    public boolean isEnabled() { return mEnabled; }

    /**
     * @return The supported APN types bitmask.
     */
    public @ApnType int getSupportedApnTypesBitmask() { return mSupportedApnTypesBitmask; }

    /**
     * @return The connection protocol on roaming network defined in 3GPP TS 27.007 section 10.1.1.
     */
    public @ProtocolType int getRoamingProtocolType() { return mRoamingProtocolType; }

    /**
     * @return The bearer bitmask indicating the applicable networks for this data profile.
     */
    public @NetworkTypeBitMask int getBearerBitmask() { return mBearerBitmask; }

    /**
     * @return The maximum transmission unit (MTU) size in bytes.
     * @deprecated use {@link #getMtuV4} or {@link #getMtuV6} instead.
     */
    @Deprecated
    public int getMtu() { return mMtuV4; }

    /**
     * This replaces the deprecated method getMtu.
     * @return The maximum transmission unit (MTU) size in bytes, for IPv4.
     */
    public int getMtuV4() { return mMtuV4; }

    /**
     * @return The maximum transmission unit (MTU) size in bytes, for IPv6.
     */
    public int getMtuV6() { return mMtuV6; }

    /**
     * @return {@code true} if modem must persist this data profile.
     */
    public boolean isPersistent() { return mPersistent; }

    /**
     * @return {@code true} if this data profile was used to bring up the last default
     * (i.e internet) data connection successfully, or the one chosen by the user in Settings'
     * APN editor. For one carrier there can be only one profiled preferred.
     */
    public boolean isPreferred() { return  mPreferred; }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "DataProfile=" + mProfileId + "/" + mProtocolType + "/" + mAuthType
                + "/" + (TelephonyUtils.IS_USER ? "***/***/***" :
                         (mApn + "/" + mUserName + "/" + mPassword)) + "/" + mType + "/"
                + mMaxConnectionsTime + "/" + mMaxConnections + "/"
                + mWaitTime + "/" + mEnabled + "/" + mSupportedApnTypesBitmask + "/"
                + mRoamingProtocolType + "/" + mBearerBitmask + "/" + mMtuV4 + "/" + mMtuV6 + "/"
                + mPersistent + "/" + mPreferred;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mProfileId);
        dest.writeString(mApn);
        dest.writeInt(mProtocolType);
        dest.writeInt(mAuthType);
        dest.writeString(mUserName);
        dest.writeString(mPassword);
        dest.writeInt(mType);
        dest.writeInt(mMaxConnectionsTime);
        dest.writeInt(mMaxConnections);
        dest.writeInt(mWaitTime);
        dest.writeBoolean(mEnabled);
        dest.writeInt(mSupportedApnTypesBitmask);
        dest.writeInt(mRoamingProtocolType);
        dest.writeInt(mBearerBitmask);
        dest.writeInt(mMtuV4);
        dest.writeInt(mMtuV6);
        dest.writeBoolean(mPersistent);
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
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataProfile that = (DataProfile) o;
        return mProfileId == that.mProfileId
                && mProtocolType == that.mProtocolType
                && mAuthType == that.mAuthType
                && mType == that.mType
                && mMaxConnectionsTime == that.mMaxConnectionsTime
                && mMaxConnections == that.mMaxConnections
                && mWaitTime == that.mWaitTime
                && mEnabled == that.mEnabled
                && mSupportedApnTypesBitmask == that.mSupportedApnTypesBitmask
                && mRoamingProtocolType == that.mRoamingProtocolType
                && mBearerBitmask == that.mBearerBitmask
                && mMtuV4 == that.mMtuV4
                && mMtuV6 == that.mMtuV6
                && mPersistent == that.mPersistent
                && mPreferred == that.mPreferred
                && Objects.equals(mApn, that.mApn)
                && Objects.equals(mUserName, that.mUserName)
                && Objects.equals(mPassword, that.mPassword);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProfileId, mApn, mProtocolType, mAuthType, mUserName, mPassword, mType,
                mMaxConnectionsTime, mMaxConnections, mWaitTime, mEnabled,
                mSupportedApnTypesBitmask, mRoamingProtocolType, mBearerBitmask, mMtuV4, mMtuV6,
                mPersistent, mPreferred);
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
        private int mType;

        private int mMaxConnectionsTime;

        private int mMaxConnections;

        private int mWaitTime;

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
         * Set the period in seconds to limit the maximum connections.
         *
         * @param maxConnectionsTime The profile type
         * @return The same instance of the builder.
         *
         * @hide
         */
        public @NonNull Builder setMaxConnectionsTime(int maxConnectionsTime) {
            mMaxConnectionsTime = maxConnectionsTime;
            return this;
        }

        /**
         * Set the maximum connections allowed.
         *
         * @param maxConnections The maximum connections allowed.
         * @return The same instance of the builder.
         *
         * @hide
         */
        public @NonNull Builder setMaxConnections(int maxConnections) {
            mMaxConnections = maxConnections;
            return this;
        }

        /**
         * Set the period in seconds to limit the maximum connections.
         *
         * @param waitTime The required wait time in seconds after a successful UE initiated
         * disconnect of a given PDN connection before the device can send a new PDN connection
         * request for that given PDN.
         *
         * @return The same instance of the builder.
         *
         * @hide
         */
        public @NonNull Builder setWaitTime(int waitTime) {
            mWaitTime = waitTime;
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
         * @deprecated use {@link #setMtuV4} or {@link #setMtuV6} instead.
         */
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
         * Set data profile as persistent/non-persistent
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
         * Build the DataProfile object
         *
         * @return The data profile object
         */
        public @NonNull DataProfile build() {
            return new DataProfile(mProfileId, mApn, mProtocolType, mAuthType, mUserName, mPassword,
                    mType, mMaxConnectionsTime, mMaxConnections, mWaitTime, mEnabled,
                    mSupportedApnTypesBitmask, mRoamingProtocolType, mBearerBitmask, mMtuV4, mMtuV6,
                    mPersistent, mPreferred);
        }
    }
}
