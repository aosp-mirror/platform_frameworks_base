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

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoLogToolTest {

    @Test
    fun generateLogGroupCache() {
        val groups = mapOf(
                "GROUP1" to LogGroup("GROUP1", true, true, "TAG1"),
                "GROUP2" to LogGroup("GROUP2", true, true, "TAG2")
        )
        val code = ProtoLogTool.generateLogGroupCache("org.example", "ProtoLog\$Cache",
                groups, "org.example.ProtoLogImpl", "org.example.ProtoLogGroups")

        assertEquals("""
            package org.example;

            public class ProtoLog${'$'}Cache {
                public static boolean GROUP1_enabled = false;
                public static boolean GROUP2_enabled = false;

                static {
                    org.example.ProtoLogImpl.sCacheUpdater = ProtoLog${'$'}Cache::update;
                    update();
                }

                static void update() {
                    GROUP1_enabled = org.example.ProtoLogImpl.isEnabled(org.example.ProtoLogGroups.GROUP1);
                    GROUP2_enabled = org.example.ProtoLogImpl.isEnabled(org.example.ProtoLogGroups.GROUP2);
                }
            }
        """.trimIndent(), code)
    }
}