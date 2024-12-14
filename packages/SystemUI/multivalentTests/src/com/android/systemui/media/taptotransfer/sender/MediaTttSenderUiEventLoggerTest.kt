package com.android.systemui.media.taptotransfer.sender

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaTttSenderUiEventLoggerTest : SysuiTestCase() {
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var logger: MediaTttSenderUiEventLogger

    @Before
    fun setUp () {
        uiEventLoggerFake = UiEventLoggerFake()
        logger = MediaTttSenderUiEventLogger(uiEventLoggerFake)
    }

    @Test
    fun logSenderStateChange_eventAssociatedWithStateIsLogged() {
        val state = ChipStateSender.ALMOST_CLOSE_TO_END_CAST
        logger.logSenderStateChange(state, instanceId)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(state.uiEvent.id)
        assertThat(uiEventLoggerFake.get(0).instanceId).isEqualTo(instanceId)
    }

    @Test
    fun logUndoClicked_undoEventLogged() {
        val undoEvent = MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_THIS_DEVICE_CLICKED

        logger.logUndoClicked(undoEvent, instanceId)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(undoEvent.id)
        assertThat(uiEventLoggerFake.get(0).instanceId).isEqualTo(instanceId)
    }

    @Test
    fun logUndoClicked_notUndoEvent_eventNotLogged() {
        val state = MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_FAILED

        logger.logUndoClicked(state, instanceId)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(0)
    }
}

private val instanceId = InstanceId.fakeInstanceId(0)
