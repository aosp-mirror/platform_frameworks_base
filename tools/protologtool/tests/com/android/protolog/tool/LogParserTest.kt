/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.protolog.tool

import com.android.json.stream.JsonReader
import com.android.internal.protolog.ProtoLogMessage
import com.android.internal.protolog.ProtoLogFileProto
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogParserTest {
    private val configParser: ViewerConfigParser = mock(ViewerConfigParser::class.java)
    private val parser = LogParser(configParser)
    private var config: MutableMap<Long, ViewerConfigParser.ConfigEntry> = mutableMapOf()
    private var outStream: OutputStream = ByteArrayOutputStream()
    private var printStream: PrintStream = PrintStream(outStream)
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Before
    fun init() {
        Mockito.`when`(configParser.parseConfig(any(JsonReader::class.java))).thenReturn(config)
    }

    private fun <T> any(type: Class<T>): T = Mockito.any<T>(type)

    private fun getConfigDummyStream(): InputStream {
        return "".byteInputStream()
    }

    private fun buildProtoInput(logBuilder: ProtoLogFileProto.Builder): InputStream {
        logBuilder.setVersion(Constants.VERSION)
        logBuilder.magicNumber =
                ProtoLogFileProto.MagicNumber.MAGIC_NUMBER_H.number.toLong() shl 32 or
                        ProtoLogFileProto.MagicNumber.MAGIC_NUMBER_L.number.toLong()
        return logBuilder.build().toByteArray().inputStream()
    }

    private fun testDate(timeMS: Long): String {
        return dateFormat.format(Date(timeMS))
    }

    @Test
    fun parse() {
        config[70933285] = ViewerConfigParser.ConfigEntry("Test completed successfully: %b",
                "ERROR", "WindowManager")

        val logBuilder = ProtoLogFileProto.newBuilder()
        val logMessageBuilder = ProtoLogMessage.newBuilder()
        logMessageBuilder
                .setMessageHash(70933285)
                .setElapsedRealtimeNanos(0)
                .addBooleanParams(true)
        logBuilder.addLog(logMessageBuilder.build())

        parser.parse(buildProtoInput(logBuilder), getConfigDummyStream(), printStream)

        assertEquals("${testDate(0)} ERROR WindowManager: Test completed successfully: true\n",
                outStream.toString())
    }

    @Test
    fun parse_formatting() {
        config[123] = ViewerConfigParser.ConfigEntry("Test completed successfully: %b %d %%" +
                " %x %s %f", "ERROR", "WindowManager")

        val logBuilder = ProtoLogFileProto.newBuilder()
        val logMessageBuilder = ProtoLogMessage.newBuilder()
        logMessageBuilder
                .setMessageHash(123)
                .setElapsedRealtimeNanos(0)
                .addBooleanParams(true)
                .addAllSint64Params(listOf(1000, 20000))
                .addDoubleParams(1000.1)
                .addStrParams("test")
        logBuilder.addLog(logMessageBuilder.build())

        parser.parse(buildProtoInput(logBuilder), getConfigDummyStream(), printStream)

        assertEquals("${testDate(0)} ERROR WindowManager: Test completed successfully: " +
                "true 1000 % 4e20 test 1000.100000\n",
                outStream.toString())
    }

    @Test
    fun parse_invalidParamsTooMany() {
        config[123] = ViewerConfigParser.ConfigEntry("Test completed successfully: %b %d %%",
                "ERROR", "WindowManager")

        val logBuilder = ProtoLogFileProto.newBuilder()
        val logMessageBuilder = ProtoLogMessage.newBuilder()
        logMessageBuilder
                .setMessageHash(123)
                .setElapsedRealtimeNanos(0)
                .addBooleanParams(true)
                .addAllSint64Params(listOf(1000, 20000, 300000))
                .addAllDoubleParams(listOf(0.1, 0.00001, 1000.1))
                .addStrParams("test")
        logBuilder.addLog(logMessageBuilder.build())

        parser.parse(buildProtoInput(logBuilder), getConfigDummyStream(), printStream)

        assertEquals("${testDate(0)} INVALID: 123 - [test] [1000, 20000, 300000] " +
                "[0.1, 1.0E-5, 1000.1] [true]\n", outStream.toString())
    }

    @Test
    fun parse_invalidParamsNotEnough() {
        config[123] = ViewerConfigParser.ConfigEntry("Test completed successfully: %b %d %%" +
                " %x %s %f", "ERROR", "WindowManager")

        val logBuilder = ProtoLogFileProto.newBuilder()
        val logMessageBuilder = ProtoLogMessage.newBuilder()
        logMessageBuilder
                .setMessageHash(123)
                .setElapsedRealtimeNanos(0)
                .addBooleanParams(true)
                .addStrParams("test")
        logBuilder.addLog(logMessageBuilder.build())

        parser.parse(buildProtoInput(logBuilder), getConfigDummyStream(), printStream)

        assertEquals("${testDate(0)} INVALID: 123 - [test] [] [] [true]\n",
                outStream.toString())
    }

    @Test(expected = InvalidInputException::class)
    fun parse_invalidMagicNumber() {
        val logBuilder = ProtoLogFileProto.newBuilder()
        logBuilder.setVersion(Constants.VERSION)
        logBuilder.magicNumber = 0
        val stream = logBuilder.build().toByteArray().inputStream()

        parser.parse(stream, getConfigDummyStream(), printStream)
    }

    @Test(expected = InvalidInputException::class)
    fun parse_invalidVersion() {
        val logBuilder = ProtoLogFileProto.newBuilder()
        logBuilder.setVersion("invalid")
        logBuilder.magicNumber =
                ProtoLogFileProto.MagicNumber.MAGIC_NUMBER_H.number.toLong() shl 32 or
                        ProtoLogFileProto.MagicNumber.MAGIC_NUMBER_L.number.toLong()
        val stream = logBuilder.build().toByteArray().inputStream()

        parser.parse(stream, getConfigDummyStream(), printStream)
    }

    @Test
    fun parse_noConfig() {
        val logBuilder = ProtoLogFileProto.newBuilder()
        val logMessageBuilder = ProtoLogMessage.newBuilder()
        logMessageBuilder
                .setMessageHash(70933285)
                .setElapsedRealtimeNanos(0)
                .addBooleanParams(true)
        logBuilder.addLog(logMessageBuilder.build())

        parser.parse(buildProtoInput(logBuilder), getConfigDummyStream(), printStream)

        assertEquals("${testDate(0)} UNKNOWN: 70933285 - [] [] [] [true]\n",
                outStream.toString())
    }
}
