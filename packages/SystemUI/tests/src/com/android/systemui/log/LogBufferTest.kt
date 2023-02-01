package com.android.systemui.log

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner

@SmallTest
@RunWith(MockitoJUnitRunner::class)
class LogBufferTest : SysuiTestCase() {
    private lateinit var buffer: LogBuffer

    private lateinit var outputWriter: StringWriter

    @Mock
    private lateinit var logcatEchoTracker: LogcatEchoTracker

    @Before
    fun setup() {
        outputWriter = StringWriter()
        buffer = createBuffer()
    }

    private fun createBuffer(): LogBuffer {
        return LogBuffer("TestBuffer", 1, logcatEchoTracker, false)
    }

    @Test
    fun log_shouldSaveLogToBuffer() {
        buffer.log("Test", LogLevel.INFO, "Some test message")

        val dumpedString = dumpBuffer()

        assertThat(dumpedString).contains("Some test message")
    }

    @Test
    fun log_shouldRotateIfLogBufferIsFull() {
        buffer.log("Test", LogLevel.INFO, "This should be rotated")
        buffer.log("Test", LogLevel.INFO, "New test message")

        val dumpedString = dumpBuffer()

        assertThat(dumpedString).contains("New test message")
    }

    @Test
    fun dump_writesExceptionAndStacktrace() {
        buffer = createBuffer()
        val exception = createTestException("Exception message", "TestClass")
        buffer.log("Tag", LogLevel.ERROR, { str1 = "Extra message" }, { str1!! }, exception)

        val dumpedString = dumpBuffer()

        assertThat(dumpedString).contains("Extra message")
        assertThat(dumpedString).contains("java.lang.RuntimeException: Exception message")
        assertThat(dumpedString).contains("at TestClass.TestMethod(TestClass.java:1)")
        assertThat(dumpedString).contains("at TestClass.TestMethod(TestClass.java:2)")
    }

    @Test
    fun dump_writesCauseAndStacktrace() {
        buffer = createBuffer()
        val exception = createTestException("Exception message",
                "TestClass",
                cause = createTestException("The real cause!", "TestClass"))
        buffer.log("Tag", LogLevel.ERROR, { str1 = "Extra message" }, { str1!! }, exception)

        val dumpedString = dumpBuffer()

        assertThat(dumpedString)
                .contains("Caused by: java.lang.RuntimeException: The real cause!")
        assertThat(dumpedString).contains("at TestClass.TestMethod(TestClass.java:1)")
        assertThat(dumpedString).contains("at TestClass.TestMethod(TestClass.java:2)")
    }

    @Test
    fun dump_writesSuppressedExceptionAndStacktrace() {
        buffer = createBuffer()
        val exception = RuntimeException("Root exception message")
        exception.addSuppressed(
                createTestException(
                        "First suppressed exception",
                        "FirstClass",
                        createTestException("Cause of suppressed exp", "ThirdClass")
                ))
        exception.addSuppressed(
                createTestException("Second suppressed exception", "SecondClass"))
        buffer.log("Tag", LogLevel.ERROR, { str1 = "Extra message" }, { str1!! }, exception)

        val dumpedStr = dumpBuffer()

        // first suppressed exception
        assertThat(dumpedStr)
                .contains("Suppressed: " +
                        "java.lang.RuntimeException: First suppressed exception")
        assertThat(dumpedStr).contains("at FirstClass.TestMethod(FirstClass.java:1)")
        assertThat(dumpedStr).contains("at FirstClass.TestMethod(FirstClass.java:2)")

        assertThat(dumpedStr)
                .contains("Caused by: java.lang.RuntimeException: Cause of suppressed exp")
        assertThat(dumpedStr).contains("at ThirdClass.TestMethod(ThirdClass.java:1)")
        assertThat(dumpedStr).contains("at ThirdClass.TestMethod(ThirdClass.java:2)")

        // second suppressed exception
        assertThat(dumpedStr)
                .contains("Suppressed: " +
                        "java.lang.RuntimeException: Second suppressed exception")
        assertThat(dumpedStr).contains("at SecondClass.TestMethod(SecondClass.java:1)")
        assertThat(dumpedStr).contains("at SecondClass.TestMethod(SecondClass.java:2)")
    }

    private fun createTestException(
            message: String,
            errorClass: String,
            cause: Throwable? = null,
    ): Exception {
        val exception = RuntimeException(message, cause)
        exception.stackTrace = (1..5).map { lineNumber ->
            StackTraceElement(errorClass,
                    "TestMethod",
                    "$errorClass.java",
                    lineNumber)
        }.toTypedArray()
        return exception
    }

    private fun dumpBuffer(): String {
        buffer.dump(PrintWriter(outputWriter), tailLength = 100)
        return outputWriter.toString()
    }
}
