/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.external

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class CustomTileStatePersisterTest : SysuiTestCase() {

    companion object {
        private val TEST_COMPONENT = ComponentName("pkg", "cls")
        private const val TEST_USER = 0
        private val KEY = TileServiceKey(TEST_COMPONENT, TEST_USER)

        private const val TEST_STATE = Tile.STATE_INACTIVE
        private const val TEST_LABEL = "test_label"
        private const val TEST_SUBTITLE = "test_subtitle"
        private const val TEST_CONTENT_DESCRIPTION = "test_content_description"
        private const val TEST_STATE_DESCRIPTION = "test_state_description"
        private const val TEST_DEFAULT_LABEL = "default_label"

        private fun Tile.isEqualTo(other: Tile): Boolean {
            return state == other.state &&
                    label == other.label &&
                    subtitle == other.subtitle &&
                    contentDescription == other.contentDescription &&
                    stateDescription == other.stateDescription
        }
    }

    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var sharedPreferences: SharedPreferences
    @Mock(answer = Answers.RETURNS_SELF)
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var tile: Tile
    private lateinit var customTileStatePersister: CustomTileStatePersister

    @Captor
    private lateinit var stringCaptor: ArgumentCaptor<String>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(mockContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(sharedPreferences)
        `when`(sharedPreferences.edit()).thenReturn(editor)

        tile = Tile()
        customTileStatePersister = CustomTileStatePersisterImpl(mockContext)
    }

    @Test
    fun testWriteState() {
        tile.apply {
            state = TEST_STATE
            label = TEST_LABEL
            subtitle = TEST_SUBTITLE
            contentDescription = TEST_CONTENT_DESCRIPTION
            stateDescription = TEST_STATE_DESCRIPTION
        }

        customTileStatePersister.persistState(KEY, tile)

        verify(editor).putString(eq(KEY.toString()), capture(stringCaptor))

        assertThat(tile.isEqualTo(readTileFromString(stringCaptor.value))).isTrue()
    }

    @Test
    fun testReadState() {
        tile.apply {
            state = TEST_STATE
            label = TEST_LABEL
            subtitle = TEST_SUBTITLE
            contentDescription = TEST_CONTENT_DESCRIPTION
            stateDescription = TEST_STATE_DESCRIPTION
        }

        `when`(sharedPreferences.getString(eq(KEY.toString()), any()))
                .thenReturn(writeToString(tile))

        assertThat(tile.isEqualTo(customTileStatePersister.readState(KEY)!!)).isTrue()
    }

    @Test
    fun testReadStateDefault() {
        `when`(sharedPreferences.getString(any(), any())).thenAnswer {
            it.getArgument(1)
        }

        assertThat(customTileStatePersister.readState(KEY)).isNull()
    }

    @Test
    fun testStoreNulls() {
        assertThat(tile.label).isNull()

        customTileStatePersister.persistState(KEY, tile)

        verify(editor).putString(eq(KEY.toString()), capture(stringCaptor))

        assertThat(readTileFromString(stringCaptor.value).label).isNull()
    }

    @Test
    fun testReadNulls() {
        assertThat(tile.label).isNull()

        `when`(sharedPreferences.getString(eq(KEY.toString()), any()))
                .thenReturn(writeToString(tile))

        assertThat(customTileStatePersister.readState(KEY)!!.label).isNull()
    }

    @Test
    fun testRemoveState() {
        customTileStatePersister.removeState(KEY)

        verify(editor).remove(KEY.toString())
    }

    @Test
    fun testWithDefaultLabel_notStored() {
        tile.setDefaultLabel(TEST_DEFAULT_LABEL)

        `when`(sharedPreferences.getString(eq(KEY.toString()), any()))
                .thenReturn(writeToString(tile))

        assertThat(customTileStatePersister.readState(KEY)!!.label).isNull()
    }
}