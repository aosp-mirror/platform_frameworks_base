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
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.CameraLaunchSourceModel
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.CommandQueue.Callbacks
import com.android.systemui.util.mockito.argumentCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardInteractorTest : SysuiTestCase() {
    @Mock private lateinit var commandQueue: CommandQueue

    private lateinit var underTest: KeyguardInteractor
    private lateinit var repository: FakeKeyguardRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        repository = FakeKeyguardRepository()
        underTest = KeyguardInteractor(repository, commandQueue)
    }

    @Test
    fun onCameraLaunchDetected() = runTest {
        val flow = underTest.onCameraLaunchDetected
        var cameraLaunchSource = collectLastValue(flow)
        runCurrent()

        val captor = argumentCaptor<CommandQueue.Callbacks>()
        verify(commandQueue).addCallback(captor.capture())

        captor.value.onCameraLaunchGestureDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE)
        assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.WIGGLE)

        captor.value.onCameraLaunchGestureDetected(
            StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
        )
        assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.POWER_DOUBLE_TAP)

        captor.value.onCameraLaunchGestureDetected(
            StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER
        )
        assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.LIFT_TRIGGER)

        captor.value.onCameraLaunchGestureDetected(
            StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE
        )
        assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.QUICK_AFFORDANCE)

        flow.onCompletion { verify(commandQueue).removeCallback(captor.value) }
    }
}
