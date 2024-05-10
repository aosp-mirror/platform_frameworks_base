/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm

import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.SuspendDialogInfo
import android.content.pm.UserPackage
import android.os.Binder
import android.os.PersistableBundle
import com.android.server.testutils.any
import com.android.server.testutils.eq
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(JUnit4::class)
class SuspendPackageHelperTest : PackageHelperTestBase() {
    override fun setup() {
        super.setup()
        whenever(rule.mocks().appOpsManager.checkOpNoThrow(
                eq(AppOpsManager.OP_SYSTEM_EXEMPT_FROM_SUSPENSION), any(), any()))
                .thenReturn(AppOpsManager.MODE_DEFAULT)
    }

    companion object {
        val doUserPackage = UserPackage.of(TEST_USER_ID, DEVICE_OWNER_PACKAGE)
        val platformUserPackage = UserPackage.of(TEST_USER_ID, PLATFORM_PACKAGE_NAME)
        val testUserPackage1 = UserPackage.of(TEST_USER_ID, TEST_PACKAGE_1)
    }

    @Test
    fun setPackagesSuspended() {
        val targetPackages = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                targetPackages, true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)
        testHandler.flush()

        verify(pms).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper).sendPackagesSuspendedOrUnsuspendedForUser(any(Computer::class.java),
            eq(Intent.ACTION_PACKAGES_SUSPENDED), pkgListCaptor.capture(), any(), any(), any())

        var modifiedPackages = pkgListCaptor.value
        assertThat(modifiedPackages).asList().containsExactly(TEST_PACKAGE_1, TEST_PACKAGE_2)
        assertThat(failedNames).isEmpty()
    }

    @Test
    fun setPackagesSuspended_emptyPackageName() {
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                null /* packageNames */, true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)

        assertThat(failedNames).isNull()

        failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOfNulls(0), true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)

        assertThat(failedNames).isEmpty()
    }

    @Test
    fun setPackagesSuspended_callerIsNotAllowed() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOf(TEST_PACKAGE_2), true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */,
                testUserPackage1, TEST_USER_ID,
                Binder.getCallingUid(), false /* quarantined */)

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(TEST_PACKAGE_2)
    }

    @Test
    fun setPackagesSuspended_callerSuspendItself() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOf(DEVICE_OWNER_PACKAGE), true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(DEVICE_OWNER_PACKAGE)
    }

    @Test
    fun setPackagesSuspended_nonexistentPackage() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOf(NONEXISTENT_PACKAGE), true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(NONEXISTENT_PACKAGE)
    }

    @Test
    fun setPackagesSuspended_knownPackages() {
        val knownPackages = arrayOf(DEVICE_ADMIN_PACKAGE, DEFAULT_HOME_PACKAGE, DIALER_PACKAGE,
            INSTALLER_PACKAGE, UNINSTALLER_PACKAGE, VERIFIER_PACKAGE, PERMISSION_CONTROLLER_PACKAGE)
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                knownPackages, true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)!!

        assertThat(failedNames.size).isEqualTo(knownPackages.size)
        for (pkg in knownPackages) {
            assertThat(failedNames).asList().contains(pkg)
        }
    }

    @Test
    fun setPackagesUnsuspended() {
        val targetPackages = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                targetPackages, true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)
        testHandler.flush()
        Mockito.clearInvocations(broadcastHelper)
        assertThat(failedNames).isEmpty()
        failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                targetPackages, false /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)
        testHandler.flush()

        verify(pms, times(2)).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper).sendPackagesSuspendedOrUnsuspendedForUser(any(Computer::class.java),
                eq(Intent.ACTION_PACKAGES_UNSUSPENDED), pkgListCaptor.capture(), any(), any(),
                any())
        verify(broadcastHelper).sendMyPackageSuspendedOrUnsuspended(any(Computer::class.java),
                any(), any(), any())

        var modifiedPackages = pkgListCaptor.value
        assertThat(modifiedPackages).asList().containsExactly(TEST_PACKAGE_1, TEST_PACKAGE_2)
        assertThat(failedNames).isEmpty()
    }

    @Test
    fun getUnsuspendablePackagesForUser() {
        val suspendables = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        val unsuspendables = arrayOf(DEVICE_ADMIN_PACKAGE, DEFAULT_HOME_PACKAGE, DIALER_PACKAGE,
            INSTALLER_PACKAGE, UNINSTALLER_PACKAGE, VERIFIER_PACKAGE, PERMISSION_CONTROLLER_PACKAGE)
        val results = suspendPackageHelper.getUnsuspendablePackagesForUser(pms.snapshotComputer(),
            suspendables + unsuspendables, TEST_USER_ID, deviceOwnerUid)

        assertThat(results.size).isEqualTo(unsuspendables.size)
        for (pkg in unsuspendables) {
            assertThat(results).asList().contains(pkg)
        }
    }

    @Test
    fun getUnsuspendablePackagesForUser_callerIsNotAllowed() {
        val suspendables = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        val results = suspendPackageHelper.getUnsuspendablePackagesForUser(pms.snapshotComputer(),
            suspendables, TEST_USER_ID, Binder.getCallingUid())

        assertThat(results.size).isEqualTo(suspendables.size)
        for (pkg in suspendables) {
            assertThat(results).asList().contains(pkg)
        }
    }

    @Test
    fun getSuspendedPackageAppExtras() {
        val appExtras = PersistableBundle()
        appExtras.putString(TEST_PACKAGE_1, TEST_PACKAGE_1)
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOf(TEST_PACKAGE_1), true /* suspended */, appExtras, null /* launcherExtras */,
                null /* dialogInfo */, doUserPackage, TEST_USER_ID, deviceOwnerUid,
                false /* quarantined */)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        val result = SuspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(result.getString(TEST_PACKAGE_1)).isEqualTo(TEST_PACKAGE_1)
    }

    @Test
    fun removeSuspensionsBySuspendingPackage() {
        val appExtras = PersistableBundle()
        appExtras.putString(TEST_PACKAGE_1, TEST_PACKAGE_2)
        val targetPackages = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                targetPackages, true /* suspended */, appExtras, null /* launcherExtras */,
                null /* dialogInfo */, doUserPackage, TEST_USER_ID, deviceOwnerUid,
                false /* quarantined */)
        testHandler.flush()
        Mockito.clearInvocations(broadcastHelper)
        assertThat(failedNames).isEmpty()
        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isEqualTo(doUserPackage)
        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(doUserPackage)
        assertThat(SuspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNotNull()
        assertThat(SuspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNotNull()

        suspendPackageHelper.removeSuspensionsBySuspendingPackage(pms.snapshotComputer(),
            targetPackages, { suspender -> suspender.packageName == DEVICE_OWNER_PACKAGE },
            TEST_USER_ID)

        testHandler.flush()
        verify(pms, times(2)).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper).sendPackagesSuspendedOrUnsuspendedForUser(any(Computer::class.java),
                eq(Intent.ACTION_PACKAGES_UNSUSPENDED), any(), any(), any(), any())
        verify(broadcastHelper).sendMyPackageSuspendedOrUnsuspended(any(Computer::class.java),
                any(), any(), any())

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(SuspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(SuspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNull()
    }

    @Test
    fun getSuspendedPackageLauncherExtras() {
        val launcherExtras = PersistableBundle()
        launcherExtras.putString(TEST_PACKAGE_2, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOf(TEST_PACKAGE_2), true /* suspended */, null /* appExtras */, launcherExtras,
                null /* dialogInfo */, doUserPackage, TEST_USER_ID, deviceOwnerUid,
                false /* quarantined */)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        val result = suspendPackageHelper.getSuspendedPackageLauncherExtras(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(result.getString(TEST_PACKAGE_2)).isEqualTo(TEST_PACKAGE_2)
    }

    @Test
    fun isPackageSuspended() {
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOf(TEST_PACKAGE_1), true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, null /* dialogInfo */, doUserPackage,
                TEST_USER_ID, deviceOwnerUid, false /* quarantined */)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        assertThat(suspendPackageHelper.isPackageSuspended(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isTrue()
    }

    @Test
    fun getSuspendingPackage() {
        val launcherExtras = PersistableBundle()
        launcherExtras.putString(TEST_PACKAGE_2, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOf(TEST_PACKAGE_2), true /* suspended */, null /* appExtras */, launcherExtras,
                null /* dialogInfo */, doUserPackage, TEST_USER_ID, deviceOwnerUid,
                false /* quarantined */)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(doUserPackage)
    }

    @Test
    fun getSuspendingPackagePrecedence() {
        val launcherExtras = PersistableBundle()
        launcherExtras.putString(TEST_PACKAGE_2, TEST_PACKAGE_2)
        val targetPackages = arrayOf(TEST_PACKAGE_2)
        // Suspend.
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                targetPackages, true /* suspended */, null /* appExtras */, launcherExtras,
                null /* dialogInfo */, doUserPackage, TEST_USER_ID, deviceOwnerUid,
                false /* quarantined */)
        assertThat(failedNames).isEmpty()
        testHandler.flush()

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
                TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(doUserPackage)

        // Suspend by system.
        failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                targetPackages, true /* suspended */, null /* appExtras */, launcherExtras,
                null /* dialogInfo */, platformUserPackage, TEST_USER_ID, deviceOwnerUid,
                false /* quarantined */)
        assertThat(failedNames).isEmpty()
        testHandler.flush()

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
                TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(platformUserPackage)

        // QAS by package1.
        failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                targetPackages, true /* suspended */, null /* appExtras */, launcherExtras,
                null /* dialogInfo */, testUserPackage1, TEST_USER_ID, deviceOwnerUid,
                true /* quarantined */)
        assertThat(failedNames).isEmpty()
        testHandler.flush()

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
                TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(testUserPackage1)

        // Un-QAS by package1.
        suspendPackageHelper.removeSuspensionsBySuspendingPackage(pms.snapshotComputer(),
                targetPackages, { suspendingPackage -> suspendingPackage == testUserPackage1 },
                TEST_USER_ID)
        testHandler.flush()

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
                TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(platformUserPackage)

        // Un-suspend by system.
        suspendPackageHelper.removeSuspensionsBySuspendingPackage(pms.snapshotComputer(),
                targetPackages, { suspender -> suspender.packageName == PLATFORM_PACKAGE_NAME },
                TEST_USER_ID)
        testHandler.flush()

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
                TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(doUserPackage)

        // Unsuspend.
        suspendPackageHelper.removeSuspensionsBySuspendingPackage(pms.snapshotComputer(),
                targetPackages, { suspendingPackage -> suspendingPackage == doUserPackage },
                TEST_USER_ID)
        testHandler.flush()

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
                TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNull()
    }

    @Test
    fun getSuspendedDialogInfo() {
        val dialogInfo = SuspendDialogInfo.Builder()
            .setTitle(TEST_PACKAGE_1).build()
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
                arrayOf(TEST_PACKAGE_1), true /* suspended */, null /* appExtras */,
                null /* launcherExtras */, dialogInfo, doUserPackage, TEST_USER_ID,
                deviceOwnerUid, false /* quarantined */)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        val result = suspendPackageHelper.getSuspendedDialogInfo(pms.snapshotComputer(),
            TEST_PACKAGE_1, doUserPackage, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(result.title).isEqualTo(TEST_PACKAGE_1)
    }
}
