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

import android.os.Build
import android.os.SystemProperties
import com.android.server.extendedtestutils.wheneverStatic
import com.android.server.testutils.spy
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class InitAppsHelperTest {
    @Rule
    @JvmField
    val rule = MockSystemRule()

    @Before
    fun setUp() {
        rule.system().stageNominalSystemState()
    }

    private fun mockNoFirstBoot() {
        // To mock that this is not the first boot
        rule.system().stageScanExistingPackage("a.data.package", 1L,
            rule.system().dataAppDirectory)
    }

    private fun mockFingerprintChanged() {
        val versionInfo = Settings.VersionInfo()
        versionInfo.fingerprint = "mockFingerprintForTesting"
        whenever(rule.mocks().settings.internalVersion) {
            versionInfo
        }
    }

    private fun mockFingerprintUnchanged() {
        // To mock that this is not the first boot
        val versionInfo = Settings.VersionInfo()
        versionInfo.fingerprint = MockSystem.DEFAULT_VERSION_INFO.fingerprint
        whenever(rule.mocks().settings.internalVersion) {
            versionInfo
        }
    }

    private fun mockOta() {
        wheneverStatic {
            SystemProperties.getBoolean("persist.pm.mock-upgrade", false)
        }.thenReturn(true)
    }

    private fun createPackageManagerService(): PackageManagerService {
        return spy(PackageManagerService(rule.mocks().injector,
            false /*factoryTest*/,
            MockSystem.DEFAULT_VERSION_INFO.fingerprint,
            false /*isEngBuild*/,
            false /*isUserDebugBuild*/,
            Build.VERSION_CODES.CUR_DEVELOPMENT,
            Build.VERSION.INCREMENTAL))
    }

    @Test
    fun testSystemScanFlagOnFirstBoot() {
        val pms = createPackageManagerService()
        assertThat(pms.isFirstBoot).isEqualTo(true)
        assertThat(pms.isDeviceUpgrading).isEqualTo(false)
        val initAppsHelper = InitAppsHelper(pms, rule.mocks().apexManager, null,
            listOf<ScanPartition>())
        assertThat(
            initAppsHelper.systemScanFlags and PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE)
            .isEqualTo(PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE)
    }

    @Test
    fun testSystemScanFlagWithMockOTA() {
        mockNoFirstBoot()
        mockFingerprintUnchanged()
        mockOta()
        val pms = createPackageManagerService()
        assertThat(pms.isFirstBoot).isEqualTo(false)
        assertThat(pms.isDeviceUpgrading).isEqualTo(true)
        val initAppsHelper = InitAppsHelper(pms, rule.mocks().apexManager, null,
            listOf<ScanPartition>())
        assertThat(
            initAppsHelper.systemScanFlags and PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE)
            .isEqualTo(PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE)
    }

    @Test
    fun testSystemScanFlagNoOTA() {
        mockNoFirstBoot()
        mockFingerprintUnchanged()
        val pms = createPackageManagerService()
        assertThat(pms.isFirstBoot).isEqualTo(false)
        assertThat(pms.isDeviceUpgrading).isEqualTo(false)
        val initAppsHelper = InitAppsHelper(pms, rule.mocks().apexManager, null,
            listOf<ScanPartition>())
        assertThat(
            initAppsHelper.systemScanFlags and PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE)
            .isEqualTo(0)
    }

    @Test
    fun testSystemScanFlagWithFingerprintChanged() {
        mockNoFirstBoot()
        mockFingerprintChanged()
        val pms = createPackageManagerService()
        assertThat(pms.isFirstBoot).isEqualTo(false)
        assertThat(pms.isDeviceUpgrading).isEqualTo(true)
        val initAppsHelper = InitAppsHelper(pms, rule.mocks().apexManager, null,
            listOf<ScanPartition>())
        assertThat(
            initAppsHelper.systemScanFlags and PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE)
            .isEqualTo(PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE)
    }
}
