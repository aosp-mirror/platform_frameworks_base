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

import android.content.Intent
import android.content.pm.SuspendDialogInfo
import android.os.Binder
import android.os.PersistableBundle
import android.util.ArrayMap
import android.util.SparseArray
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.testutils.any
import com.android.server.testutils.eq
import com.android.server.testutils.nullable
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.argThat
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(JUnit4::class)
class SuspendPackageHelperTest : PackageHelperTestBase() {

    @Test
    fun setPackagesSuspended() {
        val targetPackages = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            targetPackages, true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()

        verify(pms).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper).sendPackageBroadcast(eq(Intent.ACTION_PACKAGES_SUSPENDED),
            nullable(), bundleCaptor.capture(), anyInt(), nullable(), nullable(), any(),
            nullable(), nullable(), nullable())
        verify(broadcastHelper).doSendBroadcast(eq(Intent.ACTION_MY_PACKAGE_SUSPENDED), nullable(),
            nullable(), any(), eq(TEST_PACKAGE_1), nullable(), any(), any(), nullable(), nullable())
        verify(broadcastHelper).doSendBroadcast(eq(Intent.ACTION_MY_PACKAGE_SUSPENDED), nullable(),
            nullable(), any(), eq(TEST_PACKAGE_2), nullable(), any(), any(), nullable(), nullable())

