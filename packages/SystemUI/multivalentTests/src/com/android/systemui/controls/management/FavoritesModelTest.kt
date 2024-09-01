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

import android.content.ComponentName
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlInterface
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class FavoritesModelTest : SysuiTestCase() {

    companion object {
        private val TEST_COMPONENT = ComponentName.unflattenFromString("test_pkg/.test_cls")!!
        private val ID_PREFIX = "control"
        private val INITIAL_FAVORITES = (0..5).map {
            ControlInfo("$ID_PREFIX$it", "title$it", "subtitle$it", it)
        }
    }

    @Mock
    private lateinit var callback: FavoritesModel.FavoritesModelCallback
    @Mock
    private lateinit var adapter: RecyclerView.Adapter<*>
    @Mock
    private lateinit var customIconCache: CustomIconCache
    private lateinit var model: FavoritesModel
    private lateinit var dividerWrapper: DividerWrapper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        model = FavoritesModel(customIconCache, TEST_COMPONENT, INITIAL_FAVORITES, callback)
        model.attachAdapter(adapter)
        dividerWrapper = model.elements.first { it is DividerWrapper } as DividerWrapper
    }

    @After
    fun testListConsistency() {
        assertEquals(INITIAL_FAVORITES.size + 1, model.elements.toSet().size)
        val dividerIndex = getDividerPosition()
        model.elements.forEachIndexed { index, element ->
            if (index == dividerIndex) {
                assertEquals(dividerWrapper, element)
            } else {
                element as ControlInterface
                assertEquals(index < dividerIndex, element.favorite)
            }
        }
        assertEquals(model.favorites, model.elements.take(dividerIndex).map {
            (it as ControlInfoWrapper).controlInfo
        })
    }

    @Test
    fun testInitialElements() {
        val expected = INITIAL_FAVORITES.map {
            ControlInfoWrapper(TEST_COMPONENT, it, true, customIconCache::retrieve)
        } + DividerWrapper()
        assertEquals(expected, model.elements)
    }

    @Test
    fun testFavorites() {
        assertEquals(INITIAL_FAVORITES, model.favorites)
    }

    @Test
    fun testRemoveFavorite_notInFavorites() {
        val removed = 4
        val id = "$ID_PREFIX$removed"

        model.changeFavoriteStatus(id, false)

        assertTrue(model.favorites.none { it.controlId == id })

        verify(callback).onFirstChange()
    }

    @Test
    fun testRemoveFavorite_endOfElements() {
        val removed = 4
        val id = "$ID_PREFIX$removed"
        model.changeFavoriteStatus(id, false)

        assertEquals(ControlInfoWrapper(
                TEST_COMPONENT, INITIAL_FAVORITES[4], false), model.elements.last())
        verify(callback).onFirstChange()
    }

    @Test
    fun testRemoveFavorite_adapterNotified() {
        val removed = 4
        val id = "$ID_PREFIX$removed"
        model.changeFavoriteStatus(id, false)

        val lastPos = model.elements.size - 1
        verify(adapter).notifyItemChanged(eq(lastPos), any(Any::class.java))
        verify(adapter).notifyItemMoved(removed, lastPos)

        verify(callback).onFirstChange()
    }

    @Test
    fun testRemoveFavorite_dividerMovedBack() {
        val oldDividerPosition = getDividerPosition()
        val removed = 4
        val id = "$ID_PREFIX$removed"
        model.changeFavoriteStatus(id, false)

        assertEquals(oldDividerPosition - 1, getDividerPosition())

        verify(callback).onFirstChange()
    }

    @Test
    fun testRemoveFavorite_ShowDivider() {
        val oldDividerPosition = getDividerPosition()
        val removed = 4
        val id = "$ID_PREFIX$removed"
        model.changeFavoriteStatus(id, false)

        assertTrue(dividerWrapper.showDivider)
        verify(adapter).notifyItemChanged(oldDividerPosition)

        verify(callback).onFirstChange()
    }

    @Test
    fun testDoubleRemove_onlyOnce() {
        val removed = 4
        val id = "$ID_PREFIX$removed"
        model.changeFavoriteStatus(id, false)
        model.changeFavoriteStatus(id, false)

        verify(adapter /* only once */).notifyItemChanged(anyInt(), any(Any::class.java))
        verify(adapter /* only once */).notifyItemMoved(anyInt(), anyInt())
        verify(adapter /* only once (divider) */).notifyItemChanged(anyInt())

        verify(callback).onFirstChange()
    }

    @Test
    fun testRemoveTwo_InSameOrder() {
        val removedFirst = 3
        val removedSecond = 0
        model.changeFavoriteStatus("$ID_PREFIX$removedFirst", false)
        model.changeFavoriteStatus("$ID_PREFIX$removedSecond", false)

        assertEquals(listOf(
                ControlInfoWrapper(TEST_COMPONENT, INITIAL_FAVORITES[removedFirst], false),
                ControlInfoWrapper(TEST_COMPONENT, INITIAL_FAVORITES[removedSecond], false)
        ), model.elements.takeLast(2))

        verify(callback).onFirstChange()
    }

    @Test
    fun testRemoveAll_showNone() {
        INITIAL_FAVORITES.forEach {
            model.changeFavoriteStatus(it.controlId, false)
        }
        assertEquals(dividerWrapper, model.elements.first())
        assertTrue(dividerWrapper.showNone)
        verify(adapter, times(2)).notifyItemChanged(anyInt()) // divider
        verify(callback).onNoneChanged(true)

        verify(callback).onFirstChange()
    }

    @Test
    fun testAddFavorite_movedToEnd() {
        val added = 2
        val id = "$ID_PREFIX$added"
        model.changeFavoriteStatus(id, false)
        model.changeFavoriteStatus(id, true)

        assertEquals(id, model.favorites.last().controlId)

        verify(callback).onFirstChange()
    }

    @Test
    fun testAddFavorite_onlyOnce() {
        val added = 2
        val id = "$ID_PREFIX$added"
        model.changeFavoriteStatus(id, false)
        model.changeFavoriteStatus(id, true)
        model.changeFavoriteStatus(id, true)

        // Once for remove and once for add
        verify(adapter, times(2)).notifyItemChanged(anyInt(), any(Any::class.java))
        verify(adapter, times(2)).notifyItemMoved(anyInt(), anyInt())

        verify(callback).onFirstChange()
    }

    @Test
    fun testAddFavorite_notRemoved() {
        val added = 2
        val id = "$ID_PREFIX$added"
        model.changeFavoriteStatus(id, true)

        verifyNoMoreInteractions(adapter)

        verify(callback, never()).onFirstChange()
    }

    @Test
    fun testAddOnlyRemovedFavorite_dividerStopsShowing() {
        val added = 2
        val id = "$ID_PREFIX$added"
        model.changeFavoriteStatus(id, false)
        model.changeFavoriteStatus(id, true)

        assertFalse(dividerWrapper.showDivider)
        val inOrder = inOrder(adapter)
        inOrder.verify(adapter).notifyItemChanged(model.elements.size - 1)
        inOrder.verify(adapter).notifyItemChanged(model.elements.size - 2)

        verify(callback).onFirstChange()
    }

    @Test
    fun testAddFirstFavorite_dividerNotShowsNone() {
        INITIAL_FAVORITES.forEach {
            model.changeFavoriteStatus(it.controlId, false)
        }

        verify(callback).onNoneChanged(true)

        model.changeFavoriteStatus("${ID_PREFIX}3", true)
        assertEquals(1, getDividerPosition())

        verify(callback).onNoneChanged(false)

        verify(callback).onFirstChange()
    }

    @Test
    fun testMoveBetweenFavorites() {
        val from = 2
        val to = 4

        model.onMoveItem(from, to)
        assertEquals(
                listOf(0, 1, 3, 4, 2, 5).map { "$ID_PREFIX$it" },
                model.favorites.map(ControlInfo::controlId)
        )
        verify(adapter).notifyItemMoved(from, to)
        verify(adapter, never()).notifyItemChanged(anyInt(), any(Any::class.java))

        verify(callback).onFirstChange()
    }

    @Test
    fun testCacheCalledWhenGettingCustomIcon() {
        val wrapper = model.elements[0] as ControlInfoWrapper
        wrapper.customIcon

        verify(customIconCache).retrieve(TEST_COMPONENT, wrapper.controlId)
    }

    private fun getDividerPosition(): Int = model.elements.indexOf(dividerWrapper)
}
