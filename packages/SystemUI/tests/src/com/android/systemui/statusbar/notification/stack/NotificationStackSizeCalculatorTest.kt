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
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy
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
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationStackSizeCalculatorTest : SysuiTestCase() {

    @Mock private lateinit var groupManager: NotificationGroupManagerLegacy

    @Mock private lateinit var notificationLockscreenUserManager: NotificationLockscreenUserManager

    @Mock private lateinit var sysuiStatusBarStateController: SysuiStatusBarStateController

    @Mock private lateinit var stackLayout: NotificationStackScrollLayout

    private val testableResources = mContext.getOrCreateTestableResources()

    private lateinit var sizeCalculator: NotificationStackSizeCalculator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(stackLayout.calculateGapHeight(nullable(), nullable(), any()))
            .thenReturn(GAP_HEIGHT)
        whenever(groupManager.isSummaryOfSuppressedGroup(any())).thenReturn(false)
        with(testableResources) {
            addOverride(R.integer.keyguard_max_notification_count, -1)
            addOverride(R.dimen.notification_divider_height, NOTIFICATION_PADDING.toInt())
        }

        sizeCalculator =
            NotificationStackSizeCalculator(
                groupManager = groupManager,
                lockscreenUserManager = notificationLockscreenUserManager,
                statusBarStateController = sysuiStatusBarStateController,
                testableResources.resources)
    }

    @Test
    fun computeMaxKeyguardNotifications_zeroSpace_returnZero() {
        val rows = listOf(createMockRow(height = ROW_HEIGHT, visibleOnLockscreen = true))

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
                createMockRow(rowHeight, visibleOnLockscreen = true),
                createMockRow(rowHeight, visibleOnLockscreen = true))

        val maxNotifications =
            computeMaxKeyguardNotifications(
                rows, availableSpace = spaceForOne, shelfHeight = shelfHeight)

        assertThat(maxNotifications).isEqualTo(1)
    }

    @Test
    fun computeMaxKeyguardNotifications_spaceForOne_shelfUsableForLastNotification_returnsTwo() {
        val rowHeight = ROW_HEIGHT
        val totalSpaceForEachRow = GAP_HEIGHT + rowHeight
        val shelfHeight = totalSpaceForEachRow + NOTIFICATION_PADDING
        val spaceForOne = totalSpaceForEachRow
        val rows =
            listOf(
                createMockRow(rowHeight, visibleOnLockscreen = true),
                createMockRow(rowHeight, visibleOnLockscreen = true))

        val maxNotifications =
            computeMaxKeyguardNotifications(
                rows, availableSpace = spaceForOne, shelfHeight = shelfHeight)

        assertThat(maxNotifications).isEqualTo(1)
    }

    @Test
    fun computeMaxKeyguardNotifications_invisibleOnLockscreen_returnsZero() {
        val rows = listOf(createMockRow(visibleOnLockscreen = false))

        val maxNotifications = computeMaxKeyguardNotifications(rows, Float.MAX_VALUE)

        assertThat(maxNotifications).isEqualTo(0)
    }

    @Test
    fun computeMaxKeyguardNotifications_spaceForTwo_returnsTwo() {
        val rowHeight = ROW_HEIGHT
        val totalSpaceForEachRow = GAP_HEIGHT + rowHeight
        val spaceForTwo = totalSpaceForEachRow * 2 + NOTIFICATION_PADDING
        val rows =
            listOf(
                createMockRow(rowHeight, visibleOnLockscreen = true),
                createMockRow(rowHeight, visibleOnLockscreen = true),
                createMockRow(rowHeight, visibleOnLockscreen = true))

        val maxNotifications = computeMaxKeyguardNotifications(rows, spaceForTwo, shelfHeight = 0f)

        assertThat(maxNotifications).isEqualTo(2)
    }

    @Test
    fun computeHeight_returnsLessThanAvailableSpaceUsedToCalculateMaxNotifications() {
        val rowHeight = ROW_HEIGHT
        val shelfHeight = SHELF_HEIGHT
        val totalSpaceForEachRow = GAP_HEIGHT + rowHeight + NOTIFICATION_PADDING
        val availableSpace = totalSpaceForEachRow * 2
        val rows =
            listOf(
                createMockRow(rowHeight, visibleOnLockscreen = true),
                createMockRow(rowHeight, visibleOnLockscreen = true),
                createMockRow(rowHeight, visibleOnLockscreen = true))

        val maxNotifications = computeMaxKeyguardNotifications(rows, availableSpace, shelfHeight)
        assertThat(maxNotifications).isEqualTo(2)

        val height = sizeCalculator.computeHeight(stackLayout, maxNotifications, SHELF_HEIGHT)
        assertThat(height).isAtMost(availableSpace + SHELF_HEIGHT)
    }

    @Test
    fun computeHeight_allInvisibleToLockscreen_NotInLockscreen_returnsHigherThanZero() {
        setOnLockscreen(false)
        val rowHeight = 10f
        setupChildren(listOf(createMockRow(rowHeight, visibleOnLockscreen = false)))

        val height =
            sizeCalculator.computeHeight(
                stackLayout, maxNotifications = Int.MAX_VALUE, SHELF_HEIGHT)

        assertThat(height).isGreaterThan(rowHeight)
    }

    @Test
    fun computeHeight_allInvisibleToLockscreen_onLockscreen_returnsZero() {
        setOnLockscreen(true)
        setupChildren(listOf(createMockRow(visibleOnLockscreen = false)))

        val height =
            sizeCalculator.computeHeight(
                stackLayout, maxNotifications = Int.MAX_VALUE, SHELF_HEIGHT)

        assertThat(height).isEqualTo(0)
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
        (1..number).map { createMockRow(visibleOnLockscreen = true) }.toList()

    private fun createMockRow(
        height: Float = ROW_HEIGHT,
        visibleOnLockscreen: Boolean = true,
        isRemoved: Boolean = false,
        visibility: Int = VISIBLE,
        summaryOfSuppressed: Boolean = false
    ): ExpandableNotificationRow {
        val row = mock(ExpandableNotificationRow::class.java)
        val entry = mock(NotificationEntry::class.java)
        val sbn = mock(StatusBarNotification::class.java)
        whenever(entry.sbn).thenReturn(sbn)
        whenever(row.entry).thenReturn(entry)
        whenever(row.isRemoved).thenReturn(isRemoved)
        whenever(row.visibility).thenReturn(visibility)
        whenever(notificationLockscreenUserManager.shouldShowOnKeyguard(entry))
            .thenReturn(visibleOnLockscreen)
        whenever(groupManager.isSummaryOfSuppressedGroup(sbn)).thenReturn(summaryOfSuppressed)
        whenever(row.getMinHeight(any())).thenReturn(height.toInt())
        whenever(row.intrinsicHeight).thenReturn(height.toInt())
        return row
    }

    private fun setOnLockscreen(onLockscreen: Boolean) {
        whenever(sysuiStatusBarStateController.state)
            .thenReturn(
                if (onLockscreen) {
                    KEYGUARD
                } else {
                    SHADE
                })
    }

    /** Default dimensions for tests that don't overwrite them. */
    companion object {
        const val GAP_HEIGHT = 12f
        const val NOTIFICATION_PADDING = 3f
        const val SHELF_HEIGHT = 14f
        const val ROW_HEIGHT = SHELF_HEIGHT * 3
    }
}
