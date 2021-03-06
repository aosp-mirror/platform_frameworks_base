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

package com.android.systemui.qs.tileimpl

import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import android.text.TextUtils
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class QSTileBaseViewTest : SysuiTestCase() {

    @Mock
    private lateinit var iconView: QSIconView

    private lateinit var tileView: QSTileBaseView

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        tileView = QSTileBaseView(context, iconView, false)
    }

    @Test
    fun testSecondaryLabelNotModified_unavailable() {
        val state = QSTile.State()
        val testString = "TEST STRING"
        state.state = Tile.STATE_UNAVAILABLE
        state.secondaryLabel = testString

        tileView.handleStateChanged(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(testString)
    }

    @Test
    fun testSecondaryLabelNotModified_booleanInactive() {
        val state = QSTile.BooleanState()
        val testString = "TEST STRING"
        state.state = Tile.STATE_INACTIVE
        state.secondaryLabel = testString

        tileView.handleStateChanged(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(testString)
    }

    @Test
    fun testSecondaryLabelNotModified_booleanActive() {
        val state = QSTile.BooleanState()
        val testString = "TEST STRING"
        state.state = Tile.STATE_ACTIVE
        state.secondaryLabel = testString

        tileView.handleStateChanged(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(testString)
    }

    @Test
    fun testSecondaryLabelNotModified_availableNotBoolean_inactive() {
        val state = QSTile.State()
        state.state = Tile.STATE_INACTIVE
        state.secondaryLabel = ""

        tileView.handleStateChanged(state)

        assertThat(TextUtils.isEmpty(state.secondaryLabel)).isTrue()
    }

    @Test
    fun testSecondaryLabelNotModified_availableNotBoolean_active() {
        val state = QSTile.State()
        state.state = Tile.STATE_ACTIVE
        state.secondaryLabel = ""

        tileView.handleStateChanged(state)

        assertThat(TextUtils.isEmpty(state.secondaryLabel)).isTrue()
    }

    @Test
    fun testSecondaryLabelDescription_unavailable() {
        val state = QSTile.State()
        state.state = Tile.STATE_UNAVAILABLE
        state.secondaryLabel = ""

        tileView.handleStateChanged(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(
            context.getString(R.string.tile_unavailable)
        )
    }

    @Test
    fun testSecondaryLabelDescription_booleanInactive() {
        val state = QSTile.BooleanState()
        state.state = Tile.STATE_INACTIVE
        state.secondaryLabel = ""

        tileView.handleStateChanged(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(
            context.getString(R.string.switch_bar_off)
        )
    }

    @Test
    fun testSecondaryLabelDescription_booleanActive() {
        val state = QSTile.BooleanState()
        state.state = Tile.STATE_ACTIVE
        state.secondaryLabel = ""

        tileView.handleStateChanged(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(
            context.getString(R.string.switch_bar_on)
        )
    }
}