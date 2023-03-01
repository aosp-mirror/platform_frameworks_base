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
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.ClockSettings
import com.android.systemui.plugins.PluginListener
import com.android.systemui.plugins.PluginManager
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import junit.framework.Assert.assertEquals
import junit.framework.Assert.fail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var scope: TestScope

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockPluginManager: PluginManager
    @Mock private lateinit var mockClock: ClockController
    @Mock private lateinit var mockDefaultClock: ClockController
    @Mock private lateinit var mockThumbnail: Drawable
    @Mock private lateinit var mockContentResolver: ContentResolver
    private lateinit var fakeDefaultProvider: FakeClockPlugin
    private lateinit var pluginListener: PluginListener<ClockProviderPlugin>
    private lateinit var registry: ClockRegistry

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
        override fun createClock(settings: ClockSettings): ClockController =
            createCallbacks[settings.clockId!!]!!()
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
        dispatcher = StandardTestDispatcher()
        scope = TestScope(dispatcher)

        fakeDefaultProvider = FakeClockPlugin()
            .addClock(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME, { mockDefaultClock }, { mockThumbnail })
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)

        val captor = argumentCaptor<PluginListener<ClockProviderPlugin>>()
        registry = object : ClockRegistry(
            mockContext,
            mockPluginManager,
            scope = scope.backgroundScope,
            mainDispatcher = dispatcher,
            bgDispatcher = dispatcher,
            isEnabled = true,
            handleAllUsers = true,
            defaultClockProvider = fakeDefaultProvider,
        ) {
            override fun querySettings() { }
            override fun applySettings(value: ClockSettings?) {
                settings = value
            }
            // Unit Test does not validate threading
            override fun assertMainThread() {}
            override fun assertNotMainThread() {}
        }
        registry.registerListeners()

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

        val plugin2 = FakeClockPlugin()
            .addClock("clock_3", "clock 3", { mockClock })
            .addClock("clock_4", "clock 4")

        registry.applySettings(ClockSettings("clock_3", null))
        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)

        val clock = registry.createCurrentClock()
        assertEquals(mockClock, clock)
    }

    @Test
    fun createDefaultClock_pluginDisconnected() {
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        val plugin2 = FakeClockPlugin()
            .addClock("clock_3", "clock 3")
            .addClock("clock_4", "clock 4")

        registry.applySettings(ClockSettings("clock_3", null))
        pluginListener.onPluginConnected(plugin1, mockContext)
        pluginListener.onPluginConnected(plugin2, mockContext)
        pluginListener.onPluginDisconnected(plugin2)

        val clock = registry.createCurrentClock()
        assertEquals(clock, mockDefaultClock)
    }

    @Test
    fun pluginRemoved_clockAndListChanged() {
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        val plugin2 = FakeClockPlugin()
            .addClock("clock_3", "clock 3", { mockClock })
            .addClock("clock_4", "clock 4")


        var changeCallCount = 0
        var listChangeCallCount = 0
        registry.registerClockChangeListener(object : ClockRegistry.ClockChangeListener {
            override fun onCurrentClockChanged() { changeCallCount++ }
            override fun onAvailableClocksChanged() { listChangeCallCount++ }
        })

        registry.applySettings(ClockSettings("clock_3", null))
        assertEquals(0, changeCallCount)
        assertEquals(0, listChangeCallCount)

        pluginListener.onPluginConnected(plugin1, mockContext)
        assertEquals(0, changeCallCount)
        assertEquals(1, listChangeCallCount)

        pluginListener.onPluginConnected(plugin2, mockContext)
        assertEquals(1, changeCallCount)
        assertEquals(2, listChangeCallCount)

        pluginListener.onPluginDisconnected(plugin1)
        assertEquals(1, changeCallCount)
        assertEquals(3, listChangeCallCount)

        pluginListener.onPluginDisconnected(plugin2)
        assertEquals(2, changeCallCount)
        assertEquals(4, listChangeCallCount)
    }


    @Test
    fun jsonDeserialization_gotExpectedObject() {
        val expected = ClockSettings("ID", null).apply {
            metadata.put("appliedTimestamp", 500)
        }
        val actual = ClockSettings.deserialize("""{
            "clockId":"ID",
            "metadata": {
                "appliedTimestamp":500
            }
        }""")
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_noTimestamp_gotExpectedObject() {
        val expected = ClockSettings("ID", null)
        val actual = ClockSettings.deserialize("{\"clockId\":\"ID\"}")
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_nullTimestamp_gotExpectedObject() {
        val expected = ClockSettings("ID", null)
        val actual = ClockSettings.deserialize("""{
            "clockId":"ID",
            "metadata":null
        }""")
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_noId_deserializedEmpty() {
        val expected = ClockSettings(null, null).apply {
            metadata.put("appliedTimestamp", 500)
        }
        val actual = ClockSettings.deserialize("{\"metadata\":{\"appliedTimestamp\":500}}")
        assertEquals(expected, actual)
    }

    @Test
    fun jsonSerialization_gotExpectedString() {
        val expected = "{\"clockId\":\"ID\",\"metadata\":{\"appliedTimestamp\":500}}"
        val actual = ClockSettings.serialize(ClockSettings("ID", null).apply {
            metadata.put("appliedTimestamp", 500)
        })
        assertEquals(expected, actual)
    }

    @Test
    fun jsonSerialization_noTimestamp_gotExpectedString() {
        val expected = "{\"clockId\":\"ID\",\"metadata\":{}}"
        val actual = ClockSettings.serialize(ClockSettings("ID", null))
        assertEquals(expected, actual)
    }
}
