package com.android.systemui.media.taptotransfer.sender

import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
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
        logger.logSenderStateChange(state)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(state.uiEvent.id)
    }

    @Test
    fun logUndoClicked_undoEventLogged() {
        val undoEvent = MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_THIS_DEVICE_CLICKED

        logger.logUndoClicked(undoEvent)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(undoEvent.id)
    }

    @Test
    fun logUndoClicked_notUndoEvent_eventNotLogged() {
        logger.logUndoClicked(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_FAILED)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(0)
    }
}
