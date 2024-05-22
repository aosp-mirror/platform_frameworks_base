package com.android.systemui.statusbar.notification.stack

import android.annotation.DimenRes
import android.content.pm.PackageManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.keyguard.BouncerPanelExpansionCalculator.aboutToShowBouncerProgress
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ShadeInterpolation.getContentAlpha
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.res.R
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator
import com.android.systemui.statusbar.EmptyShadeView
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.RoundableState
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView.FooterViewState
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.shared.NotificationsImprovedHunAnimation
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.AvalancheController
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
class StackScrollAlgorithmTest : SysuiTestCase() {

    @JvmField @Rule var expect: Expect = Expect.create()

    private val largeScreenShadeInterpolator = mock<LargeScreenShadeInterpolator>()
    private val avalancheController = mock<AvalancheController>()

    private val hostView = FrameLayout(context)
    private val stackScrollAlgorithm = StackScrollAlgorithm(context, hostView)
    private val notificationRow = mock<ExpandableNotificationRow>()
    private val notificationEntry = mock<NotificationEntry>()
    private val dumpManager = mock<DumpManager>()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mStatusBarKeyguardViewManager = mock<StatusBarKeyguardViewManager>()
    private val notificationShelf = mock<NotificationShelf>()
    private val emptyShadeView =
        EmptyShadeView(context, /* attrs= */ null).apply {
            layout(/* l= */ 0, /* t= */ 0, /* r= */ 100, /* b= */ 100)
        }
    private val footerView = FooterView(context, /*attrs=*/ null)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ambientState =
        AmbientState(
            context,
            dumpManager,
            /* sectionProvider */ { _, _ -> false },
            /* bypassController */ { false },
            mStatusBarKeyguardViewManager,
            largeScreenShadeInterpolator,
            avalancheController
        )

    private val testableResources = mContext.getOrCreateTestableResources()
    private val featureFlags = mock<FeatureFlagsClassic>()
    private val maxPanelHeight =
        mContext.resources.displayMetrics.heightPixels -
            px(R.dimen.notification_panel_margin_top) -
            px(R.dimen.notification_panel_margin_bottom)

    private fun px(@DimenRes id: Int): Float =
        testableResources.resources.getDimensionPixelSize(id).toFloat()

    private val bigGap = px(R.dimen.notification_section_divider_height)
    private val smallGap = px(R.dimen.notification_section_divider_height_lockscreen)
    private val scrimPadding = px(R.dimen.notification_side_paddings)

    @Before
    fun setUp() {
        Assume.assumeFalse(isTv())
        mDependency.injectTestDependency(FeatureFlags::class.java, featureFlags)
        whenever(notificationShelf.viewState).thenReturn(ExpandableViewState())
        whenever(notificationRow.viewState).thenReturn(ExpandableViewState())
        whenever(notificationRow.entry).thenReturn(notificationEntry)
        whenever(notificationRow.roundableState)
            .thenReturn(RoundableState(notificationRow, notificationRow, 0f))
        ambientState.isSmallScreen = true

        hostView.addView(notificationRow)
    }

    private fun isTv(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    @Test
    fun resetViewStates_defaultHun_yTranslationIsInset() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        resetViewStates_hunYTranslationIs(stackScrollAlgorithm.mHeadsUpInset)
    }

