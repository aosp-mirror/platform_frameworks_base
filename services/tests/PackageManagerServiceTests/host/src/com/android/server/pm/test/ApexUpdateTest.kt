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

package com.android.server.pm.test

import com.android.modules.testing.utils.ApexInstallHelper
import com.android.tradefed.invoker.TestInformation
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DeviceJUnit4ClassRunner::class)
class ApexUpdateTest : BaseHostJUnit4Test() {

    companion object {
        private const val APEX_NAME = "com.android.server.pm.test.apex"
        private const val APK_IN_APEX_NAME = "$APEX_NAME.app"
        private const val APK_FILE_NAME = "PackageManagerTestApexApp.apk"

        private lateinit var apexInstallHelper: ApexInstallHelper

        @JvmStatic
        @BeforeClassWithInfo
        fun initApexHelper(testInformation: TestInformation) {
            apexInstallHelper = ApexInstallHelper(testInformation)
        }

        @JvmStatic
        @AfterClass
        fun revertChanges() {
            apexInstallHelper.revertChanges()
        }
    }

    @Before
    @After
    fun uninstallApp() {
        device.uninstallPackage(APK_IN_APEX_NAME)
    }

    @Test
    fun apexModuleName() {
        // Install the test APEX and assert it's returned as the APEX module itself
        // (null when not --include-apex)
        apexInstallHelper.pushApexAndReboot("PackageManagerTestApex.apex")
        assertModuleName(APEX_NAME).isNull()
        assertModuleName(APEX_NAME, includeApex = true).isEqualTo(APEX_NAME)

        // Check the APK-in-APEX, ensuring there is only 1 active package
        assertModuleName(APK_IN_APEX_NAME).isEqualTo(APEX_NAME)
        assertModuleName(APK_IN_APEX_NAME, hidden = true).isNull()

        // Then install a /data update to the APK-in-APEX
        device.installPackage(testInformation.getDependencyFile(APK_FILE_NAME, false), false)

        // Verify same as above
        assertModuleName(APEX_NAME, includeApex = true).isEqualTo(APEX_NAME)
        assertModuleName(APK_IN_APEX_NAME).isEqualTo(APEX_NAME)

        // But also check that the /data variant now has a hidden package
        assertModuleName(APK_IN_APEX_NAME, hidden = true).isEqualTo(APEX_NAME)

        // Reboot the device and check that values are preserved
        device.reboot()
        assertModuleName(APEX_NAME, includeApex = true).isEqualTo(APEX_NAME)
        assertModuleName(APK_IN_APEX_NAME).isEqualTo(APEX_NAME)
        assertModuleName(APK_IN_APEX_NAME, hidden = true).isEqualTo(APEX_NAME)

        // Revert the install changes (delete system image APEX) and check that it's gone
        apexInstallHelper.revertChanges()
        assertModuleName(APEX_NAME, includeApex = true).isNull()

        // Verify the module name is no longer associated with the APK-in-APEX,
        // which is now just a regular /data APK with no hidden system variant.
        // The assertion for the valid /data APK uses "null" because the value
        // printed for normal packages is "apexModuleName=null". As opposed to
        // a literal null indicating the package variant doesn't exist
        assertModuleName(APK_IN_APEX_NAME).isEqualTo("null")
        assertModuleName(APK_IN_APEX_NAME, hidden = true).isEqualTo(null)
    }

    private fun assertModuleName(
        packageName: String,
        hidden: Boolean = false,
        includeApex: Boolean = false
    ) = assertThat(
        device.executeShellCommand(
                "dumpsys package ${"--include-apex".takeIf { includeApex }} $packageName"
        )
            .lineSequence()
            .map(String::trim)
            .dropWhile { !it.startsWith(if (hidden) "Hidden system packages:" else "Packages:")}
            .dropWhile { !it.startsWith("Package [$packageName]") }
            .takeWhile { !it.startsWith("User 0:") }
            .firstOrNull { it.startsWith("apexModuleName=") }
            ?.removePrefix("apexModuleName=")
    )
}
