/**
 * Copyright (c) 2017, The Android Open Source Project
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

package android.net.wifi.hotspot2.pps;

import android.net.wifi.ParcelUtil;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;

/**
 * Class representing configuration parameters for subscription or policy update in
 * PerProviderSubscription (PPS) Management Object (MO) tree.  This is used by both
 * PerProviderSubscription/Policy/PolicyUpdate and PerProviderSubscription/SubscriptionUpdate
 * subtree.
 *
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 *
 * @hide
 */
public final class UpdateParameter implements Parcelable {
    private static final String TAG = "UpdateParameter";

    /**
     * Value indicating policy update is not applicable.  Thus, never check with policy server
     * for updates.
     */
    public static final long UPDATE_CHECK_INTERVAL_NEVER = 0xFFFFFFFFL;

    /**
     * Valid string for UpdateMethod.
     */
    public static final String UPDATE_METHOD_OMADM = "OMA-DM-ClientInitiated";
    public static final String UPDATE_METHOD_SSP = "SSP-ClientInitiated";

    /**
     * Valid string for Restriction.
     */
    public static final String UPDATE_RESTRICTION_HOMESP = "HomeSP";
    public static final String UPDATE_RESTRICTION_ROAMING_PARTNER = "RoamingPartner";
    public static final String UPDATE_RESTRICTION_UNRESTRICTED = "Unrestricted";

    /**
     * Maximum bytes for URI string.
     */
    private static final int MAX_URI_BYTES = 1023;

    /**
     * Maximum bytes for URI string.
     */
    private static final int MAX_URL_BYTES = 1023;

    /**
     * Maximum bytes for username.
     */
    private static final int MAX_USERNAME_BYTES = 63;

    /**
     * Maximum bytes for password.
     */
    private static final int MAX_PASSWORD_BYTES = 255;

    /**
     * Number of bytes for certificate SHA-256 fingerprint byte array.
     */
    private static final int CERTIFICATE_SHA256_BYTES = 32;

    /**
     * This specifies how often the mobile device shall check with policy server for updates.
     *
     * Using Long.MIN_VALUE to indicate unset value.
     */
    private long mUpdateIntervalInMinutes = Long.MIN_VALUE;
    public void setUpdateIntervalInMinutes(long updateIntervalInMinutes) {
        mUpdateIntervalInMinutes = updateIntervalInMinutes;
    }
    public long getUpdateIntervalInMinutes() {
        return mUpdateIntervalInMinutes;
    }

    /**
     * The method used to update the policy.  Permitted values are "OMA-DM-ClientInitiated"
     * and "SPP-ClientInitiated".
     */
    private String mUpdateMethod = null;
    public void setUpdateMethod(String updateMethod) {
        mUpdateMethod = updateMethod;
    }
    public String getUpdateMethod() {
        return mUpdateMethod;
    }

    /**
     * This specifies the hotspots at which the subscription update is permitted.  Permitted
     * values are "HomeSP", "RoamingPartner", or "Unrestricted";
     */
    private String mRestriction = null;
    public void setRestriction(String restriction) {
        mRestriction = restriction;
    }
    public String getRestriction() {
        return mRestriction;
    }

    /**
     * The URI of the update server.
     */
    private String mServerUri = null;
    public void setServerUri(String serverUri) {
        mServerUri = serverUri;
    }
    public String getServerUri() {
        return mServerUri;
    }

    /**
     * Username used to authenticate with the policy server.
     */
    private String mUsername = null;
    public void setUsername(String username) {
        mUsername = username;
    }
    public String getUsername() {
        return mUsername;
    }

    /**
     * Base64 encoded password used to authenticate with the policy server.
     */
    private String mBase64EncodedPassword = null;
    public void setBase64EncodedPassword(String password) {
        mBase64EncodedPassword = password;
    }
    public String getBase64EncodedPassword() {
        return mBase64EncodedPassword;
    }

    /**
     * HTTPS URL for retrieving certificate for trust root.  The trust root is used to validate
     * policy server's identity.
     */
    private String mTrustRootCertUrl = null;
    public void setTrustRootCertUrl(String trustRootCertUrl) {
        mTrustRootCertUrl = trustRootCertUrl;
    }
    public String getTrustRootCertUrl() {
        return mTrustRootCertUrl;
    }

