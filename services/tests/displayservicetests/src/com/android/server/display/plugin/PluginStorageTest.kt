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

package com.android.server.display.plugin

import androidx.test.filters.SmallTest
import com.android.server.display.plugin.PluginManager.PluginChangeListener
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private val TEST_PLUGIN_TYPE1 = PluginType(String::class.java, "test_type1")
private val TEST_PLUGIN_TYPE2 = PluginType(String::class.java, "test_type2")
private val DISPLAY_ID_1 = "display_1"
private val DISPLAY_ID_2 = "display_2"

@SmallTest
class PluginStorageTest {

    var storage = PluginStorage(setOf(TEST_PLUGIN_TYPE1, TEST_PLUGIN_TYPE2))

    @Test
    fun testUpdateValue() {
        val type1Value = "value1"
        val testChangeListener = TestPluginChangeListener<String>()
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener)

        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, type1Value)

        assertThat(testChangeListener.receivedValue).isEqualTo(type1Value)
    }

    @Test
    fun testAddListener() {
        val type1Value = "value1"
        val testChangeListener = TestPluginChangeListener<String>()
        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, type1Value)

        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener)

        assertThat(testChangeListener.receivedValue).isEqualTo(type1Value)
    }

    @Test
    fun testRemoveListener() {
        val type1Value = "value1"
        val testChangeListener = TestPluginChangeListener<String>()
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener)
        storage.removeListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener)

        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, type1Value)

        assertThat(testChangeListener.receivedValue).isNull()
    }

    @Test
    fun testAddListener_multipleValues() {
        val type1Value = "value1"
        val type2Value = "value2"
        val testChangeListener = TestPluginChangeListener<String>()
        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, type1Value)
        storage.updateValue(TEST_PLUGIN_TYPE2, DISPLAY_ID_1, type2Value)

        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener)

        assertThat(testChangeListener.receivedValue).isEqualTo(type1Value)
    }

    @Test
    fun testUpdateValue_multipleListeners() {
        val type1Value = "value1"
        val testChangeListener1 = TestPluginChangeListener<String>()
        val testChangeListener2 = TestPluginChangeListener<String>()
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener1)
        storage.addListener(TEST_PLUGIN_TYPE2, DISPLAY_ID_1, testChangeListener2)

        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, type1Value)

        assertThat(testChangeListener1.receivedValue).isEqualTo(type1Value)
        assertThat(testChangeListener2.receivedValue).isNull()
    }

    @Test
    fun testDisabledPluginType() {
        storage = PluginStorage(setOf(TEST_PLUGIN_TYPE2))
        val type1Value = "value1"
        val testChangeListener = TestPluginChangeListener<String>()
        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, type1Value)

        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener)

        assertThat(testChangeListener.receivedValue).isNull()
    }

    @Test
    fun testUpdateGlobal_noDisplaySpecificValue() {
        val type1Value = "value1"
        val testChangeListener1 = TestPluginChangeListener<String>()
        val testChangeListener2 = TestPluginChangeListener<String>()
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener1)
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_2, testChangeListener2)

        storage.updateGlobalValue(TEST_PLUGIN_TYPE1, type1Value)

        assertThat(testChangeListener1.receivedValue).isEqualTo(type1Value)
        assertThat(testChangeListener2.receivedValue).isEqualTo(type1Value)
    }

    @Test
    fun testUpdateGlobal_withDisplaySpecificValue() {
        val type1Value = "value1"
        val type1GlobalValue = "value1Global"
        val testChangeListener1 = TestPluginChangeListener<String>()
        val testChangeListener2 = TestPluginChangeListener<String>()
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener1)
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_2, testChangeListener2)

        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, type1Value)
        storage.updateGlobalValue(TEST_PLUGIN_TYPE1, type1GlobalValue)

        assertThat(testChangeListener1.receivedValue).isEqualTo(type1Value)
        assertThat(testChangeListener2.receivedValue).isEqualTo(type1GlobalValue)
    }

    @Test
    fun testUpdateGlobal_withDisplaySpecificValueRemoved() {
        val type1Value = "value1"
        val type1GlobalValue = "value1Global"
        val testChangeListener1 = TestPluginChangeListener<String>()
        val testChangeListener2 = TestPluginChangeListener<String>()
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, testChangeListener1)
        storage.addListener(TEST_PLUGIN_TYPE1, DISPLAY_ID_2, testChangeListener2)

        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, type1Value)
        storage.updateGlobalValue(TEST_PLUGIN_TYPE1, type1GlobalValue)
        storage.updateValue(TEST_PLUGIN_TYPE1, DISPLAY_ID_1, null)

        assertThat(testChangeListener1.receivedValue).isEqualTo(type1GlobalValue)
        assertThat(testChangeListener2.receivedValue).isEqualTo(type1GlobalValue)
    }

    private class TestPluginChangeListener<T> : PluginChangeListener<T> {
        var receivedValue: T? = null

        override fun onChanged(value: T?) {
            receivedValue = value
        }
    }
}
