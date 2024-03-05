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

package com.android.systemui.qs.tiles.impl.rotation.domain.interactor

import android.Manifest
import android.content.packageManager
import android.content.pm.PackageManager
import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.camera.data.repository.fakeCameraAutoRotateRepository
import com.android.systemui.camera.data.repository.fakeCameraSensorPrivacyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.leaks.FakeBatteryController
import com.android.systemui.utils.leaks.FakeRotationLockController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class RotationLockTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val batteryController = FakeBatteryController(LeakCheck())
    private val rotationController = FakeRotationLockController(LeakCheck())
    private val fakeCameraAutoRotateRepository = kosmos.fakeCameraAutoRotateRepository
    private val fakeCameraSensorPrivacyRepository = kosmos.fakeCameraSensorPrivacyRepository
    private val packageManager = kosmos.packageManager

    private val testUser = UserHandle.of(1)
    private lateinit var underTest: RotationLockTileDataInteractor

    @Before
    fun setup() {
        whenever(packageManager.rotationResolverPackageName).thenReturn(TEST_PACKAGE_NAME)
        whenever(
                packageManager.checkPermission(
                    eq(Manifest.permission.CAMERA),
                    eq(TEST_PACKAGE_NAME)
                )
            )
            .thenReturn(PackageManager.PERMISSION_GRANTED)

        underTest =
            RotationLockTileDataInteractor(
                rotationController,
                batteryController,
                fakeCameraAutoRotateRepository,
                fakeCameraSensorPrivacyRepository,
                packageManager,
                context.orCreateTestableResources
                    .apply {
                        addOverride(com.android.internal.R.bool.config_allowRotationResolver, true)
                    }
                    .resources
            )
    }

    @Test
    fun availability_isTrue() =
        testScope.runTest {
            val availability = underTest.availability(testUser).toCollection(mutableListOf())

            assertThat(availability).hasSize(1)
            assertThat(availability.last()).isTrue()
        }

    @Test
    fun tileData_isRotationLockedMatchesRotationController() =
        testScope.runTest {
            val data by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            runCurrent()
            assertThat(data!!.isRotationLocked).isEqualTo(false)

            rotationController.setRotationLocked(true, CALLER)
            runCurrent()
            assertThat(data!!.isRotationLocked).isEqualTo(true)

            rotationController.setRotationLocked(false, CALLER)
            runCurrent()
            assertThat(data!!.isRotationLocked).isEqualTo(false)
        }

    @Test
    fun tileData_cameraRotationMatchesBatteryController() =
        testScope.runTest {
            setupControllersToEnableCameraRotation()
            val data by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            runCurrent()
            assertThat(data!!.isCameraRotationEnabled).isTrue()

            batteryController.setPowerSaveMode(true)
            runCurrent()
            assertThat(data!!.isCameraRotationEnabled).isFalse()

            batteryController.setPowerSaveMode(false)
            runCurrent()
            assertThat(data!!.isCameraRotationEnabled).isTrue()
        }

    @Test
    fun tileData_cameraRotationMatchesSensorPrivacyRepository() =
        testScope.runTest {
            setupControllersToEnableCameraRotation()
            val lastValue by
                this.collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            assertThat(lastValue!!.isCameraRotationEnabled).isTrue()

            fakeCameraSensorPrivacyRepository.setEnabled(testUser, true)
            runCurrent()
            assertThat(lastValue!!.isCameraRotationEnabled).isFalse()

            fakeCameraSensorPrivacyRepository.setEnabled(testUser, false)
            runCurrent()
            assertThat(lastValue!!.isCameraRotationEnabled).isTrue()
        }

    @Test
    fun tileData_cameraRotationMatchesAutoRotateRepository() =
        testScope.runTest {
            setupControllersToEnableCameraRotation()

            val lastValue by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            assertThat(lastValue!!.isCameraRotationEnabled).isTrue()

            fakeCameraAutoRotateRepository.setEnabled(testUser, false)
            runCurrent()
            assertThat(lastValue!!.isCameraRotationEnabled).isFalse()

            fakeCameraAutoRotateRepository.setEnabled(testUser, true)
            runCurrent()
            assertThat(lastValue!!.isCameraRotationEnabled).isTrue()
        }

    @Test
    fun tileData_matchesPackageManagerPermissionDenied() =
        testScope.runTest {
            whenever(
                    packageManager.checkPermission(
                        eq(Manifest.permission.CAMERA),
                        eq(TEST_PACKAGE_NAME)
                    )
                )
                .thenReturn(PackageManager.PERMISSION_DENIED)

            val lastValue by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            assertThat(lastValue!!.isCameraRotationEnabled).isEqualTo(false)
        }

    @Test
    fun tileData_setConfigAllowRotationResolverToFalse_cameraRotationIsNotEnabled() =
        testScope.runTest {
            underTest.apply {
                overrideResource(com.android.internal.R.bool.config_allowRotationResolver, false)
            }
            setupControllersToEnableCameraRotation()
            val lastValue by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()

            assertThat(lastValue!!.isCameraRotationEnabled).isEqualTo(false)
        }

    private fun setupControllersToEnableCameraRotation() {
        rotationController.setRotationLocked(true, CALLER)
        batteryController.setPowerSaveMode(false)
        fakeCameraSensorPrivacyRepository.setEnabled(testUser, false)
        fakeCameraAutoRotateRepository.setEnabled(testUser, true)
    }

    private companion object {
        private const val CALLER = "RotationLockTileDataInteractorTest"
        private const val TEST_PACKAGE_NAME = "com.test"
    }
}
