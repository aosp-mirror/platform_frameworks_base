/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.util.apk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import libcore.io.Streams;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Unit test for {@link android.util.apk.SourceStampVerifier} */
@RunWith(JUnit4.class)
public class SourceStampVerifierTest {

    private final Context mContext = ApplicationProvider.getApplicationContext();

    private File mPrimaryApk;
    private File mSecondaryApk;

    @After
    public void tearDown() throws Exception {
        if (mPrimaryApk != null) {
            mPrimaryApk.delete();
        }
        if (mSecondaryApk != null) {
            mSecondaryApk.delete();
        }
    }

    @Test
    public void testSourceStamp_noStamp() throws Exception {
        File testApk = getApk("SourceStampVerifierTest/original.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(testApk.getAbsolutePath());

        assertFalse(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_correctSignature() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/valid-stamp.apk");
        byte[] expectedStampCertHash = getSourceStampCertificateHashFromApk(mPrimaryApk);

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertTrue(result.isVerified());
        assertNotNull(result.getCertificate());
        byte[] actualStampCertHash =
                MessageDigest.getInstance("SHA-256").digest(result.getCertificate().getEncoded());
        assertArrayEquals(expectedStampCertHash, actualStampCertHash);
    }

    @Test
    public void testSourceStamp_signatureMissing() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/stamp-without-block.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertFalse(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_certificateMismatch() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/stamp-certificate-mismatch.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_apkHashMismatch_v1SignatureScheme() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/stamp-apk-hash-mismatch-v1.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_apkHashMismatch_v2SignatureScheme() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/stamp-apk-hash-mismatch-v2.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_apkHashMismatch_v3SignatureScheme() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/stamp-apk-hash-mismatch-v3.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_malformedSignature() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/stamp-malformed-signature.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_multiApk_validStamps() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/valid-stamp.apk");
        mSecondaryApk = getApk("SourceStampVerifierTest/valid-stamp.apk");
        byte[] expectedStampCertHash = getSourceStampCertificateHashFromApk(mPrimaryApk);
        List<String> apkFiles = new ArrayList<>();
        apkFiles.add(mPrimaryApk.getAbsolutePath());
        apkFiles.add(mSecondaryApk.getAbsolutePath());

        SourceStampVerificationResult result = SourceStampVerifier.verify(apkFiles);

        assertTrue(result.isPresent());
        assertTrue(result.isVerified());
        assertNotNull(result.getCertificate());
        byte[] actualStampCertHash =
                MessageDigest.getInstance("SHA-256").digest(result.getCertificate().getEncoded());
        assertArrayEquals(expectedStampCertHash, actualStampCertHash);
    }

    @Test
    public void testSourceStamp_multiApk_invalidStamps() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/valid-stamp.apk");
        mSecondaryApk = getApk("SourceStampVerifierTest/stamp-apk-hash-mismatch-v3.apk");
        List<String> apkFiles = new ArrayList<>();
        apkFiles.add(mPrimaryApk.getAbsolutePath());
        apkFiles.add(mSecondaryApk.getAbsolutePath());

        SourceStampVerificationResult result = SourceStampVerifier.verify(apkFiles);

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_validStampLineage() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/stamp-lineage-valid.apk");
        byte[] expectedStampCertHash = getSourceStampCertificateHashFromApk(mPrimaryApk);

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertTrue(result.isVerified());
        assertNotNull(result.getCertificate());
        byte[] actualStampCertHash =
                MessageDigest.getInstance("SHA-256").digest(result.getCertificate().getEncoded());
        assertArrayEquals(expectedStampCertHash, actualStampCertHash);
        assertEquals(2, result.getCertificateLineage().size());
        assertEquals(result.getCertificate(),
                result.getCertificateLineage().get(result.getCertificateLineage().size() - 1));
    }

    @Test
    public void testSourceStamp_invalidStampLineage() throws Exception {
        mPrimaryApk = getApk("SourceStampVerifierTest/stamp-lineage-invalid.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(mPrimaryApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
        assertTrue(result.getCertificateLineage().isEmpty());
    }

    private File getApk(String apkPath) throws IOException {
        File apk = File.createTempFile("SourceStampApk", ".apk");
        try (InputStream inputStream = mContext.getAssets().open(apkPath)) {
            Files.copy(inputStream, apk.toPath(), REPLACE_EXISTING);
        }
        return apk;
    }

    private byte[] getSourceStampCertificateHashFromApk(File apk) throws IOException {
        ZipFile apkZipFile = new ZipFile(apk);
        ZipEntry stampCertZipEntry = apkZipFile.getEntry("stamp-cert-sha256");
        return Streams.readFully(apkZipFile.getInputStream(stampCertZipEntry));
    }
}
