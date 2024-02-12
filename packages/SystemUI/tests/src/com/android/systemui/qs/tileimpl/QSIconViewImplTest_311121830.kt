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

package com.android.systemui.qs.tileimpl

import android.animation.AnimatorTestRule
import android.content.Context
import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import android.testing.UiThreadTest
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.WifiIcons
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Test for regression b/311121830 and b/323125376 */
@RunWith(AndroidTestingRunner::class)
@UiThreadTest
@SmallTest
class QSIconViewImplTest_311121830 : SysuiTestCase() {

    @get:Rule val animatorRule = AnimatorTestRule(this)

    @Test
    fun alwaysLastIcon() {
        // Need to inflate with the correct theme so the colors can be retrieved and the animations
        // are run
        val iconView =
            AnimateQSIconViewImpl(
                ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings)
            )

        val initialState =
            QSTile.State().apply {
                state = Tile.STATE_INACTIVE
                icon = QSTileImpl.ResourceIcon.get(R.drawable.ic_qs_no_internet_available)
            }
        val firstState =
            QSTile.State().apply {
                state = Tile.STATE_ACTIVE
                icon = QSTileImpl.ResourceIcon.get(WifiIcons.WIFI_NO_INTERNET_ICONS[4])
            }
        val secondState =
            QSTile.State().apply {
                state = Tile.STATE_ACTIVE
                icon = QSTileImpl.ResourceIcon.get(WifiIcons.WIFI_FULL_ICONS[4])
            }

        // Start with the initial state
        iconView.setIcon(initialState, /* allowAnimations= */ false)

        // Set the first state to animate, and advance time to half the time of the animation
        iconView.setIcon(firstState, /* allowAnimations= */ true)
        animatorRule.advanceTimeBy(QSIconViewImpl.QS_ANIM_LENGTH / 2)

        // Set the second state to animate (it shouldn't, because `State.state` is the same) and
        // advance time to 2 animations length
        iconView.setIcon(secondState, /* allowAnimations= */ true)
        animatorRule.advanceTimeBy(QSIconViewImpl.QS_ANIM_LENGTH * 2)

        assertThat(iconView.mLastIcon).isEqualTo(secondState.icon)
    }

    @Test
    fun alwaysLastIcon_twoStateChanges() {
        // Need to inflate with the correct theme so the colors can be retrieved and the animations
        // are run
        val iconView =
            AnimateQSIconViewImpl(
                ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings)
            )

        val initialState =
            QSTile.State().apply {
                state = Tile.STATE_ACTIVE
                icon = QSTileImpl.ResourceIcon.get(WifiIcons.WIFI_FULL_ICONS[4])
            }
        val firstState =
            QSTile.State().apply {
                state = Tile.STATE_INACTIVE
                icon = QSTileImpl.ResourceIcon.get(WifiIcons.WIFI_NO_INTERNET_ICONS[4])
            }
        val secondState =
            QSTile.State().apply {
                state = Tile.STATE_ACTIVE
                icon = QSTileImpl.ResourceIcon.get(WifiIcons.WIFI_FULL_ICONS[3])
            }
        val thirdState =
            QSTile.State().apply {
                state = Tile.STATE_INACTIVE
                icon = QSTileImpl.ResourceIcon.get(WifiIcons.WIFI_NO_NETWORK)
            }

        // Start with the initial state
        iconView.setIcon(initialState, /* allowAnimations= */ false)

        // Set the first state to animate, and advance time to one third of the animation
        iconView.setIcon(firstState, /* allowAnimations= */ true)
        animatorRule.advanceTimeBy(QSIconViewImpl.QS_ANIM_LENGTH / 3)

        // Set the second state to animate and advance time by another third of animations length
        iconView.setIcon(secondState, /* allowAnimations= */ true)
        animatorRule.advanceTimeBy(QSIconViewImpl.QS_ANIM_LENGTH / 3)

        // Set the third state to animate and advance time by two times the animation length
        // to guarantee that all animations are done
        iconView.setIcon(thirdState, /* allowAnimations= */ true)
        animatorRule.advanceTimeBy(QSIconViewImpl.QS_ANIM_LENGTH * 2)

        assertThat(iconView.mLastIcon).isEqualTo(thirdState.icon)
    }

    private class AnimateQSIconViewImpl(context: Context) : QSIconViewImpl(context) {
        override fun createIcon(): View {
            return object : ImageView(context) {
                override fun isShown(): Boolean {
                    return true
                }
            }
        }
    }
}
