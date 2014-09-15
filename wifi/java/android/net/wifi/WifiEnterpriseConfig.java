/*
 * Copyright (C) 2013 The Android Open Source Project
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
package android.net.wifi;

import android.os.Parcel;
import android.os.Parcelable;
import android.security.Credentials;
import android.text.TextUtils;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;

/**
 * Enterprise configuration details for Wi-Fi. Stores details about the EAP method
 * and any associated credentials.
 */
public class WifiEnterpriseConfig implements Parcelable {

    /** @hide */
    public static final String EMPTY_VALUE         = "NULL";
    /** @hide */
    public static final String EAP_KEY             = "eap";
    /** @hide */
    public static final String PHASE2_KEY          = "phase2";
    /** @hide */
    public static final String IDENTITY_KEY        = "identity";
    /** @hide */
    public static final String ANON_IDENTITY_KEY   = "anonymous_identity";
    /** @hide */
    public static final String PASSWORD_KEY        = "password";
    /** @hide */
    public static final String SUBJECT_MATCH_KEY   = "subject_match";
    /** @hide */
    public static final String OPP_KEY_CACHING     = "proactive_key_caching";
    /**
     * String representing the keystore OpenSSL ENGINE's ID.
     * @hide
     */
    public static final String ENGINE_ID_KEYSTORE = "keystore";

    /**
     * String representing the keystore URI used for wpa_supplicant.
     * @hide
     */
    public static final String KEYSTORE_URI = "keystore://";

    /**
     * String to set the engine value to when it should be enabled.
     * @hide
     */
    public static final String ENGINE_ENABLE = "1";

    /**
     * String to set the engine value to when it should be disabled.
     * @hide
     */
    public static final String ENGINE_DISABLE = "0";

    /** @hide */
    public static final String CA_CERT_PREFIX = KEYSTORE_URI + Credentials.CA_CERTIFICATE;
    /** @hide */
    public static final String CLIENT_CERT_PREFIX = KEYSTORE_URI + Credentials.USER_CERTIFICATE;
    /** @hide */
    public static final String CLIENT_CERT_KEY     = "client_cert";
    /** @hide */
    public static final String CA_CERT_KEY         = "ca_cert";
    /** @hide */
    public static final String ENGINE_KEY          = "engine";
    /** @hide */
    public static final String ENGINE_ID_KEY       = "engine_id";
    /** @hide */
    public static final String PRIVATE_KEY_ID_KEY  = "key_id";

    private HashMap<String, String> mFields = new HashMap<String, String>();
    private X509Certificate mCaCert;
    private PrivateKey mClientPrivateKey;
    private X509Certificate mClientCertificate;

    public WifiEnterpriseConfig() {
        // Do not set defaults so that the enterprise fields that are not changed
        // by API are not changed underneath
        // This is essential because an app may not have all fields like password
        // available. It allows modification of subset of fields.

    }

    /** Copy constructor */
    public WifiEnterpriseConfig(WifiEnterpriseConfig source) {
        for (String key : source.mFields.keySet()) {
            mFields.put(key, source.mFields.get(key));
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mFields.size());
        for (Map.Entry<String, String> entry : mFields.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeString(entry.getValue());
        }

        writeCertificate(dest, mCaCert);

        if (mClientPrivateKey != null) {
            String algorithm = mClientPrivateKey.getAlgorithm();
            byte[] userKeyBytes = mClientPrivateKey.getEncoded();
            dest.writeInt(userKeyBytes.length);
            dest.writeByteArray(userKeyBytes);
            dest.writeString(algorithm);
        } else {
            dest.writeInt(0);
        }

        writeCertificate(dest, mClientCertificate);
    }

    private void writeCertificate(Parcel dest, X509Certificate cert) {
        if (cert != null) {
            try {
                byte[] certBytes = cert.getEncoded();
                dest.writeInt(certBytes.length);
                dest.writeByteArray(certBytes);
            } catch (CertificateEncodingException e) {
                dest.writeInt(0);
            }
        } else {
            dest.writeInt(0);
        }
    }

