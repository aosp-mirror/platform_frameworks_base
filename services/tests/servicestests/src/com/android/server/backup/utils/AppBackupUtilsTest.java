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
import android.os.Process;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.backup.RefactoredBackupManagerService;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AppBackupUtilsTest {
    private static final String CUSTOM_BACKUP_AGENT_NAME = "custom.backup.agent";
    private static final String TEST_PACKAGE_NAME = "test_package";

    @Test
    public void appIsEligibleForBackup_backupNotAllowed_returnsFalse() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = 0;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_systemAppWithoutCustomBackupAgent_returnsFalse() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = null;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_sharedStorageBackupPackage_returnsFalse() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = RefactoredBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isFalse();
    }

    @Test
    public void appIsEligibleForBackup_systemAppWithCustomBackupAgent_returnsTrue() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.SYSTEM_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsEligibleForBackup_nonSystemAppWithoutCustomBackupAgent_returnsTrue() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = null;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

    @Test
    public void appIsEligibleForBackup_nonSystemAppWithCustomBackupAgent_returnsTrue() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        applicationInfo.uid = Process.FIRST_APPLICATION_UID;
        applicationInfo.backupAgentName = CUSTOM_BACKUP_AGENT_NAME;
        applicationInfo.packageName = TEST_PACKAGE_NAME;

        boolean isEligible = AppBackupUtils.appIsEligibleForBackup(applicationInfo);

        assertThat(isEligible).isTrue();
    }

}
