/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.log.table

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableChange.Companion.IS_INITIAL_PREFIX
import com.android.systemui.log.table.TableChange.Companion.MAX_STRING_LENGTH
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TableChangeTest : SysuiTestCase() {

    @Test
    fun setString_isString() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "",
            columnName = "fakeName",
            isInitial = false,
        )
        underTest.set("fakeValue")

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("fakeValue")
    }

    @Test
    fun setString_null() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "",
            columnName = "fakeName",
            isInitial = false,
        )
        underTest.set(null as String?)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("null")
    }

    @Test
    fun setBoolean_isBoolean() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "",
            columnName = "fakeName",
            isInitial = false,
        )
        underTest.set(true)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("true")
    }

    @Test
    fun setInt_isInt() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "",
            columnName = "fakeName",
            isInitial = false,
        )
        underTest.set(8900)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("8900")
    }

    @Test
    fun setInt_null() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "",
            columnName = "fakeName",
            isInitial = false,
        )
        underTest.set(null as Int?)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("null")
    }

    @Test
    fun setThenReset_isEmpty() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "",
            columnName = "fakeName",
            isInitial = false,
        )
        underTest.set(8900)
        underTest.reset(
            timestamp = 0,
            columnPrefix = "prefix",
            columnName = "name",
            isInitial = false,
        )

        assertThat(underTest.hasData()).isFalse()
        assertThat(underTest.getVal()).isEqualTo("null")
    }

    @Test
    fun getName_hasPrefix() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")

        assertThat(underTest.getName()).contains("fakePrefix")
        assertThat(underTest.getName()).contains("fakeName")
    }

    @Test
    fun getName_noPrefix() {
        val underTest = TableChange(columnPrefix = "", columnName = "fakeName")

        assertThat(underTest.getName()).contains("fakeName")
    }

    @Test
    fun getVal_notInitial() {
        val underTest = TableChange(columnName = "name", isInitial = false)
        underTest.set("testValue")

        assertThat(underTest.getVal()).isEqualTo("testValue")
    }

    @Test
    fun getVal_isInitial() {
        val underTest = TableChange(columnName = "name", isInitial = true)
        underTest.set("testValue")

        assertThat(underTest.getVal()).isEqualTo("${IS_INITIAL_PREFIX}testValue")
    }

    @Test
    fun resetThenSet_hasNewValue() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "prefix",
            columnName = "original",
            isInitial = false,
        )
        underTest.set("fakeValue")
        underTest.reset(timestamp = 0, columnPrefix = "", columnName = "updated", isInitial = false)
        underTest.set(8900)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("updated")
        assertThat(underTest.getName()).doesNotContain("prefix")
        assertThat(underTest.getName()).doesNotContain("original")
        assertThat(underTest.getVal()).isEqualTo("8900")
    }

    @Test
    fun reset_initialToNotInitial_valDoesNotHaveInitial() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "prefix",
            columnName = "original",
            isInitial = true,
        )
        underTest.set("fakeValue")
        underTest.reset(timestamp = 0, columnPrefix = "", columnName = "updated", isInitial = false)
        underTest.set(8900)

        assertThat(underTest.getVal()).doesNotContain(IS_INITIAL_PREFIX)
    }

    @Test
    fun reset_notInitialToInitial_valHasInitial() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 100,
            columnPrefix = "prefix",
            columnName = "original",
            isInitial = false,
        )
        underTest.set("fakeValue")
        underTest.reset(timestamp = 0, columnPrefix = "", columnName = "updated", isInitial = true)
        underTest.set(8900)

        assertThat(underTest.getVal()).contains(IS_INITIAL_PREFIX)
    }

    @Test
    fun updateTo_emptyToString_isString() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set("newString")
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("newString")
    }

    @Test
    fun updateTo_intToEmpty_isEmpty() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(42)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isFalse()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("null")
    }

    @Test
    fun updateTo_stringToBool_isBool() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set("oldString")

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set(true)
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("true")
    }

    @Test
    fun updateTo_intToString_isString() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(43)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set("newString")
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("newString")
    }

    @Test
    fun updateTo_boolToInt_isInt() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(false)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set(44)
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("44")
    }

    @Test
    fun updateTo_boolToNewBool_isNewBool() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(false)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set(true)
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("true")
    }

    @Test
    fun updateTo_notInitialToInitial_isInitial() {
        val underTest =
            TableChange(columnPrefix = "fakePrefix", columnName = "fakeName", isInitial = false)
        underTest.set(false)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName", isInitial = true)
        new.set(true)
        underTest.updateTo(new)

        assertThat(underTest.getVal()).contains(IS_INITIAL_PREFIX)
    }

    @Test
    fun updateTo_initialToNotInitial_isNotInitial() {
        val underTest =
            TableChange(columnPrefix = "fakePrefix", columnName = "fakeName", isInitial = true)
        underTest.set(false)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName", isInitial = false)
        new.set(true)
        underTest.updateTo(new)

        assertThat(underTest.getVal()).doesNotContain(IS_INITIAL_PREFIX)
    }

    @Test
    fun constructor_columnAndValueTooLong_truncated() {
        val underTest =
            TableChange(
                columnPrefix = "P".repeat(MAX_STRING_LENGTH + 10),
                columnName = "N".repeat(MAX_STRING_LENGTH + 10),
                type = TableChange.DataType.STRING,
                str = "V".repeat(MAX_STRING_LENGTH + 10),
            )

        assertThat(underTest.getName()).contains("P".repeat(MAX_STRING_LENGTH))
        assertThat(underTest.getName()).doesNotContain("P".repeat(MAX_STRING_LENGTH + 1))
        assertThat(underTest.getName()).contains("N".repeat(MAX_STRING_LENGTH))
        assertThat(underTest.getName()).doesNotContain("N".repeat(MAX_STRING_LENGTH + 1))
        assertThat(underTest.getVal()).isEqualTo("V".repeat(MAX_STRING_LENGTH))
    }

    @Test
    fun constructor_columnNameNotTooLong_noReallocation() {
        val inputColumnName = "fakeName"
        val inputValue = "fakeValue"
        val underTest =
            TableChange(
                columnPrefix = "",
                columnName = inputColumnName,
                type = TableChange.DataType.STRING,
                str = inputValue,
            )

        // Use referential equality to verify we didn't reallocate a new string when the string is
        // *not* too long.
        assertTrue(underTest.getColumnName() === inputColumnName)
    }

    @Test
    fun reset_columnPrefixTooLong_truncated() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 1L,
            columnPrefix = "P".repeat(MAX_STRING_LENGTH + 10),
            columnName = "name",
            isInitial = false,
        )

        assertThat(underTest.getName()).contains("P".repeat(MAX_STRING_LENGTH))
        assertThat(underTest.getName()).doesNotContain("P".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun reset_columnNameTooLong_truncated() {
        val underTest = TableChange()

        underTest.reset(
            timestamp = 1L,
            columnPrefix = "prefix",
            columnName = "N".repeat(MAX_STRING_LENGTH + 10),
            isInitial = false,
        )

        assertThat(underTest.getName()).contains("N".repeat(MAX_STRING_LENGTH))
        assertThat(underTest.getName()).doesNotContain("N".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun reset_columnNameNotTooLong_noReallocation() {
        val underTest = TableChange()
        val shortColumnName = "shortColumnName"

        underTest.reset(
            timestamp = 1L,
            columnPrefix = "prefix",
            columnName = shortColumnName,
            isInitial = false,
        )

        // Use referential equality to verify we didn't reallocate a new string when the string is
        // *not* too long.
        assertTrue(underTest.getColumnName() === shortColumnName)
    }

    @Test
    fun setString_valueTooLong_truncated() {
        val underTest = TableChange()

        underTest.set("V".repeat(MAX_STRING_LENGTH + 1))

        assertThat(underTest.getVal()).isEqualTo("V".repeat(MAX_STRING_LENGTH))
    }

    @Test
    fun updateTo_newColumnPrefixTooLong_truncated() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(42)

        val new =
            TableChange(
                columnPrefix = "P".repeat(MAX_STRING_LENGTH + 10),
                columnName = "name",
            )
        underTest.updateTo(new)

        assertThat(underTest.getName()).contains("P".repeat(MAX_STRING_LENGTH))
        assertThat(underTest.getName()).doesNotContain("P".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun updateTo_newColumnNameTooLong_truncated() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(42)

        val new =
            TableChange(
                columnPrefix = "prefix",
                columnName = "N".repeat(MAX_STRING_LENGTH + 10),
            )
        underTest.updateTo(new)

        assertThat(underTest.getName()).contains("N".repeat(MAX_STRING_LENGTH))
        assertThat(underTest.getName()).doesNotContain("N".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun updateTo_columnNameNotTooLong_noReallocation() {
        val underTest = TableChange()
        val shortColumnName = "shortColumnName"
        val new = TableChange(columnPrefix = "prefix", columnName = shortColumnName)

        underTest.updateTo(new)

        // Use referential equality to verify we didn't reallocate a new string when the string is
        // *not* too long.
        assertTrue(underTest.getColumnName() === shortColumnName)
    }

    @Test
    fun updateTo_newValTooLong_truncated() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set("value")

        val new = TableChange()
        new.set("V".repeat(MAX_STRING_LENGTH + 10))

        underTest.updateTo(new)

        assertThat(underTest.getVal()).isEqualTo("V".repeat(MAX_STRING_LENGTH))
    }
}
