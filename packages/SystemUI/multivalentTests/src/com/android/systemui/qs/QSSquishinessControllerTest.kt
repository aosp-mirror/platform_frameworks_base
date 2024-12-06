package com.android.systemui.qs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidJUnit4::class)
@SmallTest
class QSSquishinessControllerTest : SysuiTestCase() {

    @Mock private lateinit var qsAnimator: QSAnimator
    @Mock private lateinit var qsPanelController: QSPanelController
    @Mock private lateinit var quickQsPanelController: QuickQSPanelController

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var qsSquishinessController: QSSquishinessController

    @Before
    fun setup() {
        qsSquishinessController = QSSquishinessController(qsAnimator,
                qsPanelController, quickQsPanelController)
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
        verify(qsPanelController).setSquishinessFraction(0.5f)
        verify(quickQsPanelController).setSquishinessFraction(0.5f)
    }
}