        var modifiedPackages = bundleCaptor.value.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
        assertThat(modifiedPackages).asList().containsExactly(TEST_PACKAGE_1, TEST_PACKAGE_2)
        assertThat(failedNames).isEmpty()
    }

    @Test
    fun setPackagesSuspended_emptyPackageName() {
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            null /* packageNames */, true /* suspended */, null /* appExtras */,
            null /* launcherExtras */, null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID,
            deviceOwnerUid)

        assertThat(failedNames).isNull()

        failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            arrayOfNulls(0), true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)

        assertThat(failedNames).isEmpty()
    }

    @Test
    fun setPackagesSuspended_callerIsNotAllowed() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            arrayOf(TEST_PACKAGE_2), true /* suspended */, null /* appExtras */,
            null /* launcherExtras */, null /* dialogInfo */, TEST_PACKAGE_1, TEST_USER_ID,
            Binder.getCallingUid())

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(TEST_PACKAGE_2)
    }

    @Test
    fun setPackagesSuspended_callerSuspendItself() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            arrayOf(DEVICE_OWNER_PACKAGE), true /* suspended */, null /* appExtras */,
            null /* launcherExtras */, null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID,
            deviceOwnerUid)

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(DEVICE_OWNER_PACKAGE)
    }

    @Test
    fun setPackagesSuspended_nonexistentPackage() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            arrayOf(NONEXISTENT_PACKAGE), true /* suspended */, null /* appExtras */,
            null /* launcherExtras */, null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID,
            deviceOwnerUid)

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(NONEXISTENT_PACKAGE)
    }

    @Test
    fun setPackagesSuspended_knownPackages() {
        val knownPackages = arrayOf(DEVICE_ADMIN_PACKAGE, DEFAULT_HOME_PACKAGE, DIALER_PACKAGE,
            INSTALLER_PACKAGE, UNINSTALLER_PACKAGE, VERIFIER_PACKAGE, PERMISSION_CONTROLLER_PACKAGE)
        val failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            knownPackages, true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(failedNames.size).isEqualTo(knownPackages.size)
        for (pkg in knownPackages) {
            assertThat(failedNames).asList().contains(pkg)
        }
    }

    @Test
    fun setPackagesUnsuspended() {
        val targetPackages = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            targetPackages, true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()
        failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            targetPackages, false /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()

        verify(pms, times(2)).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper).sendPackageBroadcast(eq(Intent.ACTION_PACKAGES_UNSUSPENDED),
            nullable(), bundleCaptor.capture(), anyInt(), nullable(), nullable(), any(),
            nullable(), nullable(), nullable())
        verify(broadcastHelper).doSendBroadcast(eq(Intent.ACTION_MY_PACKAGE_UNSUSPENDED),
            nullable(), nullable(), any(), eq(TEST_PACKAGE_1), nullable(), any(), any(),
            nullable(), nullable())
        verify(broadcastHelper).doSendBroadcast(eq(Intent.ACTION_MY_PACKAGE_UNSUSPENDED),
            nullable(), nullable(), any(), eq(TEST_PACKAGE_2), nullable(), any(), any(),
            nullable(), nullable())

        var modifiedPackages = bundleCaptor.value.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
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
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        val result = suspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
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
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()
        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isEqualTo(DEVICE_OWNER_PACKAGE)
        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(DEVICE_OWNER_PACKAGE)
        assertThat(suspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNotNull()
        assertThat(suspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNotNull()

        suspendPackageHelper.removeSuspensionsBySuspendingPackage(pms.snapshotComputer(),
            targetPackages, { suspendingPackage -> suspendingPackage == DEVICE_OWNER_PACKAGE },
            TEST_USER_ID)

        testHandler.flush()
        verify(pms, times(2)).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper).sendPackageBroadcast(eq(Intent.ACTION_PACKAGES_UNSUSPENDED),
            nullable(), bundleCaptor.capture(), anyInt(), nullable(), nullable(), any(),
            nullable(), nullable(), nullable())
        verify(broadcastHelper).doSendBroadcast(eq(Intent.ACTION_MY_PACKAGE_UNSUSPENDED),
            nullable(), nullable(), any(), eq(TEST_PACKAGE_1), nullable(), any(), any(),
            nullable(), nullable())
        verify(broadcastHelper).doSendBroadcast(eq(Intent.ACTION_MY_PACKAGE_UNSUSPENDED),
            nullable(), nullable(), any(), eq(TEST_PACKAGE_2), nullable(), any(), any(),
            nullable(), nullable())

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(suspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(suspendPackageHelper.getSuspendedPackageAppExtras(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNull()
    }

    @Test
    fun getSuspendedPackageLauncherExtras() {
        val launcherExtras = PersistableBundle()
        launcherExtras.putString(TEST_PACKAGE_2, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            arrayOf(TEST_PACKAGE_2), true /* suspended */, null /* appExtras */, launcherExtras,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
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
            null /* launcherExtras */, null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID,
            deviceOwnerUid)
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
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        assertThat(suspendPackageHelper.getSuspendingPackage(pms.snapshotComputer(),
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(DEVICE_OWNER_PACKAGE)
    }

    @Test
    fun getSuspendedDialogInfo() {
        val dialogInfo = SuspendDialogInfo.Builder()
            .setTitle(TEST_PACKAGE_1).build()
        var failedNames = suspendPackageHelper.setPackagesSuspended(pms.snapshotComputer(),
            arrayOf(TEST_PACKAGE_1), true /* suspended */, null /* appExtras */,
            null /* launcherExtras */, dialogInfo, DEVICE_OWNER_PACKAGE, TEST_USER_ID,
            deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        val result = suspendPackageHelper.getSuspendedDialogInfo(pms.snapshotComputer(),
            TEST_PACKAGE_1, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(result.title).isEqualTo(TEST_PACKAGE_1)
    }

    @Test
    @Throws(Exception::class)
    fun sendPackagesSuspendedForUser_withSameVisibilityAllowList() {
        mockAllowList(packageSetting1, allowList(10001, 10002, 10003))
        mockAllowList(packageSetting2, allowList(10001, 10002, 10003))

        suspendPackageHelper.sendPackagesSuspendedForUser(pms.snapshotComputer(),
            Intent.ACTION_PACKAGES_SUSPENDED, packagesToChange, uidsToChange, TEST_USER_ID)
        testHandler.flush()
        verify(broadcastHelper).sendPackageBroadcast(any(), nullable(), bundleCaptor.capture(),
                anyInt(), nullable(), nullable(), any(), nullable(), any(), nullable())

        var changedPackages = bundleCaptor.value.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
        var changedUids = bundleCaptor.value.getIntArray(Intent.EXTRA_CHANGED_UID_LIST)
        assertThat(changedPackages).asList().containsExactly(TEST_PACKAGE_1, TEST_PACKAGE_2)
        assertThat(changedUids).asList().containsExactly(
                packageSetting1.appId, packageSetting2.appId)
    }

    @Test
    @Throws(Exception::class)
    fun sendPackagesSuspendedForUser_withDifferentVisibilityAllowList() {
        mockAllowList(packageSetting1, allowList(10001, 10002, 10003))
        mockAllowList(packageSetting2, allowList(10001, 10002, 10007))

        suspendPackageHelper.sendPackagesSuspendedForUser(pms.snapshotComputer(),
            Intent.ACTION_PACKAGES_SUSPENDED, packagesToChange, uidsToChange, TEST_USER_ID)
        testHandler.flush()
        verify(broadcastHelper, times(2)).sendPackageBroadcast(
                any(), nullable(), bundleCaptor.capture(), anyInt(), nullable(), nullable(), any(),
                nullable(), any(), nullable())

        bundleCaptor.allValues.forEach {
            var changedPackages = it.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
            var changedUids = it.getIntArray(Intent.EXTRA_CHANGED_UID_LIST)
            assertThat(changedPackages?.size).isEqualTo(1)
            assertThat(changedUids?.size).isEqualTo(1)
            assertThat(changedPackages?.get(0)).isAnyOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
            assertThat(changedUids?.get(0)).isAnyOf(packageSetting1.appId, packageSetting2.appId)
        }
    }

    @Test
    @Throws(Exception::class)
    fun sendPackagesSuspendedForUser_withNullVisibilityAllowList() {
        mockAllowList(packageSetting1, allowList(10001, 10002, 10003))
        mockAllowList(packageSetting2, null)

        suspendPackageHelper.sendPackagesSuspendedForUser(pms.snapshotComputer(),
            Intent.ACTION_PACKAGES_SUSPENDED, packagesToChange, uidsToChange, TEST_USER_ID)
        testHandler.flush()
        verify(broadcastHelper, times(2)).sendPackageBroadcast(
                any(), nullable(), bundleCaptor.capture(), anyInt(), nullable(), nullable(), any(),
                nullable(), nullable(), nullable())

        bundleCaptor.allValues.forEach {
            var changedPackages = it.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
            var changedUids = it.getIntArray(Intent.EXTRA_CHANGED_UID_LIST)
            assertThat(changedPackages?.size).isEqualTo(1)
            assertThat(changedUids?.size).isEqualTo(1)
            assertThat(changedPackages?.get(0)).isAnyOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
            assertThat(changedUids?.get(0)).isAnyOf(packageSetting1.appId, packageSetting2.appId)
        }
    }

    @Test
    @Throws(Exception::class)
    fun sendPackagesSuspendModifiedForUser() {
        suspendPackageHelper.sendPackagesSuspendedForUser(pms.snapshotComputer(),
            Intent.ACTION_PACKAGES_SUSPENSION_CHANGED, packagesToChange, uidsToChange,
            TEST_USER_ID)
        testHandler.flush()
        verify(broadcastHelper).sendPackageBroadcast(
                eq(Intent.ACTION_PACKAGES_SUSPENSION_CHANGED), nullable(), bundleCaptor.capture(),
                anyInt(), nullable(), nullable(), any(), nullable(), nullable(), nullable())

        var modifiedPackages = bundleCaptor.value.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
        var modifiedUids = bundleCaptor.value.getIntArray(Intent.EXTRA_CHANGED_UID_LIST)
        assertThat(modifiedPackages).asList().containsExactly(TEST_PACKAGE_1, TEST_PACKAGE_2)
        assertThat(modifiedUids).asList().containsExactly(
                packageSetting1.appId, packageSetting2.appId)
    }

    private fun allowList(vararg uids: Int) = SparseArray<IntArray>().apply {
        this.put(TEST_USER_ID, uids)
    }

    private fun mockAllowList(pkgSetting: PackageStateInternal, list: SparseArray<IntArray>?) {
        whenever(rule.mocks().appsFilter.getVisibilityAllowList(
            argThat { it?.packageName == pkgSetting.packageName }, any(IntArray::class.java),
            any() as ArrayMap<String, out PackageStateInternal>
        ))
            .thenReturn(list)
    }
}
