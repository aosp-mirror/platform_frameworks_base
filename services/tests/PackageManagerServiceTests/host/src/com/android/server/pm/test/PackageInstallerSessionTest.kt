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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.jvm.JvmField

@RunWith(DeviceJUnit4ClassRunner::class)
class PackageInstallerSessionTest : BaseHostJUnit4Test() {
    companion object {
        private const val DEVICE_SIDE = "PackageManagerServiceDeviceSideTests.apk"
    }

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun verify_parentSessionFail_childSessionFiles_shouldBeDestroyed() {
        runDeviceTest("com.android.server.pm.PackageInstallerSessionTest",
                "verify_parentSessionFail_childSessionFiles_shouldBeDestroyed")
    }

    /**
     * Run a device side test from com.android.server.pm.test.deviceside.DeviceSide
     *
     * @param method the method to run
     */
    fun runDeviceTest(testClassName: String, method: String) {
        val deviceSideFile = HostUtils.copyResourceToHostFile(DEVICE_SIDE, tempFolder.newFile())
        Truth.assertThat(device.installPackage(deviceSideFile, true)).isNull()
        runDeviceTests(device, "com.android.server.pm.test.deviceside",
                testClassName, method)
    }
}
