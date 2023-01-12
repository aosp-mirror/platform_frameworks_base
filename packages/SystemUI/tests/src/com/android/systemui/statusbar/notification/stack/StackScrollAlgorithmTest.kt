package com.android.systemui.statusbar.notification.stack

import android.annotation.DimenRes
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.keyguard.BouncerPanelExpansionCalculator.aboutToShowBouncerProgress
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ShadeInterpolation.getContentAlpha
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.EmptyShadeView
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
class StackScrollAlgorithmTest : SysuiTestCase() {

    private val hostView = FrameLayout(context)
    private val stackScrollAlgorithm = StackScrollAlgorithm(context, hostView)
    private val notificationRow = mock<ExpandableNotificationRow>()
    private val dumpManager = mock<DumpManager>()
    private val mStatusBarKeyguardViewManager = mock<StatusBarKeyguardViewManager>()
    private val notificationShelf = mock<NotificationShelf>()
    private val emptyShadeView = EmptyShadeView(context, /* attrs= */ null).apply {
        layout(/* l= */ 0, /* t= */ 0, /* r= */ 100, /* b= */ 100)
    }
    private val ambientState = AmbientState(
            context,
            dumpManager,
            /* sectionProvider */ { _, _ -> false },
            /* bypassController */ { false },
            mStatusBarKeyguardViewManager
    )

    private val testableResources = mContext.getOrCreateTestableResources()

    private fun px(@DimenRes id: Int): Float =
            testableResources.resources.getDimensionPixelSize(id).toFloat()

    private val bigGap = px(R.dimen.notification_section_divider_height)
    private val smallGap = px(R.dimen.notification_section_divider_height_lockscreen)

    @Before
    fun setUp() {
        whenever(notificationShelf.viewState).thenReturn(ExpandableViewState())
        whenever(notificationRow.viewState).thenReturn(ExpandableViewState())

        hostView.addView(notificationRow)
    }

