/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Unit tests for {@link android.net.wifi.ParcelUtil}.
 */
@SmallTest
public class ParcelUtilTest {
    private Parcel mParcel;

    @Before
    public void setUp() throws Exception {
        mParcel = Parcel.obtain();
    }

    @Test
    public void readWriteNullPrivateKey() throws Exception {
        ParcelUtil.writePrivateKey(mParcel, null);

        mParcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        PrivateKey readKey = ParcelUtil.readPrivateKey(mParcel);
        assertNull(readKey);
    }

    @Test
    public void readWriteValidPrivateKey() throws Exception {
        PrivateKey writeKey = FakeKeys.RSA_KEY1;
        ParcelUtil.writePrivateKey(mParcel, writeKey);

        mParcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        PrivateKey readKey = ParcelUtil.readPrivateKey(mParcel);
        assertNotNull(readKey);
        assertEquals(writeKey.getAlgorithm(), readKey.getAlgorithm());
        assertArrayEquals(writeKey.getEncoded(), readKey.getEncoded());
    }

    @Test
    public void readWriteNullCertificate() throws Exception {
        ParcelUtil.writeCertificate(mParcel, null);

        mParcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        X509Certificate readCert = ParcelUtil.readCertificate(mParcel);
        assertNull(readCert);
    }

    @Test
    public void readWriteValidCertificate() throws Exception {
        X509Certificate writeCert = FakeKeys.CA_CERT1;
        ParcelUtil.writeCertificate(mParcel, writeCert);

        mParcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        X509Certificate readCert = ParcelUtil.readCertificate(mParcel);
        assertNotNull(readCert);
        assertArrayEquals(writeCert.getEncoded(), readCert.getEncoded());
    }

    @Test
    public void readWriteNullCertificates() throws Exception {
        ParcelUtil.writeCertificates(mParcel, null);

        mParcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        X509Certificate[] readCerts = ParcelUtil.readCertificates(mParcel);
        assertNull(readCerts);
    }

    @Test
    public void readWriteValidCertificates() throws Exception {
        X509Certificate[] writeCerts = new X509Certificate[2];
        writeCerts[0] = FakeKeys.CA_CERT0;
        writeCerts[1] = FakeKeys.CA_CERT1;
        ParcelUtil.writeCertificates(mParcel, writeCerts);

        mParcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        X509Certificate[] readCerts = ParcelUtil.readCertificates(mParcel);
        assertNotNull(readCerts);
        assertEquals(writeCerts.length, readCerts.length);
        for (int i = 0; i < writeCerts.length; i++) {
            assertNotNull(readCerts[i]);
            assertArrayEquals(writeCerts[i].getEncoded(), readCerts[i].getEncoded());
        }
    }
}
