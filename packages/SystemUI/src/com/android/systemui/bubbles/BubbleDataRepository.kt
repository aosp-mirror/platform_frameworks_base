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
package com.android.systemui.bubbles

import android.annotation.UserIdInt
import android.util.Log
import com.android.systemui.bubbles.storage.BubblePersistentRepository
import com.android.systemui.bubbles.storage.BubbleVolatileRepository
import com.android.systemui.bubbles.storage.BubbleXmlEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class BubbleDataRepository @Inject constructor(
    private val volatileRepository: BubbleVolatileRepository,
    private val persistentRepository: BubblePersistentRepository
) {

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    /**
     * Adds the bubble in memory, then persists the snapshot after adding the bubble to disk
     * asynchronously.
     */
    fun addBubble(@UserIdInt userId: Int, bubble: Bubble) = addBubbles(userId, listOf(bubble))

    /**
     * Adds the bubble in memory, then persists the snapshot after adding the bubble to disk
     * asynchronously.
     */
    fun addBubbles(@UserIdInt userId: Int, bubbles: List<Bubble>) {
        if (DEBUG) Log.d(TAG, "adding ${bubbles.size} bubbles")
        val entities = transform(userId, bubbles).also(volatileRepository::addBubbles)
        if (entities.isNotEmpty()) persistToDisk()
    }

    /**
     * Removes the bubbles from memory, then persists the snapshot to disk asynchronously.
     */
    fun removeBubbles(@UserIdInt userId: Int, bubbles: List<Bubble>) {
        if (DEBUG) Log.d(TAG, "removing ${bubbles.size} bubbles")
        val entities = transform(userId, bubbles).also(volatileRepository::removeBubbles)
        if (entities.isNotEmpty()) persistToDisk()
    }

    private fun transform(userId: Int, bubbles: List<Bubble>): List<BubbleXmlEntity> {
        return bubbles.mapNotNull { b ->
            val shortcutId = b.shortcutInfo?.id ?: return@mapNotNull null
            BubbleXmlEntity(userId, b.packageName, shortcutId)
        }
    }

    /**
     * Persists the bubbles to disk. When being called multiple times, it waits for first ongoing
     * write operation to finish then run another write operation exactly once.
     *
     * e.g.
     * Job A started -> blocking I/O
     * Job B started, cancels A, wait for blocking I/O in A finishes
     * Job C started, cancels B, wait for job B to finish
     * Job D started, cancels C, wait for job C to finish
     * Job A completed
     * Job B resumes and reaches yield() and is then cancelled
     * Job C resumes and reaches yield() and is then cancelled
     * Job D resumes and performs another blocking I/O
     */
    private fun persistToDisk() {
        val prev = job
        job = ioScope.launch {
            // if there was an ongoing disk I/O operation, they can be cancelled
            prev?.cancelAndJoin()
            // check for cancellation before disk I/O
            yield()
            // save to disk
            persistentRepository.persistsToDisk(volatileRepository.bubbles)
        }
    }

    /**
     * Load bubbles from disk.
     */
    fun loadBubbles(cb: (List<Bubble>) -> Unit) = ioScope.launch {
        val bubbleXmlEntities = persistentRepository.readFromDisk()
        volatileRepository.addBubbles(bubbleXmlEntities)
        uiScope.launch {
            // TODO: transform bubbleXmlEntities into bubbles
            // cb(bubbles)
        }
    }
}

private const val TAG = "BubbleDataRepository"
private const val DEBUG = false
