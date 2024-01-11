/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.fragments.FragmentHostManager
import com.android.systemui.fragments.FragmentService
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.navigationbar.NavigationModeController.ModeChangedListener
import com.android.systemui.plugins.qs.QS
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.function.Consumer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class NotificationsQSContainerControllerTest : SysuiTestCase() {

    private val view = mock<NotificationsQuickSettingsContainer>()
    private val navigationModeController = mock<NavigationModeController>()
    private val overviewProxyService = mock<OverviewProxyService>()
    private val shadeHeaderController = mock<ShadeHeaderController>()
    private val shadeInteractor = mock<ShadeInteractor>()
    private val fragmentService = mock<FragmentService>()
    private val fragmentHostManager = mock<FragmentHostManager>()
    private val notificationStackScrollLayoutController =
        mock<NotificationStackScrollLayoutController>()
    private val largeScreenHeaderHelper = mock<LargeScreenHeaderHelper>()

    @Captor lateinit var navigationModeCaptor: ArgumentCaptor<ModeChangedListener>
    @Captor lateinit var taskbarVisibilityCaptor: ArgumentCaptor<OverviewProxyListener>
    @Captor lateinit var windowInsetsCallbackCaptor: ArgumentCaptor<Consumer<WindowInsets>>
    @Captor lateinit var constraintSetCaptor: ArgumentCaptor<ConstraintSet>
    @Captor lateinit var attachStateListenerCaptor: ArgumentCaptor<View.OnAttachStateChangeListener>

    lateinit var underTest: NotificationsQSContainerController

    private lateinit var featureFlags: FakeFeatureFlags
    private lateinit var navigationModeCallback: ModeChangedListener
    private lateinit var taskbarVisibilityCallback: OverviewProxyListener
    private lateinit var windowInsetsCallback: Consumer<WindowInsets>
    private lateinit var fakeSystemClock: FakeSystemClock
    private lateinit var delayableExecutor: FakeExecutor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeSystemClock = FakeSystemClock()
        delayableExecutor = FakeExecutor(fakeSystemClock)
        mSetFlagsRule.enableFlags(KeyguardShadeMigrationNssl.FLAG_NAME)
        featureFlags = FakeFeatureFlags().apply { set(Flags.QS_CONTAINER_GRAPH_OPTIMIZER, true) }
        mContext.ensureTestableResources()
        whenever(view.context).thenReturn(mContext)
        whenever(view.resources).thenReturn(mContext.resources)

        whenever(fragmentService.getFragmentHostManager(any())).thenReturn(fragmentHostManager)

        whenever(shadeInteractor.isQsExpanded).thenReturn(MutableStateFlow(false))

        underTest =
            NotificationsQSContainerController(
                view,
                navigationModeController,
                overviewProxyService,
                shadeHeaderController,
                shadeInteractor,
                fragmentService,
                delayableExecutor,
                featureFlags,
                notificationStackScrollLayoutController,
                ResourcesSplitShadeStateController(),
                largeScreenHeaderHelperLazy = { largeScreenHeaderHelper }
            )

        overrideResource(R.dimen.split_shade_notifications_scrim_margin_bottom, SCRIM_MARGIN)
        overrideResource(R.dimen.notification_panel_margin_bottom, NOTIFICATIONS_MARGIN)
        overrideResource(R.bool.config_use_split_notification_shade, false)
        overrideResource(R.dimen.qs_footer_actions_bottom_padding, FOOTER_ACTIONS_PADDING)
        overrideResource(R.dimen.qs_footer_action_inset, FOOTER_ACTIONS_INSET)
        whenever(navigationModeController.addListener(navigationModeCaptor.capture()))
            .thenReturn(GESTURES_NAVIGATION)
        doNothing().`when`(overviewProxyService).addCallback(taskbarVisibilityCaptor.capture())
        doNothing().`when`(view).setInsetsChangedListener(windowInsetsCallbackCaptor.capture())
        doNothing().`when`(view).applyConstraints(constraintSetCaptor.capture())
        doNothing().`when`(view).addOnAttachStateChangeListener(attachStateListenerCaptor.capture())
        underTest.init()
        attachStateListenerCaptor.value.onViewAttachedToWindow(view)

        navigationModeCallback = navigationModeCaptor.value
        taskbarVisibilityCallback = taskbarVisibilityCaptor.value
        windowInsetsCallback = windowInsetsCallbackCaptor.value

        Mockito.clearInvocations(view)
    }

    @Test
    fun testSmallScreen_updateResources_splitShadeHeightIsSet() {
        overrideResource(R.bool.config_use_large_screen_shade_header, false)
        overrideResource(R.dimen.qs_header_height, 10)
        overrideResource(R.dimen.large_screen_shade_header_height, 20)

        // ensure the estimated height (would be 3 here) wouldn't impact this test case
        overrideResource(R.dimen.large_screen_shade_header_min_height, 1)
        overrideResource(R.dimen.new_qs_header_non_clickable_element_height, 1)

        underTest.updateResources()

        val captor = ArgumentCaptor.forClass(ConstraintSet::class.java)
        verify(view).applyConstraints(capture(captor))
        assertThat(captor.value.getHeight(R.id.split_shade_status_bar)).isEqualTo(10)
    }

    @Test
    fun testLargeScreen_updateResources_splitShadeHeightIsSet() {
        overrideResource(R.bool.config_use_large_screen_shade_header, true)
        overrideResource(R.dimen.qs_header_height, 10)
        overrideResource(R.dimen.large_screen_shade_header_height, 20)

        // ensure the estimated height (would be 3 here) wouldn't impact this test case
        overrideResource(R.dimen.large_screen_shade_header_min_height, 1)
        overrideResource(R.dimen.new_qs_header_non_clickable_element_height, 1)

        underTest.updateResources()

        val captor = ArgumentCaptor.forClass(ConstraintSet::class.java)
        verify(view).applyConstraints(capture(captor))
        assertThat(captor.value.getHeight(R.id.split_shade_status_bar)).isEqualTo(20)
    }

    @Test
    fun testSmallScreen_estimatedHeightIsLargerThanDimenValue_shadeHeightIsSetToEstimatedHeight() {
        overrideResource(R.bool.config_use_large_screen_shade_header, false)
        overrideResource(R.dimen.qs_header_height, 10)
        overrideResource(R.dimen.large_screen_shade_header_height, 20)

        // make the estimated height (would be 15 here) larger than qs_header_height
        overrideResource(R.dimen.large_screen_shade_header_min_height, 5)
        overrideResource(R.dimen.new_qs_header_non_clickable_element_height, 5)

        underTest.updateResources()

        val captor = ArgumentCaptor.forClass(ConstraintSet::class.java)
        verify(view).applyConstraints(capture(captor))
        assertThat(captor.value.getHeight(R.id.split_shade_status_bar)).isEqualTo(15)
    }

    @Test
    fun testTaskbarVisibleInSplitShade() {
        enableSplitShade()

        given(
            taskbarVisible = true,
            navigationMode = GESTURES_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(
            expectedContainerPadding = 0, // taskbar should disappear when shade is expanded
            expectedNotificationsMargin = NOTIFICATIONS_MARGIN,
            expectedQsPadding = NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET
        )

        given(
            taskbarVisible = true,
            navigationMode = BUTTONS_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(
            expectedContainerPadding = STABLE_INSET_BOTTOM,
            expectedNotificationsMargin = NOTIFICATIONS_MARGIN,
            expectedQsPadding = NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET
        )
    }

    @Test
    fun testTaskbarNotVisibleInSplitShade() {
        // when taskbar is not visible, it means we're on the home screen
        enableSplitShade()

        given(
            taskbarVisible = false,
            navigationMode = GESTURES_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(
            expectedContainerPadding = 0,
            expectedQsPadding = NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET
        )

        given(
            taskbarVisible = false,
            navigationMode = BUTTONS_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(
            expectedContainerPadding = 0, // qs goes full height as it's not obscuring nav buttons
            expectedNotificationsMargin = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN,
            expectedQsPadding = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET
        )
    }

    @Test
    fun testTaskbarNotVisibleInSplitShadeWithCutout() {
        enableSplitShade()

        given(
            taskbarVisible = false,
            navigationMode = GESTURES_NAVIGATION,
            insets = windowInsets().withCutout()
        )
        then(
            expectedContainerPadding = CUTOUT_HEIGHT,
            expectedQsPadding = NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET
        )

        given(
            taskbarVisible = false,
            navigationMode = BUTTONS_NAVIGATION,
            insets = windowInsets().withCutout().withStableBottom()
        )
        then(
            expectedContainerPadding = 0,
            expectedNotificationsMargin = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN,
            expectedQsPadding = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN - QS_PADDING_OFFSET
        )
    }

    @Test
    fun testTaskbarVisibleInSinglePaneShade() {
        disableSplitShade()

        given(
            taskbarVisible = true,
            navigationMode = GESTURES_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(expectedContainerPadding = 0, expectedQsPadding = STABLE_INSET_BOTTOM)

        given(
            taskbarVisible = true,
            navigationMode = BUTTONS_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(
            expectedContainerPadding = STABLE_INSET_BOTTOM,
            expectedQsPadding = STABLE_INSET_BOTTOM
        )
    }

    @Test
    fun testTaskbarNotVisibleInSinglePaneShade() {
        disableSplitShade()

        given(taskbarVisible = false, navigationMode = GESTURES_NAVIGATION, insets = emptyInsets())
        then(expectedContainerPadding = 0)

        given(
            taskbarVisible = false,
            navigationMode = GESTURES_NAVIGATION,
            insets = windowInsets().withCutout().withStableBottom()
        )
        then(expectedContainerPadding = CUTOUT_HEIGHT, expectedQsPadding = STABLE_INSET_BOTTOM)

        given(
            taskbarVisible = false,
            navigationMode = BUTTONS_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(
            expectedContainerPadding = 0,
            expectedNotificationsMargin = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN,
            expectedQsPadding = STABLE_INSET_BOTTOM
        )
    }

    @Test
    fun testDetailShowingInSinglePaneShade() {
        disableSplitShade()
        underTest.setDetailShowing(true)

        // always sets spacings to 0
        given(
            taskbarVisible = false,
            navigationMode = GESTURES_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(expectedContainerPadding = 0, expectedNotificationsMargin = 0)

        given(taskbarVisible = false, navigationMode = BUTTONS_NAVIGATION, insets = emptyInsets())
        then(expectedContainerPadding = 0, expectedNotificationsMargin = 0)
    }

    @Test
    fun testDetailShowingInSplitShade() {
        enableSplitShade()
        underTest.setDetailShowing(true)

        given(
            taskbarVisible = false,
            navigationMode = GESTURES_NAVIGATION,
            insets = windowInsets().withStableBottom()
        )
        then(expectedContainerPadding = 0)

        // should not influence spacing
        given(taskbarVisible = false, navigationMode = BUTTONS_NAVIGATION, insets = emptyInsets())
        then(expectedContainerPadding = 0)
    }

    @Test
    fun testNotificationsMarginBottomIsUpdated() {
        Mockito.clearInvocations(view)
        enableSplitShade()
        verify(view).setNotificationsMarginBottom(NOTIFICATIONS_MARGIN)

        overrideResource(R.dimen.notification_panel_margin_bottom, 100)
        disableSplitShade()
        verify(view).setNotificationsMarginBottom(100)
    }

    @Test
    fun testSplitShadeLayout_isAlignedToGuideline() {
        enableSplitShade()
        underTest.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).endToEnd).isEqualTo(R.id.qs_edge_guideline)
    }

    @Test
    fun testSinglePaneLayout_childrenHaveEqualMargins() {
        disableSplitShade()
        underTest.updateResources()
        val qsStartMargin = getConstraintSetLayout(R.id.qs_frame).startMargin
        val qsEndMargin = getConstraintSetLayout(R.id.qs_frame).endMargin
        assertThat(qsStartMargin == qsEndMargin).isTrue()
    }

    @Test
    fun testSplitShadeLayout_childrenHaveInsideMarginsOfZero() {
        enableSplitShade()
        underTest.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).endMargin).isEqualTo(0)
    }

    @Test
    fun testSplitShadeLayout_qsFrameHasHorizontalMarginsOfZero() {
        enableSplitShade()
        underTest.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).endMargin).isEqualTo(0)
        assertThat(getConstraintSetLayout(R.id.qs_frame).startMargin).isEqualTo(0)
    }

    @Test
    fun testLargeScreenLayout_qsAndNotifsTopMarginIsOfHeaderHeight() {
        setLargeScreen()
        val largeScreenHeaderHeight = 100
        overrideResource(R.dimen.large_screen_shade_header_height, largeScreenHeaderHeight)

        // ensure the estimated height (would be 30 here) wouldn't impact this test case
        overrideResource(R.dimen.large_screen_shade_header_min_height, 10)
        overrideResource(R.dimen.new_qs_header_non_clickable_element_height, 10)

        underTest.updateResources()

        assertThat(getConstraintSetLayout(R.id.qs_frame).topMargin)
            .isEqualTo(largeScreenHeaderHeight)
    }

    @Test
    fun testSmallScreenLayout_qsAndNotifsTopMarginIsZero() {
        setSmallScreen()
        underTest.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).topMargin).isEqualTo(0)
    }

    @Test
    fun testSinglePaneShadeLayout_qsFrameHasHorizontalMarginsSetToCorrectValue() {
        disableSplitShade()
        underTest.updateResources()
        val notificationPanelMarginHorizontal =
            mContext.resources.getDimensionPixelSize(R.dimen.notification_panel_margin_horizontal)
        assertThat(getConstraintSetLayout(R.id.qs_frame).endMargin)
            .isEqualTo(notificationPanelMarginHorizontal)
        assertThat(getConstraintSetLayout(R.id.qs_frame).startMargin)
            .isEqualTo(notificationPanelMarginHorizontal)
    }

    @Test
    fun testSinglePaneShadeLayout_isAlignedToParent() {
        disableSplitShade()
        underTest.updateResources()
        assertThat(getConstraintSetLayout(R.id.qs_frame).endToEnd)
            .isEqualTo(ConstraintSet.PARENT_ID)
    }

    @Test
    fun testAllChildrenOfNotificationContainer_haveIds() {
        // set dimen to 0 to avoid triggering updating bottom spacing
        overrideResource(R.dimen.split_shade_notifications_scrim_margin_bottom, 0)
        val container = NotificationsQuickSettingsContainer(mContext, null)
        container.removeAllViews()
        container.addView(newViewWithId(1))
        container.addView(newViewWithId(View.NO_ID))
        val controller =
            NotificationsQSContainerController(
                container,
                navigationModeController,
                overviewProxyService,
                shadeHeaderController,
                shadeInteractor,
                fragmentService,
                delayableExecutor,
                featureFlags,
                notificationStackScrollLayoutController,
                ResourcesSplitShadeStateController(),
                largeScreenHeaderHelperLazy = { largeScreenHeaderHelper }
            )
        controller.updateConstraints()

        assertThat(container.getChildAt(0).id).isEqualTo(1)
        assertThat(container.getChildAt(1).id).isNotEqualTo(View.NO_ID)
    }

    @Test
    fun testWindowInsetDebounce() {
        disableSplitShade()

        given(
            taskbarVisible = false,
            navigationMode = GESTURES_NAVIGATION,
            insets = emptyInsets(),
            applyImmediately = false
        )
        fakeSystemClock.advanceTime(INSET_DEBOUNCE_MILLIS / 2)
        windowInsetsCallback.accept(windowInsets().withStableBottom())

        delayableExecutor.advanceClockToLast()
        delayableExecutor.runAllReady()

        verify(view, never()).setQSContainerPaddingBottom(0)
        verify(view).setQSContainerPaddingBottom(STABLE_INSET_BOTTOM)
    }

    @Test
    fun testStartCustomizingWithDuration() {
        underTest.setCustomizerShowing(true, 100L)
        verify(shadeHeaderController).startCustomizingAnimation(true, 100L)
    }

    @Test
    fun testEndCustomizingWithDuration() {
        underTest.setCustomizerShowing(true, 0L) // Only tracks changes
        reset(shadeHeaderController)

        underTest.setCustomizerShowing(false, 100L)
        verify(shadeHeaderController).startCustomizingAnimation(false, 100L)
    }

    @Test
    fun testTagListenerAdded() {
        verify(fragmentHostManager).addTagListener(eq(QS.TAG), eq(view))
    }

    @Test
    fun testTagListenerRemoved() {
        attachStateListenerCaptor.value.onViewDetachedFromWindow(view)
        verify(fragmentHostManager).removeTagListener(eq(QS.TAG), eq(view))
    }

    private fun disableSplitShade() {
        setSplitShadeEnabled(false)
    }

    private fun enableSplitShade() {
        setSplitShadeEnabled(true)
    }

    private fun setSplitShadeEnabled(enabled: Boolean) {
        overrideResource(R.bool.config_use_split_notification_shade, enabled)
        underTest.updateResources()
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
        Mockito.clearInvocations(view)
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
        verify(view).setPadding(anyInt(), anyInt(), anyInt(), eq(expectedContainerPadding))
        verify(view).setNotificationsMarginBottom(expectedNotificationsMargin)
        verify(view).setQSContainerPaddingBottom(expectedQsPadding)
        Mockito.clearInvocations(view)
    }

    private fun windowInsets() = mock(WindowInsets::class.java, RETURNS_DEEP_STUBS)

    private fun emptyInsets() = mock(WindowInsets::class.java)

    private fun WindowInsets.withCutout(): WindowInsets {
        whenever(checkNotNull(displayCutout).safeInsetBottom).thenReturn(CUTOUT_HEIGHT)
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
        val layoutParams =
            ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        // required as cloning ConstraintSet fails if view doesn't have layout params
        view.layoutParams = layoutParams
        return view
    }

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
}
