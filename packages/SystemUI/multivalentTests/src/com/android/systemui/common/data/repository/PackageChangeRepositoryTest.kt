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

package com.android.systemui.common.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class PackageChangeRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    @Mock private lateinit var context: Context
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var handler: Handler
    @Mock private lateinit var packageInstallerMonitor: PackageInstallerMonitor

    private lateinit var repository: PackageChangeRepository
    private lateinit var updateMonitor: PackageUpdateMonitor

    @Before
    fun setUp() =
        with(kosmos) {
            MockitoAnnotations.initMocks(this@PackageChangeRepositoryTest)
            whenever(context.packageManager).thenReturn(packageManager)

            repository =
                PackageChangeRepositoryImpl(packageInstallerMonitor) { user ->
                    updateMonitor =
                        PackageUpdateMonitor(
                            user = user,
                            bgDispatcher = testDispatcher,
                            scope = applicationCoroutineScope,
                            context = context,
                            bgHandler = handler,
                            logger = PackageUpdateLogger(logcatLogBuffer()),
                            systemClock = fakeSystemClock,
                        )
                    updateMonitor
                }
        }

    @Test
    fun packageUninstalled() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(repository.packageChanged(USER_100))
                assertThat(packageChange).isNull()

                updateMonitor.onPackageRemoved(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 100, /* appId = */ 10)
                )

                assertThat(packageChange).isInstanceOf(PackageChangeModel.Uninstalled::class.java)
                assertThat(packageChange?.packageName).isEqualTo(TEST_PACKAGE)
            }
        }

    @Test
    fun packageUpdateStarted() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(repository.packageChanged(USER_100))
                assertThat(packageChange).isNull()

                updateMonitor.onPackageUpdateStarted(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 100, /* appId = */ 10)
                )

                assertThat(packageChange).isInstanceOf(PackageChangeModel.UpdateStarted::class.java)
                assertThat(packageChange?.packageName).isEqualTo(TEST_PACKAGE)
            }
        }

    @Test
    fun packageUpdateFinished() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(repository.packageChanged(USER_100))
                assertThat(packageChange).isNull()

                updateMonitor.onPackageUpdateFinished(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 100, /* appId = */ 10)
                )

                assertThat(packageChange)
                    .isInstanceOf(PackageChangeModel.UpdateFinished::class.java)
                assertThat(packageChange?.packageName).isEqualTo(TEST_PACKAGE)
            }
        }

    @Test
    fun packageInstalled() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(repository.packageChanged(UserHandle.ALL))
                assertThat(packageChange).isNull()

                updateMonitor.onPackageAdded(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 100, /* appId = */ 10)
                )

                assertThat(packageChange).isInstanceOf(PackageChangeModel.Installed::class.java)
                assertThat(packageChange?.packageName).isEqualTo(TEST_PACKAGE)
            }
        }

    @Test
    fun packageIsChanged() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(repository.packageChanged(USER_100))
                assertThat(packageChange).isNull()

                updateMonitor.onPackageChanged(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 100, /* appId = */ 10),
                    components = emptyArray()
                )

                assertThat(packageChange).isInstanceOf(PackageChangeModel.Changed::class.java)
                assertThat(packageChange?.packageName).isEqualTo(TEST_PACKAGE)
            }
        }

    @Test
    fun filtersOutUpdatesFromOtherUsers() =
        with(kosmos) {
            testScope.runTest {
                val packageChange by collectLastValue(repository.packageChanged(USER_100))
                assertThat(packageChange).isNull()

                updateMonitor.onPackageUpdateFinished(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 101, /* appId = */ 10)
                )

                updateMonitor.onPackageAdded(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 99, /* appId = */ 10)
                )

                assertThat(packageChange).isNull()
            }
        }

    @Test
    fun listenToUpdatesFromAllUsers() =
        with(kosmos) {
            testScope.runTest {
                val packageChanges by collectValues(repository.packageChanged(UserHandle.ALL))
                assertThat(packageChanges).isEmpty()

                updateMonitor.onPackageUpdateFinished(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 101, /* appId = */ 10)
                )

                updateMonitor.onPackageAdded(
                    packageName = TEST_PACKAGE,
                    uid = UserHandle.getUid(/* userId = */ 99, /* appId = */ 10)
                )

                assertThat(packageChanges).hasSize(2)
            }
        }

    private companion object {
        val USER_100 = UserHandle.of(100)
        const val TEST_PACKAGE = "pkg.test"
    }
}
