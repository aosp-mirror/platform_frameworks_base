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
package com.android.systemui.shared.clocks

import android.content.ContentResolver
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.PluginListener
import com.android.systemui.plugins.PluginManager
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import junit.framework.Assert.assertEquals
import junit.framework.Assert.fail
import org.json.JSONException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ClockRegistryTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockPluginManager: PluginManager
    @Mock private lateinit var mockClock: ClockController
    @Mock private lateinit var mockDefaultClock: ClockController
    @Mock private lateinit var mockThumbnail: Drawable
    @Mock private lateinit var mockHandler: Handler
    @Mock private lateinit var mockContentResolver: ContentResolver
    private lateinit var fakeDefaultProvider: FakeClockPlugin
    private lateinit var pluginListener: PluginListener<ClockProviderPlugin>
    private lateinit var registry: ClockRegistry

    private var settingValue: String = ""

    companion object {
        private fun failFactory(): ClockController {
            fail("Unexpected call to createClock")
            return null!!
        }

        private fun failThumbnail(): Drawable? {
            fail("Unexpected call to getThumbnail")
            return null
        }
    }

    private class FakeClockPlugin : ClockProviderPlugin {
        private val metadata = mutableListOf<ClockMetadata>()
        private val createCallbacks = mutableMapOf<ClockId, () -> ClockController>()
        private val thumbnailCallbacks = mutableMapOf<ClockId, () -> Drawable?>()

        override fun getClocks() = metadata
        override fun createClock(id: ClockId): ClockController = createCallbacks[id]!!()
        override fun getClockThumbnail(id: ClockId): Drawable? = thumbnailCallbacks[id]!!()

        fun addClock(
            id: ClockId,
            name: String,
            create: () -> ClockController = ::failFactory,
            getThumbnail: () -> Drawable? = ::failThumbnail
        ): FakeClockPlugin {
            metadata.add(ClockMetadata(id, name))
            createCallbacks[id] = create
            thumbnailCallbacks[id] = getThumbnail
            return this
        }
    }

    @Before
    fun setUp() {
        fakeDefaultProvider = FakeClockPlugin()
            .addClock(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME, { mockDefaultClock }, { mockThumbnail })
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)

        val captor = argumentCaptor<PluginListener<ClockProviderPlugin>>()
        registry = object : ClockRegistry(
            mockContext,
            mockPluginManager,
            mockHandler,
            isEnabled = true,
            userHandle = UserHandle.USER_ALL,
            defaultClockProvider = fakeDefaultProvider
        ) {
            override var currentClockId: ClockId
                get() = settingValue
                set(value) { settingValue = value }
        }

        verify(mockPluginManager)
            .addPluginListener(captor.capture(), eq(ClockProviderPlugin::class.java), eq(true))
        pluginListener = captor.value
    }

    @Test
    fun pluginRegistration_CorrectState() {
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        val plugin2 = FakeClockPlugin()
            .addClock("clock_3", "clock 3")
            .addClock("clock_4", "clock 4")

        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)
        val list = registry.getClocks()
        assertEquals(
            list,
            listOf(
                ClockMetadata(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME),
                ClockMetadata("clock_1", "clock 1"),
                ClockMetadata("clock_2", "clock 2"),
                ClockMetadata("clock_3", "clock 3"),
                ClockMetadata("clock_4", "clock 4")
            )
        )
    }

    @Test
    fun noPlugins_createDefaultClock() {
        val clock = registry.createCurrentClock()
        assertEquals(clock, mockDefaultClock)
    }

    @Test
    fun clockIdConflict_ErrorWithoutCrash() {
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1", { mockClock }, { mockThumbnail })
            .addClock("clock_2", "clock 2", { mockClock }, { mockThumbnail })

        val plugin2 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)
        val list = registry.getClocks()
        assertEquals(
            list,
            listOf(
                ClockMetadata(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME),
                ClockMetadata("clock_1", "clock 1"),
                ClockMetadata("clock_2", "clock 2")
            )
        )

        assertEquals(registry.createExampleClock("clock_1"), mockClock)
        assertEquals(registry.createExampleClock("clock_2"), mockClock)
        assertEquals(registry.getClockThumbnail("clock_1"), mockThumbnail)
        assertEquals(registry.getClockThumbnail("clock_2"), mockThumbnail)
    }

    @Test
    fun createCurrentClock_pluginConnected() {
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        settingValue = "clock_3"
        val plugin2 = FakeClockPlugin()
            .addClock("clock_3", "clock 3", { mockClock })
            .addClock("clock_4", "clock 4")

        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)

        val clock = registry.createCurrentClock()
        assertEquals(clock, mockClock)
    }

    @Test
    fun createDefaultClock_pluginDisconnected() {
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        settingValue = "clock_3"
        val plugin2 = FakeClockPlugin()
            .addClock("clock_3", "clock 3")
            .addClock("clock_4", "clock 4")

        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)
        pluginListener.onPluginDisconnected(plugin2)

        val clock = registry.createCurrentClock()
        assertEquals(clock, mockDefaultClock)
    }

    @Test
    fun pluginRemoved_clockChanged() {
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        settingValue = "clock_3"
        val plugin2 = FakeClockPlugin()
            .addClock("clock_3", "clock 3", { mockClock })
            .addClock("clock_4", "clock 4")

        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)

        var changeCallCount = 0
        registry.registerClockChangeListener { changeCallCount++ }

        pluginListener.onPluginDisconnected(plugin1)
        assertEquals(0, changeCallCount)

        pluginListener.onPluginDisconnected(plugin2)
        assertEquals(1, changeCallCount)
    }

    @Test
    fun jsonDeserialization_gotExpectedObject() {
        val expected = ClockRegistry.ClockSetting("ID", 500)
        val actual = ClockRegistry.ClockSetting.deserialize("""{
            "clockId":"ID",
            "_applied_timestamp":500
        }""")
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_noTimestamp_gotExpectedObject() {
        val expected = ClockRegistry.ClockSetting("ID", null)
        val actual = ClockRegistry.ClockSetting.deserialize("{\"clockId\":\"ID\"}")
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_nullTimestamp_gotExpectedObject() {
        val expected = ClockRegistry.ClockSetting("ID", null)
        val actual = ClockRegistry.ClockSetting.deserialize("""{
            "clockId":"ID",
            "_applied_timestamp":null
        }""")
        assertEquals(expected, actual)
    }

    @Test(expected = JSONException::class)
    fun jsonDeserialization_noId_threwException() {
        val expected = ClockRegistry.ClockSetting("ID", 500)
        val actual = ClockRegistry.ClockSetting.deserialize("{\"_applied_timestamp\":500}")
        assertEquals(expected, actual)
    }

    @Test
    fun jsonSerialization_gotExpectedString() {
        val expected = "{\"clockId\":\"ID\",\"_applied_timestamp\":500}"
        val actual = ClockRegistry.ClockSetting.serialize( ClockRegistry.ClockSetting("ID", 500))
        assertEquals(expected, actual)
    }

    @Test
    fun jsonSerialization_noTimestamp_gotExpectedString() {
        val expected = "{\"clockId\":\"ID\"}"
        val actual = ClockRegistry.ClockSetting.serialize( ClockRegistry.ClockSetting("ID", null))
        assertEquals(expected, actual)
    }
}
