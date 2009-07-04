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


/**
 * The CertTool class provides the functions to list the certs/keys,
 * generate the certificate request(csr) and store the certificate into
 * keystore.
 *
 * {@hide}
 */
public class CertTool {
    static {
        System.loadLibrary("certtool_jni");
    }

    public static final String ACTION_ADD_CREDENTIAL =
            "android.security.ADD_CREDENTIAL";
    public static final String KEY_TYPE_NAME = "typeName";
    public static final String KEY_ITEM = "item";
    public static final String KEY_NAMESPACE = "namespace";
    public static final String KEY_DESCRIPTION = "description";

    private static final String TAG = "CertTool";

    private static final String TITLE_CA_CERT = "CA Certificate";
    private static final String TITLE_USER_CERT = "User Certificate";
    private static final String TITLE_PKCS12_KEYSTORE = "PKCS12 Keystore";
    private static final String TITLE_PRIVATE_KEY = "Private Key";
    private static final String UNKNOWN = "Unknown";
    private static final String ISSUER_NAME = "Issuer Name:";
    private static final String DISTINCT_NAME = "Distinct Name:";

    private static final String CA_CERTIFICATE = "CACERT";
    private static final String USER_CERTIFICATE = "USRCERT";
    private static final String USER_KEY = "USRKEY";

    private static final String KEYNAME_DELIMITER = "_";
    private static final Keystore sKeystore = Keystore.getInstance();

    private native String generateCertificateRequest(int bits, String subject);
    private native boolean isPkcs12Keystore(byte[] data);
    private native int generateX509Certificate(byte[] data);
    private native boolean isCaCertificate(int handle);
    private native String getIssuerDN(int handle);
    private native String getCertificateDN(int handle);
    private native String getPrivateKeyPEM(int handle);
    private native void freeX509Certificate(int handle);

    private static CertTool singleton = null;

    private CertTool() { }

    public static final CertTool getInstance() {
        if (singleton == null) {
            singleton = new CertTool();
        }
        return singleton;
    }

    public String getUserPrivateKey(String key) {
        return USER_KEY + KEYNAME_DELIMITER + key;
    }

    public String getUserCertificate(String key) {
        return USER_CERTIFICATE + KEYNAME_DELIMITER + key;
    }

    public String getCaCertificate(String key) {
        return CA_CERTIFICATE + KEYNAME_DELIMITER + key;
    }

    public String[] getAllUserCertificateKeys() {
        return sKeystore.listKeys(USER_KEY);
    }

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

    public String generateKeyPair(int keyStrengthIndex, String challenge,
            String dirName) {
        return generateCertificateRequest(getKeyLength(keyStrengthIndex),
                dirName);
    }

    private Intent prepareIntent(String title, byte[] data, String namespace,
            String issuer, String distinctName) {
        Intent intent = new Intent(ACTION_ADD_CREDENTIAL);
        intent.putExtra(KEY_TYPE_NAME, title);
        intent.putExtra(KEY_ITEM + "0", data);
        intent.putExtra(KEY_NAMESPACE + "0", namespace);
        intent.putExtra(KEY_DESCRIPTION + "0", ISSUER_NAME + issuer);
        intent.putExtra(KEY_DESCRIPTION + "1", DISTINCT_NAME + distinctName);
        return intent;
    }

    private void addExtraIntentInfo(Intent intent, String namespace,
            String data) {
        intent.putExtra(KEY_ITEM + "1", data);
        intent.putExtra(KEY_NAMESPACE + "1", namespace);
    }

    public synchronized void addCertificate(byte[] data, Context context) {
        int handle;
        Intent intent = null;

        if (isPkcs12Keystore(data)) {
            intent = prepareIntent(TITLE_PKCS12_KEYSTORE, data, USER_KEY,
                    UNKNOWN, UNKNOWN);
        } else if ((handle = generateX509Certificate(data)) != 0) {
            String issuer = getIssuerDN(handle);
            String distinctName = getCertificateDN(handle);
            String privateKeyPEM = getPrivateKeyPEM(handle);
            if (isCaCertificate(handle)) {
                intent = prepareIntent(TITLE_CA_CERT, data, CA_CERTIFICATE,
                        issuer, distinctName);
            } else {
                intent = prepareIntent(TITLE_USER_CERT, data, USER_CERTIFICATE,
                        issuer, distinctName);
                if (!TextUtils.isEmpty(privateKeyPEM)) {
                    addExtraIntentInfo(intent, USER_KEY, privateKeyPEM);
                }
            }
            freeX509Certificate(handle);
        }
        if (intent != null) context.startActivity(intent);
    }
}
