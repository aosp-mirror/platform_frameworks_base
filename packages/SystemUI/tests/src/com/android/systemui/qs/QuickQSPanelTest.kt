package com.android.systemui.qs

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class QuickQSPanelTest : SysuiTestCase() {

    private lateinit var testableLooper: TestableLooper
    private lateinit var quickQSPanel: QuickQSPanel

    private lateinit var parentView: ViewGroup

    @Before
    @Throws(Exception::class)
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        testableLooper.runWithLooper {
            quickQSPanel = QuickQSPanel(mContext, null)
            quickQSPanel.initialize()

            quickQSPanel.onFinishInflate()
            quickQSPanel.setSecurityFooter(View(mContext), false)
            quickQSPanel.setHeaderContainer(LinearLayout(mContext))
            // Provides a parent with non-zero size for QSPanel
            parentView = FrameLayout(mContext).apply {
                addView(quickQSPanel)
            }
        }
    }

    @Test
    fun testHasExpandAccessibilityAction() {
        val info = AccessibilityNodeInfo(quickQSPanel)
        quickQSPanel.onInitializeAccessibilityNodeInfo(info)

        Truth.assertThat(info.actions and AccessibilityNodeInfo.ACTION_EXPAND).isNotEqualTo(0)
        Truth.assertThat(info.actions and AccessibilityNodeInfo.ACTION_COLLAPSE).isEqualTo(0)
    }

    @Test
    fun testExpandActionCallsRunnable() {
        val mockRunnable = Mockito.mock(Runnable::class.java)
        quickQSPanel.setCollapseExpandAction(mockRunnable)

        quickQSPanel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null)
        Mockito.verify(mockRunnable).run()
    }
}