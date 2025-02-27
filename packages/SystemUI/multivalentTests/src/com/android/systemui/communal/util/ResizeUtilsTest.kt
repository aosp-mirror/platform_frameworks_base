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

package com.android.systemui.communal.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.util.ResizeUtils.resizeOngoingItems
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class ResizeUtilsTest : SysuiTestCase() {
    private val mockWidget =
        mock<CommunalContentModel.WidgetContent.Widget> {
            on { size } doReturn CommunalContentSize.Responsive(1)
        }

    @Test
    fun noOngoingContent() {
        val list = listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 2)

        assertThat(resized).containsExactly(mockWidget)
    }

    @Test
    fun singleOngoingContent_singleRowGrid() {
        val list = createOngoingListWithSize(1) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 1)

        assertThat(resized.map { it.size })
            .containsExactly(CommunalContentSize.Responsive(1), mockWidget.size)
            .inOrder()
    }

    @Test
    fun singleOngoingContent_twoRowGrid() {
        val list = createOngoingListWithSize(1) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 2)

        assertThat(resized.map { it.size })
            .containsExactly(CommunalContentSize.Responsive(2), mockWidget.size)
            .inOrder()
    }

    @Test
    fun singleOngoingContent_threeRowGrid() {
        val list = createOngoingListWithSize(1) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 3)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(2),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
        // A spacer should be added as the second element to avoid mixing widget content
        // with ongoing content.
        assertThat(resized[1]).isInstanceOf(CommunalContentModel.Spacer::class.java)
    }

    @Test
    fun twoOngoingContent_singleRowGrid() {
        val list = createOngoingListWithSize(2) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 1)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    @Test
    fun twoOngoingContent_twoRowGrid() {
        val list = createOngoingListWithSize(2) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 2)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    @Test
    fun twoOngoingContent_threeRowGrid() {
        val list = createOngoingListWithSize(2) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 3)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(2),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    @Test
    fun threeOngoingContent_singleRowGrid() {
        val list = createOngoingListWithSize(3) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 1)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    @Test
    fun threeOngoingContent_twoRowGrid() {
        val list = createOngoingListWithSize(3) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 2)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(2),
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    @Test
    fun threeOngoingContent_threeRowGrid() {
        val list = createOngoingListWithSize(3) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 3)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    @Test
    fun fourOngoingContent_singleRowGrid() {
        val list = createOngoingListWithSize(4) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 1)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    @Test
    fun fourOngoingContent_twoRowGrid() {
        val list = createOngoingListWithSize(4) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 2)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    @Test
    fun fourOngoingContent_threeRowGrid() {
        val list = createOngoingListWithSize(4) + listOf(mockWidget)
        val resized = resizeOngoingItems(list = list, numRows = 3)

        assertThat(resized.map { it.size })
            .containsExactly(
                CommunalContentSize.Responsive(2),
                CommunalContentSize.Responsive(1),
                CommunalContentSize.Responsive(2),
                CommunalContentSize.Responsive(1),
                mockWidget.size,
            )
            .inOrder()
    }

    private fun createOngoingListWithSize(size: Int): List<CommunalContentModel.Ongoing> {
        return List(size) { CommunalContentModel.Umo(createdTimestampMillis = 100) }
    }
}
