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

import android.content.ComponentName
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.taptotransfer.receiver.ChipStateReceiver
import com.android.systemui.media.taptotransfer.receiver.MediaTttChipControllerReceiver
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executor

@SmallTest
@Ignore("b/216286227")
class MediaTttCommandLineHelperTest : SysuiTestCase() {

    private val inlineExecutor = Executor { command -> command.run() }
    private val commandRegistry = CommandRegistry(context, inlineExecutor)
    private val pw = PrintWriter(StringWriter())

    private lateinit var mediaTttCommandLineHelper: MediaTttCommandLineHelper

    @Mock
    private lateinit var mediaTttChipControllerReceiver: MediaTttChipControllerReceiver

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mediaTttCommandLineHelper =
            MediaTttCommandLineHelper(
                commandRegistry,
                context,
                mediaTttChipControllerReceiver,
            )
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_senderCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the sender command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(SENDER_COMMAND) { EmptyCommand() }
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

    /* TODO(b/216318437): Revive these tests using the new SystemApis.
    @Test
    fun sender_moveCloserToStartCast_serviceCallbackCalled() {
        commandRegistry.onShellCommand(pw, getMoveCloserToStartCastCommand())

        assertThat(context.isBound(mediaSenderServiceComponentName)).isTrue()

        val deviceInfoCaptor = argumentCaptor<DeviceInfo>()
        verify(mediaSenderService).closeToReceiverToStartCast(any(), capture(deviceInfoCaptor))
        assertThat(deviceInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_moveCloserToEndCast_serviceCallbackCalled() {
        commandRegistry.onShellCommand(pw, getMoveCloserToEndCastCommand())

        assertThat(context.isBound(mediaSenderServiceComponentName)).isTrue()

        val deviceInfoCaptor = argumentCaptor<DeviceInfo>()
        verify(mediaSenderService).closeToReceiverToEndCast(any(), capture(deviceInfoCaptor))
        assertThat(deviceInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_transferToReceiverTriggered_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getTransferToReceiverTriggeredCommand())

        assertThat(context.isBound(mediaSenderServiceComponentName)).isTrue()

        val deviceInfoCaptor = argumentCaptor<DeviceInfo>()
        verify(mediaSenderService).transferToReceiverTriggered(any(), capture(deviceInfoCaptor))
        assertThat(deviceInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_transferToThisDeviceTriggered_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getTransferToThisDeviceTriggeredCommand())

        assertThat(context.isBound(mediaSenderServiceComponentName)).isTrue()
        verify(mediaSenderService).transferToThisDeviceTriggered(any(), any())
    }

    @Test
    fun sender_transferToReceiverSucceeded_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getTransferToReceiverSucceededCommand())

        assertThat(context.isBound(mediaSenderServiceComponentName)).isTrue()

        val deviceInfoCaptor = argumentCaptor<DeviceInfo>()
        verify(mediaSenderService)
            .transferToReceiverSucceeded(any(), capture(deviceInfoCaptor), any())
        assertThat(deviceInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_transferToThisDeviceSucceeded_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(pw, getTransferToThisDeviceSucceededCommand())

        assertThat(context.isBound(mediaSenderServiceComponentName)).isTrue()

        val deviceInfoCaptor = argumentCaptor<DeviceInfo>()
        verify(mediaSenderService)
            .transferToThisDeviceSucceeded(any(), capture(deviceInfoCaptor), any())
        assertThat(deviceInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_transferFailed_serviceCallbackCalled() {
        commandRegistry.onShellCommand(pw, getTransferFailedCommand())

        assertThat(context.isBound(mediaSenderServiceComponentName)).isTrue()
        verify(mediaSenderService).transferFailed(any(), any())
    }

    @Test
    fun sender_noLongerCloseToReceiver_serviceCallbackCalledAndServiceUnbound() {
        commandRegistry.onShellCommand(pw, getNoLongerCloseToReceiverCommand())

        // Once we're no longer close to the receiver, we should unbind the service.
        assertThat(context.isBound(mediaSenderServiceComponentName)).isFalse()
        verify(mediaSenderService).noLongerCloseToReceiver(any(), any())
    }

     */

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

    private fun getMoveCloserToStartCastCommand(): Array<String> =
        arrayOf(
            SENDER_COMMAND,
            DEVICE_NAME,
            MOVE_CLOSER_TO_START_CAST_COMMAND_NAME
        )

    private fun getMoveCloserToEndCastCommand(): Array<String> =
        arrayOf(
            SENDER_COMMAND,
            DEVICE_NAME,
            MOVE_CLOSER_TO_END_CAST_COMMAND_NAME
        )

    private fun getTransferToReceiverTriggeredCommand(): Array<String> =
        arrayOf(
            SENDER_COMMAND,
            DEVICE_NAME,
            TRANSFER_TO_RECEIVER_TRIGGERED_COMMAND_NAME
        )

    private fun getTransferToThisDeviceTriggeredCommand(): Array<String> =
        arrayOf(
            SENDER_COMMAND,
            DEVICE_NAME,
            TRANSFER_TO_THIS_DEVICE_TRIGGERED_COMMAND_NAME
        )

    private fun getTransferToReceiverSucceededCommand(): Array<String> =
        arrayOf(
            SENDER_COMMAND,
            DEVICE_NAME,
            TRANSFER_TO_RECEIVER_SUCCEEDED_COMMAND_NAME
        )

    private fun getTransferToThisDeviceSucceededCommand(): Array<String> =
        arrayOf(
            SENDER_COMMAND,
            DEVICE_NAME,
            TRANSFER_TO_THIS_DEVICE_SUCCEEDED_COMMAND_NAME
        )

    private fun getTransferFailedCommand(): Array<String> =
        arrayOf(
            SENDER_COMMAND,
            DEVICE_NAME,
            TRANSFER_FAILED_COMMAND_NAME
        )

    private fun getNoLongerCloseToReceiverCommand(): Array<String> =
        arrayOf(
            SENDER_COMMAND,
            DEVICE_NAME,
            NO_LONGER_CLOSE_TO_RECEIVER_COMMAND_NAME
        )

    class EmptyCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
        }

        override fun help(pw: PrintWriter) {
        }
    }
}

private const val DEVICE_NAME = "My Tablet"
