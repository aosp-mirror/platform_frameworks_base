package com.android.systemui.qs

import android.testing.TestableLooper
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.logging.QSLogger
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@SmallTest
class QuickQSPanelTest : SysuiTestCase() {

    @Mock private lateinit var qsLogger: QSLogger

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
            quickQSPanel.initialize(qsLogger, true)

            quickQSPanel.onFinishInflate()
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
