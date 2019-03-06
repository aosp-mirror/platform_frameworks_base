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
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.TelephonyManager.NetworkTypeBitMask;
import android.telephony.data.ApnSetting.ApnType;
import android.telephony.data.ApnSetting.AuthType;
import android.text.TextUtils;

import com.android.internal.telephony.RILConstants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
    public @interface DataProfileType {}

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

    @DataProfileType
    private final int mType;

    private final int mMaxConnsTime;

    private final int mMaxConns;

    private final int mWaitTime;

    private final boolean mEnabled;

    @ApnType
    private final int mSupportedApnTypesBitmap;

    @ProtocolType
    private final int mRoamingProtocolType;

    @NetworkTypeBitMask
    private final int mBearerBitmap;

    private final int mMtu;

    private final boolean mPersistent;

    private final boolean mPreferred;

    /** @hide */
    public DataProfile(int profileId, String apn, @ProtocolType int protocolType, int authType,
                       String userName, String password, int type, int maxConnsTime, int maxConns,
                       int waitTime, boolean enabled, @ApnType int supportedApnTypesBitmap,
                       @ProtocolType int roamingProtocolType, @NetworkTypeBitMask int bearerBitmap,
                       int mtu, boolean persistent, boolean preferred) {
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
        this.mMaxConnsTime = maxConnsTime;
        this.mMaxConns = maxConns;
        this.mWaitTime = waitTime;
        this.mEnabled = enabled;

        this.mSupportedApnTypesBitmap = supportedApnTypesBitmap;
        this.mRoamingProtocolType = roamingProtocolType;
        this.mBearerBitmap = bearerBitmap;
        this.mMtu = mtu;
        this.mPersistent = persistent;
        this.mPreferred = preferred;
    }

    /** @hide */
    public DataProfile(Parcel source) {
        mProfileId = source.readInt();
        mApn = source.readString();
        mProtocolType = source.readInt();
        mAuthType = source.readInt();
        mUserName = source.readString();
        mPassword = source.readString();
        mType = source.readInt();
        mMaxConnsTime = source.readInt();
        mMaxConns = source.readInt();
        mWaitTime = source.readInt();
        mEnabled = source.readBoolean();
        mSupportedApnTypesBitmap = source.readInt();
        mRoamingProtocolType = source.readInt();
        mBearerBitmap = source.readInt();
        mMtu = source.readInt();
        mPersistent = source.readBoolean();
        mPreferred = source.readBoolean();
    }

    /**
     * @return Id of the data profile.
     */
    public int getProfileId() { return mProfileId; }

    /**
     * @return The APN to establish data connection.
     */
    @NonNull
    public String getApn() { return mApn; }

    /**
     * @return The connection protocol defined in 3GPP TS 27.007 section 10.1.1.
     */
    public @ProtocolType int getProtocol() { return mProtocolType; }

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
    public @DataProfileType int getType() { return mType; }

    /**
     * @return The period in seconds to limit the maximum connections.
     */
    public int getMaxConnsTime() { return mMaxConnsTime; }

    /**
     * @return The maximum connections allowed.
     */
    public int getMaxConns() { return mMaxConns; }

    /**
     * @return The required wait time in seconds after a successful UE initiated disconnect of a
     * given PDN connection before the device can send a new PDN connection request for that given
     * PDN.
     */
    public int getWaitTime() { return mWaitTime; }

    /**
     * @return True if the profile is enabled.
     */
    public boolean isEnabled() { return mEnabled; }

    /**
     * @return The supported APN types bitmap.
     */
    public @ApnType int getSupportedApnTypesBitmap() { return mSupportedApnTypesBitmap; }

    /**
     * @return The connection protocol on roaming network defined in 3GPP TS 27.007 section 10.1.1.
     */
    public @ProtocolType int getRoamingProtocol() { return mRoamingProtocolType; }

    /**
     * @return The bearer bitmap indicating the applicable networks for this data profile.
     */
    public @NetworkTypeBitMask int getBearerBitmap() { return mBearerBitmap; }

    /**
     * @return The maximum transmission unit (MTU) size in bytes.
     */
    public int getMtu() { return mMtu; }

    /**
     * @return {@code true} if modem must persist this data profile.
     */
    public boolean isPersistent() { return mPersistent; }

    /**
     * @return {@code true} if this data profile was used to bring up the last default
     * (i.e internet) data connection successfully.
     */
    public boolean isPreferred() { return  mPreferred; }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "DataProfile=" + mProfileId + "/" + mProtocolType + "/" + mAuthType
                + "/" + (Build.IS_USER ? "***/***/***" :
                         (mApn + "/" + mUserName + "/" + mPassword)) + "/" + mType + "/"
                + mMaxConnsTime + "/" + mMaxConns + "/"
                + mWaitTime + "/" + mEnabled + "/" + mSupportedApnTypesBitmap + "/"
                + mRoamingProtocolType + "/" + mBearerBitmap + "/" + mMtu + "/" + mPersistent + "/"
                + mPreferred;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof DataProfile == false) return false;
        return (o == this || toString().equals(o.toString()));
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mProfileId);
        dest.writeString(mApn);
        dest.writeInt(mProtocolType);
        dest.writeInt(mAuthType);
        dest.writeString(mUserName);
        dest.writeString(mPassword);
        dest.writeInt(mType);
        dest.writeInt(mMaxConnsTime);
        dest.writeInt(mMaxConns);
        dest.writeInt(mWaitTime);
        dest.writeBoolean(mEnabled);
        dest.writeInt(mSupportedApnTypesBitmap);
        dest.writeInt(mRoamingProtocolType);
        dest.writeInt(mBearerBitmap);
        dest.writeInt(mMtu);
        dest.writeBoolean(mPersistent);
        dest.writeBoolean(mPreferred);
    }

    /** @hide */
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
}
