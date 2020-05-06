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

import javax.inject.Inject
import javax.inject.Singleton

private const val CAPACITY = 16

/**
 * BubbleVolatileRepository holds the most updated snapshot of list of bubbles for in-memory
 * manipulation.
 */
@Singleton
class BubbleVolatileRepository @Inject constructor() {
    /**
     * An ordered set of bubbles based on their natural ordering.
     */
    private val entities = mutableSetOf<BubbleXmlEntity>()

    /**
     * Returns a snapshot of all the bubbles.
     */
    val bubbles: List<BubbleXmlEntity>
        @Synchronized
        get() = entities.toList()

    /**
     * Add the bubbles to memory and perform a de-duplication. In case a bubble already exists,
     * it will be moved to the last.
     */
    @Synchronized
    fun addBubbles(bubbles: List<BubbleXmlEntity>) {
        if (bubbles.isEmpty()) return
        bubbles.forEach { entities.remove(it) }
        if (entities.size + bubbles.size >= CAPACITY) {
            entities.drop(entities.size + bubbles.size - CAPACITY)
        }
        entities.addAll(bubbles)
    }

    @Synchronized
    fun removeBubbles(bubbles: List<BubbleXmlEntity>) {
        bubbles.forEach { entities.remove(it) }
    }
}
