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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

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

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

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
        File testApk = getApk("SourceStampVerifierTest/valid-stamp.apk");
        ZipFile apkZipFile = new ZipFile(testApk);
        ZipEntry stampCertZipEntry = apkZipFile.getEntry("stamp-cert-sha256");
        int size = (int) stampCertZipEntry.getSize();
        byte[] expectedStampCertHash = new byte[size];
        try (InputStream inputStream = apkZipFile.getInputStream(stampCertZipEntry)) {
            inputStream.read(expectedStampCertHash);
        }

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(testApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertTrue(result.isVerified());
        assertNotNull(result.getCertificate());
        byte[] actualStampCertHash =
                MessageDigest.getInstance("SHA-256").digest(result.getCertificate().getEncoded());
        assertArrayEquals(expectedStampCertHash, actualStampCertHash);
    }

    @Test
    public void testSourceStamp_signatureMissing() throws Exception {
        File testApk = getApk("SourceStampVerifierTest/stamp-without-block.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(testApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_certificateMismatch() throws Exception {
        File testApk = getApk("SourceStampVerifierTest/stamp-certificate-mismatch.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(testApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_apkHashMismatch() throws Exception {
        File testApk = getApk("SourceStampVerifierTest/stamp-apk-hash-mismatch.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(testApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_malformedSignature() throws Exception {
        File testApk = getApk("SourceStampVerifierTest/stamp-malformed-signature.apk");

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(testApk.getAbsolutePath());

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    @Test
    public void testSourceStamp_multiApk_validStamps() throws Exception {
        File testApk1 = getApk("SourceStampVerifierTest/valid-stamp.apk");
        File testApk2 = getApk("SourceStampVerifierTest/valid-stamp.apk");
        ZipFile apkZipFile = new ZipFile(testApk1);
        ZipEntry stampCertZipEntry = apkZipFile.getEntry("stamp-cert-sha256");
        int size = (int) stampCertZipEntry.getSize();
        byte[] expectedStampCertHash = new byte[size];
        try (InputStream inputStream = apkZipFile.getInputStream(stampCertZipEntry)) {
            inputStream.read(expectedStampCertHash);
        }
        List<String> apkFiles = new ArrayList<>();
        apkFiles.add(testApk1.getAbsolutePath());
        apkFiles.add(testApk2.getAbsolutePath());

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(apkFiles);

        assertTrue(result.isPresent());
        assertTrue(result.isVerified());
        assertNotNull(result.getCertificate());
        byte[] actualStampCertHash =
                MessageDigest.getInstance("SHA-256").digest(result.getCertificate().getEncoded());
        assertArrayEquals(expectedStampCertHash, actualStampCertHash);
    }

    @Test
    public void testSourceStamp_multiApk_invalidStamps() throws Exception {
        File testApk1 = getApk("SourceStampVerifierTest/valid-stamp.apk");
        File testApk2 = getApk("SourceStampVerifierTest/stamp-apk-hash-mismatch.apk");
        List<String> apkFiles = new ArrayList<>();
        apkFiles.add(testApk1.getAbsolutePath());
        apkFiles.add(testApk2.getAbsolutePath());

        SourceStampVerificationResult result =
                SourceStampVerifier.verify(apkFiles);

        assertTrue(result.isPresent());
        assertFalse(result.isVerified());
        assertNull(result.getCertificate());
    }

    private File getApk(String apkPath) throws IOException {
        File testApk = File.createTempFile("SourceStampApk", ".apk");
        try (InputStream inputStream = mContext.getAssets().open(apkPath)) {
            Files.copy(inputStream, testApk.toPath(), REPLACE_EXISTING);
        }
        return testApk;
    }
}