    @Test
    fun resetViewStates_defaultHun_yTranslationIsInset() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(notificationRow.viewState.yTranslation)
                .isEqualTo(stackScrollAlgorithm.mHeadsUpInset)
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
        assertThat(notificationRow.viewState.yTranslation).isEqualTo(minHeadsUpTranslation)
    }

    @Test
    fun resetViewStates_emptyShadeView_isCenteredVertically() {
        stackScrollAlgorithm.initView(context)
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
    fun resetViewStates_hunGoingToShade_viewBecomesOpaque() {
        whenever(notificationRow.isAboveShelf).thenReturn(true)
        ambientState.isShadeExpanded = true
        ambientState.trackedHeadsUpRow = notificationRow
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(1f)
    }

    @Test
    fun resetViewStates_expansionChanging_notificationBecomesTransparent() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(false)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
                expansionFraction = 0.25f,
                expectedAlpha = 0.0f
        )
    }

    @Test
    fun resetViewStates_expansionChangingWhileBouncerInTransit_viewBecomesTransparent() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(true)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
                expansionFraction = 0.85f,
                expectedAlpha = 0.0f
        )
    }

    @Test
    fun resetViewStates_expansionChanging_notificationAlphaUpdated() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(false)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
                expansionFraction = 0.6f,
                expectedAlpha = getContentAlpha(0.6f)
        )
    }

    @Test
    fun resetViewStates_expansionChangingWhileBouncerInTransit_notificationAlphaUpdated() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(true)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
                expansionFraction = 0.95f,
                expectedAlpha = aboutToShowBouncerProgress(0.95f)
        )
    }

    @Test
    fun resetViewStates_expansionChanging_shelfUpdated() {
        ambientState.shelf = notificationShelf
        ambientState.isExpansionChanging = true
        ambientState.expansionFraction = 0.6f
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        verify(notificationShelf).updateState(
                /* algorithmState= */any(),
                /* ambientState= */eq(ambientState)
        )
    }

    @Test
    fun resetViewStates_isOnKeyguard_viewBecomesTransparent() {
        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.hideAmount = 0.25f
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(1f - ambientState.hideAmount)
    }

    @Test
    fun resetViewStates_isOnKeyguard_emptyShadeViewBecomesTransparent() {
        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.fractionToShade = 0.25f
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val expected = getContentAlpha(ambientState.fractionToShade)
        assertThat(emptyShadeView.viewState.alpha).isEqualTo(expected)
    }

    @Test
    fun resetViewStates_isOnKeyguard_emptyShadeViewBecomesOpaque() {
        ambientState.setStatusBarState(StatusBarState.SHADE)
        ambientState.fractionToShade = 0.25f
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(emptyShadeView.viewState.alpha).isEqualTo(1f)
    }

    @Test
    fun resetViewStates_hiddenShelf_allRowsBecomesTransparent() {
        hostView.removeAllViews()
        val row1 = mockExpandableNotificationRow()
        hostView.addView(row1)
        val row2 = mockExpandableNotificationRow()
        hostView.addView(row2)

        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.hideAmount = 0.25f
        notificationShelf.viewState.hidden = true
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val expected = 1f - ambientState.hideAmount
        assertThat(row1.viewState.alpha).isEqualTo(expected)
        assertThat(row2.viewState.alpha).isEqualTo(expected)
    }

    @Test
    fun resetViewStates_hiddenShelf_shelfAlphaDoesNotChange() {
        val expected = notificationShelf.viewState.alpha
        notificationShelf.viewState.hidden = true
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationShelf.viewState.alpha).isEqualTo(expected)
    }

    @Test
    fun resetViewStates_shelfTopLessThanViewTop_hidesView() {
        notificationRow.viewState.yTranslation = 10f
        notificationShelf.viewState.yTranslation = 0.9f
        notificationShelf.viewState.hidden = false
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(0f)
    }

    @Test
    fun resetViewStates_shelfTopGreaterOrEqualThanViewTop_viewAlphaDoesNotChange() {
        val expected = notificationRow.viewState.alpha
        notificationRow.viewState.yTranslation = 10f
        notificationShelf.viewState.yTranslation = 10f
        notificationShelf.viewState.hidden = false
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(expected)
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

    @Test
    fun updateViewWithShelf_viewAboveShelf_viewShown() {
        val viewStart = 0f
        val shelfStart = 1f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.isExpandAnimationRunning).thenReturn(false)
        whenever(expandableView.hasExpandingChild()).thenReturn(false)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertFalse(expandableViewState.hidden)
    }

    @Test
    fun updateViewWithShelf_viewBelowShelf_viewHidden() {
        val shelfStart = 0f
        val viewStart = 1f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.isExpandAnimationRunning).thenReturn(false)
        whenever(expandableView.hasExpandingChild()).thenReturn(false)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertTrue(expandableViewState.hidden)
    }

    @Test
    fun updateViewWithShelf_viewBelowShelfButIsExpanding_viewShown() {
        val shelfStart = 0f
        val viewStart = 1f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.isExpandAnimationRunning).thenReturn(true)
        whenever(expandableView.hasExpandingChild()).thenReturn(true)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertFalse(expandableViewState.hidden)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_endVisible_true() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = false

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ true,
                /* mustStayOnScreen= */ true,
                /* isViewEndVisible= */ true,
                /* viewEnd= */ 0f,
                /* maxHunY= */ 10f)

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_endHidden_false() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ true,
                /* mustStayOnScreen= */ true,
                /* isViewEndVisible= */ true,
                /* viewEnd= */ 10f,
                /* maxHunY= */ 0f)

        assertFalse(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_shadeClosed_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ false,
                /* mustStayOnScreen= */ true,
                /* isViewEndVisible= */ true,
                /* viewEnd= */ 10f,
                /* maxHunY= */ 1f)

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_notHUN_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ true,
                /* mustStayOnScreen= */ false,
                /* isViewEndVisible= */ true,
                /* viewEnd= */ 10f,
                /* maxHunY= */ 1f)

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_topHidden_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ true,
                /* mustStayOnScreen= */ true,
                /* isViewEndVisible= */ false,
                /* viewEnd= */ 10f,
                /* maxHunY= */ 1f)

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun clampHunToTop_viewYGreaterThanQqs_viewYUnchanged() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = 50f

        stackScrollAlgorithm.clampHunToTop(/* quickQsOffsetHeight= */ 10f,
                /* stackTranslation= */ 0f,
                /* collapsedHeight= */ 1f, expandableViewState)

        // qqs (10 + 0) < viewY (50)
        assertEquals(50f, expandableViewState.yTranslation)
    }

    @Test
    fun clampHunToTop_viewYLessThanQqs_viewYChanged() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = -10f

        stackScrollAlgorithm.clampHunToTop(/* quickQsOffsetHeight= */ 10f,
                /* stackTranslation= */ 0f,
                /* collapsedHeight= */ 1f, expandableViewState)

        // qqs (10 + 0) > viewY (-10)
        assertEquals(10f, expandableViewState.yTranslation)
    }

    @Test
    fun clampHunToTop_viewYFarAboveVisibleStack_heightCollapsed() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.height = 20
        expandableViewState.yTranslation = -100f

        stackScrollAlgorithm.clampHunToTop(/* quickQsOffsetHeight= */ 10f,
                /* stackTranslation= */ 0f,
                /* collapsedHeight= */ 10f, expandableViewState)

        // newTranslation = max(10, -100) = 10
        // distToRealY = 10 - (-100f) = 110
        // height = max(20 - 110, 10f)
        assertEquals(10, expandableViewState.height)
    }

    @Test
    fun clampHunToTop_viewYNearVisibleStack_heightTallerThanCollapsed() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.height = 20
        expandableViewState.yTranslation = 5f

        stackScrollAlgorithm.clampHunToTop(/* quickQsOffsetHeight= */ 10f,
                /* stackTranslation= */ 0f,
                /* collapsedHeight= */ 10f, expandableViewState)

        // newTranslation = max(10, 5) = 10
        // distToRealY = 10 - 5 = 5
        // height = max(20 - 5, 10) = 15
        assertEquals(15, expandableViewState.height)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackBelowScreen_round() {
        val currentRoundness = stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 110f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f)
        assertEquals(1f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackAboveScreenBelowPinPoint_halfRound() {
        val currentRoundness = stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 90f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f)
        assertEquals(0.5f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackAbovePinPoint_notRound() {
        val currentRoundness = stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 0f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f)
        assertEquals(0f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_originallyRoundAndStackAbovePinPoint_round() {
        val currentRoundness = stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 0f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 1f)
        assertEquals(1f, currentRoundness)
    }

    @Test
    fun shadeOpened_hunFullyOverlapsQqsPanel_hunShouldHaveFullShadow() {
        // Given: shade is opened, yTranslation of HUN is 0,
        // the height of HUN equals to the height of QQS Panel,
        // and HUN fully overlaps with QQS Panel
        ambientState.stackTranslation = px(R.dimen.qqs_layout_margin_top) +
                px(R.dimen.qqs_layout_padding_bottom)
        val childHunView = createHunViewMock(
                isShadeOpen = true,
                fullyVisible = false,
                headerVisibleAmount = 1f,
        )
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
                /* i= */ 0,
                /* StackScrollAlgorithmState= */ algorithmState,
                /* ambientState= */ ambientState,
                /* shouldElevateHun= */ true
        )

        // Then: full shadow would be applied
        assertEquals(px(R.dimen.heads_up_pinned_elevation), childHunView.viewState.zTranslation)
    }

    @Test
    fun shadeOpened_hunPartiallyOverlapsQQS_hunShouldHavePartialShadow() {
        // Given: shade is opened, yTranslation of HUN is greater than 0,
        // the height of HUN is equal to the height of QQS Panel,
        // and HUN partially overlaps with QQS Panel
        ambientState.stackTranslation = px(R.dimen.qqs_layout_margin_top) +
                px(R.dimen.qqs_layout_padding_bottom)
        val childHunView = createHunViewMock(
                isShadeOpen = true,
                fullyVisible = false,
                headerVisibleAmount = 1f,
        )
        // Use half of the HUN's height as overlap
        childHunView.viewState.yTranslation = (childHunView.viewState.height + 1 shr 1).toFloat()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
                /* i= */ 0,
                /* StackScrollAlgorithmState= */ algorithmState,
                /* ambientState= */ ambientState,
                /* shouldElevateHun= */ true
        )

        // Then: HUN should have shadow, but not as full size
        assertThat(childHunView.viewState.zTranslation).isGreaterThan(0.0f)
        assertThat(childHunView.viewState.zTranslation)
                .isLessThan(px(R.dimen.heads_up_pinned_elevation))
    }

    @Test
    fun shadeOpened_hunDoesNotOverlapQQS_hunShouldHaveNoShadow() {
        // Given: shade is opened, yTranslation of HUN is equal to QQS Panel's height,
        // the height of HUN is equal to the height of QQS Panel,
        // and HUN doesn't overlap with QQS Panel
        ambientState.stackTranslation = px(R.dimen.qqs_layout_margin_top) +
                px(R.dimen.qqs_layout_padding_bottom)
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView = createHunViewMock(
                isShadeOpen = true,
                fullyVisible = true,
                headerVisibleAmount = 1f,
        )
        // HUN doesn't overlap with QQS Panel
        childHunView.viewState.yTranslation = ambientState.topPadding +
                ambientState.stackTranslation
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
                /* i= */ 0,
                /* StackScrollAlgorithmState= */ algorithmState,
                /* ambientState= */ ambientState,
                /* shouldElevateHun= */ true
        )

        // Then: HUN should not have shadow
        assertEquals(0f, childHunView.viewState.zTranslation)
    }

    @Test
    fun shadeClosed_hunShouldHaveFullShadow() {
        // Given: shade is closed, ambientState.stackTranslation == -ambientState.topPadding,
        // the height of HUN is equal to the height of QQS Panel,
        ambientState.stackTranslation = -ambientState.topPadding
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView = createHunViewMock(
                isShadeOpen = false,
                fullyVisible = false,
                headerVisibleAmount = 0f,
        )
        childHunView.viewState.yTranslation = 0f
        // Shade is closed, thus childHunView's headerVisibleAmount is 0
        childHunView.headerVisibleAmount = 0f
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
                /* i= */ 0,
                /* StackScrollAlgorithmState= */ algorithmState,
                /* ambientState= */ ambientState,
                /* shouldElevateHun= */ true
        )

        // Then: HUN should have full shadow
        assertEquals(px(R.dimen.heads_up_pinned_elevation), childHunView.viewState.zTranslation)
    }

    @Test
    fun draggingHunToOpenShade_hunShouldHavePartialShadow() {
        // Given: shade is closed when HUN pops up,
        // now drags down the HUN to open shade
        ambientState.stackTranslation = -ambientState.topPadding
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView = createHunViewMock(
                isShadeOpen = false,
                fullyVisible = false,
                headerVisibleAmount = 0.5f,
        )
        childHunView.viewState.yTranslation = 0f
        // Shade is being opened, thus childHunView's headerVisibleAmount is between 0 and 1
        // use 0.5 as headerVisibleAmount here
        childHunView.headerVisibleAmount = 0.5f
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
                /* i= */ 0,
                /* StackScrollAlgorithmState= */ algorithmState,
                /* ambientState= */ ambientState,
                /* shouldElevateHun= */ true
        )

        // Then: HUN should have shadow, but not as full size
        assertThat(childHunView.viewState.zTranslation).isGreaterThan(0.0f)
        assertThat(childHunView.viewState.zTranslation)
                .isLessThan(px(R.dimen.heads_up_pinned_elevation))
    }

    private fun createHunViewMock(
            isShadeOpen: Boolean,
            fullyVisible: Boolean,
            headerVisibleAmount: Float,
    ) =
            mock<ExpandableNotificationRow>().apply {
                val childViewStateMock = createHunChildViewState(isShadeOpen, fullyVisible)
                whenever(this.viewState).thenReturn(childViewStateMock)

                whenever(this.mustStayOnScreen()).thenReturn(true)
                whenever(this.headerVisibleAmount).thenReturn(headerVisibleAmount)
            }


    private fun createHunChildViewState(
            isShadeOpen: Boolean,
            fullyVisible: Boolean,
    ) =
            ExpandableViewState().apply {
                // Mock the HUN's height with ambientState.topPadding +
                // ambientState.stackTranslation
                height = (ambientState.topPadding + ambientState.stackTranslation).toInt()
                if (isShadeOpen && fullyVisible) {
                    yTranslation =
                            ambientState.topPadding + ambientState.stackTranslation
                } else {
                    yTranslation = 0f
                }
                headsUpIsVisible = fullyVisible
            }

    private fun resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction: Float,
            expectedAlpha: Float
    ) {
        ambientState.isExpansionChanging = true
        ambientState.expansionFraction = expansionFraction
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(expectedAlpha)
    }
}

private fun mockExpandableNotificationRow(): ExpandableNotificationRow {
    return mock(ExpandableNotificationRow::class.java).apply {
        whenever(viewState).thenReturn(ExpandableViewState())
    }
}
