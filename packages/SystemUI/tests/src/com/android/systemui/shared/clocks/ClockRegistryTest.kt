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
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags.TRANSIT_CLOCK
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.ClockSettings
import com.android.systemui.plugins.PluginListener
import com.android.systemui.plugins.PluginLifecycleManager
import com.android.systemui.plugins.PluginManager
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import junit.framework.Assert.assertEquals
import junit.framework.Assert.fail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ClockRegistryTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var scope: TestScope

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockPluginManager: PluginManager
    @Mock private lateinit var mockClock: ClockController
    @Mock private lateinit var mockDefaultClock: ClockController
    @Mock private lateinit var mockThumbnail: Drawable
    @Mock private lateinit var mockContentResolver: ContentResolver
    @Mock private lateinit var mockPluginLifecycle: PluginLifecycleManager<ClockProviderPlugin>
    private lateinit var fakeDefaultProvider: FakeClockPlugin
    private lateinit var pluginListener: PluginListener<ClockProviderPlugin>
    private lateinit var registry: ClockRegistry
    private val featureFlags = FakeFeatureFlags()

    companion object {
        private fun failFactory(clockId: ClockId): ClockController {
            fail("Unexpected call to createClock: $clockId")
            return null!!
        }

        private fun failThumbnail(clockId: ClockId): Drawable? {
            fail("Unexpected call to getThumbnail: $clockId")
            return null
        }
    }

    private class FakeClockPlugin : ClockProviderPlugin {
        private val metadata = mutableListOf<ClockMetadata>()
        private val createCallbacks = mutableMapOf<ClockId, (ClockId) -> ClockController>()
        private val thumbnailCallbacks = mutableMapOf<ClockId, (ClockId) -> Drawable?>()

        override fun getClocks() = metadata
        override fun createClock(settings: ClockSettings): ClockController =
            createCallbacks[settings.clockId!!]!!(settings.clockId!!)
        override fun getClockThumbnail(id: ClockId): Drawable? = thumbnailCallbacks[id]!!(id)

        fun addClock(
            id: ClockId,
            name: String,
            create: (ClockId) -> ClockController = ::failFactory,
            getThumbnail: (ClockId) -> Drawable? = ::failThumbnail
        ): FakeClockPlugin {
            metadata.add(ClockMetadata(id, name))
            createCallbacks[id] = create
            thumbnailCallbacks[id] = getThumbnail
            return this
        }
    }

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
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
            keepAllLoaded = false,
            subTag = "Test",
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

        pluginListener.onPluginLoaded(plugin1, mockContext, mockPluginLifecycle)
        pluginListener.onPluginLoaded(plugin2, mockContext, mockPluginLifecycle)
        val list = registry.getClocks()
        assertEquals(
            list.toSet(),
            setOf(
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
    fun clockIdConflict_ErrorWithoutCrash_unloadDuplicate() {
        val mockPluginLifecycle1 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1", { mockClock }, { mockThumbnail })
            .addClock("clock_2", "clock 2", { mockClock }, { mockThumbnail })

        val mockPluginLifecycle2 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        val plugin2 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        pluginListener.onPluginLoaded(plugin1, mockContext, mockPluginLifecycle1)
        pluginListener.onPluginLoaded(plugin2, mockContext, mockPluginLifecycle2)
        val list = registry.getClocks()
        assertEquals(
            list.toSet(),
            setOf(
                ClockMetadata(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME),
                ClockMetadata("clock_1", "clock 1"),
                ClockMetadata("clock_2", "clock 2")
            )
        )

        assertEquals(registry.createExampleClock("clock_1"), mockClock)
        assertEquals(registry.createExampleClock("clock_2"), mockClock)
        assertEquals(registry.getClockThumbnail("clock_1"), mockThumbnail)
        assertEquals(registry.getClockThumbnail("clock_2"), mockThumbnail)
        verify(mockPluginLifecycle1, never()).unloadPlugin()
        verify(mockPluginLifecycle2, times(2)).unloadPlugin()
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
        pluginListener.onPluginLoaded(plugin1, mockContext, mockPluginLifecycle)
        pluginListener.onPluginLoaded(plugin2, mockContext, mockPluginLifecycle)

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
        pluginListener.onPluginLoaded(plugin1, mockContext, mockPluginLifecycle)
        pluginListener.onPluginLoaded(plugin2, mockContext, mockPluginLifecycle)
        pluginListener.onPluginUnloaded(plugin2, mockPluginLifecycle)

        val clock = registry.createCurrentClock()
        assertEquals(clock, mockDefaultClock)
    }

    @Test
    fun pluginRemoved_clockAndListChanged() {
        val mockPluginLifecycle1 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        val plugin1 = FakeClockPlugin()
            .addClock("clock_1", "clock 1")
            .addClock("clock_2", "clock 2")

        val mockPluginLifecycle2 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
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
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(0, listChangeCallCount)

        pluginListener.onPluginLoaded(plugin1, mockContext, mockPluginLifecycle1)
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(1, listChangeCallCount)

        pluginListener.onPluginLoaded(plugin2, mockContext, mockPluginLifecycle2)
        scheduler.runCurrent()
        assertEquals(2, changeCallCount)
        assertEquals(2, listChangeCallCount)

        pluginListener.onPluginUnloaded(plugin1, mockPluginLifecycle1)
        scheduler.runCurrent()
        assertEquals(2, changeCallCount)
        assertEquals(2, listChangeCallCount)

        pluginListener.onPluginUnloaded(plugin2, mockPluginLifecycle2)
        scheduler.runCurrent()
        assertEquals(3, changeCallCount)
        assertEquals(2, listChangeCallCount)

        pluginListener.onPluginDetached(mockPluginLifecycle1)
        scheduler.runCurrent()
        assertEquals(3, changeCallCount)
        assertEquals(3, listChangeCallCount)

        pluginListener.onPluginDetached(mockPluginLifecycle2)
        scheduler.runCurrent()
        assertEquals(3, changeCallCount)
        assertEquals(4, listChangeCallCount)
    }

    @Test
    fun unknownPluginAttached_clockAndListUnchanged_loadRequested() {
        val mockPluginLifecycle = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        whenever(mockPluginLifecycle.getPackage()).thenReturn("some.other.package")

        var changeCallCount = 0
        var listChangeCallCount = 0
        registry.registerClockChangeListener(object : ClockRegistry.ClockChangeListener {
            override fun onCurrentClockChanged() { changeCallCount++ }
            override fun onAvailableClocksChanged() { listChangeCallCount++ }
        })

        assertEquals(true, pluginListener.onPluginAttached(mockPluginLifecycle))
        scheduler.runCurrent()
        assertEquals(0, changeCallCount)
        assertEquals(0, listChangeCallCount)
    }

    @Test
    fun knownPluginAttached_clockAndListChanged_notLoaded() {
        val mockPluginLifecycle1 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        whenever(mockPluginLifecycle1.getPackage()).thenReturn("com.android.systemui.falcon.one")
        val mockPluginLifecycle2 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        whenever(mockPluginLifecycle2.getPackage()).thenReturn("com.android.systemui.falcon.two")

        var changeCallCount = 0
        var listChangeCallCount = 0
        registry.registerClockChangeListener(object : ClockRegistry.ClockChangeListener {
            override fun onCurrentClockChanged() { changeCallCount++ }
            override fun onAvailableClocksChanged() { listChangeCallCount++ }
        })

        registry.applySettings(ClockSettings("DIGITAL_CLOCK_CALLIGRAPHY", null))
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(0, listChangeCallCount)

        assertEquals(false, pluginListener.onPluginAttached(mockPluginLifecycle1))
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(1, listChangeCallCount)

        assertEquals(false, pluginListener.onPluginAttached(mockPluginLifecycle2))
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(2, listChangeCallCount)
    }

    @Test
    fun pluginAddRemove_concurrentModification() {
        val mockPluginLifecycle1 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        val mockPluginLifecycle2 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        val mockPluginLifecycle3 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        val mockPluginLifecycle4 = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        val plugin1 = FakeClockPlugin().addClock("clock_1", "clock 1")
        val plugin2 = FakeClockPlugin().addClock("clock_2", "clock 2")
        val plugin3 = FakeClockPlugin().addClock("clock_3", "clock 3")
        val plugin4 = FakeClockPlugin().addClock("clock_4", "clock 4")
        whenever(mockPluginLifecycle1.isLoaded).thenReturn(true)
        whenever(mockPluginLifecycle2.isLoaded).thenReturn(true)
        whenever(mockPluginLifecycle3.isLoaded).thenReturn(true)
        whenever(mockPluginLifecycle4.isLoaded).thenReturn(true)

        // Set the current clock to the final clock to load
        registry.applySettings(ClockSettings("clock_4", null))
        scheduler.runCurrent()

        // When ClockRegistry attempts to unload a plugin, we at that point decide to load and
        // unload other plugins. This causes ClockRegistry to modify the list of available clock
        // plugins while it is being iterated over. In production this happens as a result of a
        // thread race, instead of synchronously like it does here.
        whenever(mockPluginLifecycle2.unloadPlugin()).then {
            pluginListener.onPluginDetached(mockPluginLifecycle1)
            pluginListener.onPluginLoaded(plugin4, mockContext, mockPluginLifecycle4)
        }

        // Load initial plugins
        pluginListener.onPluginLoaded(plugin1, mockContext, mockPluginLifecycle1)
        pluginListener.onPluginLoaded(plugin2, mockContext, mockPluginLifecycle2)
        pluginListener.onPluginLoaded(plugin3, mockContext, mockPluginLifecycle3)

        // Repeatedly verify the loaded providers to get final state
        registry.verifyLoadedProviders()
        scheduler.runCurrent()
        registry.verifyLoadedProviders()
        scheduler.runCurrent()

        // Verify all plugins were correctly loaded into the registry
        assertEquals(registry.getClocks().toSet(), setOf(
            ClockMetadata("DEFAULT", "Default Clock"),
            ClockMetadata("clock_2", "clock 2"),
            ClockMetadata("clock_3", "clock 3"),
            ClockMetadata("clock_4", "clock 4")
        ))
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

    @Test
    fun testTransitClockEnabled_hasTransitClock() {
        testTransitClockFlag(true)
    }

    @Test
    fun testTransitClockDisabled_noTransitClock() {
        testTransitClockFlag(false)
    }

    private fun testTransitClockFlag(flag: Boolean) {
        featureFlags.set(TRANSIT_CLOCK, flag)
        registry.isTransitClockEnabled = featureFlags.isEnabled(TRANSIT_CLOCK)
        val mockPluginLifecycle = mock<PluginLifecycleManager<ClockProviderPlugin>>()
        val plugin = FakeClockPlugin()
                .addClock("clock_1", "clock 1")
                .addClock("DIGITAL_CLOCK_METRO", "metro clock")
        pluginListener.onPluginLoaded(plugin, mockContext, mockPluginLifecycle)

        val list = registry.getClocks()
        if (flag) {
            assertEquals(
                    setOf(
                            ClockMetadata(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME),
                            ClockMetadata("clock_1", "clock 1"),
                            ClockMetadata("DIGITAL_CLOCK_METRO", "metro clock")
                    ),
                    list.toSet()
            )
        } else {
            assertEquals(
                    setOf(
                            ClockMetadata(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME),
                            ClockMetadata("clock_1", "clock 1")
                    ),
                    list.toSet()
            )
        }
    }
}
