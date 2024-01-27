/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.ComponentName
import android.content.pm.PackageManager
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.statusbar.policy.SafetyController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class SafetyCenterAutoAddableTest : SysuiTestCase() {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Mock private lateinit var safetyController: SafetyController
    @Mock private lateinit var packageManager: PackageManager
    @Captor
    private lateinit var safetyControllerListenerCaptor: ArgumentCaptor<SafetyController.Listener>

    private lateinit var underTest: SafetyCenterAutoAddable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.ensureTestableResources()

        // Set these by default, will also test special cases
        context.orCreateTestableResources.addOverride(
            R.string.safety_quick_settings_tile_class,
            SAFETY_TILE_CLASS_NAME
        )
        whenever(packageManager.permissionControllerPackageName)
            .thenReturn(PERMISSION_CONTROLLER_PACKAGE_NAME)

        underTest =
            SafetyCenterAutoAddable(
                safetyController,
                packageManager,
                context.resources,
                testDispatcher,
            )
    }

    @Test
    fun strategyAlwaysTrack() =
        testScope.runTest {
            assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.Always)
        }

    @Test
    fun tileAlwaysAdded() =
        testScope.runTest {
            val signal by collectLastValue(underTest.autoAddSignal(0))

            assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
        }

    @Test
    fun safetyCenterDisabled_removeSignal() =
        testScope.runTest {
            val signal by collectLastValue(underTest.autoAddSignal(0))
            runCurrent()

            verify(safetyController).addCallback(capture(safetyControllerListenerCaptor))
            safetyControllerListenerCaptor.value.onSafetyCenterEnableChanged(false)

            assertThat(signal).isEqualTo(AutoAddSignal.Remove(SPEC))
        }

    @Test
    fun safetyCenterEnabled_newAddSignal() =
        testScope.runTest {
            val signals by collectValues(underTest.autoAddSignal(0))
            runCurrent()

            verify(safetyController).addCallback(capture(safetyControllerListenerCaptor))
            safetyControllerListenerCaptor.value.onSafetyCenterEnableChanged(true)

            assertThat(signals.size).isEqualTo(2)
            assertThat(signals.last()).isEqualTo(AutoAddSignal.Add(SPEC))
        }

    @Test
    fun flowCancelled_removeListener() =
        testScope.runTest {
            val job = launch { underTest.autoAddSignal(0).collect() }
            runCurrent()

            verify(safetyController).addCallback(capture(safetyControllerListenerCaptor))

            job.cancel()
            runCurrent()
            verify(safetyController).removeCallback(safetyControllerListenerCaptor.value)
        }

    @Test
    fun emptyClassName_noSignals() =
        testScope.runTest {
            context.orCreateTestableResources.addOverride(
                R.string.safety_quick_settings_tile_class,
                ""
            )
            val signal by collectLastValue(underTest.autoAddSignal(0))
            runCurrent()

            verify(safetyController, never()).addCallback(any())

            assertThat(signal).isNull()
        }

    companion object {
        private const val SAFETY_TILE_CLASS_NAME = "cls"
        private const val PERMISSION_CONTROLLER_PACKAGE_NAME = "pkg"
        private val SPEC by lazy {
            TileSpec.create(
                ComponentName(PERMISSION_CONTROLLER_PACKAGE_NAME, SAFETY_TILE_CLASS_NAME)
            )
        }
    }
}
