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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.ravenwood.RavenwoodEnvironment.CompatIdsForTest
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RavenwoodCompatFrameworkTest {
    @Test
    fun testEnabled() {
        Assert.assertTrue(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_1))
    }

    @Test
    fun testDisabled() {
        Assert.assertFalse(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_2))
    }

    @Test
    fun testEnabledAfterSForUApps() {
        Assert.assertTrue(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_3))
    }

    @Test
    fun testEnabledAfterUForUApps() {
        Assert.assertFalse(CompatChanges.isChangeEnabled(CompatIdsForTest.TEST_COMPAT_ID_4))
    }
}
