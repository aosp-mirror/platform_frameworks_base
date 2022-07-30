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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.lang.IllegalStateException
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class FakeFeatureFlagsTest : SysuiTestCase() {

    private val booleanFlag = BooleanFlag(-1000)
    private val stringFlag = StringFlag(-1001)
    private val resourceBooleanFlag = ResourceBooleanFlag(-1002, resourceId = -1)
    private val resourceStringFlag = ResourceStringFlag(-1003, resourceId = -1)
    private val sysPropBooleanFlag = SysPropBooleanFlag(-1004, name = "test")

    /**
     * FakeFeatureFlags does not honor any default values. All flags which are accessed must be
     * specified. If not, an exception is thrown.
     */
    @Test
    fun throwsIfUnspecifiedFlagIsAccessed() {
        val flags: FeatureFlags = FakeFeatureFlags()
        try {
            assertThat(flags.isEnabled(Flags.TEAMFOOD)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("TEAMFOOD")
        }
        try {
            assertThat(flags.isEnabled(booleanFlag)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1000)")
        }
        try {
            assertThat(flags.isEnabled(resourceBooleanFlag)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1002)")
        }
        try {
            assertThat(flags.isEnabled(sysPropBooleanFlag)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1004)")
        }
        try {
            assertThat(flags.getString(stringFlag)).isEmpty()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1001)")
        }
        try {
            assertThat(flags.getString(resourceStringFlag)).isEmpty()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1003)")
        }
    }

    @Test
    fun specifiedFlagsReturnCorrectValues() {
        val flags = FakeFeatureFlags()
        flags.set(booleanFlag, false)
        flags.set(resourceBooleanFlag, false)
        flags.set(sysPropBooleanFlag, false)
        flags.set(resourceStringFlag, "")

        assertThat(flags.isEnabled(booleanFlag)).isFalse()
        assertThat(flags.isEnabled(resourceBooleanFlag)).isFalse()
        assertThat(flags.isEnabled(sysPropBooleanFlag)).isFalse()
        assertThat(flags.getString(resourceStringFlag)).isEmpty()

        flags.set(booleanFlag, true)
        flags.set(resourceBooleanFlag, true)
        flags.set(sysPropBooleanFlag, true)
        flags.set(resourceStringFlag, "Android")

        assertThat(flags.isEnabled(booleanFlag)).isTrue()
        assertThat(flags.isEnabled(resourceBooleanFlag)).isTrue()
        assertThat(flags.isEnabled(sysPropBooleanFlag)).isTrue()
        assertThat(flags.getString(resourceStringFlag)).isEqualTo("Android")
    }
}