    public static final Creator<WifiEnterpriseConfig> CREATOR =
            new Creator<WifiEnterpriseConfig>() {
                public WifiEnterpriseConfig createFromParcel(Parcel in) {
                    WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
                    int count = in.readInt();
                    for (int i = 0; i < count; i++) {
                        String key = in.readString();
                        String value = in.readString();
                        enterpriseConfig.mFields.put(key, value);
                    }

                    enterpriseConfig.mCaCert = readCertificate(in);

                    PrivateKey userKey = null;
                    int len = in.readInt();
                    if (len > 0) {
                        try {
                            byte[] bytes = new byte[len];
                            in.readByteArray(bytes);
                            String algorithm = in.readString();
                            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
                            userKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
                        } catch (NoSuchAlgorithmException e) {
                            userKey = null;
                        } catch (InvalidKeySpecException e) {
                            userKey = null;
                        }
                    }

                    enterpriseConfig.mClientPrivateKey = userKey;
                    enterpriseConfig.mClientCertificate = readCertificate(in);
                    return enterpriseConfig;
                }

                private X509Certificate readCertificate(Parcel in) {
                    X509Certificate cert = null;
                    int len = in.readInt();
                    if (len > 0) {
                        try {
                            byte[] bytes = new byte[len];
                            in.readByteArray(bytes);
                            CertificateFactory cFactory = CertificateFactory.getInstance("X.509");
                            cert = (X509Certificate) cFactory
                                    .generateCertificate(new ByteArrayInputStream(bytes));
                        } catch (CertificateException e) {
                            cert = null;
                        }
                    }
                    return cert;
                }

                public WifiEnterpriseConfig[] newArray(int size) {
                    return new WifiEnterpriseConfig[size];
                }
            };

    /** The Extensible Authentication Protocol method used */
    public static final class Eap {
        /** No EAP method used. Represents an empty config */
        public static final int NONE    = -1;
        /** Protected EAP */
        public static final int PEAP    = 0;
        /** EAP-Transport Layer Security */
        public static final int TLS     = 1;
        /** EAP-Tunneled Transport Layer Security */
        public static final int TTLS    = 2;
        /** EAP-Password */
        public static final int PWD     = 3;
        /** EAP-Subscriber Identity Module */
        public static final int SIM     = 4;
        /** EAP-Authentication and Key Agreement */
        public static final int AKA     = 5;
        /** @hide */
        public static final String[] strings = { "PEAP", "TLS", "TTLS", "PWD", "SIM", "AKA" };

        /** Prevent initialization */
        private Eap() {}
    }

    /** The inner authentication method used */
    public static final class Phase2 {
        public static final int NONE        = 0;
        /** Password Authentication Protocol */
        public static final int PAP         = 1;
        /** Microsoft Challenge Handshake Authentication Protocol */
        public static final int MSCHAP      = 2;
        /** Microsoft Challenge Handshake Authentication Protocol v2 */
        public static final int MSCHAPV2    = 3;
        /** Generic Token Card */
        public static final int GTC         = 4;
        private static final String PREFIX = "auth=";
        /** @hide */
        public static final String[] strings = {EMPTY_VALUE, "PAP", "MSCHAP",
                "MSCHAPV2", "GTC" };

        /** Prevent initialization */
        private Phase2() {}
    }

    /** Internal use only
     * @hide
     */
    public HashMap<String, String> getFields() {
        return mFields;
    }

    /**
     * Set the EAP authentication method.
     * @param  eapMethod is one {@link Eap#PEAP}, {@link Eap#TLS}, {@link Eap#TTLS} or
     *                   {@link Eap#PWD}
     * @throws IllegalArgumentException on an invalid eap method
     */
    public void setEapMethod(int eapMethod) {
        switch (eapMethod) {
            /** Valid methods */
            case Eap.TLS:
                setPhase2Method(Phase2.NONE);
                /* fall through */
            case Eap.PEAP:
            case Eap.PWD:
            case Eap.TTLS:
            case Eap.SIM:
            case Eap.AKA:
                mFields.put(EAP_KEY, Eap.strings[eapMethod]);
                mFields.put(OPP_KEY_CACHING, "1");
                break;
            default:
                throw new IllegalArgumentException("Unknown EAP method");
        }
    }

    /**
     * Get the eap method.
     * @return eap method configured
     */
    public int getEapMethod() {
        String eapMethod  = mFields.get(EAP_KEY);
        return getStringIndex(Eap.strings, eapMethod, Eap.NONE);
    }

    /**
     * Set Phase 2 authentication method. Sets the inner authentication method to be used in
     * phase 2 after setting up a secure channel
     * @param phase2Method is the inner authentication method and can be one of {@link Phase2#NONE},
     *                     {@link Phase2#PAP}, {@link Phase2#MSCHAP}, {@link Phase2#MSCHAPV2},
     *                     {@link Phase2#GTC}
     * @throws IllegalArgumentException on an invalid phase2 method
     *
     */
    public void setPhase2Method(int phase2Method) {
        switch (phase2Method) {
            case Phase2.NONE:
                mFields.put(PHASE2_KEY, EMPTY_VALUE);
                break;
            /** Valid methods */
            case Phase2.PAP:
            case Phase2.MSCHAP:
            case Phase2.MSCHAPV2:
            case Phase2.GTC:
                mFields.put(PHASE2_KEY, convertToQuotedString(
                        Phase2.PREFIX + Phase2.strings[phase2Method]));
                break;
            default:
                throw new IllegalArgumentException("Unknown Phase 2 method");
        }
    }

