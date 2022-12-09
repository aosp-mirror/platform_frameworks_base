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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Resources
import android.test.suitebuilder.annotation.SmallTest
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.Serializable
import java.io.StringWriter
import java.util.function.Consumer
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

/**
 * NOTE: This test is for the version of FeatureFlagManager in src-release, which should not allow
 * overriding, and should never return any value other than the one provided as the default.
 */
@SmallTest
class FeatureFlagsDebugTest : SysuiTestCase() {
    private lateinit var mFeatureFlagsDebug: FeatureFlagsDebug

    @Mock private lateinit var flagManager: FlagManager
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var secureSettings: SecureSettings
    @Mock private lateinit var systemProperties: SystemPropertiesHelper
    @Mock private lateinit var resources: Resources
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var commandRegistry: CommandRegistry
    @Mock private lateinit var barService: IStatusBarService
    @Mock private lateinit var pw: PrintWriter
    private val flagMap = mutableMapOf<Int, Flag<*>>()
    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var clearCacheAction: Consumer<Int>
    private val serverFlagReader = ServerFlagReaderFake()

    private val deviceConfig = DeviceConfigProxyFake()
    private val teamfoodableFlagA = UnreleasedFlag(500, true)
    private val teamfoodableFlagB = ReleasedFlag(501, true)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        flagMap.put(teamfoodableFlagA.id, teamfoodableFlagA)
        flagMap.put(teamfoodableFlagB.id, teamfoodableFlagB)
        mFeatureFlagsDebug = FeatureFlagsDebug(
            flagManager,
            mockContext,
            secureSettings,
            systemProperties,
            resources,
            dumpManager,
            deviceConfig,
            serverFlagReader,
            flagMap,
            commandRegistry,
            barService
        )
        verify(flagManager).onSettingsChangedAction = any()
        broadcastReceiver = withArgCaptor {
            verify(mockContext).registerReceiver(capture(), any(), nullable(), nullable(),
                any())
        }
        clearCacheAction = withArgCaptor {
            verify(flagManager).clearCacheAction = capture()
        }
        whenever(flagManager.idToSettingsKey(any())).thenAnswer { "key-${it.arguments[0]}" }
    }

    @Test
    fun readBooleanFlag() {
        // Remember that the TEAMFOOD flag is id#1 and has special behavior.
        whenever(flagManager.readFlagValue<Boolean>(eq(3), any())).thenReturn(true)
        whenever(flagManager.readFlagValue<Boolean>(eq(4), any())).thenReturn(false)

        assertThat(mFeatureFlagsDebug.isEnabled(ReleasedFlag(2))).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(UnreleasedFlag(3))).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(ReleasedFlag(4))).isFalse()
        assertThat(mFeatureFlagsDebug.isEnabled(UnreleasedFlag(5))).isFalse()
    }

    @Test
    fun teamFoodFlag_False() {
        whenever(flagManager.readFlagValue<Boolean>(eq(1), any())).thenReturn(false)
        assertThat(mFeatureFlagsDebug.isEnabled(teamfoodableFlagA)).isFalse()
        assertThat(mFeatureFlagsDebug.isEnabled(teamfoodableFlagB)).isTrue()

        // Regular boolean flags should still test the same.
        // Only our teamfoodableFlag should change.
        readBooleanFlag()
    }

    @Test
    fun teamFoodFlag_True() {
        whenever(flagManager.readFlagValue<Boolean>(eq(1), any())).thenReturn(true)
        assertThat(mFeatureFlagsDebug.isEnabled(teamfoodableFlagA)).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(teamfoodableFlagB)).isTrue()

        // Regular boolean flags should still test the same.
        // Only our teamfoodableFlag should change.
        readBooleanFlag()
    }

    @Test
    fun teamFoodFlag_Overridden() {
        whenever(flagManager.readFlagValue<Boolean>(eq(teamfoodableFlagA.id), any()))
                .thenReturn(true)
        whenever(flagManager.readFlagValue<Boolean>(eq(teamfoodableFlagB.id), any()))
                .thenReturn(false)
        whenever(flagManager.readFlagValue<Boolean>(eq(1), any())).thenReturn(true)
        assertThat(mFeatureFlagsDebug.isEnabled(teamfoodableFlagA)).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(teamfoodableFlagB)).isFalse()

        // Regular boolean flags should still test the same.
        // Only our teamfoodableFlag should change.
        readBooleanFlag()
    }

    @Test
    fun readResourceBooleanFlag() {
        whenever(resources.getBoolean(1001)).thenReturn(false)
        whenever(resources.getBoolean(1002)).thenReturn(true)
        whenever(resources.getBoolean(1003)).thenReturn(false)
        whenever(resources.getBoolean(1004)).thenAnswer { throw NameNotFoundException() }
        whenever(resources.getBoolean(1005)).thenAnswer { throw NameNotFoundException() }

        whenever(flagManager.readFlagValue<Boolean>(eq(3), any())).thenReturn(true)
        whenever(flagManager.readFlagValue<Boolean>(eq(5), any())).thenReturn(false)

        assertThat(mFeatureFlagsDebug.isEnabled(ResourceBooleanFlag(1, 1001))).isFalse()
        assertThat(mFeatureFlagsDebug.isEnabled(ResourceBooleanFlag(2, 1002))).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(ResourceBooleanFlag(3, 1003))).isTrue()

        Assert.assertThrows(NameNotFoundException::class.java) {
            mFeatureFlagsDebug.isEnabled(ResourceBooleanFlag(4, 1004))
        }
        // Test that resource is loaded (and validated) even when the setting is set.
        //  This prevents developers from not noticing when they reference an invalid resource.
        Assert.assertThrows(NameNotFoundException::class.java) {
            mFeatureFlagsDebug.isEnabled(ResourceBooleanFlag(5, 1005))
        }
    }

    @Test
    fun readSysPropBooleanFlag() {
        whenever(systemProperties.getBoolean(anyString(), anyBoolean())).thenAnswer {
            if ("b".equals(it.getArgument<String?>(0))) {
                return@thenAnswer true
            }
            return@thenAnswer it.getArgument(1)
        }

        assertThat(mFeatureFlagsDebug.isEnabled(SysPropBooleanFlag(1, "a"))).isFalse()
        assertThat(mFeatureFlagsDebug.isEnabled(SysPropBooleanFlag(2, "b"))).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(SysPropBooleanFlag(3, "c", true))).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(SysPropBooleanFlag(4, "d", false))).isFalse()
        assertThat(mFeatureFlagsDebug.isEnabled(SysPropBooleanFlag(5, "e"))).isFalse()
    }

    @Test
    fun readDeviceConfigBooleanFlag() {
        val namespace = "test_namespace"
        deviceConfig.setProperty(namespace, "a", "true", false)
        deviceConfig.setProperty(namespace, "b", "false", false)
        deviceConfig.setProperty(namespace, "c", null, false)

        assertThat(mFeatureFlagsDebug.isEnabled(DeviceConfigBooleanFlag(1, "a", namespace)))
            .isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(DeviceConfigBooleanFlag(2, "b", namespace)))
            .isFalse()
        assertThat(mFeatureFlagsDebug.isEnabled(DeviceConfigBooleanFlag(3, "c", namespace)))
            .isFalse()
    }

    @Test
    fun readStringFlag() {
        whenever(flagManager.readFlagValue<String>(eq(3), any())).thenReturn("foo")
        whenever(flagManager.readFlagValue<String>(eq(4), any())).thenReturn("bar")
        assertThat(mFeatureFlagsDebug.getString(StringFlag(1, "biz"))).isEqualTo("biz")
        assertThat(mFeatureFlagsDebug.getString(StringFlag(2, "baz"))).isEqualTo("baz")
        assertThat(mFeatureFlagsDebug.getString(StringFlag(3, "buz"))).isEqualTo("foo")
        assertThat(mFeatureFlagsDebug.getString(StringFlag(4, "buz"))).isEqualTo("bar")
    }

    @Test
    fun readResourceStringFlag() {
        whenever(resources.getString(1001)).thenReturn("")
        whenever(resources.getString(1002)).thenReturn("resource2")
        whenever(resources.getString(1003)).thenReturn("resource3")
        whenever(resources.getString(1004)).thenReturn(null)
        whenever(resources.getString(1005)).thenAnswer { throw NameNotFoundException() }
        whenever(resources.getString(1006)).thenAnswer { throw NameNotFoundException() }

        whenever(flagManager.readFlagValue<String>(eq(3), any())).thenReturn("override3")
        whenever(flagManager.readFlagValue<String>(eq(4), any())).thenReturn("override4")
        whenever(flagManager.readFlagValue<String>(eq(6), any())).thenReturn("override6")

        assertThat(mFeatureFlagsDebug.getString(ResourceStringFlag(1, 1001))).isEqualTo("")
        assertThat(mFeatureFlagsDebug.getString(ResourceStringFlag(2, 1002))).isEqualTo("resource2")
        assertThat(mFeatureFlagsDebug.getString(ResourceStringFlag(3, 1003))).isEqualTo("override3")

        Assert.assertThrows(NullPointerException::class.java) {
            mFeatureFlagsDebug.getString(ResourceStringFlag(4, 1004))
        }
        Assert.assertThrows(NameNotFoundException::class.java) {
            mFeatureFlagsDebug.getString(ResourceStringFlag(5, 1005))
        }
        // Test that resource is loaded (and validated) even when the setting is set.
        //  This prevents developers from not noticing when they reference an invalid resource.
        Assert.assertThrows(NameNotFoundException::class.java) {
            mFeatureFlagsDebug.getString(ResourceStringFlag(6, 1005))
        }
    }

    @Test
    fun broadcastReceiver_IgnoresInvalidData() {
        addFlag(UnreleasedFlag(1))
        addFlag(ResourceBooleanFlag(2, 1002))
        addFlag(StringFlag(3, "flag3"))
        addFlag(ResourceStringFlag(4, 1004))

        broadcastReceiver.onReceive(mockContext, null)
        broadcastReceiver.onReceive(mockContext, Intent())
        broadcastReceiver.onReceive(mockContext, Intent("invalid action"))
        broadcastReceiver.onReceive(mockContext, Intent(FlagManager.ACTION_SET_FLAG))
        setByBroadcast(0, false)     // unknown id does nothing
        setByBroadcast(1, "string")  // wrong type does nothing
        setByBroadcast(2, 123)       // wrong type does nothing
        setByBroadcast(3, false)     // wrong type does nothing
        setByBroadcast(4, 123)       // wrong type does nothing
        verifyNoMoreInteractions(flagManager, secureSettings)
    }

    @Test
    fun intentWithId_NoValueKeyClears() {
        addFlag(UnreleasedFlag(1))

        // trying to erase an id not in the map does nothing
        broadcastReceiver.onReceive(
            mockContext,
            Intent(FlagManager.ACTION_SET_FLAG).putExtra(FlagManager.EXTRA_ID, 0)
        )
        verifyNoMoreInteractions(flagManager, secureSettings)

        // valid id with no value puts empty string in the setting
        broadcastReceiver.onReceive(
            mockContext,
            Intent(FlagManager.ACTION_SET_FLAG).putExtra(FlagManager.EXTRA_ID, 1)
        )
        verifyPutData(1, "", numReads = 0)
    }

    @Test
    fun setBooleanFlag() {
        addFlag(UnreleasedFlag(1))
        addFlag(UnreleasedFlag(2))
        addFlag(ResourceBooleanFlag(3, 1003))
        addFlag(ResourceBooleanFlag(4, 1004))

        setByBroadcast(1, false)
        verifyPutData(1, "{\"type\":\"boolean\",\"value\":false}")

        setByBroadcast(2, true)
        verifyPutData(2, "{\"type\":\"boolean\",\"value\":true}")

        setByBroadcast(3, false)
        verifyPutData(3, "{\"type\":\"boolean\",\"value\":false}")

        setByBroadcast(4, true)
        verifyPutData(4, "{\"type\":\"boolean\",\"value\":true}")
    }

    @Test
    fun setStringFlag() {
        addFlag(StringFlag(1, "flag1"))
        addFlag(ResourceStringFlag(2, 1002))

        setByBroadcast(1, "override1")
        verifyPutData(1, "{\"type\":\"string\",\"value\":\"override1\"}")

        setByBroadcast(2, "override2")
        verifyPutData(2, "{\"type\":\"string\",\"value\":\"override2\"}")
    }

    @Test
    fun setFlag_ClearsCache() {
        val flag1 = addFlag(StringFlag(1, "flag1"))
        whenever(flagManager.readFlagValue<String>(eq(1), any())).thenReturn("original")

        // gets the flag & cache it
        assertThat(mFeatureFlagsDebug.getString(flag1)).isEqualTo("original")
        verify(flagManager).readFlagValue(eq(1), eq(StringFlagSerializer))

        // hit the cache
        assertThat(mFeatureFlagsDebug.getString(flag1)).isEqualTo("original")
        verifyNoMoreInteractions(flagManager)

        // set the flag
        setByBroadcast(1, "new")
        verifyPutData(1, "{\"type\":\"string\",\"value\":\"new\"}", numReads = 2)
        whenever(flagManager.readFlagValue<String>(eq(1), any())).thenReturn("new")

        assertThat(mFeatureFlagsDebug.getString(flag1)).isEqualTo("new")
        verify(flagManager, times(3)).readFlagValue(eq(1), eq(StringFlagSerializer))
    }

    @Test
    fun serverSide_Overrides_MakesFalse() {
        val flag = ReleasedFlag(100)

        serverFlagReader.setFlagValue(flag.id, false)

        assertThat(mFeatureFlagsDebug.isEnabled(flag)).isFalse()
    }

    @Test
    fun serverSide_Overrides_MakesTrue() {
        val flag = UnreleasedFlag(100)

        serverFlagReader.setFlagValue(flag.id, true)

        assertThat(mFeatureFlagsDebug.isEnabled(flag)).isTrue()
    }

    @Test
    fun statusBarCommand_IsRegistered() {
        verify(commandRegistry).registerCommand(anyString(), any())
    }

    @Test
    fun noOpCommand() {
        val cmd = captureCommand()

        cmd.execute(pw, ArrayList())
        verify(pw, atLeastOnce()).println()
        verify(flagManager).readFlagValue<Boolean>(eq(1), any())
        verifyZeroInteractions(secureSettings)
    }

    @Test
    fun readFlagCommand() {
        addFlag(UnreleasedFlag(1))
        val cmd = captureCommand()
        cmd.execute(pw, listOf("1"))
        verify(flagManager).readFlagValue<Boolean>(eq(1), any())
    }

    @Test
    fun setFlagCommand() {
        addFlag(UnreleasedFlag(1))
        val cmd = captureCommand()
        cmd.execute(pw, listOf("1", "on"))
        verifyPutData(1, "{\"type\":\"boolean\",\"value\":true}")
    }

    @Test
    fun toggleFlagCommand() {
        addFlag(ReleasedFlag(1))
        val cmd = captureCommand()
        cmd.execute(pw, listOf("1", "toggle"))
        verifyPutData(1, "{\"type\":\"boolean\",\"value\":false}", 2)
    }

    @Test
    fun eraseFlagCommand() {
        addFlag(ReleasedFlag(1))
        val cmd = captureCommand()
        cmd.execute(pw, listOf("1", "erase"))
        verify(secureSettings).putStringForUser(eq("key-1"), eq(""), anyInt())
    }

    @Test
    fun dumpFormat() {
        val flag1 = ReleasedFlag(1)
        val flag2 = ResourceBooleanFlag(2, 1002)
        val flag3 = UnreleasedFlag(3)
        val flag4 = StringFlag(4, "")
        val flag5 = StringFlag(5, "flag5default")
        val flag6 = ResourceStringFlag(6, 1006)
        val flag7 = ResourceStringFlag(7, 1007)

        whenever(resources.getBoolean(1002)).thenReturn(true)
        whenever(resources.getString(1006)).thenReturn("resource1006")
        whenever(resources.getString(1007)).thenReturn("resource1007")
        whenever(flagManager.readFlagValue(eq(7), eq(StringFlagSerializer)))
            .thenReturn("override7")

        // WHEN the flags have been accessed
        assertThat(mFeatureFlagsDebug.isEnabled(flag1)).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(flag2)).isTrue()
        assertThat(mFeatureFlagsDebug.isEnabled(flag3)).isFalse()
        assertThat(mFeatureFlagsDebug.getString(flag4)).isEmpty()
        assertThat(mFeatureFlagsDebug.getString(flag5)).isEqualTo("flag5default")
        assertThat(mFeatureFlagsDebug.getString(flag6)).isEqualTo("resource1006")
        assertThat(mFeatureFlagsDebug.getString(flag7)).isEqualTo("override7")

        // THEN the dump contains the flags and the default values
        val dump = dumpToString()
        assertThat(dump).contains(" sysui_flag_1: true\n")
        assertThat(dump).contains(" sysui_flag_2: true\n")
        assertThat(dump).contains(" sysui_flag_3: false\n")
        assertThat(dump).contains(" sysui_flag_4: [length=0] \"\"\n")
        assertThat(dump).contains(" sysui_flag_5: [length=12] \"flag5default\"\n")
        assertThat(dump).contains(" sysui_flag_6: [length=12] \"resource1006\"\n")
        assertThat(dump).contains(" sysui_flag_7: [length=9] \"override7\"\n")
    }

    private fun verifyPutData(id: Int, data: String, numReads: Int = 1) {
        inOrder(flagManager, secureSettings).apply {
            verify(flagManager, times(numReads)).readFlagValue(eq(id), any<FlagSerializer<*>>())
            verify(flagManager).idToSettingsKey(eq(id))
            verify(secureSettings).putStringForUser(eq("key-$id"), eq(data), anyInt())
            verify(flagManager).dispatchListenersAndMaybeRestart(eq(id), any())
        }.verifyNoMoreInteractions()
        verifyNoMoreInteractions(flagManager, secureSettings)
    }

    private fun setByBroadcast(id: Int, value: Serializable?) {
        val intent = Intent(FlagManager.ACTION_SET_FLAG)
        intent.putExtra(FlagManager.EXTRA_ID, id)
        intent.putExtra(FlagManager.EXTRA_VALUE, value)
        broadcastReceiver.onReceive(mockContext, intent)
    }

    private fun <F : Flag<*>> addFlag(flag: F): F {
        val old = flagMap.put(flag.id, flag)
        check(old == null) { "Flag ${flag.id} already registered" }
        return flag
    }

    private fun captureCommand(): Command {
        val captor = argumentCaptor<Function0<Command>>()
        verify(commandRegistry).registerCommand(anyString(), capture(captor))

        return captor.value.invoke()
    }

    private fun dumpToString(): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        mFeatureFlagsDebug.dump(pw, emptyArray<String>())
        pw.flush()
        return sw.toString()
    }
}
