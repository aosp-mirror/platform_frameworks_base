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

package com.android.systemui.bubbles.storage

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BubblePersistentRepositoryTest : SysuiTestCase() {

    private val bubbles = listOf(
            BubbleEntity(0, "com.example.messenger", "shortcut-1", "key-1", 120, 0),
            BubbleEntity(10, "com.example.chat", "alice and bob", "key-2", 0, 16537428, "title"),
            BubbleEntity(0, "com.example.messenger", "shortcut-2", "key-3", 120, 0)
    )
    private lateinit var repository: BubblePersistentRepository

    @Before
    fun setup() {
        repository = BubblePersistentRepository(mContext)
    }

    @Test
    fun testReadWriteOperation() {
        // Verify read before write doesn't cause FileNotFoundException
        val actual = repository.readFromDisk()
        assertNotNull(actual)
        assertTrue(actual.isEmpty())

        repository.persistsToDisk(bubbles)
        assertEquals(bubbles, repository.readFromDisk())
    }
}