package com.android.systemui.media.taptotransfer.receiver

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
        val instanceId = InstanceId.fakeInstanceId(0)

        logger.logReceiverStateChange(state, instanceId)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(state.uiEvent.id)
        assertThat(uiEventLoggerFake.logs[0].instanceId).isEqualTo(instanceId)
    }
}
