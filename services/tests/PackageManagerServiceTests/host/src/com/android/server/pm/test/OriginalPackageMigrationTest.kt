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
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(DeviceJUnit4ClassRunner::class)
class OriginalPackageMigrationTest : BaseHostJUnit4Test() {

    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"
        private const val VERSION_ONE = "PackageManagerTestAppVersion1.apk"
        private const val VERSION_TWO = "PackageManagerTestAppVersion2.apk"
        private const val VERSION_THREE = "PackageManagerTestAppVersion3.apk"
        private const val NEW_PKG = "PackageManagerTestAppOriginalOverride.apk"

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    private val tempFolder = TemporaryFolder()
    private val preparer: SystemPreparer = SystemPreparer(tempFolder,
            SystemPreparer.RebootStrategy.FULL, deviceRebootRule) { this.device }

    @Rule
    @JvmField
    val rules = RuleChain.outerRule(tempFolder).around(preparer)!!

    @Before
    @After
    fun deleteApkFolders() {
        preparer.deleteApkFolders(Partition.SYSTEM, VERSION_ONE, VERSION_TWO, VERSION_THREE,
                NEW_PKG)
                .reboot()
    }

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

        assertCodePath(apk)

        // Ensure data is preserved by writing to the original dataDir
        val file = tempFolder.newFile().apply { writeText("Test") }
        device.pushFile(file, "${HostUtils.getDataDir(device, TEST_PKG_NAME)}/files/test.txt")

        preparer.deleteApkFolders(Partition.SYSTEM, apk)
                .pushApk(NEW_PKG, Partition.SYSTEM)
                .reboot()

        assertCodePath(NEW_PKG)

        // And then reading the data contents back
        assertThat(device.pullFileContents(
                "${HostUtils.getDataDir(device, TEST_PKG_NAME)}/files/test.txt"))
                .isEqualTo("Test")
    }

    private fun assertCodePath(apk: String) {
        assertThat(HostUtils.getCodePaths(device, TEST_PKG_NAME))
                .containsExactly(HostUtils.makePathForApk(apk, Partition.SYSTEM).parent.toString())
    }
}
