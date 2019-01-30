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

package android.net.wifi.hotspot2.pps;

import android.net.wifi.EAPConstants;
import android.net.wifi.ParcelUtil;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Class representing Credential subtree in the PerProviderSubscription (PPS)
 * Management Object (MO) tree.
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 *
 * In addition to the fields in the Credential subtree, this will also maintain necessary
 * information for the private key and certificates associated with this credential.
 */
public final class Credential implements Parcelable {
    private static final String TAG = "Credential";

    /**
     * Max string length for realm.  Refer to Credential/Realm node in Hotspot 2.0 Release 2
     * Technical Specification Section 9.1 for more info.
     */
    private static final int MAX_REALM_BYTES = 253;

    /**
     * The time this credential is created. It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
     * Using Long.MIN_VALUE to indicate unset value.
     */
    private long mCreationTimeInMillis = Long.MIN_VALUE;
    /**
     * @hide
     */
    public void setCreationTimeInMillis(long creationTimeInMillis) {
        mCreationTimeInMillis = creationTimeInMillis;
    }
    /**
     * @hide
     */
    public long getCreationTimeInMillis() {
        return mCreationTimeInMillis;
    }

    /**
     * The time this credential will expire. It is in the format of number
     * of milliseconds since January 1, 1970, 00:00:00 GMT.
    * Using Long.MIN_VALUE to indicate unset value.
     */
    private long mExpirationTimeInMillis = Long.MIN_VALUE;
    /**
     * @hide
     */
    public void setExpirationTimeInMillis(long expirationTimeInMillis) {
        mExpirationTimeInMillis = expirationTimeInMillis;
    }
    /**
     * @hide
     */
    public long getExpirationTimeInMillis() {
        return mExpirationTimeInMillis;
    }

    /**
     * The realm associated with this credential.  It will be used to determine
     * if this credential can be used to authenticate with a given hotspot by
     * comparing the realm specified in that hotspot's ANQP element.
     */
    private String mRealm = null;
    /**
     * Set the realm associated with this credential.
     *
     * @param realm The realm to set to
     */
    public void setRealm(String realm) {
        mRealm = realm;
    }
    /**
     * Get the realm associated with this credential.
     *
     * @return the realm associated with this credential
     */
    public String getRealm() {
        return mRealm;
    }

    /**
     * When set to true, the device should check AAA (Authentication, Authorization,
     * and Accounting) server's certificate during EAP (Extensible Authentication
     * Protocol) authentication.
     */
    private boolean mCheckAaaServerCertStatus = false;
    /**
     * @hide
     */
    public void setCheckAaaServerCertStatus(boolean checkAaaServerCertStatus) {
        mCheckAaaServerCertStatus = checkAaaServerCertStatus;
    }
    /**
     * @hide
     */
    public boolean getCheckAaaServerCertStatus() {
        return mCheckAaaServerCertStatus;
    }

    /**
     * Username-password based credential.
     * Contains the fields under PerProviderSubscription/Credential/UsernamePassword subtree.
     */
    public static final class UserCredential implements Parcelable {
        /**
         * Maximum string length for username.  Refer to Credential/UsernamePassword/Username
         * node in Hotspot 2.0 Release 2 Technical Specification Section 9.1 for more info.
         */
        private static final int MAX_USERNAME_BYTES = 63;

        /**
         * Maximum string length for password.  Refer to Credential/UsernamePassword/Password
         * in Hotspot 2.0 Release 2 Technical Specification Section 9.1 for more info.
         */
        private static final int MAX_PASSWORD_BYTES = 255;

        /**
         * Supported authentication methods.
         * @hide
         */
        public static final String AUTH_METHOD_PAP = "PAP";
        /** @hide */
        public static final String AUTH_METHOD_MSCHAP = "MS-CHAP";
        /** @hide */
        public static final String AUTH_METHOD_MSCHAPV2 = "MS-CHAP-V2";

        /**
         * Supported Non-EAP inner methods.  Refer to
         * Credential/UsernamePassword/EAPMethod/InnerEAPType in Hotspot 2.0 Release 2 Technical
         * Specification Section 9.1 for more info.
         */
        private static final Set<String> SUPPORTED_AUTH = new HashSet<String>(
                Arrays.asList(AUTH_METHOD_PAP, AUTH_METHOD_MSCHAP, AUTH_METHOD_MSCHAPV2));

