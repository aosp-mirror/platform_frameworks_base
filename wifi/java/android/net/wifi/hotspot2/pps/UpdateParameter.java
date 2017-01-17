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

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
    public long updateIntervalInMinutes = Long.MIN_VALUE;

    /**
     * The method used to update the policy.  Permitted values are "OMA-DM-ClientInitiated"
     * and "SPP-ClientInitiated".
     */
    public String updateMethod = null;

    /**
     * This specifies the hotspots at which the subscription update is permitted.  Permitted
     * values are "HomeSP", "RoamingPartner", or "Unrestricted";
     */
    public String restriction = null;

    /**
     * The URI of the update server.
     */
    public String serverUri = null;

    /**
     * Username used to authenticate with the policy server.
     */
    public String username = null;

    /**
     * Base64 encoded password used to authenticate with the policy server.
     */
    public String base64EncodedPassword = null;

    /**
     * HTTPS URL for retrieving certificate for trust root.  The trust root is used to validate
     * policy server's identity.
     */
    public String trustRootCertUrl = null;

    /**
     * SHA-256 fingerprint of the certificate located at {@link #trustRootCertUrl}
     */
    public byte[] trustRootCertSha256Fingerprint = null;

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
        updateIntervalInMinutes = source.updateIntervalInMinutes;
        updateMethod = source.updateMethod;
        restriction = source.restriction;
        serverUri = source.serverUri;
        username = source.username;
        base64EncodedPassword = source.base64EncodedPassword;
        trustRootCertUrl = source.trustRootCertUrl;
        if (source.trustRootCertSha256Fingerprint != null) {
            trustRootCertSha256Fingerprint = Arrays.copyOf(source.trustRootCertSha256Fingerprint,
                    source.trustRootCertSha256Fingerprint.length);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(updateIntervalInMinutes);
        dest.writeString(updateMethod);
        dest.writeString(restriction);
        dest.writeString(serverUri);
        dest.writeString(username);
        dest.writeString(base64EncodedPassword);
        dest.writeString(trustRootCertUrl);
        dest.writeByteArray(trustRootCertSha256Fingerprint);
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

        return updateIntervalInMinutes == that.updateIntervalInMinutes
                && TextUtils.equals(updateMethod, that.updateMethod)
                && TextUtils.equals(restriction, that.restriction)
                && TextUtils.equals(serverUri, that.serverUri)
                && TextUtils.equals(username, that.username)
                && TextUtils.equals(base64EncodedPassword, that.base64EncodedPassword)
                && TextUtils.equals(trustRootCertUrl, that.trustRootCertUrl)
                && Arrays.equals(trustRootCertSha256Fingerprint,
                        that.trustRootCertSha256Fingerprint);
    }

    /**
     * Validate UpdateParameter data.
     *
     * @return true on success
     */
    public boolean validate() {
        if (updateIntervalInMinutes == Long.MIN_VALUE) {
            Log.d(TAG, "Update interval not specified");
            return false;
        }
        // Update not applicable.
        if (updateIntervalInMinutes == UPDATE_CHECK_INTERVAL_NEVER) {
            return true;
        }

        if (!TextUtils.equals(updateMethod, UPDATE_METHOD_OMADM)
                && !TextUtils.equals(updateMethod, UPDATE_METHOD_SSP)) {
            Log.d(TAG, "Unknown update method: " + updateMethod);
            return false;
        }

        if (!TextUtils.equals(restriction, UPDATE_RESTRICTION_HOMESP)
                && !TextUtils.equals(restriction, UPDATE_RESTRICTION_ROAMING_PARTNER)
                && !TextUtils.equals(restriction, UPDATE_RESTRICTION_UNRESTRICTED)) {
            Log.d(TAG, "Unknown restriction: " + restriction);
            return false;
        }

        if (TextUtils.isEmpty(serverUri)) {
            Log.d(TAG, "Missing update server URI");
            return false;
        }
        if (serverUri.getBytes(StandardCharsets.UTF_8).length > MAX_URI_BYTES) {
            Log.d(TAG, "URI bytes exceeded the max: "
                    + serverUri.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }

        if (TextUtils.isEmpty(username)) {
            Log.d(TAG, "Missing username");
            return false;
        }
        if (username.getBytes(StandardCharsets.UTF_8).length > MAX_USERNAME_BYTES) {
            Log.d(TAG, "Username bytes exceeded the max: "
                    + username.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }

        if (TextUtils.isEmpty(base64EncodedPassword)) {
            Log.d(TAG, "Missing username");
            return false;
        }
        if (base64EncodedPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            Log.d(TAG, "Password bytes exceeded the max: "
                    + base64EncodedPassword.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }
        try {
            Base64.decode(base64EncodedPassword, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "Invalid encoding for password: " + base64EncodedPassword);
            return false;
        }

        if (TextUtils.isEmpty(trustRootCertUrl)) {
            Log.d(TAG, "Missing trust root certificate URL");
            return false;
        }
        if (trustRootCertUrl.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES) {
            Log.d(TAG, "Trust root cert URL bytes exceeded the max: "
                    + trustRootCertUrl.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }

        if (trustRootCertSha256Fingerprint == null) {
            Log.d(TAG, "Missing trust root certificate SHA-256 fingerprint");
            return false;
        }
        if (trustRootCertSha256Fingerprint.length != CERTIFICATE_SHA256_BYTES) {
            Log.d(TAG, "Incorrect size of trust root certificate SHA-256 fingerprint: "
                    + trustRootCertSha256Fingerprint.length);
            return false;
        }
        return true;
    }

    public static final Creator<UpdateParameter> CREATOR =
        new Creator<UpdateParameter>() {
            @Override
            public UpdateParameter createFromParcel(Parcel in) {
                UpdateParameter updateParam = new UpdateParameter();
                updateParam.updateIntervalInMinutes = in.readLong();
                updateParam.updateMethod = in.readString();
                updateParam.restriction = in.readString();
                updateParam.serverUri = in.readString();
                updateParam.username = in.readString();
                updateParam.base64EncodedPassword = in.readString();
                updateParam.trustRootCertUrl = in.readString();
                updateParam.trustRootCertSha256Fingerprint = in.createByteArray();
                return updateParam;
            }

            @Override
            public UpdateParameter[] newArray(int size) {
                return new UpdateParameter[size];
            }
        };
}
