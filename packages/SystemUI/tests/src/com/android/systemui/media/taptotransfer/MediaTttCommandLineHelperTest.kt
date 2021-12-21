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
import com.android.systemui.media.taptotransfer.receiver.ChipStateReceiver
import com.android.systemui.media.taptotransfer.receiver.MediaTttChipControllerReceiver
import com.android.systemui.media.taptotransfer.sender.MediaTttChipControllerSender
import com.android.systemui.media.taptotransfer.sender.MoveCloserToTransfer
import com.android.systemui.media.taptotransfer.sender.TransferInitiated
import com.android.systemui.media.taptotransfer.sender.TransferSucceeded
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
    private lateinit var mediaTttChipControllerSender: MediaTttChipControllerSender
    @Mock
    private lateinit var mediaTttChipControllerReceiver: MediaTttChipControllerReceiver

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mediaTttCommandLineHelper =
            MediaTttCommandLineHelper(
                commandRegistry,
                context,
                mediaTttChipControllerSender,
                mediaTttChipControllerReceiver,
                FakeExecutor(FakeSystemClock())
            )
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_addSenderCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the add command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            ADD_CHIP_COMMAND_SENDER_TAG
        ) { EmptyCommand() }
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_removeSenderCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the remove command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            REMOVE_CHIP_COMMAND_SENDER_TAG
        ) { EmptyCommand() }
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_addReceiverCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the add command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            ADD_CHIP_COMMAND_RECEIVER_TAG
        ) { EmptyCommand() }
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_removeReceiverCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the remove command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(
            REMOVE_CHIP_COMMAND_RECEIVER_TAG
        ) { EmptyCommand() }
    }

    @Test
    fun sender_moveCloserToTransfer_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getMoveCloserToTransferCommand())

        verify(mediaTttChipControllerSender).displayChip(any(MoveCloserToTransfer::class.java))
    }

    @Test
    fun sender_transferInitiated_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getTransferInitiatedCommand())

        verify(mediaTttChipControllerSender).displayChip(any(TransferInitiated::class.java))
    }

    @Test
    fun sender_transferSucceeded_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getTransferSucceededCommand())

        verify(mediaTttChipControllerSender).displayChip(any(TransferSucceeded::class.java))
    }

    @Test
    fun sender_removeCommand_chipRemoved() {
        commandRegistry.onShellCommand(pw, arrayOf(REMOVE_CHIP_COMMAND_SENDER_TAG))

        verify(mediaTttChipControllerSender).removeChip()
    }

    @Test
    fun receiver_addCommand_chipAdded() {
        commandRegistry.onShellCommand(pw, arrayOf(ADD_CHIP_COMMAND_RECEIVER_TAG))

        verify(mediaTttChipControllerReceiver).displayChip(any(ChipStateReceiver::class.java))
    }

    @Test
    fun receiver_removeCommand_chipRemoved() {
        commandRegistry.onShellCommand(pw, arrayOf(REMOVE_CHIP_COMMAND_RECEIVER_TAG))

        verify(mediaTttChipControllerReceiver).removeChip()
    }

    private fun getMoveCloserToTransferCommand(): Array<String> =
        arrayOf(
            ADD_CHIP_COMMAND_SENDER_TAG,
            DEVICE_NAME,
            MOVE_CLOSER_TO_TRANSFER_COMMAND_NAME
        )

    private fun getTransferInitiatedCommand(): Array<String> =
        arrayOf(
            ADD_CHIP_COMMAND_SENDER_TAG,
            DEVICE_NAME,
            TRANSFER_INITIATED_COMMAND_NAME
        )

    private fun getTransferSucceededCommand(): Array<String> =
        arrayOf(
            ADD_CHIP_COMMAND_SENDER_TAG,
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
