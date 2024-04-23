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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.phone

import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.doze.DozeHost
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class DozeServiceHostCoroutinesTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val sceneContainerRepository = kosmos.sceneContainerRepository
    private val keyguardInteractor = kosmos.keyguardInteractor

    val underTest =
        kosmos.dozeServiceHost.apply {
            initialize(
                /* centralSurfaces = */ mock(),
                /* statusBarKeyguardViewManager = */ mock(),
                /* notificationShadeWindowViewController = */ mock(),
                /* ambientIndicationContainer = */ mock(),
            )
        }

    @Test
    @EnableSceneContainer
    fun startStopDozing() =
        testScope.runTest {
            val isDozing by collectLastValue(keyguardInteractor.isDozing)

            // GIVEN a callback is set
            val callback: DozeHost.Callback = mock()
            underTest.addCallback(callback)
            // AND we are on the lock screen
            sceneContainerRepository.changeScene(Scenes.Lockscreen)
            // AND dozing is not requested yet
            assertThat(underTest.dozingRequested).isFalse()

            // WHEN dozing started
            underTest.startDozing()
            runCurrent()

            // THEN isDozing is set to true
            assertThat(isDozing).isTrue()
            assertThat(underTest.dozingRequested).isTrue()
            verify(callback).onDozingChanged(true)

            // WHEN dozing stopped
            underTest.stopDozing()
            runCurrent()

            // THEN isDozing is set to false
            assertThat(isDozing).isFalse()
            assertThat(underTest.dozingRequested).isFalse()
            verify(callback).onDozingChanged(false)
        }
}
