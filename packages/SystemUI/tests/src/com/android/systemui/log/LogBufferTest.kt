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
        buffer = createBuffer(UNBOUNDED_STACK_TRACE, NESTED_TRACE_DEPTH)
    }

    private fun createBuffer(rootTraceDepth: Int, nestedTraceDepth: Int): LogBuffer {
        return LogBuffer("TestBuffer",
                1,
                logcatEchoTracker,
                false,
                rootStackTraceDepth = rootTraceDepth,
                nestedStackTraceDepth = nestedTraceDepth)
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
    fun dump_writesExceptionAndStacktraceLimitedToGivenDepth() {
        buffer = createBuffer(rootTraceDepth = 2, nestedTraceDepth = -1)
        // stack trace depth of 5
        val exception = createTestException("Exception message", "TestClass", 5)
        buffer.log("Tag", LogLevel.ERROR, { str1 = "Extra message" }, { str1!! }, exception)

        val dumpedString = dumpBuffer()

        // logs are limited to depth 2
        assertThat(dumpedString).contains("E Tag: Extra message")
        assertThat(dumpedString).contains("E Tag: java.lang.RuntimeException: Exception message")
        assertThat(dumpedString).contains("E Tag: \tat TestClass.TestMethod(TestClass.java:1)")
        assertThat(dumpedString).contains("E Tag: \tat TestClass.TestMethod(TestClass.java:2)")
        assertThat(dumpedString)
                .doesNotContain("E Tag: \tat TestClass.TestMethod(TestClass.java:3)")
    }

    @Test
    fun dump_writesCauseAndStacktraceLimitedToGivenDepth() {
        buffer = createBuffer(rootTraceDepth = 0, nestedTraceDepth = 2)
        val exception = createTestException("Exception message",
                "TestClass",
                1,
                cause = createTestException("The real cause!", "TestClass", 5))
        buffer.log("Tag", LogLevel.ERROR, { str1 = "Extra message" }, { str1!! }, exception)

        val dumpedString = dumpBuffer()

        // logs are limited to depth 2
        assertThat(dumpedString)
                .contains("E Tag: Caused by: java.lang.RuntimeException: The real cause!")
        assertThat(dumpedString).contains("E Tag: \tat TestClass.TestMethod(TestClass.java:1)")
        assertThat(dumpedString).contains("E Tag: \tat TestClass.TestMethod(TestClass.java:2)")
        assertThat(dumpedString)
                .doesNotContain("E Tag: \tat TestClass.TestMethod(TestClass.java:3)")
    }

    @Test
    fun dump_writesSuppressedExceptionAndStacktraceLimitedToGivenDepth() {
        buffer = createBuffer(rootTraceDepth = 0, nestedTraceDepth = 2)
        val exception = RuntimeException("Root exception message")
        exception.addSuppressed(
                createTestException(
                        "First suppressed exception",
                        "FirstClass",
                        5,
                        createTestException("Cause of suppressed exp", "ThirdClass", 5)
                ))
        exception.addSuppressed(
                createTestException("Second suppressed exception", "SecondClass", 5))
        buffer.log("Tag", LogLevel.ERROR, { str1 = "Extra message" }, { str1!! }, exception)

        val dumpedStr = dumpBuffer()

        // logs are limited to depth 2
        // first suppressed exception
        assertThat(dumpedStr)
                .contains("E Tag: Suppressed: " +
                        "java.lang.RuntimeException: First suppressed exception")
        assertThat(dumpedStr).contains("E Tag: \tat FirstClass.TestMethod(FirstClass.java:1)")
        assertThat(dumpedStr).contains("E Tag: \tat FirstClass.TestMethod(FirstClass.java:2)")
        assertThat(dumpedStr)
                .doesNotContain("E Tag: \tat FirstClass.TestMethod(FirstClass.java:3)")

        assertThat(dumpedStr)
                .contains("E Tag: Caused by: java.lang.RuntimeException: Cause of suppressed exp")
        assertThat(dumpedStr).contains("E Tag: \tat ThirdClass.TestMethod(ThirdClass.java:1)")
        assertThat(dumpedStr).contains("E Tag: \tat ThirdClass.TestMethod(ThirdClass.java:2)")
        assertThat(dumpedStr)
                .doesNotContain("E Tag: \tat ThirdClass.TestMethod(ThirdClass.java:3)")

        // second suppressed exception
        assertThat(dumpedStr)
                .contains("E Tag: Suppressed: " +
                        "java.lang.RuntimeException: Second suppressed exception")
        assertThat(dumpedStr).contains("E Tag: \tat SecondClass.TestMethod(SecondClass.java:1)")
        assertThat(dumpedStr).contains("E Tag: \tat SecondClass.TestMethod(SecondClass.java:2)")
        assertThat(dumpedStr)
                .doesNotContain("E Tag: \tat SecondClass.TestMethod(SecondClass.java:3)")
    }

    private fun createTestException(
        message: String,
        errorClass: String,
        stackTraceLength: Int,
        cause: Throwable? = null
    ): Exception {
        val exception = RuntimeException(message, cause)
        exception.stackTrace = createStackTraceElements(errorClass, stackTraceLength)
        return exception
    }

    private fun dumpBuffer(): String {
        buffer.dump(PrintWriter(outputWriter), tailLength = 100)
        return outputWriter.toString()
    }

    private fun createStackTraceElements(
        errorClass: String,
        stackTraceLength: Int
    ): Array<StackTraceElement> {
        return (1..stackTraceLength).map { lineNumber ->
            StackTraceElement(errorClass,
                    "TestMethod",
                    "$errorClass.java",
                    lineNumber)
        }.toTypedArray()
    }
}
