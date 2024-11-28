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

import android.content.res.Configuration
import android.graphics.Rect
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableContext
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.QSPanelControllerBase.TileRecord
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileViewImpl
import com.android.systemui.res.R
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
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper
@SmallTest
class QSPanelTest(flags: FlagsParameterization) : SysuiTestCase() {

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Mock private lateinit var qsLogger: QSLogger

    private lateinit var testableLooper: TestableLooper
    private lateinit var qsPanel: QSPanel

    private lateinit var footer: View

    private val themedContext =
        TestableContext(ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings))

    @Before
    @Throws(Exception::class)
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        // Apply only the values of the theme that are not defined

        testableLooper.runWithLooper {
            qsPanel = QSPanel(themedContext, null)
            qsPanel.mUsingMediaPlayer = true

            qsPanel.initialize(qsLogger, true)
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
        if (qsPanel.isAttachedToWindow) {
            ViewUtils.detachView(qsPanel)
        }
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
    @DisableSceneContainer
    fun testTilesFooterVisibleLandscapeMedia() {
        // We need at least a tile so the layout has a height
        qsPanel.tileLayout?.addTile(
            QSPanelControllerBase.TileRecord(
                mock(QSTile::class.java),
                QSTileViewImpl(themedContext),
            )
        )

        val mediaView = FrameLayout(themedContext)
        mediaView.addView(View(themedContext), MATCH_PARENT, 800)

        qsPanel.setUsingHorizontalLayout(/* horizontal */ true, mediaView, /* force */ true)
        qsPanel.measure(
            /* width */ View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY),
            /* height */ View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        qsPanel.layout(0, 0, qsPanel.measuredWidth, qsPanel.measuredHeight)

        val tiles = qsPanel.tileLayout as View
        // Tiles are effectively to the left of media
        assertThat(tiles isLeftOf mediaView).isTrue()
        assertThat(tiles.isVisibleToUser).isTrue()

        assertThat(footer isLeftOf mediaView).isTrue()
        assertThat(footer.isVisibleToUser).isTrue()
    }

    @Test
    fun testBottomPadding() {
        val padding = 10
        themedContext.orCreateTestableResources.addOverride(
            R.dimen.qs_panel_padding_bottom,
            padding,
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
            paddingCombined,
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

    @Test
    @DisableSceneContainer
    fun initializedWithNoMedia_sceneContainerDisabled_tileLayoutParentIsAlwaysQsPanel() {
        lateinit var panel: QSPanel
        lateinit var tileLayout: View
        testableLooper.runWithLooper {
            panel = QSPanel(themedContext, null)
            panel.mUsingMediaPlayer = true

            panel.initialize(qsLogger, /* usingMediaPlayer= */ false)
            tileLayout = panel.orCreateTileLayout as View
            // QSPanel inflates a footer inside of it, mocking it here
            footer = LinearLayout(themedContext).apply { id = R.id.qs_footer }
            panel.addView(footer, MATCH_PARENT, 100)
            panel.onFinishInflate()
            // Provides a parent with non-zero size for QSPanel
            ViewUtils.attachView(panel)
        }
        val mockMediaHost = mock(ViewGroup::class.java)

        panel.setUsingHorizontalLayout(false, mockMediaHost, true)

        assertThat(tileLayout.parent).isSameInstanceAs(panel)

        panel.setUsingHorizontalLayout(true, mockMediaHost, true)
        assertThat(tileLayout.parent).isSameInstanceAs(panel)

        ViewUtils.detachView(panel)
    }

    @Test
    @DisableSceneContainer
    fun initializeWithNoMedia_mediaNeverAttached() {
        lateinit var panel: QSPanel
        testableLooper.runWithLooper {
            panel = QSPanel(themedContext, null)
            panel.mUsingMediaPlayer = true

            panel.initialize(qsLogger, /* usingMediaPlayer= */ false)
            panel.orCreateTileLayout as View
            // QSPanel inflates a footer inside of it, mocking it here
            footer = LinearLayout(themedContext).apply { id = R.id.qs_footer }
            panel.addView(footer, MATCH_PARENT, 100)
            panel.onFinishInflate()
            // Provides a parent with non-zero size for QSPanel
            ViewUtils.attachView(panel)
        }
        val mockMediaHost = FrameLayout(themedContext)

        panel.setUsingHorizontalLayout(false, mockMediaHost, true)
        assertThat(mockMediaHost.parent).isNull()

        panel.setUsingHorizontalLayout(true, mockMediaHost, true)
        assertThat(mockMediaHost.parent).isNull()

        ViewUtils.detachView(panel)
    }

    @Test
    fun setRowColumnLayout() {
        qsPanel.setColumnRowLayout(/* withMedia= */ false)

        assertThat(qsPanel.tileLayout!!.minRows).isEqualTo(1)
        assertThat(qsPanel.tileLayout!!.maxColumns).isEqualTo(4)

        qsPanel.setColumnRowLayout(/* withMedia= */ true)

        assertThat(qsPanel.tileLayout!!.minRows).isEqualTo(2)
        assertThat(qsPanel.tileLayout!!.maxColumns).isEqualTo(2)
    }

    @Test
    fun noPendingConfigChangesAtBeginning() {
        assertThat(qsPanel.hadConfigurationChangeWhileDetached()).isFalse()
    }

    @Test
    fun configChangesWhileDetached_pendingConfigChanges() {
        ViewUtils.detachView(qsPanel)

        qsPanel.onConfigurationChanged(Configuration())

        assertThat(qsPanel.hadConfigurationChangeWhileDetached()).isTrue()
    }

    @Test
    fun configChangesWhileDetached_reattach_pendingConfigChanges() {
        ViewUtils.detachView(qsPanel)

        qsPanel.onConfigurationChanged(Configuration())
        testableLooper.runWithLooper { ViewUtils.attachView(qsPanel) }

        assertThat(qsPanel.hadConfigurationChangeWhileDetached()).isTrue()
    }

    @Test
    fun configChangesWhileDetached_reattach_detach_pendingConfigChanges_reset() {
        ViewUtils.detachView(qsPanel)

        qsPanel.onConfigurationChanged(Configuration())

        testableLooper.runWithLooper { ViewUtils.attachView(qsPanel) }
        ViewUtils.detachView(qsPanel)

        assertThat(qsPanel.hadConfigurationChangeWhileDetached()).isFalse()
    }

    @Test
    fun configChangeWhileAttached_noPendingConfigChanges() {
        qsPanel.onConfigurationChanged(Configuration())

        assertThat(qsPanel.hadConfigurationChangeWhileDetached()).isFalse()
    }

    @Test
    fun configChangeWhileAttachedWithPending_doesntResetPending() {
        ViewUtils.detachView(qsPanel)

        qsPanel.onConfigurationChanged(Configuration())

        testableLooper.runWithLooper { ViewUtils.attachView(qsPanel) }

        qsPanel.onConfigurationChanged(Configuration())

        assertThat(qsPanel.hadConfigurationChangeWhileDetached()).isTrue()
    }

    companion object {
        @Parameters(name = "{0}") @JvmStatic fun getParams() = parameterizeSceneContainerFlag()
    }

    private infix fun View.isLeftOf(other: View): Boolean {
        val rect = Rect()
        getBoundsOnScreen(rect)
        val thisRight = rect.right

        other.getBoundsOnScreen(rect)

        return thisRight <= rect.left
    }
}
