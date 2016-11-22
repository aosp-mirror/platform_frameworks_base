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
import android.os.Parcelable;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Class representing Credential subtree in the PerProviderSubscription (PPS)
 * Management Object (MO) tree.
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 *
 * In addition to the fields in the Credential subtree, this will also maintain necessary
 * information for the private key and certificates associated with this credential.
 *
 * Currently we only support the nodes that are used by Hotspot 2.0 Release 1.
 *
 * @hide
 */
public final class Credential implements Parcelable {
    private static final String TAG = "Credential";

    /**
     * Max string length for realm.  Refer to Credential/Realm node in Hotspot 2.0 Release 2
     * Technical Specification Section 9.1 for more info.
     */
    private static final int MAX_REALM_LENGTH = 253;

    /**
     * The realm associated with this credential.  It will be used to determine
     * if this credential can be used to authenticate with a given hotspot by
     * comparing the realm specified in that hotspot's ANQP element.
     */
    public String realm = null;

    /**
     * Username-password based credential.
     * Contains the fields under PerProviderSubscription/Credential/UsernamePassword subtree.
     */
    public static final class UserCredential implements Parcelable {
        /**
         * Maximum string length for username.  Refer to Credential/UsernamePassword/Username
         * node in Hotspot 2.0 Release 2 Technical Specification Section 9.1 for more info.
         */
        private static final int MAX_USERNAME_LENGTH = 63;

        /**
         * Maximum string length for password.  Refer to Credential/UsernamePassword/Password
         * in Hotspot 2.0 Release 2 Technical Specification Section 9.1 for more info.
         */
        private static final int MAX_PASSWORD_LENGTH = 255;

        /**
         * Supported Non-EAP inner methods.  Refer to
         * Credential/UsernamePassword/EAPMethod/InnerEAPType in Hotspot 2.0 Release 2 Technical
         * Specification Section 9.1 for more info.
         */
        private static final Set<String> SUPPORTED_AUTH =
                new HashSet<String>(Arrays.asList("PAP", "CHAP", "MS-CHAP", "MS-CHAP-V2"));

        /**
         * Username of the credential.
         */
        public String username = null;

        /**
         * Base64-encoded password.
         */
        public String password = null;

        /**
         * EAP (Extensible Authentication Protocol) method type.
         * Refer to http://www.iana.org/assignments/eap-numbers/eap-numbers.xml#eap-numbers-4
         * for valid values.
         * Using Integer.MIN_VALUE to indicate unset value.
         */
        public int eapType = Integer.MIN_VALUE;

        /**
         * Non-EAP inner authentication method.
         */
        public String nonEapInnerMethod = null;

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
                username = source.username;
                password = source.password;
                eapType = source.eapType;
                nonEapInnerMethod = source.nonEapInnerMethod;
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(username);
            dest.writeString(password);
            dest.writeInt(eapType);
            dest.writeString(nonEapInnerMethod);
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
            return TextUtils.equals(username, that.username) &&
                    TextUtils.equals(password, that.password) &&
                    eapType == that.eapType &&
                    TextUtils.equals(nonEapInnerMethod, that.nonEapInnerMethod);
        }

        /**
         * Validate the configuration data.
         *
         * @return true on success or false on failure
         */
        public boolean validate() {
            if (TextUtils.isEmpty(username)) {
                Log.d(TAG, "Missing username");
                return false;
            }
            if (username.length() > MAX_USERNAME_LENGTH) {
                Log.d(TAG, "username exceeding maximum length: " + username.length());
                return false;
            }

            if (TextUtils.isEmpty(password)) {
                Log.d(TAG, "Missing password");
                return false;
            }
            if (password.length() > MAX_PASSWORD_LENGTH) {
                Log.d(TAG, "password exceeding maximum length: " + password.length());
                return false;
            }

            // Only supports EAP-TTLS for user credential.
            if (eapType != EAPConstants.EAP_TTLS) {
                Log.d(TAG, "Invalid EAP Type for user credential: " + eapType);
                return false;
            }

            // Verify Non-EAP inner method for EAP-TTLS.
            if (!SUPPORTED_AUTH.contains(nonEapInnerMethod)) {
                Log.d(TAG, "Invalid non-EAP inner method for EAP-TTLS: " + nonEapInnerMethod);
                return false;
            }
            return true;
        }

