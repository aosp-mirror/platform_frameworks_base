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

package android.net.wifi;

import android.os.Parcel;

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

/**
 * Provides utilities for writing/reading a non-Parcelable objects to/from
 * a Parcel object.
 *
 * @hide
 */
public class ParcelUtil {
    /**
     * Write a PrivateKey object |key| to the specified Parcel |dest|.
     *
     * Below is the data format:
     * |algorithm|     -> String of algorithm name
     * |endcodedKey|  -> byte[] of key data
     *
     * For a null PrivateKey object, a null string will be written to |algorithm| and
     * |encodedKey| will be skipped. Since a PrivateKey can only be constructed with
     * a valid algorithm String.
     *
     * @param dest Parcel object to write to
     * @param key PrivateKey object to read from.
     */
    public static void writePrivateKey(Parcel dest, PrivateKey key) {
        if (key == null) {
            dest.writeString(null);
            return;
        }

        dest.writeString(key.getAlgorithm());
        dest.writeByteArray(key.getEncoded());
    }

    /**
     * Read/create a PrivateKey object from a specified Parcel object |in|.
     *
     * Refer to the function above for the expected data format.
     *
     * @param in Parcel object to read from
     * @return a PrivateKey object or null
     */
    public static PrivateKey readPrivateKey(Parcel in) {
        String algorithm = in.readString();
        if (algorithm == null) {
            return null;
        }

        byte[] userKeyBytes = in.createByteArray();
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(userKeyBytes));
       } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return null;
       }
    }

    /**
     * Write a X509Certificate object |cert| to a Parcel object |dest|.
     * The data being written to the Parcel is just a byte[] of the encoded certificate data.
     *
     * @param dest Parcel object to write to
     * @param cert X509Certificate object to read from
     */
    public static void writeCertificate(Parcel dest, X509Certificate cert) {
        byte[] certBytes = null;
        if (cert != null) {
            try {
                certBytes = cert.getEncoded();
            } catch (CertificateEncodingException e) {
                /* empty, write null. */
            }
        }
        dest.writeByteArray(certBytes);
    }

    /**
     * Read/create a X509Certificate object from a specified Parcel object |in|.
     *
     * @param in Parcel object to read from
     * @return a X509Certficate object or null
     */
    public static X509Certificate readCertificate(Parcel in) {
        byte[] certBytes = in.createByteArray();
        if (certBytes == null) {
            return null;
        }

        try {
            CertificateFactory cFactory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cFactory
                    .generateCertificate(new ByteArrayInputStream(certBytes));
        } catch (CertificateException e) {
            return null;
        }
    }

    /**
     * Write an array of X509Certificate objects |certs| to a Parcel object |dest|.
     * The data being written to the Parcel are consist of an integer indicating
     * the size of the array and the certificates data.  Certificates data will be
     * skipped for a null array or size of 0 array.
     *
     * @param dest Parcel object to write to
     * @param certs array of X509Certificate objects to read from
     */
    public static void writeCertificates(Parcel dest, X509Certificate[] certs) {
        if (certs == null || certs.length == 0) {
            dest.writeInt(0);
            return;
        }

        dest.writeInt(certs.length);
        for (int i = 0; i < certs.length; i++) {
            writeCertificate(dest, certs[i]);
        }
    }

    /**
     * Read/create an array of X509Certificate objects from a specified Parcel object |in|.
     *
     * @param in Parcel object to read from
     * @return X509Certficate[] or null
     */
    public static X509Certificate[] readCertificates(Parcel in) {
        int length = in.readInt();
        if (length == 0) {
            return null;
        }

        X509Certificate[] certs = new X509Certificate[length];
        for (int i = 0; i < length; i++) {
            certs[i] = readCertificate(in);
        }
        return certs;
    }
}
