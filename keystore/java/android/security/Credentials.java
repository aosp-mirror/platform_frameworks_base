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

import android.annotation.UnsupportedAppUsage;

import com.android.org.bouncycastle.util.io.pem.PemObject;
import com.android.org.bouncycastle.util.io.pem.PemReader;
import com.android.org.bouncycastle.util.io.pem.PemWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@hide}
 */
public class Credentials {
    private static final String LOGTAG = "Credentials";

    public static final String INSTALL_ACTION = "android.credentials.INSTALL";

    public static final String INSTALL_AS_USER_ACTION = "android.credentials.INSTALL_AS_USER";

    /** Key prefix for CA certificates. */
    public static final String CA_CERTIFICATE = "CACERT_";

    /** Key prefix for user certificates. */
    public static final String USER_CERTIFICATE = "USRCERT_";

    /** Key prefix for user private and secret keys. */
    public static final String USER_PRIVATE_KEY = "USRPKEY_";

    /** Key prefix for user secret keys.
     *  @deprecated use {@code USER_PRIVATE_KEY} for this category instead.
     */
    public static final String USER_SECRET_KEY = "USRSKEY_";

    /** Key prefix for VPN. */
    public static final String VPN = "VPN_";

    /** Key prefix for WIFI. */
    public static final String WIFI = "WIFI_";

    /** Key containing suffix of lockdown VPN profile. */
    public static final String LOCKDOWN_VPN = "LOCKDOWN_VPN";

    /** Data type for public keys. */
    public static final String EXTRA_PUBLIC_KEY = "KEY";

    /** Data type for private keys. */
    public static final String EXTRA_PRIVATE_KEY = "PKEY";

    // historically used by Android
    public static final String EXTENSION_CRT = ".crt";
    public static final String EXTENSION_P12 = ".p12";
    // commonly used on Windows
    public static final String EXTENSION_CER = ".cer";
    public static final String EXTENSION_PFX = ".pfx";

    /**
     * Intent extra: install the certificate bundle as this UID instead of
     * system.
     */
    public static final String EXTRA_INSTALL_AS_UID = "install_as_uid";

    /**
     * Intent extra: name for the user's key pair.
     */
    public static final String EXTRA_USER_KEY_ALIAS = "user_key_pair_name";

    /**
     * Intent extra: data for the user's private key in PEM-encoded PKCS#8.
     */
    public static final String EXTRA_USER_PRIVATE_KEY_DATA = "user_private_key_data";

    /**
     * Intent extra: data for the user's certificate in PEM-encoded X.509.
     */
    public static final String EXTRA_USER_CERTIFICATE_DATA = "user_certificate_data";

    /**
     * Intent extra: data for CA certificate chain in PEM-encoded X.509.
     */
    public static final String EXTRA_CA_CERTIFICATES_DATA = "ca_certificates_data";

    /**
     * Convert objects to a PEM format which is used for
     * CA_CERTIFICATE and USER_CERTIFICATE entries.
     */
    @UnsupportedAppUsage
    public static byte[] convertToPem(Certificate... objects)
            throws IOException, CertificateEncodingException {
        ByteArrayOutputStream bao = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(bao, StandardCharsets.US_ASCII);
        PemWriter pw = new PemWriter(writer);
        for (Certificate o : objects) {
            pw.writeObject(new PemObject("CERTIFICATE", o.getEncoded()));
        }
        pw.close();
        return bao.toByteArray();
    }
    /**
     * Convert objects from PEM format, which is used for
     * CA_CERTIFICATE and USER_CERTIFICATE entries.
     */
    public static List<X509Certificate> convertFromPem(byte[] bytes)
            throws IOException, CertificateException {
        ByteArrayInputStream bai = new ByteArrayInputStream(bytes);
        Reader reader = new InputStreamReader(bai, StandardCharsets.US_ASCII);
        PemReader pr = new PemReader(reader);

        try {
            CertificateFactory cf = CertificateFactory.getInstance("X509");

            List<X509Certificate> result = new ArrayList<X509Certificate>();
            PemObject o;
            while ((o = pr.readPemObject()) != null) {
                if (o.getType().equals("CERTIFICATE")) {
                    Certificate c = cf.generateCertificate(new ByteArrayInputStream(o.getContent()));
                    result.add((X509Certificate) c);
                } else {
                    throw new IllegalArgumentException("Unknown type " + o.getType());
                }
            }
            return result;
        } finally {
            pr.close();
        }
    }

    /**
     * Delete all types (private key, user certificate, CA certificate) for a
     * particular {@code alias}. All three can exist for any given alias.
     * Returns {@code true} if the alias no longer contains any types.
     */
    public static boolean deleteAllTypesForAlias(KeyStore keystore, String alias) {
        return deleteAllTypesForAlias(keystore, alias, KeyStore.UID_SELF);
    }

    /**
     * Delete all types (private key, user certificate, CA certificate) for a
     * particular {@code alias}. All three can exist for any given alias.
     * Returns {@code true} if the alias no longer contains any types.
     */
    public static boolean deleteAllTypesForAlias(KeyStore keystore, String alias, int uid) {
        /*
         * Make sure every type is deleted. There can be all three types, so
         * don't use a conditional here.
         */
        return deleteUserKeyTypeForAlias(keystore, alias, uid)
                & deleteCertificateTypesForAlias(keystore, alias, uid);
    }

    /**
     * Delete certificate types (user certificate, CA certificate) for a
     * particular {@code alias}. Both can exist for any given alias.
     * Returns {@code true} if the alias no longer contains either type.
     */
    public static boolean deleteCertificateTypesForAlias(KeyStore keystore, String alias) {
        return deleteCertificateTypesForAlias(keystore, alias, KeyStore.UID_SELF);
    }

    /**
     * Delete certificate types (user certificate, CA certificate) for a
     * particular {@code alias}. Both can exist for any given alias.
     * Returns {@code true} if the alias no longer contains either type.
     */
    public static boolean deleteCertificateTypesForAlias(KeyStore keystore, String alias, int uid) {
        /*
         * Make sure every certificate type is deleted. There can be two types,
         * so don't use a conditional here.
         */
        return keystore.delete(Credentials.USER_CERTIFICATE + alias, uid)
                & keystore.delete(Credentials.CA_CERTIFICATE + alias, uid);
    }

    /**
     * Delete user key for a particular {@code alias}.
     * Returns {@code true} if the entry no longer exists.
     */
    public static boolean deleteUserKeyTypeForAlias(KeyStore keystore, String alias) {
        return deleteUserKeyTypeForAlias(keystore, alias, KeyStore.UID_SELF);
    }

    /**
     * Delete user key for a particular {@code alias}.
     * Returns {@code true} if the entry no longer exists.
     */
    public static boolean deleteUserKeyTypeForAlias(KeyStore keystore, String alias, int uid) {
        int ret = keystore.delete2(Credentials.USER_PRIVATE_KEY + alias, uid);
        if (ret == KeyStore.KEY_NOT_FOUND) {
            return keystore.delete(Credentials.USER_SECRET_KEY + alias, uid);
        }
        return ret == KeyStore.NO_ERROR;
    }

    /**
     * Delete legacy prefixed entry for a particular {@code alias}
     * Returns {@code true} if the entry no longer exists.
     */
    public static boolean deleteLegacyKeyForAlias(KeyStore keystore, String alias, int uid) {
        return keystore.delete(Credentials.USER_SECRET_KEY + alias, uid);
    }
}
