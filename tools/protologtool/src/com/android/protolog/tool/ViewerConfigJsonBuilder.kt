/*
 * Copyright (C) 2024 The Android Open Source Project
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
import com.android.protolog.tool.Constants.VERSION
import java.io.StringWriter

class ViewerConfigJsonBuilder : ProtoLogTool.ProtologViewerConfigBuilder {
    override fun build(statements: Map<ProtoLogTool.LogCall, Long>): ByteArray {
        val groups = statements.map { it.key.logGroup }.toSet()
        val stringWriter = StringWriter()
        val writer = JsonWriter(stringWriter)
        writer.setIndent("  ")
        writer.beginObject()
        writer.name("version")
        writer.value(VERSION)
        writer.name("messages")
        writer.beginObject()
        statements.forEach { (log, key) ->
            writer.name(key.toString())
            writer.beginObject()
            writer.name("message")
            writer.value(log.messageString)
            writer.name("level")
            writer.value(log.logLevel.name)
            writer.name("group")
            writer.value(log.logGroup.name)
            writer.name("at")
            writer.value(log.position)
            writer.endObject()
        }
        writer.endObject()
        writer.name("groups")
        writer.beginObject()
        groups.toSortedSet { o1, o2 -> o1.name.compareTo(o2.name) }.forEach { group ->
            writer.name(group.name)
            writer.beginObject()
            writer.name("tag")
            writer.value(group.tag)
            writer.endObject()
        }
        writer.endObject()
        writer.endObject()
        stringWriter.buffer.append('\n')
        return stringWriter.toString().toByteArray()
    }
}
