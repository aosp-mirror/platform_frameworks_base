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
 * limitations under the License.
 */
package com.android.server.pm.backup;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.platform.test.annotations.Presubmit;
import android.test.MoreAsserts;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.BackupUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupUtilsTest {

    private static final Signature SIGNATURE_1 = generateSignature((byte) 1);
    private static final Signature SIGNATURE_2 = generateSignature((byte) 2);
    private static final Signature SIGNATURE_3 = generateSignature((byte) 3);
    private static final Signature SIGNATURE_4 = generateSignature((byte) 4);
    private static final byte[] SIGNATURE_HASH_1 = BackupUtils.hashSignature(SIGNATURE_1);
    private static final byte[] SIGNATURE_HASH_2 = BackupUtils.hashSignature(SIGNATURE_2);
    private static final byte[] SIGNATURE_HASH_3 = BackupUtils.hashSignature(SIGNATURE_3);
    private static final byte[] SIGNATURE_HASH_4 = BackupUtils.hashSignature(SIGNATURE_4);

    private PackageManagerInternal mMockPackageManagerInternal;

    @Before
    public void setUp() throws Exception {
        mMockPackageManagerInternal = mock(PackageManagerInternal.class);
    }

    @Test
    public void signaturesMatch_targetIsNull_returnsFalse() throws Exception {
        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, null,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_systemApplication_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_storedSignatureNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = BackupUtils.signaturesMatch(null, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_storedSignatureEmpty_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }


    @Test
    public void
    signaturesMatch_disallowsUnsignedApps_targetSignatureEmpty_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void
    signaturesMatch_disallowsUnsignedApps_targetSignatureNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_bothSignaturesNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = BackupUtils.signaturesMatch(null, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_bothSignaturesEmpty_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_equalSignatures_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2, SIGNATURE_3},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        storedSigHashes.add(SIGNATURE_HASH_2);
        storedSigHashes.add(SIGNATURE_HASH_3);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_extraSignatureInTarget_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2, SIGNATURE_3},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        storedSigHashes.add(SIGNATURE_HASH_2);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_extraSignatureInStored_returnsFalse() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        storedSigHashes.add(SIGNATURE_HASH_2);
        storedSigHashes.add(SIGNATURE_HASH_3);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_oneNonMatchingSignature_returnsFalse() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2, SIGNATURE_3},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        storedSigHashes.add(SIGNATURE_HASH_2);
        storedSigHashes.add(SIGNATURE_HASH_4);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_singleStoredSignatureNoRotation_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(SIGNATURE_HASH_1,
                packageInfo.packageName);

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_singleStoredSignatureWithRotationAssumeDataCapability_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        // we know SIGNATURE_1 is in history, and we want to assume it has
        // SigningDetails.CertCapabilities.INSTALLED_DATA capability
        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(SIGNATURE_HASH_1,
                packageInfo.packageName);

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isTrue();
    }

    @Test
    public void
            signaturesMatch_singleStoredSignatureWithRotationAssumeNoDataCapability_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2},
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        // we know SIGNATURE_1 is in history, but we want to assume it does not have
        // SigningDetails.CertCapabilities.INSTALLED_DATA capability
        doReturn(false).when(mMockPackageManagerInternal).isDataRestoreSafe(SIGNATURE_HASH_1,
                packageInfo.packageName);

        ArrayList<byte[]> storedSigHashes = new ArrayList<>();
        storedSigHashes.add(SIGNATURE_HASH_1);
        boolean result = BackupUtils.signaturesMatch(storedSigHashes, packageInfo,
                mMockPackageManagerInternal);

        assertThat(result).isFalse();
    }

    @Test
    public void testHashSignature() {
        final byte[] sig1 = "abc".getBytes();
        final byte[] sig2 = "def".getBytes();

        final byte[] hash1a = BackupUtils.hashSignature(sig1);
        final byte[] hash1b = BackupUtils.hashSignature(new Signature(sig1));

        final byte[] hash2a = BackupUtils.hashSignature(sig2);
        final byte[] hash2b = BackupUtils.hashSignature(new Signature(sig2));

        assertEquals(32, hash1a.length);
        MoreAsserts.assertEquals(hash1a, hash1b);

        assertEquals(32, hash2a.length);
        MoreAsserts.assertEquals(hash2a, hash2b);

        assertFalse(Arrays.equals(hash1a, hash2a));

        final ArrayList<byte[]> listA = BackupUtils.hashSignatureArray(Arrays.asList(
                "abc".getBytes(), "def".getBytes()));

        final ArrayList<byte[]> listB = BackupUtils.hashSignatureArray(new Signature[]{
                new Signature("abc".getBytes()), new Signature("def".getBytes())});

        assertEquals(2, listA.size());
        assertEquals(2, listB.size());

        MoreAsserts.assertEquals(hash1a, listA.get(0));
        MoreAsserts.assertEquals(hash1a, listB.get(0));

        MoreAsserts.assertEquals(hash2a, listA.get(1));
        MoreAsserts.assertEquals(hash2a, listB.get(1));
    }

    private static Signature generateSignature(byte i) {
        byte[] signatureBytes = new byte[256];
        signatureBytes[0] = i;
        return new Signature(signatureBytes);
    }
}
