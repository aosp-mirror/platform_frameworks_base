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

open class ViewerConfigParser {
    data class MessageEntry(
        val messageString: String,
        val level: String,
        val groupName: String
    )

    fun parseMessage(jsonReader: JsonReader): MessageEntry {
        jsonReader.beginObject()
        var message: String? = null
        var level: String? = null
        var groupName: String? = null
        while (jsonReader.hasNext()) {
            val key = jsonReader.nextName()
            when (key) {
                "message" -> message = jsonReader.nextString()
                "level" -> level = jsonReader.nextString()
                "group" -> groupName = jsonReader.nextString()
                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()
        if (message.isNullOrBlank() || level.isNullOrBlank() || groupName.isNullOrBlank()) {
            throw InvalidViewerConfigException("Invalid message entry in viewer config")
        }
        return MessageEntry(message, level, groupName)
    }

    data class GroupEntry(val tag: String)

    fun parseGroup(jsonReader: JsonReader): GroupEntry {
        jsonReader.beginObject()
        var tag: String? = null
        while (jsonReader.hasNext()) {
            val key = jsonReader.nextName()
            when (key) {
                "tag" -> tag = jsonReader.nextString()
                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()
        if (tag.isNullOrBlank()) {
            throw InvalidViewerConfigException("Invalid group entry in viewer config")
        }
        return GroupEntry(tag)
    }

    fun parseMessages(jsonReader: JsonReader): Map<Int, MessageEntry> {
        val config: MutableMap<Int, MessageEntry> = mutableMapOf()
        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            val key = jsonReader.nextName()
            val hash = key.toIntOrNull()
                    ?: throw InvalidViewerConfigException("Invalid key in messages viewer config")
            config[hash] = parseMessage(jsonReader)
        }
        jsonReader.endObject()
        return config
    }

    fun parseGroups(jsonReader: JsonReader): Map<String, GroupEntry> {
        val config: MutableMap<String, GroupEntry> = mutableMapOf()
        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            val key = jsonReader.nextName()
            config[key] = parseGroup(jsonReader)
        }
        jsonReader.endObject()
        return config
    }

    data class ConfigEntry(val messageString: String, val level: String, val tag: String)

    open fun parseConfig(jsonReader: JsonReader): Map<Int, ConfigEntry> {
        var messages: Map<Int, MessageEntry>? = null
        var groups: Map<String, GroupEntry>? = null
        var version: String? = null

        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            val key = jsonReader.nextName()
            when (key) {
                "messages" -> messages = parseMessages(jsonReader)
                "groups" -> groups = parseGroups(jsonReader)
                "version" -> version = jsonReader.nextString()

                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()
        if (messages == null || groups == null || version == null) {
            throw InvalidViewerConfigException("Invalid config - definitions missing")
        }
        if (version != Constants.VERSION) {
            throw InvalidViewerConfigException("Viewer config version not supported by this tool," +
                    " config version $version, viewer version ${Constants.VERSION}")
        }
        return messages.map { msg ->
            msg.key to ConfigEntry(
                    msg.value.messageString, msg.value.level, groups[msg.value.groupName]?.tag
                    ?: throw InvalidViewerConfigException(
                            "Group definition missing for ${msg.value.groupName}"))
        }.toMap()
    }
}
