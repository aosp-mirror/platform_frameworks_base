/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.flags

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth
import java.lang.StringBuilder
import java.util.ArrayList
import java.util.HashMap
import org.junit.Test

@SmallTest
class FlagsTest : SysuiTestCase() {
    @Test
    fun testDuplicateFlagIdCheckWorks() {
        val flags = Flags.collectFlagsInClass(DuplicateFlagContainer)
        val duplicates = groupDuplicateFlags(flags)
        Truth.assertWithMessage(generateAssertionMessage(duplicates))
            .that(duplicates.size)
            .isEqualTo(2)
    }

    @Test
    fun testNoDuplicateFlagIds() {
        val flags = Flags.collectFlagsInClass(Flags)
        val duplicates = groupDuplicateFlags(flags)
        Truth.assertWithMessage(generateAssertionMessage(duplicates))
            .that(duplicates.size)
            .isEqualTo(0)
    }

    private fun generateAssertionMessage(duplicates: Map<Int, List<String>>): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append("Duplicate flag keys found: {")
        for (id in duplicates.keys) {
            stringBuilder
                .append(" ")
                .append(id)
                .append(": [")
                .append(java.lang.String.join(", ", duplicates[id]))
                .append("]")
        }
        stringBuilder.append(" }")
        return stringBuilder.toString()
    }

    private fun groupDuplicateFlags(flags: Map<String, Flag<*>>): Map<Int, List<String>> {
        val grouping: MutableMap<Int, MutableList<String>> = HashMap()
        for (flag in flags) {
            grouping.putIfAbsent(flag.value.id, ArrayList())
            grouping[flag.value.id]!!.add(flag.key)
        }
        val result: MutableMap<Int, List<String>> = HashMap()
        for (id in grouping.keys) {
            if (grouping[id]!!.size > 1) {
                result[id] = grouping[id]!!
            }
        }
        return result
    }

    private object DuplicateFlagContainer {
        val A_FLAG: BooleanFlag = UnreleasedFlag(0)
        val B_FLAG: BooleanFlag = UnreleasedFlag(0)
        val C_FLAG = StringFlag(0)
        val D_FLAG: BooleanFlag = UnreleasedFlag(1)
        val E_FLAG = DoubleFlag(3)
        val F_FLAG = DoubleFlag(3)
    }
}
