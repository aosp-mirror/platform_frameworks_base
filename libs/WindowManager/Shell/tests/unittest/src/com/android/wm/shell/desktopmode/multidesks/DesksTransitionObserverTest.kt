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
package com.android.wm.shell.desktopmode.multidesks

import android.os.Binder
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowManager.TRANSIT_CLOSE
import android.window.TransitionInfo
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.desktopmode.DesktopRepository
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.sysui.ShellInit
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

/**
 * Tests for [DesksTransitionObserver].
 *
 * Build/Install/Run: atest WMShellUnitTests:DesksTransitionObserverTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesksTransitionObserverTest : ShellTestCase() {

    @JvmField @Rule val setFlagsRule = SetFlagsRule()

    private lateinit var desktopUserRepositories: DesktopUserRepositories
    private lateinit var observer: DesksTransitionObserver

    private val repository: DesktopRepository
        get() = desktopUserRepositories.current

    @Before
    fun setUp() {
        desktopUserRepositories =
            DesktopUserRepositories(
                context,
                ShellInit(TestShellExecutor()),
                /* shellController= */ mock(),
                /* persistentRepository= */ mock(),
                /* repositoryInitializer= */ mock(),
                /* mainCoroutineScope= */ mock(),
                /* userManager= */ mock(),
            )
        observer = DesksTransitionObserver(desktopUserRepositories)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MULTIPLE_DESKTOPS_BACKEND)
    fun onTransitionReady_removeDesk_removesFromRepository() {
        val transition = Binder()
        val removeTransition =
            DeskTransition.RemoveDesk(
                transition,
                displayId = DEFAULT_DISPLAY,
                deskId = 5,
                tasks = setOf(10, 11),
                onDeskRemovedListener = null,
            )
        repository.addDesk(DEFAULT_DISPLAY, deskId = 5)

        observer.addPendingTransition(removeTransition)
        observer.onTransitionReady(
            transition = transition,
            info = TransitionInfo(TRANSIT_CLOSE, /* flags= */ 0),
        )

        assertThat(repository.getDeskIds(DEFAULT_DISPLAY)).doesNotContain(5)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MULTIPLE_DESKTOPS_BACKEND)
    fun onTransitionReady_removeDesk_invokesOnRemoveListener() {
        class FakeOnDeskRemovedListener : OnDeskRemovedListener {
            var lastDeskRemoved: Int? = null

            override fun onDeskRemoved(lastDisplayId: Int, deskId: Int) {
                lastDeskRemoved = deskId
            }
        }
        val transition = Binder()
        val removeListener = FakeOnDeskRemovedListener()
        val removeTransition =
            DeskTransition.RemoveDesk(
                transition,
                displayId = DEFAULT_DISPLAY,
                deskId = 5,
                tasks = setOf(10, 11),
                onDeskRemovedListener = removeListener,
            )
        repository.addDesk(DEFAULT_DISPLAY, deskId = 5)

        observer.addPendingTransition(removeTransition)
        observer.onTransitionReady(
            transition = transition,
            info = TransitionInfo(TRANSIT_CLOSE, /* flags= */ 0),
        )

        assertThat(removeListener.lastDeskRemoved).isEqualTo(5)
    }
}
