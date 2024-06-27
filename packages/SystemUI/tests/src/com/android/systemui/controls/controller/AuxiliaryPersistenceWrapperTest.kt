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

package com.android.systemui.controls.controller

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.File

@SmallTest
@RunWith(AndroidJUnit4::class)
class AuxiliaryPersistenceWrapperTest : SysuiTestCase() {

    companion object {
        fun <T> any(): T = Mockito.any()
        private val TEST_COMPONENT = ComponentName.unflattenFromString("test_pkg/.test_cls")!!
        private val TEST_COMPONENT_OTHER =
            ComponentName.unflattenFromString("test_pkg/.test_other")!!
    }

    @Mock
    private lateinit var persistenceWrapper: ControlsFavoritePersistenceWrapper
    @Mock
    private lateinit var structure1: StructureInfo
    @Mock
    private lateinit var structure2: StructureInfo
    @Mock
    private lateinit var structure3: StructureInfo

    private lateinit var auxiliaryFileWrapper: AuxiliaryPersistenceWrapper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(structure1.componentName).thenReturn(TEST_COMPONENT)
        `when`(structure2.componentName).thenReturn(TEST_COMPONENT_OTHER)
        `when`(structure3.componentName).thenReturn(TEST_COMPONENT)

        `when`(persistenceWrapper.fileExists).thenReturn(true)
        `when`(persistenceWrapper.readFavorites()).thenReturn(
            listOf(structure1, structure2, structure3))

        auxiliaryFileWrapper = AuxiliaryPersistenceWrapper(persistenceWrapper)
    }

    @Test
    fun testInitialStructures() {
        val expected = listOf(structure1, structure2, structure3)
        assertEquals(expected, auxiliaryFileWrapper.favorites)
    }

    @Test
    fun testInitialize_fileDoesNotExist() {
        `when`(persistenceWrapper.fileExists).thenReturn(false)
        auxiliaryFileWrapper.initialize()
        assertTrue(auxiliaryFileWrapper.favorites.isEmpty())
    }

    @Test
    fun testGetCachedValues_component() {
        val cached = auxiliaryFileWrapper.getCachedFavoritesAndRemoveFor(TEST_COMPONENT)
        val expected = listOf(structure1, structure3)

        assertEquals(expected, cached)
    }

    @Test
    fun testGetCachedValues_componentOther() {
        val cached = auxiliaryFileWrapper.getCachedFavoritesAndRemoveFor(TEST_COMPONENT_OTHER)
        val expected = listOf(structure2)

        assertEquals(expected, cached)
    }

    @Test
    fun testGetCachedValues_component_removed() {
        auxiliaryFileWrapper.getCachedFavoritesAndRemoveFor(TEST_COMPONENT)
        verify(persistenceWrapper).storeFavorites(listOf(structure2))
    }

    @Test
    fun testChangeFile() {
        auxiliaryFileWrapper.changeFile(mock(File::class.java))
        val inOrder = inOrder(persistenceWrapper)
        inOrder.verify(persistenceWrapper).changeFileAndBackupManager(
                any(), ArgumentMatchers.isNull())
        inOrder.verify(persistenceWrapper).readFavorites()
    }

    @Test
    fun testFileRemoved() {
        `when`(persistenceWrapper.fileExists).thenReturn(false)

        assertEquals(emptyList<StructureInfo>(),
            auxiliaryFileWrapper.getCachedFavoritesAndRemoveFor(TEST_COMPONENT))
        assertEquals(emptyList<StructureInfo>(),
            auxiliaryFileWrapper.getCachedFavoritesAndRemoveFor(TEST_COMPONENT_OTHER))

        verify(persistenceWrapper, never()).storeFavorites(ArgumentMatchers.anyList())
    }
}
