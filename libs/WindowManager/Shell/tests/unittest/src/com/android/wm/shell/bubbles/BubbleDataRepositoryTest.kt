/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.bubbles

import android.app.ActivityTaskManager
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.util.SparseArray
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.bubbles.storage.BubbleEntity
import com.android.wm.shell.bubbles.storage.BubblePersistentRepository
import com.android.wm.shell.common.ShellExecutor
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy

class BubbleDataRepositoryTest : ShellTestCase() {

    private val user0BubbleEntities = listOf(
        BubbleEntity(
            userId = 0,
            packageName = "com.example.messenger",
            shortcutId = "shortcut-1",
            key = "0k1",
            desiredHeight = 120,
            desiredHeightResId = 0,
            title = null,
            taskId = 1,
            locus = null,
            isDismissable = true
        ),
        BubbleEntity(
            userId = 10,
            packageName = "com.example.chat",
            shortcutId = "alice and bob",
            key = "0k2",
            desiredHeight = 0,
            desiredHeightResId = 16537428,
            title = "title",
            taskId = 2,
            locus = null
        ),
        BubbleEntity(
            userId = 0,
            packageName = "com.example.messenger",
            shortcutId = "shortcut-2",
            key = "0k3",
            desiredHeight = 120,
            desiredHeightResId = 0,
            title = null,
            taskId = ActivityTaskManager.INVALID_TASK_ID,
            locus = null
        )
    )

    private val user1BubbleEntities = listOf(
        BubbleEntity(
            userId = 1,
            packageName = "com.example.messenger",
            shortcutId = "shortcut-1",
            key = "1k1",
            desiredHeight = 120,
            desiredHeightResId = 0,
            title = null,
            taskId = 3,
            locus = null,
            isDismissable = true
        ),
        BubbleEntity(
            userId = 12,
            packageName = "com.example.chat",
            shortcutId = "alice and bob",
            key = "1k2",
            desiredHeight = 0,
            desiredHeightResId = 16537428,
            title = "title",
            taskId = 4,
            locus = null
        ),
        BubbleEntity(
            userId = 1,
            packageName = "com.example.messenger",
            shortcutId = "shortcut-2",
            key = "1k3",
            desiredHeight = 120,
            desiredHeightResId = 0,
            title = null,
            taskId = ActivityTaskManager.INVALID_TASK_ID,
            locus = null
        ),
        BubbleEntity(
            userId = 12,
            packageName = "com.example.chat",
            shortcutId = "alice",
            key = "1k4",
            desiredHeight = 0,
            desiredHeightResId = 16537428,
            title = "title",
            taskId = 5,
            locus = null
        )
    )

    private val mainExecutor = mock(ShellExecutor::class.java)
    private val launcherApps = mock(LauncherApps::class.java)

    private val persistedBubbles = SparseArray<List<BubbleEntity>>()

    private lateinit var dataRepository: BubbleDataRepository
    private lateinit var persistentRepository: BubblePersistentRepository

    @Before
    fun setup() {
        persistentRepository = spy(BubblePersistentRepository(mContext))
        dataRepository = BubbleDataRepository(launcherApps, mainExecutor, persistentRepository)

        // Add the bubbles to the persistent repository
        persistedBubbles.put(0, user0BubbleEntities)
        persistedBubbles.put(1, user1BubbleEntities)
        persistentRepository.persistsToDisk(persistedBubbles)
    }

    @After
    fun teardown() {
        // Clean up any persisted bubbles for the next run
        persistentRepository.persistsToDisk(SparseArray())
    }

    @Test
    fun testLoadBubbles_invalidParent() {
        val activeUserIds = listOf(10, 1, 12) // Missing user 0 in persistedBubbles
        dataRepository.loadBubbles(1, activeUserIds) {
            // Verify that user 0 has been removed from the persisted list
            val entitiesByUser = persistentRepository.readFromDisk()
            assertThat(entitiesByUser.get(0)).isNull()
        }
    }

    @Test
    fun testLoadBubbles_invalidChild() {
        val activeUserIds = listOf(0, 10, 1) // Missing user 1's child user 12
        dataRepository.loadBubbles(1, activeUserIds) {
            // Build a list to compare against
            val user1BubblesWithoutUser12 = mutableListOf<Bubble>()
            val user1EntitiesWithoutUser12 = mutableListOf<BubbleEntity>()
            for (entity in user1BubbleEntities) {
                if (entity.userId != 12) {
                    user1BubblesWithoutUser12.add(entity.toBubble())
                    user1EntitiesWithoutUser12.add(entity)
                }
            }

            // Verify that user 12 has been removed from the persisted list
            val entitiesByUser = persistentRepository.readFromDisk()
            assertThat(entitiesByUser.get(1)).isEqualTo(user1EntitiesWithoutUser12)
        }
    }

    private fun BubbleEntity.toBubble(): Bubble {
        return Bubble(
            key,
            mock(ShortcutInfo::class.java),
            desiredHeight,
            desiredHeightResId,
            title,
            taskId,
            locus,
            isDismissable,
            mainExecutor,
            mock(Bubbles.BubbleMetadataFlagListener::class.java)
        )
    }
}