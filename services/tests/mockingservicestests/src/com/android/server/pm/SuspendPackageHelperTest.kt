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
import android.content.pm.PackageManagerInternal
import android.content.pm.SuspendDialogInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.UserHandle
import android.os.UserManager
import android.util.ArrayMap
import android.util.SparseArray
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.testutils.TestHandler
import com.android.server.testutils.any
import com.android.server.testutils.eq
import com.android.server.testutils.nullable
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.argThat
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
class SuspendPackageHelperTest {

    companion object {
        const val TEST_PACKAGE_1 = "com.android.test.package1"
        const val TEST_PACKAGE_2 = "com.android.test.package2"
        const val DEVICE_OWNER_PACKAGE = "com.android.test.owner"
        const val NONEXISTENT_PACKAGE = "com.android.test.nonexistent"
        const val DEVICE_ADMIN_PACKAGE = "com.android.test.known.device.admin"
        const val DEFAULT_HOME_PACKAGE = "com.android.test.known.home"
        const val DIALER_PACKAGE = "com.android.test.known.dialer"
        const val INSTALLER_PACKAGE = "com.android.test.known.installer"
        const val UNINSTALLER_PACKAGE = "com.android.test.known.uninstaller"
        const val VERIFIER_PACKAGE = "com.android.test.known.verifier"
        const val PERMISSION_CONTROLLER_PACKAGE = "com.android.test.known.permission"
        const val TEST_USER_ID = 0
    }

    lateinit var pms: PackageManagerService
    lateinit var suspendPackageHelper: SuspendPackageHelper
    lateinit var testHandler: TestHandler
    lateinit var defaultAppProvider: DefaultAppProvider
    lateinit var packageSetting1: PackageStateInternal
    lateinit var packageSetting2: PackageStateInternal
    lateinit var ownerSetting: PackageStateInternal
    lateinit var packagesToSuspend: Array<String>
    lateinit var uidsToSuspend: IntArray

    @Mock
    lateinit var broadcastHelper: BroadcastHelper
    @Mock
    lateinit var protectedPackages: ProtectedPackages

    @Captor
    lateinit var bundleCaptor: ArgumentCaptor<Bundle>

    @Rule
    @JvmField
    val rule = MockSystemRule()
    var deviceOwnerUid = 0

    @Before
    @Throws(Exception::class)
    fun setup() {
        MockitoAnnotations.initMocks(this)
        rule.system().stageNominalSystemState()
        pms = spy(createPackageManagerService(
            TEST_PACKAGE_1, TEST_PACKAGE_2, DEVICE_OWNER_PACKAGE, DEVICE_ADMIN_PACKAGE,
            DEFAULT_HOME_PACKAGE, DIALER_PACKAGE, INSTALLER_PACKAGE, UNINSTALLER_PACKAGE,
            VERIFIER_PACKAGE, PERMISSION_CONTROLLER_PACKAGE))
        suspendPackageHelper = SuspendPackageHelper(
            pms, rule.mocks().injector, broadcastHelper, protectedPackages)
        defaultAppProvider = rule.mocks().defaultAppProvider
        testHandler = rule.mocks().handler
        packageSetting1 = pms.getPackageStateInternal(TEST_PACKAGE_1)!!
        packageSetting2 = pms.getPackageStateInternal(TEST_PACKAGE_2)!!
        ownerSetting = pms.getPackageStateInternal(DEVICE_OWNER_PACKAGE)!!
        deviceOwnerUid = UserHandle.getUid(TEST_USER_ID, ownerSetting.appId)
        packagesToSuspend = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        uidsToSuspend = intArrayOf(packageSetting1.appId, packageSetting2.appId)

        whenever(protectedPackages.getDeviceOwnerOrProfileOwnerPackage(eq(TEST_USER_ID)))
            .thenReturn(DEVICE_OWNER_PACKAGE)
        whenever(rule.mocks().userManagerService.hasUserRestriction(
            eq(UserManager.DISALLOW_APPS_CONTROL), eq(TEST_USER_ID))).thenReturn(true)
        whenever(rule.mocks().userManagerService.hasUserRestriction(
            eq(UserManager.DISALLOW_UNINSTALL_APPS), eq(TEST_USER_ID))).thenReturn(true)
        mockKnownPackages(pms)
    }

    @Test
    fun setPackagesSuspended() {
        val targetPackages = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        val failedNames = suspendPackageHelper.setPackagesSuspended(targetPackages,
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
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
        var failedNames = suspendPackageHelper.setPackagesSuspended(null /* packageNames */,
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)

        assertThat(failedNames).isNull()

        failedNames = suspendPackageHelper.setPackagesSuspended(arrayOfNulls(0),
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)

        assertThat(failedNames).isEmpty()
    }

    @Test
    fun setPackagesSuspended_callerIsNotAllowed() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(arrayOf(TEST_PACKAGE_2),
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, TEST_PACKAGE_1, TEST_USER_ID, Binder.getCallingUid())

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(TEST_PACKAGE_2)
    }

