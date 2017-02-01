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

import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSP;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.os.Parcel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Class representing Passpoint configuration.  This contains configurations specified in
 * PerProviderSubscription (PPS) Management Object (MO) tree.
 *
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 *
 * @hide
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
     * Configurations under HomeSP subtree.
     */
    private HomeSP mHomeSp = null;
    public void setHomeSp(HomeSP homeSp) { mHomeSp = homeSp; }
    public HomeSP getHomeSp() { return mHomeSp; }

    /**
     * Configurations under Credential subtree.
     */
    private Credential mCredential = null;
    public void setCredential(Credential credential) {
        mCredential = credential;
    }
    public Credential getCredential() {
        return mCredential;
    }

    /**
     * Configurations under Policy subtree.
     */
    private Policy mPolicy = null;
    public void setPolicy(Policy policy) {
        mPolicy = policy;
    }
    public Policy getPolicy() {
        return mPolicy;
    }

    /**
     * Meta data for performing subscription update.
     */
    private UpdateParameter mSubscriptionUpdate = null;
    public void setSubscriptionUpdate(UpdateParameter subscriptionUpdate) {
        mSubscriptionUpdate = subscriptionUpdate;
    }
    public UpdateParameter getSubscriptionUpdate() {
        return mSubscriptionUpdate;
    }

    /**
     * List of HTTPS URL for retrieving trust root certificate and the corresponding SHA-256
     * fingerprint of the certificate.  The certificates are used for verifying AAA server's
     * identity during EAP authentication.
     */
    private Map<String, byte[]> mTrustRootCertList = null;
    public void setTrustRootCertList(Map<String, byte[]> trustRootCertList) {
        mTrustRootCertList = trustRootCertList;
    }
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
    public void setUpdateIdentifier(int updateIdentifier) {
        mUpdateIdentifier = updateIdentifier;
    }
    public int getUpdateIdentififer() {
        return mUpdateIdentifier;
    }

    /**
     * The priority of the credential.
     *
     * Use Integer.MIN_VALUE to indicate unset value.
     */
    private int mCredentialPriority = Integer.MIN_VALUE;
    public void setCredentialPriority(int credentialPriority) {
        mCredentialPriority = credentialPriority;
    }
    public int getCredentialPriority() {
        return mCredentialPriority;
    }

    /**
     * The time this subscription is created. It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    private long mSubscriptionCreationTimeInMs = Long.MIN_VALUE;
    public void setSubscriptionCreationTimeInMs(long subscriptionCreationTimeInMs) {
        mSubscriptionCreationTimeInMs = subscriptionCreationTimeInMs;
    }
    public long getSubscriptionCreationTimeInMs() {
        return mSubscriptionCreationTimeInMs;
    }

    /**
     * The time this subscription will expire. It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    private long mSubscriptionExpirationTimeInMs = Long.MIN_VALUE;
    public void setSubscriptionExpirationTimeInMs(long subscriptionExpirationTimeInMs) {
        mSubscriptionExpirationTimeInMs = subscriptionExpirationTimeInMs;
    }
    public long getSubscriptionExpirationTimeInMs() {
        return mSubscriptionExpirationTimeInMs;
    }

    /**
     * The type of the subscription.  This is defined by the provider and the value is provider
     * specific.
     */
    private String mSubscriptionType = null;
    public void setSubscriptionType(String subscriptionType) {
        mSubscriptionType = subscriptionType;
    }
    public String getSubscriptionType() {
        return mSubscriptionType;
    }

    /**
     * The time period for usage statistics accumulation. A value of zero means that usage
     * statistics are not accumulated on a periodic basis (e.g., a one-time limit for
     * “pay as you go” - PAYG service). A non-zero value specifies the usage interval in minutes.
     */
    private long mUsageLimitUsageTimePeriodInMinutes = Long.MIN_VALUE;
    public void setUsageLimitUsageTimePeriodInMinutes(long usageLimitUsageTimePeriodInMinutes) {
        mUsageLimitUsageTimePeriodInMinutes = usageLimitUsageTimePeriodInMinutes;
    }
    public long getUsageLimitUsageTimePeriodInMinutes() {
        return mUsageLimitUsageTimePeriodInMinutes;
    }

    /**
     * The time at which usage statistic accumulation  begins.  It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    private long mUsageLimitStartTimeInMs = Long.MIN_VALUE;
    public void setUsageLimitStartTimeInMs(long usageLimitStartTimeInMs) {
        mUsageLimitStartTimeInMs = usageLimitStartTimeInMs;
    }
    public long getUsageLimitStartTimeInMs() {
        return mUsageLimitStartTimeInMs;
    }

    /**
     * The cumulative data limit in megabytes for the {@link #usageLimitUsageTimePeriodInMinutes}.
     * A value of zero indicate unlimited data usage.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    private long mUsageLimitDataLimit = Long.MIN_VALUE;
    public void setUsageLimitDataLimit(long usageLimitDataLimit) {
        mUsageLimitDataLimit = usageLimitDataLimit;
    }
    public long getUsageLimitDataLimit() {
        return mUsageLimitDataLimit;
    }

    /**
     * The cumulative time limit in minutes for the {@link #usageLimitUsageTimePeriodInMinutes}.
     * A value of zero indicate unlimited time usage.
     */
    private long mUsageLimitTimeLimitInMinutes = Long.MIN_VALUE;
    public void setUsageLimitTimeLimitInMinutes(long usageLimitTimeLimitInMinutes) {
        mUsageLimitTimeLimitInMinutes = usageLimitTimeLimitInMinutes;
    }
    public long getUsageLimitTimeLimitInMinutes() {
        return mUsageLimitTimeLimitInMinutes;
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
            mHomeSp = new HomeSP(source.mHomeSp);
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
        mSubscriptionCreationTimeInMs = source.mSubscriptionCreationTimeInMs;
        mSubscriptionExpirationTimeInMs = source.mSubscriptionExpirationTimeInMs;
        mSubscriptionType = source.mSubscriptionType;
        mUsageLimitDataLimit = source.mUsageLimitDataLimit;
        mUsageLimitStartTimeInMs = source.mUsageLimitStartTimeInMs;
        mUsageLimitTimeLimitInMinutes = source.mUsageLimitTimeLimitInMinutes;
        mUsageLimitUsageTimePeriodInMinutes = source.mUsageLimitUsageTimePeriodInMinutes;
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
        dest.writeLong(mSubscriptionCreationTimeInMs);
        dest.writeLong(mSubscriptionExpirationTimeInMs);
        dest.writeString(mSubscriptionType);
        dest.writeLong(mUsageLimitUsageTimePeriodInMinutes);
        dest.writeLong(mUsageLimitStartTimeInMs);
        dest.writeLong(mUsageLimitDataLimit);
        dest.writeLong(mUsageLimitTimeLimitInMinutes);
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
                && (mCredential == null ? that.mCredential == null
                        : mCredential.equals(that.mCredential))
                && (mPolicy == null ? that.mPolicy == null : mPolicy.equals(that.mPolicy))
                && (mSubscriptionUpdate == null ? that.mSubscriptionUpdate == null
                        : mSubscriptionUpdate.equals(that.mSubscriptionUpdate))
                && isTrustRootCertListEquals(mTrustRootCertList, that.mTrustRootCertList)
                && mUpdateIdentifier == that.mUpdateIdentifier
                && mCredentialPriority == that.mCredentialPriority
                && mSubscriptionCreationTimeInMs == that.mSubscriptionCreationTimeInMs
                && mSubscriptionExpirationTimeInMs == that.mSubscriptionExpirationTimeInMs
                && TextUtils.equals(mSubscriptionType, that.mSubscriptionType)
                && mUsageLimitUsageTimePeriodInMinutes == that.mUsageLimitUsageTimePeriodInMinutes
                && mUsageLimitStartTimeInMs == that.mUsageLimitStartTimeInMs
                && mUsageLimitDataLimit == that.mUsageLimitDataLimit
                && mUsageLimitTimeLimitInMinutes == that.mUsageLimitTimeLimitInMinutes;
    }

    /**
     * Validate the configuration data.
     *
     * @return true on success or false on failure
     */
    public boolean validate() {
        if (mHomeSp == null || !mHomeSp.validate()) {
            return false;
        }
        if (mCredential == null || !mCredential.validate()) {
            return false;
        }
        if (mPolicy != null && !mPolicy.validate()) {
            return false;
        }
        if (mSubscriptionUpdate != null && !mSubscriptionUpdate.validate()) {
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

    public static final Creator<PasspointConfiguration> CREATOR =
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
                config.setSubscriptionCreationTimeInMs(in.readLong());
                config.setSubscriptionExpirationTimeInMs(in.readLong());
                config.setSubscriptionType(in.readString());
                config.setUsageLimitUsageTimePeriodInMinutes(in.readLong());
                config.setUsageLimitStartTimeInMs(in.readLong());
                config.setUsageLimitDataLimit(in.readLong());
                config.setUsageLimitTimeLimitInMinutes(in.readLong());
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
}
