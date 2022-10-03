package com.android.systemui.shade

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManagerPolicyConstants
import androidx.annotation.IdRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.navigationbar.NavigationModeController.ModeChangedListener
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.function.Consumer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class NotificationQSContainerControllerTest : SysuiTestCase() {

    companion object {
        const val STABLE_INSET_BOTTOM = 100
        const val CUTOUT_HEIGHT = 50
        const val GESTURES_NAVIGATION = WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL
        const val BUTTONS_NAVIGATION = WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON
        const val NOTIFICATIONS_MARGIN = 50
        const val SCRIM_MARGIN = 10
        const val FOOTER_ACTIONS_INSET = 2
        const val FOOTER_ACTIONS_PADDING = 2
        const val FOOTER_ACTIONS_OFFSET = FOOTER_ACTIONS_INSET + FOOTER_ACTIONS_PADDING
        const val QS_PADDING_OFFSET = SCRIM_MARGIN + FOOTER_ACTIONS_OFFSET
    }

    @Mock
    private lateinit var navigationModeController: NavigationModeController
    @Mock
    private lateinit var overviewProxyService: OverviewProxyService
    @Mock
    private lateinit var notificationsQSContainer: NotificationsQuickSettingsContainer
    @Mock
    private lateinit var largeScreenShadeHeaderController: LargeScreenShadeHeaderController
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Captor
    lateinit var navigationModeCaptor: ArgumentCaptor<ModeChangedListener>
    @Captor
    lateinit var taskbarVisibilityCaptor: ArgumentCaptor<OverviewProxyListener>
    @Captor
    lateinit var windowInsetsCallbackCaptor: ArgumentCaptor<Consumer<WindowInsets>>
    @Captor
    lateinit var constraintSetCaptor: ArgumentCaptor<ConstraintSet>

    private lateinit var controller: NotificationsQSContainerController
    private lateinit var navigationModeCallback: ModeChangedListener
    private lateinit var taskbarVisibilityCallback: OverviewProxyListener
    private lateinit var windowInsetsCallback: Consumer<WindowInsets>
    private lateinit var delayableExecutor: FakeExecutor
    private lateinit var fakeSystemClock: FakeSystemClock

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mContext.ensureTestableResources()
        whenever(notificationsQSContainer.context).thenReturn(mContext)
        whenever(notificationsQSContainer.resources).thenReturn(mContext.resources)
        fakeSystemClock = FakeSystemClock()
        delayableExecutor = FakeExecutor(fakeSystemClock)
        controller = NotificationsQSContainerController(
                notificationsQSContainer,
                navigationModeController,
                overviewProxyService,
                largeScreenShadeHeaderController,
                featureFlags,
                delayableExecutor
        )

        overrideResource(R.dimen.split_shade_notifications_scrim_margin_bottom, SCRIM_MARGIN)
        overrideResource(R.dimen.notification_panel_margin_bottom, NOTIFICATIONS_MARGIN)
        overrideResource(R.bool.config_use_split_notification_shade, false)
        overrideResource(R.dimen.qs_footer_actions_bottom_padding, FOOTER_ACTIONS_PADDING)
        overrideResource(R.dimen.qs_footer_action_inset, FOOTER_ACTIONS_INSET)
        whenever(navigationModeController.addListener(navigationModeCaptor.capture()))
                .thenReturn(GESTURES_NAVIGATION)
        doNothing().`when`(overviewProxyService).addCallback(taskbarVisibilityCaptor.capture())
        doNothing().`when`(notificationsQSContainer)
                .setInsetsChangedListener(windowInsetsCallbackCaptor.capture())
        doNothing().`when`(notificationsQSContainer).applyConstraints(constraintSetCaptor.capture())

        controller.init()
        controller.onViewAttached()

        navigationModeCallback = navigationModeCaptor.value
        taskbarVisibilityCallback = taskbarVisibilityCaptor.value
        windowInsetsCallback = windowInsetsCallbackCaptor.value
    }

    @Test
    fun testTaskbarVisibleInSplitShade() {
        enableSplitShade()

        given(taskbarVisible = true,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0, // taskbar should disappear when shade is expanded
                expectedNotificationsMargin = NOTIFICATIONS_MARGIN,
                expectedQsPadding = NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET)

        given(taskbarVisible = true,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = STABLE_INSET_BOTTOM,
                expectedNotificationsMargin = NOTIFICATIONS_MARGIN,
                expectedQsPadding = NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET)
    }

    @Test
    fun testTaskbarNotVisibleInSplitShade() {
        // when taskbar is not visible, it means we're on the home screen
        enableSplitShade()

        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedQsPadding = NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0, // qs goes full height as it's not obscuring nav buttons
                expectedNotificationsMargin = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN,
                expectedQsPadding = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET)
    }

    @Test
    fun testTaskbarNotVisibleInSplitShadeWithCutout() {
        enableSplitShade()

        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withCutout())
        then(expectedContainerPadding = CUTOUT_HEIGHT,
            expectedQsPadding = NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withCutout().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN,
                expectedQsPadding = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET)
    }

    @Test
    fun testTaskbarVisibleInSinglePaneShade() {
        disableSplitShade()

        given(taskbarVisible = true,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedQsPadding = STABLE_INSET_BOTTOM)

        given(taskbarVisible = true,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = STABLE_INSET_BOTTOM,
                expectedQsPadding = STABLE_INSET_BOTTOM)
    }

    @Test
    fun testTaskbarNotVisibleInSinglePaneShade() {
        disableSplitShade()

        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = emptyInsets())
        then(expectedContainerPadding = 0)

        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withCutout().withStableBottom())
        then(expectedContainerPadding = CUTOUT_HEIGHT, expectedQsPadding = STABLE_INSET_BOTTOM)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN,
                expectedQsPadding = STABLE_INSET_BOTTOM)
    }

    @Test
    fun testCustomizingInSinglePaneShade() {
        disableSplitShade()
        controller.setCustomizerShowing(true)

        // always sets spacings to 0
        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = 0)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = emptyInsets())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = 0)
    }

    @Test
    fun testDetailShowingInSinglePaneShade() {
        disableSplitShade()
        controller.setDetailShowing(true)

        // always sets spacings to 0
        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = 0)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = emptyInsets())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = 0)
    }

    @Test
    fun testDetailShowingInSplitShade() {
        enableSplitShade()
        controller.setDetailShowing(true)

        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0)

        // should not influence spacing
        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = emptyInsets())
        then(expectedContainerPadding = 0)
    }

    @Test
    fun testNotificationsMarginBottomIsUpdated() {
        Mockito.clearInvocations(notificationsQSContainer)
        enableSplitShade()
        verify(notificationsQSContainer).setNotificationsMarginBottom(NOTIFICATIONS_MARGIN)

        overrideResource(R.dimen.notification_panel_margin_bottom, 100)
        disableSplitShade()
        verify(notificationsQSContainer).setNotificationsMarginBottom(100)
    }

    @Test
    fun testSplitShadeLayout_isAlignedToGuideline() {
        enableSplitShade()
        controller.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).endToEnd)
                .isEqualTo(R.id.qs_edge_guideline)
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).startToStart)
                .isEqualTo(R.id.qs_edge_guideline)
    }

    @Test
    fun testSinglePaneLayout_childrenHaveEqualMargins() {
        disableSplitShade()
        controller.updateResources()
        val qsStartMargin = getConstraintSetLayout(R.id.qs_frame).startMargin
        val qsEndMargin = getConstraintSetLayout(R.id.qs_frame).endMargin
        val notifStartMargin = getConstraintSetLayout(R.id.notification_stack_scroller).startMargin
        val notifEndMargin = getConstraintSetLayout(R.id.notification_stack_scroller).endMargin
        assertThat(qsStartMargin == qsEndMargin &&
                notifStartMargin == notifEndMargin &&
                qsStartMargin == notifStartMargin
        ).isTrue()
    }

    @Test
    fun testSplitShadeLayout_childrenHaveInsideMarginsOfZero() {
        enableSplitShade()
        controller.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).endMargin).isEqualTo(0)
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).startMargin)
                .isEqualTo(0)
    }

    @Test
    fun testSplitShadeLayout_qsFrameHasHorizontalMarginsOfZero() {
        enableSplitShade()
        controller.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).endMargin).isEqualTo(0)
        assertThat(getConstraintSetLayout(R.id.qs_frame).startMargin).isEqualTo(0)
    }

    @Test
    fun testLargeScreenLayout_qsAndNotifsTopMarginIsOfHeaderHeight() {
        setLargeScreen()
        val largeScreenHeaderHeight = 100
        overrideResource(R.dimen.large_screen_shade_header_height, largeScreenHeaderHeight)

        controller.updateResources()

        assertThat(getConstraintSetLayout(R.id.qs_frame).topMargin)
                .isEqualTo(largeScreenHeaderHeight)
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).topMargin)
                .isEqualTo(largeScreenHeaderHeight)
    }

    @Test
    fun testSmallScreenLayout_qsAndNotifsTopMarginIsZero() {
        setSmallScreen()
        controller.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).topMargin).isEqualTo(0)
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).topMargin)
                .isEqualTo(0)
    }

    @Test
    fun testSinglePaneShadeLayout_qsFrameHasHorizontalMarginsSetToCorrectValue() {
        disableSplitShade()
        controller.updateResources()
        val notificationPanelMarginHorizontal = context.resources
                .getDimensionPixelSize(R.dimen.notification_panel_margin_horizontal)
        assertThat(getConstraintSetLayout(R.id.qs_frame).endMargin)
                .isEqualTo(notificationPanelMarginHorizontal)
        assertThat(getConstraintSetLayout(R.id.qs_frame).startMargin)
                .isEqualTo(notificationPanelMarginHorizontal)
    }

    @Test
    fun testSinglePaneShadeLayout_isAlignedToParent() {
        disableSplitShade()
        controller.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).endToEnd)
                .isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).startToStart)
                .isEqualTo(ConstraintSet.PARENT_ID)
    }

    @Test
    fun testAllChildrenOfNotificationContainer_haveIds() {
        // set dimen to 0 to avoid triggering updating bottom spacing
        overrideResource(R.dimen.split_shade_notifications_scrim_margin_bottom, 0)
        val container = NotificationsQuickSettingsContainer(context, null)
        container.removeAllViews()
        container.addView(newViewWithId(1))
        container.addView(newViewWithId(View.NO_ID))
        val controller = NotificationsQSContainerController(
                container,
                navigationModeController,
                overviewProxyService,
                largeScreenShadeHeaderController,
                featureFlags,
                delayableExecutor
        )
        controller.updateConstraints()

        assertThat(container.getChildAt(0).id).isEqualTo(1)
        assertThat(container.getChildAt(1).id).isNotEqualTo(View.NO_ID)
    }

    @Test
    fun testWindowInsetDebounce() {
        disableSplitShade()

        given(taskbarVisible = false,
            navigationMode = GESTURES_NAVIGATION,
            insets = emptyInsets(),
            applyImmediately = false)
        fakeSystemClock.advanceTime(INSET_DEBOUNCE_MILLIS / 2)
        windowInsetsCallback.accept(windowInsets().withStableBottom())

        delayableExecutor.advanceClockToLast()
        delayableExecutor.runAllReady()

        verify(notificationsQSContainer, never()).setQSContainerPaddingBottom(0)
        verify(notificationsQSContainer).setQSContainerPaddingBottom(STABLE_INSET_BOTTOM)
    }

    @Test
    fun testStartCustomizingWithDuration() {
        controller.setCustomizerShowing(true, 100L)
        verify(largeScreenShadeHeaderController).startCustomizingAnimation(true, 100L)
    }

    @Test
    fun testEndCustomizingWithDuration() {
        controller.setCustomizerShowing(true, 0L) // Only tracks changes
        reset(largeScreenShadeHeaderController)

        controller.setCustomizerShowing(false, 100L)
        verify(largeScreenShadeHeaderController).startCustomizingAnimation(false, 100L)
    }

    private fun disableSplitShade() {
        setSplitShadeEnabled(false)
    }

    private fun enableSplitShade() {
        setSplitShadeEnabled(true)
    }

    private fun setSplitShadeEnabled(enabled: Boolean) {
        overrideResource(R.bool.config_use_split_notification_shade, enabled)
        controller.updateResources()
    }

    private fun setSmallScreen() {
        setLargeScreenEnabled(false)
    }

    private fun setLargeScreen() {
        setLargeScreenEnabled(true)
    }

    private fun setLargeScreenEnabled(enabled: Boolean) {
        overrideResource(R.bool.config_use_large_screen_shade_header, enabled)
    }

    private fun given(
        taskbarVisible: Boolean,
        navigationMode: Int,
        insets: WindowInsets,
        applyImmediately: Boolean = true
    ) {
        Mockito.clearInvocations(notificationsQSContainer)
        taskbarVisibilityCallback.onTaskbarStatusUpdated(taskbarVisible, false)
        navigationModeCallback.onNavigationModeChanged(navigationMode)
        windowInsetsCallback.accept(insets)
        if (applyImmediately) {
            delayableExecutor.advanceClockToLast()
            delayableExecutor.runAllReady()
        }
    }

    fun then(
        expectedContainerPadding: Int,
        expectedNotificationsMargin: Int = NOTIFICATIONS_MARGIN,
        expectedQsPadding: Int = 0
    ) {
        verify(notificationsQSContainer)
                .setPadding(anyInt(), anyInt(), anyInt(), eq(expectedContainerPadding))
        verify(notificationsQSContainer).setNotificationsMarginBottom(expectedNotificationsMargin)
        verify(notificationsQSContainer)
                    .setQSContainerPaddingBottom(expectedQsPadding)
        Mockito.clearInvocations(notificationsQSContainer)
    }

    private fun windowInsets() = mock(WindowInsets::class.java, RETURNS_DEEP_STUBS)

    private fun emptyInsets() = mock(WindowInsets::class.java)

    private fun WindowInsets.withCutout(): WindowInsets {
        whenever(displayCutout.safeInsetBottom).thenReturn(CUTOUT_HEIGHT)
        return this
    }

    private fun WindowInsets.withStableBottom(): WindowInsets {
        whenever(stableInsetBottom).thenReturn(STABLE_INSET_BOTTOM)
        return this
    }

    private fun getConstraintSetLayout(@IdRes id: Int): ConstraintSet.Layout {
        return constraintSetCaptor.value.getConstraint(id).layout
    }

    private fun newViewWithId(id: Int): View {
        val view = View(mContext)
        view.id = id
        val layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // required as cloning ConstraintSet fails if view doesn't have layout params
        view.layoutParams = layoutParams
        return view
    }
}
