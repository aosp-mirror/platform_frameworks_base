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
package android.telephony.euicc;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.UiccAccessRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.internal.util.Preconditions;

/** Information about a subscription which is available for download. */
public final class DownloadableSubscription implements Parcelable {

    public static final Creator<DownloadableSubscription> CREATOR =
            new Creator<DownloadableSubscription>() {
                @Override
                public DownloadableSubscription createFromParcel(Parcel in) {
                    return new DownloadableSubscription(in);
                }

                @Override
                public DownloadableSubscription[] newArray(int size) {
                    return new DownloadableSubscription[size];
                }
            };

    /**
     * Activation code. May be null for subscriptions which are not based on activation codes, e.g.
     * to download a default subscription assigned to this device.
     * Should use getEncodedActivationCode() instead.
     * @hide
     * @deprecated - Do not use. This will be private. Use getEncodedActivationCode() instead.
     */
    @Nullable
    @Deprecated
    public final String encodedActivationCode;

    @Nullable private String confirmationCode;

    // see getCarrierName and setCarrierName
    @Nullable
    private String carrierName;

    // see getAccessRules and setAccessRules
    @Nullable
    private List<UiccAccessRule> accessRules;

    /** Gets the activation code. */
    @Nullable
    public String getEncodedActivationCode() {
        return encodedActivationCode;
    }

    /** @hide */
    private DownloadableSubscription(String encodedActivationCode) {
        this.encodedActivationCode = encodedActivationCode;
    }

    private DownloadableSubscription(Parcel in) {
        encodedActivationCode = in.readString();
        confirmationCode = in.readString();
        carrierName = in.readString();
        accessRules = new ArrayList<UiccAccessRule>();
        in.readTypedList(accessRules, UiccAccessRule.CREATOR);
    }

    private DownloadableSubscription(String encodedActivationCode, String confirmationCode,
            String carrierName, List<UiccAccessRule> accessRules) {
        this.encodedActivationCode = encodedActivationCode;
        this.confirmationCode = confirmationCode;
        this.carrierName = carrierName;
        this.accessRules = accessRules;
    }

    /** @hide */
    @SystemApi
    public static final class Builder {
        @Nullable private String encodedActivationCode;
        @Nullable private String confirmationCode;
        @Nullable private String carrierName;
        List<UiccAccessRule> accessRules;

        public Builder() {}

        public Builder(DownloadableSubscription baseSubscription) {
            encodedActivationCode = baseSubscription.getEncodedActivationCode();
            confirmationCode = baseSubscription.getConfirmationCode();
            carrierName = baseSubscription.getCarrierName();
            accessRules = baseSubscription.getAccessRules();
        }

        public DownloadableSubscription build() {
            return new DownloadableSubscription(encodedActivationCode, confirmationCode,
                    carrierName, accessRules);
        }

        public Builder setEncodedActivationCode(String value) {
            encodedActivationCode = value;
            return this;
        }

        public Builder setConfirmationCode(String value) {
            confirmationCode = value;
            return this;
        }

        public Builder setCarrierName(String value) {
            carrierName = value;
            return this;
        }

        public Builder setAccessRules(List<UiccAccessRule> value) {
            accessRules = value;
            return this;
        }
    }

    /**
     * Create a DownloadableSubscription for the given activation code.
     *
     * @param encodedActivationCode the activation code to use. Must not be null.
     * @return the {@link DownloadableSubscription} which may be passed to
     *     {@link EuiccManager#downloadSubscription}.
     */
    public static DownloadableSubscription forActivationCode(String encodedActivationCode) {
        Preconditions.checkNotNull(encodedActivationCode, "Activation code may not be null");
        return new DownloadableSubscription(encodedActivationCode);
    }

    /**
     * Sets the confirmation code.
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public void setConfirmationCode(String confirmationCode) {
        this.confirmationCode = confirmationCode;
    }

    /**
     * Returns the confirmation code.
     */
    @Nullable
    public String getConfirmationCode() {
        return confirmationCode;
    }

    /**
     * Set the user-visible carrier name.
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public void setCarrierName(String carrierName) {
        this.carrierName = carrierName;
    }

    /**
     * Returns the user-visible carrier name.
     *
     * <p>Only present for downloadable subscriptions that were queried from a server (as opposed to
     * those created with {@link #forActivationCode}). May be populated with
     * {@link EuiccManager#getDownloadableSubscriptionMetadata}.
     * @hide
     */
    @SystemApi
    @Nullable
    public String getCarrierName() {
        return carrierName;
    }

    /**
     * Returns the {@link UiccAccessRule}s in list dictating access to this subscription.
     *
     * <p>Only present for downloadable subscriptions that were queried from a server (as opposed to
     * those created with {@link #forActivationCode}). May be populated with
     * {@link EuiccManager#getDownloadableSubscriptionMetadata}.
     * @hide
     */
    @SystemApi
    public List<UiccAccessRule> getAccessRules() {
        return accessRules;
    }

    /**
     * Set the {@link UiccAccessRule}s dictating access to this subscription.
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public void setAccessRules(List<UiccAccessRule> accessRules) {
        this.accessRules = accessRules;
    }

    /**
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public void setAccessRules(UiccAccessRule[] accessRules) {
        this.accessRules = Arrays.asList(accessRules);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(encodedActivationCode);
        dest.writeString(confirmationCode);
        dest.writeString(carrierName);
        dest.writeTypedList(accessRules);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