        public static final Creator<UserCredential> CREATOR =
            new Creator<UserCredential>() {
                @Override
                public UserCredential createFromParcel(Parcel in) {
                    UserCredential userCredential = new UserCredential();
                    userCredential.username = in.readString();
                    userCredential.password = in.readString();
                    userCredential.eapType = in.readInt();
                    userCredential.nonEapInnerMethod = in.readString();
                    return userCredential;
                }

                @Override
                public UserCredential[] newArray(int size) {
                    return new UserCredential[size];
                }
            };
    }
    public UserCredential userCredential = null;

    /**
     * Certificate based credential.  This is used for EAP-TLS.
     * Contains fields under PerProviderSubscription/Credential/DigitalCertificate subtree.
     */
    public static final class CertificateCredential implements Parcelable {
        /**
         * Supported certificate types.
         */
        private static final String CERT_TYPE_X509V3 = "x509v3";

        /**
         * Certificate SHA-256 fingerprint length.
         */
        private static final int CERT_SHA256_FINGER_PRINT_LENGTH = 32;

        /**
         * Certificate type.
         */
        public String certType = null;

        /**
         * The SHA-256 fingerprint of the certificate.
         */
        public byte[] certSha256FingerPrint = null;

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
                certType = source.certType;
                if (source.certSha256FingerPrint != null) {
                    certSha256FingerPrint = Arrays.copyOf(source.certSha256FingerPrint,
                                                          source.certSha256FingerPrint.length);
                }
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(certType);
            dest.writeByteArray(certSha256FingerPrint);
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
            return TextUtils.equals(certType, that.certType) &&
                    Arrays.equals(certSha256FingerPrint, that.certSha256FingerPrint);
        }

        /**
         * Validate the configuration data.
         *
         * @return true on success or false on failure
         */
        public boolean validate() {
            if (!TextUtils.equals(CERT_TYPE_X509V3, certType)) {
                Log.d(TAG, "Unsupported certificate type: " + certType);
                return false;
            }
            if (certSha256FingerPrint == null ||
                    certSha256FingerPrint.length != CERT_SHA256_FINGER_PRINT_LENGTH) {
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
                    certCredential.certType = in.readString();
                    certCredential.certSha256FingerPrint = in.createByteArray();
                    return certCredential;
                }

                @Override
                public CertificateCredential[] newArray(int size) {
                    return new CertificateCredential[size];
                }
            };
    }
    public CertificateCredential certCredential = null;

    /**
     * SIM (Subscriber Identify Module) based credential.
     * Contains fields under PerProviderSubscription/Credential/SIM subtree.
     */
    public static final class SimCredential implements Parcelable {
        /**
         * Maximum string length for IMSI.
         */
        public static final int MAX_IMSI_LENGTH = 15;

        /**
         * International Mobile Subscriber Identity, is used to identify the user
         * of a cellular network and is a unique identification associated with all
         * cellular networks
         */
        public String imsi = null;

        /**
         * EAP (Extensible Authentication Protocol) method type for using SIM credential.
         * Refer to http://www.iana.org/assignments/eap-numbers/eap-numbers.xml#eap-numbers-4
         * for valid values.
         * Using Integer.MIN_VALUE to indicate unset value.
         */
        public int eapType = Integer.MIN_VALUE;

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
                imsi = source.imsi;
                eapType = source.eapType;
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
            return TextUtils.equals(imsi, that.imsi) &&
                    eapType == that.eapType;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(imsi);
            dest.writeInt(eapType);
        }

        /**
         * Validate the configuration data.
         *
         * @return true on success or false on failure
         */
        public boolean validate() {
            // Note: this only validate the format of IMSI string itself.  Additional verification
            // will be done by WifiService at the time of provisioning to verify against the IMSI
            // of the SIM card installed in the device.
            if (!verifyImsi()) {
                return false;
            }
            if (eapType != EAPConstants.EAP_SIM && eapType != EAPConstants.EAP_AKA &&
                    eapType != EAPConstants.EAP_AKA_PRIME) {
                Log.d(TAG, "Invalid EAP Type for SIM credential: " + eapType);
                return false;
            }
            return true;
        }

        public static final Creator<SimCredential> CREATOR =
            new Creator<SimCredential>() {
                @Override
                public SimCredential createFromParcel(Parcel in) {
                    SimCredential simCredential = new SimCredential();
                    simCredential.imsi = in.readString();
                    simCredential.eapType = in.readInt();
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
            if (TextUtils.isEmpty(imsi)) {
                Log.d(TAG, "Missing IMSI");
                return false;
            }
            if (imsi.length() > MAX_IMSI_LENGTH) {
                Log.d(TAG, "IMSI exceeding maximum length: " + imsi.length());
                return false;
            }

            // Locate the first non-digit character.
            int nonDigit;
            char stopChar = '\0';
            for (nonDigit = 0; nonDigit < imsi.length(); nonDigit++) {
                stopChar = imsi.charAt(nonDigit);
                if (stopChar < '0' || stopChar > '9') {
                    break;
                }
            }

            if (nonDigit == imsi.length()) {
                return true;
            }
            else if (nonDigit == imsi.length()-1 && stopChar == '*') {
                // Prefix matching.
                return true;
            }
            return false;
        }
    }
    public SimCredential simCredential = null;

    /**
     * CA (Certificate Authority) X509 certificate.
     */
    public X509Certificate caCertificate = null;

    /**
     * Client side X509 certificate chain.
     */
    public X509Certificate[] clientCertificateChain = null;

    /**
     * Client side private key.
     */
    public PrivateKey clientPrivateKey = null;

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
            realm = source.realm;
            if (source.userCredential != null) {
                userCredential = new UserCredential(source.userCredential);
            }
            if (source.certCredential != null) {
                certCredential = new CertificateCredential(source.certCredential);
            }
            if (source.simCredential != null) {
                simCredential = new SimCredential(source.simCredential);
            }
            if (source.clientCertificateChain != null) {
                clientCertificateChain = Arrays.copyOf(source.clientCertificateChain,
                                                       source.clientCertificateChain.length);
            }
            caCertificate = source.caCertificate;
            clientPrivateKey = source.clientPrivateKey;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(realm);
        dest.writeParcelable(userCredential, flags);
        dest.writeParcelable(certCredential, flags);
        dest.writeParcelable(simCredential, flags);
        ParcelUtil.writeCertificate(dest, caCertificate);
        ParcelUtil.writeCertificates(dest, clientCertificateChain);
        ParcelUtil.writePrivateKey(dest, clientPrivateKey);
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
        return TextUtils.equals(realm, that.realm) &&
                (userCredential == null ? that.userCredential == null :
                    userCredential.equals(that.userCredential)) &&
                (certCredential == null ? that.certCredential == null :
                    certCredential.equals(that.certCredential)) &&
                (simCredential == null ? that.simCredential == null :
                    simCredential.equals(that.simCredential)) &&
                isX509CertificateEquals(caCertificate, that.caCertificate) &&
                isX509CertificatesEquals(clientCertificateChain, that.clientCertificateChain) &&
                isPrivateKeyEquals(clientPrivateKey, that.clientPrivateKey);
    }

    /**
     * Validate the configuration data.
     *
     * @return true on success or false on failure
     */
    public boolean validate() {
        if (TextUtils.isEmpty(realm)) {
            Log.d(TAG, "Missing realm");
            return false;
        }
        if (realm.length() > MAX_REALM_LENGTH) {
            Log.d(TAG, "realm exceeding maximum length: " + realm.length());
            return false;
        }

        // Verify the credential.
        if (userCredential != null) {
            if (!verifyUserCredential()) {
                return false;
            }
        } else if (certCredential != null) {
            if (!verifyCertCredential()) {
                return false;
            }
        } else if (simCredential != null) {
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
                credential.realm = in.readString();
                credential.userCredential = in.readParcelable(null);
                credential.certCredential = in.readParcelable(null);
                credential.simCredential = in.readParcelable(null);
                credential.caCertificate = ParcelUtil.readCertificate(in);
                credential.clientCertificateChain = ParcelUtil.readCertificates(in);
                credential.clientPrivateKey = ParcelUtil.readPrivateKey(in);
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
     * @return true if user credential is valid, false otherwise.
     */
    private boolean verifyUserCredential() {
        if (userCredential == null) {
            Log.d(TAG, "Missing user credential");
            return false;
        }
        if (certCredential != null || simCredential != null) {
            Log.d(TAG, "Contained more than one type of credential");
            return false;
        }
        if (!userCredential.validate()) {
            return false;
        }
        if (caCertificate == null) {
            Log.d(TAG, "Missing CA Certificate for user credential");
            return false;
        }
        return true;
    }

    /**
     * Verify certificate credential, which is used for EAP-TLS.  This will verify
     * that the necessary client key and certificates are provided.
     *
     * @return true if certificate credential is valid, false otherwise.
     */
    private boolean verifyCertCredential() {
        if (certCredential == null) {
            Log.d(TAG, "Missing certificate credential");
            return false;
        }
        if (userCredential != null || simCredential != null) {
            Log.d(TAG, "Contained more than one type of credential");
            return false;
        }

        if (!certCredential.validate()) {
            return false;
        }

        // Verify required key and certificates for certificate credential.
        if (caCertificate == null) {
            Log.d(TAG, "Missing CA Certificate for certificate credential");
            return false;
        }
        if (clientPrivateKey == null) {
            Log.d(TAG, "Missing client private key for certificate credential");
            return false;
        }
        try {
            // Verify SHA-256 fingerprint for client certificate.
            if (!verifySha256Fingerprint(clientCertificateChain,
                    certCredential.certSha256FingerPrint)) {
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
        if (simCredential == null) {
            Log.d(TAG, "Missing SIM credential");
            return false;
        }
        if (userCredential != null || certCredential != null) {
            Log.d(TAG, "Contained more than one type of credential");
            return false;
        }
        return simCredential.validate();
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

    private static boolean isX509CertificateEquals(X509Certificate cert1, X509Certificate cert2) {
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
