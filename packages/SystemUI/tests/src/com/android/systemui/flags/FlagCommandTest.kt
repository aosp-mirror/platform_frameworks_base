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

package com.android.systemui.flags

import android.test.suitebuilder.annotation.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import java.io.PrintWriter
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
class FlagCommandTest : SysuiTestCase() {

    @Mock private lateinit var featureFlags: FeatureFlagsClassicDebug
    @Mock private lateinit var pw: PrintWriter
    private val flagMap = mutableMapOf<String, Flag<*>>()
    private val flagA = UnreleasedFlag("500", "test")
    private val flagB = ReleasedFlag("501", "test")
    private val stringFlag = StringFlag("502", "test", "abracadabra")
    private val intFlag = IntFlag("503", "test", 12)

    private lateinit var cmd: FlagCommand

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        whenever(featureFlags.isEnabled(any(UnreleasedFlag::class.java))).thenReturn(false)
        whenever(featureFlags.isEnabled(any(ReleasedFlag::class.java))).thenReturn(true)
        whenever(featureFlags.getString(any(StringFlag::class.java))).thenAnswer { invocation ->
            (invocation.getArgument(0) as StringFlag).default
        }
        whenever(featureFlags.getInt(any(IntFlag::class.java))).thenAnswer { invocation ->
            (invocation.getArgument(0) as IntFlag).default
        }

        flagMap.put(flagA.name, flagA)
        flagMap.put(flagB.name, flagB)
        flagMap.put(stringFlag.name, stringFlag)
        flagMap.put(intFlag.name, intFlag)

        cmd = FlagCommand(featureFlags, flagMap)
    }

    @Test
    fun readBooleanFlagCommand() {
        cmd.execute(pw, listOf(flagA.name))
        Mockito.verify(featureFlags).isEnabled(flagA)
    }

    @Test
    fun readStringFlagCommand() {
        cmd.execute(pw, listOf(stringFlag.name))
        Mockito.verify(featureFlags).getString(stringFlag)
    }

    @Test
    fun readIntFlag() {
        cmd.execute(pw, listOf(intFlag.name))
        Mockito.verify(featureFlags).getInt(intFlag)
    }

    @Test
    fun setBooleanFlagCommand() {
        cmd.execute(pw, listOf(flagB.name, "on"))
        Mockito.verify(featureFlags).setBooleanFlagInternal(flagB, true)
    }

    @Test
    fun setStringFlagCommand() {
        cmd.execute(pw, listOf(stringFlag.name, "set", "foobar"))
        Mockito.verify(featureFlags).setStringFlagInternal(stringFlag, "foobar")
    }

    @Test
    fun setIntFlag() {
        cmd.execute(pw, listOf(intFlag.name, "put", "123"))
        Mockito.verify(featureFlags).setIntFlagInternal(intFlag, 123)
    }

    @Test
    fun toggleBooleanFlagCommand() {
        cmd.execute(pw, listOf(flagB.name, "toggle"))
        Mockito.verify(featureFlags).setBooleanFlagInternal(flagB, false)
    }

    @Test
    fun eraseFlagCommand() {
        cmd.execute(pw, listOf(flagA.name, "erase"))
        Mockito.verify(featureFlags).eraseFlag(flagA)
    }
}
