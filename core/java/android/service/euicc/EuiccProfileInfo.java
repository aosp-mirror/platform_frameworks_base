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
import android.os.Parcel;
import android.os.Parcelable;
import android.service.carrier.CarrierIdentifier;
import android.telephony.UiccAccessRule;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * Information about an embedded profile (subscription) on an eUICC.
 *
 * @hide
 *
 * TODO(b/35851809): Make this a SystemApi.
 */
public final class EuiccProfileInfo implements Parcelable {

    /** Profile policy rules (bit mask) */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "POLICY_RULE_" }, value = {
            POLICY_RULE_DO_NOT_DISABLE,
            POLICY_RULE_DO_NOT_DELETE,
            POLICY_RULE_DELETE_AFTER_DISABLING
    })
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
    public final String iccid;

    /** An optional nickname for the subscription. */
    public final @Nullable String nickname;

    /** The service provider name for the subscription. */
    public final String serviceProviderName;

    /** The profile name for the subscription. */
    public final String profileName;

    /** Profile class for the subscription. */
    @ProfileClass public final int profileClass;

    /** The profile state of the subscription. */
    @ProfileState public final int state;

    /** The operator Id of the subscription. */
    public final CarrierIdentifier carrierIdentifier;

    /** The policy rules of the subscription. */
    @PolicyRule public final int policyRules;

    /**
     * Optional access rules defining which apps can manage this subscription. If unset, only the
     * platform can manage it.
     */
    public final @Nullable UiccAccessRule[] accessRules;

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
        this.iccid = iccid;
        this.accessRules = accessRules;
        this.nickname = nickname;

        this.serviceProviderName = null;
        this.profileName = null;
        this.profileClass = PROFILE_CLASS_UNSET;
        this.state = PROFILE_CLASS_UNSET;
        this.carrierIdentifier = null;
        this.policyRules = 0;
    }

    private EuiccProfileInfo(Parcel in) {
        iccid = in.readString();
        nickname = in.readString();
        serviceProviderName = in.readString();
        profileName = in.readString();
        profileClass = in.readInt();
        state = in.readInt();
        byte exist = in.readByte();
        if (exist == (byte) 1) {
            carrierIdentifier = CarrierIdentifier.CREATOR.createFromParcel(in);
        } else {
            carrierIdentifier = null;
        }
        policyRules = in.readInt();
        accessRules = in.createTypedArray(UiccAccessRule.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(iccid);
        dest.writeString(nickname);
        dest.writeString(serviceProviderName);
        dest.writeString(profileName);
        dest.writeInt(profileClass);
        dest.writeInt(state);
        if (carrierIdentifier != null) {
            dest.writeByte((byte) 1);
            carrierIdentifier.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeInt(policyRules);
        dest.writeTypedArray(accessRules, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** The builder to build a new {@link EuiccProfileInfo} instance. */
    public static final class Builder {
        public String iccid;
        public UiccAccessRule[] accessRules;
        public String nickname;
        public String serviceProviderName;
        public String profileName;
        @ProfileClass public int profileClass;
        @ProfileState public int state;
        public CarrierIdentifier carrierIdentifier;
        @PolicyRule public int policyRules;

        public Builder() {}

        public Builder(EuiccProfileInfo baseProfile) {
            iccid = baseProfile.iccid;
            nickname = baseProfile.nickname;
            serviceProviderName = baseProfile.serviceProviderName;
            profileName = baseProfile.profileName;
            profileClass = baseProfile.profileClass;
            state = baseProfile.state;
            carrierIdentifier = baseProfile.carrierIdentifier;
            policyRules = baseProfile.policyRules;
            accessRules = baseProfile.accessRules;
        }

        /** Builds the profile instance. */
        public EuiccProfileInfo build() {
            if (iccid == null) {
                throw new IllegalStateException("ICCID must be set for a profile.");
            }
            return new EuiccProfileInfo(
                    iccid,
                    nickname,
                    serviceProviderName,
                    profileName,
                    profileClass,
                    state,
                    carrierIdentifier,
                    policyRules,
                    accessRules);
        }

        /** Sets the iccId of the subscription. */
        public Builder setIccid(String value) {
            if (!TextUtils.isDigitsOnly(value)) {
                throw new IllegalArgumentException("iccid contains invalid characters: " + value);
            }
            iccid = value;
            return this;
        }

        /** Sets the nickname of the subscription. */
        public Builder setNickname(String value) {
            nickname = value;
            return this;
        }

        /** Sets the service provider name of the subscription. */
        public Builder setServiceProviderName(String value) {
            serviceProviderName = value;
            return this;
        }

        /** Sets the profile name of the subscription. */
        public Builder setProfileName(String value) {
            profileName = value;
            return this;
        }

        /** Sets the profile class of the subscription. */
        public Builder setProfileClass(@ProfileClass int value) {
            profileClass = value;
            return this;
        }

        /** Sets the state of the subscription. */
        public Builder setState(@ProfileState int value) {
            state = value;
            return this;
        }

        /** Sets the carrier identifier of the subscription. */
        public Builder setCarrierIdentifier(CarrierIdentifier value) {
            carrierIdentifier = value;
            return this;
        }

        /** Sets the policy rules of the subscription. */
        public Builder setPolicyRules(@PolicyRule int value) {
            policyRules = value;
            return this;
        }

        /** Sets the access rules of the subscription. */
        public Builder setUiccAccessRule(@Nullable UiccAccessRule[] value) {
            accessRules = value;
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
            @Nullable UiccAccessRule[] accessRules) {
        this.iccid = iccid;
        this.nickname = nickname;
        this.serviceProviderName = serviceProviderName;
        this.profileName = profileName;
        this.profileClass = profileClass;
        this.state = state;
        this.carrierIdentifier = carrierIdentifier;
        this.policyRules = policyRules;
        this.accessRules = accessRules;
    }

    /** Gets the ICCID string. */
    public String getIccid() {
        return iccid;
    }

    /** Gets the access rules. */
    @Nullable
    public UiccAccessRule[] getUiccAccessRules() {
        return accessRules;
    }

    /** Gets the nickname. */
    public String getNickname() {
        return nickname;
    }

    /** Gets the service provider name. */
    public String getServiceProviderName() {
        return serviceProviderName;
    }

    /** Gets the profile name. */
    public String getProfileName() {
        return profileName;
    }

    /** Gets the profile class. */
    @ProfileClass
    public int getProfileClass() {
        return profileClass;
    }

    /** Gets the state of the subscription. */
    @ProfileState
    public int getState() {
        return state;
    }

    /** Gets the carrier identifier. */
    public CarrierIdentifier getCarrierIdentifier() {
        return carrierIdentifier;
    }

    /** Gets the policy rules. */
    @PolicyRule
    public int getPolicyRules() {
        return policyRules;
    }

    /** Returns whether any policy rule exists. */
    public boolean hasPolicyRules() {
        return policyRules != 0;
    }

    /** Checks whether a certain policy rule exists. */
    public boolean hasPolicyRule(@PolicyRule int policy) {
        return (policyRules & policy) != 0;
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
        return Objects.equals(iccid, that.iccid)
                && Objects.equals(nickname, that.nickname)
                && Objects.equals(serviceProviderName, that.serviceProviderName)
                && Objects.equals(profileName, that.profileName)
                && profileClass == that.profileClass
                && state == that.state
                && Objects.equals(carrierIdentifier, that.carrierIdentifier)
                && policyRules == that.policyRules
                && Arrays.equals(accessRules, that.accessRules);
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + Objects.hashCode(iccid);
        result = 31 * result + Objects.hashCode(nickname);
        result = 31 * result + Objects.hashCode(serviceProviderName);
        result = 31 * result + Objects.hashCode(profileName);
        result = 31 * result + profileClass;
        result = 31 * result + state;
        result = 31 * result + Objects.hashCode(carrierIdentifier);
        result = 31 * result + policyRules;
        result = 31 * result + Arrays.hashCode(accessRules);
        return result;
    }

    @Override
    public String toString() {
        return "EuiccProfileInfo (nickname="
                + nickname
                + ", serviceProviderName="
                + serviceProviderName
                + ", profileName="
                + profileName
                + ", profileClass="
                + profileClass
                + ", state="
                + state
                + ", CarrierIdentifier="
                + carrierIdentifier.toString()
                + ", policyRules="
                + policyRules
                + ", accessRules="
                + Arrays.toString(accessRules)
                + ")";
    }
}
