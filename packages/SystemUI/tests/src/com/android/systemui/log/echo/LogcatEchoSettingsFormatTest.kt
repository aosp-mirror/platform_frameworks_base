/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.log.echo

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.echo.EchoOverrideType.BUFFER
import com.android.systemui.log.echo.EchoOverrideType.TAG
import kotlin.test.assertEquals
import org.junit.Test

@SmallTest
class LogcatEchoSettingsFormatTest : SysuiTestCase() {

    private val format = LogcatEchoSettingFormat()

    @Test
    fun testReversibility() {
        val expectedOverrides =
            listOf(
                LogcatEchoOverride(BUFFER, "buffer_0", DEBUG),
                LogcatEchoOverride(BUFFER, "buffer_1", LogLevel.WTF),
                LogcatEchoOverride(EchoOverrideType.TAG, "tag_1", LogLevel.INFO),
            )

        val storedAndLoadedOverrides =
            format.parseOverrides(format.stringifyOverrides(expectedOverrides))

        assertEquals(expectedOverrides.toSet(), storedAndLoadedOverrides.toSet())
    }

    @Test
    fun testSemicolonEscaping() {
        val expectedOverrides =
            listOf(
                LogcatEchoOverride(BUFFER, "buf;fer;0;", DEBUG),
            )

        val storedAndLoadedOverrides =
            format.parseOverrides(format.stringifyOverrides(expectedOverrides))

        assertEquals(expectedOverrides.toSet(), storedAndLoadedOverrides.toSet())
    }

    @Test
    fun testMalformedFormatStillReturnsPartialResults() {
        val result = format.parseOverrides("0;t;valid_tag;d;malformed;thing")

        assertEquals(listOf(LogcatEchoOverride(TAG, "valid_tag", DEBUG)), result)
    }

    @Test
    fun testGarbageInputDoesNotCrash() {
        assertEquals(emptyList(), format.parseOverrides("(&983n123"))
    }
}
