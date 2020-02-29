/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi.hotspot2;

import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_NONE;
import static android.net.wifi.WifiConfiguration.MeteredOverride;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Class representing Passpoint configuration.  This contains configurations specified in
 * PerProviderSubscription (PPS) Management Object (MO) tree.
 *
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 */
public final class PasspointConfiguration implements Parcelable {
    private static final String TAG = "PasspointConfiguration";

    /**
     * Number of bytes for certificate SHA-256 fingerprint byte array.
     */
    private static final int CERTIFICATE_SHA256_BYTES = 32;

    /**
     * Maximum bytes for URL string.
     */
    private static final int MAX_URL_BYTES = 1023;

    /**
     * Integer value used for indicating null value in the Parcel.
     */
    private static final int NULL_VALUE = -1;

    /**
     * Configurations under HomeSp subtree.
     */
    private HomeSp mHomeSp = null;

    /**
     * Set the Home SP (Service Provider) information.
     *
     * @param homeSp The Home SP information to set to
     */
    public void setHomeSp(HomeSp homeSp) { mHomeSp = homeSp; }
    /**
     * Get the Home SP (Service Provider) information.
     *
     * @return Home SP information
     */
    public HomeSp getHomeSp() { return mHomeSp; }

    /**
     * Configurations under AAAServerTrustedNames subtree.
     */
    private String[] mAaaServerTrustedNames = null;
    /**
     * Set the AAA server trusted names information.
     *
     * @param aaaServerTrustedNames The AAA server trusted names information to set to
     * @hide
     */
    public void setAaaServerTrustedNames(@Nullable String[] aaaServerTrustedNames) {
        mAaaServerTrustedNames = aaaServerTrustedNames;
    }
    /**
     * Get the AAA server trusted names information.
     *
     * @return AAA server trusted names information
     * @hide
     */
    public @Nullable String[] getAaaServerTrustedNames() {
        return mAaaServerTrustedNames;
    }

    /**
     * Configurations under Credential subtree.
     */
    private Credential mCredential = null;
    /**
     * Set the credential information.
     *
     * @param credential The credential information to set to
     */
    public void setCredential(Credential credential) {
        mCredential = credential;
    }
    /**
     * Get the credential information.
     *
     * @return credential information
     */
    public Credential getCredential() {
        return mCredential;
    }

    /**
     * Configurations under Policy subtree.
     */
    private Policy mPolicy = null;
    /**
     * @hide
     */
    public void setPolicy(Policy policy) {
        mPolicy = policy;
    }
    /**
     * @hide
     */
    public Policy getPolicy() {
        return mPolicy;
    }

    /**
     * Meta data for performing subscription update.
     */
    private UpdateParameter mSubscriptionUpdate = null;
    /**
     * @hide
     */
    public void setSubscriptionUpdate(UpdateParameter subscriptionUpdate) {
        mSubscriptionUpdate = subscriptionUpdate;
    }
    /**
     * @hide
     */
    public UpdateParameter getSubscriptionUpdate() {
        return mSubscriptionUpdate;
    }

    /**
     * List of HTTPS URL for retrieving trust root certificate and the corresponding SHA-256
     * fingerprint of the certificate.  The certificates are used for verifying AAA server's
     * identity during EAP authentication.
     */
    private Map<String, byte[]> mTrustRootCertList = null;
    /**
     * @hide
     */
    public void setTrustRootCertList(Map<String, byte[]> trustRootCertList) {
        mTrustRootCertList = trustRootCertList;
    }
    /**
     * @hide
     */
    public Map<String, byte[]> getTrustRootCertList() {
        return mTrustRootCertList;
    }

    /**
     * Set by the subscription server, updated every time the configuration is updated by
     * the subscription server.
     *
     * Use Integer.MIN_VALUE to indicate unset value.
     */
    private int mUpdateIdentifier = Integer.MIN_VALUE;
    /**
     * @hide
     */
    public void setUpdateIdentifier(int updateIdentifier) {
        mUpdateIdentifier = updateIdentifier;
    }
    /**
     * @hide
     */
    public int getUpdateIdentifier() {
        return mUpdateIdentifier;
    }

