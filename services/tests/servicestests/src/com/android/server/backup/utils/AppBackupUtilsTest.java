/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.backup.utils;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Process;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.backup.RefactoredBackupManagerService;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AppBackupUtilsTest {
    private static final String CUSTOM_BACKUP_AGENT_NAME = "custom.backup.agent";
    private static final String TEST_PACKAGE_NAME = "test_package";

    private final Random mRandom = new Random(1000000009);

    @Test
    public void appIsEligibleForBackup_backupNotAllowed_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_systemAppWithoutCustomBackupAgent_returnsFalse()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = null;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_sharedStorageBackupPackage_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = RefactoredBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_systemAppWithCustomBackupAgent_returnsTrue()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsEligibleForBackup_nonSystemAppWithoutCustomBackupAgent_returnsTrue()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = null;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsEligibleForBackup_nonSystemAppWithCustomBackupAgent_returnsTrue()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsStopped_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_STOPPED;

        boolean isStopped = AppBackupUtils.appIsStopped(applicationInfo);

        assertThat(isStopped).isTrue();
    }

    @Test
    public void appIsStopped_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = ~ApplicationInfo.FLAG_STOPPED;

        boolean isStopped = AppBackupUtils.appIsStopped(applicationInfo);

        assertThat(isStopped).isFalse();
    }

    @Test
    public void appGetsFullBackup_noCustomBackupAgent_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = null;

        boolean result = AppBackupUtils.appGetsFullBackup(packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void appGetsFullBackup_withCustomBackupAgentAndFullBackupOnlyFlag_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_FULL_BACKUP_ONLY;

        boolean result = AppBackupUtils.appGetsFullBackup(packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void appGetsFullBackup_withCustomBackupAgentAndWithoutFullBackupOnlyFlag_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.applicationInfo.flags = ~ApplicationInfo.FLAG_FULL_BACKUP_ONLY;

        boolean result = AppBackupUtils.appGetsFullBackup(packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void appIsKeyValueOnly_noCustomBackupAgent_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = null;

        boolean result = AppBackupUtils.appIsKeyValueOnly(packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void appIsKeyValueOnly_withCustomBackupAgentAndFullBackupOnlyFlag_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_FULL_BACKUP_ONLY;

        boolean result = AppBackupUtils.appIsKeyValueOnly(packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void appIsKeyValueOnly_withCustomBackupAgentAndWithoutFullBackupOnlyFlag_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.applicationInfo.flags = ~ApplicationInfo.FLAG_FULL_BACKUP_ONLY;

        boolean result = AppBackupUtils.appIsKeyValueOnly(packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_targetIsNull_returnsFalse() throws Exception {
        boolean result = AppBackupUtils.signaturesMatch(new Signature[0], null);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_systemApplication_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;

        boolean result = AppBackupUtils.signaturesMatch(new Signature[0], packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_allowsUnsignedApps_bothSignaturesNull_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(null, packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_allowsUnsignedApps_bothSignaturesEmpty_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[0];
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(new Signature[0], packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void
    signaturesMatch_allowsUnsignedApps_storedSignatureNullTargetSignatureEmpty_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[0];
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(null, packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void
    signaturesMatch_allowsUnsignedApps_storedSignatureEmptyTargetSignatureNull_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(new Signature[0], packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void
    signaturesMatch_disallowsAppsUnsignedOnOnlyOneDevice_storedSignatureIsNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{generateRandomSignature()};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(null, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void
    signaturesMatch_disallowsAppsUnsignedOnOnlyOneDevice_targetSignatureIsNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(new Signature[]{generateRandomSignature()},
                packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_signaturesMatch_returnsTrue() throws Exception {
        Signature signature1 = generateRandomSignature();
        Signature signature2 = generateRandomSignature();
        Signature signature3 = generateRandomSignature();
        assertThat(signature1).isNotEqualTo(signature2);
        assertThat(signature2).isNotEqualTo(signature3);
        assertThat(signature1).isNotEqualTo(signature3);

        Signature signature1Copy = new Signature(signature1.toByteArray());
        Signature signature2Copy = new Signature(signature2.toByteArray());
        Signature signature3Copy = new Signature(signature3.toByteArray());
        assertThat(signature1Copy).isEqualTo(signature1);
        assertThat(signature2Copy).isEqualTo(signature2);
        assertThat(signature3Copy).isEqualTo(signature3);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{signature1, signature2, signature3};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{signature3Copy, signature1Copy, signature2Copy}, packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_extraSignatureInTarget_returnsTrue() throws Exception {
        Signature signature1 = generateRandomSignature();
        Signature signature2 = generateRandomSignature();
        Signature signature3 = generateRandomSignature();
        assertThat(signature1).isNotEqualTo(signature2);
        assertThat(signature2).isNotEqualTo(signature3);
        assertThat(signature1).isNotEqualTo(signature3);

        Signature signature1Copy = new Signature(signature1.toByteArray());
        Signature signature2Copy = new Signature(signature2.toByteArray());
        assertThat(signature1Copy).isEqualTo(signature1);
        assertThat(signature2Copy).isEqualTo(signature2);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{signature1, signature2, signature3};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{signature2Copy, signature1Copy}, packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_extraSignatureInStored_returnsFalse() throws Exception {
        Signature signature1 = generateRandomSignature();
        Signature signature2 = generateRandomSignature();
        Signature signature3 = generateRandomSignature();
        assertThat(signature1).isNotEqualTo(signature2);
        assertThat(signature2).isNotEqualTo(signature3);
        assertThat(signature1).isNotEqualTo(signature3);

        Signature signature1Copy = new Signature(signature1.toByteArray());
        Signature signature2Copy = new Signature(signature2.toByteArray());
        assertThat(signature1Copy).isEqualTo(signature1);
        assertThat(signature2Copy).isEqualTo(signature2);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{signature1Copy, signature2Copy};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{signature1, signature2, signature3}, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_emptyStoredSignatures_returnsTrue() throws Exception {
        Signature signature1 = generateRandomSignature();
        Signature signature2 = generateRandomSignature();
        Signature signature3 = generateRandomSignature();
        assertThat(signature1).isNotEqualTo(signature2);
        assertThat(signature2).isNotEqualTo(signature3);
        assertThat(signature1).isNotEqualTo(signature3);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{signature1, signature2, signature3};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(new Signature[0], packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_emptyTargetSignatures_returnsFalse() throws Exception {
        Signature signature1 = generateRandomSignature();
        Signature signature2 = generateRandomSignature();
        Signature signature3 = generateRandomSignature();
        assertThat(signature1).isNotEqualTo(signature2);
        assertThat(signature2).isNotEqualTo(signature3);
        assertThat(signature1).isNotEqualTo(signature3);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[0];
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{signature1, signature2, signature3}, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_oneNonMatchingSignature_returnsFalse() throws Exception {
        Signature signature1 = generateRandomSignature();
        Signature signature2 = generateRandomSignature();
        Signature signature3 = generateRandomSignature();
        Signature signature4 = generateRandomSignature();
        assertThat(signature1).isNotEqualTo(signature2);
        assertThat(signature2).isNotEqualTo(signature3);
        assertThat(signature1).isNotEqualTo(signature3);
        assertThat(signature1).isNotEqualTo(signature4);
        assertThat(signature2).isNotEqualTo(signature4);
        assertThat(signature3).isNotEqualTo(signature4);

        Signature signature1Copy = new Signature(signature1.toByteArray());
        Signature signature2Copy = new Signature(signature2.toByteArray());
        assertThat(signature1Copy).isEqualTo(signature1);
        assertThat(signature2Copy).isEqualTo(signature2);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{signature1, signature2, signature3};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{signature1Copy, signature2Copy, signature4}, packageInfo);

        assertThat(result).isFalse();
    }

    private Signature generateRandomSignature() {
        byte[] signatureBytes = new byte[256];
        mRandom.nextBytes(signatureBytes);
        return new Signature(signatureBytes);
    }
}
