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
class OverlayPathsUninstallSystemUpdatesTest : BaseHostJUnit4Test() {

    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"
        private const val VERSION_ONE = "PackageManagerTestAppVersion1.apk"
        private const val VERSION_TWO = "PackageManagerTestAppVersion2.apk"

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    private val tempFolder = TemporaryFolder()
    private val preparer: SystemPreparer = SystemPreparer(tempFolder,
            SystemPreparer.RebootStrategy.FULL, deviceRebootRule, true) { this.device }

    @Rule
    @JvmField
    val rules = RuleChain.outerRule(tempFolder).around(preparer)!!
    private val filePath = HostUtils.makePathForApk("PackageManagerTestApp.apk", Partition.PRODUCT)

    @Before
    @After
    fun removeApk() {
        device.uninstallPackage(TEST_PKG_NAME)
    }

    @Test
    fun verify() {
        // First, push a system app to the device and then update it so there's a data variant
        preparer.pushResourceFile(VERSION_ONE, filePath.toString())
                .reboot()

        val versionTwoFile = HostUtils.copyResourceToHostFile(VERSION_TWO, tempFolder.newFile())

        assertThat(device.installPackage(versionTwoFile, true)).isNull()

        device.executeShellCommand(
                "cmd overlay fabricate --target-name TestResources" +
                " --target $TEST_PKG_NAME" +
                " --name UninstallSystemUpdatesTest" +
                " $TEST_PKG_NAME:color/overlay_test 0x1C 0xFFFFFFFF"
        )

        device.executeShellCommand(
                "cmd overlay enable --user 0 com.android.shell:UninstallSystemUpdatesTest"
        )

        fun verifyValueOverlaid() {
            assertThat(device.executeShellCommand(
                    "cmd overlay lookup --user 0 $TEST_PKG_NAME $TEST_PKG_NAME:color/overlay_test"
            ).trim()).isEqualTo("#ffffffff")
        }

        verifyValueOverlaid()

        assertThat(
                device.executeShellCommand("pm uninstall-system-updates $TEST_PKG_NAME"
        ).trim()).endsWith("Success")

        // Wait for paths to re-propagate. This doesn't do a retry loop in case the path clear also
        // has some latency. There must be some minimum wait time for the paths to settle, and then
        // a wait time for the paths to re-propagate. Rather than complicate the logic, just wait
        // a long enough time for both events to occur.
        Thread.sleep(5000)

        verifyValueOverlaid()
    }
}
