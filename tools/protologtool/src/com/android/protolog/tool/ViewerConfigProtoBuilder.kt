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

import perfetto.protos.PerfettoTrace.ProtoLogLevel
import perfetto.protos.PerfettoTrace.ProtoLogViewerConfig

/**
 * A builder class to construct the viewer configuration (i.e. mappings of protolog hashes to log
 * message information used to decode the protolog messages) encoded as a proto message.
 */
class ViewerConfigProtoBuilder : ProtoLogTool.ProtologViewerConfigBuilder {
    /**
     * @return a byte array of a ProtoLogViewerConfig proto message encoding all the viewer
     * configurations mapping protolog hashes to message information and log group information.
     */
    override fun build(statements: Map<ProtoLogTool.LogCall, Long>): ByteArray {
        val configBuilder = ProtoLogViewerConfig.newBuilder()

        val groups = statements.map { it.key.logGroup }.toSet()
        val groupIds = mutableMapOf<LogGroup, Int>()
        groups.forEach {
            groupIds.putIfAbsent(it, groupIds.size + 1)
        }

        groupIds.forEach { (group, id) ->
            configBuilder.addGroups(ProtoLogViewerConfig.Group.newBuilder()
                    .setId(id)
                    .setName(group.name)
                    .setTag(group.tag)
                    .build())
        }

        statements.forEach { (log, key) ->
            val groupId = groupIds[log.logGroup] ?: error("missing group id")

            configBuilder.addMessages(
                ProtoLogViewerConfig.MessageData.newBuilder()
                        .setMessageId(key)
                        .setMessage(log.messageString)
                        .setLevel(
                            ProtoLogLevel.forNumber(log.logLevel.id))
                        .setGroupId(groupId)
                        .setLocation(log.position)
            )
        }

        return configBuilder.build().toByteArray()
    }
}
