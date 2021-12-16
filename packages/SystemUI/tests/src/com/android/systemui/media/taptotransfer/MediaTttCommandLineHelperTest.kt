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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executor

@SmallTest
class MediaTttCommandLineHelperTest : SysuiTestCase() {

    private val inlineExecutor = Executor { command -> command.run() }
    private val commandRegistry = CommandRegistry(context, inlineExecutor)
    private val pw = PrintWriter(StringWriter())

    private lateinit var mediaTttCommandLineHelper: MediaTttCommandLineHelper

    @Mock
    private lateinit var mediaTttChipController: MediaTttChipController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mediaTttCommandLineHelper =
            MediaTttCommandLineHelper(
                commandRegistry, mediaTttChipController, FakeExecutor(FakeSystemClock())
            )
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_addCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the add command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            ADD_CHIP_COMMAND_TAG
        ) { EmptyCommand() }
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_removeCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the remove command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            REMOVE_CHIP_COMMAND_TAG
        ) { EmptyCommand() }
    }

    @Test
    fun moveCloserToTransfer_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())

        verify(mediaTttChipController).displayChip(any(MoveCloserToTransfer::class.java))
    }

    @Test
    fun transferInitiated_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getTransferInitiatedCommand())

        verify(mediaTttChipController).displayChip(any(TransferInitiated::class.java))
    }

    @Test
    fun transferSucceeded_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getTransferSucceededCommand())

        verify(mediaTttChipController).displayChip(any(TransferSucceeded::class.java))
    }

    @Test
    fun removeCommand_chipRemoved() {
        commandRegistry.onShellCommand(pw, arrayOf(REMOVE_CHIP_COMMAND_TAG))

        verify(mediaTttChipController).removeChip()
    }

    private fun getMoveCloserToTransferCommand(): Array<String> =
        arrayOf(
            ADD_CHIP_COMMAND_TAG,
            DEVICE_NAME,
            MOVE_CLOSER_TO_TRANSFER_COMMAND_NAME
        )

    private fun getTransferInitiatedCommand(): Array<String> =
        arrayOf(
            ADD_CHIP_COMMAND_TAG,
            DEVICE_NAME,
            TRANSFER_INITIATED_COMMAND_NAME
        )

    private fun getTransferSucceededCommand(): Array<String> =
        arrayOf(
            ADD_CHIP_COMMAND_TAG,
            DEVICE_NAME,
            TRANSFER_SUCCEEDED_COMMAND_NAME
        )

    class EmptyCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
        }

        override fun help(pw: PrintWriter) {
        }
    }
}

private const val DEVICE_NAME = "My Tablet"
