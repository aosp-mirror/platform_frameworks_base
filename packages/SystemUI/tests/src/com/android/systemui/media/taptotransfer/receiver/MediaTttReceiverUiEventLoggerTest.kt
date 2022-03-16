package com.android.systemui.media.taptotransfer.receiver

import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
class MediaTttReceiverUiEventLoggerTest : SysuiTestCase() {
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var logger: MediaTttReceiverUiEventLogger

    @Before
    fun setUp() {
        uiEventLoggerFake = UiEventLoggerFake()
        logger = MediaTttReceiverUiEventLogger(uiEventLoggerFake)
    }

    @Test
    fun logReceiverStateChange_eventAssociatedWithStateIsLogged() {
        val state = ChipStateReceiver.CLOSE_TO_SENDER

        logger.logReceiverStateChange(state)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(state.uiEvent.id)
    }
}
