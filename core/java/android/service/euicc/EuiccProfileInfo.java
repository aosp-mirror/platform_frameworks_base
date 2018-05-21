/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.service.euicc;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.carrier.CarrierIdentifier;
import android.telephony.UiccAccessRule;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Information about an embedded profile (subscription) on an eUICC.
 *
 * @hide
 */
@SystemApi
public final class EuiccProfileInfo implements Parcelable {

    /** Profile policy rules (bit mask) */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "POLICY_RULE_" }, value = {
            POLICY_RULE_DO_NOT_DISABLE,
            POLICY_RULE_DO_NOT_DELETE,
            POLICY_RULE_DELETE_AFTER_DISABLING
    })
    /** @hide */
    public @interface PolicyRule {}
    /** Once this profile is enabled, it cannot be disabled. */
    public static final int POLICY_RULE_DO_NOT_DISABLE = 1;
    /** This profile cannot be deleted. */
    public static final int POLICY_RULE_DO_NOT_DELETE = 1 << 1;
    /** This profile should be deleted after being disabled. */
    public static final int POLICY_RULE_DELETE_AFTER_DISABLING = 1 << 2;

    /** Class of the profile */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "PROFILE_CLASS_" }, value = {
            PROFILE_CLASS_TESTING,
            PROFILE_CLASS_PROVISIONING,
            PROFILE_CLASS_OPERATIONAL,
            PROFILE_CLASS_UNSET
    })
    /** @hide */
    public @interface ProfileClass {}
    /** Testing profiles */
    public static final int PROFILE_CLASS_TESTING = 0;
    /** Provisioning profiles which are pre-loaded on eUICC */
    public static final int PROFILE_CLASS_PROVISIONING = 1;
    /** Operational profiles which can be pre-loaded or downloaded */
    public static final int PROFILE_CLASS_OPERATIONAL = 2;
    /**
     * Profile class not set.
     * @hide
     */
    public static final int PROFILE_CLASS_UNSET = -1;

    /** State of the profile */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "PROFILE_STATE_" }, value = {
            PROFILE_STATE_DISABLED,
            PROFILE_STATE_ENABLED,
            PROFILE_STATE_UNSET
    })
    /** @hide */
    public @interface ProfileState {}
    /** Disabled profiles */
    public static final int PROFILE_STATE_DISABLED = 0;
    /** Enabled profile */
    public static final int PROFILE_STATE_ENABLED = 1;
    /**
     * Profile state not set.
     * @hide
     */
    public static final int PROFILE_STATE_UNSET = -1;

    /** The iccid of the subscription. */
    private final String mIccid;

    /** An optional nickname for the subscription. */
    private final @Nullable String mNickname;

    /** The service provider name for the subscription. */
    private final String mServiceProviderName;

    /** The profile name for the subscription. */
    private final String mProfileName;

    /** Profile class for the subscription. */
    @ProfileClass private final int mProfileClass;

    /** The profile state of the subscription. */
    @ProfileState private final int mState;

    /** The operator Id of the subscription. */
    private final CarrierIdentifier mCarrierIdentifier;

    /** The policy rules of the subscription. */
    @PolicyRule private final int mPolicyRules;

    /**
     * Optional access rules defining which apps can manage this subscription. If unset, only the
     * platform can manage it.
     */
    private final @Nullable UiccAccessRule[] mAccessRules;

    public static final Creator<EuiccProfileInfo> CREATOR = new Creator<EuiccProfileInfo>() {
        @Override
        public EuiccProfileInfo createFromParcel(Parcel in) {
            return new EuiccProfileInfo(in);
        }

        @Override
        public EuiccProfileInfo[] newArray(int size) {
            return new EuiccProfileInfo[size];
        }
    };

    // TODO(b/70292228): Remove this method when LPA can be updated.
    /**
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public EuiccProfileInfo(String iccid, @Nullable UiccAccessRule[] accessRules,
            @Nullable String nickname) {
        if (!TextUtils.isDigitsOnly(iccid)) {
            throw new IllegalArgumentException("iccid contains invalid characters: " + iccid);
        }
        this.mIccid = iccid;
        this.mAccessRules = accessRules;
        this.mNickname = nickname;

        this.mServiceProviderName = null;
        this.mProfileName = null;
        this.mProfileClass = PROFILE_CLASS_UNSET;
        this.mState = PROFILE_STATE_UNSET;
        this.mCarrierIdentifier = null;
        this.mPolicyRules = 0;
    }

    private EuiccProfileInfo(Parcel in) {
        mIccid = in.readString();
        mNickname = in.readString();
        mServiceProviderName = in.readString();
        mProfileName = in.readString();
        mProfileClass = in.readInt();
        mState = in.readInt();
        byte exist = in.readByte();
        if (exist == (byte) 1) {
            mCarrierIdentifier = CarrierIdentifier.CREATOR.createFromParcel(in);
        } else {
            mCarrierIdentifier = null;
        }
        mPolicyRules = in.readInt();
        mAccessRules = in.createTypedArray(UiccAccessRule.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mIccid);
        dest.writeString(mNickname);
        dest.writeString(mServiceProviderName);
        dest.writeString(mProfileName);
        dest.writeInt(mProfileClass);
        dest.writeInt(mState);
        if (mCarrierIdentifier != null) {
            dest.writeByte((byte) 1);
            mCarrierIdentifier.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeInt(mPolicyRules);
        dest.writeTypedArray(mAccessRules, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** The builder to build a new {@link EuiccProfileInfo} instance. */
    public static final class Builder {
        private String mIccid;
        private List<UiccAccessRule> mAccessRules;
        private String mNickname;
        private String mServiceProviderName;
        private String mProfileName;
        @ProfileClass private int mProfileClass;
        @ProfileState private int mState;
        private CarrierIdentifier mCarrierIdentifier;
        @PolicyRule private int mPolicyRules;

        public Builder(String value) {
            if (!TextUtils.isDigitsOnly(value)) {
                throw new IllegalArgumentException("iccid contains invalid characters: " + value);
            }
            mIccid = value;
        }

        public Builder(EuiccProfileInfo baseProfile) {
            mIccid = baseProfile.mIccid;
            mNickname = baseProfile.mNickname;
            mServiceProviderName = baseProfile.mServiceProviderName;
            mProfileName = baseProfile.mProfileName;
            mProfileClass = baseProfile.mProfileClass;
            mState = baseProfile.mState;
            mCarrierIdentifier = baseProfile.mCarrierIdentifier;
            mPolicyRules = baseProfile.mPolicyRules;
            mAccessRules = Arrays.asList(baseProfile.mAccessRules);
        }

        /** Builds the profile instance. */
        public EuiccProfileInfo build() {
            if (mIccid == null) {
                throw new IllegalStateException("ICCID must be set for a profile.");
            }
            return new EuiccProfileInfo(
                    mIccid,
                    mNickname,
                    mServiceProviderName,
                    mProfileName,
                    mProfileClass,
                    mState,
                    mCarrierIdentifier,
                    mPolicyRules,
                    mAccessRules);
        }

        /** Sets the iccId of the subscription. */
        public Builder setIccid(String value) {
            if (!TextUtils.isDigitsOnly(value)) {
                throw new IllegalArgumentException("iccid contains invalid characters: " + value);
            }
            mIccid = value;
            return this;
        }

        /** Sets the nickname of the subscription. */
        public Builder setNickname(String value) {
            mNickname = value;
            return this;
        }

        /** Sets the service provider name of the subscription. */
        public Builder setServiceProviderName(String value) {
            mServiceProviderName = value;
            return this;
        }

        /** Sets the profile name of the subscription. */
        public Builder setProfileName(String value) {
            mProfileName = value;
            return this;
        }

        /** Sets the profile class of the subscription. */
        public Builder setProfileClass(@ProfileClass int value) {
            mProfileClass = value;
            return this;
        }

        /** Sets the state of the subscription. */
        public Builder setState(@ProfileState int value) {
            mState = value;
            return this;
        }

        /** Sets the carrier identifier of the subscription. */
        public Builder setCarrierIdentifier(CarrierIdentifier value) {
            mCarrierIdentifier = value;
            return this;
        }

        /** Sets the policy rules of the subscription. */
        public Builder setPolicyRules(@PolicyRule int value) {
            mPolicyRules = value;
            return this;
        }

        /** Sets the access rules of the subscription. */
        public Builder setUiccAccessRule(@Nullable List<UiccAccessRule> value) {
            mAccessRules = value;
            return this;
        }
    }

    private EuiccProfileInfo(
            String iccid,
            @Nullable String nickname,
            String serviceProviderName,
            String profileName,
            @ProfileClass int profileClass,
            @ProfileState int state,
            CarrierIdentifier carrierIdentifier,
            @PolicyRule int policyRules,
            @Nullable List<UiccAccessRule> accessRules) {
        this.mIccid = iccid;
        this.mNickname = nickname;
        this.mServiceProviderName = serviceProviderName;
        this.mProfileName = profileName;
        this.mProfileClass = profileClass;
        this.mState = state;
        this.mCarrierIdentifier = carrierIdentifier;
        this.mPolicyRules = policyRules;
        if (accessRules != null && accessRules.size() > 0) {
            this.mAccessRules = accessRules.toArray(new UiccAccessRule[accessRules.size()]);
        } else {
            this.mAccessRules = null;
        }
    }

    /** Gets the ICCID string. */
    public String getIccid() {
        return mIccid;
    }

    /** Gets the access rules. */
    @Nullable
    public List<UiccAccessRule> getUiccAccessRules() {
        if (mAccessRules == null) return null;
        return Arrays.asList(mAccessRules);
    }

    /** Gets the nickname. */
    @Nullable
    public String getNickname() {
        return mNickname;
    }

    /** Gets the service provider name. */
    public String getServiceProviderName() {
        return mServiceProviderName;
    }

    /** Gets the profile name. */
    public String getProfileName() {
        return mProfileName;
    }

    /** Gets the profile class. */
    @ProfileClass
    public int getProfileClass() {
        return mProfileClass;
    }

    /** Gets the state of the subscription. */
    @ProfileState
    public int getState() {
        return mState;
    }

    /** Gets the carrier identifier. */
    public CarrierIdentifier getCarrierIdentifier() {
        return mCarrierIdentifier;
    }

    /** Gets the policy rules. */
    @PolicyRule
    public int getPolicyRules() {
        return mPolicyRules;
    }

    /** Returns whether any policy rule exists. */
    public boolean hasPolicyRules() {
        return mPolicyRules != 0;
    }

    /** Checks whether a certain policy rule exists. */
    public boolean hasPolicyRule(@PolicyRule int policy) {
        return (mPolicyRules & policy) != 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        EuiccProfileInfo that = (EuiccProfileInfo) obj;
        return Objects.equals(mIccid, that.mIccid)
                && Objects.equals(mNickname, that.mNickname)
                && Objects.equals(mServiceProviderName, that.mServiceProviderName)
                && Objects.equals(mProfileName, that.mProfileName)
                && mProfileClass == that.mProfileClass
                && mState == that.mState
                && Objects.equals(mCarrierIdentifier, that.mCarrierIdentifier)
                && mPolicyRules == that.mPolicyRules
                && Arrays.equals(mAccessRules, that.mAccessRules);
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + Objects.hashCode(mIccid);
        result = 31 * result + Objects.hashCode(mNickname);
        result = 31 * result + Objects.hashCode(mServiceProviderName);
        result = 31 * result + Objects.hashCode(mProfileName);
        result = 31 * result + mProfileClass;
        result = 31 * result + mState;
        result = 31 * result + Objects.hashCode(mCarrierIdentifier);
        result = 31 * result + mPolicyRules;
        result = 31 * result + Arrays.hashCode(mAccessRules);
        return result;
    }

    @Override
    public String toString() {
        return "EuiccProfileInfo (nickname="
                + mNickname
                + ", serviceProviderName="
                + mServiceProviderName
                + ", profileName="
                + mProfileName
                + ", profileClass="
                + mProfileClass
                + ", state="
                + mState
                + ", CarrierIdentifier="
                + mCarrierIdentifier
                + ", policyRules="
                + mPolicyRules
                + ", accessRules="
                + Arrays.toString(mAccessRules)
                + ")";
    }
}
