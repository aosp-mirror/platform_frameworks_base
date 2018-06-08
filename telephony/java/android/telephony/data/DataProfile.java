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

import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.telephony.RILConstants;

/**
 * Description of a mobile data profile used for establishing
 * data connections.
 *
 * @hide
 */
public final class DataProfile implements Parcelable {

    // The types indicating the data profile is used on GSM (3GPP) or CDMA (3GPP2) network.
    public static final int TYPE_COMMON = 0;
    public static final int TYPE_3GPP = 1;
    public static final int TYPE_3GPP2 = 2;

    private final int mProfileId;

    private final String mApn;

    private final String mProtocol;

    private final int mAuthType;

    private final String mUserName;

    private final String mPassword;

    private final int mType;

    private final int mMaxConnsTime;

    private final int mMaxConns;

    private final int mWaitTime;

    private final boolean mEnabled;

    private final int mSupportedApnTypesBitmap;

    private final String mRoamingProtocol;

    private final int mBearerBitmap;

    private final int mMtu;

    private final String mMvnoType;

    private final String mMvnoMatchData;

    private final boolean mModemCognitive;

    public DataProfile(int profileId, String apn, String protocol, int authType,
                String userName, String password, int type, int maxConnsTime, int maxConns,
                int waitTime, boolean enabled, int supportedApnTypesBitmap, String roamingProtocol,
                int bearerBitmap, int mtu, String mvnoType, String mvnoMatchData,
                boolean modemCognitive) {

        this.mProfileId = profileId;
        this.mApn = apn;
        this.mProtocol = protocol;
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
        this.mRoamingProtocol = roamingProtocol;
        this.mBearerBitmap = bearerBitmap;
        this.mMtu = mtu;
        this.mMvnoType = mvnoType;
        this.mMvnoMatchData = mvnoMatchData;
        this.mModemCognitive = modemCognitive;
    }

    public DataProfile(Parcel source) {
        mProfileId = source.readInt();
        mApn = source.readString();
        mProtocol = source.readString();
        mAuthType = source.readInt();
        mUserName = source.readString();
        mPassword = source.readString();
        mType = source.readInt();
        mMaxConnsTime = source.readInt();
        mMaxConns = source.readInt();
        mWaitTime = source.readInt();
        mEnabled = source.readBoolean();
        mSupportedApnTypesBitmap = source.readInt();
        mRoamingProtocol = source.readString();
        mBearerBitmap = source.readInt();
        mMtu = source.readInt();
        mMvnoType = source.readString();
        mMvnoMatchData = source.readString();
        mModemCognitive = source.readBoolean();
    }

    /**
     * @return Id of the data profile.
     */
    public int getProfileId() { return mProfileId; }

    /**
     * @return The APN to establish data connection.
     */
    public String getApn() { return mApn; }

    /**
     * @return The connection protocol, should be one of the PDP_type values in TS 27.007 section
     * 10.1.1. For example, "IP", "IPV6", "IPV4V6", or "PPP".
     */
    public String getProtocol() { return mProtocol; }

    /**
     * @return The authentication protocol used for this PDP context
     * (None: 0, PAP: 1, CHAP: 2, PAP&CHAP: 3)
     */
    public int getAuthType() { return mAuthType; }

    /**
     * @return The username for APN. Can be null.
     */
    public String getUserName() { return mUserName; }

    /**
     * @return The password for APN. Can be null.
     */
    public String getPassword() { return mPassword; }

    /**
     * @return The profile type. Could be one of TYPE_COMMON, TYPE_3GPP, or TYPE_3GPP2.
     */
    public int getType() { return mType; }

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
     * @return The supported APN types bitmap. See RIL_ApnTypes for the value of each bit.
     */
    public int getSupportedApnTypesBitmap() { return mSupportedApnTypesBitmap; }

    /**
     * @return  The connection protocol on roaming network, should be one of the PDP_type values in
     * TS 27.007 section 10.1.1. For example, "IP", "IPV6", "IPV4V6", or "PPP".
     */
    public String getRoamingProtocol() { return mRoamingProtocol; }

    /**
     * @return The bearer bitmap. See RIL_RadioAccessFamily for the value of each bit.
     */
    public int getBearerBitmap() { return mBearerBitmap; }

    /**
     * @return The maximum transmission unit (MTU) size in bytes.
     */
    public int getMtu() { return mMtu; }

    /**
     * @return The MVNO type: possible values are "imsi", "gid", "spn".
     */
    public String getMvnoType() { return mMvnoType; }

    /**
     * @return The MVNO match data. For example,
     * SPN: A MOBILE, BEN NL, ...
     * IMSI: 302720x94, 2060188, ...
     * GID: 4E, 33, ...
     */
    public String getMvnoMatchData() { return mMvnoMatchData; }

    /**
     * @return True if the data profile was sent to the modem through setDataProfile earlier.
     */
    public boolean isModemCognitive() { return mModemCognitive; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "DataProfile=" + mProfileId + "/" + mProtocol + "/" + mAuthType
                + "/" + (Build.IS_USER ? "***/***/***" :
                         (mApn + "/" + mUserName + "/" + mPassword))
                + "/" + mType + "/" + mMaxConnsTime
                + "/" + mMaxConns + "/" + mWaitTime + "/" + mEnabled + "/"
                + mSupportedApnTypesBitmap + "/" + mRoamingProtocol + "/" + mBearerBitmap + "/"
                + mMtu + "/" + mMvnoType + "/" + mMvnoMatchData + "/" + mModemCognitive;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof DataProfile == false) return false;
        return (o == this || toString().equals(o.toString()));
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mProfileId);
        dest.writeString(mApn);
        dest.writeString(mProtocol);
        dest.writeInt(mAuthType);
        dest.writeString(mUserName);
        dest.writeString(mPassword);
        dest.writeInt(mType);
        dest.writeInt(mMaxConnsTime);
        dest.writeInt(mMaxConns);
        dest.writeInt(mWaitTime);
        dest.writeBoolean(mEnabled);
        dest.writeInt(mSupportedApnTypesBitmap);
        dest.writeString(mRoamingProtocol);
        dest.writeInt(mBearerBitmap);
        dest.writeInt(mMtu);
        dest.writeString(mMvnoType);
        dest.writeString(mMvnoMatchData);
        dest.writeBoolean(mModemCognitive);
    }

    public static final Parcelable.Creator<DataProfile> CREATOR =
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
