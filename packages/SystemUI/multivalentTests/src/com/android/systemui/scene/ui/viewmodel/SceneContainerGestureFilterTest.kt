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

package com.android.systemui.scene.ui.viewmodel

import android.graphics.Region
import android.view.setSystemGestureExclusionRegion
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.scene.sceneContainerGestureFilterFactory
import com.android.systemui.settings.displayTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SceneContainerGestureFilterTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val displayId = kosmos.displayTracker.defaultDisplayId

    private val underTest = kosmos.sceneContainerGestureFilterFactory.create(displayId)
    private val activationJob = Job()

    @Test
    fun shouldFilterGesture_whenNoRegion_returnsFalse() =
        testScope.runTest {
            activate()
            setSystemGestureExclusionRegion(displayId, null)
            runCurrent()

            assertThat(underTest.shouldFilterGesture(Offset(100f, 100f))).isFalse()
        }

    @Test
    fun shouldFilterGesture_whenOutsideRegion_returnsFalse() =
        testScope.runTest {
            activate()
            setSystemGestureExclusionRegion(displayId, Region(0, 0, 200, 200))
            runCurrent()

            assertThat(underTest.shouldFilterGesture(Offset(300f, 100f))).isFalse()
        }

    @Test
    fun shouldFilterGesture_whenInsideRegion_returnsTrue() =
        testScope.runTest {
            activate()
            setSystemGestureExclusionRegion(displayId, Region(0, 0, 200, 200))
            runCurrent()

            assertThat(underTest.shouldFilterGesture(Offset(100f, 100f))).isTrue()
        }

    @Test(expected = IllegalStateException::class)
    fun shouldFilterGesture_beforeActivation_throws() =
        testScope.runTest {
            setSystemGestureExclusionRegion(displayId, Region(0, 0, 200, 200))
            runCurrent()

            underTest.shouldFilterGesture(Offset(100f, 100f))
        }

    @Test(expected = IllegalStateException::class)
    fun shouldFilterGesture_afterCancellation_throws() =
        testScope.runTest {
            activate()
            setSystemGestureExclusionRegion(displayId, Region(0, 0, 200, 200))
            runCurrent()

            cancel()

            underTest.shouldFilterGesture(Offset(100f, 100f))
        }

    private fun TestScope.activate() {
        underTest.activateIn(testScope, activationJob)
        runCurrent()
    }

    private fun TestScope.cancel() {
        activationJob.cancel()
        runCurrent()
    }
}
