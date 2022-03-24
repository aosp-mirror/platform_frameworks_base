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
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.QSPanelControllerBase.TileRecord
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class QSPanelTest : SysuiTestCase() {
    private lateinit var mTestableLooper: TestableLooper
    private lateinit var mQsPanel: QSPanel

    @Mock
    private lateinit var mHost: QSTileHost

    @Mock
    private lateinit var dndTile: QSTileImpl<*>

    @Mock
    private lateinit var mDndTileRecord: TileRecord

    @Mock
    private lateinit var mQSLogger: QSLogger
    private lateinit var mParentView: ViewGroup

    @Mock
    private lateinit var mQSTileView: QSTileView

    private lateinit var mFooter: View

    @Before
    @Throws(Exception::class)
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mTestableLooper = TestableLooper.get(this)

        mDndTileRecord.tile = dndTile
        mDndTileRecord.tileView = mQSTileView
        mTestableLooper.runWithLooper {
            mQsPanel = QSPanel(mContext, null)
            mQsPanel.initialize()
            // QSPanel inflates a footer inside of it, mocking it here
            mFooter = LinearLayout(mContext).apply { id = R.id.qs_footer }
            mQsPanel.addView(mFooter)
            mQsPanel.onFinishInflate()
            mQsPanel.setSecurityFooter(View(mContext), false)
            mQsPanel.setHeaderContainer(LinearLayout(mContext))
            // Provides a parent with non-zero size for QSPanel
            mParentView = FrameLayout(mContext).apply {
                addView(mQsPanel)
            }

            whenever(dndTile.tileSpec).thenReturn("dnd")
            whenever(mHost.tiles).thenReturn(emptyList())
            whenever(mHost.createTileView(any(), any(), anyBoolean())).thenReturn(mQSTileView)
            mQsPanel.addTile(mDndTileRecord)
        }
    }

    @Test
    fun testSecurityFooter_appearsOnBottomOnSplitShade() {
        mQsPanel.onConfigurationChanged(getNewOrientationConfig(ORIENTATION_LANDSCAPE))
        mQsPanel.switchSecurityFooter(true)

        mTestableLooper.runWithLooper {
            mQsPanel.isExpanded = true
        }

        // After mFooter
        assertThat(mQsPanel.indexOfChild(mQsPanel.mSecurityFooter)).isEqualTo(
                mQsPanel.indexOfChild(mFooter) + 1
        )
    }

    @Test
    fun testSecurityFooter_appearsOnBottomIfPortrait() {
        mQsPanel.onConfigurationChanged(getNewOrientationConfig(ORIENTATION_PORTRAIT))
        mQsPanel.switchSecurityFooter(false)

        mTestableLooper.runWithLooper {
            mQsPanel.isExpanded = true
        }

        // After mFooter
        assertThat(mQsPanel.indexOfChild(mQsPanel.mSecurityFooter)).isEqualTo(
                mQsPanel.indexOfChild(mFooter) + 1
        )
    }

    @Test
    fun testSecurityFooter_appearsOnTopIfSmallScreenAndLandscape() {
        mQsPanel.onConfigurationChanged(getNewOrientationConfig(ORIENTATION_LANDSCAPE))
        mQsPanel.switchSecurityFooter(false)

        mTestableLooper.runWithLooper {
            mQsPanel.isExpanded = true
        }

        // -1 means that it is part of the mHeaderContainer
        assertThat(mQsPanel.indexOfChild(mQsPanel.mSecurityFooter)).isEqualTo(-1)
    }

    private fun getNewOrientationConfig(@Configuration.Orientation newOrientation: Int) =
            context.resources.configuration.apply { orientation = newOrientation }
}
