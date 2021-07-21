/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.PRIVATE_FLAG_PRIVILEGED
import android.content.pm.PackageManager
import android.content.pm.PackageParser
import android.os.Build
import android.os.Process
import android.util.Log
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.testutils.whenever
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.collection.IsMapContaining.hasKey
import org.hamcrest.core.IsNot.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.io.File

@RunWith(JUnit4::class)
class PackageManagerServiceBootTest {

    @Rule
    @JvmField
    val rule = MockSystemRule()

    @Before
    @Throws(Exception::class)
    fun setup() {
        Log.i("system.out", "setup", Exception())
        rule.system().stageNominalSystemState()
    }

    private fun createPackageManagerService(): PackageManagerService {
        return PackageManagerService(rule.mocks().injector,
                false /*coreOnly*/,
                false /*factoryTest*/,
                MockSystem.DEFAULT_VERSION_INFO.fingerprint,
                false /*isEngBuild*/,
                false /*isUserDebugBuild*/,
                Build.VERSION_CODES.CUR_DEVELOPMENT,
                Build.VERSION.INCREMENTAL)
    }

    @Test
    @Throws(Exception::class)
    fun simpleConstruction() {
        val pm = createPackageManagerService()
        verify(rule.mocks().injector).bootstrap(pm)
        verify(rule.mocks().settings).addSharedUserLPw("android.uid.system",
                Process.SYSTEM_UID, FLAG_SYSTEM, PRIVATE_FLAG_PRIVILEGED)
        verify(rule.mocks().settings).addSharedUserLPw("android.uid.phone",
                Process.PHONE_UID, FLAG_SYSTEM, PRIVATE_FLAG_PRIVILEGED)
        verify(rule.mocks().settings).addSharedUserLPw("android.uid.log",
                Process.LOG_UID, FLAG_SYSTEM, PRIVATE_FLAG_PRIVILEGED)
        verify(rule.mocks().settings).addSharedUserLPw("android.uid.nfc",
                Process.NFC_UID, FLAG_SYSTEM, PRIVATE_FLAG_PRIVILEGED)
        verify(rule.mocks().settings).addSharedUserLPw("android.uid.bluetooth",
                Process.BLUETOOTH_UID, FLAG_SYSTEM, PRIVATE_FLAG_PRIVILEGED)
        verify(rule.mocks().settings).addSharedUserLPw("android.uid.shell",
                Process.SHELL_UID, FLAG_SYSTEM, PRIVATE_FLAG_PRIVILEGED)
        verify(rule.mocks().settings).addSharedUserLPw("android.uid.se",
                Process.SE_UID, FLAG_SYSTEM, PRIVATE_FLAG_PRIVILEGED)
        verify(rule.mocks().settings).addSharedUserLPw("android.uid.networkstack",
                Process.NETWORK_STACK_UID, FLAG_SYSTEM, PRIVATE_FLAG_PRIVILEGED)
        rule.system().validateFinalState()
    }

    @Test
    @Throws(Exception::class)
    fun existingDataPackage_remains() {
        rule.system().stageScanExistingPackage("a.data.package", 1L, rule.system().dataAppDirectory)
        val pm = createPackageManagerService()
        rule.system().validateFinalState()
        assertThat(pm.mPackages, hasKey("a.data.package"))
    }

    @Test
    @Throws(Exception::class)
    fun unexpectedDataPackage_isRemoved() {
        rule.system().stageScanNewPackage(
                "a.data.package", 1L, rule.system().dataAppDirectory)
        val pm = createPackageManagerService()
        verify(rule.mocks().settings, Mockito.never()).insertPackageSettingLPw(
                argThat { setting: PackageSetting -> setting.name == "a.data.package" },
                argThat { pkg: AndroidPackage -> pkg.packageName == "a.data.package" })
        assertThat(pm.mPackages, not(hasKey("a.data.package")))
    }

    @Test
    @Throws(Exception::class)
    fun expectedPackageMissing_doesNotReplace() {
        // setup existing package
        rule.system().stageScanExistingPackage("a.data.package", 1L,
                rule.system().dataAppDirectory)
        // simulate parsing failure for any path containing the package name.
        whenever(rule.mocks().packageParser.parsePackage(
                argThat { path: File -> path.path.contains("a.data.package") },
                anyInt(),
                anyBoolean()))
                .thenThrow(PackageParser.PackageParserException(
                        PackageManager.INSTALL_FAILED_INVALID_APK, "Oh no!"))
        val pm = createPackageManagerService()
        verify(rule.mocks().settings, Mockito.never()).insertPackageSettingLPw(
                argThat { setting: PackageSetting -> setting.name == "a.data.package" },
                argThat { pkg: AndroidPackage -> pkg.packageName == "a.data.package" })
        assertThat(pm.mPackages, not(hasKey("a.data.package")))
    }

    @Test
    @Throws(Exception::class)
    fun expectingBetter_updateStillBetter() {
        // Debug.waitForDebugger()
        val systemAppPackageName = "com.android.test.updated.system.app"
        val systemAppSigningDetails = rule.system().createRandomSigningDetails()
        val systemVersionParent = rule.system()
                .getPartitionFromFlag(PackageManagerService.SCAN_AS_PRODUCT).privAppFolder

        // system app v1 is disabled
        whenever(rule.mocks().settings.isDisabledSystemPackageLPr(systemAppPackageName)) {
            true
        }
        whenever(rule.mocks().settings.getDisabledSystemPkgLPr(systemAppPackageName)) {
            rule.system().createBasicSettingBuilder(
                    File(systemVersionParent, systemAppPackageName),
                    systemAppPackageName, 1, systemAppSigningDetails).build()
        }

        // system app v3 is on data/app
        rule.system().stageScanExistingPackage(
                systemAppPackageName,
                3,
                rule.system().dataAppDirectory,
                withPackage = { it.apply { signingDetails = systemAppSigningDetails } },
                withExistingSetting = { it.setPkgFlags(FLAG_SYSTEM) })

        // system app v2 is scanned from system
        rule.system().stageScanNewPackage(systemAppPackageName, 2, systemVersionParent,
                withPackage = { it.apply { signingDetails = systemAppSigningDetails } },
                withSetting = { it.setPkgFlags(FLAG_SYSTEM) })

        val pm = createPackageManagerService()

        assertThat("system package should exist after boot",
                pm.mPackages[systemAppPackageName], notNullValue())
        assertThat("system package should remain at version on data/app",
                pm.mPackages[systemAppPackageName]!!.longVersionCode, equalTo(3))
    }
}