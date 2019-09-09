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

package com.android.protologtool

import com.android.json.stream.JsonReader
import com.android.server.wm.ProtoLogMessage
import com.android.server.wm.WindowManagerLogFileProto
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Implements a simple parser/viewer for binary ProtoLog logs.
 * A binary log is translated into Android "LogCat"-like text log.
 */
class LogParser(private val configParser: ViewerConfigParser) {
    companion object {
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        private val magicNumber =
                WindowManagerLogFileProto.MagicNumber.MAGIC_NUMBER_H.number.toLong() shl 32 or
                        WindowManagerLogFileProto.MagicNumber.MAGIC_NUMBER_L.number.toLong()
    }

    private fun printTime(time: Long, offset: Long, ps: PrintStream) {
        ps.print(dateFormat.format(Date(time / 1000000 + offset)) + " ")
    }

    private fun printFormatted(
        protoLogMessage: ProtoLogMessage,
        configEntry: ViewerConfigParser.ConfigEntry,
        ps: PrintStream
    ) {
        val strParmIt = protoLogMessage.strParamsList.iterator()
        val longParamsIt = protoLogMessage.sint64ParamsList.iterator()
        val doubleParamsIt = protoLogMessage.doubleParamsList.iterator()
        val boolParamsIt = protoLogMessage.booleanParamsList.iterator()
        val args = mutableListOf<Any>()
        val format = configEntry.messageString
        val argTypes = CodeUtils.parseFormatString(format)
        try {
            argTypes.forEach {
                when (it) {
                    CodeUtils.LogDataTypes.BOOLEAN -> args.add(boolParamsIt.next())
                    CodeUtils.LogDataTypes.LONG -> args.add(longParamsIt.next())
                    CodeUtils.LogDataTypes.DOUBLE -> args.add(doubleParamsIt.next())
                    CodeUtils.LogDataTypes.STRING -> args.add(strParmIt.next())
                }
            }
        } catch (ex: NoSuchElementException) {
            throw InvalidFormatStringException("Invalid format string in config", ex)
        }
        if (strParmIt.hasNext() || longParamsIt.hasNext() ||
                doubleParamsIt.hasNext() || boolParamsIt.hasNext()) {
            throw RuntimeException("Invalid format string in config - no enough matchers")
        }
        val formatted = format.format(*(args.toTypedArray()))
        ps.print("${configEntry.level} ${configEntry.tag}: $formatted\n")
    }

    private fun printUnformatted(protoLogMessage: ProtoLogMessage, ps: PrintStream, tag: String) {
        ps.println("$tag: ${protoLogMessage.messageHash} - ${protoLogMessage.strParamsList}" +
                " ${protoLogMessage.sint64ParamsList} ${protoLogMessage.doubleParamsList}" +
                " ${protoLogMessage.booleanParamsList}")
    }

    fun parse(protoLogInput: InputStream, jsonConfigInput: InputStream, ps: PrintStream) {
        val jsonReader = JsonReader(BufferedReader(InputStreamReader(jsonConfigInput)))
        val config = configParser.parseConfig(jsonReader)
        val protoLog = WindowManagerLogFileProto.parseFrom(protoLogInput)

        if (protoLog.magicNumber != magicNumber) {
            throw InvalidInputException("ProtoLog file magic number is invalid.")
        }
        if (protoLog.version != Constants.VERSION) {
            throw InvalidInputException("ProtoLog file version not supported by this tool," +
                    " log version ${protoLog.version}, viewer version ${Constants.VERSION}")
        }

        protoLog.logList.forEach { log ->
            printTime(log.elapsedRealtimeNanos, protoLog.realTimeToElapsedTimeOffsetMillis, ps)
            if (log.messageHash !in config) {
                printUnformatted(log, ps, "UNKNOWN")
            } else {
                val conf = config.getValue(log.messageHash)
                try {
                    printFormatted(log, conf, ps)
                } catch (ex: Exception) {
                    printUnformatted(log, ps, "INVALID")
                }
            }
        }
    }
}
