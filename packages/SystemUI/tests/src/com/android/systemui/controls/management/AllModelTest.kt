/*
 * Copyright (C) 2019 The Android Open Source Project
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
import com.android.systemui.controls.controller.ControlInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AllModelTest : SysuiTestCase() {

    companion object {
        private const val EMPTY_STRING = "Other"
    }

    @Mock
    lateinit var pendingIntent: PendingIntent
    @Mock
    lateinit var controlsModelCallback: ControlsModel.ControlsModelCallback

    val idPrefix = "controlId"
    val favoritesIndices = listOf(7, 3, 1, 9)
    val favoritesList = favoritesIndices.map { "controlId$it" }
    lateinit var controls: List<ControlStatus>

    lateinit var model: AllModel

    private fun zoneMap(id: Int): String? {
        return when (id) {
            10 -> ""
            11 -> null
            else -> ((id + 1) % 3).toString()
        }
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // controlId0 --> zone = 1
        // controlId1 --> zone = 2, favorite
        // controlId2 --> zone = 0
        // controlId3 --> zone = 1, favorite
        // controlId4 --> zone = 2
        // controlId5 --> zone = 0
        // controlId6 --> zone = 1
        // controlId7 --> zone = 2, favorite
        // controlId8 --> zone = 0
        // controlId9 --> zone = 1, favorite
        // controlId10 --> zone = ""
        // controlId11 --> zone = null
        controls = (0..11).map {
            ControlStatus(
                    Control.StatelessBuilder("$idPrefix$it", pendingIntent)
                            .setZone(zoneMap(it))
                            .build(),
                    ComponentName("", ""),
                    it in favoritesIndices
            )
        }
        model = AllModel(controls, favoritesList, EMPTY_STRING, controlsModelCallback)
    }

    @Test
    fun testElements() {

        // Zones are sorted by order of appearance, with empty at the end with special header.
        val expected = listOf(
                ZoneNameWrapper("1"),
                ControlStatusWrapper(controls[0]),
                ControlStatusWrapper(controls[3]),
                ControlStatusWrapper(controls[6]),
                ControlStatusWrapper(controls[9]),
                ZoneNameWrapper("2"),
                ControlStatusWrapper(controls[1]),
                ControlStatusWrapper(controls[4]),
                ControlStatusWrapper(controls[7]),
                ZoneNameWrapper("0"),
                ControlStatusWrapper(controls[2]),
                ControlStatusWrapper(controls[5]),
                ControlStatusWrapper(controls[8]),
                ZoneNameWrapper(EMPTY_STRING),
                ControlStatusWrapper(controls[10]),
                ControlStatusWrapper(controls[11])
        )
        expected.zip(model.elements).forEachIndexed { index, it ->
            assertEquals("Error in item at index $index", it.first, it.second)
        }
    }

    private fun sameControl(controlInfo: ControlInfo, control: Control): Boolean {
        return controlInfo.controlId == control.controlId &&
                controlInfo.controlTitle == control.title &&
                controlInfo.controlSubtitle == control.subtitle &&
                controlInfo.deviceType == control.deviceType
    }

    @Test
    fun testAllEmpty_noHeader() {
        val selected_controls = listOf(controls[10], controls[11])
        val new_model = AllModel(selected_controls, emptyList(), EMPTY_STRING,
                controlsModelCallback)
        val expected = listOf(
                ControlStatusWrapper(controls[10]),
                ControlStatusWrapper(controls[11])
        )

        expected.zip(new_model.elements).forEachIndexed { index, it ->
            assertEquals("Error in item at index $index", it.first, it.second)
        }
    }

    @Test
    fun testFavorites() {
        val expectedFavorites = favoritesIndices.map(controls::get).map(ControlStatus::control)
        model.favorites.zip(expectedFavorites).forEach {
            assertTrue(sameControl(it.first, it.second))
        }
    }

    @Test
    fun testAddFavorite() {
        val indexToAdd = 6
        model.changeFavoriteStatus("$idPrefix$indexToAdd", true)

        val expectedFavorites = favoritesIndices.map(controls::get).map(ControlStatus::control) +
                controls[indexToAdd].control

        model.favorites.zip(expectedFavorites).forEach {
            assertTrue(sameControl(it.first, it.second))
        }

        verify(controlsModelCallback).onFirstChange()
    }

    @Test
    fun testAddFavorite_changesModelFlag() {
        val indexToAdd = 6
        val id = "$idPrefix$indexToAdd"
        model.changeFavoriteStatus(id, true)
        assertTrue(
                (model.elements.first {
                    it is ControlStatusWrapper && it.controlStatus.control.controlId == id
                } as ControlStatusWrapper)
                        .controlStatus.favorite
        )

        verify(controlsModelCallback).onFirstChange()
    }

    @Test
    fun testAddFavorite_alreadyThere() {
        val indexToAdd = 7
        model.changeFavoriteStatus("$idPrefix$indexToAdd", true)

        val expectedFavorites = favoritesIndices.map(controls::get).map(ControlStatus::control)

        assertEquals(expectedFavorites.size, model.favorites.size)
        model.favorites.zip(expectedFavorites).forEach {
            assertTrue(sameControl(it.first, it.second))
        }

        verify(controlsModelCallback, never()).onFirstChange()
    }

    @Test
    fun testRemoveFavorite() {
        val indexToRemove = 3
        model.changeFavoriteStatus("$idPrefix$indexToRemove", false)

        val expectedFavorites = (favoritesIndices.filterNot { it == indexToRemove })
                .map(controls::get)
                .map(ControlStatus::control)

        model.favorites.zip(expectedFavorites).forEach {
            assertTrue(sameControl(it.first, it.second))
        }

        verify(controlsModelCallback).onFirstChange()
    }

    @Test
    fun testRemoveFavorite_changesModelFlag() {
        val indexToRemove = 3
        val id = "$idPrefix$indexToRemove"
        model.changeFavoriteStatus(id, false)
        assertFalse(
                (model.elements.first {
                    it is ControlStatusWrapper && it.controlStatus.control.controlId == id
                } as ControlStatusWrapper)
                        .controlStatus.favorite
        )

        verify(controlsModelCallback).onFirstChange()
    }

    @Test
    fun testRemoveFavorite_notThere() {
        val indexToRemove = 4
        model.changeFavoriteStatus("$idPrefix$indexToRemove", false)

        val expectedFavorites = favoritesIndices.map(controls::get).map(ControlStatus::control)

        model.favorites.zip(expectedFavorites).forEach {
            assertTrue(sameControl(it.first, it.second))
        }

        verify(controlsModelCallback, never()).onFirstChange()
    }
}
