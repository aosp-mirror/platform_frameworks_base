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

    public HomeSP homeSp = null;
    public Credential credential = null;
    public Policy policy = null;

    /**
     * Meta data for performing subscription update.
     */
    public UpdateParameter subscriptionUpdate = null;

    /**
     * List of HTTPS URL for retrieving trust root certificate and the corresponding SHA-256
     * fingerprint of the certificate.  The certificates are used for verifying AAA server's
     * identity during EAP authentication.
     */
    public Map<String, byte[]> trustRootCertList = null;

    /**
     * Set by the subscription server, updated every time the configuration is updated by
     * the subscription server.
     *
     * Use Integer.MIN_VALUE to indicate unset value.
     */
    public int updateIdentifier = Integer.MIN_VALUE;

    /**
     * The priority of the credential.
     *
     * Use Integer.MIN_VALUE to indicate unset value.
     */
    public int credentialPriority = Integer.MIN_VALUE;

    /**
     * The time this subscription is created. It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    public long subscriptionCreationTimeInMs = Long.MIN_VALUE;

    /**
     * The time this subscription will expire. It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    public long subscriptionExpirationTimeInMs = Long.MIN_VALUE;

    /**
     * The type of the subscription.  This is defined by the provider and the value is provider
     * specific.
     */
    public String subscriptionType = null;

    /**
     * The time period for usage statistics accumulation. A value of zero means that usage
     * statistics are not accumulated on a periodic basis (e.g., a one-time limit for
     * “pay as you go” - PAYG service). A non-zero value specifies the usage interval in minutes.
     */
    public long usageLimitUsageTimePeriodInMinutes = Long.MIN_VALUE;

    /**
     * The time at which usage statistic accumulation  begins.  It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    public long usageLimitStartTimeInMs = Long.MIN_VALUE;

    /**
     * The cumulative data limit in megabytes for the {@link #usageLimitUsageTimePeriodInMinutes}.
     * A value of zero indicate unlimited data usage.
     *
     * Use Long.MIN_VALUE to indicate unset value.
     */
    public long usageLimitDataLimit = Long.MIN_VALUE;

    /**
     * The cumulative time limit in minutes for the {@link #usageLimitUsageTimePeriodInMinutes}.
     * A value of zero indicate unlimited time usage.
     */
    public long usageLimitTimeLimitInMinutes = Long.MIN_VALUE;


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

        if (source.homeSp != null) {
            homeSp = new HomeSP(source.homeSp);
        }
        if (source.credential != null) {
            credential = new Credential(source.credential);
        }
        if (source.policy != null) {
            policy = new Policy(source.policy);
        }
        if (source.trustRootCertList != null) {
            trustRootCertList = Collections.unmodifiableMap(source.trustRootCertList);
        }
        if (source.subscriptionUpdate != null) {
            subscriptionUpdate = new UpdateParameter(source.subscriptionUpdate);
        }
        updateIdentifier = source.updateIdentifier;
        credentialPriority = source.credentialPriority;
        subscriptionCreationTimeInMs = source.subscriptionCreationTimeInMs;
        subscriptionExpirationTimeInMs = source.subscriptionExpirationTimeInMs;
        subscriptionType = source.subscriptionType;
        usageLimitDataLimit = source.usageLimitDataLimit;
        usageLimitStartTimeInMs = source.usageLimitStartTimeInMs;
        usageLimitTimeLimitInMinutes = source.usageLimitTimeLimitInMinutes;
        usageLimitUsageTimePeriodInMinutes = source.usageLimitUsageTimePeriodInMinutes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(homeSp, flags);
        dest.writeParcelable(credential, flags);
        dest.writeParcelable(policy, flags);
        dest.writeParcelable(subscriptionUpdate, flags);
        writeTrustRootCerts(dest, trustRootCertList);
        dest.writeInt(updateIdentifier);
        dest.writeInt(credentialPriority);
        dest.writeLong(subscriptionCreationTimeInMs);
        dest.writeLong(subscriptionExpirationTimeInMs);
        dest.writeString(subscriptionType);
        dest.writeLong(usageLimitUsageTimePeriodInMinutes);
        dest.writeLong(usageLimitStartTimeInMs);
        dest.writeLong(usageLimitDataLimit);
        dest.writeLong(usageLimitTimeLimitInMinutes);
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
        return (homeSp == null ? that.homeSp == null : homeSp.equals(that.homeSp))
                && (credential == null ? that.credential == null
                        : credential.equals(that.credential))
                && (policy == null ? that.policy == null : policy.equals(that.policy))
                && (subscriptionUpdate == null ? that.subscriptionUpdate == null
                        : subscriptionUpdate.equals(that.subscriptionUpdate))
                && isTrustRootCertListEquals(trustRootCertList, that.trustRootCertList)
                && updateIdentifier == that.updateIdentifier
                && credentialPriority == that.credentialPriority
                && subscriptionCreationTimeInMs == that.subscriptionCreationTimeInMs
                && subscriptionExpirationTimeInMs == that.subscriptionExpirationTimeInMs
                && TextUtils.equals(subscriptionType, that.subscriptionType)
                && usageLimitUsageTimePeriodInMinutes == that.usageLimitUsageTimePeriodInMinutes
                && usageLimitStartTimeInMs == that.usageLimitStartTimeInMs
                && usageLimitDataLimit == that.usageLimitDataLimit
                && usageLimitTimeLimitInMinutes == that .usageLimitTimeLimitInMinutes;
    }

    /**
     * Validate the configuration data.
     *
     * @return true on success or false on failure
     */
    public boolean validate() {
        if (homeSp == null || !homeSp.validate()) {
            return false;
        }
        if (credential == null || !credential.validate()) {
            return false;
        }
        if (policy != null && !policy.validate()) {
            return false;
        }
        if (subscriptionUpdate != null && !subscriptionUpdate.validate()) {
            return false;
        }
        if (trustRootCertList != null) {
            for (Map.Entry<String, byte[]> entry : trustRootCertList.entrySet()) {
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
                config.homeSp = in.readParcelable(null);
                config.credential = in.readParcelable(null);
                config.policy = in.readParcelable(null);
                config.subscriptionUpdate = in.readParcelable(null);
                config.trustRootCertList = readTrustRootCerts(in);
                config.updateIdentifier = in.readInt();
                config.credentialPriority = in.readInt();
                config.subscriptionCreationTimeInMs = in.readLong();
                config.subscriptionExpirationTimeInMs = in.readLong();
                config.subscriptionType = in.readString();
                config.usageLimitUsageTimePeriodInMinutes = in.readLong();
                config.usageLimitStartTimeInMs = in.readLong();
                config.usageLimitDataLimit = in.readLong();
                config.usageLimitTimeLimitInMinutes = in.readLong();
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
