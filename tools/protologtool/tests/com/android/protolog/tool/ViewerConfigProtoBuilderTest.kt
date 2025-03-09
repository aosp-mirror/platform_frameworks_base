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

import com.android.internal.protolog.common.LogLevel
import com.android.protolog.tool.ProtoLogTool.LogCall
import com.google.common.truth.Truth
import org.junit.Test
import perfetto.protos.PerfettoTrace.ProtoLogViewerConfig

class ViewerConfigProtoBuilderTest {
    companion object {
        private val TAG1 = "WM_TEST"
        private val TAG2 = "WM_DEBUG"

        private val TEST1 = ViewerConfigParser.ConfigEntry("test1", LogLevel.INFO.name,
            TAG1
        )
        private val TEST2 = ViewerConfigParser.ConfigEntry("test2", LogLevel.DEBUG.name,
            TAG2
        )

        private val GROUP1 = LogGroup("TEST_GROUP", true, true, TAG1)
        private val GROUP2 = LogGroup("DEBUG_GROUP", true, true, TAG2)
        private val GROUP3 = LogGroup("UNUSED_GROUP", true, true, TAG1)

        private val GROUPS = listOf(
            GROUP1,
            GROUP2,
            GROUP3
        )

        private const val PATH = "/tmp/test.java"
    }

    @Test
    fun includesUnusedProtoLogGroups() {
        // Added because of b/373754057. This test might need to be removed in the future.

        val configBuilder = ViewerConfigProtoBuilder()

        val logCallRegistry = ProtoLogTool.LogCallRegistry()
        logCallRegistry.addLogCalls(listOf(
            LogCall(TEST1.messageString, LogLevel.INFO, GROUP1, PATH),
            LogCall(TEST2.messageString, LogLevel.INFO, GROUP2, PATH),
        ))

        val rawProto = configBuilder.build(GROUPS, logCallRegistry.getStatements())

        val viewerConfig = ProtoLogViewerConfig.parseFrom(rawProto)
        Truth.assertThat(viewerConfig.groupsCount).isEqualTo(GROUPS.size)
        Truth.assertThat(viewerConfig.messagesCount).isLessThan(GROUPS.size)
    }
}