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
class InvalidNewSystemAppTest : BaseHostJUnit4Test() {

    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.dummy_app"
        private const val VERSION_ONE = "PackageManagerDummyAppVersion1.apk"
        private const val VERSION_TWO = "PackageManagerDummyAppVersion2.apk"
        private const val VERSION_THREE_INVALID = "PackageManagerDummyAppVersion3Invalid.apk"
        private const val VERSION_FOUR = "PackageManagerDummyAppVersion4.apk"

        @get:ClassRule
        val deviceRebootRule = SystemPreparer.TestRuleDelegate(true)
    }

    private val tempFolder = TemporaryFolder()
    private val preparer: SystemPreparer = SystemPreparer(tempFolder,
            SystemPreparer.RebootStrategy.FULL, deviceRebootRule) { this.device }

    @get:Rule
    val rules = RuleChain.outerRule(tempFolder).around(preparer)!!
    private val filePath = HostUtils.makePathForApk("PackageManagerDummyApp.apk", Partition.PRODUCT)

    @Before
    @After
    fun removeApk() {
        device.uninstallPackage(TEST_PKG_NAME)
        device.deleteFile(filePath.parent.toString())
        device.reboot()
    }

    @Test
    fun verify() {
        // First, push a system app to the device and then update it so there's a data variant
        preparer.pushResourceFile(VERSION_ONE, filePath.toString())
                .reboot()

        val versionTwoFile = HostUtils.copyResourceToHostFile(VERSION_TWO, tempFolder.newFile())

        assertThat(device.installPackage(versionTwoFile, true)).isNull()

        // Then push a bad update to the system, overwriting the existing file as if an OTA occurred
        preparer.deleteFile(filePath.toString())
                .pushResourceFile(VERSION_THREE_INVALID, filePath.toString())
                .reboot()

        // This will remove the package from the device, which is expected
        assertThat(device.getAppPackageInfo(TEST_PKG_NAME)).isNull()

        // Then check that a user would still be able to install the application manually
        val versionFourFile = HostUtils.copyResourceToHostFile(VERSION_FOUR, tempFolder.newFile())
        assertThat(device.installPackage(versionFourFile, true)).isNull()
    }
}
