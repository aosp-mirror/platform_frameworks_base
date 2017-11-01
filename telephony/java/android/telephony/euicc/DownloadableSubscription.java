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
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.UiccAccessRule;

import com.android.internal.util.Preconditions;

/**
 * Information about a subscription which is available for download.
 *
 * TODO(b/35851809): Make this public.
 * @hide
 */
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
     * @hide
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    @Nullable
    public final String encodedActivationCode;

    // see getCarrierName and setCarrierName
    @Nullable
    private String carrierName;
    // see getAccessRules and setAccessRules
    private UiccAccessRule[] accessRules;

    /** @hide */
    private DownloadableSubscription(String encodedActivationCode) {
        this.encodedActivationCode = encodedActivationCode;
    }

    private DownloadableSubscription(Parcel in) {
        encodedActivationCode = in.readString();
        carrierName = in.readString();
        accessRules = in.createTypedArray(UiccAccessRule.CREATOR);
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
     * Set the user-visible carrier name.
     * @hide
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
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
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    @Nullable
    public String getCarrierName() {
        return carrierName;
    }

    /**
     * Returns the {@link UiccAccessRule}s dictating access to this subscription.
     *
     * <p>Only present for downloadable subscriptions that were queried from a server (as opposed to
     * those created with {@link #forActivationCode}). May be populated with
     * {@link EuiccManager#getDownloadableSubscriptionMetadata}.
     * @hide
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    public UiccAccessRule[] getAccessRules() {
        return accessRules;
    }

    /**
     * Set the {@link UiccAccessRule}s dictating access to this subscription.
     * @hide
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    public void setAccessRules(UiccAccessRule[] accessRules) {
        this.accessRules = accessRules;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(encodedActivationCode);
        dest.writeString(carrierName);
        dest.writeTypedArray(accessRules, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
