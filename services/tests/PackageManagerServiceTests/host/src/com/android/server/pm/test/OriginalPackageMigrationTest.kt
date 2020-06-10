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

package com.android.server.pm.test

import com.android.internal.util.test.SystemPreparer
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.google.common.truth.Truth.assertThat
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(DeviceJUnit4ClassRunner::class)
class OriginalPackageMigrationTest : BaseHostJUnit4Test() {

    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.dummy_app"
        private const val VERSION_ONE = "PackageManagerDummyAppVersion1.apk"
        private const val VERSION_TWO = "PackageManagerDummyAppVersion2.apk"
        private const val VERSION_THREE = "PackageManagerDummyAppVersion3.apk"
        private const val NEW_PKG = "PackageManagerDummyAppOriginalOverride.apk"

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    private val tempFolder = TemporaryFolder()
    private val preparer: SystemPreparer = SystemPreparer(tempFolder,
            SystemPreparer.RebootStrategy.START_STOP, deviceRebootRule) { this.device }

    @get:Rule
    val rules = RuleChain.outerRule(tempFolder).around(preparer)!!

    @Test
    fun lowerVersion() {
        runForApk(VERSION_ONE)
    }

    @Test
    fun sameVersion() {
        runForApk(VERSION_TWO)
    }

    @Test
    fun higherVersion() {
        runForApk(VERSION_THREE)
    }

    // A bug was found where renamed the package during parsing was leading to an invalid version
    // code check at scan time. A lower version package was being dropped after reboot. To test
    // this, the override APK is defined as versionCode 2 and the original package is given
    // versionCode 1, 2, and 3 from the other methods.
    private fun runForApk(apk: String) {
        preparer.pushApk(apk, Partition.SYSTEM)
                .reboot()

        device.getAppPackageInfo(TEST_PKG_NAME).run {
            assertThat(codePath).contains(apk.removeSuffix(".apk"))
        }

        // Ensure data is preserved by writing to the original dataDir
        val file = tempFolder.newFile().apply { writeText("Test") }
        device.pushFile(file, "${HostUtils.getDataDir(device, TEST_PKG_NAME)}/files/test.txt")

        preparer.deleteApk(apk, Partition.SYSTEM)
                .pushApk(NEW_PKG, Partition.SYSTEM)
                .reboot()

        device.getAppPackageInfo(TEST_PKG_NAME)
                .run {
                    assertThat(this.toString()).isNotEmpty()
                    assertThat(codePath)
                            .contains(NEW_PKG.removeSuffix(".apk"))
                }

        // And then reading the data contents back
        assertThat(device.pullFileContents(
                "${HostUtils.getDataDir(device, TEST_PKG_NAME)}/files/test.txt"))
                .isEqualTo("Test")
    }
}