    /**
     * Get the phase 2 authentication method.
     * @return a phase 2 method defined at {@link Phase2}
     * */
    public int getPhase2Method() {
        String phase2Method = removeDoubleQuotes(mFields.get(PHASE2_KEY));
        // Remove auth= prefix
        if (phase2Method.startsWith(Phase2.PREFIX)) {
            phase2Method = phase2Method.substring(Phase2.PREFIX.length());
        }
        return getStringIndex(Phase2.strings, phase2Method, Phase2.NONE);
    }

    /**
     * Set the identity
     * @param identity
     */
    public void setIdentity(String identity) {
        setFieldValue(IDENTITY_KEY, identity, "");
    }

    /**
     * Get the identity
     * @return the identity
     */
    public String getIdentity() {
        return getFieldValue(IDENTITY_KEY, "");
    }

    /**
     * Set anonymous identity. This is used as the unencrypted identity with
     * certain EAP types
     * @param anonymousIdentity the anonymous identity
     */
    public void setAnonymousIdentity(String anonymousIdentity) {
        setFieldValue(ANON_IDENTITY_KEY, anonymousIdentity, "");
    }

    /** Get the anonymous identity
     * @return anonymous identity
     */
    public String getAnonymousIdentity() {
        return getFieldValue(ANON_IDENTITY_KEY, "");
    }

    /**
     * Set the password.
     * @param password the password
     */
    public void setPassword(String password) {
        setFieldValue(PASSWORD_KEY, password, "");
    }

    /**
     * Get the password.
     *
     * Returns locally set password value. For networks fetched from
     * framework, returns "*".
     */
    public String getPassword() {
        return getFieldValue(PASSWORD_KEY, "");
    }

    /**
     * Set CA certificate alias.
     *
     * <p> See the {@link android.security.KeyChain} for details on installing or choosing
     * a certificate
     * </p>
     * @param alias identifies the certificate
     * @hide
     */
    public void setCaCertificateAlias(String alias) {
        setFieldValue(CA_CERT_KEY, alias, CA_CERT_PREFIX);
    }

    /**
     * Get CA certificate alias
     * @return alias to the CA certificate
     * @hide
     */
    public String getCaCertificateAlias() {
        return getFieldValue(CA_CERT_KEY, CA_CERT_PREFIX);
    }

    /**
     * Specify a X.509 certificate that identifies the server.
     *
     * <p>A default name is automatically assigned to the certificate and used
     * with this configuration. The framework takes care of installing the
     * certificate when the config is saved and removing the certificate when
     * the config is removed.
     *
     * @param cert X.509 CA certificate
     * @throws IllegalArgumentException if not a CA certificate
     */
    public void setCaCertificate(X509Certificate cert) {
        if (cert != null) {
            if (cert.getBasicConstraints() >= 0) {
                mCaCert = cert;
            } else {
                throw new IllegalArgumentException("Not a CA certificate");
            }
        } else {
            mCaCert = null;
        }
    }

    /**
     * Get CA certificate
     * @return X.509 CA certificate
     */
    public X509Certificate getCaCertificate() {
        return mCaCert;
    }

    /**
     * @hide
     */
    public void resetCaCertificate() {
        mCaCert = null;
    }

    /** Set Client certificate alias.
     *
     * <p> See the {@link android.security.KeyChain} for details on installing or choosing
     * a certificate
     * </p>
     * @param alias identifies the certificate
     * @hide
     */
    public void setClientCertificateAlias(String alias) {
        setFieldValue(CLIENT_CERT_KEY, alias, CLIENT_CERT_PREFIX);
        setFieldValue(PRIVATE_KEY_ID_KEY, alias, Credentials.USER_PRIVATE_KEY);
        // Also, set engine parameters
        if (TextUtils.isEmpty(alias)) {
            mFields.put(ENGINE_KEY, ENGINE_DISABLE);
            mFields.put(ENGINE_ID_KEY, EMPTY_VALUE);
        } else {
            mFields.put(ENGINE_KEY, ENGINE_ENABLE);
            mFields.put(ENGINE_ID_KEY, convertToQuotedString(ENGINE_ID_KEYSTORE));
        }
    }

    /**
     * Get client certificate alias
     * @return alias to the client certificate
     * @hide
     */
    public String getClientCertificateAlias() {
        return getFieldValue(CLIENT_CERT_KEY, CLIENT_CERT_PREFIX);
    }

