/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.os.ParcelFileDescriptor
import android.os.Trace
import android.perftests.utils.PerfManualStatusReporter
import android.perftests.utils.TraceMarkParser
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.android.compatibility.common.util.SystemUtil.eventually
import com.android.compatibility.common.util.SystemUtil.runShellCommand
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

@RunWith(AndroidJUnit4::class)
class PermissionServicePerfTest {
    @get:Rule val mPerfManualStatusReporter = PerfManualStatusReporter()
    @get:Rule val mAdoptShellPermissionsRule = AdoptShellPermissionsRule(
        InstrumentationRegistry.getInstrumentation().getUiAutomation(),
        Manifest.permission.INSTALL_PACKAGES,
        Manifest.permission.DELETE_PACKAGES
    )
    val mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation()

    @Test
    fun testInstallPackages() {
        mUiAutomation.executeShellCommand(COMMAND_TRACE_START)
        eventually { assertThat(Trace.isTagEnabled(TRACE_TAG)).isTrue() }
        val benchmarkState = mPerfManualStatusReporter.benchmarkState
        val durations = ArrayList<Long>()

        while (benchmarkState.keepRunning(durations)) {
            uninstallAllTestApps()
            installAllTestApps()

            val parser = TraceMarkParser { line -> line.name.contains(PKG_INSTALL_TRACE_PREFIX) }
            dumpResult(parser) { _, slices ->
                slices.forEachIndexed { _, slice ->
                    durations.add(TimeUnit.MICROSECONDS.toNanos(slice.durationInMicroseconds))
                }
            }
        }

        mUiAutomation.executeShellCommand(COMMAND_TRACE_END)
    }

    private fun installAllTestApps() {
        for (i in 0..29) {
            installTestApp(i)
        }
    }

    private fun installTestApp(appId: Int) {
        val apkPath = "$APK_DIR$APK_NAME$appId.apk"
        runShellCommand("pm install -t $apkPath")
    }

    private fun uninstallAllTestApps() {
        for (i in 0..29) {
            uninstallTestApp(i)
        }
    }

    private fun uninstallTestApp(appId: Int) {
        val packageName = "$PKG_NAME$appId"
        runShellCommand("pm uninstall $packageName")
    }

    private fun dumpResult(
        parser: TraceMarkParser,
        handler: BiConsumer<String, List<TraceMarkParser.TraceMarkSlice>>
    ) {
        parser.reset()
        try {
            val inputStream = ParcelFileDescriptor.AutoCloseInputStream(
                mUiAutomation.executeShellCommand(COMMAND_TRACE_DUMP)
            )
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line = reader.readLine()
            while (line != null) {
                parser.visit(line)
                line = reader.readLine()
            }
        } catch (e: IOException) {
            Log.e(LOG_TAG, "IO error while reading trace dump file.")
        }
        parser.forAllSlices(handler)
    }

    companion object {
        private val LOG_TAG = PermissionServicePerfTest::class.java.simpleName
        private const val TRACE_TAG = Trace.TRACE_TAG_PACKAGE_MANAGER
        private const val PKG_INSTALL_TRACE_PREFIX =
            "TaggedTracingPermissionManagerServiceImpl#onPackageInstalled"
        private const val COMMAND_TRACE_START = "atrace --async_start -b 16000 pm"
        private const val COMMAND_TRACE_END = "atrace --async_stop"
        private const val COMMAND_TRACE_DUMP = "atrace --async_dump"
        private const val APK_DIR = "/data/local/tmp/perftests/"
        private const val APK_NAME = "UsePermissionApp"
        private const val PKG_NAME = "android.perftests.appenumeration"
    }
}
