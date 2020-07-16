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

import android.content.pm.LauncherApps
import android.os.UserHandle
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.bubbles.ShortcutKey
import javax.inject.Inject
import javax.inject.Singleton

private const val CAPACITY = 16

/**
 * BubbleVolatileRepository holds the most updated snapshot of list of bubbles for in-memory
 * manipulation.
 */
@Singleton
class BubbleVolatileRepository @Inject constructor(
    private val launcherApps: LauncherApps
) {
    /**
     * An ordered set of bubbles based on their natural ordering.
     */
    private var entities = mutableSetOf<BubbleEntity>()

    /**
     * The capacity of the cache.
     */
    @VisibleForTesting
    var capacity = CAPACITY

    /**
     * Returns a snapshot of all the bubbles.
     */
    val bubbles: List<BubbleEntity>
        @Synchronized
        get() = entities.toList()

    /**
     * Add the bubbles to memory and perform a de-duplication. In case a bubble already exists,
     * it will be moved to the last.
     */
    @Synchronized
    fun addBubbles(bubbles: List<BubbleEntity>) {
        if (bubbles.isEmpty()) return
        // Verify the size of given bubbles is within capacity, otherwise trim down to capacity
        val bubblesInRange = bubbles.takeLast(capacity)
        // To ensure natural ordering of the bubbles, removes bubbles which already exist
        val uniqueBubbles = bubblesInRange.filterNot { b: BubbleEntity ->
            entities.removeIf { e: BubbleEntity -> b.key == e.key } }
        val overflowCount = entities.size + bubblesInRange.size - capacity
        if (overflowCount > 0) {
            // Uncache ShortcutInfo of bubbles that will be removed due to capacity
            uncache(entities.take(overflowCount))
            entities = entities.drop(overflowCount).toMutableSet()
        }
        entities.addAll(bubblesInRange)
        cache(uniqueBubbles)
    }

    @Synchronized
    fun removeBubbles(bubbles: List<BubbleEntity>) =
            uncache(bubbles.filter { b: BubbleEntity ->
                entities.removeIf { e: BubbleEntity -> b.key == e.key } })

    private fun cache(bubbles: List<BubbleEntity>) {
        bubbles.groupBy { ShortcutKey(it.userId, it.packageName) }.forEach { (key, bubbles) ->
            launcherApps.cacheShortcuts(key.pkg, bubbles.map { it.shortcutId },
                    UserHandle.of(key.userId), LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS)
        }
    }

    private fun uncache(bubbles: List<BubbleEntity>) {
        bubbles.groupBy { ShortcutKey(it.userId, it.packageName) }.forEach { (key, bubbles) ->
            launcherApps.uncacheShortcuts(key.pkg, bubbles.map { it.shortcutId },
                    UserHandle.of(key.userId), LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS)
        }
    }
}
