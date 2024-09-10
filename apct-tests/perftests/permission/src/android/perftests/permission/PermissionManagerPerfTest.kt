/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.perftests.permission

import android.Manifest
import android.companion.virtual.VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT
import android.content.Context
import android.perftests.utils.PerfStatusReporter
import android.permission.PermissionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.android.compatibility.common.util.SystemUtil.runShellCommand
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionManagerPerfTest {
    @get:Rule var perfStatusReporter = PerfStatusReporter()
    @get:Rule
    val mAdoptShellPermissionsRule =
        AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
        )

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val permissionManager = context.getSystemService(PermissionManager::class.java)!!

    @Before
    fun setup() {
        val apkPath = "$APK_DIR${APK_NAME}.apk"
        runShellCommand("pm install -tg $apkPath")
    }

    @After
    fun cleanup() {
        runShellCommand("pm uninstall $PKG_NAME")
    }

    @Test
    fun testGetAllPermissionStates() {
        val benchmarkState = perfStatusReporter.benchmarkState
        while (benchmarkState.keepRunning()) {
            permissionManager.getAllPermissionStates(PKG_NAME, PERSISTENT_DEVICE_ID_DEFAULT)
        }
    }

    companion object {
        private const val APK_DIR = "/data/local/tmp/perftests/"
        private const val APK_NAME = "UsePermissionApp0"
        private const val PKG_NAME = "android.perftests.appenumeration0"
    }
}
