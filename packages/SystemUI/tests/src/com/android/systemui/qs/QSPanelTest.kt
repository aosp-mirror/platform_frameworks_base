/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.qs

import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableContext
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.QSPanelControllerBase.TileRecord
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileViewImpl
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class QSPanelTest : SysuiTestCase() {

    @Mock private lateinit var qsLogger: QSLogger

    private lateinit var testableLooper: TestableLooper
    private lateinit var qsPanel: QSPanel

    private lateinit var footer: View

    private val themedContext = TestableContext(
            ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings)
    )

    @Before
    @Throws(Exception::class)
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        // Apply only the values of the theme that are not defined

        testableLooper.runWithLooper {
            qsPanel = QSPanel(themedContext, null)
            qsPanel.mUsingMediaPlayer = true

            qsPanel.initialize(qsLogger)
            // QSPanel inflates a footer inside of it, mocking it here
            footer = LinearLayout(themedContext).apply { id = R.id.qs_footer }
            qsPanel.addView(footer, MATCH_PARENT, 100)
            qsPanel.onFinishInflate()
            // Provides a parent with non-zero size for QSPanel
            ViewUtils.attachView(qsPanel)
        }
    }

    @After
    fun tearDown() {
        ViewUtils.detachView(qsPanel)
    }

    @Test
    fun testHasCollapseAccessibilityAction() {
        val info = AccessibilityNodeInfo(qsPanel)
        qsPanel.onInitializeAccessibilityNodeInfo(info)

        assertThat(info.actions and AccessibilityNodeInfo.ACTION_COLLAPSE).isNotEqualTo(0)
        assertThat(info.actions and AccessibilityNodeInfo.ACTION_EXPAND).isEqualTo(0)
    }

    @Test
    fun testCollapseActionCallsRunnable() {
        val mockRunnable = mock(Runnable::class.java)
        qsPanel.setCollapseExpandAction(mockRunnable)

        qsPanel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null)
        verify(mockRunnable).run()
    }

    @Test
    fun testTilesFooterVisibleRTLLandscapeMedia() {
        qsPanel.layoutDirection = View.LAYOUT_DIRECTION_RTL
        // We need at least a tile so the layout has a height
        qsPanel.tileLayout?.addTile(
                QSPanelControllerBase.TileRecord(
                    mock(QSTile::class.java),
                    QSTileViewImpl(themedContext)
                )
            )

        val mediaView = FrameLayout(themedContext)
        mediaView.addView(View(themedContext), MATCH_PARENT, 800)

        qsPanel.setUsingHorizontalLayout(/* horizontal */ true, mediaView, /* force */ true)
        qsPanel.measure(
            /* width */ View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY),
            /* height */ View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        qsPanel.layout(0, 0, qsPanel.measuredWidth, qsPanel.measuredHeight)

        val tiles = qsPanel.tileLayout as View
        // Tiles are effectively to the right of media
        assertThat(mediaView isLeftOf tiles)
        assertThat(tiles.isVisibleToUser).isTrue()

        assertThat(mediaView isLeftOf footer)
        assertThat(footer.isVisibleToUser).isTrue()
    }

    @Test
    fun testTilesFooterVisibleLandscapeMedia() {
        qsPanel.layoutDirection = View.LAYOUT_DIRECTION_LTR
        // We need at least a tile so the layout has a height
        qsPanel.tileLayout?.addTile(
            QSPanelControllerBase.TileRecord(
                mock(QSTile::class.java),
                QSTileViewImpl(themedContext)
            )
        )

        val mediaView = FrameLayout(themedContext)
        mediaView.addView(View(themedContext), MATCH_PARENT, 800)

        qsPanel.setUsingHorizontalLayout(/* horizontal */ true, mediaView, /* force */ true)
        qsPanel.measure(
            /* width */ View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY),
            /* height */ View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        qsPanel.layout(0, 0, qsPanel.measuredWidth, qsPanel.measuredHeight)

        val tiles = qsPanel.tileLayout as View
        // Tiles are effectively to the left of media
        assertThat(tiles isLeftOf mediaView)
        assertThat(tiles.isVisibleToUser).isTrue()

        assertThat(footer isLeftOf mediaView)
        assertThat(footer.isVisibleToUser).isTrue()
    }

    @Test
    fun testBottomPadding() {
        val padding = 10
        themedContext.orCreateTestableResources.addOverride(
                R.dimen.qs_panel_padding_bottom,
                padding
        )
        qsPanel.updatePadding()
        assertThat(qsPanel.paddingBottom).isEqualTo(padding)
    }

    @Test
    fun testTopPadding() {
        val padding = 10
        val paddingCombined = 100
        themedContext.orCreateTestableResources.addOverride(R.dimen.qs_panel_padding_top, padding)
        themedContext.orCreateTestableResources.addOverride(
                R.dimen.qs_panel_padding_top,
                paddingCombined
        )

        qsPanel.updatePadding()
        assertThat(qsPanel.paddingTop).isEqualTo(paddingCombined)
    }

    @Test
    fun testSetSquishinessFraction_noCrash() {
        qsPanel.addView(qsPanel.mTileLayout as View, 0)
        qsPanel.addView(FrameLayout(context))
        qsPanel.setSquishinessFraction(0.5f)
    }

    @Test
    fun testSplitShade_CollapseAccessibilityActionNotAnnounced() {
        qsPanel.setCanCollapse(false)
        val accessibilityInfo = mock(AccessibilityNodeInfo::class.java)
        qsPanel.onInitializeAccessibilityNodeInfo(accessibilityInfo)

        val actionCollapse = AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
        verify(accessibilityInfo, never()).addAction(actionCollapse)
    }

    @Test
    fun addTile_callbackAdded() {
        val tile = mock(QSTile::class.java)
        val tileView = mock(QSTileView::class.java)

        val record = TileRecord(tile, tileView)

        qsPanel.addTile(record)

        verify(tile).addCallback(record.callback)
    }

    private infix fun View.isLeftOf(other: View): Boolean {
        val rect = Rect()
        getBoundsOnScreen(rect)
        val thisRight = rect.right

        other.getBoundsOnScreen(rect)

        return thisRight <= rect.left
    }
}
