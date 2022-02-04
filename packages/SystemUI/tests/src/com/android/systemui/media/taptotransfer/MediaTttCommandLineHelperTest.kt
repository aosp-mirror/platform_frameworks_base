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

import android.app.StatusBarManager
import android.content.Context
import android.media.MediaRoute2Info
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.taptotransfer.sender.AlmostCloseToEndCast
import com.android.systemui.media.taptotransfer.sender.AlmostCloseToStartCast
import com.android.systemui.media.taptotransfer.sender.TransferFailed
import com.android.systemui.media.taptotransfer.sender.TransferToReceiverTriggered
import com.android.systemui.media.taptotransfer.sender.TransferToThisDeviceSucceeded
import com.android.systemui.media.taptotransfer.sender.TransferToThisDeviceTriggered
import com.android.systemui.media.taptotransfer.sender.TransferToReceiverSucceeded
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
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
    private lateinit var statusBarManager: StatusBarManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.addMockSystemService(Context.STATUS_BAR_SERVICE, statusBarManager)
        mediaTttCommandLineHelper =
            MediaTttCommandLineHelper(
                commandRegistry,
                context,
                FakeExecutor(FakeSystemClock()),
            )
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_senderCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the sender command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(SENDER_COMMAND) { EmptyCommand() }
    }

    @Test(expected = IllegalStateException::class)
    fun constructor_receiverCommandAlreadyRegistered() {
        // Since creating the chip controller should automatically register the receiver command, it
        // should throw when registering it again.
        commandRegistry.registerCommand(RECEIVER_COMMAND) { EmptyCommand() }
    }

    @Test
    fun sender_almostCloseToStartCast_serviceCallbackCalled() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(AlmostCloseToStartCast::class.simpleName!!)
        )

        val routeInfoCaptor = argumentCaptor<MediaRoute2Info>()
        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST),
            capture(routeInfoCaptor),
            nullable(),
            nullable())
        assertThat(routeInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_almostCloseToEndCast_serviceCallbackCalled() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(AlmostCloseToEndCast::class.simpleName!!)
        )

        val routeInfoCaptor = argumentCaptor<MediaRoute2Info>()
        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST),
            capture(routeInfoCaptor),
            nullable(),
            nullable())
        assertThat(routeInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_transferToReceiverTriggered_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(TransferToReceiverTriggered::class.simpleName!!)
        )

        val routeInfoCaptor = argumentCaptor<MediaRoute2Info>()
        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED),
            capture(routeInfoCaptor),
            nullable(),
            nullable())
        assertThat(routeInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_transferToThisDeviceTriggered_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(TransferToThisDeviceTriggered::class.simpleName!!)
        )

        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED),
            any(),
            nullable(),
            nullable())
    }

    @Test
    fun sender_transferToReceiverSucceeded_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(TransferToReceiverSucceeded::class.simpleName!!)
        )

        val routeInfoCaptor = argumentCaptor<MediaRoute2Info>()
        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED),
            capture(routeInfoCaptor),
            nullable(),
            nullable())
        assertThat(routeInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_transferToThisDeviceSucceeded_chipDisplayWithCorrectState() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(TransferToThisDeviceSucceeded::class.simpleName!!)
        )

        val routeInfoCaptor = argumentCaptor<MediaRoute2Info>()
        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED),
            capture(routeInfoCaptor),
            nullable(),
            nullable())
        assertThat(routeInfoCaptor.value!!.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun sender_transferFailed_serviceCallbackCalled() {
        commandRegistry.onShellCommand(pw, getSenderCommand(TransferFailed::class.simpleName!!))

        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED),
            any(),
            nullable(),
            nullable())
    }

    @Test
    fun sender_farFromReceiver_serviceCallbackCalled() {
        commandRegistry.onShellCommand(pw, getSenderCommand(FAR_FROM_RECEIVER_STATE))

        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER),
            any(),
            nullable(),
            nullable())
    }

    @Test
    fun receiver_closeToSender_serviceCallbackCalled() {
        commandRegistry.onShellCommand(pw, getReceiverCommand(CLOSE_TO_SENDER_STATE))

        verify(statusBarManager).updateMediaTapToTransferReceiverDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER),
            any()
        )
    }

    @Test
    fun receiver_farFromSender_serviceCallbackCalled() {
        commandRegistry.onShellCommand(pw, getReceiverCommand(FAR_FROM_SENDER_STATE))

        verify(statusBarManager).updateMediaTapToTransferReceiverDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER),
            any()
        )
    }

    private fun getSenderCommand(displayState: String): Array<String> =
        arrayOf(SENDER_COMMAND, DEVICE_NAME, displayState)

    private fun getReceiverCommand(displayState: String): Array<String> =
        arrayOf(RECEIVER_COMMAND, displayState)

    class EmptyCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
        }

        override fun help(pw: PrintWriter) {
        }
    }
}

private const val DEVICE_NAME = "My Tablet"
