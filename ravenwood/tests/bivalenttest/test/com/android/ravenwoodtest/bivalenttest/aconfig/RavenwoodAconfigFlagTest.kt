/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwoodtest.bivalenttest.aconfig

import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.os.Flags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class RavenwoodAconfigSimpleReadTests {
    @Test
    fun testFalseFlags() {
        assertFalse(Flags.ravenwoodFlagRo1())
        assertFalse(Flags.ravenwoodFlagRw1())
    }

    @Test
    @Ignore // TODO: Enable this test after rolling out the "2" flags.
    fun testTrueFlags() {
        assertTrue(Flags.ravenwoodFlagRo2())
        assertTrue(Flags.ravenwoodFlagRw2())
    }
}

@RunWith(AndroidJUnit4::class)
class RavenwoodAconfigCheckFlagsRuleTests {
    @Rule
    @JvmField
    val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_RAVENWOOD_FLAG_RO_1)
    fun testRequireFlagsEnabledRo() {
        fail("This test shouldn't be executed")
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_RAVENWOOD_FLAG_RW_1)
    fun testRequireFlagsEnabledRw() {
        fail("This test shouldn't be executed")
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_RAVENWOOD_FLAG_RO_2)
    @Ignore // TODO: Enable this test after rolling out the "2" flags.
    fun testRequireFlagsDisabledRo() {
        fail("This test shouldn't be executed")
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_RAVENWOOD_FLAG_RW_2)
    @Ignore // TODO: Enable this test after rolling out the "2" flags.
    fun testRequireFlagsDisabledRw() {
        fail("This test shouldn't be executed")
    }
}

@RunWith(AndroidJUnit4::class)
class RavenwoodAconfigSetFlagsRuleWithDefaultTests {
    @Rule
    @JvmField
    val setFlagsRule = SetFlagsRule()

    @Test
    @EnableFlags(Flags.FLAG_RAVENWOOD_FLAG_RO_1)
    fun testSetRoFlag() {
        assertTrue(Flags.ravenwoodFlagRo1())
        assertFalse(Flags.ravenwoodFlagRw1())
    }

    @Test
    @EnableFlags(Flags.FLAG_RAVENWOOD_FLAG_RW_1)
    fun testSetRwFlag() {
        assertFalse(Flags.ravenwoodFlagRo1())
        assertTrue(Flags.ravenwoodFlagRw1())
    }
}