    /**
     * The priority of the credential.
     *
     * Use Integer.MIN_VALUE to indicate unset value.
     */
    private int mCredentialPriority = Integer.MIN_VALUE;
    /**
     * @hide
     */
    public void setCredentialPriority(int credentialPriority) {
        mCredentialPriority = credentialPriority;
    }
    /**
     * @hide
     */
    public int getCredentialPriority() {
        return mCredentialPriority;
    }

    /**
     * The time this subscription is created. It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    private long mSubscriptionCreationTimeInMillis = Long.MIN_VALUE;
    /**
     * @hide
     */
    public void setSubscriptionCreationTimeInMillis(long subscriptionCreationTimeInMillis) {
        mSubscriptionCreationTimeInMillis = subscriptionCreationTimeInMillis;
    }
    /**
     * @hide
     */
    public long getSubscriptionCreationTimeInMillis() {
        return mSubscriptionCreationTimeInMillis;
    }

    /**
     * The time this subscription will expire. It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    private long mSubscriptionExpirationTimeMillis = Long.MIN_VALUE;
    /**
     * @hide
     */
    public void setSubscriptionExpirationTimeInMillis(long subscriptionExpirationTimeInMillis) {
        mSubscriptionExpirationTimeMillis = subscriptionExpirationTimeInMillis;
    }
    /**
     *  Utility method to get the time this subscription will expire. It is in the format of number
     *  of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     *  @return The time this subscription will expire, or Long.MIN_VALUE to indicate unset value
     */
    @CurrentTimeMillisLong
    public long getSubscriptionExpirationTimeMillis() {
        return mSubscriptionExpirationTimeMillis;
    }

    /**
     * The type of the subscription.  This is defined by the provider and the value is provider
     * specific.
     */
    private String mSubscriptionType = null;
    /**
     * @hide
     */
    public void setSubscriptionType(String subscriptionType) {
        mSubscriptionType = subscriptionType;
    }
    /**
     * @hide
     */
    public String getSubscriptionType() {
        return mSubscriptionType;
    }

    /**
     * The time period for usage statistics accumulation. A value of zero means that usage
     * statistics are not accumulated on a periodic basis (e.g., a one-time limit for
     * “pay as you go” - PAYG service). A non-zero value specifies the usage interval in minutes.
     */
    private long mUsageLimitUsageTimePeriodInMinutes = Long.MIN_VALUE;
    /**
     * @hide
     */
    public void setUsageLimitUsageTimePeriodInMinutes(long usageLimitUsageTimePeriodInMinutes) {
        mUsageLimitUsageTimePeriodInMinutes = usageLimitUsageTimePeriodInMinutes;
    }
    /**
     * @hide
     */
    public long getUsageLimitUsageTimePeriodInMinutes() {
        return mUsageLimitUsageTimePeriodInMinutes;
    }

    /**
     * The time at which usage statistic accumulation  begins.  It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    private long mUsageLimitStartTimeInMillis = Long.MIN_VALUE;
    /**
     * @hide
     */
    public void setUsageLimitStartTimeInMillis(long usageLimitStartTimeInMillis) {
        mUsageLimitStartTimeInMillis = usageLimitStartTimeInMillis;
    }
    /**
     * @hide
     */
    public long getUsageLimitStartTimeInMillis() {
        return mUsageLimitStartTimeInMillis;
    }

    /**
     * The cumulative data limit in megabytes for the {@link #usageLimitUsageTimePeriodInMinutes}.
     * A value of zero indicate unlimited data usage.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    private long mUsageLimitDataLimit = Long.MIN_VALUE;
    /**
     * @hide
     */
    public void setUsageLimitDataLimit(long usageLimitDataLimit) {
        mUsageLimitDataLimit = usageLimitDataLimit;
    }
    /**
     * @hide
     */
    public long getUsageLimitDataLimit() {
        return mUsageLimitDataLimit;
    }

    /**
     * The cumulative time limit in minutes for the {@link #usageLimitUsageTimePeriodInMinutes}.
     * A value of zero indicate unlimited time usage.
     */
    private long mUsageLimitTimeLimitInMinutes = Long.MIN_VALUE;
    /**
     * @hide
     */
    public void setUsageLimitTimeLimitInMinutes(long usageLimitTimeLimitInMinutes) {
        mUsageLimitTimeLimitInMinutes = usageLimitTimeLimitInMinutes;
    }
    /**
     * @hide
     */
    public long getUsageLimitTimeLimitInMinutes() {
        return mUsageLimitTimeLimitInMinutes;
    }