        /**
         * Username of the credential.
         */
        private String mUsername = null;
        /**
         * Set the username associated with this user credential.
         *
         * @param username The username to set to
         */
        public void setUsername(String username) {
            mUsername = username;
        }
        /**
         * Get the username associated with this user credential.
         *
         * @return the username associated with this user credential
         */
        public String getUsername() {
            return mUsername;
        }

        /**
         * Base64-encoded password.
         */
        private String mPassword = null;
        /**
         * Set the Base64-encoded password associated with this user credential.
         *
         * @param password The password to set to
         */
        public void setPassword(String password) {
            mPassword = password;
        }
        /**
         * Get the Base64-encoded password associated with this user credential.
         *
         * @return the Base64-encoded password associated with this user credential
         */
        public String getPassword() {
            return mPassword;
        }

        /**
         * Flag indicating if the password is machine managed.
         */
        private boolean mMachineManaged = false;
        /**
         * @hide
         */
        public void setMachineManaged(boolean machineManaged) {
            mMachineManaged = machineManaged;
        }
        /**
         * @hide
         */
        public boolean getMachineManaged() {
            return mMachineManaged;
        }

        /**
         * The name of the application used to generate the password.
         */
        private String mSoftTokenApp = null;
        /**
         * @hide
         */
        public void setSoftTokenApp(String softTokenApp) {
            mSoftTokenApp = softTokenApp;
        }
        /**
         * @hide
         */
        public String getSoftTokenApp() {
            return mSoftTokenApp;
        }

        /**
         * Flag indicating if this credential is usable on other mobile devices as well.
         */
        private boolean mAbleToShare = false;
        /**
         * @hide
         */
        public void setAbleToShare(boolean ableToShare) {
            mAbleToShare = ableToShare;
        }
        /**
         * @hide
         */
        public boolean getAbleToShare() {
            return mAbleToShare;
        }

        /**
         * EAP (Extensible Authentication Protocol) method type.
         * Refer to
         * <a href="http://www.iana.org/assignments/eap-numbers/eap-numbers.xml#eap-numbers-4">
         * EAP Numbers</a> for valid values.
         * Using Integer.MIN_VALUE to indicate unset value.
         */
        private int mEapType = Integer.MIN_VALUE;
        /**
         * Set the EAP (Extensible Authentication Protocol) method type associated with this
         * user credential.
         * Refer to
         * <a href="http://www.iana.org/assignments/eap-numbers/eap-numbers.xml#eap-numbers-4">
         * EAP Numbers</a> for valid values.
         *
         * @param eapType The EAP method type associated with this user credential
         */
        public void setEapType(int eapType) {
            mEapType = eapType;
        }
        /**
         * Get the EAP (Extensible Authentication Protocol) method type associated with this
         * user credential.
         *
         * @return EAP method type
         */
        public int getEapType() {
            return mEapType;
        }

        /**
         * Non-EAP inner authentication method.
         */
        private String mNonEapInnerMethod = null;
        /**
         * Set the inner non-EAP method associated with this user credential.
         *
         * @param nonEapInnerMethod The non-EAP inner method to set to
         */
        public void setNonEapInnerMethod(String nonEapInnerMethod) {
            mNonEapInnerMethod = nonEapInnerMethod;
        }
        /**
         * Get the inner non-EAP method associated with this user credential.
         *
         * @return Non-EAP inner method associated with this user credential
         */
        public String getNonEapInnerMethod() {
            return mNonEapInnerMethod;
        }

        /**
         * Constructor for creating UserCredential with default values.
         */
        public UserCredential() {}

        /**
         * Copy constructor.
         *
         * @param source The source to copy from
         */
        public UserCredential(UserCredential source) {
            if (source != null) {
                mUsername = source.mUsername;
                mPassword = source.mPassword;
                mMachineManaged = source.mMachineManaged;
                mSoftTokenApp = source.mSoftTokenApp;
                mAbleToShare = source.mAbleToShare;
                mEapType = source.mEapType;
                mNonEapInnerMethod = source.mNonEapInnerMethod;
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mUsername);
            dest.writeString(mPassword);
            dest.writeInt(mMachineManaged ? 1 : 0);
            dest.writeString(mSoftTokenApp);
            dest.writeInt(mAbleToShare ? 1 : 0);
            dest.writeInt(mEapType);
            dest.writeString(mNonEapInnerMethod);
        }

