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
package com.android.ravenwoodtest.bivalenttest.compat

import android.app.compat.CompatChanges
import android.compat.testing.PlatformCompatChangeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.ravenwood.RavenwoodEnvironment.CompatIdsForTest
import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RavenwoodCompatFrameworkTest {

    @get:Rule
    val compatRule = PlatformCompatChangeRule()

    @Test
    fun testEnabled() {
        assertTrue(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_1))
    }

    @Test
    fun testDisabled() {
        assertFalse(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_2))
    }

    @Test
    fun testEnabledAfterSForUApps() {
        assertTrue(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_3))
    }

    @Test
    fun testEnabledAfterUForUApps() {
        assertFalse(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_4))
    }

    @Test
    @EnableCompatChanges(CompatIdsForTest.TEST_COMPAT_ID_5)
    fun testEnableCompatChanges() {
        assertTrue(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_5))
    }

    @Test
    @DisableCompatChanges(CompatIdsForTest.TEST_COMPAT_ID_5)
    fun testDisableCompatChanges() {
        assertFalse(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_5))
    }
}
