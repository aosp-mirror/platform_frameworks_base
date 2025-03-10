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

import android.content.Context
import androidx.test.filters.SmallTest
import com.android.server.display.feature.DisplayManagerFlags
import com.android.server.display.plugin.PluginManager.PluginChangeListener

import org.junit.Test

import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private val TEST_PLUGIN_TYPE = PluginType(Int::class.java, "test_type")

@SmallTest
class PluginManagerTest {

    private val mockContext = mock<Context>()
    private val mockFlags = mock<DisplayManagerFlags>()
    private val mockListener = mock<PluginChangeListener<Int>>()
    private val testInjector = TestInjector()

    @Test
    fun testBootCompleted_enabledPluginManager() {
        val pluginManager = createPluginManager()

        pluginManager.onBootCompleted()

        verify(testInjector.mockPlugin1).onBootCompleted()
        verify(testInjector.mockPlugin2).onBootCompleted()
    }

    @Test
    fun testBootCompleted_disabledPluginManager() {
        val pluginManager = createPluginManager(false)

        pluginManager.onBootCompleted()

        verify(testInjector.mockPlugin1, never()).onBootCompleted()
        verify(testInjector.mockPlugin2, never()).onBootCompleted()
    }

    @Test
    fun testSubscribe() {
        val pluginManager = createPluginManager()

        pluginManager.subscribe(TEST_PLUGIN_TYPE, mockListener)

        verify(testInjector.mockStorage).addListener(TEST_PLUGIN_TYPE, mockListener)
    }

    @Test
    fun testUnsubscribe() {
        val pluginManager = createPluginManager()

        pluginManager.unsubscribe(TEST_PLUGIN_TYPE, mockListener)

        verify(testInjector.mockStorage).removeListener(TEST_PLUGIN_TYPE, mockListener)
    }

    private fun createPluginManager(enabled: Boolean = true): PluginManager {
        whenever(mockFlags.isPluginManagerEnabled).thenReturn(enabled)
        return PluginManager(mockContext, mockFlags, testInjector)
    }

    private class TestInjector : PluginManager.Injector() {
        val mockStorage = mock<PluginStorage>()
        val mockPlugin1 = mock<Plugin>()
        val mockPlugin2 = mock<Plugin>()

        override fun getPluginStorage(): PluginStorage {
            return mockStorage
        }

        override fun loadPlugins(context: Context?, storage: PluginStorage?): List<Plugin> {
            return listOf(mockPlugin1, mockPlugin2)
        }
    }
}
