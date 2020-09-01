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

package com.android.tests.gating

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.parsing.result.ParseInput
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.android.internal.compat.IPlatformCompat
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.testng.Assert.assertThrows
import java.io.FileReader

/**
 * Verifies the shell commands "am compat enable/disable/reset" against a real server change ID
 * for a not installed package.
 *
 * This class intentionally does not use any PlatformCompat testing infrastructure since that could
 * interfere with what it's testing.
 */
@RunWith(Parameterized::class)
class PlatformCompatCommandNotInstalledTest {

    companion object {

        private const val TEST_PKG = "com.android.tests.gating.app_not_installed"
        private const val TEST_CHANGE_ID = ParseInput.DeferredError.MISSING_APP_TAG

        private val instrumentation = InstrumentationRegistry.getInstrumentation()

        @JvmStatic
        @BeforeClass
        fun assumeDebuggable() {
            assumeTrue(Build.IS_DEBUGGABLE)
        }

        @JvmStatic
        @BeforeClass
        fun assertNotInstalled() {
            assertThrows(PackageManager.NameNotFoundException::class.java) {
                instrumentation.context.packageManager
                        .getApplicationInfo(TEST_PKG, PackageManager.MATCH_ALL)
            }
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = arrayOf(
                Params(enableDisable = null, targetSdk = 29, result = false),
                Params(enableDisable = null, targetSdk = 30, result = true),

                Params(enableDisable = true, targetSdk = 29, result = true),
                Params(enableDisable = true, targetSdk = 30, result = true),

                Params(enableDisable = false, targetSdk = 29, result = false),
                Params(enableDisable = false, targetSdk = 30, result = false)
        )
    }

    data class Params(val enableDisable: Boolean?, val targetSdk: Int, val result: Boolean)

    @Parameterized.Parameter(0)
    lateinit var params: Params

    private val uiAutomation: UiAutomation = instrumentation.getUiAutomation()
    private val platformCompat = IPlatformCompat.Stub.asInterface(
            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE))

    @Before
    fun resetChangeId() {
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG)

        val result = command("am compat reset $TEST_CHANGE_ID $TEST_PKG")
        assertThat(result.startsWith("Reset change") || result.startsWith("No override"))
                .isTrue()
    }

    fun ParcelFileDescriptor.text() = FileReader(fileDescriptor).readText()

    @After
    fun resetIdentity() = uiAutomation.dropShellPermissionIdentity()

    @Test
    fun execute() {
        when (params.enableDisable) {
            null -> { /* do nothing */
            }
            true -> assertThat(command("am compat enable $TEST_CHANGE_ID $TEST_PKG"))
                    .startsWith("Enabled change")
            false -> assertThat(command("am compat disable $TEST_CHANGE_ID $TEST_PKG"))
                    .startsWith("Disabled change")
        }

        val appInfo = ApplicationInfo().apply {
            this.packageName = TEST_PKG
            this.targetSdkVersion = params.targetSdk
        }

        assertThat(platformCompat.isChangeEnabled(TEST_CHANGE_ID, appInfo)).isEqualTo(params.result)
    }

    private fun command(command: String) =
            FileReader(uiAutomation.executeShellCommand(command).fileDescriptor).readText()
}
