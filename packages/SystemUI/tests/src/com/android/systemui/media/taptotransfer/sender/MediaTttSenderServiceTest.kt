package com.android.systemui.media.taptotransfer.sender

import android.media.MediaRoute2Info
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.mediattt.DeviceInfo
import com.android.systemui.shared.mediattt.IDeviceSenderService
import com.android.systemui.shared.mediattt.IUndoTransferCallback
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class MediaTttSenderServiceTest : SysuiTestCase() {

    private lateinit var service: IDeviceSenderService

    @Mock
    private lateinit var controller: MediaTttChipControllerSender

    private val mediaInfo = MediaRoute2Info.Builder("id", "Test Name")
        .addFeature("feature")
        .build()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val mediaTttSenderService = MediaTttSenderService(context, controller)
        service = IDeviceSenderService.Stub.asInterface(mediaTttSenderService.onBind(null))
    }

    @Test
    fun closeToReceiverToStartCast_controllerTriggeredWithCorrectState() {
        val name = "Fake name"
        service.closeToReceiverToStartCast(mediaInfo, DeviceInfo(name))

        val chipStateCaptor = argumentCaptor<MoveCloserToStartCast>()
        verify(controller).displayChip(capture(chipStateCaptor))

        val chipState = chipStateCaptor.value!!
        assertThat(chipState.getChipTextString(context)).contains(name)
    }

    @Test
    fun closeToReceiverToEndCast_controllerTriggeredWithCorrectState() {
        val name = "Fake name"
        service.closeToReceiverToEndCast(mediaInfo, DeviceInfo(name))

        val chipStateCaptor = argumentCaptor<MoveCloserToEndCast>()
        verify(controller).displayChip(capture(chipStateCaptor))

        val chipState = chipStateCaptor.value!!
        assertThat(chipState.getChipTextString(context)).contains(name)
    }

    @Test
    fun transferToThisDeviceTriggered_controllerTriggeredWithCorrectState() {
        service.transferToThisDeviceTriggered(mediaInfo, DeviceInfo("Fake name"))

        verify(controller).displayChip(any<TransferToThisDeviceTriggered>())
    }

    @Test
    fun transferToReceiverTriggered_controllerTriggeredWithCorrectState() {
        val name = "Fake name"
        service.transferToReceiverTriggered(mediaInfo, DeviceInfo(name))

        val chipStateCaptor = argumentCaptor<TransferToReceiverTriggered>()
        verify(controller).displayChip(capture(chipStateCaptor))

        val chipState = chipStateCaptor.value!!
        assertThat(chipState.getChipTextString(context)).contains(name)
    }

    @Test
    fun transferToReceiverSucceeded_controllerTriggeredWithCorrectState() {
        val name = "Fake name"
        val undoCallback = object : IUndoTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        service.transferToReceiverSucceeded(mediaInfo, DeviceInfo(name), undoCallback)

        val chipStateCaptor = argumentCaptor<TransferToReceiverSucceeded>()
        verify(controller).displayChip(capture(chipStateCaptor))

        val chipState = chipStateCaptor.value!!
        assertThat(chipState.getChipTextString(context)).contains(name)
        assertThat(chipState.undoCallback).isEqualTo(undoCallback)
    }

    @Test
    fun transferToThisDeviceSucceeded_controllerTriggeredWithCorrectState() {
        val undoCallback = object : IUndoTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        service.transferToThisDeviceSucceeded(mediaInfo, DeviceInfo("name"), undoCallback)

        val chipStateCaptor = argumentCaptor<TransferToThisDeviceSucceeded>()
        verify(controller).displayChip(capture(chipStateCaptor))

        val chipState = chipStateCaptor.value!!
        assertThat(chipState.undoCallback).isEqualTo(undoCallback)
    }

    @Test
    fun transferFailed_controllerTriggeredWithTransferFailedState() {
        service.transferFailed(mediaInfo, DeviceInfo("Fake name"))

        verify(controller).displayChip(any<TransferFailed>())
    }

    @Test
    fun noLongerCloseToReceiver_controllerRemoveChipTriggered() {
        service.noLongerCloseToReceiver(mediaInfo, DeviceInfo("Fake name"))

        verify(controller).removeChip()
    }
}
