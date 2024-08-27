package com.android.systemui.qs

import android.testing.TestableLooper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.customize.QSCustomizer
import com.android.systemui.util.mockito.eq
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@SmallTest
class QSContainerImplTest : SysuiTestCase() {

    @Mock
    private lateinit var quickStatusBarHeader: QuickStatusBarHeader
    @Mock
    private lateinit var qsCustomizer: QSCustomizer
    @Mock
    private lateinit var qsPanelContainer: NonInterceptingScrollView
    @Mock
    private lateinit var qsPanelController: QSPanelController
    @Mock
    private lateinit var quickStatusBarHeaderController: QuickStatusBarHeaderController

    private lateinit var qsContainer: QSContainerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        qsContainer = QSContainerImpl(mContext, null)

        setUpMockView(quickStatusBarHeader, R.id.header)
        setUpMockView(qsCustomizer, R.id.qs_customize)
        setUpMockView(qsPanelContainer, R.id.expanded_qs_scroll_view)

        qsContainer.onFinishInflate()
    }

    private fun setUpMockView(view: View, id: Int) {
        whenever(view.findViewById<View>(id)).thenReturn(view)
        whenever(view.layoutParams).thenReturn(FrameLayout.LayoutParams(0, 0))
        qsContainer.addView(view)
    }

    @Test
    fun testContainerBottomPadding() {
        val originalPadding = qsPanelContainer.paddingBottom
        qsContainer.updateResources(
            qsPanelController,
            quickStatusBarHeaderController
        )
        verify(qsPanelContainer)
            .setPaddingRelative(
                anyInt(),
                anyInt(),
                anyInt(),
                eq(originalPadding)
            )
    }
}
