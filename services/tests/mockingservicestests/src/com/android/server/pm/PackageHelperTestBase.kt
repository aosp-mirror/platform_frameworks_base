/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.pm.PackageManagerInternal
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.testutils.TestHandler
import com.android.server.testutils.any
import com.android.server.testutils.eq
import com.android.server.testutils.whenever
import org.junit.Before
import org.junit.Rule
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.spy
import org.mockito.MockitoAnnotations

open class PackageHelperTestBase {

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
    lateinit var packagesToChange: Array<String>
    lateinit var uidsToChange: IntArray

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
    open fun setup() {
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
        packageSetting1 = pms.snapshotComputer().getPackageStateInternal(TEST_PACKAGE_1)!!
        packageSetting2 = pms.snapshotComputer().getPackageStateInternal(TEST_PACKAGE_2)!!
        ownerSetting = pms.snapshotComputer().getPackageStateInternal(DEVICE_OWNER_PACKAGE)!!
        deviceOwnerUid = UserHandle.getUid(TEST_USER_ID, ownerSetting.appId)
        packagesToChange = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        uidsToChange = intArrayOf(packageSetting1.appId, packageSetting2.appId)

        whenever(protectedPackages.getDeviceOwnerOrProfileOwnerPackage(eq(TEST_USER_ID)))
                .thenReturn(DEVICE_OWNER_PACKAGE)
        whenever(rule.mocks().userManagerService.hasUserRestriction(
                eq(UserManager.DISALLOW_APPS_CONTROL), eq(TEST_USER_ID))).thenReturn(true)
        whenever(rule.mocks().userManagerService.hasUserRestriction(
                eq(UserManager.DISALLOW_UNINSTALL_APPS), eq(TEST_USER_ID))).thenReturn(true)
        mockKnownPackages(pms)
    }

    private fun mockKnownPackages(pms: PackageManagerService) {
        Mockito.doAnswer { it.arguments[0] == DEVICE_ADMIN_PACKAGE }.`when`(pms)
                .isPackageDeviceAdmin(any(), any())
        Mockito.doReturn(DEFAULT_HOME_PACKAGE).`when`(defaultAppProvider)
                .getDefaultHome(eq(TEST_USER_ID))
        Mockito.doReturn(DIALER_PACKAGE).`when`(defaultAppProvider)
                .getDefaultDialer(eq(TEST_USER_ID))
        Mockito.doReturn(arrayOf(INSTALLER_PACKAGE)).`when`(pms).getKnownPackageNamesInternal(
                any(), eq(PackageManagerInternal.PACKAGE_INSTALLER), eq(TEST_USER_ID))
        Mockito.doReturn(arrayOf(UNINSTALLER_PACKAGE)).`when`(pms).getKnownPackageNamesInternal(
                any(), eq(PackageManagerInternal.PACKAGE_UNINSTALLER), eq(TEST_USER_ID))
        Mockito.doReturn(arrayOf(VERIFIER_PACKAGE)).`when`(pms).getKnownPackageNamesInternal(
                any(), eq(PackageManagerInternal.PACKAGE_VERIFIER), eq(TEST_USER_ID))
        Mockito.doReturn(arrayOf(PERMISSION_CONTROLLER_PACKAGE)).`when`(pms)
                .getKnownPackageNamesInternal(any(),
                        eq(PackageManagerInternal.PACKAGE_PERMISSION_CONTROLLER), eq(TEST_USER_ID))
    }

    private fun createPackageManagerService(vararg stageExistingPackages: String):
            PackageManagerService {
        stageExistingPackages.forEach {
            rule.system().stageScanExistingPackage(it, 1L,
                    rule.system().dataAppDirectory)
        }
        var pms = PackageManagerService(rule.mocks().injector,
                false /* coreOnly */,
                false /* factoryTest */,
                MockSystem.DEFAULT_VERSION_INFO.fingerprint,
                false /* isEngBuild */,
                false /* isUserDebugBuild */,
                Build.VERSION_CODES.CUR_DEVELOPMENT,
                Build.VERSION.INCREMENTAL)
        rule.system().validateFinalState()
        return pms
    }
}