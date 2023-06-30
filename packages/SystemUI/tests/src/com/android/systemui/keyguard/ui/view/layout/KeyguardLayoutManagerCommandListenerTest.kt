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
 */

package com.android.systemui.keyguard.ui.view.layout

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import java.io.PrintWriter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class KeyguardLayoutManagerCommandListenerTest : SysuiTestCase() {
    private lateinit var keyguardLayoutManagerCommandListener: KeyguardLayoutManagerCommandListener
    @Mock private lateinit var commandRegistry: CommandRegistry
    @Mock private lateinit var keyguardLayoutManager: KeyguardLayoutManager
    @Mock private lateinit var pw: PrintWriter
    private lateinit var command: () -> Command

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        keyguardLayoutManagerCommandListener =
            KeyguardLayoutManagerCommandListener(
                commandRegistry,
                keyguardLayoutManager,
            )
        keyguardLayoutManagerCommandListener.start()
        command =
            withArgCaptor<() -> Command> {
                verify(commandRegistry).registerCommand(eq("layout"), capture())
            }
    }

    @Test
    fun testHelp() {
        command().execute(pw, listOf("help"))
        verify(pw, atLeastOnce()).println(anyString())
        verify(keyguardLayoutManager, never()).transitionToLayout(anyString())
    }

    @Test
    fun testBlank() {
        command().execute(pw, listOf())
        verify(pw, atLeastOnce()).println(anyString())
        verify(keyguardLayoutManager, never()).transitionToLayout(anyString())
    }

    @Test
    fun testValidArg() {
        bindFakeIdMapToLayoutManager()
        command().execute(pw, listOf("fake"))
        verify(keyguardLayoutManager).transitionToLayout("fake")
    }

    private fun bindFakeIdMapToLayoutManager() {
        val map = mapOf("fake" to mock(LockscreenLayout::class.java))
        whenever(keyguardLayoutManager.layoutIdMap).thenReturn(map)
    }
}
