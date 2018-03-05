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
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.backup.BackupManagerService;
import com.android.server.backup.testutils.PackageManagerStub;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AppBackupUtilsTest {
    private static final String CUSTOM_BACKUP_AGENT_NAME = "custom.backup.agent";
    private static final String TEST_PACKAGE_NAME = "test_package";

    private static final Signature SIGNATURE_1 = generateSignature((byte) 1);
    private static final Signature SIGNATURE_2 = generateSignature((byte) 2);
    private static final Signature SIGNATURE_3 = generateSignature((byte) 3);
    private static final Signature SIGNATURE_4 = generateSignature((byte) 4);

    private final PackageManagerStub mPackageManagerStub = new PackageManagerStub();

    @Test
    public void appIsEligibleForBackup_backupNotAllowed_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

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

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_sharedStorageBackupPackage_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_systemAppWithCustomBackupAgentAndEnabled_returnsTrue()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsEligibleForBackup_nonSystemAppWithoutCustomBackupAgentAndEnabled_returnsTrue()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = null;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsEligibleForBackup_nonSystemAppWithCustomBackupAgentAndEnabled_returnsTrue()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsEligibleForBackup_systemAppWithCustomBackupAgentAndDisabled_returnsFalse()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_nonSystemAppWithoutCustomBackupAgentAndDisabled_returnsFalse()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = null;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_nonSystemAppWithCustomBackupAgentAndDisbled_returnsFalse()
            throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo,
                mPackageManagerStub);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsDisabled_stateEnabled_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        boolean isDisabled = AppBackupUtils.appIsDisabled(applicationInfo, mPackageManagerStub);

        assertThat(isDisabled).isFalse();
    }

    @Test
    public void appIsDisabled_stateDisabled_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        boolean isDisabled = AppBackupUtils.appIsDisabled(applicationInfo, mPackageManagerStub);

        assertThat(isDisabled).isTrue();
    }

    @Test
    public void appIsDisabled_stateDisabledUser_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;

        boolean isDisabled = AppBackupUtils.appIsDisabled(applicationInfo, mPackageManagerStub);

        assertThat(isDisabled).isTrue();
    }

    @Test
    public void appIsDisabled_stateDisabledUntilUsed_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        PackageManagerStub.sApplicationEnabledSetting =
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;

        boolean isDisabled = AppBackupUtils.appIsDisabled(applicationInfo, mPackageManagerStub);

        assertThat(isDisabled).isTrue();
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
        boolean result = AppBackupUtils.signaturesMatch(new Signature[] {SIGNATURE_1}, null);

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
    public void signaturesMatch_disallowsUnsignedApps_storedSignatureNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[] {SIGNATURE_1};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(null, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_storedSignatureEmpty_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[] {SIGNATURE_1};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(new Signature[0], packageInfo);

        assertThat(result).isFalse();
    }


    @Test
    public void
    signaturesMatch_disallowsUnsignedApps_targetSignatureEmpty_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[0];
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(new Signature[] {SIGNATURE_1},
                packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void
    signaturesMatch_disallowsUnsignedApps_targetSignatureNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(new Signature[] {SIGNATURE_1},
                packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_bothSignaturesNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(null, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_bothSignaturesEmpty_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[0];
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(new Signature[0], packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_equalSignatures_returnsTrue() throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());
        Signature signature2Copy = new Signature(SIGNATURE_2.toByteArray());
        Signature signature3Copy = new Signature(SIGNATURE_3.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{SIGNATURE_1, SIGNATURE_2, SIGNATURE_3};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{signature3Copy, signature1Copy, signature2Copy}, packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_extraSignatureInTarget_returnsTrue() throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());
        Signature signature2Copy = new Signature(SIGNATURE_2.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{SIGNATURE_1, SIGNATURE_2, SIGNATURE_3};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{signature2Copy, signature1Copy}, packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_extraSignatureInStored_returnsFalse() throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());
        Signature signature2Copy = new Signature(SIGNATURE_2.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{signature1Copy, signature2Copy};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{SIGNATURE_1, SIGNATURE_2, SIGNATURE_3}, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_oneNonMatchingSignature_returnsFalse() throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());
        Signature signature2Copy = new Signature(SIGNATURE_2.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signatures = new Signature[]{SIGNATURE_1, SIGNATURE_2, SIGNATURE_3};
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = AppBackupUtils.signaturesMatch(
                new Signature[]{signature1Copy, signature2Copy, SIGNATURE_4}, packageInfo);

        assertThat(result).isFalse();
    }

    private static Signature generateSignature(byte i) {
        byte[] signatureBytes = new byte[256];
        signatureBytes[0] = i;
        return new Signature(signatureBytes);
    }
}
