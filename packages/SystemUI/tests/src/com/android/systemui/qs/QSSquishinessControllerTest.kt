package com.android.systemui.qs

import android.testing.AndroidTestingRunner
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tileimpl.QSTileViewImpl
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class QSSquishinessControllerTest : SysuiTestCase() {

    @Mock private lateinit var qsTileHost: QSTileHost
    @Mock private lateinit var qqsFooterActionsView: FooterActionsView
    @Mock private lateinit var qqsFooterActionsViewLP: ViewGroup.MarginLayoutParams
    @Mock private lateinit var qsAnimator: QSAnimator
    @Mock private lateinit var quickQsPanelController: QuickQSPanelController
    @Mock private lateinit var qstileView: QSTileViewImpl
    @Mock private lateinit var qstile: QSTile
    @Mock private lateinit var tileLayout: TileLayout

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var qsSquishinessController: QSSquishinessController

    @Before
    fun setup() {
        qsSquishinessController = QSSquishinessController(qsTileHost, qqsFooterActionsView,
                qsAnimator, quickQsPanelController)
        `when`(qsTileHost.tiles).thenReturn(mutableListOf(qstile))
        `when`(quickQsPanelController.getTileView(any())).thenReturn(qstileView)
        `when`(quickQsPanelController.tileLayout).thenReturn(tileLayout)
        `when`(qqsFooterActionsView.layoutParams).thenReturn(qqsFooterActionsViewLP)
    }

    @Test
    fun setSquishiness_requestsAnimatorUpdate() {
        qsSquishinessController.squishiness = 0.5f
        verify(qsAnimator, never()).requestAnimatorUpdate()

        qsSquishinessController.squishiness = 0f
        verify(qsAnimator).requestAnimatorUpdate()
    }

    @Test
    fun setSquishiness_updatesTiles() {
        qsSquishinessController.squishiness = 0.5f
        verify(qstileView).squishinessFraction = 0.5f
        verify(tileLayout).setSquishinessFraction(0.5f)
    }
}