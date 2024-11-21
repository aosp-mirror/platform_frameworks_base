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

package com.android.wm.shell.desktopmode.compatui

import android.os.Binder
import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_OPEN
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.DesktopRepository
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFullscreenTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFullscreenTaskBuilder
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createSystemModalTask
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.TransitionInfoBuilder
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class SystemModalsTransitionHandlerTest : ShellTestCase() {
    private val mainExecutor = mock<ShellExecutor>()
    private val animExecutor = mock<ShellExecutor>()
    private val shellInit = mock<ShellInit>()
    private val transitions = mock<Transitions>()
    private val desktopRepository = mock<DesktopRepository>()
    private val startT = mock<SurfaceControl.Transaction>()
    private val finishT = mock<SurfaceControl.Transaction>()

    private lateinit var transitionHandler: SystemModalsTransitionHandler

    @Before
    fun setUp() {
        // Simulate having one Desktop task so that we see Desktop Mode as active
        whenever(desktopRepository.getVisibleTaskCount(anyInt())).thenReturn(1)
        transitionHandler = createTransitionHandler()
    }

    private fun createTransitionHandler() =
        SystemModalsTransitionHandler(
            context,
            mainExecutor,
            animExecutor,
            shellInit,
            transitions,
            desktopRepository,
        )

    @Test
    fun instantiate_addsInitCallback() {
        verify(shellInit).addInitCallback(any(), any<SystemModalsTransitionHandler>())
    }

    @Test
    fun startAnimation_desktopNotActive_doesNotAnimate() {
        whenever(desktopRepository.getVisibleTaskCount(anyInt())).thenReturn(1)
        val info =
            TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN, createSystemModalTask())
                .build()

        assertThat(transitionHandler.startAnimation(Binder(), info, startT, finishT) {}).isTrue()
    }

    @Test
    fun startAnimation_launchingSystemModal_animates() {
        val info =
            TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN, createSystemModalTask())
                .build()

        assertThat(transitionHandler.startAnimation(Binder(), info, startT, finishT) {}).isTrue()
    }

    @Test
    fun startAnimation_nonLaunchingSystemModal_doesNotAnimate() {
        val info =
            TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_CHANGE, createSystemModalTask())
                .build()

        assertThat(transitionHandler.startAnimation(Binder(), info, startT, finishT) {}).isFalse()
    }

    @Test
    fun startAnimation_launchingFullscreenTask_doesNotAnimate() {
        val info =
            TransitionInfoBuilder(TRANSIT_OPEN)
                .addChange(TRANSIT_OPEN, createFullscreenTask())
                .build()

        assertThat(transitionHandler.startAnimation(Binder(), info, startT, finishT) {}).isFalse()
    }

    @Test
    fun startAnimation_closingSystemModal_animates() {
        val info =
            TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_CLOSE, createSystemModalTask())
                .build()

        assertThat(transitionHandler.startAnimation(Binder(), info, startT, finishT) {}).isTrue()
    }

    @Test
    fun startAnimation_closingFullscreenTask_doesNotAnimate() {
        val info =
            TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_CLOSE, createFullscreenTask())
                .build()

        assertThat(transitionHandler.startAnimation(Binder(), info, startT, finishT) {}).isFalse()
    }

    @Test
    fun startAnimation_closingPreviouslyLaunchedSystemModal_animates() {
        val systemModalTask = createSystemModalTask()
        val nonModalSystemModalTask =
            createFullscreenTaskBuilder().setTaskId(systemModalTask.taskId).build()
        val launchInfo =
            TransitionInfoBuilder(TRANSIT_OPEN).addChange(TRANSIT_OPEN, systemModalTask).build()
        transitionHandler.startAnimation(Binder(), launchInfo, startT, finishT) {}
        val closeInfo =
            TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_CLOSE, nonModalSystemModalTask)
                .build()

        assertThat(transitionHandler.startAnimation(Binder(), closeInfo, startT, finishT) {})
            .isTrue()
    }
}
