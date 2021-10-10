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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.backup.BackupManager.OperationType;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.UserBackupManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupEligibilityRulesTest {
    private static final String CUSTOM_BACKUP_AGENT_NAME = "custom.backup.agent";
    private static final String TEST_PACKAGE_NAME = "com.android.frameworks.servicestests";

    private static final Signature SIGNATURE_1 = generateSignature((byte) 1);
    private static final Signature SIGNATURE_2 = generateSignature((byte) 2);
    private static final Signature SIGNATURE_3 = generateSignature((byte) 3);
    private static final Signature SIGNATURE_4 = generateSignature((byte) 4);

    @Rule public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Mock private PackageManagerInternal mMockPackageManagerInternal;
    @Mock private PackageManager mPackageManager;

    private BackupEligibilityRules mBackupEligibilityRules;
    private int mUserId;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mUserId = UserHandle.USER_SYSTEM;
        mBackupEligibilityRules = getBackupEligibilityRules(OperationType.BACKUP);
    }

    @Test
    public void appIsEligibleForBackup_backupNotAllowed_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

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

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_sharedStorageBackupPackage_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = UserBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

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
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

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
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

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
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

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
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

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
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

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
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        boolean isEligible = mBackupEligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    @EnableCompatChanges({BackupEligibilityRules.IGNORE_ALLOW_BACKUP_IN_D2D})
    public void appIsEligibleForBackup_backupNotAllowedAndInMigration_returnsTrue()
            throws Exception {
        ApplicationInfo applicationInfo = getApplicationInfo(Process.FIRST_APPLICATION_UID,
                /* flags */ 0, CUSTOM_BACKUP_AGENT_NAME);

        BackupEligibilityRules eligibilityRules = getBackupEligibilityRules(
                OperationType.MIGRATION);
        boolean isEligible = eligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsEligibleForBackup_backupNotAllowedForSystemAppAndInMigration_returnsFalse()
            throws Exception {
        ApplicationInfo applicationInfo = getApplicationInfo(Process.SYSTEM_UID,
                ApplicationInfo.FLAG_SYSTEM, CUSTOM_BACKUP_AGENT_NAME);
        BackupEligibilityRules eligibilityRules = getBackupEligibilityRules(
                OperationType.MIGRATION);
        boolean isEligible = eligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    @EnableCompatChanges({BackupEligibilityRules.RESTRICT_ADB_BACKUP})
    public void appIsEligibleForBackup_adbBackupNotAllowed_returnsFalseForAdbBackup()
            throws Exception {
        ApplicationInfo applicationInfo = getApplicationInfo(Process.FIRST_APPLICATION_UID,
                /* flags */ ApplicationInfo.PRIVATE_FLAG_PRIVILEGED, CUSTOM_BACKUP_AGENT_NAME);
        BackupEligibilityRules eligibilityRules = getBackupEligibilityRules(
                OperationType.ADB_BACKUP);
        when(mPackageManager.getProperty(eq(PackageManager.PROPERTY_ALLOW_ADB_BACKUP),
                eq(TEST_PACKAGE_NAME))).thenReturn(getAdbBackupProperty(
                        /* allowAdbBackup */ false));

        boolean isEligible = eligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    @EnableCompatChanges({BackupEligibilityRules.RESTRICT_ADB_BACKUP})
    public void appIsEligibleForBackup_adbBackupAllowed_returnsTrueForAdbBackup()
            throws Exception {
        ApplicationInfo applicationInfo = getApplicationInfo(Process.FIRST_APPLICATION_UID,
                /* flags */ ApplicationInfo.PRIVATE_FLAG_PRIVILEGED, CUSTOM_BACKUP_AGENT_NAME);
        BackupEligibilityRules eligibilityRules = getBackupEligibilityRules(
                OperationType.ADB_BACKUP);
        when(mPackageManager.getProperty(eq(PackageManager.PROPERTY_ALLOW_ADB_BACKUP),
                eq(TEST_PACKAGE_NAME))).thenReturn(getAdbBackupProperty(
                /* allowAdbBackup */ true));

        boolean isEligible = eligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    @EnableCompatChanges({BackupEligibilityRules.RESTRICT_ADB_BACKUP})
    public void appIsEligibleForBackup_debuggableNonPrivilegedApp_returnsTrueForAdbBackup()
            throws Exception {
        ApplicationInfo applicationInfo = getApplicationInfo(Process.FIRST_APPLICATION_UID,
                /* flags */ ApplicationInfo.FLAG_DEBUGGABLE, CUSTOM_BACKUP_AGENT_NAME);
        BackupEligibilityRules eligibilityRules = getBackupEligibilityRules(
                OperationType.ADB_BACKUP);

        boolean isEligible = eligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    @DisableCompatChanges({BackupEligibilityRules.RESTRICT_ADB_BACKUP})
    public void appIsEligibleForBackup_allowBackupTrueBeforeS_returnsTrueForAdbBackup()
            throws Exception {
        ApplicationInfo applicationInfo = getApplicationInfo(Process.FIRST_APPLICATION_UID,
                ApplicationInfo.FLAG_ALLOW_BACKUP, CUSTOM_BACKUP_AGENT_NAME);
        BackupEligibilityRules eligibilityRules = getBackupEligibilityRules(
                OperationType.ADB_BACKUP);

        boolean isEligible = eligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    @DisableCompatChanges({BackupEligibilityRules.RESTRICT_ADB_BACKUP})
    public void appIsEligibleForBackup_allowBackupFalseBeforeS_returnsFalseForAdbBackup()
            throws Exception {
        ApplicationInfo applicationInfo = getApplicationInfo(Process.FIRST_APPLICATION_UID,
                /* flags */ 0, CUSTOM_BACKUP_AGENT_NAME);
        BackupEligibilityRules eligibilityRules = getBackupEligibilityRules(
                OperationType.ADB_BACKUP);

        boolean isEligible = eligibilityRules.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsDisabled_stateDefaultManifestEnabled_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;
        applicationInfo.enabled = true;
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

        boolean isDisabled =
                mBackupEligibilityRules.appIsDisabled(applicationInfo);

        assertThat(isDisabled).isFalse();
    }

    @Test
    public void appIsDisabled_stateDefaultManifestDisabled_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;
        applicationInfo.enabled = false;
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);


        boolean isDisabled =
                mBackupEligibilityRules.appIsDisabled(applicationInfo);

        assertThat(isDisabled).isTrue();
    }

    @Test
    public void appIsDisabled_stateEnabled_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);


        boolean isDisabled =
                mBackupEligibilityRules.appIsDisabled(applicationInfo);

        assertThat(isDisabled).isFalse();
    }

    @Test
    public void appIsDisabled_stateDisabled_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);


        boolean isDisabled =
                mBackupEligibilityRules.appIsDisabled(applicationInfo);

        assertThat(isDisabled).isTrue();
    }

    @Test
    public void appIsDisabled_stateDisabledUser_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);

        boolean isDisabled =
                mBackupEligibilityRules.appIsDisabled(applicationInfo);

        assertThat(isDisabled).isTrue();
    }

    @Test
    public void appIsDisabled_stateDisabledUntilUsed_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManagerInternal.getApplicationEnabledState(TEST_PACKAGE_NAME, mUserId))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);

        boolean isDisabled =
                mBackupEligibilityRules.appIsDisabled(applicationInfo);

        assertThat(isDisabled).isTrue();
    }

    @Test
    public void appIsStopped_returnsTrue() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_STOPPED;

        boolean isStopped = mBackupEligibilityRules.appIsStopped(applicationInfo);

        assertThat(isStopped).isTrue();
    }

    @Test
    public void appIsStopped_returnsFalse() throws Exception {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = ~ApplicationInfo.FLAG_STOPPED;

        boolean isStopped = mBackupEligibilityRules.appIsStopped(applicationInfo);

        assertThat(isStopped).isFalse();
    }

    @Test
    public void appGetsFullBackup_noCustomBackupAgent_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = null;

        boolean result = mBackupEligibilityRules.appGetsFullBackup(packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void appGetsFullBackup_withCustomBackupAgentAndFullBackupOnlyFlag_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_FULL_BACKUP_ONLY;

        boolean result = mBackupEligibilityRules.appGetsFullBackup(packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void appGetsFullBackup_withCustomBackupAgentAndWithoutFullBackupOnlyFlag_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.applicationInfo.flags = ~ApplicationInfo.FLAG_FULL_BACKUP_ONLY;

        boolean result = mBackupEligibilityRules.appGetsFullBackup(packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void appIsKeyValueOnly_noCustomBackupAgent_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = null;

        boolean result = mBackupEligibilityRules.appIsKeyValueOnly(packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void appIsKeyValueOnly_withCustomBackupAgentAndFullBackupOnlyFlag_returnsTrue()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_FULL_BACKUP_ONLY;

        boolean result = mBackupEligibilityRules.appIsKeyValueOnly(packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void appIsKeyValueOnly_withCustomBackupAgentAndWithoutFullBackupOnlyFlag_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.backupAgentName = "backup.agent";
        packageInfo.applicationInfo.flags = ~ApplicationInfo.FLAG_FULL_BACKUP_ONLY;

        boolean result = mBackupEligibilityRules.appIsKeyValueOnly(packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_targetIsNull_returnsFalse() throws Exception {
        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[] {SIGNATURE_1}, null);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_systemApplication_returnsTrue() throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;

        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[0], packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_storedSignatureNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {SIGNATURE_1},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = mBackupEligibilityRules.signaturesMatch(null, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_storedSignatureEmpty_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {SIGNATURE_1},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[0], packageInfo);

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

        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[] {SIGNATURE_1},
                packageInfo);

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

        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[] {SIGNATURE_1},
                packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_bothSignaturesNull_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.signingInfo = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = mBackupEligibilityRules.signaturesMatch(null, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_disallowsUnsignedApps_bothSignaturesEmpty_returnsFalse()
            throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = null;
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[0], packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_equalSignatures_returnsTrue() throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());
        Signature signature2Copy = new Signature(SIGNATURE_2.toByteArray());
        Signature signature3Copy = new Signature(SIGNATURE_3.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2, SIGNATURE_3},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = mBackupEligibilityRules.signaturesMatch(
                new Signature[] {signature3Copy, signature1Copy, signature2Copy}, packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_extraSignatureInTarget_returnsTrue() throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());
        Signature signature2Copy = new Signature(SIGNATURE_2.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2, SIGNATURE_3},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = mBackupEligibilityRules.signaturesMatch(
                new Signature[]{signature2Copy, signature1Copy}, packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_extraSignatureInStored_returnsFalse() throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());
        Signature signature2Copy = new Signature(SIGNATURE_2.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {signature1Copy, signature2Copy},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = mBackupEligibilityRules.signaturesMatch(
                new Signature[]{SIGNATURE_1, SIGNATURE_2, SIGNATURE_3}, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_oneNonMatchingSignature_returnsFalse() throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());
        Signature signature2Copy = new Signature(SIGNATURE_2.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {SIGNATURE_1, SIGNATURE_2, SIGNATURE_3},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        boolean result = mBackupEligibilityRules.signaturesMatch(
                new Signature[]{signature1Copy, signature2Copy, SIGNATURE_4}, packageInfo);

        assertThat(result).isFalse();
    }

    @Test
    public void signaturesMatch_singleStoredSignatureNoRotation_returnsTrue()
            throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {SIGNATURE_1},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        packageInfo.applicationInfo = new ApplicationInfo();

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(signature1Copy,
                packageInfo.packageName);

        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[] {signature1Copy},
                packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void signaturesMatch_singleStoredSignatureWithRotationAssumeDataCapability_returnsTrue()
            throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {SIGNATURE_2},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        new Signature[] {SIGNATURE_1, SIGNATURE_2}));
        packageInfo.applicationInfo = new ApplicationInfo();

        // we know signature1Copy is in history, and we want to assume it has
        // SigningDetails.CertCapabilities.INSTALLED_DATA capability
        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(signature1Copy,
                packageInfo.packageName);

        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[] {signature1Copy},
                packageInfo);

        assertThat(result).isTrue();
    }

    @Test
    public void
            signaturesMatch_singleStoredSignatureWithRotationAssumeNoDataCapability_returnsFalse()
            throws Exception {
        Signature signature1Copy = new Signature(SIGNATURE_1.toByteArray());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "test";
        packageInfo.signingInfo = new SigningInfo(
                new PackageParser.SigningDetails(
                        new Signature[] {SIGNATURE_2},
                        PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        new Signature[] {SIGNATURE_1, SIGNATURE_2}));
        packageInfo.applicationInfo = new ApplicationInfo();

        // we know signature1Copy is in history, but we want to assume it does not have
        // SigningDetails.CertCapabilities.INSTALLED_DATA capability
        doReturn(false).when(mMockPackageManagerInternal).isDataRestoreSafe(signature1Copy,
                packageInfo.packageName);

        boolean result = mBackupEligibilityRules.signaturesMatch(new Signature[] {signature1Copy},
                packageInfo);

        assertThat(result).isFalse();
    }

    private BackupEligibilityRules getBackupEligibilityRules(@OperationType int operationType) {
        return new BackupEligibilityRules(mPackageManager, mMockPackageManagerInternal, mUserId,
                operationType);
    }

    private static Signature generateSignature(byte i) {
        byte[] signatureBytes = new byte[256];
        signatureBytes[0] = i;
        return new Signature(signatureBytes);
    }

    private static ApplicationInfo getApplicationInfo(int appUid, int flags,
            String backupAgentName) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = flags;
        applicationInfo.packageName = TEST_PACKAGE_NAME;
        applicationInfo.uid = appUid;
        applicationInfo.backupAgentName = backupAgentName;
        return applicationInfo;
    }

    private static Property getAdbBackupProperty(boolean allowAdbBackup) {
        return new Property(PackageManager.PROPERTY_ALLOW_ADB_BACKUP, allowAdbBackup,
                TEST_PACKAGE_NAME, /* className */ "");
    }
}