        @Override
        public boolean equals(Object thatObject) {
            if (this == thatObject) {
                return true;
            }
            if (!(thatObject instanceof UserCredential)) {
                return false;
            }

            UserCredential that = (UserCredential) thatObject;
            return TextUtils.equals(mUsername, that.mUsername)
                    && TextUtils.equals(mPassword, that.mPassword)
                    && mMachineManaged == that.mMachineManaged
                    && TextUtils.equals(mSoftTokenApp, that.mSoftTokenApp)
                    && mAbleToShare == that.mAbleToShare
                    && mEapType == that.mEapType
                    && TextUtils.equals(mNonEapInnerMethod, that.mNonEapInnerMethod);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUsername, mPassword, mMachineManaged, mSoftTokenApp,
                    mAbleToShare, mEapType, mNonEapInnerMethod);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("Username: ").append(mUsername).append("\n");
            builder.append("MachineManaged: ").append(mMachineManaged).append("\n");
            builder.append("SoftTokenApp: ").append(mSoftTokenApp).append("\n");
            builder.append("AbleToShare: ").append(mAbleToShare).append("\n");
            builder.append("EAPType: ").append(mEapType).append("\n");
            builder.append("AuthMethod: ").append(mNonEapInnerMethod).append("\n");
            return builder.toString();
        }

        /**
         * Validate the configuration data.
         *
         * @return true on success or false on failure
         * @hide
         */
        public boolean validate() {
            if (TextUtils.isEmpty(mUsername)) {
                Log.d(TAG, "Missing username");
                return false;
            }
            if (mUsername.getBytes(StandardCharsets.UTF_8).length > MAX_USERNAME_BYTES) {
                Log.d(TAG, "username exceeding maximum length: "
                        + mUsername.getBytes(StandardCharsets.UTF_8).length);
                return false;
            }

            if (TextUtils.isEmpty(mPassword)) {
                Log.d(TAG, "Missing password");
                return false;
            }
            if (mPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
                Log.d(TAG, "password exceeding maximum length: "
                        + mPassword.getBytes(StandardCharsets.UTF_8).length);
                return false;
            }

            // Only supports EAP-TTLS for user credential.
            if (mEapType != EAPConstants.EAP_TTLS) {
                Log.d(TAG, "Invalid EAP Type for user credential: " + mEapType);
                return false;
            }

            // Verify Non-EAP inner method for EAP-TTLS.
            if (!SUPPORTED_AUTH.contains(mNonEapInnerMethod)) {
                Log.d(TAG, "Invalid non-EAP inner method for EAP-TTLS: " + mNonEapInnerMethod);
                return false;
            }
            return true;
        }

