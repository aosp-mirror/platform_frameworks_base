/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack

import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.view.View.VISIBLE
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.nullable
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationStackSizeCalculatorTest : SysuiTestCase() {

    @Mock private lateinit var sysuiStatusBarStateController: SysuiStatusBarStateController

    @Mock private lateinit var stackLayout: NotificationStackScrollLayout

    private val testableResources = mContext.getOrCreateTestableResources()

    private lateinit var sizeCalculator: NotificationStackSizeCalculator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(stackLayout.calculateGapHeight(nullable(), nullable(), any()))
            .thenReturn(GAP_HEIGHT)
        with(testableResources) {
            addOverride(R.integer.keyguard_max_notification_count, -1)
            addOverride(R.dimen.notification_divider_height, DIVIDER_HEIGHT.toInt())
        }

        sizeCalculator =
            NotificationStackSizeCalculator(
                statusBarStateController = sysuiStatusBarStateController,
                testableResources.resources)
    }

    @Test
    fun computeMaxKeyguardNotifications_zeroSpace_returnZero() {
        val rows = listOf(createMockRow(height = ROW_HEIGHT))

        val maxNotifications =
            computeMaxKeyguardNotifications(rows, availableSpace = 0f, shelfHeight = 0f)

        assertThat(maxNotifications).isEqualTo(0)
    }

    @Test
    fun computeMaxKeyguardNotifications_infiniteSpace_returnsAll() {
        val numberOfRows = 30
        val rows = createLockscreenRows(numberOfRows)

        val maxNotifications = computeMaxKeyguardNotifications(rows, Float.MAX_VALUE)

        assertThat(maxNotifications).isEqualTo(numberOfRows)
    }

    @Test
    fun computeMaxKeyguardNotifications_spaceForOne_returnsOne() {
        val rowHeight = ROW_HEIGHT
        val totalSpaceForEachRow = GAP_HEIGHT + rowHeight
        val shelfHeight =
            totalSpaceForEachRow / 2 // In this way shelf absence will not leave room for another.
        val spaceForOne = totalSpaceForEachRow
        val rows =
            listOf(
                createMockRow(rowHeight),
                createMockRow(rowHeight))

        val maxNotifications =
            computeMaxKeyguardNotifications(
                rows, availableSpace = spaceForOne, shelfHeight = shelfHeight)

        assertThat(maxNotifications).isEqualTo(1)
    }

    @Test
    fun computeMaxKeyguardNotifications_spaceForOne_shelfUsableForLastNotification_returnsTwo() {
        val rowHeight = ROW_HEIGHT
        val totalSpaceForEachRow = GAP_HEIGHT + rowHeight
        val shelfHeight = totalSpaceForEachRow + DIVIDER_HEIGHT
        val spaceForOne = totalSpaceForEachRow
        val rows =
            listOf(
                createMockRow(rowHeight),
                createMockRow(rowHeight))

        val maxNotifications =
            computeMaxKeyguardNotifications(
                rows, availableSpace = spaceForOne, shelfHeight = shelfHeight)

        assertThat(maxNotifications).isEqualTo(1)
    }

    @Test
    fun computeMaxKeyguardNotifications_spaceForTwo_returnsTwo() {
        val rowHeight = ROW_HEIGHT
        val totalSpaceForEachRow = GAP_HEIGHT + rowHeight
        val spaceForTwo = totalSpaceForEachRow * 2 + DIVIDER_HEIGHT
        val rows =
            listOf(
                createMockRow(rowHeight),
                createMockRow(rowHeight),
                createMockRow(rowHeight))

        val maxNotifications = computeMaxKeyguardNotifications(rows, spaceForTwo, shelfHeight = 0f)

        assertThat(maxNotifications).isEqualTo(2)
    }

    @Test
    fun computeHeight_returnsAtMostSpaceAvailable_withGapBeforeShelf() {
        val rowHeight = ROW_HEIGHT
        val shelfHeight = SHELF_HEIGHT
        val totalSpaceForEachRow = GAP_HEIGHT + rowHeight + DIVIDER_HEIGHT
        val availableSpace = totalSpaceForEachRow * 2

        // All rows in separate sections (default setup).
        val rows =
            listOf(
                createMockRow(rowHeight),
                createMockRow(rowHeight),
                createMockRow(rowHeight))

        val maxNotifications = computeMaxKeyguardNotifications(rows, availableSpace, shelfHeight)
        assertThat(maxNotifications).isEqualTo(2)

        val height = sizeCalculator.computeHeight(stackLayout, maxNotifications, SHELF_HEIGHT)
        assertThat(height).isAtMost(availableSpace + GAP_HEIGHT + SHELF_HEIGHT)
    }

    @Test
    fun computeHeight_returnsAtMostSpaceAvailable_noGapBeforeShelf() {
        val rowHeight = ROW_HEIGHT
        val shelfHeight = SHELF_HEIGHT
        val totalSpaceForEachRow = GAP_HEIGHT + rowHeight + DIVIDER_HEIGHT
        val availableSpace = totalSpaceForEachRow * 1

        // Both rows are in the same section.
        whenever(stackLayout.calculateGapHeight(nullable(), nullable(), any()))
                .thenReturn(0f)
        val rows =
                listOf(
                        createMockRow(rowHeight),
                        createMockRow(rowHeight))

        val maxNotifications = computeMaxKeyguardNotifications(rows, availableSpace, shelfHeight)
        assertThat(maxNotifications).isEqualTo(1)

        val height = sizeCalculator.computeHeight(stackLayout, maxNotifications, SHELF_HEIGHT)
        assertThat(height).isAtMost(availableSpace + SHELF_HEIGHT)
    }

    private fun computeMaxKeyguardNotifications(
        rows: List<ExpandableView>,
        availableSpace: Float,
        shelfHeight: Float = SHELF_HEIGHT
    ): Int {
        setupChildren(rows)
        return sizeCalculator.computeMaxKeyguardNotifications(
            stackLayout, availableSpace, shelfHeight)
    }

    private fun setupChildren(children: List<ExpandableView>) {
        whenever(stackLayout.getChildAt(any())).thenAnswer { invocation ->
            val inx = invocation.getArgument<Int>(0)
            return@thenAnswer children[inx]
        }
        whenever(stackLayout.childCount).thenReturn(children.size)
    }

    private fun createLockscreenRows(number: Int): List<ExpandableNotificationRow> =
        (1..number).map { createMockRow() }.toList()

    private fun createMockRow(
        height: Float = ROW_HEIGHT,
        isRemoved: Boolean = false,
        visibility: Int = VISIBLE,
    ): ExpandableNotificationRow {
        val row = mock(ExpandableNotificationRow::class.java)
        val entry = mock(NotificationEntry::class.java)
        val sbn = mock(StatusBarNotification::class.java)
        whenever(entry.sbn).thenReturn(sbn)
        whenever(row.entry).thenReturn(entry)
        whenever(row.isRemoved).thenReturn(isRemoved)
        whenever(row.visibility).thenReturn(visibility)
        whenever(row.getMinHeight(any())).thenReturn(height.toInt())
        whenever(row.intrinsicHeight).thenReturn(height.toInt())
        return row
    }

    /** Default dimensions for tests that don't overwrite them. */
    companion object {
        const val GAP_HEIGHT = 12f
        const val DIVIDER_HEIGHT = 3f
        const val SHELF_HEIGHT = 14f
        const val ROW_HEIGHT = SHELF_HEIGHT * 3
    }
}