    /**
     * The map of OSU service provider names whose each element is presented in different
     * languages for the service provider, which is used for finding a matching
     * PasspointConfiguration with a given service provider name.
     */
    private Map<String, String> mServiceFriendlyNames = null;

    /**
     * @hide
     */
    public void setServiceFriendlyNames(Map<String, String> serviceFriendlyNames) {
        mServiceFriendlyNames = serviceFriendlyNames;
    }

    /**
     * @hide
     */
    public Map<String, String> getServiceFriendlyNames() {
        return mServiceFriendlyNames;
    }

    /**
     * Return the friendly Name for current language from the list of friendly names of OSU
     * provider.
     * The string matching the default locale will be returned if it is found, otherwise the
     * first string in the list will be returned.  A null will be returned if the list is empty.
     *
     * @return String matching the default locale, null otherwise
     * @hide
     */
    public String getServiceFriendlyName() {
        if (mServiceFriendlyNames == null || mServiceFriendlyNames.isEmpty()) return null;
        String lang = Locale.getDefault().getLanguage();
        String friendlyName = mServiceFriendlyNames.get(lang);
        if (friendlyName != null) {
            return friendlyName;
        }
        friendlyName = mServiceFriendlyNames.get("en");
        if (friendlyName != null) {
            return friendlyName;
        }
        return mServiceFriendlyNames.get(mServiceFriendlyNames.keySet().stream().findFirst().get());
    }

    /**
     * The carrier ID identifies the operator who provides this network configuration.
     *    see {@link TelephonyManager#getSimCarrierId()}
     */
    private int mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;

    /**
     * Set the carrier ID associated with current configuration.
     * @param carrierId {@code mCarrierId}
     * @hide
     */
    public void setCarrierId(int carrierId) {
        this.mCarrierId = carrierId;
    }

    /**
     * Get the carrier ID associated with current configuration.
     * @return {@code mCarrierId}
     * @hide
     */
    public int getCarrierId() {
        return mCarrierId;
    }

    /**
     * The auto-join configuration specifies whether or not the Passpoint Configuration is
     * considered for auto-connection. If true then yes, if false then it isn't considered as part
     * of auto-connection - but can still be manually connected to.
     */
    private boolean mIsAutojoinEnabled = true;

    /**
     * The mac randomization setting specifies whether a randomized or device MAC address will
     * be used to connect to the passpoint network. If true, a randomized MAC will be used.
     * Otherwise, the device MAC address will be used.
     */
    private boolean mIsMacRandomizationEnabled = true;

    /**
     * Indicates if the end user has expressed an explicit opinion about the
     * meteredness of this network, such as through the Settings app.
     * This value is one of {@link #METERED_OVERRIDE_NONE}, {@link #METERED_OVERRIDE_METERED},
     * or {@link #METERED_OVERRIDE_NOT_METERED}.
     * <p>
     * This should always override any values from {@link WifiInfo#getMeteredHint()}.
     *
     * By default this field is set to {@link #METERED_OVERRIDE_NONE}.
     */
    private int mMeteredOverride = METERED_OVERRIDE_NONE;

    /**
     * Configures the auto-association status of this Passpoint configuration. A value of true
     * indicates that the configuration will be considered for auto-connection, a value of false
     * indicates that only manual connection will work - the framework will not auto-associate to
     * this Passpoint network.
     *
     * @param autojoinEnabled true to be considered for framework auto-connection, false otherwise.
     * @hide
     */
    public void setAutojoinEnabled(boolean autojoinEnabled) {
        mIsAutojoinEnabled = autojoinEnabled;
    }

    /**
     * Configures the MAC randomization setting for this Passpoint configuration.
     * If set to true, the framework will use a randomized MAC address to connect to this Passpoint
     * network. Otherwise, the framework will use the device MAC address.
     *
     * @param enabled true to use randomized MAC address, false to use device MAC address.
     * @hide
     */
    public void setMacRandomizationEnabled(boolean enabled) {
        mIsMacRandomizationEnabled = enabled;
    }

    /**
     * Sets the metered override setting for this Passpoint configuration.
     *
     * @param meteredOverride One of the values in {@link MeteredOverride}
     * @hide
     */
    public void setMeteredOverride(@MeteredOverride int meteredOverride) {
        mMeteredOverride = meteredOverride;
    }

