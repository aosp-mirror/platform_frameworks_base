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
package com.android.systemui.dreams.homecontrols

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.powerManager
import android.service.controls.ControlsProviderService.CONTROLS_SURFACE_ACTIVITY_PANEL
import android.service.controls.ControlsProviderService.CONTROLS_SURFACE_DREAM
import android.service.controls.ControlsProviderService.EXTRA_CONTROLS_SURFACE
import android.service.dreams.DreamService
import android.window.TaskFragmentInfo
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dreams.homecontrols.service.TaskFragmentComponent
import com.android.systemui.dreams.homecontrols.shared.model.HomeControlsComponentInfo
import com.android.systemui.dreams.homecontrols.shared.model.fakeHomeControlsDataSource
import com.android.systemui.dreams.homecontrols.shared.model.homeControlsDataSource
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.android.systemui.util.wakelock.WakeLockFake
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeControlsDreamServiceTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val fakeWakeLock = WakeLockFake()
    private val fakeWakeLockBuilder by lazy {
        WakeLockFake.Builder(context).apply { setWakeLock(fakeWakeLock) }
    }

    private val lifecycleOwner = TestLifecycleOwner(coroutineDispatcher = kosmos.testDispatcher)

    private val taskFragmentComponent = mock<TaskFragmentComponent>()
    private val activity = mock<Activity>()
    private val onCreateCallback = argumentCaptor<(TaskFragmentInfo) -> Unit>()
    private val onInfoChangedCallback = argumentCaptor<(TaskFragmentInfo) -> Unit>()
    private val hideCallback = argumentCaptor<() -> Unit>()
    private var dreamService =
        mock<DreamService> {
            on { activity } doReturn activity
            on { redirectWake } doReturn false
        }

    private val taskFragmentComponentFactory =
        mock<TaskFragmentComponent.Factory> {
            on {
                create(
                    activity = eq(activity),
                    onCreateCallback = onCreateCallback.capture(),
                    onInfoChangedCallback = onInfoChangedCallback.capture(),
                    hide = hideCallback.capture(),
                )
            } doReturn taskFragmentComponent
        }

    private val underTest: HomeControlsDreamServiceImpl by lazy {
        with(kosmos) {
            HomeControlsDreamServiceImpl(
                taskFragmentFactory = taskFragmentComponentFactory,
                wakeLockBuilder = fakeWakeLockBuilder,
                powerManager = powerManager,
                systemClock = fakeSystemClock,
                dataSource = homeControlsDataSource,
                logBuffer = logcatLogBuffer("HomeControlsDreamServiceTest"),
                service = dreamService,
                lifecycleOwner = lifecycleOwner,
            )
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(kosmos.testDispatcher)
        kosmos.fakeHomeControlsDataSource.setComponentInfo(
            HomeControlsComponentInfo(PANEL_COMPONENT, true)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testOnAttachedToWindowCreatesTaskFragmentComponent() =
        testScope.runTest {
            underTest.onAttachedToWindow()
            verify(taskFragmentComponentFactory).create(any(), any(), any(), any())
        }

    @Test
    fun testOnDetachedFromWindowDestroyTaskFragmentComponent() =
        testScope.runTest {
            underTest.onAttachedToWindow()
            underTest.onDetachedFromWindow()
            verify(taskFragmentComponent).destroy()
        }

    @Test
    fun testNotCreatingTaskFragmentComponentWhenActivityIsNull() =
        testScope.runTest {
            dreamService = mock<DreamService> { on { activity } doReturn null }
            underTest.onAttachedToWindow()
            verify(taskFragmentComponentFactory, never()).create(any(), any(), any(), any())
            verify(dreamService).finish()
        }

    @Test
    fun testAttachWindow_wakeLockAcquired() =
        testScope.runTest {
            underTest.onAttachedToWindow()
            assertThat(fakeWakeLock.isHeld).isTrue()
        }

    @Test
    fun testDetachWindow_wakeLockCanBeReleased() =
        testScope.runTest {
            underTest.onAttachedToWindow()
            assertThat(fakeWakeLock.isHeld).isTrue()

            underTest.onDetachedFromWindow()
            assertThat(fakeWakeLock.isHeld).isFalse()
        }

    @Test
    fun testFinishesDreamWithoutRestartingActivityWhenNotRedirectingWakes() =
        testScope.runTest {
            underTest.onAttachedToWindow()
            onCreateCallback.firstValue.invoke(mock<TaskFragmentInfo>())
            runCurrent()
            verify(taskFragmentComponent, times(1)).startActivityInTaskFragment(intentMatcher())

            // Task fragment becomes empty
            onInfoChangedCallback.firstValue.invoke(
                mock<TaskFragmentInfo> { on { isEmpty } doReturn true }
            )
            advanceUntilIdle()
            // Dream is finished and activity is not restarted
            verify(taskFragmentComponent, times(1)).startActivityInTaskFragment(intentMatcher())
            verify(dreamService, never()).wakeUp()
            verify(dreamService).finish()
        }

    @Test
    fun testRestartsActivityWhenRedirectingWakes() =
        testScope.runTest {
            dreamService =
                mock<DreamService> {
                    on { activity } doReturn activity
                    on { redirectWake } doReturn true
                }
            underTest.onAttachedToWindow()
            onCreateCallback.firstValue.invoke(mock<TaskFragmentInfo>())
            runCurrent()
            verify(taskFragmentComponent, times(1)).startActivityInTaskFragment(intentMatcher())

            // Task fragment becomes empty
            onInfoChangedCallback.firstValue.invoke(
                mock<TaskFragmentInfo> { on { isEmpty } doReturn true }
            )
            advanceUntilIdle()

            // Activity is restarted instead of finishing the dream.
            verify(taskFragmentComponent, times(2)).startActivityInTaskFragment(intentMatcher())
            verify(dreamService).wakeUp()
            verify(dreamService, never()).finish()
        }

    private fun intentMatcher() =
        argThat<Intent> {
            getIntExtra(EXTRA_CONTROLS_SURFACE, CONTROLS_SURFACE_ACTIVITY_PANEL) ==
                CONTROLS_SURFACE_DREAM && component == PANEL_COMPONENT
        }

    private companion object {
        val PANEL_COMPONENT = ComponentName("test.pkg", "test.panel")
    }
}
