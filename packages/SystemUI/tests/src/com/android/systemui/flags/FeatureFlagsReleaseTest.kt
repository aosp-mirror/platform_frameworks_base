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

import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Resources
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import java.io.PrintWriter
import java.io.StringWriter
import org.mockito.Mockito.`when` as whenever

/**
 * NOTE: This test is for the version of FeatureFlagManager in src-release, which should not allow
 * overriding, and should never return any value other than the one provided as the default.
 */
@SmallTest
class FeatureFlagsReleaseTest : SysuiTestCase() {
    private lateinit var mFeatureFlagsRelease: FeatureFlagsRelease

    @Mock private lateinit var mResources: Resources
    @Mock private lateinit var mDumpManager: DumpManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mFeatureFlagsRelease = FeatureFlagsRelease(mResources, mDumpManager)
    }

    @After
    fun onFinished() {
        // The dump manager should be registered with even for the release version, but that's it.
        verify(mDumpManager).registerDumpable(any(), any())
        verifyNoMoreInteractions(mDumpManager)
    }

    @Test
    fun testBooleanResourceFlag() {
        val flagId = 213
        val flagResourceId = 3
        val flag = ResourceBooleanFlag(flagId, flagResourceId)
        whenever(mResources.getBoolean(flagResourceId)).thenReturn(true)
        assertThat(mFeatureFlagsRelease.isEnabled(flag)).isTrue()
    }

    @Test
    fun testReadResourceStringFlag() {
        whenever(mResources.getString(1001)).thenReturn("")
        whenever(mResources.getString(1002)).thenReturn("res2")
        whenever(mResources.getString(1003)).thenReturn(null)
        whenever(mResources.getString(1004)).thenAnswer { throw NameNotFoundException() }

        assertThat(mFeatureFlagsRelease.getString(ResourceStringFlag(1, 1001))).isEqualTo("")
        assertThat(mFeatureFlagsRelease.getString(ResourceStringFlag(2, 1002))).isEqualTo("res2")

        assertThrows(NullPointerException::class.java) {
            mFeatureFlagsRelease.getString(ResourceStringFlag(3, 1003))
        }
        assertThrows(NameNotFoundException::class.java) {
            mFeatureFlagsRelease.getString(ResourceStringFlag(4, 1004))
        }
    }

    @Test
    fun testDump() {
        val flag1 = BooleanFlag(1, true)
        val flag2 = ResourceBooleanFlag(2, 1002)
        val flag3 = BooleanFlag(3, false)
        val flag4 = StringFlag(4, "")
        val flag5 = StringFlag(5, "flag5default")
        val flag6 = ResourceStringFlag(6, 1006)

        whenever(mResources.getBoolean(1002)).thenReturn(true)
        whenever(mResources.getString(1006)).thenReturn("resource1006")
        whenever(mResources.getString(1007)).thenReturn("resource1007")

        // WHEN the flags have been accessed
        assertThat(mFeatureFlagsRelease.isEnabled(flag1)).isTrue()
        assertThat(mFeatureFlagsRelease.isEnabled(flag2)).isTrue()
        assertThat(mFeatureFlagsRelease.isEnabled(flag3)).isFalse()
        assertThat(mFeatureFlagsRelease.getString(flag4)).isEmpty()
        assertThat(mFeatureFlagsRelease.getString(flag5)).isEqualTo("flag5default")
        assertThat(mFeatureFlagsRelease.getString(flag6)).isEqualTo("resource1006")

        // THEN the dump contains the flags and the default values
        val dump = dumpToString()
        assertThat(dump).contains(" sysui_flag_1: true\n")
        assertThat(dump).contains(" sysui_flag_2: true\n")
        assertThat(dump).contains(" sysui_flag_3: false\n")
        assertThat(dump).contains(" sysui_flag_4: [length=0] \"\"\n")
        assertThat(dump).contains(" sysui_flag_5: [length=12] \"flag5default\"\n")
        assertThat(dump).contains(" sysui_flag_6: [length=12] \"resource1006\"\n")
    }

    private fun dumpToString(): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        mFeatureFlagsRelease.dump(mock(), pw, emptyArray())
        pw.flush()
        return sw.toString()
    }
}