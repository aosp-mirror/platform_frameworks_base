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
package com.android.systemui.statusbar.phone

import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.StatusBarIconView
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

/**
 * Tests for {@link NotificationIconContainer}.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotificationIconContainerTest : SysuiTestCase() {

    private val iconContainer = NotificationIconContainer(context, /* attrs= */ null)

    @Test
    fun calculateWidthFor_zeroIcons_widthIsZero() {
        assertEquals(/* expected= */ iconContainer.calculateWidthFor(/* numIcons= */ 0f),
                /* actual= */ 0f)
    }

    @Test
    fun calculateWidthFor_oneIcon_widthForOneIcon() {
        iconContainer.setActualPaddingStart(10f)
        iconContainer.setActualPaddingEnd(10f)
        iconContainer.setIconSize(10);

        assertEquals(/* expected= */ iconContainer.calculateWidthFor(/* numIcons= */ 1f),
                /* actual= */ 30f)
    }

    @Test
    fun calculateWidthFor_fourIcons_widthForFourIcons() {
        iconContainer.setActualPaddingStart(10f)
        iconContainer.setActualPaddingEnd(10f)
        iconContainer.setIconSize(10);

        assertEquals(/* expected= */ iconContainer.calculateWidthFor(/* numIcons= */ 4f),
                /* actual= */ 60f)
    }

    @Test
    fun calculateWidthFor_fiveIcons_widthForFourIcons() {
        iconContainer.setActualPaddingStart(10f)
        iconContainer.setActualPaddingEnd(10f)
        iconContainer.setIconSize(10);
        assertEquals(/* expected= */ iconContainer.calculateWidthFor(/* numIcons= */ 5f),
                /* actual= */ 60f)
    }

    @Test
    fun calculateIconXTranslations_shortShelfOneIcon_atCorrectXWithoutOverflowDot() {
        iconContainer.setActualPaddingStart(10f)
        iconContainer.setActualPaddingEnd(10f)
        iconContainer.setIconSize(10);

        val icon = mockStatusBarIcon()
        iconContainer.addView(icon)
        assertEquals(1, iconContainer.childCount)

        val iconState = iconContainer.getIconState(icon)
        iconState.iconAppearAmount = 1f

        val width = iconContainer.calculateWidthFor(/* numIcons= */ 1f)
        iconContainer.setActualLayoutWidth(width.toInt())

        iconContainer.calculateIconXTranslations()
        assertEquals(10f, iconState.xTranslation)
        assertFalse(iconContainer.hasOverflow())
    }

    @Test
    fun calculateIconXTranslations_shortShelfFourIcons_atCorrectXWithoutOverflowDot() {
        iconContainer.setActualPaddingStart(10f)
        iconContainer.setActualPaddingEnd(10f)
        iconContainer.setIconSize(10);

        val iconOne = mockStatusBarIcon()
        val iconTwo = mockStatusBarIcon()
        val iconThree = mockStatusBarIcon()
        val iconFour = mockStatusBarIcon()

        iconContainer.addView(iconOne)
        iconContainer.addView(iconTwo)
        iconContainer.addView(iconThree)
        iconContainer.addView(iconFour)
        assertEquals(4, iconContainer.childCount)

        val width = iconContainer.calculateWidthFor(/* numIcons= */ 4f)
        iconContainer.setActualLayoutWidth(width.toInt())

        iconContainer.calculateIconXTranslations()
        assertEquals(10f, iconContainer.getIconState(iconOne).xTranslation)
        assertEquals(20f, iconContainer.getIconState(iconTwo).xTranslation)
        assertEquals(30f, iconContainer.getIconState(iconThree).xTranslation)
        assertEquals(40f, iconContainer.getIconState(iconFour).xTranslation)

        assertFalse(iconContainer.hasOverflow())
    }

    @Test
    fun calculateIconXTranslations_shortShelfFiveIcons_atCorrectXWithOverflowDot() {
        iconContainer.setActualPaddingStart(10f)
        iconContainer.setActualPaddingEnd(10f)
        iconContainer.setIconSize(10);

        val iconOne = mockStatusBarIcon()
        val iconTwo = mockStatusBarIcon()
        val iconThree = mockStatusBarIcon()
        val iconFour = mockStatusBarIcon()
        val iconFive = mockStatusBarIcon()

        iconContainer.addView(iconOne)
        iconContainer.addView(iconTwo)
        iconContainer.addView(iconThree)
        iconContainer.addView(iconFour)
        iconContainer.addView(iconFive)
        assertEquals(5, iconContainer.childCount)

        val width = iconContainer.calculateWidthFor(/* numIcons= */ 5f)
        iconContainer.setActualLayoutWidth(width.toInt())

        iconContainer.calculateIconXTranslations()
        assertEquals(10f, iconContainer.getIconState(iconOne).xTranslation)
        assertEquals(20f, iconContainer.getIconState(iconTwo).xTranslation)
        assertEquals(30f, iconContainer.getIconState(iconThree).xTranslation)
        assertTrue(iconContainer.hasOverflow())
    }

    @Test
    fun shouldForceOverflow_appearingAboveSpeedBump_true() {
        val forceOverflow = iconContainer.shouldForceOverflow(
                /* i= */ 1,
                /* speedBumpIndex= */ 0,
                /* iconAppearAmount= */ 1f,
                /* maxVisibleIcons= */ 5
        )
        assertTrue(forceOverflow);
    }

    @Test
    fun shouldForceOverflow_moreThanMaxVisible_true() {
        val forceOverflow = iconContainer.shouldForceOverflow(
                /* i= */ 10,
                /* speedBumpIndex= */ 11,
                /* iconAppearAmount= */ 0f,
                /* maxVisibleIcons= */ 5
        )
        assertTrue(forceOverflow);
    }

    @Test
    fun shouldForceOverflow_belowSpeedBumpAndLessThanMaxVisible_false() {
        val forceOverflow = iconContainer.shouldForceOverflow(
                /* i= */ 0,
                /* speedBumpIndex= */ 11,
                /* iconAppearAmount= */ 0f,
                /* maxVisibleIcons= */ 5
        )
        assertFalse(forceOverflow);
    }

    @Test
    fun isOverflowing_lastChildXLessThanLayoutEnd_false() {
        val isOverflowing = iconContainer.isOverflowing(
                /* isLastChild= */ true,
                /* translationX= */ 0f,
                /* layoutEnd= */ 10f,
                /* iconSize= */ 2f,
        )
        assertFalse(isOverflowing)
    }


    @Test
    fun isOverflowing_lastChildXEqualToLayoutEnd_true() {
        val isOverflowing = iconContainer.isOverflowing(
                /* isLastChild= */ true,
                /* translationX= */ 10f,
                /* layoutEnd= */ 10f,
                /* iconSize= */ 2f,
        )
        assertTrue(isOverflowing)
    }

    @Test
    fun isOverflowing_lastChildXGreaterThanLayoutEnd_true() {
        val isOverflowing = iconContainer.isOverflowing(
                /* isLastChild= */ true,
                /* translationX= */ 20f,
                /* layoutEnd= */ 10f,
                /* iconSize= */ 2f,
        )
        assertTrue(isOverflowing)
    }

    @Test
    fun isOverflowing_notLastChildXLessThanDotX_false() {
        val isOverflowing = iconContainer.isOverflowing(
                /* isLastChild= */ false,
                /* translationX= */ 0f,
                /* layoutEnd= */ 10f,
                /* iconSize= */ 2f,
        )
        assertFalse(isOverflowing)
    }

    @Test
    fun isOverflowing_notLastChildXGreaterThanDotX_true() {
        val isOverflowing = iconContainer.isOverflowing(
                /* isLastChild= */ false,
                /* translationX= */ 20f,
                /* layoutEnd= */ 10f,
                /* iconSize= */ 2f,
        )
        assertTrue(isOverflowing)
    }

    @Test
    fun isOverflowing_notLastChildXEqualToDotX_true() {
        val isOverflowing = iconContainer.isOverflowing(
                /* isLastChild= */ false,
                /* translationX= */ 8f,
                /* layoutEnd= */ 10f,
                /* iconSize= */ 2f,
        )
        assertTrue(isOverflowing)
    }

    private fun mockStatusBarIcon() : StatusBarIconView {
        val iconView = mock(StatusBarIconView::class.java)
        whenever(iconView.width).thenReturn(10)

        val icon = mock(android.graphics.drawable.Icon::class.java)
        whenever(iconView.sourceIcon).thenReturn(icon)

        val sbn = mock(StatusBarNotification::class.java)
        whenever(sbn.groupKey).thenReturn("groupKey")
        whenever(iconView.notification).thenReturn(sbn)
        return iconView
    }
}