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

import com.android.json.stream.JsonWriter
import com.github.javaparser.ast.CompilationUnit
import com.android.protolog.tool.Constants.VERSION
import com.github.javaparser.ast.expr.MethodCallExpr
import java.io.StringWriter

class ViewerConfigBuilder(
    private val protoLogCallVisitor: ProtoLogCallProcessor
) : ProtoLogCallVisitor {
    override fun processCall(
        call: MethodCallExpr,
        messageString: String,
        level: LogLevel,
        group: LogGroup
    ) {
        if (group.enabled) {
            val position = CodeUtils.getPositionString(call, fileName)
            val key = CodeUtils.hash(position, messageString, level, group)
            if (statements.containsKey(key)) {
                if (statements[key] != LogCall(messageString, level, group, position)) {
                    throw HashCollisionException(
                            "Please modify the log message \"$messageString\" " +
                                    "or \"${statements[key]}\" - their hashes are equal.")
                }
            } else {
                groups.add(group)
                statements[key] = LogCall(messageString, level, group, position)
                call.range.isPresent
            }
        }
    }

    private val statements: MutableMap<Int, LogCall> = mutableMapOf()
    private val groups: MutableSet<LogGroup> = mutableSetOf()
    private var fileName: String = ""

    fun processClass(unit: CompilationUnit, fileName: String) {
        this.fileName = fileName
        protoLogCallVisitor.process(unit, this)
    }

    fun build(): String {
        val stringWriter = StringWriter()
        val writer = JsonWriter(stringWriter)
        writer.setIndent("  ")
        writer.beginObject()
        writer.name("version")
        writer.value(VERSION)
        writer.name("messages")
        writer.beginObject()
        statements.toSortedMap().forEach { (key, value) ->
            writer.name(key.toString())
            writer.beginObject()
            writer.name("message")
            writer.value(value.messageString)
            writer.name("level")
            writer.value(value.logLevel.name)
            writer.name("group")
            writer.value(value.logGroup.name)
            writer.name("at")
            writer.value(value.position)
            writer.endObject()
        }
        writer.endObject()
        writer.name("groups")
        writer.beginObject()
        groups.toSortedSet(Comparator { o1, o2 -> o1.name.compareTo(o2.name) }).forEach { group ->
            writer.name(group.name)
            writer.beginObject()
            writer.name("tag")
            writer.value(group.tag)
            writer.endObject()
        }
        writer.endObject()
        writer.endObject()
        stringWriter.buffer.append('\n')
        return stringWriter.toString()
    }

    data class LogCall(
        val messageString: String,
        val logLevel: LogLevel,
        val logGroup: LogGroup,
        val position: String
    )
}
