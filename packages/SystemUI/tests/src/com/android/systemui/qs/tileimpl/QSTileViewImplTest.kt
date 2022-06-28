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

import android.content.Context
import android.graphics.drawable.Drawable
import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
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
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class QSTileViewImplTest : SysuiTestCase() {

    @Mock
    private lateinit var iconView: QSIconView
    @Mock
    private lateinit var customDrawable: Drawable

    private lateinit var tileView: FakeTileView
    private lateinit var customDrawableView: View
    private lateinit var chevronView: View

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.ensureTestableResources()

        tileView = FakeTileView(context, iconView, false)
        customDrawableView = tileView.requireViewById(R.id.customDrawable)
        chevronView = tileView.requireViewById(R.id.chevron)
    }

    @Test
    fun testSecondaryLabelNotModified_unavailable() {
        val state = QSTile.State()
        val testString = "TEST STRING"
        state.state = Tile.STATE_UNAVAILABLE
        state.secondaryLabel = testString

        tileView.changeState(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(testString)
    }

    @Test
    fun testSecondaryLabelNotModified_booleanInactive() {
        val state = QSTile.BooleanState()
        val testString = "TEST STRING"
        state.state = Tile.STATE_INACTIVE
        state.secondaryLabel = testString

        tileView.changeState(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(testString)
    }

    @Test
    fun testSecondaryLabelNotModified_booleanActive() {
        val state = QSTile.BooleanState()
        val testString = "TEST STRING"
        state.state = Tile.STATE_ACTIVE
        state.secondaryLabel = testString

        tileView.changeState(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(testString)
    }

    @Test
    fun testSecondaryLabelNotModified_availableNotBoolean_inactive() {
        val state = QSTile.State()
        state.state = Tile.STATE_INACTIVE
        state.secondaryLabel = ""

        tileView.changeState(state)

        assertThat(TextUtils.isEmpty(state.secondaryLabel)).isTrue()
    }

    @Test
    fun testSecondaryLabelNotModified_availableNotBoolean_active() {
        val state = QSTile.State()
        state.state = Tile.STATE_ACTIVE
        state.secondaryLabel = ""

        tileView.changeState(state)

        assertThat(TextUtils.isEmpty(state.secondaryLabel)).isTrue()
    }

    @Test
    fun testSecondaryLabelDescription_unavailable_default() {
        val state = QSTile.State()
        state.state = Tile.STATE_UNAVAILABLE
        state.secondaryLabel = ""

        tileView.changeState(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(
            context.getString(R.string.tile_unavailable)
        )
    }

    @Test
    fun testSecondaryLabelDescription_booleanInactive_default() {
        val state = QSTile.BooleanState()
        state.state = Tile.STATE_INACTIVE
        state.secondaryLabel = ""

        tileView.changeState(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(
            context.getString(R.string.switch_bar_off)
        )
    }

    @Test
    fun testSecondaryLabelDescription_booleanActive_default() {
        val state = QSTile.BooleanState()
        state.state = Tile.STATE_ACTIVE
        state.secondaryLabel = ""

        tileView.changeState(state)

        assertThat(state.secondaryLabel as CharSequence).isEqualTo(
            context.getString(R.string.switch_bar_on)
        )
    }

    @Test
    fun testShowCustomDrawableViewBooleanState() {
        val state = QSTile.BooleanState()
        state.sideViewCustomDrawable = customDrawable

        tileView.changeState(state)

        assertThat(customDrawableView.visibility).isEqualTo(View.VISIBLE)
        assertThat(chevronView.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testShowCustomDrawableViewNonBooleanState() {
        val state = QSTile.State()
        state.sideViewCustomDrawable = customDrawable

        tileView.changeState(state)

        assertThat(customDrawableView.visibility).isEqualTo(View.VISIBLE)
        assertThat(chevronView.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testShowCustomDrawableViewBooleanStateForceChevron() {
        val state = QSTile.BooleanState()
        state.sideViewCustomDrawable = customDrawable
        state.forceExpandIcon = true

        tileView.changeState(state)

        assertThat(customDrawableView.visibility).isEqualTo(View.VISIBLE)
        assertThat(chevronView.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testShowChevronNonBooleanState() {
        val state = QSTile.State()

        tileView.changeState(state)

        assertThat(customDrawableView.visibility).isEqualTo(View.GONE)
        assertThat(chevronView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testShowChevronBooleanStateForcheShow() {
        val state = QSTile.BooleanState()
        state.forceExpandIcon = true

        tileView.changeState(state)

        assertThat(customDrawableView.visibility).isEqualTo(View.GONE)
        assertThat(chevronView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testNoImageShown() {
        val state = QSTile.BooleanState()

        tileView.changeState(state)

        assertThat(customDrawableView.visibility).isEqualTo(View.GONE)
        assertThat(chevronView.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testUseStateStringsForKnownSpec_Boolean() {
        val state = QSTile.BooleanState()
        val spec = "internet"
        state.spec = spec

        val unavailableString = "${spec}_unavailable"
        val offString = "${spec}_off"
        val onString = "${spec}_on"

        context.orCreateTestableResources.addOverride(R.array.tile_states_internet, arrayOf(
            unavailableString,
            offString,
            onString
        ))

        // State UNAVAILABLE
        state.secondaryLabel = ""
        state.state = Tile.STATE_UNAVAILABLE
        tileView.changeState(state)
        assertThat((tileView.secondaryLabel as TextView).text).isEqualTo(unavailableString)

        // State INACTIVE
        state.secondaryLabel = ""
        state.state = Tile.STATE_INACTIVE
        tileView.changeState(state)
        assertThat((tileView.secondaryLabel as TextView).text).isEqualTo(offString)

        // State ACTIVE
        state.secondaryLabel = ""
        state.state = Tile.STATE_ACTIVE
        tileView.changeState(state)
        assertThat((tileView.secondaryLabel as TextView).text).isEqualTo(onString)
    }

    @Test
    fun testCollectionItemInfoHasPosition() {
        val position = 5
        tileView.setPosition(position)

        val info = AccessibilityNodeInfo(tileView)
        tileView.onInitializeAccessibilityNodeInfo(info)

        assertThat(info.collectionItemInfo.rowIndex).isEqualTo(position)
        assertThat(info.collectionItemInfo.rowSpan).isEqualTo(1)
        assertThat(info.collectionItemInfo.columnIndex).isEqualTo(0)
        assertThat(info.collectionItemInfo.columnSpan).isEqualTo(1)
    }

    @Test
    fun testCollectionItemInfoNoPosition() {
        val info = AccessibilityNodeInfo(tileView)
        tileView.onInitializeAccessibilityNodeInfo(info)

        assertThat(info.collectionItemInfo).isNull()
    }

    class FakeTileView(
        context: Context,
        icon: QSIconView,
        collapsed: Boolean
    ) : QSTileViewImpl(context, icon, collapsed) {
        fun changeState(state: QSTile.State) {
            handleStateChanged(state)
        }
    }
}