/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.security;

import android.content.Context;
import android.content.Intent;
import android.security.Keystore;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;

/**
 * The CertTool class provides the functions to list the certs/keys,
 * generate the certificate request(csr) and store certificates into
 * keystore.
 *
 * {@hide}
 */
public class CertTool {
    static {
        System.loadLibrary("certtool_jni");
    }

    /** Keystore namespace for CA certificates. */
    public static final String CA_CERTIFICATE = "CACERT";

    /** Keystore namespace for user certificates. */
    public static final String USER_CERTIFICATE = "USRCERT";

    /** Keystore namespace for user private keys. */
    public static final String USER_KEY = "USRKEY";

    /** Action string for adding certificates to keystore. */
    public static final String ACTION_ADD_CREDENTIAL =
            "android.security.ADD_CREDENTIAL";

    /** Action string for installing certificates to keystore from sdcard. */
    public static final String ACTION_INSTALL_CERT_FROM_SDCARD =
            "android.security.INSTALL_CERT_FROM_SDCARD";

    /** Dialog title for adding a CA certificate. */
    public static final String TITLE_CA_CERT = "CA Certificate";

    /** Dialog title for adding a user certificate. */
    public static final String TITLE_USER_CERT = "User Certificate";

    /** Dialog title for adding a user private key. */
    public static final String TITLE_PRIVATE_KEY = "Private Key";

    /** Dialog title for adding a PKCS12 keystore. */
    public static final String TITLE_PKCS12_KEYSTORE = "PKCS12 Keystore";

    public static final int INCORRECT_PKCS12_PASSPHRASE = -100;

    /**
     * The builder class for building an add-credential-to-keystore intent.
     */
    public static class AddCredentialIntentBuilder {
        private Intent mIntent;
        private int mCount;

        /**
         * Creates a builder to build a add-credential-to-keystore intent.
         *
         * @param title title of the dialog for adding this credential
         * @param descriptions description strings to show on the dialog
         */
        public AddCredentialIntentBuilder(String title,
                String... descriptions) {
            Intent intent = new Intent(ACTION_ADD_CREDENTIAL);
            intent.putExtra(KEY_TITLE, title);

            int i = 0;
            for (String description : descriptions) {
                intent.putExtra(KEY_DESCRIPTION + (i++), description);
            }
            mIntent = intent;
        }

        /**
         * Adds credential data to the intent.
         *
         * @param namespace the namespace of the keystore to add the credential
         *      data to
         * @param data the credential data
         * @return this builder
         */
        public AddCredentialIntentBuilder addCredential(String namespace,
                byte[] data) {
            mIntent.putExtra(KEY_NAMESPACE + mCount, namespace);
            mIntent.putExtra(KEY_ITEM + mCount, data);
            mCount++;
            return this;
        }

        /** Returns the intent. */
        public Intent build() {
            return mIntent;
        }
    }

    /**
     * Request for adding credential data to keystore.
     */
    public static class AddCredentialRequest {
        private Intent mIntent;

        /**
         * Creates an add-credential-data-to-keystore request.
         *
         * @param intent an add-credential-data-to-keystore intent
         * @see AddCredentialIntentBuilder
         */
        public AddCredentialRequest(Intent intent) {
            mIntent = intent;
        }

        /** Returns the dialog title. */
        public String getTitle() {
            return mIntent.getStringExtra(KEY_TITLE);
        }

        /**
         * Returns the i'th credential data.
         * @return the data or null if not exists
         */
        public byte[] getDataAt(int i) {
            return mIntent.getByteArrayExtra(KEY_ITEM + i);
        }

        /**
         * Returns the namespace of the i'th credential data.
         * @return the namespace string or null if missing
         */
        public String getNamespaceAt(int i) {
            return mIntent.getStringExtra(KEY_NAMESPACE + i);
        }

        /** Returns the descriptions of the credential data. */
        public String[] getDescriptions() {
            ArrayList<String> list = new ArrayList<String>();
            for (int i = 0; ; i++) {
                String s = mIntent.getStringExtra(KEY_DESCRIPTION + i);
                if (s == null) break;
                list.add(s);
            }
            return list.toArray(new String[list.size()]);
        }
    }

    private static final String KEY_TITLE = "typeName";
    private static final String KEY_ITEM = "item";
    private static final String KEY_NAMESPACE = "namespace";
    private static final String KEY_DESCRIPTION = "description";

    private static final String TAG = "CertTool";
    private static final String UNKNOWN = "Unknown";
    private static final String ISSUER_NAME = "Issuer Name:";
    private static final String DISTINCT_NAME = "Distinct Name:";

    private static final String KEYNAME_DELIMITER = "_";
    private static final Keystore sKeystore = Keystore.getInstance();

    private native int getPkcs12Handle(byte[] data, String password);
    private native String getPkcs12Certificate(int handle);
    private native String getPkcs12PrivateKey(int handle);
    private native String popPkcs12CertificateStack(int handle);
    private native void freePkcs12Handle(int handle);
    private native String generateCertificateRequest(int bits, String challenge);
    private native boolean isPkcs12Keystore(byte[] data);
    private native int generateX509Certificate(byte[] data);
    private native boolean isCaCertificate(int handle);
    private native String getIssuerDN(int handle);
    private native String getCertificateDN(int handle);
    private native String getPrivateKeyPEM(int handle);
    private native void freeX509Certificate(int handle);

