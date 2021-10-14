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
import android.os.Build
import android.os.Bundle
import android.util.SparseArray
import com.android.server.testutils.any
import com.android.server.testutils.eq
import com.android.server.testutils.nullable
import com.android.server.testutils.whenever
import com.android.server.utils.WatchedArrayMap
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
class SuspendPackagesBroadcastTest {

    companion object {
        const val TEST_PACKAGE_1 = "com.android.test.package1"
        const val TEST_PACKAGE_2 = "com.android.test.package2"
        const val TEST_USER_ID = 0
    }

    lateinit var pms: PackageManagerService
    lateinit var packageSetting1: PackageSetting
    lateinit var packageSetting2: PackageSetting
    lateinit var packagesToSuspend: Array<String>
    lateinit var uidsToSuspend: IntArray

    @Captor
    lateinit var bundleCaptor: ArgumentCaptor<Bundle>

    @Rule
    @JvmField
    val rule = MockSystemRule()

    @Before
    @Throws(Exception::class)
    fun setup() {
        MockitoAnnotations.initMocks(this)
        rule.system().stageNominalSystemState()
        pms = spy(createPackageManagerService(TEST_PACKAGE_1, TEST_PACKAGE_2))
        packageSetting1 = pms.getPackageSetting(TEST_PACKAGE_1)!!
        packageSetting2 = pms.getPackageSetting(TEST_PACKAGE_2)!!
        packagesToSuspend = arrayOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
        uidsToSuspend = intArrayOf(packageSetting1.appId, packageSetting2.appId)
    }

    @Test
    @Throws(Exception::class)
    fun sendPackagesSuspendedForUser_withSameVisibilityAllowList() {
        mockAllowList(packageSetting1, allowList(10001, 10002, 10003))
        mockAllowList(packageSetting2, allowList(10001, 10002, 10003))

        pms.sendPackagesSuspendedForUser(
                packagesToSuspend, uidsToSuspend, TEST_USER_ID, /* suspended = */ true)
        verify(pms).sendPackageBroadcast(any(), nullable(), bundleCaptor.capture(),
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

        pms.sendPackagesSuspendedForUser(
                packagesToSuspend, uidsToSuspend, TEST_USER_ID, /* suspended = */ true)
        verify(pms, times(2)).sendPackageBroadcast(any(), nullable(), bundleCaptor.capture(),
                anyInt(), nullable(), nullable(), any(), nullable(), any(), nullable())

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

        pms.sendPackagesSuspendedForUser(
                packagesToSuspend, uidsToSuspend, TEST_USER_ID, /* suspended = */ true)
        verify(pms, times(2)).sendPackageBroadcast(any(), nullable(), bundleCaptor.capture(),
                anyInt(), nullable(), nullable(), any(), nullable(), nullable(), nullable())

        bundleCaptor.allValues.forEach {
            var changedPackages = it.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
            var changedUids = it.getIntArray(Intent.EXTRA_CHANGED_UID_LIST)
            assertThat(changedPackages?.size).isEqualTo(1)
            assertThat(changedUids?.size).isEqualTo(1)
            assertThat(changedPackages?.get(0)).isAnyOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
            assertThat(changedUids?.get(0)).isAnyOf(packageSetting1.appId, packageSetting2.appId)
        }
    }

    private fun allowList(vararg uids: Int) = SparseArray<IntArray>().apply {
        this.put(TEST_USER_ID, uids)
    }

    private fun mockAllowList(pkgSetting: PackageSetting, list: SparseArray<IntArray>?) {
        whenever(rule.mocks().injector.appsFilter.getVisibilityAllowList(eq(pkgSetting),
                any(IntArray::class.java), any() as WatchedArrayMap<String, PackageSetting>))
                .thenReturn(list)
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
