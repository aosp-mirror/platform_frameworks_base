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
import android.os.Process;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Slog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
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
    private static final String TAG = "WifiEnterpriseConfig";
    private static final boolean DBG = false;
    /**
     * In old configurations, the "private_key" field was used. However, newer
     * configurations use the key_id field with the engine_id set to "keystore".
     * If this field is found in the configuration, the migration code is
     * triggered.
     */
    private static final String OLD_PRIVATE_KEY_NAME = "private_key";

    /**
     * String representing the keystore OpenSSL ENGINE's ID.
     */
    private static final String ENGINE_ID_KEYSTORE = "keystore";

    /**
     * String representing the keystore URI used for wpa_supplicant.
     */
    private static final String KEYSTORE_URI = "keystore://";

    /**
     * String to set the engine value to when it should be enabled.
     */
    private static final String ENGINE_ENABLE = "1";

    /**
     * String to set the engine value to when it should be disabled.
     */
    private static final String ENGINE_DISABLE = "0";

    private static final String CA_CERT_PREFIX = KEYSTORE_URI + Credentials.CA_CERTIFICATE;
    private static final String CLIENT_CERT_PREFIX = KEYSTORE_URI + Credentials.USER_CERTIFICATE;

    private static final String EAP_KEY             = "eap";
    private static final String PHASE2_KEY          = "phase2";
    private static final String IDENTITY_KEY        = "identity";
    private static final String ANON_IDENTITY_KEY   = "anonymous_identity";
    private static final String PASSWORD_KEY        = "password";
    private static final String CLIENT_CERT_KEY     = "client_cert";
    private static final String CA_CERT_KEY         = "ca_cert";
    private static final String SUBJECT_MATCH_KEY   = "subject_match";
    private static final String ENGINE_KEY          = "engine";
    private static final String ENGINE_ID_KEY       = "engine_id";
    private static final String PRIVATE_KEY_ID_KEY  = "key_id";
    private static final String OPP_KEY_CACHING     = "proactive_key_caching";

    private HashMap<String, String> mFields = new HashMap<String, String>();
    private X509Certificate mCaCert;
    private PrivateKey mClientPrivateKey;
    private X509Certificate mClientCertificate;
    private boolean mNeedsSoftwareKeystore = false;

    /** This represents an empty value of an enterprise field.
     * NULL is used at wpa_supplicant to indicate an empty value
     */
    static final String EMPTY_VALUE = "NULL";

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
        /** @hide */
        public static final String[] strings = { "PEAP", "TLS", "TTLS", "PWD" };

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
        public static final String[] strings = {EMPTY_VALUE, "PAP", "MSCHAP", "MSCHAPV2", "GTC" };

        /** Prevent initialization */
        private Phase2() {}
    }

    /** Internal use only */
    HashMap<String, String> getFields() {
        return mFields;
    }

    /** Internal use only */
    static String[] getSupplicantKeys() {
        return new String[] { EAP_KEY, PHASE2_KEY, IDENTITY_KEY, ANON_IDENTITY_KEY, PASSWORD_KEY,
                CLIENT_CERT_KEY, CA_CERT_KEY, SUBJECT_MATCH_KEY, ENGINE_KEY, ENGINE_ID_KEY,
                PRIVATE_KEY_ID_KEY };
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
            case Eap.PEAP:
            case Eap.PWD:
            case Eap.TLS:
            case Eap.TTLS:
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
     *
     * @return X.509 CA certificate
     */
    public X509Certificate getCaCertificate() {
        return mCaCert;
    }

    /**
     * Set Client certificate alias.
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

    boolean needsKeyStore() {
        // Has no keys to be installed
        if (mClientCertificate == null && mCaCert == null) return false;
        return true;
    }

    static boolean isHardwareBackedKey(PrivateKey key) {
        return KeyChain.isBoundKeyAlgorithm(key.getAlgorithm());
    }

    static boolean hasHardwareBackedKey(Certificate certificate) {
        return KeyChain.isBoundKeyAlgorithm(certificate.getPublicKey().getAlgorithm());
    }

    boolean needsSoftwareBackedKeyStore() {
        return mNeedsSoftwareKeystore;
    }

    boolean installKeys(android.security.KeyStore keyStore, String name) {
        boolean ret = true;
        String privKeyName = Credentials.USER_PRIVATE_KEY + name;
        String userCertName = Credentials.USER_CERTIFICATE + name;
        String caCertName = Credentials.CA_CERTIFICATE + name;
        if (mClientCertificate != null) {
            byte[] privKeyData = mClientPrivateKey.getEncoded();
            if (isHardwareBackedKey(mClientPrivateKey)) {
                // Hardware backed key store is secure enough to store keys un-encrypted, this
                // removes the need for user to punch a PIN to get access to these keys
                if (DBG) Slog.d(TAG, "importing keys " + name + " in hardware backed " +
                        "store");
                ret = keyStore.importKey(privKeyName, privKeyData, Process.WIFI_UID,
                                KeyStore.FLAG_NONE);
            } else {
                // Software backed key store is NOT secure enough to store keys un-encrypted.
                // Save keys encrypted so they are protected with user's PIN. User will
                // have to unlock phone before being able to use these keys and connect to
                // networks.
                if (DBG) Slog.d(TAG, "importing keys " + name + " in software backed store");
                ret = keyStore.importKey(privKeyName, privKeyData, Process.WIFI_UID,
                        KeyStore.FLAG_ENCRYPTED);
                mNeedsSoftwareKeystore = true;
            }
            if (ret == false) {
                return ret;
            }

            ret = putCertInKeyStore(keyStore, userCertName, mClientCertificate);
            if (ret == false) {
                // Remove private key installed
                keyStore.delKey(privKeyName, Process.WIFI_UID);
                return ret;
            }
        }

        if (mCaCert != null) {
            ret = putCertInKeyStore(keyStore, caCertName, mCaCert);
            if (ret == false) {
                if (mClientCertificate != null) {
                    // Remove client key+cert
                    keyStore.delKey(privKeyName, Process.WIFI_UID);
                    keyStore.delete(userCertName, Process.WIFI_UID);
                }
                return ret;
            }
        }

        // Set alias names
        if (mClientCertificate != null) {
            setClientCertificateAlias(name);
            mClientPrivateKey = null;
            mClientCertificate = null;
        }

        if (mCaCert != null) {
            setCaCertificateAlias(name);
            mCaCert = null;
        }

        return ret;
    }

    private boolean putCertInKeyStore(android.security.KeyStore keyStore, String name,
            Certificate cert) {
        try {
            byte[] certData = Credentials.convertToPem(cert);
            if (DBG) Slog.d(TAG, "putting certificate " + name + " in keystore");
            return keyStore.put(name, certData, Process.WIFI_UID, KeyStore.FLAG_NONE);

        } catch (IOException e1) {
            return false;
        } catch (CertificateException e2) {
            return false;
        }
    }

    void removeKeys(KeyStore keyStore) {
        String client = getFieldValue(CLIENT_CERT_KEY, CLIENT_CERT_PREFIX);
        // a valid client certificate is configured
        if (!TextUtils.isEmpty(client)) {
            if (DBG) Slog.d(TAG, "removing client private key and user cert");
            keyStore.delKey(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
            keyStore.delete(Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
        }

        String ca = getFieldValue(CA_CERT_KEY, CA_CERT_PREFIX);
        // a valid ca certificate is configured
        if (!TextUtils.isEmpty(ca)) {
            if (DBG) Slog.d(TAG, "removing CA cert");
            keyStore.delete(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
        }
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

    /** Migrates the old style TLS config to the new config style. This should only be used
     * when restoring an old wpa_supplicant.conf or upgrading from a previous
     * platform version.
     * @return true if the config was updated
     * @hide
     */
    boolean migrateOldEapTlsNative(WifiNative wifiNative, int netId) {
        String oldPrivateKey = wifiNative.getNetworkVariable(netId, OLD_PRIVATE_KEY_NAME);
        /*
         * If the old configuration value is not present, then there is nothing
         * to do.
         */
        if (TextUtils.isEmpty(oldPrivateKey)) {
            return false;
        } else {
            // Also ignore it if it's empty quotes.
            oldPrivateKey = removeDoubleQuotes(oldPrivateKey);
            if (TextUtils.isEmpty(oldPrivateKey)) {
                return false;
            }
        }

        mFields.put(ENGINE_KEY, ENGINE_ENABLE);
        mFields.put(ENGINE_ID_KEY, convertToQuotedString(ENGINE_ID_KEYSTORE));

        /*
        * The old key started with the keystore:// URI prefix, but we don't
        * need that anymore. Trim it off if it exists.
        */
        final String keyName;
        if (oldPrivateKey.startsWith(KEYSTORE_URI)) {
            keyName = new String(oldPrivateKey.substring(KEYSTORE_URI.length()));
        } else {
            keyName = oldPrivateKey;
        }
        mFields.put(PRIVATE_KEY_ID_KEY, convertToQuotedString(keyName));

        wifiNative.setNetworkVariable(netId, ENGINE_KEY, mFields.get(ENGINE_KEY));
        wifiNative.setNetworkVariable(netId, ENGINE_ID_KEY, mFields.get(ENGINE_ID_KEY));
        wifiNative.setNetworkVariable(netId, PRIVATE_KEY_ID_KEY, mFields.get(PRIVATE_KEY_ID_KEY));
        // Remove old private_key string so we don't run this again.
        wifiNative.setNetworkVariable(netId, OLD_PRIVATE_KEY_NAME, EMPTY_VALUE);
        return true;
    }

    /** Migrate certs from global pool to wifi UID if not already done */
    void migrateCerts(android.security.KeyStore keyStore) {
        String client = getFieldValue(CLIENT_CERT_KEY, CLIENT_CERT_PREFIX);
        // a valid client certificate is configured
        if (!TextUtils.isEmpty(client)) {
            if (!keyStore.contains(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID)) {
                keyStore.duplicate(Credentials.USER_PRIVATE_KEY + client, -1,
                        Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
                keyStore.duplicate(Credentials.USER_CERTIFICATE + client, -1,
                        Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
            }
        }

        String ca = getFieldValue(CA_CERT_KEY, CA_CERT_PREFIX);
        // a valid ca certificate is configured
        if (!TextUtils.isEmpty(ca)) {
            if (!keyStore.contains(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID)) {
                keyStore.duplicate(Credentials.CA_CERTIFICATE + ca, -1,
                        Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
            }
        }
    }

    void initializeSoftwareKeystoreFlag(android.security.KeyStore keyStore) {
        String client = getFieldValue(CLIENT_CERT_KEY, CLIENT_CERT_PREFIX);
        if (!TextUtils.isEmpty(client)) {
            // a valid client certificate is configured

            // BUGBUG: keyStore.get() never returns certBytes; because it is not
            // taking WIFI_UID as a parameter. It always looks for certificate
            // with SYSTEM_UID, and never finds any Wifi certificates. Assuming that
            // all certificates need software keystore until we get the get() API
            // fixed.

            mNeedsSoftwareKeystore = true;

            /*
            try {

                if (DBG) Slog.d(TAG, "Loading client certificate " + Credentials
                        .USER_CERTIFICATE + client);

                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                if (factory == null) {
                    Slog.e(TAG, "Error getting certificate factory");
                    return;
                }

                byte[] certBytes = keyStore.get(Credentials.USER_CERTIFICATE + client);
                if (certBytes != null) {
                    Certificate cert = (X509Certificate) factory.generateCertificate(
                            new ByteArrayInputStream(certBytes));

                    if (cert != null) {
                        mNeedsSoftwareKeystore = hasHardwareBackedKey(cert);

                        if (DBG) Slog.d(TAG, "Loaded client certificate " + Credentials
                                .USER_CERTIFICATE + client);
                        if (DBG) Slog.d(TAG, "It " + (mNeedsSoftwareKeystore ? "needs" :
                                "does not need" ) + " software key store");
                    } else {
                        Slog.d(TAG, "could not generate certificate");
                    }
                } else {
                    Slog.e(TAG, "Could not load client certificate " + Credentials
                            .USER_CERTIFICATE + client);
                    mNeedsSoftwareKeystore = true;
                }

            } catch(CertificateException e) {
                Slog.e(TAG, "Could not read certificates");
                mCaCert = null;
                mClientCertificate = null;
            }
            */
        }
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
     */
    private String getFieldValue(String key, String prefix) {
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
     */
    private void setFieldValue(String key, String value, String prefix) {
        if (TextUtils.isEmpty(value)) {
            mFields.put(key, EMPTY_VALUE);
        } else {
            mFields.put(key, convertToQuotedString(prefix + value));
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