    private static CertTool sSingleton = null;

    private CertTool() { }

    public static final CertTool getInstance() {
        if (sSingleton == null) {
            sSingleton = new CertTool();
        }
        return sSingleton;
    }

    /**
     * Gets the full key to retrieve the user private key from the keystore.
     * @see #getAllUserCertificateKeys()
     */
    public String getUserPrivateKey(String key) {
        return USER_KEY + KEYNAME_DELIMITER + key;
    }

    /**
     * Gets the full key to retrieve the user certificate from the keystore.
     * @see #getAllUserCertificateKeys()
     */
    public String getUserCertificate(String key) {
        return USER_CERTIFICATE + KEYNAME_DELIMITER + key;
    }

    /**
     * Gets the full key to retrieve the CA certificate from the keystore.
     * @see #getAllCaCertificateKeys()
     */
    public String getCaCertificate(String key) {
        return CA_CERTIFICATE + KEYNAME_DELIMITER + key;
    }

    /**
     * Gets all the keys to the user certificates/private keys stored in the
     * keystore.
     * @see #getUserCertificate(String)
     * @see #getUserPrivateKey(String)
     */
    public String[] getAllUserCertificateKeys() {
        return sKeystore.listKeys(USER_KEY);
    }

    /**
     * Gets all the keys to the CA certificates stored in the keystore.
     * @see #getCaCertificate(String)
     */
    public String[] getAllCaCertificateKeys() {
        return sKeystore.listKeys(CA_CERTIFICATE);
    }

    public String[] getSupportedKeyStrenghs() {
        return new String[] {"High Grade", "Medium Grade"};
    }

    private int getKeyLength(int index) {
        if (index == 0) return 2048;
        return 1024;
    }

    /**
     * Generates a key pair.
     *
     * @param keyStrengthIndex index to the array of supported key strengths;
     *      see {@link #getSupportedKeyStrenghs()}
     * @param challenge the challenge string for generating the pair
     * @param dirName (not used)
     * @return a certificate request from the resulted public key
     */
    public String generateKeyPair(int keyStrengthIndex, String challenge,
            String dirName) {
        return generateCertificateRequest(getKeyLength(keyStrengthIndex),
                challenge);
    }

    private int extractAndStoreKeysFromPkcs12(int handle, String keyname) {
        int ret, i = 0;
        String pemData;

        if ((pemData = getPkcs12Certificate(handle)) != null) {
            if ((ret = sKeystore.put(USER_CERTIFICATE, keyname, pemData)) != 0) {
                return ret;
            }
        }
        if ((pemData = getPkcs12PrivateKey(handle)) != null) {
            if ((ret = sKeystore.put(USER_KEY, keyname, pemData)) != 0) {
                return ret;
            }
        }
        if ((pemData = this.popPkcs12CertificateStack(handle)) != null) {
            if ((ret = sKeystore.put(CA_CERTIFICATE, keyname, pemData)) != 0) {
                return ret;
            }
        }
        return 0;
    }

    /** Adds a PKCS12 keystore to the keystore. */
    public int addPkcs12Keystore(byte[] p12Data, String password,
            String keyname) {
        int handle, ret;
        Log.i("CertTool", "addPkcs12Keystore()");

        if ((handle = getPkcs12Handle(p12Data, password)) == 0) {
            return INCORRECT_PKCS12_PASSPHRASE;
        }
        ret = extractAndStoreKeysFromPkcs12(handle, keyname);
        freePkcs12Handle(handle);
        return ret;
    }

    /**
     * Adds a certificate to the keystore.
     *
     * @param data the certificate data
     * @param context the context to send an add-credential-to-keystore intent
     */
    public synchronized void addCertificate(byte[] data, Context context) {
        int handle;
        Intent intent = null;

        Log.i("CertTool", "addCertificate()");
        if (isPkcs12Keystore(data)) {
            intent = prepareIntent(TITLE_PKCS12_KEYSTORE, UNKNOWN, UNKNOWN)
                    .addCredential(USER_KEY, data).build();
        } else if ((handle = generateX509Certificate(data)) != 0) {
            String issuer = getIssuerDN(handle);
            String distinctName = getCertificateDN(handle);
            String privateKeyPEM = getPrivateKeyPEM(handle);
            if (isCaCertificate(handle)) {
                intent = prepareIntent(TITLE_CA_CERT, issuer, distinctName)
                        .addCredential(CA_CERTIFICATE, data).build();
            } else {
                AddCredentialIntentBuilder builder =
                        prepareIntent(TITLE_USER_CERT, issuer, distinctName)
                        .addCredential(USER_CERTIFICATE, data);
                if (!TextUtils.isEmpty(privateKeyPEM)) {
                    builder.addCredential(USER_KEY, privateKeyPEM.getBytes());
                }
                intent = builder.build();
            }
            freeX509Certificate(handle);
        }
        if (intent != null) {
            context.startActivity(intent);
        } else {
            Log.w("CertTool", "incorrect data for addCertificate()");
        }
    }

    private AddCredentialIntentBuilder prepareIntent(
            String title, String issuer, String distinctName) {
        return new AddCredentialIntentBuilder(title, ISSUER_NAME + issuer,
                DISTINCT_NAME + distinctName);
    }
}
