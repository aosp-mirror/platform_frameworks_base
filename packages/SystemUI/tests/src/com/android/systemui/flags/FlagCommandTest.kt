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

    @Mock private lateinit var featureFlags: FeatureFlagsDebug
    @Mock private lateinit var pw: PrintWriter
    private val flagMap = mutableMapOf<Int, Flag<*>>()
    private val flagA = UnreleasedFlag(500)
    private val flagB = ReleasedFlag(501)

    private lateinit var cmd: FlagCommand

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        whenever(featureFlags.isEnabled(any(UnreleasedFlag::class.java))).thenReturn(false)
        whenever(featureFlags.isEnabled(any(ReleasedFlag::class.java))).thenReturn(true)
        flagMap.put(flagA.id, flagA)
        flagMap.put(flagB.id, flagB)

        cmd = FlagCommand(featureFlags, flagMap)
    }

    @Test
    fun noOpCommand() {
        cmd.execute(pw, ArrayList())
        Mockito.verify(pw, Mockito.atLeastOnce()).println()
        Mockito.verify(featureFlags).isEnabled(flagA)
        Mockito.verify(featureFlags).isEnabled(flagB)
    }

    @Test
    fun readFlagCommand() {
        cmd.execute(pw, listOf(flagA.id.toString()))
        Mockito.verify(featureFlags).isEnabled(flagA)
    }

    @Test
    fun setFlagCommand() {
        cmd.execute(pw, listOf(flagB.id.toString(), "on"))
        Mockito.verify(featureFlags).setBooleanFlagInternal(flagB, true)
    }

    @Test
    fun toggleFlagCommand() {
        cmd.execute(pw, listOf(flagB.id.toString(), "toggle"))
        Mockito.verify(featureFlags).setBooleanFlagInternal(flagB, false)
    }

    @Test
    fun eraseFlagCommand() {
        cmd.execute(pw, listOf(flagA.id.toString(), "erase"))
        Mockito.verify(featureFlags).eraseFlag(flagA)
    }
}
