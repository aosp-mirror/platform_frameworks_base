/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class CustomIconCacheTest : SysuiTestCase() {

    companion object {
        private val TEST_COMPONENT1 = ComponentName.unflattenFromString("pkg/.cls1")!!
        private val TEST_COMPONENT2 = ComponentName.unflattenFromString("pkg/.cls2")!!
        private const val CONTROL_ID_1 = "TEST_CONTROL_1"
        private const val CONTROL_ID_2 = "TEST_CONTROL_2"
    }

    @Mock(stubOnly = true)
    private lateinit var icon1: Icon
    @Mock(stubOnly = true)
    private lateinit var icon2: Icon
    private lateinit var customIconCache: CustomIconCache

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        customIconCache = CustomIconCache()
    }

    @Test
    fun testIconStoredCorrectly() {
        customIconCache.store(TEST_COMPONENT1, CONTROL_ID_1, icon1)

        assertTrue(icon1 === customIconCache.retrieve(TEST_COMPONENT1, CONTROL_ID_1))
    }

    @Test
    fun testIconNotStoredReturnsNull() {
        customIconCache.store(TEST_COMPONENT1, CONTROL_ID_1, icon1)

        assertNull(customIconCache.retrieve(TEST_COMPONENT1, CONTROL_ID_2))
    }

    @Test
    fun testWrongComponentReturnsNull() {
        customIconCache.store(TEST_COMPONENT1, CONTROL_ID_1, icon1)

        assertNull(customIconCache.retrieve(TEST_COMPONENT2, CONTROL_ID_1))
    }

    @Test
    fun testChangeComponentOldComponentIsRemoved() {
        customIconCache.store(TEST_COMPONENT1, CONTROL_ID_1, icon1)
        customIconCache.store(TEST_COMPONENT2, CONTROL_ID_2, icon2)

        assertNull(customIconCache.retrieve(TEST_COMPONENT1, CONTROL_ID_1))
        assertNull(customIconCache.retrieve(TEST_COMPONENT1, CONTROL_ID_2))
    }

    @Test
    fun testChangeComponentCorrectIconRetrieved() {
        customIconCache.store(TEST_COMPONENT1, CONTROL_ID_1, icon1)
        customIconCache.store(TEST_COMPONENT2, CONTROL_ID_1, icon2)

        assertTrue(icon2 === customIconCache.retrieve(TEST_COMPONENT2, CONTROL_ID_1))
    }

    @Test
    fun testStoreNull() {
        customIconCache.store(TEST_COMPONENT1, CONTROL_ID_1, icon1)
        customIconCache.store(TEST_COMPONENT1, CONTROL_ID_1, null)

        assertNull(customIconCache.retrieve(TEST_COMPONENT1, CONTROL_ID_1))
    }
}