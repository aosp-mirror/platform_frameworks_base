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
import android.content.Intent
import android.service.controls.ControlsProviderService.CONTROLS_SURFACE_ACTIVITY_PANEL
import android.service.controls.ControlsProviderService.CONTROLS_SURFACE_DREAM
import android.service.controls.ControlsProviderService.EXTRA_CONTROLS_SURFACE
import android.window.TaskFragmentInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.settings.FakeControlsSettingsRepository
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.wakelock.WakeLockFake
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import org.mockito.kotlin.whenever

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

    private val taskFragmentComponent = mock<TaskFragmentComponent>()
    private val activity = mock<Activity>()
    private val onCreateCallback = argumentCaptor<(TaskFragmentInfo) -> Unit>()
    private val onInfoChangedCallback = argumentCaptor<(TaskFragmentInfo) -> Unit>()
    private val hideCallback = argumentCaptor<() -> Unit>()
    private val dreamServiceDelegate =
        mock<DreamServiceDelegate> { on { getActivity(any()) } doReturn activity }

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

    private val underTest: HomeControlsDreamService by lazy { buildService() }

    @Before
    fun setup() {
        whenever(kosmos.controlsComponent.getControlsListingController())
            .thenReturn(Optional.of(kosmos.controlsListingController))
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
            val serviceWithNullActivity =
                buildService(
                    mock<DreamServiceDelegate> { on { getActivity(underTest) } doReturn null }
                )

            serviceWithNullActivity.onAttachedToWindow()
            verify(taskFragmentComponentFactory, never()).create(any(), any(), any(), any())
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
            whenever(dreamServiceDelegate.redirectWake(any())).thenReturn(false)
            underTest.onAttachedToWindow()
            onCreateCallback.firstValue.invoke(mock<TaskFragmentInfo>())
            verify(taskFragmentComponent, times(1)).startActivityInTaskFragment(intentMatcher())

            // Task fragment becomes empty
            onInfoChangedCallback.firstValue.invoke(
                mock<TaskFragmentInfo> { on { isEmpty } doReturn true }
            )
            advanceUntilIdle()
            // Dream is finished and activity is not restarted
            verify(taskFragmentComponent, times(1)).startActivityInTaskFragment(intentMatcher())
            verify(dreamServiceDelegate, never()).wakeUp(any())
            verify(dreamServiceDelegate).finish(any())
        }

    @Test
    fun testRestartsActivityWhenRedirectingWakes() =
        testScope.runTest {
            whenever(dreamServiceDelegate.redirectWake(any())).thenReturn(true)
            underTest.onAttachedToWindow()
            onCreateCallback.firstValue.invoke(mock<TaskFragmentInfo>())
            verify(taskFragmentComponent, times(1)).startActivityInTaskFragment(intentMatcher())

            // Task fragment becomes empty
            onInfoChangedCallback.firstValue.invoke(
                mock<TaskFragmentInfo> { on { isEmpty } doReturn true }
            )
            advanceUntilIdle()
            // Activity is restarted instead of finishing the dream.
            verify(taskFragmentComponent, times(2)).startActivityInTaskFragment(intentMatcher())
            verify(dreamServiceDelegate).wakeUp(any())
            verify(dreamServiceDelegate, never()).finish(any())
        }

    private fun intentMatcher() =
        argThat<Intent> {
            getIntExtra(EXTRA_CONTROLS_SURFACE, CONTROLS_SURFACE_ACTIVITY_PANEL) ==
                CONTROLS_SURFACE_DREAM
        }

    private fun buildService(
        activityProvider: DreamServiceDelegate = dreamServiceDelegate
    ): HomeControlsDreamService =
        with(kosmos) {
            return HomeControlsDreamService(
                controlsSettingsRepository = FakeControlsSettingsRepository(),
                taskFragmentFactory = taskFragmentComponentFactory,
                homeControlsComponentInteractor = homeControlsComponentInteractor,
                wakeLockBuilder = fakeWakeLockBuilder,
                dreamServiceDelegate = activityProvider,
                bgDispatcher = testDispatcher,
                logBuffer = logcatLogBuffer("HomeControlsDreamServiceTest")
            )
        }
}