        public static final Creator<UserCredential> CREATOR =
            new Creator<UserCredential>() {
                @Override
                public UserCredential createFromParcel(Parcel in) {
                    UserCredential userCredential = new UserCredential();
                    userCredential.setUsername(in.readString());
                    userCredential.setPassword(in.readString());
                    userCredential.setMachineManaged(in.readInt() != 0);
                    userCredential.setSoftTokenApp(in.readString());
                    userCredential.setAbleToShare(in.readInt() != 0);
                    userCredential.setEapType(in.readInt());
                    userCredential.setNonEapInnerMethod(in.readString());
                    return userCredential;
                }

                @Override
                public UserCredential[] newArray(int size) {
                    return new UserCredential[size];
                }
            };
    }
    private UserCredential mUserCredential = null;
    /**
     * Set the user credential information.
     *
     * @param userCredential The user credential to set to
     */
    public void setUserCredential(UserCredential userCredential) {
        mUserCredential = userCredential;
    }
    /**
     * Get the user credential information.
     *
     * @return user credential information
     */
    public UserCredential getUserCredential() {
        return mUserCredential;
    }

    /**
     * Certificate based credential.  This is used for EAP-TLS.
     * Contains fields under PerProviderSubscription/Credential/DigitalCertificate subtree.
     */
    public static final class CertificateCredential implements Parcelable {
        /**
         * Supported certificate types.
         * @hide
         */
        public static final String CERT_TYPE_X509V3 = "x509v3";

        /**
         * Certificate SHA-256 fingerprint length.
         */
        private static final int CERT_SHA256_FINGER_PRINT_LENGTH = 32;

        /**
         * Certificate type.
         */
        private String mCertType = null;
        /**
         * Set the certificate type associated with this certificate credential.
         *
         * @param certType The certificate type to set to
         */
        public void setCertType(String certType) {
            mCertType = certType;
        }
        /**
         * Get the certificate type associated with this certificate credential.
         *
         * @return certificate type
         */
        public String getCertType() {
            return mCertType;
        }

        /**
         * The SHA-256 fingerprint of the certificate.
         */
        private byte[] mCertSha256Fingerprint = null;
        /**
         * Set the certificate SHA-256 fingerprint associated with this certificate credential.
         *
         * @param certSha256Fingerprint The certificate fingerprint to set to
         */
        public void setCertSha256Fingerprint(byte[] certSha256Fingerprint) {
            mCertSha256Fingerprint = certSha256Fingerprint;
        }
        /**
         * Get the certificate SHA-256 fingerprint associated with this certificate credential.
         *
         * @return certificate SHA-256 fingerprint
         */
        public byte[] getCertSha256Fingerprint() {
            return mCertSha256Fingerprint;
        }

        /**
         * Constructor for creating CertificateCredential with default values.
         */
        public CertificateCredential() {}

        /**
         * Copy constructor.
         *
         * @param source The source to copy from
         */
        public CertificateCredential(CertificateCredential source) {
            if (source != null) {
                mCertType = source.mCertType;
                if (source.mCertSha256Fingerprint != null) {
                    mCertSha256Fingerprint = Arrays.copyOf(source.mCertSha256Fingerprint,
                                                          source.mCertSha256Fingerprint.length);
                }
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mCertType);
            dest.writeByteArray(mCertSha256Fingerprint);
        }

        @Override
        public boolean equals(Object thatObject) {
            if (this == thatObject) {
                return true;
            }
            if (!(thatObject instanceof CertificateCredential)) {
                return false;
            }

            CertificateCredential that = (CertificateCredential) thatObject;
            return TextUtils.equals(mCertType, that.mCertType)
                    && Arrays.equals(mCertSha256Fingerprint, that.mCertSha256Fingerprint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mCertType, Arrays.hashCode(mCertSha256Fingerprint));
        }

        @Override
        public String toString() {
            return "CertificateType: " + mCertType + "\n";
        }

        /**
         * Validate the configuration data.
         *
         * @return true on success or false on failure
         * @hide
         */
        public boolean validate() {
            if (!TextUtils.equals(CERT_TYPE_X509V3, mCertType)) {
                Log.d(TAG, "Unsupported certificate type: " + mCertType);
                return false;
            }
            if (mCertSha256Fingerprint == null
                    || mCertSha256Fingerprint.length != CERT_SHA256_FINGER_PRINT_LENGTH) {
                Log.d(TAG, "Invalid SHA-256 fingerprint");
                return false;
            }
            return true;
        }

        public static final Creator<CertificateCredential> CREATOR =
            new Creator<CertificateCredential>() {
                @Override
                public CertificateCredential createFromParcel(Parcel in) {
                    CertificateCredential certCredential = new CertificateCredential();
                    certCredential.setCertType(in.readString());
                    certCredential.setCertSha256Fingerprint(in.createByteArray());
                    return certCredential;
                }

                @Override
                public CertificateCredential[] newArray(int size) {
                    return new CertificateCredential[size];
                }
            };
    }
    private CertificateCredential mCertCredential = null;
    /**
     * Set the certificate credential information.
     *
     * @param certCredential The certificate credential to set to
     */
    public void setCertCredential(CertificateCredential certCredential) {
        mCertCredential = certCredential;
    }
    /**
     * Get the certificate credential information.
     *
     * @return certificate credential information
     */
    public CertificateCredential getCertCredential() {
        return mCertCredential;
    }

    /**
     * SIM (Subscriber Identify Module) based credential.
     * Contains fields under PerProviderSubscription/Credential/SIM subtree.
     */
    public static final class SimCredential implements Parcelable {
        /**
         * Maximum string length for IMSI.
         */
        private static final int MAX_IMSI_LENGTH = 15;

        /**
         * International Mobile Subscriber Identity, is used to identify the user
         * of a cellular network and is a unique identification associated with all
         * cellular networks
         */
        private String mImsi = null;
        /**
         * Set the IMSI (International Mobile Subscriber Identity) associated with this SIM
         * credential.
         *
         * @param imsi The IMSI to set to
         */
        public void setImsi(String imsi) {
            mImsi = imsi;
        }
        /**
         * Get the IMSI (International Mobile Subscriber Identity) associated with this SIM
         * credential.
         *
         * @return IMSI associated with this SIM credential
         */
        public String getImsi() {
            return mImsi;
        }

        /**
         * EAP (Extensible Authentication Protocol) method type for using SIM credential.
         * Refer to http://www.iana.org/assignments/eap-numbers/eap-numbers.xml#eap-numbers-4
         * for valid values.
         * Using Integer.MIN_VALUE to indicate unset value.
         */
        private int mEapType = Integer.MIN_VALUE;
        /**
         * Set the EAP (Extensible Authentication Protocol) method type associated with this
         * SIM credential.
         *
         * @param eapType The EAP method type to set to
         */
        public void setEapType(int eapType) {
            mEapType = eapType;
        }
        /**
         * Get the EAP (Extensible Authentication Protocol) method type associated with this
         * SIM credential.
         *
         * @return EAP method type associated with this SIM credential
         */
        public int getEapType() {
            return mEapType;
        }

        /**
         * Constructor for creating SimCredential with default values.
         */
        public SimCredential() {}

        /**
         * Copy constructor
         *
         * @param source The source to copy from
         */
        public SimCredential(SimCredential source) {
            if (source != null) {
                mImsi = source.mImsi;
                mEapType = source.mEapType;
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public boolean equals(Object thatObject) {
            if (this == thatObject) {
                return true;
            }
            if (!(thatObject instanceof SimCredential)) {
                return false;
            }

            SimCredential that = (SimCredential) thatObject;
            return TextUtils.equals(mImsi, that.mImsi)
                    && mEapType == that.mEapType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mImsi, mEapType);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("IMSI: ").append(mImsi).append("\n");
            builder.append("EAPType: ").append(mEapType).append("\n");
            return builder.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mImsi);
            dest.writeInt(mEapType);
        }

        /**
         * Validate the configuration data.
         *
         * @return true on success or false on failure
         * @hide
         */
        public boolean validate() {
            // Note: this only validate the format of IMSI string itself.  Additional verification
            // will be done by WifiService at the time of provisioning to verify against the IMSI
            // of the SIM card installed in the device.
            if (!verifyImsi()) {
                return false;
            }
            if (mEapType != EAPConstants.EAP_SIM && mEapType != EAPConstants.EAP_AKA
                    && mEapType != EAPConstants.EAP_AKA_PRIME) {
                Log.d(TAG, "Invalid EAP Type for SIM credential: " + mEapType);
                return false;
            }
            return true;
        }

        public static final Creator<SimCredential> CREATOR =
            new Creator<SimCredential>() {
                @Override
                public SimCredential createFromParcel(Parcel in) {
                    SimCredential simCredential = new SimCredential();
                    simCredential.setImsi(in.readString());
                    simCredential.setEapType(in.readInt());
                    return simCredential;
                }

                @Override
                public SimCredential[] newArray(int size) {
                    return new SimCredential[size];
                }
            };

        /**
         * Verify the IMSI (International Mobile Subscriber Identity) string.  The string
         * should contain zero or more numeric digits, and might ends with a "*" for prefix
         * matching.
         *
         * @return true if IMSI is valid, false otherwise.
         */
        private boolean verifyImsi() {
            if (TextUtils.isEmpty(mImsi)) {
                Log.d(TAG, "Missing IMSI");
                return false;
            }
            if (mImsi.length() > MAX_IMSI_LENGTH) {
                Log.d(TAG, "IMSI exceeding maximum length: " + mImsi.length());
                return false;
            }

            // Locate the first non-digit character.
            int nonDigit;
            char stopChar = '\0';
            for (nonDigit = 0; nonDigit < mImsi.length(); nonDigit++) {
                stopChar = mImsi.charAt(nonDigit);
                if (stopChar < '0' || stopChar > '9') {
                    break;
                }
            }

            if (nonDigit == mImsi.length()) {
                return true;
            }
            else if (nonDigit == mImsi.length()-1 && stopChar == '*') {
                // Prefix matching.
                return true;
            }
            return false;
        }
    }
    private SimCredential mSimCredential = null;
    /**
     * Set the SIM credential information.
     *
     * @param simCredential The SIM credential to set to
     */
    public void setSimCredential(SimCredential simCredential) {
        mSimCredential = simCredential;
    }
    /**
     * Get the SIM credential information.
     *
     * @return SIM credential information
     */
    public SimCredential getSimCredential() {
        return mSimCredential;
    }

    /**
     * CA (Certificate Authority) X509 certificates.
     */
    private X509Certificate[] mCaCertificates = null;

    /**
     * Set the CA (Certification Authority) certificate associated with this credential.
     *
     * @param caCertificate The CA certificate to set to
     */
    public void setCaCertificate(X509Certificate caCertificate) {
        mCaCertificates = null;
        if (caCertificate != null) {
            mCaCertificates = new X509Certificate[] {caCertificate};
        }
    }

    /**
     * Set the CA (Certification Authority) certificates associated with this credential.
     *
     * @param caCertificates The list of CA certificates to set to
     * @hide
     */
    public void setCaCertificates(X509Certificate[] caCertificates) {
        mCaCertificates = caCertificates;
    }

    /**
     * Get the CA (Certification Authority) certificate associated with this credential.
     *
     * @return CA certificate associated with this credential, {@code null} if certificate is not
     * set or certificate is more than one.
     */
    public X509Certificate getCaCertificate() {
        return mCaCertificates == null || mCaCertificates.length > 1 ? null : mCaCertificates[0];
    }

    /**
     * Get the CA (Certification Authority) certificates associated with this credential.
     *
     * @return The list of CA certificates associated with this credential
     * @hide
     */
    public X509Certificate[] getCaCertificates() {
        return mCaCertificates;
    }

    /**
     * Client side X509 certificate chain.
     */
    private X509Certificate[] mClientCertificateChain = null;
    /**
     * Set the client certificate chain associated with this credential.
     *
     * @param certificateChain The client certificate chain to set to
     */
    public void setClientCertificateChain(X509Certificate[] certificateChain) {
        mClientCertificateChain = certificateChain;
    }
    /**
     * Get the client certificate chain associated with this credential.
     *
     * @return client certificate chain associated with this credential
     */
    public X509Certificate[] getClientCertificateChain() {
        return mClientCertificateChain;
    }

    /**
     * Client side private key.
     */
    private PrivateKey mClientPrivateKey = null;
    /**
     * Set the client private key associated with this credential.
     *
     * @param clientPrivateKey the client private key to set to
     */
    public void setClientPrivateKey(PrivateKey clientPrivateKey) {
        mClientPrivateKey = clientPrivateKey;
    }
    /**
     * Get the client private key associated with this credential.
     *
     * @return client private key associated with this credential.
     */
    public PrivateKey getClientPrivateKey() {
        return mClientPrivateKey;
    }

    /**
     * Constructor for creating Credential with default values.
     */
    public Credential() {}

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     */
    public Credential(Credential source) {
        if (source != null) {
            mCreationTimeInMillis = source.mCreationTimeInMillis;
            mExpirationTimeInMillis = source.mExpirationTimeInMillis;
            mRealm = source.mRealm;
            mCheckAaaServerCertStatus = source.mCheckAaaServerCertStatus;
            if (source.mUserCredential != null) {
                mUserCredential = new UserCredential(source.mUserCredential);
            }
            if (source.mCertCredential != null) {
                mCertCredential = new CertificateCredential(source.mCertCredential);
            }
            if (source.mSimCredential != null) {
                mSimCredential = new SimCredential(source.mSimCredential);
            }
            if (source.mClientCertificateChain != null) {
                mClientCertificateChain = Arrays.copyOf(source.mClientCertificateChain,
                                                        source.mClientCertificateChain.length);
            }
            if (source.mCaCertificates != null) {
                mCaCertificates = Arrays.copyOf(source.mCaCertificates,
                        source.mCaCertificates.length);
            }

            mClientPrivateKey = source.mClientPrivateKey;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mCreationTimeInMillis);
        dest.writeLong(mExpirationTimeInMillis);
        dest.writeString(mRealm);
        dest.writeInt(mCheckAaaServerCertStatus ? 1 : 0);
        dest.writeParcelable(mUserCredential, flags);
        dest.writeParcelable(mCertCredential, flags);
        dest.writeParcelable(mSimCredential, flags);
        ParcelUtil.writeCertificates(dest, mCaCertificates);
        ParcelUtil.writeCertificates(dest, mClientCertificateChain);
        ParcelUtil.writePrivateKey(dest, mClientPrivateKey);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof Credential)) {
            return false;
        }

        Credential that = (Credential) thatObject;
        return TextUtils.equals(mRealm, that.mRealm)
                && mCreationTimeInMillis == that.mCreationTimeInMillis
                && mExpirationTimeInMillis == that.mExpirationTimeInMillis
                && mCheckAaaServerCertStatus == that.mCheckAaaServerCertStatus
                && (mUserCredential == null ? that.mUserCredential == null
                    : mUserCredential.equals(that.mUserCredential))
                && (mCertCredential == null ? that.mCertCredential == null
                    : mCertCredential.equals(that.mCertCredential))
                && (mSimCredential == null ? that.mSimCredential == null
                    : mSimCredential.equals(that.mSimCredential))
                && isX509CertificatesEquals(mCaCertificates, that.mCaCertificates)
                && isX509CertificatesEquals(mClientCertificateChain, that.mClientCertificateChain)
                && isPrivateKeyEquals(mClientPrivateKey, that.mClientPrivateKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCreationTimeInMillis, mExpirationTimeInMillis, mRealm,
                mCheckAaaServerCertStatus, mUserCredential, mCertCredential, mSimCredential,
                mClientPrivateKey, Arrays.hashCode(mCaCertificates),
                Arrays.hashCode(mClientCertificateChain));
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Realm: ").append(mRealm).append("\n");
        builder.append("CreationTime: ").append(mCreationTimeInMillis != Long.MIN_VALUE
                ? new Date(mCreationTimeInMillis) : "Not specified").append("\n");
        builder.append("ExpirationTime: ").append(mExpirationTimeInMillis != Long.MIN_VALUE
                ? new Date(mExpirationTimeInMillis) : "Not specified").append("\n");
        builder.append("CheckAAAServerStatus: ").append(mCheckAaaServerCertStatus).append("\n");
        if (mUserCredential != null) {
            builder.append("UserCredential Begin ---\n");
            builder.append(mUserCredential);
            builder.append("UserCredential End ---\n");
        }
        if (mCertCredential != null) {
            builder.append("CertificateCredential Begin ---\n");
            builder.append(mCertCredential);
            builder.append("CertificateCredential End ---\n");
        }
        if (mSimCredential != null) {
            builder.append("SIMCredential Begin ---\n");
            builder.append(mSimCredential);
            builder.append("SIMCredential End ---\n");
        }
        return builder.toString();
    }

    /**
     * Validate the configuration data.
     *
     * @param isR1 {@code true} if the configuration is for R1
     * @return true on success or false on failure
     * @hide
     */
    public boolean validate(boolean isR1) {
        if (TextUtils.isEmpty(mRealm)) {
            Log.d(TAG, "Missing realm");
            return false;
        }
        if (mRealm.getBytes(StandardCharsets.UTF_8).length > MAX_REALM_BYTES) {
            Log.d(TAG, "realm exceeding maximum length: "
                    + mRealm.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }

        // Verify the credential.
        if (mUserCredential != null) {
            if (!verifyUserCredential(isR1)) {
                return false;
            }
        } else if (mCertCredential != null) {
            if (!verifyCertCredential(isR1)) {
                return false;
            }
        } else if (mSimCredential != null) {
            if (!verifySimCredential()) {
                return false;
            }
        } else {
            Log.d(TAG, "Missing required credential");
            return false;
        }

        return true;
    }

    public static final Creator<Credential> CREATOR =
        new Creator<Credential>() {
            @Override
            public Credential createFromParcel(Parcel in) {
                Credential credential = new Credential();
                credential.setCreationTimeInMillis(in.readLong());
                credential.setExpirationTimeInMillis(in.readLong());
                credential.setRealm(in.readString());
                credential.setCheckAaaServerCertStatus(in.readInt() != 0);
                credential.setUserCredential(in.readParcelable(null));
                credential.setCertCredential(in.readParcelable(null));
                credential.setSimCredential(in.readParcelable(null));
                credential.setCaCertificates(ParcelUtil.readCertificates(in));
                credential.setClientCertificateChain(ParcelUtil.readCertificates(in));
                credential.setClientPrivateKey(ParcelUtil.readPrivateKey(in));
                return credential;
            }

            @Override
            public Credential[] newArray(int size) {
                return new Credential[size];
            }
        };

    /**
     * Verify user credential.
     *
     * @param isR1 {@code true} if credential is for R1
     * @return true if user credential is valid, false otherwise.
     */
    private boolean verifyUserCredential(boolean isR1) {
        if (mUserCredential == null) {
            Log.d(TAG, "Missing user credential");
            return false;
        }
        if (mCertCredential != null || mSimCredential != null) {
            Log.d(TAG, "Contained more than one type of credential");
            return false;
        }
        if (!mUserCredential.validate()) {
            return false;
        }

        // CA certificate is required for R1 Passpoint profile.
        // For R2, it is downloaded using cert URL provided in PPS MO after validation completes.
        if (isR1 && mCaCertificates == null) {
            Log.d(TAG, "Missing CA Certificate for user credential");
            return false;
        }
        return true;
    }

    /**
     * Verify certificate credential, which is used for EAP-TLS.  This will verify
     * that the necessary client key and certificates are provided.
     *
     * @param isR1 {@code true} if credential is for R1
     * @return true if certificate credential is valid, false otherwise.
     */
    private boolean verifyCertCredential(boolean isR1) {
        if (mCertCredential == null) {
            Log.d(TAG, "Missing certificate credential");
            return false;
        }
        if (mUserCredential != null || mSimCredential != null) {
            Log.d(TAG, "Contained more than one type of credential");
            return false;
        }

        if (!mCertCredential.validate()) {
            return false;
        }

        // Verify required key and certificates for certificate credential.
        // CA certificate is required for R1 Passpoint profile.
        // For R2, it is downloaded using cert URL provided in PPS MO after validation completes.
        if (isR1 && mCaCertificates == null) {
            Log.d(TAG, "Missing CA Certificate for certificate credential");
            return false;
        }
        if (mClientPrivateKey == null) {
            Log.d(TAG, "Missing client private key for certificate credential");
            return false;
        }
        try {
            // Verify SHA-256 fingerprint for client certificate.
            if (!verifySha256Fingerprint(mClientCertificateChain,
                    mCertCredential.getCertSha256Fingerprint())) {
                Log.d(TAG, "SHA-256 fingerprint mismatch");
                return false;
            }
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            Log.d(TAG, "Failed to verify SHA-256 fingerprint: " + e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * Verify SIM credential.
     *
     * @return true if SIM credential is valid, false otherwise.
     */
    private boolean verifySimCredential() {
        if (mSimCredential == null) {
            Log.d(TAG, "Missing SIM credential");
            return false;
        }
        if (mUserCredential != null || mCertCredential != null) {
            Log.d(TAG, "Contained more than one type of credential");
            return false;
        }
        return mSimCredential.validate();
    }

    private static boolean isPrivateKeyEquals(PrivateKey key1, PrivateKey key2) {
        if (key1 == null && key2 == null) {
            return true;
        }

        /* Return false if only one of them is null */
        if (key1 == null || key2 == null) {
            return false;
        }

        return TextUtils.equals(key1.getAlgorithm(), key2.getAlgorithm()) &&
                Arrays.equals(key1.getEncoded(), key2.getEncoded());
    }

    /**
     * Verify two X.509 certificates are identical.
     *
     * @param cert1 a certificate to compare
     * @param cert2 a certificate to compare
     * @return {@code true} if given certificates are the same each other, {@code false} otherwise.
     * @hide
     */
    public static boolean isX509CertificateEquals(X509Certificate cert1, X509Certificate cert2) {
        if (cert1 == null && cert2 == null) {
            return true;
        }

        /* Return false if only one of them is null */
        if (cert1 == null || cert2 == null) {
            return false;
        }

        boolean result = false;
        try {
            result = Arrays.equals(cert1.getEncoded(), cert2.getEncoded());
        } catch (CertificateEncodingException e) {
            /* empty, return false. */
        }
        return result;
    }

    private static boolean isX509CertificatesEquals(X509Certificate[] certs1,
                                                    X509Certificate[] certs2) {
        if (certs1 == null && certs2 == null) {
            return true;
        }

        /* Return false if only one of them is null */
        if (certs1 == null || certs2 == null) {
            return false;
        }

        if (certs1.length != certs2.length) {
            return false;
        }

        for (int i = 0; i < certs1.length; i++) {
            if (!isX509CertificateEquals(certs1[i], certs2[i])) {
                return false;
            }
        }

        return true;
    }

    /**
     * Verify that the digest for a certificate in the certificate chain matches expected
     * fingerprint.  The certificate that matches the fingerprint is the client certificate.
     *
     * @param certChain Chain of certificates
     * @param expectedFingerprint The expected SHA-256 digest of the client certificate
     * @return true if the certificate chain contains a matching certificate, false otherwise
     * @throws NoSuchAlgorithmException
     * @throws CertificateEncodingException
     */
    private static boolean verifySha256Fingerprint(X509Certificate[] certChain,
                                                   byte[] expectedFingerprint)
            throws NoSuchAlgorithmException, CertificateEncodingException {
        if (certChain == null) {
            return false;
        }
        MessageDigest digester = MessageDigest.getInstance("SHA-256");
        for (X509Certificate certificate : certChain) {
            digester.reset();
            byte[] fingerprint = digester.digest(certificate.getEncoded());
            if (Arrays.equals(expectedFingerprint, fingerprint)) {
                return true;
            }
        }
        return false;
    }
}