    /**
     * SHA-256 fingerprint of the certificate located at {@code mTrustRootCertUrl}
     */
    private byte[] mTrustRootCertSha256Fingerprint = null;
    public void setTrustRootCertSha256Fingerprint(byte[] fingerprint) {
        mTrustRootCertSha256Fingerprint = fingerprint;
    }
    public byte[] getTrustRootCertSha256Fingerprint() {
        return mTrustRootCertSha256Fingerprint;
    }

    /**
     * CA (Certificate Authority) X509 certificates.
     */
    private X509Certificate mCaCertificate;

    /**
     * Set the CA (Certification Authority) certificate associated with Policy/Subscription update.
     *
     * @param caCertificate The CA certificate to set
     * @hide
     */
    public void setCaCertificate(X509Certificate caCertificate) {
        mCaCertificate = caCertificate;
    }

    /**
     * Get the CA (Certification Authority) certificate associated with Policy/Subscription update.
     *
     * @return CA certificate associated and {@code null} if certificate is not set.
     * @hide
     */
    public X509Certificate getCaCertificate() {
        return mCaCertificate;
    }

    /**
     * Constructor for creating Policy with default values.
     */
    public UpdateParameter() {}

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     */
    public UpdateParameter(UpdateParameter source) {
        if (source == null) {
            return;
        }
        mUpdateIntervalInMinutes = source.mUpdateIntervalInMinutes;
        mUpdateMethod = source.mUpdateMethod;
        mRestriction = source.mRestriction;
        mServerUri = source.mServerUri;
        mUsername = source.mUsername;
        mBase64EncodedPassword = source.mBase64EncodedPassword;
        mTrustRootCertUrl = source.mTrustRootCertUrl;
        if (source.mTrustRootCertSha256Fingerprint != null) {
            mTrustRootCertSha256Fingerprint = Arrays.copyOf(source.mTrustRootCertSha256Fingerprint,
                    source.mTrustRootCertSha256Fingerprint.length);
        }
        mCaCertificate = source.mCaCertificate;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mUpdateIntervalInMinutes);
        dest.writeString(mUpdateMethod);
        dest.writeString(mRestriction);
        dest.writeString(mServerUri);
        dest.writeString(mUsername);
        dest.writeString(mBase64EncodedPassword);
        dest.writeString(mTrustRootCertUrl);
        dest.writeByteArray(mTrustRootCertSha256Fingerprint);
        ParcelUtil.writeCertificate(dest, mCaCertificate);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof UpdateParameter)) {
            return false;
        }
        UpdateParameter that = (UpdateParameter) thatObject;

        return mUpdateIntervalInMinutes == that.mUpdateIntervalInMinutes
                && TextUtils.equals(mUpdateMethod, that.mUpdateMethod)
                && TextUtils.equals(mRestriction, that.mRestriction)
                && TextUtils.equals(mServerUri, that.mServerUri)
                && TextUtils.equals(mUsername, that.mUsername)
                && TextUtils.equals(mBase64EncodedPassword, that.mBase64EncodedPassword)
                && TextUtils.equals(mTrustRootCertUrl, that.mTrustRootCertUrl)
                && Arrays.equals(mTrustRootCertSha256Fingerprint,
                that.mTrustRootCertSha256Fingerprint)
                && Credential.isX509CertificateEquals(mCaCertificate, that.mCaCertificate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUpdateIntervalInMinutes, mUpdateMethod, mRestriction, mServerUri,
                mUsername, mBase64EncodedPassword, mTrustRootCertUrl,
                Arrays.hashCode(mTrustRootCertSha256Fingerprint), mCaCertificate);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("UpdateInterval: ").append(mUpdateIntervalInMinutes).append("\n");
        builder.append("UpdateMethod: ").append(mUpdateMethod).append("\n");
        builder.append("Restriction: ").append(mRestriction).append("\n");
        builder.append("ServerURI: ").append(mServerUri).append("\n");
        builder.append("Username: ").append(mUsername).append("\n");
        builder.append("TrustRootCertURL: ").append(mTrustRootCertUrl).append("\n");
        return builder.toString();
    }

    /**
     * Validate UpdateParameter data.
     *
     * @return true on success
     * @hide
     */
    public boolean validate() {
        if (mUpdateIntervalInMinutes == Long.MIN_VALUE) {
            Log.d(TAG, "Update interval not specified");
            return false;
        }
        // Update not applicable.
        if (mUpdateIntervalInMinutes == UPDATE_CHECK_INTERVAL_NEVER) {
            return true;
        }

        if (!TextUtils.equals(mUpdateMethod, UPDATE_METHOD_OMADM)
                && !TextUtils.equals(mUpdateMethod, UPDATE_METHOD_SSP)) {
            Log.d(TAG, "Unknown update method: " + mUpdateMethod);
            return false;
        }

        if (!TextUtils.equals(mRestriction, UPDATE_RESTRICTION_HOMESP)
                && !TextUtils.equals(mRestriction, UPDATE_RESTRICTION_ROAMING_PARTNER)
                && !TextUtils.equals(mRestriction, UPDATE_RESTRICTION_UNRESTRICTED)) {
            Log.d(TAG, "Unknown restriction: " + mRestriction);
            return false;
        }

        if (TextUtils.isEmpty(mServerUri)) {
            Log.d(TAG, "Missing update server URI");
            return false;
        }
        if (mServerUri.getBytes(StandardCharsets.UTF_8).length > MAX_URI_BYTES) {
            Log.d(TAG, "URI bytes exceeded the max: "
                    + mServerUri.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }

        if (TextUtils.isEmpty(mUsername)) {
            Log.d(TAG, "Missing username");
            return false;
        }
        if (mUsername.getBytes(StandardCharsets.UTF_8).length > MAX_USERNAME_BYTES) {
            Log.d(TAG, "Username bytes exceeded the max: "
                    + mUsername.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }

        if (TextUtils.isEmpty(mBase64EncodedPassword)) {
            Log.d(TAG, "Missing username");
            return false;
        }
        if (mBase64EncodedPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            Log.d(TAG, "Password bytes exceeded the max: "
                    + mBase64EncodedPassword.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }
        try {
            Base64.decode(mBase64EncodedPassword, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "Invalid encoding for password: " + mBase64EncodedPassword);
            return false;
        }

        if (TextUtils.isEmpty(mTrustRootCertUrl)) {
            Log.d(TAG, "Missing trust root certificate URL");
            return false;
        }
        if (mTrustRootCertUrl.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES) {
            Log.d(TAG, "Trust root cert URL bytes exceeded the max: "
                    + mTrustRootCertUrl.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }

        if (mTrustRootCertSha256Fingerprint == null) {
            Log.d(TAG, "Missing trust root certificate SHA-256 fingerprint");
            return false;
        }
        if (mTrustRootCertSha256Fingerprint.length != CERTIFICATE_SHA256_BYTES) {
            Log.d(TAG, "Incorrect size of trust root certificate SHA-256 fingerprint: "
                    + mTrustRootCertSha256Fingerprint.length);
            return false;
        }
        return true;
    }

    public static final @android.annotation.NonNull Creator<UpdateParameter> CREATOR =
        new Creator<UpdateParameter>() {
            @Override
            public UpdateParameter createFromParcel(Parcel in) {
                UpdateParameter updateParam = new UpdateParameter();
                updateParam.setUpdateIntervalInMinutes(in.readLong());
                updateParam.setUpdateMethod(in.readString());
                updateParam.setRestriction(in.readString());
                updateParam.setServerUri(in.readString());
                updateParam.setUsername(in.readString());
                updateParam.setBase64EncodedPassword(in.readString());
                updateParam.setTrustRootCertUrl(in.readString());
                updateParam.setTrustRootCertSha256Fingerprint(in.createByteArray());
                updateParam.setCaCertificate(ParcelUtil.readCertificate(in));
                return updateParam;
            }

            @Override
            public UpdateParameter[] newArray(int size) {
                return new UpdateParameter[size];
            }
        };
}
