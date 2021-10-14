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

package com.android.server.pm.test

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.google.common.truth.Truth
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(DeviceJUnit4ClassRunner::class)
class BootTest : BaseHostJUnit4Test() {
    companion object {
        private const val TEST_PKG_NAME = "com.android.server.pm.test.test_app"
        private const val TEST_APK_NAME = "PackageManagerTestAppStub.apk"
    }

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Before
    fun installApk() {
        val testApkFile = HostUtils.copyResourceToHostFile(TEST_APK_NAME, tempFolder.newFile())
        device.installPackage(testApkFile, true)
    }

    @After
    fun removeApk() {
        device.uninstallPackage(TEST_PKG_NAME)
    }

    @Test
    fun testUninstallPackageWithKeepDataAndReboot() {
        Truth.assertThat(isPackageInstalled(TEST_PKG_NAME)).isTrue()
        uninstallPackageWithKeepData(TEST_PKG_NAME)
        Truth.assertThat(isPackageInstalled(TEST_PKG_NAME)).isFalse()
        device.rebootUntilOnline()
        waitForBootCompleted()
    }

    private fun uninstallPackageWithKeepData(packageName: String) {
        device.executeShellCommand("pm uninstall -k $packageName")
    }

    private fun waitForBootCompleted() {
        for (i in 0 until 45) {
            if (isBootCompleted()) {
                return
            }
            Thread.sleep(1000)
        }
        throw AssertionError("System failed to become ready!")
    }

    private fun isBootCompleted(): Boolean {
        return "1" == device.executeShellCommand("getprop sys.boot_completed").trim()
    }
}