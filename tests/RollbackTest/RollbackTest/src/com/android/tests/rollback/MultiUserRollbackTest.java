/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tests.rollback;

import static com.android.cts.rollback.lib.RollbackInfoSubject.assertThat;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.rollback.lib.Rollback;
import com.android.cts.rollback.lib.RollbackUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


@RunWith(JUnit4.class)
public class MultiUserRollbackTest {

    @Before
    public void adoptShellPermissions() {
        InstallUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.DELETE_PACKAGES,
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                Manifest.permission.MANAGE_ROLLBACKS);
    }

    @After
    public void dropShellPermissions() {
        InstallUtils.dropShellPermissionIdentity();
    }

    @Test
    public void cleanUp() {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        rm.getAvailableRollbacks().stream().flatMap(info -> info.getPackages().stream())
                .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
        rm.getRecentlyCommittedRollbacks().stream().flatMap(info -> info.getPackages().stream())
                .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
        assertThat(rm.getAvailableRollbacks()).isEmpty();
        assertThat(rm.getRecentlyCommittedRollbacks()).isEmpty();
    }

    @Test
    public void testBasic() throws Exception {
        new RollbackTest().testBasic();
    }

    /**
     * Install version 1 of the test app. This method is run for both users.
     */
    @Test
    public void testMultipleUsersInstallV1() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);
        Install.single(TestApp.A1).commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        InstallUtils.processUserData(TestApp.A);
    }

    /**
     * Upgrade the test app to version 2. This method should only run once as the system user,
     * and will update the app for both users.
     */
    @Test
    public void testMultipleUsersUpgradeToV2() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        Install.single(TestApp.A2).setEnableRollback().commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        RollbackInfo rollback = RollbackUtils.waitForAvailableRollback(TestApp.A);
        assertThat(rollback).packagesContainsExactly(
                Rollback.from(TestApp.A2).to(TestApp.A1));
    }

    /**
     * This method is run for both users. Assert that the test app has upgraded for both users, and
     * update their userdata to reflect this new version.
     */
    @Test
    public void testMultipleUsersUpdateUserData() {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        InstallUtils.processUserData(TestApp.A);
    }

    /**
     * The system will have rolled back the test app at this stage. Verify that the rollback has
     * taken place, and that the userdata has been correctly rolled back. This method is run for
     * both users.
     */
    @Test
    public void testMultipleUsersVerifyUserdataRollback() {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        InstallUtils.processUserData(TestApp.A);
    }

    @Test
    public void testStagedRollback_Phase1() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);
        Install.single(TestApp.A1).setStaged().commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);
    }

    @Test
    public void testStagedRollback_Phase2() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        Install.single(TestApp.A2).setStaged().setEnableRollback().commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
    }

    @Test
    public void testStagedRollback_Phase3() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        RollbackInfo rollback = RollbackUtils.waitForAvailableRollback(TestApp.A);
        assertThat(rollback).packagesContainsExactly(Rollback.from(TestApp.A2).to(TestApp.A1));
        RollbackUtils.rollback(rollback.getRollbackId());
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
    }

    @Test
    public void testStagedRollback_Phase4() {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
    }
}
