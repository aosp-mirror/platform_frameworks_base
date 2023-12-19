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

package com.android.systemui.common.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.data.shared.model.PackageChangeModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class PackageUpdateMonitorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @Mock private lateinit var context: Context
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var handler: Handler

    private lateinit var monitor: PackageUpdateMonitor

    @Before
    fun setUp() =
        with(kosmos) {
            MockitoAnnotations.initMocks(this@PackageUpdateMonitorTest)
            whenever(context.packageManager).thenReturn(packageManager)

            monitor =
                PackageUpdateMonitor(
                    user = USER_100,
                    bgDispatcher = testDispatcher,
                    bgHandler = handler,
                    context = context,
                    scope = applicationCoroutineScope,
                    logger = PackageUpdateLogger(logcatLogBuffer())
                )
        }

    @Test
    fun becomesActiveWhenFlowCollected() =
        with(kosmos) {
            testScope.runTest {
                assertThat(monitor.isActive).isFalse()
                val job = monitor.packageChanged.launchIn(this)
                runCurrent()
                assertThat(monitor.isActive).isTrue()
                job.cancel()
                runCurrent()
                assertThat(monitor.isActive).isFalse()
            }
        }

    @Test
    fun packageAdded() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(monitor.packageChanged)
                assertThat(packageChange).isNull()

                monitor.onPackageAdded(TEST_PACKAGE, 123)

                assertThat(packageChange)
                    .isEqualTo(
                        PackageChangeModel.Installed(packageName = TEST_PACKAGE, packageUid = 123)
                    )
            }
        }

    @Test
    fun packageRemoved() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(monitor.packageChanged)
                assertThat(packageChange).isNull()

                monitor.onPackageRemoved(TEST_PACKAGE, 123)

                assertThat(packageChange)
                    .isEqualTo(
                        PackageChangeModel.Uninstalled(packageName = TEST_PACKAGE, packageUid = 123)
                    )
            }
        }

    @Test
    fun packageChanged() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(monitor.packageChanged)
                assertThat(packageChange).isNull()

                monitor.onPackageChanged(TEST_PACKAGE, 123, emptyArray())

                assertThat(packageChange)
                    .isEqualTo(
                        PackageChangeModel.Changed(packageName = TEST_PACKAGE, packageUid = 123)
                    )
            }
        }

    @Test
    fun packageUpdateStarted() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(monitor.packageChanged)
                assertThat(packageChange).isNull()

                monitor.onPackageUpdateStarted(TEST_PACKAGE, 123)

                assertThat(packageChange)
                    .isEqualTo(
                        PackageChangeModel.UpdateStarted(
                            packageName = TEST_PACKAGE,
                            packageUid = 123
                        )
                    )
            }
        }

    @Test
    fun packageUpdateFinished() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(monitor.packageChanged)
                assertThat(packageChange).isNull()

                monitor.onPackageUpdateFinished(TEST_PACKAGE, 123)

                assertThat(packageChange)
                    .isEqualTo(
                        PackageChangeModel.UpdateFinished(
                            packageName = TEST_PACKAGE,
                            packageUid = 123
                        )
                    )
            }
        }

    @Test
    fun handlesBackflow() =
        with(kosmos) {
            testScope.runTest {
                val latch = MutableSharedFlow<Unit>()
                val packageChanges by collectValues(monitor.packageChanged.onEach { latch.first() })
                assertThat(packageChanges).isEmpty()

                monitor.onPackageAdded(TEST_PACKAGE, 123)
                monitor.onPackageUpdateStarted(TEST_PACKAGE, 123)
                monitor.onPackageUpdateFinished(TEST_PACKAGE, 123)

                assertThat(packageChanges).isEmpty()
                latch.emit(Unit)
                assertThat(packageChanges).hasSize(1)
                latch.emit(Unit)
                assertThat(packageChanges).hasSize(2)
                latch.emit(Unit)
                assertThat(packageChanges)
                    .containsExactly(
                        PackageChangeModel.Installed(TEST_PACKAGE, 123),
                        PackageChangeModel.UpdateStarted(TEST_PACKAGE, 123),
                        PackageChangeModel.UpdateFinished(TEST_PACKAGE, 123),
                    )
                    .inOrder()
            }
        }

    companion object {
        private val USER_100 = UserHandle.of(100)
        private const val TEST_PACKAGE = "pkg.test"
    }
}
