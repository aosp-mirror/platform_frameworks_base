package com.android.systemui.qs

import android.testing.AndroidTestingRunner
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class QSSquishinessControllerTest : SysuiTestCase() {

    @Mock private lateinit var qqsFooterActionsView: FooterActionsView
    @Mock private lateinit var qqsFooterActionsViewLP: ViewGroup.MarginLayoutParams
    @Mock private lateinit var qsAnimator: QSAnimator
    @Mock private lateinit var qsPanelController: QSPanelController
    @Mock private lateinit var quickQsPanelController: QuickQSPanelController
    @Mock private lateinit var tileLayout: TileLayout
    @Mock private lateinit var pagedTileLayout: PagedTileLayout

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var qsSquishinessController: QSSquishinessController

    @Before
    fun setup() {
        qsSquishinessController = QSSquishinessController(qqsFooterActionsView, qsAnimator,
                qsPanelController, quickQsPanelController)
        `when`(quickQsPanelController.tileLayout).thenReturn(tileLayout)
        `when`(qsPanelController.tileLayout).thenReturn(pagedTileLayout)
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
        verify(tileLayout).setSquishinessFraction(0.5f)
        verify(pagedTileLayout).setSquishinessFraction(0.5f)
    }
}