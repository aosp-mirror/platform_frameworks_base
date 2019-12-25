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
 * limitations under the License
 */

package com.android.keyguard.clock

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

private const val PACKAGE = "com.android.keyguard.clock.Clock"
private const val CLOCK_FIELD = "clock"
private const val TIMESTAMP_FIELD = "_applied_timestamp"
private const val USER_ID = 0

@RunWith(AndroidTestingRunner::class)
@SmallTest
class SettingsWrapperTest : SysuiTestCase() {

    private lateinit var wrapper: SettingsWrapper
    private lateinit var migration: SettingsWrapper.Migration

    @Before
    fun setUp() {
        migration = mock(SettingsWrapper.Migration::class.java)
        wrapper = SettingsWrapper(getContext().contentResolver, migration)
    }

    @Test
    fun testDecodeUnnecessary() {
        // GIVEN a settings value that doesn't need to be decoded
        val value = PACKAGE
        // WHEN the value is decoded
        val decoded = wrapper.decode(value, USER_ID)
        // THEN the same value is returned, because decoding isn't necessary.
        // TODO(b/135674383): Null should be returned when the migration code in removed.
        assertThat(decoded).isEqualTo(value)
        // AND the value is migrated to JSON format
        verify(migration).migrate(value, USER_ID)
    }

    @Test
    fun testDecodeJSON() {
        // GIVEN a settings value that is encoded in JSON
        val json: JSONObject = JSONObject()
        json.put(CLOCK_FIELD, PACKAGE)
        json.put(TIMESTAMP_FIELD, System.currentTimeMillis())
        val value = json.toString()
        // WHEN the value is decoded
        val decoded = wrapper.decode(value, USER_ID)
        // THEN the clock field should have been extracted
        assertThat(decoded).isEqualTo(PACKAGE)
    }

    @Test
    fun testDecodeJSONWithoutClockField() {
        // GIVEN a settings value that doesn't contain the CLOCK_FIELD
        val json: JSONObject = JSONObject()
        json.put(TIMESTAMP_FIELD, System.currentTimeMillis())
        val value = json.toString()
        // WHEN the value is decoded
        val decoded = wrapper.decode(value, USER_ID)
        // THEN null is returned
        assertThat(decoded).isNull()
        // AND the value is not migrated to JSON format
        verify(migration, never()).migrate(value, USER_ID)
    }

    @Test
    fun testDecodeNullJSON() {
        assertThat(wrapper.decode(null, USER_ID)).isNull()
    }
}