    @Test
    fun setPackagesSuspended_callerSuspendItself() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(arrayOf(DEVICE_OWNER_PACKAGE),
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(DEVICE_OWNER_PACKAGE)
    }

    @Test
    fun setPackagesSuspended_nonexistentPackage() {
        val failedNames = suspendPackageHelper.setPackagesSuspended(arrayOf(NONEXISTENT_PACKAGE),
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)

        assertThat(failedNames).asList().hasSize(1)
        assertThat(failedNames).asList().contains(NONEXISTENT_PACKAGE)
    }

    @Test
    fun setPackagesSuspended_knownPackages() {
        val knownPackages = arrayOf(DEVICE_ADMIN_PACKAGE, DEFAULT_HOME_PACKAGE, DIALER_PACKAGE,
            INSTALLER_PACKAGE, UNINSTALLER_PACKAGE, VERIFIER_PACKAGE, PERMISSION_CONTROLLER_PACKAGE)
        val failedNames = suspendPackageHelper.setPackagesSuspended(knownPackages,
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(failedNames.size).isEqualTo(knownPackages.size)
        for (pkg in knownPackages) {
            assertThat(failedNames).asList().contains(pkg)
        }
    }

    @Test
    fun setPackagesUnsuspended() {
        val targetPackages = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(targetPackages,
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()
        failedNames = suspendPackageHelper.setPackagesSuspended(targetPackages,
            false /* suspended */, null /* appExtras */, null /* launcherExtras */,
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
        val results = suspendPackageHelper.getUnsuspendablePackagesForUser(
            suspendables + unsuspendables, TEST_USER_ID, deviceOwnerUid)

        assertThat(results.size).isEqualTo(unsuspendables.size)
        for (pkg in unsuspendables) {
            assertThat(results).asList().contains(pkg)
        }
    }

    @Test
    fun getUnsuspendablePackagesForUser_callerIsNotAllowed() {
        val suspendables = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        val results = suspendPackageHelper.getUnsuspendablePackagesForUser(
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
        var failedNames = suspendPackageHelper.setPackagesSuspended(arrayOf(TEST_PACKAGE_1),
            true /* suspended */, appExtras, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        val result = suspendPackageHelper.getSuspendedPackageAppExtras(
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(result.getString(TEST_PACKAGE_1)).isEqualTo(TEST_PACKAGE_1)
    }

    @Test
    fun removeSuspensionsBySuspendingPackage() {
        val appExtras = PersistableBundle()
        appExtras.putString(TEST_PACKAGE_1, TEST_PACKAGE_2)
        val targetPackages = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(targetPackages,
            true /* suspended */, appExtras, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()
        assertThat(suspendPackageHelper.getSuspendingPackage(
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isEqualTo(DEVICE_OWNER_PACKAGE)
        assertThat(suspendPackageHelper.getSuspendingPackage(
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(DEVICE_OWNER_PACKAGE)
        assertThat(suspendPackageHelper.getSuspendedPackageAppExtras(
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNotNull()
        assertThat(suspendPackageHelper.getSuspendedPackageAppExtras(
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNotNull()

        suspendPackageHelper.removeSuspensionsBySuspendingPackage(targetPackages,
            { suspendingPackage -> suspendingPackage == DEVICE_OWNER_PACKAGE }, TEST_USER_ID)

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

        assertThat(suspendPackageHelper.getSuspendingPackage(
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(suspendPackageHelper.getSuspendingPackage(
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(suspendPackageHelper.getSuspendedPackageAppExtras(
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isNull()
        assertThat(suspendPackageHelper.getSuspendedPackageAppExtras(
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isNull()
    }

    @Test
    fun getSuspendedPackageLauncherExtras() {
        val launcherExtras = PersistableBundle()
        launcherExtras.putString(TEST_PACKAGE_2, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(arrayOf(TEST_PACKAGE_2),
            true /* suspended */, null /* appExtras */, launcherExtras,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        val result = suspendPackageHelper.getSuspendedPackageLauncherExtras(
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(result.getString(TEST_PACKAGE_2)).isEqualTo(TEST_PACKAGE_2)
    }

    @Test
    fun isPackageSuspended() {
        var failedNames = suspendPackageHelper.setPackagesSuspended(arrayOf(TEST_PACKAGE_1),
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        assertThat(suspendPackageHelper.isPackageSuspended(
            TEST_PACKAGE_1, TEST_USER_ID, deviceOwnerUid)).isTrue()
    }

    @Test
    fun getSuspendingPackage() {
        val launcherExtras = PersistableBundle()
        launcherExtras.putString(TEST_PACKAGE_2, TEST_PACKAGE_2)
        var failedNames = suspendPackageHelper.setPackagesSuspended(arrayOf(TEST_PACKAGE_2),
            true /* suspended */, null /* appExtras */, launcherExtras,
            null /* dialogInfo */, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        assertThat(suspendPackageHelper.getSuspendingPackage(
            TEST_PACKAGE_2, TEST_USER_ID, deviceOwnerUid)).isEqualTo(DEVICE_OWNER_PACKAGE)
    }

    @Test
    fun getSuspendedDialogInfo() {
        val dialogInfo = SuspendDialogInfo.Builder()
            .setTitle(TEST_PACKAGE_1).build()
        var failedNames = suspendPackageHelper.setPackagesSuspended(arrayOf(TEST_PACKAGE_1),
            true /* suspended */, null /* appExtras */, null /* launcherExtras */,
            dialogInfo, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        assertThat(failedNames).isEmpty()

        val result = suspendPackageHelper.getSuspendedDialogInfo(
            TEST_PACKAGE_1, DEVICE_OWNER_PACKAGE, TEST_USER_ID, deviceOwnerUid)!!

        assertThat(result.title).isEqualTo(TEST_PACKAGE_1)
    }

    @Test
    @Throws(Exception::class)
    fun sendPackagesSuspendedForUser_withSameVisibilityAllowList() {
        mockAllowList(packageSetting1, allowList(10001, 10002, 10003))
        mockAllowList(packageSetting2, allowList(10001, 10002, 10003))

        suspendPackageHelper.sendPackagesSuspendedForUser(Intent.ACTION_PACKAGES_SUSPENDED,
                packagesToSuspend, uidsToSuspend, TEST_USER_ID)
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

        suspendPackageHelper.sendPackagesSuspendedForUser(Intent.ACTION_PACKAGES_SUSPENDED,
                packagesToSuspend, uidsToSuspend, TEST_USER_ID)
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

        suspendPackageHelper.sendPackagesSuspendedForUser(Intent.ACTION_PACKAGES_SUSPENDED,
                packagesToSuspend, uidsToSuspend, TEST_USER_ID)
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
        suspendPackageHelper.sendPackagesSuspendedForUser(Intent.ACTION_PACKAGES_SUSPENSION_CHANGED,
                packagesToSuspend, uidsToSuspend, TEST_USER_ID)
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

    private fun mockKnownPackages(pms: PackageManagerService) {
        Mockito.doAnswer { it.arguments[0] == DEVICE_ADMIN_PACKAGE }.`when`(pms)
            .isPackageDeviceAdmin(any(), any())
        Mockito.doReturn(DEFAULT_HOME_PACKAGE).`when`(defaultAppProvider)
            .getDefaultHome(eq(TEST_USER_ID))
        Mockito.doReturn(DIALER_PACKAGE).`when`(defaultAppProvider)
            .getDefaultDialer(eq(TEST_USER_ID))
        Mockito.doReturn(arrayOf(INSTALLER_PACKAGE)).`when`(pms).getKnownPackageNamesInternal(
            eq(PackageManagerInternal.PACKAGE_INSTALLER), eq(TEST_USER_ID))
        Mockito.doReturn(arrayOf(UNINSTALLER_PACKAGE)).`when`(pms).getKnownPackageNamesInternal(
            eq(PackageManagerInternal.PACKAGE_UNINSTALLER), eq(TEST_USER_ID))
        Mockito.doReturn(arrayOf(VERIFIER_PACKAGE)).`when`(pms).getKnownPackageNamesInternal(
            eq(PackageManagerInternal.PACKAGE_VERIFIER), eq(TEST_USER_ID))
        Mockito.doReturn(arrayOf(PERMISSION_CONTROLLER_PACKAGE)).`when`(pms)
            .getKnownPackageNamesInternal(
                eq(PackageManagerInternal.PACKAGE_PERMISSION_CONTROLLER), eq(TEST_USER_ID))
    }

    private fun createPackageManagerService(vararg stageExistingPackages: String):
            PackageManagerService {
        stageExistingPackages.forEach {
            rule.system().stageScanExistingPackage(it, 1L,
                    rule.system().dataAppDirectory)
        }
        var pms = PackageManagerService(rule.mocks().injector,
                false /*coreOnly*/,
                false /*factoryTest*/,
                MockSystem.DEFAULT_VERSION_INFO.fingerprint,
                false /*isEngBuild*/,
                false /*isUserDebugBuild*/,
                Build.VERSION_CODES.CUR_DEVELOPMENT,
                Build.VERSION.INCREMENTAL)
        rule.system().validateFinalState()
        return pms
    }
}
