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

package com.android.systemui.statusbar.notification.collection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotifCollectionCacheTest : SysuiTestCase() {
    companion object {
        const val A = "a"
        const val B = "b"
        const val C = "c"
    }

    val systemClock = FakeSystemClock()
    val underTest =
        NotifCollectionCache<String>(purgeTimeoutMillis = 200L, systemClock = systemClock)

    @After
    fun cleanUp() {
        underTest.clear()
    }

    @Test
    fun fetch_isOnlyCalledOncePerEntry() {
        val fetchList = mutableListOf<String>()
        val fetch = { key: String ->
            fetchList.add(key)
            key
        }

        // Construct the cache and make sure fetch is called
        assertThat(underTest.getOrFetch(A, fetch)).isEqualTo(A)
        assertThat(underTest.getOrFetch(B, fetch)).isEqualTo(B)
        assertThat(underTest.getOrFetch(C, fetch)).isEqualTo(C)
        assertThat(fetchList).containsExactly(A, B, C).inOrder()

        // Verify that further calls don't trigger fetch again
        underTest.getOrFetch(A, fetch)
        underTest.getOrFetch(A, fetch)
        underTest.getOrFetch(B, fetch)
        underTest.getOrFetch(C, fetch)
        assertThat(fetchList).containsExactly(A, B, C).inOrder()

        // Verify that fetch gets called again if the entries are cleared
        underTest.clear()
        underTest.getOrFetch(A, fetch)
        assertThat(fetchList).containsExactly(A, B, C, A).inOrder()
    }

    @Test
    fun purge_beforeTimeout_doesNothing() {
        // Populate cache
        val fetch = { key: String -> key }
        underTest.getOrFetch(A, fetch)
        underTest.getOrFetch(B, fetch)
        underTest.getOrFetch(C, fetch)

        // B starts off with ♥ ︎♥︎
        assertThat(underTest.getLives(B)).isEqualTo(2)
        // First purge run removes a ︎♥︎
        underTest.purge(listOf(A, C))
        assertNotNull(underTest.cache[B])
        assertThat(underTest.getLives(B)).isEqualTo(1)
        // Second purge run done too early does nothing to B
        systemClock.advanceTime(100L)
        underTest.purge(listOf(A, C))
        assertNotNull(underTest.cache[B])
        assertThat(underTest.getLives(B)).isEqualTo(1)
        // Purge done after timeout (200ms) clears B
        systemClock.advanceTime(100L)
        underTest.purge(listOf(A, C))
        assertNull(underTest.cache[B])
    }

    @Test
    fun get_resetsLives() {
        // Populate cache
        val fetch = { key: String -> key }
        underTest.getOrFetch(A, fetch)
        underTest.getOrFetch(B, fetch)
        underTest.getOrFetch(C, fetch)

        // Bring B down to one ︎♥︎
        underTest.purge(listOf(A, C))
        assertThat(underTest.getLives(B)).isEqualTo(1)

        // Get should restore B to ♥ ︎♥︎
        underTest.getOrFetch(B, fetch)
        assertThat(underTest.getLives(B)).isEqualTo(2)

        // Subsequent purge should remove a life regardless of timing
        underTest.purge(listOf(A, C))
        assertThat(underTest.getLives(B)).isEqualTo(1)
    }

    @Test
    fun purge_resetsLives() {
        // Populate cache
        val fetch = { key: String -> key }
        underTest.getOrFetch(A, fetch)
        underTest.getOrFetch(B, fetch)
        underTest.getOrFetch(C, fetch)

        // Bring B down to one ︎♥︎
        underTest.purge(listOf(A, C))
        assertThat(underTest.getLives(B)).isEqualTo(1)

        // When B is back to wantedKeys, it is restored to to ♥ ︎♥ ︎︎
        underTest.purge(listOf(B))
        assertThat(underTest.getLives(B)).isEqualTo(2)
        assertThat(underTest.getLives(A)).isEqualTo(1)
        assertThat(underTest.getLives(C)).isEqualTo(1)

        // Subsequent purge should remove a life regardless of timing
        underTest.purge(listOf(A, C))
        assertThat(underTest.getLives(B)).isEqualTo(1)
    }

    @Test
    fun purge_worksWithMoreLives() {
        val multiLivesCache =
            NotifCollectionCache<String>(
                retainCount = 3,
                purgeTimeoutMillis = 100L,
                systemClock = systemClock,
            )

        // Populate cache
        val fetch = { key: String -> key }
        multiLivesCache.getOrFetch(A, fetch)
        multiLivesCache.getOrFetch(B, fetch)
        multiLivesCache.getOrFetch(C, fetch)

        // B starts off with ♥ ︎♥︎ ♥ ︎♥︎
        assertThat(multiLivesCache.getLives(B)).isEqualTo(4)
        // First purge run removes a ︎♥︎
        multiLivesCache.purge(listOf(A, C))
        assertNotNull(multiLivesCache.cache[B])
        assertThat(multiLivesCache.getLives(B)).isEqualTo(3)
        // Second purge run done too early does nothing to B
        multiLivesCache.purge(listOf(A, C))
        assertNotNull(multiLivesCache.cache[B])
        assertThat(multiLivesCache.getLives(B)).isEqualTo(3)
        // Staggered purge runs remove further ︎♥︎
        systemClock.advanceTime(100L)
        multiLivesCache.purge(listOf(A, C))
        assertNotNull(multiLivesCache.cache[B])
        assertThat(multiLivesCache.getLives(B)).isEqualTo(2)
        systemClock.advanceTime(100L)
        multiLivesCache.purge(listOf(A, C))
        assertNotNull(multiLivesCache.cache[B])
        assertThat(multiLivesCache.getLives(B)).isEqualTo(1)
        systemClock.advanceTime(100L)
        multiLivesCache.purge(listOf(A, C))
        assertNull(multiLivesCache.cache[B])
    }

    @Test
    fun purge_worksWithNoLives() {
        val noLivesCache =
            NotifCollectionCache<String>(
                retainCount = 0,
                purgeTimeoutMillis = 0L,
                systemClock = systemClock,
            )

        val fetch = { key: String -> key }
        noLivesCache.getOrFetch(A, fetch)
        noLivesCache.getOrFetch(B, fetch)
        noLivesCache.getOrFetch(C, fetch)

        // Purge immediately removes entry
        noLivesCache.purge(listOf(A, C))

        assertNotNull(noLivesCache.cache[A])
        assertNull(noLivesCache.cache[B])
        assertNotNull(noLivesCache.cache[C])
    }

    @Test
    fun hitsAndMisses_areAccurate() {
        val fetch = { key: String -> key }

        // Construct the cache
        assertThat(underTest.getOrFetch(A, fetch)).isEqualTo(A)
        assertThat(underTest.getOrFetch(B, fetch)).isEqualTo(B)
        assertThat(underTest.getOrFetch(C, fetch)).isEqualTo(C)
        assertThat(underTest.hits.get()).isEqualTo(0)
        assertThat(underTest.misses.get()).isEqualTo(3)

        // Verify that further calls count as hits
        underTest.getOrFetch(A, fetch)
        underTest.getOrFetch(A, fetch)
        underTest.getOrFetch(B, fetch)
        underTest.getOrFetch(C, fetch)
        assertThat(underTest.hits.get()).isEqualTo(4)
        assertThat(underTest.misses.get()).isEqualTo(3)

        // Verify that a miss is counted again if the entries are cleared
        underTest.clear()
        underTest.getOrFetch(A, fetch)
        assertThat(underTest.hits.get()).isEqualTo(4)
        assertThat(underTest.misses.get()).isEqualTo(4)
    }

    private fun <V> NotifCollectionCache<V>.getLives(key: String) = this.cache[key]?.lives
}
