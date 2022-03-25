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
import android.test.suitebuilder.annotation.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

/**
 * NOTE: This test is for the version of FeatureFlagManager in src-release, which should not allow
 * overriding, and should never return any value other than the one provided as the default.
 */
@SmallTest
class FeatureFlagsReleaseTest : SysuiTestCase() {
    private lateinit var mFeatureFlagsRelease: FeatureFlagsRelease

    @Mock private lateinit var mResources: Resources
    @Mock private lateinit var mSystemProperties: SystemPropertiesHelper
    @Mock private lateinit var mDumpManager: DumpManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mFeatureFlagsRelease = FeatureFlagsRelease(mResources, mSystemProperties, mDumpManager)
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
    fun testSysPropBooleanFlag() {
        val flagId = 213
        val flagName = "sys_prop_flag"
        val flagDefault = true

        val flag = SysPropBooleanFlag(flagId, flagName, flagDefault)
        whenever(mSystemProperties.getBoolean(flagName, flagDefault)).thenReturn(flagDefault)
        assertThat(mFeatureFlagsRelease.isEnabled(flag)).isEqualTo(flagDefault)
    }
}