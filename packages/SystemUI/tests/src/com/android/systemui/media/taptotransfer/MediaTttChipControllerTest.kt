/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.media.taptotransfer

import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executor

@SmallTest
class MediaTttChipControllerTest : SysuiTestCase() {

    private lateinit var mediaTttChipController: MediaTttChipController

    private val inlineExecutor = Executor { command -> command.run() }
    private val commandRegistry = CommandRegistry(context, inlineExecutor)
    private val pw = PrintWriter(StringWriter())

    @Mock
    private lateinit var windowManager: WindowManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mediaTttChipController = MediaTttChipController(context, commandRegistry, windowManager)
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_addCommmandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the add command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            MediaTttChipController.ADD_CHIP_COMMAND_TAG
        ) { EmptyCommand() }
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_removeCommmandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the remove command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            MediaTttChipController.REMOVE_CHIP_COMMAND_TAG
        ) { EmptyCommand() }
    }

    @Test
    fun addChipCommand_chipAdded() {
        commandRegistry.onShellCommand(pw, arrayOf(MediaTttChipController.ADD_CHIP_COMMAND_TAG))

        verify(windowManager).addView(any(), any())
    }

    @Test
    fun addChipCommand_twice_chipNotAddedTwice() {
        commandRegistry.onShellCommand(pw, arrayOf(MediaTttChipController.ADD_CHIP_COMMAND_TAG))
        reset(windowManager)

        commandRegistry.onShellCommand(pw, arrayOf(MediaTttChipController.ADD_CHIP_COMMAND_TAG))
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun removeChipCommand_chipRemoved() {
        // First, add the chip
        commandRegistry.onShellCommand(pw, arrayOf(MediaTttChipController.ADD_CHIP_COMMAND_TAG))

        // Then, remove it
        commandRegistry.onShellCommand(pw, arrayOf(MediaTttChipController.REMOVE_CHIP_COMMAND_TAG))

        verify(windowManager).removeView(any())
    }

    @Test
    fun removeChipCommand_noAdd_viewNotRemoved() {
        commandRegistry.onShellCommand(pw, arrayOf(MediaTttChipController.REMOVE_CHIP_COMMAND_TAG))

        verify(windowManager, never()).removeView(any())
    }

    class EmptyCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
        }

        override fun help(pw: PrintWriter) {
        }
    }
}
