package com.android.systemui.media.taptotransfer.sender

import android.media.MediaRoute2Info
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.mediattt.DeviceInfo
import com.android.systemui.shared.mediattt.IDeviceSenderCallback
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

    private lateinit var service: MediaTttSenderService
    private lateinit var callback: IDeviceSenderCallback

    @Mock
    private lateinit var controller: MediaTttChipControllerSender

    private val mediaInfo = MediaRoute2Info.Builder("id", "Test Name")
        .addFeature("feature")
        .build()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        service = MediaTttSenderService(context, controller)
        callback = IDeviceSenderCallback.Stub.asInterface(service.onBind(null))
    }

    @Test
    fun closeToReceiverToStartCast_controllerTriggeredWithCorrectState() {
        val name = "Fake name"
        callback.closeToReceiverToStartCast(mediaInfo, DeviceInfo(name))

        val chipStateCaptor = argumentCaptor<MoveCloserToStartCast>()
        verify(controller).displayChip(capture(chipStateCaptor))

        val chipState = chipStateCaptor.value!!
        assertThat(chipState.getChipTextString(context)).contains(name)
    }

    @Test
    fun closeToReceiverToEndCast_controllerTriggeredWithCorrectState() {
        val name = "Fake name"
        callback.closeToReceiverToEndCast(mediaInfo, DeviceInfo(name))

        val chipStateCaptor = argumentCaptor<MoveCloserToEndCast>()
        verify(controller).displayChip(capture(chipStateCaptor))

        val chipState = chipStateCaptor.value!!
        assertThat(chipState.getChipTextString(context)).contains(name)
    }

    @Test
    fun transferToThisDeviceTriggered_controllerTriggeredWithCorrectState() {
        callback.transferToThisDeviceTriggered(mediaInfo, DeviceInfo("Fake name"))

        verify(controller).displayChip(any<TransferToThisDeviceTriggered>())
    }

    @Test
    fun transferToReceiverTriggered_controllerTriggeredWithCorrectState() {
        val name = "Fake name"
        callback.transferToReceiverTriggered(mediaInfo, DeviceInfo(name))

        val chipStateCaptor = argumentCaptor<TransferToReceiverTriggered>()
        verify(controller).displayChip(capture(chipStateCaptor))

        val chipState = chipStateCaptor.value!!
        assertThat(chipState.getChipTextString(context)).contains(name)
    }

    @Test
    fun transferFailed_controllerTriggeredWithTransferFailedState() {
        callback.transferFailed(mediaInfo, DeviceInfo("Fake name"))

        verify(controller).displayChip(any<TransferFailed>())
    }
}
