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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.app.StatusBarManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags.FACE_AUTH_REFACTOR
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.CameraLaunchSourceModel
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.CommandQueue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardInteractorTest : SysuiTestCase() {
    private lateinit var commandQueue: FakeCommandQueue
    private lateinit var featureFlags: FakeFeatureFlags
    private lateinit var testScope: TestScope

    private lateinit var underTest: KeyguardInteractor
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var bouncerRepository: FakeKeyguardBouncerRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        featureFlags = FakeFeatureFlags().apply { set(FACE_AUTH_REFACTOR, true) }
        commandQueue = FakeCommandQueue(mock(Context::class.java), mock(DisplayTracker::class.java))
        testScope = TestScope()
        repository = FakeKeyguardRepository()
        bouncerRepository = FakeKeyguardBouncerRepository()
        underTest =
            KeyguardInteractor(
                repository,
                commandQueue,
                featureFlags,
                bouncerRepository,
            )
    }

    @Test
    fun onCameraLaunchDetected() =
        testScope.runTest {
            val flow = underTest.onCameraLaunchDetected
            var cameraLaunchSource = collectLastValue(flow)
            runCurrent()

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE)
            }
            assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.WIGGLE)

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
                )
            }
            assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.POWER_DOUBLE_TAP)

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER)
            }
            assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.LIFT_TRIGGER)

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE
                )
            }
            assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.QUICK_AFFORDANCE)

            flow.onCompletion { assertThat(commandQueue.callbackCount()).isEqualTo(0) }
        }

    @Test
    fun testKeyguardGuardVisibilityStopsSecureCamera() =
        testScope.runTest {
            val secureCameraActive = collectLastValue(underTest.isSecureCameraActive)
            runCurrent()

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
                )
            }

            assertThat(secureCameraActive()).isTrue()

            // Keyguard is showing but occluded
            repository.setKeyguardShowing(true)
            repository.setKeyguardOccluded(true)
            assertThat(secureCameraActive()).isTrue()

            // Keyguard is showing and not occluded
            repository.setKeyguardOccluded(false)
            assertThat(secureCameraActive()).isFalse()
        }

    @Test
    fun testBouncerShowingResetsSecureCameraState() =
        testScope.runTest {
            val secureCameraActive = collectLastValue(underTest.isSecureCameraActive)
            runCurrent()

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
                )
            }
            assertThat(secureCameraActive()).isTrue()

            // Keyguard is showing and not occluded
            repository.setKeyguardShowing(true)
            repository.setKeyguardOccluded(true)
            assertThat(secureCameraActive()).isTrue()

            bouncerRepository.setPrimaryShow(true)
            assertThat(secureCameraActive()).isFalse()
        }

    @Test
    fun keyguardVisibilityIsDefinedAsKeyguardShowingButNotOccluded() = runTest {
        var isVisible = collectLastValue(underTest.isKeyguardVisible)
        repository.setKeyguardShowing(true)
        repository.setKeyguardOccluded(false)

        assertThat(isVisible()).isTrue()

        repository.setKeyguardOccluded(true)
        assertThat(isVisible()).isFalse()

        repository.setKeyguardShowing(false)
        repository.setKeyguardOccluded(true)
        assertThat(isVisible()).isFalse()
    }

    @Test
    fun secureCameraIsNotActiveWhenNoCameraLaunchEventHasBeenFiredYet() =
        testScope.runTest {
            val secureCameraActive = collectLastValue(underTest.isSecureCameraActive)
            runCurrent()

            assertThat(secureCameraActive()).isFalse()
        }
}

class FakeCommandQueue(val context: Context, val displayTracker: DisplayTracker) :
    CommandQueue(context, displayTracker) {
    private val callbacks = mutableListOf<Callbacks>()

    override fun addCallback(callback: Callbacks) {
        callbacks.add(callback)
    }

    override fun removeCallback(callback: Callbacks) {
        callbacks.remove(callback)
    }

    fun doForEachCallback(func: (callback: Callbacks) -> Unit) {
        callbacks.forEach { func(it) }
    }

    fun callbackCount(): Int = callbacks.size
}
