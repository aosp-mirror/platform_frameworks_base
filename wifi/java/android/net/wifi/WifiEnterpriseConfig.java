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

import java.util.HashMap;
import java.util.Map;

/** Enterprise configuration details for Wi-Fi @hide */
public class WifiEnterpriseConfig implements Parcelable {
    private static final String TAG = "WifiEnterpriseConfig";
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

    private HashMap<String, String> mFields = new HashMap<String, String>();

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
                    return enterpriseConfig;
                }

                public WifiEnterpriseConfig[] newArray(int size) {
                    return new WifiEnterpriseConfig[size];
                }
            };

    public static final class Eap {
        /* NONE represents an empty enterprise config */
        public static final int NONE    = -1;
        public static final int PEAP    = 0;
        public static final int TLS     = 1;
        public static final int TTLS    = 2;
        public static final int PWD     = 3;
        /** @hide */
        public static final String[] strings = { "PEAP", "TLS", "TTLS", "PWD" };
    }

    public static final class Phase2 {
        public static final int NONE        = 0;
        public static final int PAP         = 1;
        public static final int MSCHAP      = 2;
        public static final int MSCHAPV2    = 3;
        public static final int GTC         = 4;
        private static final String PREFIX = "auth=";
        /** @hide */
        public static final String[] strings = {EMPTY_VALUE, "PAP", "MSCHAP", "MSCHAPV2", "GTC" };
    }

    /** Internal use only @hide */
    public HashMap<String, String> getFields() {
        return mFields;
    }

    /** Internal use only @hide */
    public static String[] getSupplicantKeys() {
        return new String[] { EAP_KEY, PHASE2_KEY, IDENTITY_KEY, ANON_IDENTITY_KEY, PASSWORD_KEY,
                CLIENT_CERT_KEY, CA_CERT_KEY, SUBJECT_MATCH_KEY, ENGINE_KEY, ENGINE_ID_KEY,
                PRIVATE_KEY_ID_KEY };
    }

    /**
     * Set the EAP authentication method.
     * @param  eapMethod is one {@link Eap#PEAP}, {@link Eap#TLS}, {@link Eap#TTLS} or
     *                   {@link Eap#PWD}
     */
    public void setEapMethod(int eapMethod) {
        switch (eapMethod) {
            /** Valid methods */
            case Eap.PEAP:
            case Eap.PWD:
            case Eap.TLS:
            case Eap.TTLS:
                mFields.put(EAP_KEY, Eap.strings[eapMethod]);
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
     * Set CA certificate alias.
     *
     * <p> See the {@link android.security.KeyChain} for details on installing or choosing
     * a certificate
     * </p>
     * @param alias identifies the certificate
     */
    public void setCaCertificate(String alias) {
        setFieldValue(CA_CERT_KEY, alias, CA_CERT_PREFIX);
    }

    /**
     * Get CA certificate alias
     * @return alias to the CA certificate
     */
    public String getCaCertificate() {
        return getFieldValue(CA_CERT_KEY, CA_CERT_PREFIX);
    }

    /**
     * Set Client certificate alias.
     *
     * <p> See the {@link android.security.KeyChain} for details on installing or choosing
     * a certificate
     * </p>
     * @param alias identifies the certificate
     */
    public void setClientCertificate(String alias) {
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
     */
    public String getClientCertificate() {
        return getFieldValue(CLIENT_CERT_KEY, CLIENT_CERT_PREFIX);
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

    /** Migrates the old style TLS config to the new config style. This should only be used
     * when restoring an old wpa_supplicant.conf or upgrading from a previous
     * platform version.
     * @return true if the config was updated
     * @hide
     */
    public boolean migrateOldEapTlsNative(WifiNative wifiNative, int netId) {
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

    private String removeDoubleQuotes(String string) {
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
        if (EMPTY_VALUE.equals(value)) return "";
        return removeDoubleQuotes(value).substring(prefix.length());
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
