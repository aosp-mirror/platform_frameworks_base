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
package android.permission

import android.app.AppOpsManager
import android.content.Context
import androidx.benchmark.BenchmarkState
import androidx.benchmark.junit4.BenchmarkRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@LargeTest
/**
 * Performance unit tests for app ops APIs.
 *
 * The APIs under test are used for checking permissions and tracking permission accesses and are
 * therefore invoked frequently by the system for all permission-protected data accesses, hence
 * these APIs should be monitored closely for performance.
 */
class AppOpsPerfTest {
    @get:Rule val mBenchmarkRule: BenchmarkRule = BenchmarkRule()
    private lateinit var appOpsManager: AppOpsManager
    private lateinit var opPackageName: String
    private var opPackageUid: Int = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        appOpsManager = context.getSystemService<AppOpsManager>(AppOpsManager::class.java)!!
        opPackageName = context.getOpPackageName()
        opPackageUid = context.getPackageManager().getPackageUid(opPackageName, 0)
    }

    @Test
    fun testNoteOp() {
        val state: BenchmarkState = mBenchmarkRule.getState()
        while (state.keepRunning()) {
            appOpsManager.noteOp(
                    AppOpsManager.OPSTR_FINE_LOCATION,
                    opPackageUid,
                    opPackageName,
                    null,
                    null
            )
        }
    }

    @Test
    fun testUnsafeCheckOp() {
        val state: BenchmarkState = mBenchmarkRule.getState()
        while (state.keepRunning()) {
            appOpsManager.unsafeCheckOp(
                    AppOpsManager.OPSTR_FINE_LOCATION,
                    opPackageUid,
                    opPackageName
            )
        }
    }
}