    @Test
    fun resetViewStates_defaultHunWithStackMargin_changesHunYTranslation() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        resetViewStates_stackMargin_changesHunYTranslation()
    }

    @Test
    fun resetViewStates_defaultHunWhenShadeIsOpening_yTranslationIsInset() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)

        // scroll the panel over the HUN inset
        ambientState.stackY = stackScrollAlgorithm.mHeadsUpInset + bigGap

        // the HUN translation should be the panel scroll position + the scrim padding
        resetViewStates_hunYTranslationIs(ambientState.stackY + scrimPadding)
    }

    @Test
    @DisableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_hunAnimatingAway_yTranslationIsInset() {
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)
        resetViewStates_hunYTranslationIs(stackScrollAlgorithm.mHeadsUpInset)
    }

    @Test
    @DisableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_hunAnimatingAway_StackMarginChangesHunYTranslation() {
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)
        resetViewStates_stackMargin_changesHunYTranslation()
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_defaultHun_newHeadsUpAnim_yTranslationIsInset() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        resetViewStates_hunYTranslationIs(stackScrollAlgorithm.mHeadsUpInset)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_defaultHunWithStackMargin_newHeadsUpAnim_changesHunYTranslation() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        resetViewStates_stackMargin_changesHunYTranslation()
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_defaultHun_showingQS_newHeadsUpAnim_hunTranslatedToMax() {
        // Given: the shade is open and scrolled to the bottom to show the QuickSettings
        val maxHunTranslation = 2000f
        ambientState.maxHeadsUpTranslation = maxHunTranslation
        ambientState.setLayoutMinHeight(2500) // Mock the height of shade
        ambientState.stackY = 2500f // Scroll over the max translation
        stackScrollAlgorithm.setIsExpanded(true) // Mark the shade open
        whenever(notificationRow.mustStayOnScreen()).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        whenever(notificationRow.isAboveShelf).thenReturn(true)

        resetViewStates_hunYTranslationIs(maxHunTranslation)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_hunAnimatingAway_showingQS_newHeadsUpAnim_hunTranslatedToBottomOfScreen() {
        // Given: the shade is open and scrolled to the bottom to show the QuickSettings
        val bottomOfScreen = 2600f
        val maxHunTranslation = 2000f
        ambientState.maxHeadsUpTranslation = maxHunTranslation
        ambientState.setLayoutMinHeight(2500) // Mock the height of shade
        ambientState.stackY = 2500f // Scroll over the max translation
        stackScrollAlgorithm.setIsExpanded(true) // Mark the shade open
        stackScrollAlgorithm.setHeadsUpAppearHeightBottom(bottomOfScreen.toInt())
        whenever(notificationRow.mustStayOnScreen()).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        whenever(notificationRow.isAboveShelf).thenReturn(true)
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        resetViewStates_hunYTranslationIs(
            expected = bottomOfScreen + stackScrollAlgorithm.mHeadsUpAppearStartAboveScreen
        )
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_hunAnimatingAway_newHeadsUpAnim_hunTranslatedToTopOfScreen() {
        val topMargin = 100f
        ambientState.maxHeadsUpTranslation = 2000f
        ambientState.stackTopMargin = topMargin.toInt()
        whenever(notificationRow.intrinsicHeight).thenReturn(100)
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        resetViewStates_hunYTranslationIs(
            expected = -topMargin - stackScrollAlgorithm.mHeadsUpAppearStartAboveScreen
        )
    }

    @Test
    fun resetViewStates_hunAnimatingAway_bottomNotClipped() {
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.clipBottomAmount).isEqualTo(0)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_hunAnimatingAwayWhileDozing_yTranslationIsInset() {
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        ambientState.isDozing = true

        resetViewStates_hunYTranslationIs(stackScrollAlgorithm.mHeadsUpInset)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_hunAnimatingAwayWhileDozing_hasStackMargin_changesHunYTranslation() {
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        ambientState.isDozing = true

        resetViewStates_stackMargin_changesHunYTranslation()
    }

    @Test
    fun resetViewStates_hunsOverlapping_bottomHunClipped() {
        val topHun = mockExpandableNotificationRow()
        val bottomHun = mockExpandableNotificationRow()
        whenever(topHun.isHeadsUp).thenReturn(true)
        whenever(topHun.isPinned).thenReturn(true)
        whenever(bottomHun.isHeadsUp).thenReturn(true)
        whenever(bottomHun.isPinned).thenReturn(true)

        resetViewStates_hunsOverlapping_bottomHunClipped(topHun, bottomHun)
    }

    @Test
    @DisableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun resetViewStates_hunsOverlappingAndBottomHunAnimatingAway_bottomHunClipped() {
        val topHun = mockExpandableNotificationRow()
        val bottomHun = mockExpandableNotificationRow()
        whenever(topHun.isHeadsUp).thenReturn(true)
        whenever(topHun.isPinned).thenReturn(true)
        whenever(bottomHun.isHeadsUpAnimatingAway).thenReturn(true)

        resetViewStates_hunsOverlapping_bottomHunClipped(topHun, bottomHun)
    }

    @Test
    fun resetViewStates_emptyShadeView_isCenteredVertically() {
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)
        ambientState.layoutMaxHeight = maxPanelHeight.toInt()

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val marginBottom =
            context.resources.getDimensionPixelSize(R.dimen.notification_panel_margin_bottom)
        val fullHeight = ambientState.layoutMaxHeight + marginBottom - ambientState.stackY
        val centeredY = ambientState.stackY + fullHeight / 2f - emptyShadeView.height / 2f
        assertThat(emptyShadeView.viewState.yTranslation).isEqualTo(centeredY)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resetViewStates_expansionChanging_notificationBecomesTransparent() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(false)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = 0.25f,
            expectedAlpha = 0.0f
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resetViewStates_expansionChangingWhileBouncerInTransit_viewBecomesTransparent() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(true)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = 0.85f,
            expectedAlpha = 0.0f
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resetViewStates_expansionChanging_notificationAlphaUpdated() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(false)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = 0.6f,
            expectedAlpha = getContentAlpha(0.6f)
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resetViewStates_largeScreen_expansionChanging_alphaUpdated_largeScreenValue() {
        val expansionFraction = 0.6f
        val surfaceAlpha = 123f
        ambientState.isSmallScreen = false
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(false)
        whenever(largeScreenShadeInterpolator.getNotificationContentAlpha(expansionFraction))
            .thenReturn(surfaceAlpha)

        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = expansionFraction,
            expectedAlpha = surfaceAlpha,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun expansionChanging_largeScreen_bouncerInTransit_alphaUpdated_bouncerValues() {
        ambientState.isSmallScreen = false
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(true)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = 0.95f,
            expectedAlpha = aboutToShowBouncerProgress(0.95f),
        )
    }

    @Test
    fun resetViewStates_expansionChanging_shelfUpdated() {
        ambientState.shelf = notificationShelf
        ambientState.isExpansionChanging = true
        ambientState.expansionFraction = 0.6f
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        verify(notificationShelf)
            .updateState(/* algorithmState= */ any(), /* ambientState= */ eq(ambientState))
    }

    @Test
    fun resetViewStates_isOnKeyguard_viewBecomesTransparent() {
        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.hideAmount = 0.25f
        whenever(notificationRow.isHeadsUpState).thenReturn(true)

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
    fun resetViewStates_shadeCollapsed_emptyShadeViewBecomesTransparent() {
        ambientState.expansionFraction = 0f
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(emptyShadeView.viewState.alpha).isEqualTo(0f)
    }

    @Test
    fun resetViewStates_isOnKeyguard_emptyShadeViewBecomesOpaque() {
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
    fun resetViewStates_hiddenShelf_allRowsBecomesTransparent() {
        hostView.removeAllViews()
        val row1 = mockExpandableNotificationRow()
        hostView.addView(row1)
        val row2 = mockExpandableNotificationRow()
        hostView.addView(row2)

        whenever(row1.isHeadsUpState).thenReturn(true)
        whenever(row2.isHeadsUpState).thenReturn(false)

        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.hideAmount = 0.25f
        ambientState.dozeAmount = 0.33f
        notificationShelf.viewState.hidden = true
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(row1.viewState.alpha).isEqualTo(1f - ambientState.hideAmount)
        assertThat(row2.viewState.alpha).isEqualTo(1f - ambientState.dozeAmount)
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
    fun resetViewStates_noSpaceForFooter_footerHidden() {
        ambientState.isShadeExpanded = true
        ambientState.stackEndHeight = 0f // no space for the footer in the stack
        hostView.addView(footerView)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat((footerView.viewState as FooterViewState).hideContent).isTrue()
    }

    @Test
    fun resetViewStates_clearAllInProgress_hasNonClearableRow_footerVisible() {
        whenever(notificationRow.canViewBeCleared()).thenReturn(false)
        ambientState.isClearAllInProgress = true
        ambientState.isShadeExpanded = true
        ambientState.stackEndHeight = maxPanelHeight // plenty space for the footer in the stack
        hostView.addView(footerView)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(footerView.viewState.hidden).isFalse()
        assertThat((footerView.viewState as FooterViewState).hideContent).isFalse()
    }

    @Test
    fun resetViewStates_clearAllInProgress_allRowsClearable_footerHidden() {
        whenever(notificationRow.canViewBeCleared()).thenReturn(true)
        ambientState.isClearAllInProgress = true
        ambientState.isShadeExpanded = true
        ambientState.stackEndHeight = maxPanelHeight // plenty space for the footer in the stack
        hostView.addView(footerView)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat((footerView.viewState as FooterViewState).hideContent).isTrue()
    }

    @Test
    fun resetViewStates_clearAllInProgress_allRowsRemoved_emptyShade_footerHidden() {
        ambientState.isClearAllInProgress = true
        ambientState.isShadeExpanded = true
        ambientState.stackEndHeight = maxPanelHeight // plenty space for the footer in the stack
        hostView.removeAllViews() // remove all rows
        hostView.addView(footerView)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat((footerView.viewState as FooterViewState).hideContent).isTrue()
    }

    @Test
    fun getGapForLocation_onLockscreen_returnsSmallGap() {
        val gap =
            stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0f,
                /* onKeyguard= */ true
            )
        assertThat(gap).isEqualTo(smallGap)
    }

    @Test
    fun getGapForLocation_goingToShade_interpolatesGap() {
        val gap =
            stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0.5f,
                /* onKeyguard= */ true
            )
        assertThat(gap).isEqualTo(smallGap * 0.5f + bigGap * 0.5f)
    }

    @Test
    fun getGapForLocation_notOnLockscreen_returnsBigGap() {
        val gap =
            stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0f,
                /* onKeyguard= */ false
            )
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

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ true,
            /* mustStayOnScreen= */ true,
            /* isViewEndVisible= */ true,
            /* viewEnd= */ 0f,
            /* maxHunY= */ 10f
        )

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_endHidden_false() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ true,
            /* mustStayOnScreen= */ true,
            /* isViewEndVisible= */ true,
            /* viewEnd= */ 10f,
            /* maxHunY= */ 0f
        )

        assertFalse(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_shadeClosed_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ false,
            /* mustStayOnScreen= */ true,
            /* isViewEndVisible= */ true,
            /* viewEnd= */ 10f,
            /* maxHunY= */ 1f
        )

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_notHUN_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ true,
            /* mustStayOnScreen= */ false,
            /* isViewEndVisible= */ true,
            /* viewEnd= */ 10f,
            /* maxHunY= */ 1f
        )

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_topHidden_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ true,
            /* mustStayOnScreen= */ true,
            /* isViewEndVisible= */ false,
            /* viewEnd= */ 10f,
            /* maxHunY= */ 1f
        )

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun clampHunToTop_viewYGreaterThanQqs_viewYUnchanged() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = 50f

        stackScrollAlgorithm.clampHunToTop(
            /* quickQsOffsetHeight= */ 10f,
            /* stackTranslation= */ 0f,
            /* collapsedHeight= */ 1f,
            expandableViewState
        )

        // qqs (10 + 0) < viewY (50)
        assertEquals(50f, expandableViewState.yTranslation)
    }

    @Test
    fun clampHunToTop_viewYLessThanQqs_viewYChanged() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = -10f

        stackScrollAlgorithm.clampHunToTop(
            /* quickQsOffsetHeight= */ 10f,
            /* stackTranslation= */ 0f,
            /* collapsedHeight= */ 1f,
            expandableViewState
        )

        // qqs (10 + 0) > viewY (-10)
        assertEquals(10f, expandableViewState.yTranslation)
    }

    @Test
    fun clampHunToTop_viewYFarAboveVisibleStack_heightCollapsed() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.height = 20
        expandableViewState.yTranslation = -100f

        stackScrollAlgorithm.clampHunToTop(
            /* quickQsOffsetHeight= */ 10f,
            /* stackTranslation= */ 0f,
            /* collapsedHeight= */ 10f,
            expandableViewState
        )

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

        stackScrollAlgorithm.clampHunToTop(
            /* quickQsOffsetHeight= */ 10f,
            /* stackTranslation= */ 0f,
            /* collapsedHeight= */ 10f,
            expandableViewState
        )

        // newTranslation = max(10, 5) = 10
        // distToRealY = 10 - 5 = 5
        // height = max(20 - 5, 10) = 15
        assertEquals(15, expandableViewState.height)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackBelowScreen_round() {
        val currentRoundness =
            stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 110f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f
            )
        assertEquals(1f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackAboveScreenBelowPinPoint_halfRound() {
        val currentRoundness =
            stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 90f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f
            )
        assertEquals(0.5f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackAbovePinPoint_notRound() {
        val currentRoundness =
            stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 0f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f
            )
        assertEquals(0f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_originallyRoundAndStackAbovePinPoint_round() {
        val currentRoundness =
            stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 0f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 1f
            )
        assertEquals(1f, currentRoundness)
    }

    @Test
    fun shadeOpened_hunFullyOverlapsQqsPanel_hunShouldHaveFullShadow() {
        // Given: shade is opened, yTranslation of HUN is 0,
        // the height of HUN equals to the height of QQS Panel,
        // and HUN fully overlaps with QQS Panel
        ambientState.stackTranslation =
            px(R.dimen.qqs_layout_margin_top) + px(R.dimen.qqs_layout_padding_bottom)
        val childHunView =
            createHunViewMock(isShadeOpen = true, fullyVisible = false, headerVisibleAmount = 1f)
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
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
        ambientState.stackTranslation =
            px(R.dimen.qqs_layout_margin_top) + px(R.dimen.qqs_layout_padding_bottom)
        val childHunView =
            createHunViewMock(isShadeOpen = true, fullyVisible = false, headerVisibleAmount = 1f)
        // Use half of the HUN's height as overlap
        childHunView.viewState.yTranslation = (childHunView.viewState.height + 1 shr 1).toFloat()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
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
        ambientState.stackTranslation =
            px(R.dimen.qqs_layout_margin_top) + px(R.dimen.qqs_layout_padding_bottom)
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView =
            createHunViewMock(isShadeOpen = true, fullyVisible = true, headerVisibleAmount = 1f)
        // HUN doesn't overlap with QQS Panel
        childHunView.viewState.yTranslation =
            ambientState.topPadding + ambientState.stackTranslation
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
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
        ambientState.stackTranslation = (-ambientState.topPadding).toFloat()
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView =
            createHunViewMock(isShadeOpen = false, fullyVisible = false, headerVisibleAmount = 0f)
        childHunView.viewState.yTranslation = 0f
        // Shade is closed, thus childHunView's headerVisibleAmount is 0
        childHunView.headerVisibleAmount = 0f
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
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
        ambientState.stackTranslation = (-ambientState.topPadding).toFloat()
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView =
            createHunViewMock(isShadeOpen = false, fullyVisible = false, headerVisibleAmount = 0.5f)
        childHunView.viewState.yTranslation = 0f
        // Shade is being opened, thus childHunView's headerVisibleAmount is between 0 and 1
        // use 0.5 as headerVisibleAmount here
        childHunView.headerVisibleAmount = 0.5f
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
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
    fun aodToLockScreen_hasPulsingNotification_pulsingNotificationRowDoesNotChange() {
        // Given: Before AOD to LockScreen, there was a pulsing notification
        val pulsingNotificationView = createPulsingViewMock()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(pulsingNotificationView)
        ambientState.setPulsingRow(pulsingNotificationView)

        // When: during AOD to LockScreen, any dozeAmount between (0, 1.0) is equivalent as a middle
        // stage; here we use 0.5 for testing.
        // stackScrollAlgorithm.updatePulsingStates is called
        ambientState.dozeAmount = 0.5f
        stackScrollAlgorithm.updatePulsingStates(algorithmState, ambientState)

        // Then: ambientState.pulsingRow should still be pulsingNotificationView
        assertTrue(ambientState.isPulsingRow(pulsingNotificationView))
    }

    @Test
    fun deviceOnAod_hasPulsingNotification_recordPulsingNotificationRow() {
        // Given: Device is on AOD, there is a pulsing notification
        // ambientState.pulsingRow is null before stackScrollAlgorithm.updatePulsingStates
        ambientState.dozeAmount = 1.0f
        val pulsingNotificationView = createPulsingViewMock()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(pulsingNotificationView)
        ambientState.setPulsingRow(null)

        // When: stackScrollAlgorithm.updatePulsingStates is called
        stackScrollAlgorithm.updatePulsingStates(algorithmState, ambientState)

        // Then: ambientState.pulsingRow should record the pulsingNotificationView
        assertTrue(ambientState.isPulsingRow(pulsingNotificationView))
    }

    @Test
    fun deviceOnLockScreen_hasPulsingNotificationBefore_clearPulsingNotificationRowRecord() {
        // Given: Device finished AOD to LockScreen, there was a pulsing notification, and
        // ambientState.pulsingRow was not null before AOD to LockScreen
        // pulsingNotificationView.showingPulsing() returns false since the device is on LockScreen
        ambientState.dozeAmount = 0.0f
        val pulsingNotificationView = createPulsingViewMock()
        whenever(pulsingNotificationView.showingPulsing()).thenReturn(false)
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(pulsingNotificationView)
        ambientState.setPulsingRow(pulsingNotificationView)

        // When: stackScrollAlgorithm.updatePulsingStates is called
        stackScrollAlgorithm.updatePulsingStates(algorithmState, ambientState)

        // Then: ambientState.pulsingRow should be null
        assertTrue(ambientState.isPulsingRow(null))
    }

    @Test
    fun aodToLockScreen_hasPulsingNotification_pulsingNotificationRowShowAtFullHeight() {
        // Given: Before AOD to LockScreen, there was a pulsing notification
        val pulsingNotificationView = createPulsingViewMock()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(pulsingNotificationView)
        ambientState.setPulsingRow(pulsingNotificationView)

        // When: during AOD to LockScreen, any dozeAmount between (0, 1.0) is equivalent as a middle
        // stage; here we use 0.5 for testing. The expansionFraction is also 0.5.
        // stackScrollAlgorithm.resetViewStates is called.
        ambientState.dozeAmount = 0.5f
        setExpansionFractionWithoutShelfDuringAodToLockScreen(
            ambientState,
            algorithmState,
            fraction = 0.5f
        )
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // Then: pulsingNotificationView should show at full height
        assertEquals(
            stackScrollAlgorithm.getMaxAllowedChildHeight(pulsingNotificationView),
            pulsingNotificationView.viewState.height
        )

        // After: reset dozeAmount and expansionFraction
        ambientState.dozeAmount = 0f
        setExpansionFractionWithoutShelfDuringAodToLockScreen(
            ambientState,
            algorithmState,
            fraction = 1f
        )
    }

    // region shouldPinHunToBottomOfExpandedQs
    @Test
    fun shouldHunBeVisibleWhenScrolled_mustStayOnScreenFalse_false() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ false,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ false,
                    /*headsUpOnKeyguard=*/ false
                )
            )
            .isFalse()
    }

    @Test
    fun shouldPinHunToBottomOfExpandedQs_headsUpIsVisible_false() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ true,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ false,
                    /*headsUpOnKeyguard=*/ false
                )
            )
            .isFalse()
    }

    @Test
    fun shouldHunBeVisibleWhenScrolled_showingPulsing_false() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ true,
                    /* isOnKeyguard=*/ false,
                    /* headsUpOnKeyguard= */ false
                )
            )
            .isFalse()
    }

    @Test
    fun shouldHunBeVisibleWhenScrolled_isOnKeyguard_false() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ true,
                    /* headsUpOnKeyguard= */ false
                )
            )
            .isFalse()
    }

    @Test
    fun shouldHunBeVisibleWhenScrolled_isNotOnKeyguard_true() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ false,
                    /* headsUpOnKeyguard= */ false
                )
            )
            .isTrue()
    }

    @Test
    fun shouldHunBeVisibleWhenScrolled_headsUpOnKeyguard_true() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ true,
                    /* headsUpOnKeyguard= */ true
                )
            )
            .isTrue()
    }

    @Test
    fun shouldHunAppearFromBottom_hunAtMaxHunTranslation() {
        ambientState.maxHeadsUpTranslation = 400f
        val viewState =
            ExpandableViewState().apply {
                height = 100
                yTranslation = ambientState.maxHeadsUpTranslation - height // move it to the max
            }

        assertTrue(stackScrollAlgorithm.shouldHunAppearFromBottom(ambientState, viewState))
    }

    @Test
    fun shouldHunAppearFromBottom_hunBelowMaxHunTranslation() {
        ambientState.maxHeadsUpTranslation = 400f
        val viewState =
            ExpandableViewState().apply {
                height = 100
                yTranslation =
                    ambientState.maxHeadsUpTranslation - height - 1 // move it below the max
            }

        assertFalse(stackScrollAlgorithm.shouldHunAppearFromBottom(ambientState, viewState))
    }
    // endregion

    private fun createHunViewMock(
        isShadeOpen: Boolean,
        fullyVisible: Boolean,
        headerVisibleAmount: Float
    ) =
        mock<ExpandableNotificationRow>().apply {
            val childViewStateMock = createHunChildViewState(isShadeOpen, fullyVisible)
            whenever(this.viewState).thenReturn(childViewStateMock)

            whenever(this.mustStayOnScreen()).thenReturn(true)
            whenever(this.headerVisibleAmount).thenReturn(headerVisibleAmount)
        }

    private fun createHunChildViewState(isShadeOpen: Boolean, fullyVisible: Boolean) =
        ExpandableViewState().apply {
            // Mock the HUN's height with ambientState.topPadding +
            // ambientState.stackTranslation
            height = (ambientState.topPadding + ambientState.stackTranslation).toInt()
            if (isShadeOpen && fullyVisible) {
                yTranslation = ambientState.topPadding + ambientState.stackTranslation
            } else {
                yTranslation = 0f
            }
            headsUpIsVisible = fullyVisible
        }

    private fun createPulsingViewMock() =
        mock<ExpandableNotificationRow>().apply {
            whenever(this.viewState).thenReturn(ExpandableViewState())
            whenever(this.showingPulsing()).thenReturn(true)
        }

    private fun setExpansionFractionWithoutShelfDuringAodToLockScreen(
        ambientState: AmbientState,
        algorithmState: StackScrollAlgorithm.StackScrollAlgorithmState,
        fraction: Float
    ) {
        // showingShelf: false
        algorithmState.firstViewInShelf = null
        // scrimPadding: 0, because device is on lock screen
        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.dozeAmount = 0.0f
        // set stackEndHeight and stackHeight
        // ExpansionFractionWithoutShelf == stackHeight / stackEndHeight
        ambientState.stackEndHeight = 100f
        ambientState.stackHeight = ambientState.stackEndHeight * fraction
    }

    private fun resetViewStates_hunYTranslationIs(expected: Float) {
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(notificationRow.viewState.yTranslation).isEqualTo(expected)
    }

    private fun resetViewStates_stackMargin_changesHunYTranslation() {
        val stackTopMargin = bigGap.toInt() // a gap smaller than the headsUpInset
        val headsUpTranslationY = stackScrollAlgorithm.mHeadsUpInset - stackTopMargin

        // we need the shelf to mock the real-life behaviour of StackScrollAlgorithm#updateChild
        ambientState.shelf = notificationShelf

        // split shade case with top margin introduced by shade's status bar
        ambientState.stackTopMargin = stackTopMargin
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // heads up translation should be decreased by the top margin
        assertThat(notificationRow.viewState.yTranslation).isEqualTo(headsUpTranslationY)
    }

    private fun resetViewStates_hunsOverlapping_bottomHunClipped(
        topHun: ExpandableNotificationRow,
        bottomHun: ExpandableNotificationRow
    ) {
        val topHunHeight =
            mContext.resources.getDimensionPixelSize(R.dimen.notification_content_min_height)
        val bottomHunHeight =
            mContext.resources.getDimensionPixelSize(R.dimen.notification_max_heads_up_height)
        whenever(topHun.intrinsicHeight).thenReturn(topHunHeight)
        whenever(bottomHun.intrinsicHeight).thenReturn(bottomHunHeight)

        // we need the shelf to mock the real-life behaviour of StackScrollAlgorithm#updateChild
        ambientState.shelf = notificationShelf

        // add two overlapping HUNs
        hostView.removeAllViews()
        hostView.addView(topHun)
        hostView.addView(bottomHun)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        // the height shouldn't change
        assertThat(topHun.viewState.height).isEqualTo(topHunHeight)
        assertThat(bottomHun.viewState.height).isEqualTo(bottomHunHeight)
        // the HUN at the bottom should be clipped
        assertThat(topHun.viewState.clipBottomAmount).isEqualTo(0)
        assertThat(bottomHun.viewState.clipBottomAmount).isEqualTo(bottomHunHeight - topHunHeight)
    }

    private fun resetViewStates_expansionChanging_notificationAlphaUpdated(
        expansionFraction: Float,
        expectedAlpha: Float,
    ) {
        ambientState.isExpansionChanging = true
        ambientState.expansionFraction = expansionFraction
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        expect.that(notificationRow.viewState.alpha).isEqualTo(expectedAlpha)
    }
}

private fun mockExpandableNotificationRow(): ExpandableNotificationRow {
    return mock(ExpandableNotificationRow::class.java).apply {
        whenever(viewState).thenReturn(ExpandableViewState())
    }
}
