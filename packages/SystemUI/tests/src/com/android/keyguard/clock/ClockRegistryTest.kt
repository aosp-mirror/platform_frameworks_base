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
 * limitations under the License
 */

package com.android.keyguard.clock

import android.content.Context
import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.Clock
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.PluginListener
import com.android.systemui.shared.plugins.PluginManager
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import junit.framework.Assert.assertEquals
import junit.framework.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ClockRegistryTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockPluginManager: PluginManager
    @Mock private lateinit var mockClock: Clock
    @Mock private lateinit var mockThumbnail: Drawable
    private lateinit var pluginListener: PluginListener<ClockProviderPlugin>
    private lateinit var registry: ClockRegistry

    private fun failFactory(): Clock {
        fail("Unexpected call to createClock")
        return null!!
    }

    private fun failThumbnail(): Drawable? {
        fail("Unexpected call to getThumbnail")
        return null
    }

    private class FakeClockPlugin : ClockProviderPlugin {
        private val metadata = mutableListOf<ClockMetadata>()
        private val createCallbacks = mutableMapOf<ClockId, () -> Clock>()
        private val thumbnailCallbacks = mutableMapOf<ClockId, () -> Drawable?>()

        override fun getClocks() = metadata
        override fun createClock(id: ClockId): Clock = createCallbacks[id]!!()
        override fun getClockThumbnail(id: ClockId): Drawable? = thumbnailCallbacks[id]!!()

        fun addClock(
            id: ClockId,
            name: String,
            create: () -> Clock,
            getThumbnail: () -> Drawable?
        ) {
            metadata.add(ClockMetadata(id, name))
            createCallbacks[id] = create
            thumbnailCallbacks[id] = getThumbnail
        }
    }

    @Before
    fun setUp() {
        val captor = argumentCaptor<PluginListener<ClockProviderPlugin>>()
        registry = ClockRegistry(mockContext, mockPluginManager)
        verify(mockPluginManager).addPluginListener(captor.capture(),
            eq(ClockProviderPlugin::class.java))
        pluginListener = captor.value
    }

    @Test
    fun pluginRegistration_CorrectState() {
        val plugin1 = FakeClockPlugin()
        plugin1.addClock("clock_1", "clock 1", ::failFactory, ::failThumbnail)
        plugin1.addClock("clock_2", "clock 2", ::failFactory, ::failThumbnail)

        val plugin2 = FakeClockPlugin()
        plugin2.addClock("clock_3", "clock 3", ::failFactory, ::failThumbnail)
        plugin2.addClock("clock_4", "clock 4", ::failFactory, ::failThumbnail)

        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)
        val list = registry.getClocks()
        assertEquals(list, listOf(
            ClockMetadata("clock_1", "clock 1"),
            ClockMetadata("clock_2", "clock 2"),
            ClockMetadata("clock_3", "clock 3"),
            ClockMetadata("clock_4", "clock 4")
        ))
    }

    @Test
    fun clockIdConflict_ErrorWithoutCrash() {
        val plugin1 = FakeClockPlugin()
        plugin1.addClock("clock_1", "clock 1", { mockClock }, { mockThumbnail })
        plugin1.addClock("clock_2", "clock 2", { mockClock }, { mockThumbnail })

        val plugin2 = FakeClockPlugin()
        plugin2.addClock("clock_1", "clock 1", ::failFactory, ::failThumbnail)
        plugin2.addClock("clock_2", "clock 2", ::failFactory, ::failThumbnail)

        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)
        val list = registry.getClocks()
        assertEquals(list, listOf(
            ClockMetadata("clock_1", "clock 1"),
            ClockMetadata("clock_2", "clock 2")
        ))

        assertEquals(registry.createExampleClock("clock_1"), mockClock)
        assertEquals(registry.createExampleClock("clock_2"), mockClock)
        assertEquals(registry.getClockThumbnail("clock_1"), mockThumbnail)
        assertEquals(registry.getClockThumbnail("clock_2"), mockThumbnail)
    }
}
