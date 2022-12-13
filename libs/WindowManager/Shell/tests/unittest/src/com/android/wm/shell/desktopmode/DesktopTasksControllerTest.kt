/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.desktopmode

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.ExtendedMockito.never
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever


@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopTasksControllerTest : ShellTestCase() {

    @Mock
    lateinit var testExecutor: ShellExecutor
    @Mock
    lateinit var shellController: ShellController
    @Mock
    lateinit var shellTaskOrganizer: ShellTaskOrganizer
    @Mock
    lateinit var transitions: Transitions

    lateinit var mockitoSession: StaticMockitoSession
    lateinit var controller: DesktopTasksController
    lateinit var shellInit: ShellInit
    lateinit var desktopModeTaskRepository: DesktopModeTaskRepository

    @Before
    fun setUp() {
        mockitoSession = mockitoSession().mockStatic(DesktopModeStatus::class.java).startMocking()
        whenever(DesktopModeStatus.isProto2Enabled()).thenReturn(true)

        shellInit = Mockito.spy(ShellInit(testExecutor))
        desktopModeTaskRepository = DesktopModeTaskRepository()

        controller = createController()

        shellInit.init()
    }

    private fun createController(): DesktopTasksController {
        return DesktopTasksController(context, shellInit, shellController,
                shellTaskOrganizer, transitions, desktopModeTaskRepository, TestShellExecutor())
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun instantiate_addInitCallback() {
        verify(shellInit).addInitCallback(any(), any<DesktopTasksController>())
    }

    @Test
    fun instantiate_flagOff_doNotAddInitCallback() {
        whenever(DesktopModeStatus.isProto2Enabled()).thenReturn(false)
        clearInvocations(shellInit)

        createController()

        verify(shellInit, never()).addInitCallback(any(), any<DesktopTasksController>())
    }
}