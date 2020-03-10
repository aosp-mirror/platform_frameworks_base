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

package com.android.systemui.controls.management

import android.app.PendingIntent
import android.content.ComponentName
import android.service.controls.Control
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

open class FavoriteModelTest : SysuiTestCase() {

    @Mock
    lateinit var pendingIntent: PendingIntent
    @Mock
    lateinit var allAdapter: ControlAdapter
    @Mock
    lateinit var favoritesAdapter: ControlAdapter

    val idPrefix = "controlId"
    val favoritesIndices = listOf(7, 3, 1, 9)
    val favoritesList = favoritesIndices.map { "controlId$it" }
    lateinit var controls: List<ControlStatus>

    lateinit var model: FavoriteModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // controlId0 --> zone = 0
        // controlId1 --> zone = 1, favorite
        // controlId2 --> zone = 2
        // controlId3 --> zone = 0, favorite
        // controlId4 --> zone = 1
        // controlId5 --> zone = 2
        // controlId6 --> zone = 0
        // controlId7 --> zone = 1, favorite
        // controlId8 --> zone = 2
        // controlId9 --> zone = 0, favorite
        controls = (0..9).map {
            ControlStatus(
                    Control.StatelessBuilder("$idPrefix$it", pendingIntent)
                            .setZone((it % 3).toString())
                            .build(),
                    ComponentName("", ""),
                    it in favoritesIndices
            )
        }

        model = FavoriteModel(controls, favoritesList, favoritesAdapter, allAdapter)
    }
}

@SmallTest
@RunWith(AndroidTestingRunner::class)
class FavoriteModelNonParametrizedTests : FavoriteModelTest() {
    @Test
    fun testAll() {
        // Zones are sorted alphabetically
        val expected = listOf(
                ZoneNameWrapper("0"),
                ControlWrapper(controls[0]),
                ControlWrapper(controls[3]),
                ControlWrapper(controls[6]),
                ControlWrapper(controls[9]),
                ZoneNameWrapper("1"),
                ControlWrapper(controls[1]),
                ControlWrapper(controls[4]),
                ControlWrapper(controls[7]),
                ZoneNameWrapper("2"),
                ControlWrapper(controls[2]),
                ControlWrapper(controls[5]),
                ControlWrapper(controls[8])
        )
        assertEquals(expected, model.all)
    }

    @Test
    fun testFavoritesInOrder() {
        val expected = favoritesIndices.map { ControlWrapper(controls[it]) }
        assertEquals(expected, model.favorites)
    }

    @Test
    fun testChangeFavoriteStatus_addFavorite() {
        val controlToAdd = 6
        model.changeFavoriteStatus("$idPrefix$controlToAdd", true)

        val pair = model.all.findControl(controlToAdd)
        pair?.let {
            assertTrue(it.second.favorite)
            assertEquals(it.second, model.favorites.last().controlStatus)
            verify(favoritesAdapter).notifyItemInserted(model.favorites.size - 1)
            verify(allAdapter).notifyItemChanged(it.first)
            verifyNoMoreInteractions(favoritesAdapter, allAdapter)
        } ?: run {
            fail("control not found")
        }
    }

    @Test
    fun testChangeFavoriteStatus_removeFavorite() {
        val controlToRemove = 3
        model.changeFavoriteStatus("$idPrefix$controlToRemove", false)

        val pair = model.all.findControl(controlToRemove)
        pair?.let {
            assertFalse(it.second.favorite)
            assertTrue(model.favorites.none {
                it.controlStatus.control.controlId == "$idPrefix$controlToRemove"
            })
            verify(favoritesAdapter).notifyItemRemoved(favoritesIndices.indexOf(controlToRemove))
            verify(allAdapter).notifyItemChanged(it.first)
            verifyNoMoreInteractions(favoritesAdapter, allAdapter)
        } ?: run {
            fail("control not found")
        }
    }

    @Test
    fun testChangeFavoriteStatus_sameStatus() {
        model.changeFavoriteStatus("${idPrefix}7", true)
        model.changeFavoriteStatus("${idPrefix}6", false)

        val expected = favoritesIndices.map { ControlWrapper(controls[it]) }
        assertEquals(expected, model.favorites)

        verifyNoMoreInteractions(favoritesAdapter, allAdapter)
    }

    private fun List<ElementWrapper>.findControl(controlIndex: Int): Pair<Int, ControlStatus>? {
        val index = indexOfFirst {
            it is ControlWrapper &&
                it.controlStatus.control.controlId == "$idPrefix$controlIndex"
        }
        return if (index == -1) null else index to (get(index) as ControlWrapper).controlStatus
    }
}

@SmallTest
@RunWith(Parameterized::class)
class FavoriteModelParameterizedTest(val from: Int, val to: Int) : FavoriteModelTest() {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        fun data(): Collection<Array<Int>> {
            return (0..3).flatMap { from ->
                (0..3).map { to ->
                    arrayOf(from, to)
                }
            }.filterNot { it[0] == it[1] }
        }
    }

    @Test
    fun testMoveItem() {
        val originalFavorites = model.favorites.toList()
        val originalFavoritesIds =
                model.favorites.map { it.controlStatus.control.controlId }.toSet()
        model.onMoveItem(from, to)
        assertEquals(originalFavorites[from], model.favorites[to])
        // Check that we still have the same favorites
        assertEquals(originalFavoritesIds,
                model.favorites.map { it.controlStatus.control.controlId }.toSet())

        verify(favoritesAdapter).notifyItemMoved(from, to)

        verifyNoMoreInteractions(allAdapter, favoritesAdapter)
    }
}
