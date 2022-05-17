package com.android.systemui.statusbar.notification.stack

import android.annotation.DimenRes
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.EmptyShadeView
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.BypassController
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.SectionProvider
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@SmallTest
class StackScrollAlgorithmTest : SysuiTestCase() {

    private val hostView = FrameLayout(context)
    private val stackScrollAlgorithm = StackScrollAlgorithm(context, hostView)
    private val expandableViewState = ExpandableViewState()
    private val notificationRow = mock(ExpandableNotificationRow::class.java)
    private val mStatusBarKeyguardViewManager = mock(StatusBarKeyguardViewManager::class.java)

    private val ambientState = AmbientState(
        context,
        SectionProvider { _, _ -> false },
        BypassController { false },
        mStatusBarKeyguardViewManager
    )

    private val testableResources = mContext.orCreateTestableResources

    private fun px(@DimenRes id: Int): Float =
            testableResources.resources.getDimensionPixelSize(id).toFloat()

    private val bigGap = px(R.dimen.notification_section_divider_height)
    private val smallGap = px(R.dimen.notification_section_divider_height_lockscreen)

    @Before
    fun setUp() {
        whenever(notificationRow.viewState).thenReturn(expandableViewState)
        hostView.addView(notificationRow)
    }

    @Test
    fun resetViewStates_defaultHun_yTranslationIsInset() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(expandableViewState.yTranslation).isEqualTo(stackScrollAlgorithm.mHeadsUpInset)
    }

    @Test
    fun resetViewStates_stackMargin_changesHunYTranslation() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        val minHeadsUpTranslation = context.resources
                .getDimensionPixelSize(R.dimen.notification_side_paddings)

        // split shade case with top margin introduced by shade's status bar
        ambientState.stackTopMargin = 100
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // top margin presence should decrease heads up translation up to minHeadsUpTranslation
        assertThat(expandableViewState.yTranslation).isEqualTo(minHeadsUpTranslation)
    }

    @Test
    fun resetViewStates_emptyShadeView_isCenteredVertically() {
        stackScrollAlgorithm.initView(context)
        val emptyShadeView = EmptyShadeView(context, /* attrs= */ null).apply {
            layout(/* l= */ 0, /* t= */ 0, /* r= */ 100, /* b= */ 100)
        }
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)
        ambientState.layoutMaxHeight = 1280

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val marginBottom =
            context.resources.getDimensionPixelSize(R.dimen.notification_panel_margin_bottom)
        val fullHeight = ambientState.layoutMaxHeight + marginBottom - ambientState.stackY
        val centeredY = ambientState.stackY + fullHeight / 2f - emptyShadeView.height / 2f
        assertThat(emptyShadeView.viewState?.yTranslation).isEqualTo(centeredY)
    }

    @Test
    fun getGapForLocation_onLockscreen_returnsSmallGap() {
        val gap = stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0f, /* onKeyguard= */ true)
        assertThat(gap).isEqualTo(smallGap)
    }

    @Test
    fun getGapForLocation_goingToShade_interpolatesGap() {
        val gap = stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0.5f, /* onKeyguard= */ true)
        assertThat(gap).isEqualTo(smallGap * 0.5f + bigGap * 0.5f)
    }

    @Test
    fun getGapForLocation_notOnLockscreen_returnsBigGap() {
        val gap = stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0f, /* onKeyguard= */ false)
        assertThat(gap).isEqualTo(bigGap)
    }
}
