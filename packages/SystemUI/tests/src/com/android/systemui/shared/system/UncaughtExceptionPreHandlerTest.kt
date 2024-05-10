package com.android.systemui.shared.system

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.lang.Thread.UncaughtExceptionHandler
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.only
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
class UncaughtExceptionPreHandlerTest : SysuiTestCase() {
    private lateinit var preHandlerManager: UncaughtExceptionPreHandlerManager

    @Mock private lateinit var mockHandler: UncaughtExceptionHandler

    @Mock private lateinit var mockHandler2: UncaughtExceptionHandler

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        Thread.setUncaughtExceptionPreHandler(null)
        preHandlerManager = UncaughtExceptionPreHandlerManager()
    }

    @Test
    fun registerHandler_registersOnceOnly() {
        preHandlerManager.registerHandler(mockHandler)
        preHandlerManager.registerHandler(mockHandler)
        preHandlerManager.handleUncaughtException(Thread.currentThread(), Exception())
        verify(mockHandler, only()).uncaughtException(any(), any())
    }

    @Test
    fun registerHandler_setsUncaughtExceptionPreHandler() {
        Thread.setUncaughtExceptionPreHandler(null)
        preHandlerManager.registerHandler(mockHandler)
        assertThat(Thread.getUncaughtExceptionPreHandler()).isNotNull()
    }

    @Test
    fun registerHandler_preservesOriginalHandler() {
        Thread.setUncaughtExceptionPreHandler(mockHandler)
        preHandlerManager.registerHandler(mockHandler2)
        preHandlerManager.handleUncaughtException(Thread.currentThread(), Exception())
        verify(mockHandler, only()).uncaughtException(any(), any())
    }

    @Test
    @Ignore
    fun registerHandler_toleratesHandlersThatThrow() {
        `when`(mockHandler2.uncaughtException(any(), any())).thenThrow(RuntimeException())
        preHandlerManager.registerHandler(mockHandler2)
        preHandlerManager.registerHandler(mockHandler)
        preHandlerManager.handleUncaughtException(Thread.currentThread(), Exception())
        verify(mockHandler2, only()).uncaughtException(any(), any())
        verify(mockHandler, only()).uncaughtException(any(), any())
    }

    @Test
    fun registerHandler_doesNotSetUpTwice() {
        UncaughtExceptionPreHandlerManager().registerHandler(mockHandler2)
        assertThrows(IllegalStateException::class.java) {
            preHandlerManager.registerHandler(mockHandler)
        }
    }
}