    /**
     * Indicates whether the Passpoint configuration may be auto-connected to by the framework. A
     * value of true indicates that auto-connection can happen, a value of false indicates that it
     * cannot. However, even when auto-connection is not possible manual connection by the user is
     * possible.
     *
     * @return the auto-join configuration: true for auto-connection (or join) enabled, false
     * otherwise.
     * @hide
     */
    @SystemApi
    public boolean isAutojoinEnabled() {
        return mIsAutojoinEnabled;
    }

    /**
     * Indicates whether the user chose this configuration to be treated as metered or not.
     *
     * @return One of the values in {@link MeteredOverride}
     * @hide
     */
    @SystemApi
    @MeteredOverride
    public int getMeteredOverride() {
        return mMeteredOverride;
    }

    /**
     * Indicates whether a randomized MAC address or device MAC address will be used for
     * connections to this Passpoint network. If true, a randomized MAC address will be used.
     * Otherwise, the device MAC address will be used.
     *
     * @return true for MAC randomization enabled. False for disabled.
     * @hide
     */
    @SystemApi
    public boolean isMacRandomizationEnabled() {
        return mIsMacRandomizationEnabled;
    }

    /**
     * Constructor for creating PasspointConfiguration with default values.
     */
    public PasspointConfiguration() {}

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     */
    public PasspointConfiguration(PasspointConfiguration source) {
        if (source == null) {
            return;
        }

        if (source.mHomeSp != null) {
            mHomeSp = new HomeSp(source.mHomeSp);
        }
        if (source.mCredential != null) {
            mCredential = new Credential(source.mCredential);
        }
        if (source.mPolicy != null) {
            mPolicy = new Policy(source.mPolicy);
        }
        if (source.mTrustRootCertList != null) {
            mTrustRootCertList = Collections.unmodifiableMap(source.mTrustRootCertList);
        }
        if (source.mSubscriptionUpdate != null) {
            mSubscriptionUpdate = new UpdateParameter(source.mSubscriptionUpdate);
        }
        mUpdateIdentifier = source.mUpdateIdentifier;
        mCredentialPriority = source.mCredentialPriority;
        mSubscriptionCreationTimeInMillis = source.mSubscriptionCreationTimeInMillis;
        mSubscriptionExpirationTimeMillis = source.mSubscriptionExpirationTimeMillis;
        mSubscriptionType = source.mSubscriptionType;
        mUsageLimitDataLimit = source.mUsageLimitDataLimit;
        mUsageLimitStartTimeInMillis = source.mUsageLimitStartTimeInMillis;
        mUsageLimitTimeLimitInMinutes = source.mUsageLimitTimeLimitInMinutes;
        mUsageLimitUsageTimePeriodInMinutes = source.mUsageLimitUsageTimePeriodInMinutes;
        mServiceFriendlyNames = source.mServiceFriendlyNames;
        mAaaServerTrustedNames = source.mAaaServerTrustedNames;
        mCarrierId = source.mCarrierId;
        mIsAutojoinEnabled = source.mIsAutojoinEnabled;
        mIsMacRandomizationEnabled = source.mIsMacRandomizationEnabled;
        mMeteredOverride = source.mMeteredOverride;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mHomeSp, flags);
        dest.writeParcelable(mCredential, flags);
        dest.writeParcelable(mPolicy, flags);
        dest.writeParcelable(mSubscriptionUpdate, flags);
        writeTrustRootCerts(dest, mTrustRootCertList);
        dest.writeInt(mUpdateIdentifier);
        dest.writeInt(mCredentialPriority);
        dest.writeLong(mSubscriptionCreationTimeInMillis);
        dest.writeLong(mSubscriptionExpirationTimeMillis);
        dest.writeString(mSubscriptionType);
        dest.writeLong(mUsageLimitUsageTimePeriodInMinutes);
        dest.writeLong(mUsageLimitStartTimeInMillis);
        dest.writeLong(mUsageLimitDataLimit);
        dest.writeLong(mUsageLimitTimeLimitInMinutes);
        dest.writeStringArray(mAaaServerTrustedNames);
        Bundle bundle = new Bundle();
        bundle.putSerializable("serviceFriendlyNames",
                (HashMap<String, String>) mServiceFriendlyNames);
        dest.writeBundle(bundle);
        dest.writeInt(mCarrierId);
        dest.writeBoolean(mIsAutojoinEnabled);
        dest.writeBoolean(mIsMacRandomizationEnabled);
        dest.writeInt(mMeteredOverride);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof PasspointConfiguration)) {
            return false;
        }
        PasspointConfiguration that = (PasspointConfiguration) thatObject;
        return (mHomeSp == null ? that.mHomeSp == null : mHomeSp.equals(that.mHomeSp))
                && (mAaaServerTrustedNames == null ? that.mAaaServerTrustedNames == null
                : Arrays.equals(mAaaServerTrustedNames, that.mAaaServerTrustedNames))
                && (mCredential == null ? that.mCredential == null
                : mCredential.equals(that.mCredential))
                && (mPolicy == null ? that.mPolicy == null : mPolicy.equals(that.mPolicy))
                && (mSubscriptionUpdate == null ? that.mSubscriptionUpdate == null
                : mSubscriptionUpdate.equals(that.mSubscriptionUpdate))
                && isTrustRootCertListEquals(mTrustRootCertList, that.mTrustRootCertList)
                && mUpdateIdentifier == that.mUpdateIdentifier
                && mCredentialPriority == that.mCredentialPriority
                && mSubscriptionCreationTimeInMillis == that.mSubscriptionCreationTimeInMillis
                && mSubscriptionExpirationTimeMillis == that.mSubscriptionExpirationTimeMillis
                && TextUtils.equals(mSubscriptionType, that.mSubscriptionType)
                && mUsageLimitUsageTimePeriodInMinutes == that.mUsageLimitUsageTimePeriodInMinutes
                && mUsageLimitStartTimeInMillis == that.mUsageLimitStartTimeInMillis
                && mUsageLimitDataLimit == that.mUsageLimitDataLimit
                && mUsageLimitTimeLimitInMinutes == that.mUsageLimitTimeLimitInMinutes
                && mCarrierId == that.mCarrierId
                && mIsAutojoinEnabled == that.mIsAutojoinEnabled
                && mIsMacRandomizationEnabled == that.mIsMacRandomizationEnabled
                && mMeteredOverride == that.mMeteredOverride
                && (mServiceFriendlyNames == null ? that.mServiceFriendlyNames == null
                : mServiceFriendlyNames.equals(that.mServiceFriendlyNames));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mHomeSp, mCredential, mPolicy, mSubscriptionUpdate, mTrustRootCertList,
                mUpdateIdentifier, mCredentialPriority, mSubscriptionCreationTimeInMillis,
                mSubscriptionExpirationTimeMillis, mUsageLimitUsageTimePeriodInMinutes,
                mUsageLimitStartTimeInMillis, mUsageLimitDataLimit, mUsageLimitTimeLimitInMinutes,
                mServiceFriendlyNames, mCarrierId, mIsAutojoinEnabled, mIsMacRandomizationEnabled,
                mMeteredOverride);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("UpdateIdentifier: ").append(mUpdateIdentifier).append("\n");
        builder.append("CredentialPriority: ").append(mCredentialPriority).append("\n");
        builder.append("SubscriptionCreationTime: ").append(
                mSubscriptionCreationTimeInMillis != Long.MIN_VALUE
                ? new Date(mSubscriptionCreationTimeInMillis) : "Not specified").append("\n");
        builder.append("SubscriptionExpirationTime: ").append(
                mSubscriptionExpirationTimeMillis != Long.MIN_VALUE
                ? new Date(mSubscriptionExpirationTimeMillis) : "Not specified").append("\n");
        builder.append("UsageLimitStartTime: ").append(mUsageLimitStartTimeInMillis != Long.MIN_VALUE
                ? new Date(mUsageLimitStartTimeInMillis) : "Not specified").append("\n");
        builder.append("UsageTimePeriod: ").append(mUsageLimitUsageTimePeriodInMinutes)
                .append("\n");
        builder.append("UsageLimitDataLimit: ").append(mUsageLimitDataLimit).append("\n");
        builder.append("UsageLimitTimeLimit: ").append(mUsageLimitTimeLimitInMinutes).append("\n");
        builder.append("Provisioned by a subscription server: ")
                .append(isOsuProvisioned() ? "Yes" : "No").append("\n");
        if (mHomeSp != null) {
            builder.append("HomeSP Begin ---\n");
            builder.append(mHomeSp);
            builder.append("HomeSP End ---\n");
        }
        if (mCredential != null) {
            builder.append("Credential Begin ---\n");
            builder.append(mCredential);
            builder.append("Credential End ---\n");
        }
        if (mPolicy != null) {
            builder.append("Policy Begin ---\n");
            builder.append(mPolicy);
            builder.append("Policy End ---\n");
        }
        if (mSubscriptionUpdate != null) {
            builder.append("SubscriptionUpdate Begin ---\n");
            builder.append(mSubscriptionUpdate);
            builder.append("SubscriptionUpdate End ---\n");
        }
        if (mTrustRootCertList != null) {
            builder.append("TrustRootCertServers: ").append(mTrustRootCertList.keySet())
                    .append("\n");
        }
        if (mAaaServerTrustedNames != null) {
            builder.append("AAAServerTrustedNames: ")
                    .append(String.join(";", mAaaServerTrustedNames)).append("\n");
        }
        if (mServiceFriendlyNames != null) {
            builder.append("ServiceFriendlyNames: ").append(mServiceFriendlyNames);
        }
        builder.append("CarrierId:" + mCarrierId);
        builder.append("IsAutojoinEnabled:" + mIsAutojoinEnabled);
        builder.append("mIsMacRandomizationEnabled:" + mIsMacRandomizationEnabled);
        builder.append("mMeteredOverride:" + mMeteredOverride);
        return builder.toString();
    }

    /**
     * Validate the R1 configuration data.
     *
     * @return true on success or false on failure
     * @hide
     */
    public boolean validate() {
        // Optional: PerProviderSubscription/<X+>/SubscriptionUpdate
        if (mSubscriptionUpdate != null && !mSubscriptionUpdate.validate()) {
            return false;
        }
        return validateForCommonR1andR2();
    }

    /**
     * Validate the R2 configuration data.
     *
     * @return true on success or false on failure
     * @hide
     */
    public boolean validateForR2() {
        // Required: PerProviderSubscription/UpdateIdentifier
        if (mUpdateIdentifier == Integer.MIN_VALUE) {
            return false;
        }

        // Required: PerProviderSubscription/<X+>/SubscriptionUpdate
        if (mSubscriptionUpdate == null || !mSubscriptionUpdate.validate()) {
            return false;
        }
        return validateForCommonR1andR2();
    }

    private boolean validateForCommonR1andR2() {
        // Required: PerProviderSubscription/<X+>/HomeSP
        if (mHomeSp == null || !mHomeSp.validate()) {
            return false;
        }

        // Required: PerProviderSubscription/<X+>/Credential
        if (mCredential == null || !mCredential.validate()) {
            return false;
        }

        // Optional: PerProviderSubscription/<X+>/Policy
        if (mPolicy != null && !mPolicy.validate()) {
            return false;
        }

        if (mTrustRootCertList != null) {
            for (Map.Entry<String, byte[]> entry : mTrustRootCertList.entrySet()) {
                String url = entry.getKey();
                byte[] certFingerprint = entry.getValue();
                if (TextUtils.isEmpty(url)) {
                    Log.d(TAG, "Empty URL");
                    return false;
                }
                if (url.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES) {
                    Log.d(TAG, "URL bytes exceeded the max: "
                            + url.getBytes(StandardCharsets.UTF_8).length);
                    return false;
                }

                if (certFingerprint == null) {
                    Log.d(TAG, "Fingerprint not specified");
                    return false;
                }
                if (certFingerprint.length != CERTIFICATE_SHA256_BYTES) {
                    Log.d(TAG, "Incorrect size of trust root certificate SHA-256 fingerprint: "
                            + certFingerprint.length);
                    return false;
                }
            }
        }
        return true;
    }

    public static final @android.annotation.NonNull Creator<PasspointConfiguration> CREATOR =
        new Creator<PasspointConfiguration>() {
            @Override
            public PasspointConfiguration createFromParcel(Parcel in) {
                PasspointConfiguration config = new PasspointConfiguration();
                config.setHomeSp(in.readParcelable(null));
                config.setCredential(in.readParcelable(null));
                config.setPolicy(in.readParcelable(null));
                config.setSubscriptionUpdate(in.readParcelable(null));
                config.setTrustRootCertList(readTrustRootCerts(in));
                config.setUpdateIdentifier(in.readInt());
                config.setCredentialPriority(in.readInt());
                config.setSubscriptionCreationTimeInMillis(in.readLong());
                config.setSubscriptionExpirationTimeInMillis(in.readLong());
                config.setSubscriptionType(in.readString());
                config.setUsageLimitUsageTimePeriodInMinutes(in.readLong());
                config.setUsageLimitStartTimeInMillis(in.readLong());
                config.setUsageLimitDataLimit(in.readLong());
                config.setUsageLimitTimeLimitInMinutes(in.readLong());
                config.setAaaServerTrustedNames(in.createStringArray());
                Bundle bundle = in.readBundle();
                Map<String, String> friendlyNamesMap = (HashMap) bundle.getSerializable(
                        "serviceFriendlyNames");
                config.setServiceFriendlyNames(friendlyNamesMap);
                config.mCarrierId = in.readInt();
                config.mIsAutojoinEnabled = in.readBoolean();
                config.mIsMacRandomizationEnabled = in.readBoolean();
                config.mMeteredOverride = in.readInt();
                return config;
            }

            @Override
            public PasspointConfiguration[] newArray(int size) {
                return new PasspointConfiguration[size];
            }

            /**
             * Helper function for reading trust root certificate info list from a Parcel.
             *
             * @param in The Parcel to read from
             * @return The list of trust root certificate URL with the corresponding certificate
             *         fingerprint
             */
            private Map<String, byte[]> readTrustRootCerts(Parcel in) {
                int size = in.readInt();
                if (size == NULL_VALUE) {
                    return null;
                }
                Map<String, byte[]> trustRootCerts = new HashMap<>(size);
                for (int i = 0; i < size; i++) {
                    String key = in.readString();
                    byte[] value = in.createByteArray();
                    trustRootCerts.put(key, value);
                }
                return trustRootCerts;
            }
        };

    /**
     * Helper function for writing trust root certificate information list.
     *
     * @param dest The Parcel to write to
     * @param trustRootCerts The list of trust root certificate URL with the corresponding
     *                       certificate fingerprint
     */
    private static void writeTrustRootCerts(Parcel dest, Map<String, byte[]> trustRootCerts) {
        if (trustRootCerts == null) {
            dest.writeInt(NULL_VALUE);
            return;
        }
        dest.writeInt(trustRootCerts.size());
        for (Map.Entry<String, byte[]> entry : trustRootCerts.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeByteArray(entry.getValue());
        }
    }

    /**
     * Helper function for comparing two trust root certificate list.  Cannot use Map#equals
     * method since the value type (byte[]) doesn't override equals method.
     *
     * @param list1 The first trust root certificate list
     * @param list2 The second trust root certificate list
     * @return true if the two list are equal
     */
    private static boolean isTrustRootCertListEquals(Map<String, byte[]> list1,
            Map<String, byte[]> list2) {
        if (list1 == null || list2 == null) {
            return list1 == list2;
        }
        if (list1.size() != list2.size()) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : list1.entrySet()) {
            if (!Arrays.equals(entry.getValue(), list2.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Indicates if the Passpoint Configuration was provisioned by a subscription (OSU) server,
     * which means that it's an R2 (or R3) profile.
     *
     * @return true if the Passpoint Configuration was provisioned by a subscription server.
     */
    public boolean isOsuProvisioned() {
        return getUpdateIdentifier() != Integer.MIN_VALUE;
    }

    /**
     * Get a unique identifier for a PasspointConfiguration object. The identifier depends on the
     * configuration that identify the service provider under the HomeSp subtree, and on the
     * credential configuration under the Credential subtree.
     * The method throws an {@link IllegalStateException} if the configuration under HomeSp subtree
     * or the configuration under Credential subtree are not initialized.
     *
     * @return A unique identifier
     */
    public @NonNull String getUniqueId() {
        if (mCredential == null || mHomeSp == null || TextUtils.isEmpty(mHomeSp.getFqdn())) {
            throw new IllegalStateException("Credential or HomeSP are not initialized");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s_%x%x", mHomeSp.getFqdn(), mHomeSp.getUniqueId(),
                mCredential.getUniqueId()));
        return sb.toString();
    }
}
