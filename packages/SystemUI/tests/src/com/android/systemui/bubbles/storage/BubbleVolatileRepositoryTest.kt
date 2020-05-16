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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BubbleVolatileRepositoryTest : SysuiTestCase() {

    private val bubble1 = BubbleEntity(0, "com.example.messenger", "shortcut-1")
    private val bubble2 = BubbleEntity(10, "com.example.chat", "alice and bob")
    private val bubble3 = BubbleEntity(0, "com.example.messenger", "shortcut-2")
    private val bubbles = listOf(bubble1, bubble2, bubble3)

    private lateinit var repository: BubbleVolatileRepository

    @Before
    fun setup() {
        repository = BubbleVolatileRepository()
    }

    @Test
    fun testAddAndRemoveBubbles() {
        repository.addBubbles(bubbles)
        assertEquals(bubbles, repository.bubbles)
        repository.addBubbles(listOf(bubble1))
        assertEquals(listOf(bubble2, bubble3, bubble1), repository.bubbles)
        repository.removeBubbles(listOf(bubble3))
        assertEquals(listOf(bubble2, bubble1), repository.bubbles)
    }
}