    /**
     * Specify a private key and client certificate for client authorization.
     *
     * <p>A default name is automatically assigned to the key entry and used
     * with this configuration.  The framework takes care of installing the
     * key entry when the config is saved and removing the key entry when
     * the config is removed.

     * @param privateKey
     * @param clientCertificate
     * @throws IllegalArgumentException for an invalid key or certificate.
     */
    public void setClientKeyEntry(PrivateKey privateKey, X509Certificate clientCertificate) {
        if (clientCertificate != null) {
            if (clientCertificate.getBasicConstraints() != -1) {
                throw new IllegalArgumentException("Cannot be a CA certificate");
            }
            if (privateKey == null) {
                throw new IllegalArgumentException("Client cert without a private key");
            }
            if (privateKey.getEncoded() == null) {
                throw new IllegalArgumentException("Private key cannot be encoded");
            }
        }

        mClientPrivateKey = privateKey;
        mClientCertificate = clientCertificate;
    }

    /**
     * Get client certificate
     *
     * @return X.509 client certificate
     */
    public X509Certificate getClientCertificate() {
        return mClientCertificate;
    }

    /**
     * @hide
     */
    public void resetClientKeyEntry() {
        mClientPrivateKey = null;
        mClientCertificate = null;
    }

    /**
     * @hide
     */
    public PrivateKey getClientPrivateKey() {
        return mClientPrivateKey;
    }

    /**
     * Set subject match. This is the substring to be matched against the subject of the
     * authentication server certificate.
     * @param subjectMatch substring to be matched
     */
    public void setSubjectMatch(String subjectMatch) {
        setFieldValue(SUBJECT_MATCH_KEY, subjectMatch, "");
    }

    /**
     * Get subject match
     * @return the subject match string
     */
    public String getSubjectMatch() {
        return getFieldValue(SUBJECT_MATCH_KEY, "");
    }

    /** See {@link WifiConfiguration#getKeyIdForCredentials} @hide */
    String getKeyId(WifiEnterpriseConfig current) {
        String eap = mFields.get(EAP_KEY);
        String phase2 = mFields.get(PHASE2_KEY);

        // If either eap or phase2 are not initialized, use current config details
        if (TextUtils.isEmpty((eap))) {
            eap = current.mFields.get(EAP_KEY);
        }
        if (TextUtils.isEmpty(phase2)) {
            phase2 = current.mFields.get(PHASE2_KEY);
        }
        return eap + "_" + phase2;
    }

    private String removeDoubleQuotes(String string) {
        if (TextUtils.isEmpty(string)) return "";
        int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"')
                && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    private String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    /** Returns the index at which the toBeFound string is found in the array.
     * @param arr array of strings
     * @param toBeFound string to be found
     * @param defaultIndex default index to be returned when string is not found
     * @return the index into array
     */
    private int getStringIndex(String arr[], String toBeFound, int defaultIndex) {
        if (TextUtils.isEmpty(toBeFound)) return defaultIndex;
        for (int i = 0; i < arr.length; i++) {
            if (toBeFound.equals(arr[i])) return i;
        }
        return defaultIndex;
    }

    /** Returns the field value for the key.
     * @param key into the hash
     * @param prefix is the prefix that the value may have
     * @return value
     * @hide
     */
    public String getFieldValue(String key, String prefix) {
        String value = mFields.get(key);
        // Uninitialized or known to be empty after reading from supplicant
        if (TextUtils.isEmpty(value) || EMPTY_VALUE.equals(value)) return "";

        value = removeDoubleQuotes(value);
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length());
        } else {
            return value;
        }
    }

    /** Set a value with an optional prefix at key
     * @param key into the hash
     * @param value to be set
     * @param prefix an optional value to be prefixed to actual value
     * @hide
     */
    public void setFieldValue(String key, String value, String prefix) {
        if (TextUtils.isEmpty(value)) {
            mFields.put(key, EMPTY_VALUE);
        } else {
            mFields.put(key, convertToQuotedString(prefix + value));
        }
    }


    /** Set a value with an optional prefix at key
     * @param key into the hash
     * @param value to be set
     * @param prefix an optional value to be prefixed to actual value
     * @hide
     */
    public void setFieldValue(String key, String value) {
        if (TextUtils.isEmpty(value)) {
           mFields.put(key, EMPTY_VALUE);
        } else {
            mFields.put(key, convertToQuotedString(value));
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        for (String key : mFields.keySet()) {
            sb.append(key).append(" ").append(mFields.get(key)).append("\n");
        }
        return sb.toString();
    }
}
