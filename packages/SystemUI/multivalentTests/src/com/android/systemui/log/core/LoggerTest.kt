package com.android.systemui.log.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogMessageImpl
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.Mockito.isNull
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class LoggerTest : SysuiTestCase() {
    @Mock private lateinit var buffer: MessageBuffer
    private lateinit var message: LogMessage

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(buffer.obtain(any(), any(), any(), isNull())).thenAnswer {
            message = LogMessageImpl.Factory.create()
            return@thenAnswer message
        }
    }

    @Test
    fun log_shouldCommitLogMessage() {
        val logger = Logger(buffer, "LoggerTest")
        logger.log(LogLevel.DEBUG, { "count=$int1" }) {
            int1 = 1
            str1 = "test"
            bool1 = true
        }

        assertThat(message.int1).isEqualTo(1)
        assertThat(message.str1).isEqualTo("test")
        assertThat(message.bool1).isEqualTo(true)
    }

    @Test
    fun log_shouldUseCorrectLoggerTag() {
        val logger = Logger(buffer, "LoggerTest")
        logger.log(LogLevel.DEBUG, { "count=$int1" }) { int1 = 1 }
        verify(buffer).obtain(eq("LoggerTest"), any(), any(), nullable())
    }

    @Test
    fun v_withMessageInitializer_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.v({ "count=$int1" }) { int1 = 1 }
        verify(buffer).obtain(anyString(), eq(LogLevel.VERBOSE), any(), nullable())
    }

    @Test
    fun v_withCompileTimeMessage_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.v("Message")
        verify(buffer).obtain(anyString(), eq(LogLevel.VERBOSE), any(), nullable())
    }

    @Test
    fun d_withMessageInitializer_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.d({ "count=$int1" }) { int1 = 1 }
        verify(buffer).obtain(anyString(), eq(LogLevel.DEBUG), any(), nullable())
    }

    @Test
    fun d_withCompileTimeMessage_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.d("Message")
        verify(buffer).obtain(anyString(), eq(LogLevel.DEBUG), any(), nullable())
    }

    @Test
    fun i_withMessageInitializer_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.i({ "count=$int1" }) { int1 = 1 }
        verify(buffer).obtain(anyString(), eq(LogLevel.INFO), any(), nullable())
    }

    @Test
    fun i_withCompileTimeMessage_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.i("Message")
        verify(buffer).obtain(anyString(), eq(LogLevel.INFO), any(), nullable())
    }

    @Test
    fun w_withMessageInitializer_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.w({ "count=$int1" }) { int1 = 1 }
        verify(buffer).obtain(anyString(), eq(LogLevel.WARNING), any(), nullable())
    }

    @Test
    fun w_withCompileTimeMessage_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.w("Message")
        verify(buffer).obtain(anyString(), eq(LogLevel.WARNING), any(), nullable())
    }

    @Test
    fun e_withMessageInitializer_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.e({ "count=$int1" }) { int1 = 1 }
        verify(buffer).obtain(anyString(), eq(LogLevel.ERROR), any(), nullable())
    }

    @Test
    fun e_withCompileTimeMessage_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.e("Message")
        verify(buffer).obtain(anyString(), eq(LogLevel.ERROR), any(), nullable())
    }

    @Test
    fun wtf_withMessageInitializer_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.wtf({ "count=$int1" }) { int1 = 1 }
        verify(buffer).obtain(anyString(), eq(LogLevel.WTF), any(), nullable())
    }

    @Test
    fun wtf_withCompileTimeMessage_shouldLogAtCorrectLevel() {
        val logger = Logger(buffer, "LoggerTest")
        logger.wtf("Message")
        verify(buffer).obtain(anyString(), eq(LogLevel.WTF), any(), nullable())
    }
}
