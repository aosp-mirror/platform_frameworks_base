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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.taptotransfer.receiver.ChipStateReceiver
import com.android.systemui.media.taptotransfer.sender.ChipStateSender
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
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executor

@SmallTest
@RunWith(AndroidJUnit4::class)
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
        mediaTttCommandLineHelper.start()
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
            pw, getSenderCommand(ChipStateSender.ALMOST_CLOSE_TO_START_CAST.name)
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
            pw, getSenderCommand(ChipStateSender.ALMOST_CLOSE_TO_END_CAST.name)
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
            pw, getSenderCommand(ChipStateSender.TRANSFER_TO_RECEIVER_TRIGGERED.name)
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
            pw, getSenderCommand(ChipStateSender.TRANSFER_TO_THIS_DEVICE_TRIGGERED.name)
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
            pw, getSenderCommand(ChipStateSender.TRANSFER_TO_RECEIVER_SUCCEEDED.name)
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
            pw, getSenderCommand(ChipStateSender.TRANSFER_TO_THIS_DEVICE_SUCCEEDED.name)
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
    fun sender_transferToReceiverFailed_serviceCallbackCalled() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(ChipStateSender.TRANSFER_TO_RECEIVER_FAILED.name)
        )

        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED),
            any(),
            nullable(),
            nullable())
    }

    @Test
    fun sender_transferToThisDeviceFailed_serviceCallbackCalled() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(ChipStateSender.TRANSFER_TO_THIS_DEVICE_FAILED.name)
        )

        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED),
            any(),
            nullable(),
            nullable())
    }

    @Test
    fun sender_farFromReceiver_serviceCallbackCalled() {
        commandRegistry.onShellCommand(
            pw, getSenderCommand(ChipStateSender.FAR_FROM_RECEIVER.name)
        )

        verify(statusBarManager).updateMediaTapToTransferSenderDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER),
            any(),
            nullable(),
            nullable())
    }

    @Test
    fun receiver_closeToSender_serviceCallbackCalled() {
        commandRegistry.onShellCommand(
            pw, getReceiverCommand(ChipStateReceiver.CLOSE_TO_SENDER.name)
        )

        verify(statusBarManager).updateMediaTapToTransferReceiverDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER),
            any(),
            nullable(),
            nullable()
        )
    }

    @Test
    fun receiver_farFromSender_serviceCallbackCalled() {
        commandRegistry.onShellCommand(
            pw, getReceiverCommand(ChipStateReceiver.FAR_FROM_SENDER.name)
        )

        verify(statusBarManager).updateMediaTapToTransferReceiverDisplay(
            eq(StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER),
            any(),
            nullable(),
            nullable()
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
