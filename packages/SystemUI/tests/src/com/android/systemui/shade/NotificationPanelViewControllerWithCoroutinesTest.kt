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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.shade

import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewStub
import androidx.test.filters.SmallTest
import com.android.internal.util.CollectionUtils
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.shared.NotificationsHeadsUpRefactor
import com.android.systemui.statusbar.notification.stack.data.repository.setNotifications
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class NotificationPanelViewControllerWithCoroutinesTest :
    NotificationPanelViewControllerBaseTest() {

    @Captor private lateinit var viewCaptor: ArgumentCaptor<View>

    override fun getMainDispatcher() = Dispatchers.Main.immediate

    @Test
    fun testDisableUserSwitcherAfterEnabling_returnsViewStubToTheViewHierarchy() = runTest {
        launch(Dispatchers.Main.immediate) { givenViewAttached() }
        advanceUntilIdle()

        whenever(mResources.getBoolean(com.android.internal.R.bool.config_keyguardUserSwitcher))
            .thenReturn(true)
        updateMultiUserSetting(true)
        clearInvocations(mView)

        updateMultiUserSetting(false)

        verify(mView, atLeastOnce()).addView(viewCaptor.capture(), anyInt())
        val userSwitcherStub =
            CollectionUtils.find(
                viewCaptor.getAllValues(),
                { view -> view.getId() == R.id.keyguard_user_switcher_stub }
            )
        assertThat(userSwitcherStub).isNotNull()
        assertThat(userSwitcherStub).isInstanceOf(ViewStub::class.java)
    }

    @Test
    fun testChangeSmallestScreenWidthAndUserSwitchEnabled_inflatesUserSwitchView() = runTest {
        launch(Dispatchers.Main.immediate) { givenViewAttached() }
        advanceUntilIdle()

        whenever(mView.findViewById<View>(R.id.keyguard_user_switcher_view)).thenReturn(null)
        updateSmallestScreenWidth(300)
        whenever(mResources.getBoolean(com.android.internal.R.bool.config_keyguardUserSwitcher))
            .thenReturn(true)
        whenever(mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user))
            .thenReturn(false)
        whenever(mUserManager.isUserSwitcherEnabled(false)).thenReturn(true)

        updateSmallestScreenWidth(800)

        verify(mUserSwitcherStubView).inflate()
    }

    @Test
    fun testFinishInflate_userSwitcherDisabled_doNotInflateUserSwitchView_initClock() = runTest {
        launch(Dispatchers.Main.immediate) { givenViewAttached() }
        advanceUntilIdle()

        whenever(mResources.getBoolean(com.android.internal.R.bool.config_keyguardUserSwitcher))
            .thenReturn(true)
        whenever(mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user))
            .thenReturn(false)
        whenever(mUserManager.isUserSwitcherEnabled(false /* showEvenIfNotActionable */))
            .thenReturn(false)

        mNotificationPanelViewController.onFinishInflate()

        verify(mUserSwitcherStubView, never()).inflate()
        verify(mKeyguardStatusViewController, times(3)).displayClock(LARGE, /* animate */ true)

        coroutineContext.cancelChildren()
    }

    @Test
    fun testReInflateViews_userSwitcherDisabled_doNotInflateUserSwitchView() = runTest {
        launch(Dispatchers.Main.immediate) { givenViewAttached() }
        advanceUntilIdle()

        whenever(mResources.getBoolean(com.android.internal.R.bool.config_keyguardUserSwitcher))
            .thenReturn(true)
        whenever(mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user))
            .thenReturn(false)
        whenever(mUserManager.isUserSwitcherEnabled(false /* showEvenIfNotActionable */))
            .thenReturn(false)

        mNotificationPanelViewController.reInflateViews()

        verify(mUserSwitcherStubView, never()).inflate()

        coroutineContext.cancelChildren()
    }

    @Test
    fun testDoubleTapRequired_Keyguard() = runTest {
        launch(Dispatchers.Main.immediate) {
            val listener = getFalsingTapListener()
            mStatusBarStateController.setState(KEYGUARD)

            listener.onAdditionalTapRequired()

            verify(mKeyguardIndicationController).showTransientIndication(anyInt())
        }
        advanceUntilIdle()
    }

    @Test
    fun doubleTapRequired_onKeyguard_usesPerformHapticFeedback() = runTest {
        launch(Dispatchers.Main.immediate) {
            val listener = getFalsingTapListener()
            mStatusBarStateController.setState(KEYGUARD)

            listener.onAdditionalTapRequired()
            verify(mKeyguardIndicationController).showTransientIndication(anyInt())
            verify(mVibratorHelper)
                .performHapticFeedback(eq(mView), eq(HapticFeedbackConstants.REJECT))
        }
        advanceUntilIdle()
    }

    @Test
    fun testDoubleTapRequired_ShadeLocked() = runTest {
        launch(Dispatchers.Main.immediate) {
            val listener = getFalsingTapListener()
            mStatusBarStateController.setState(SHADE_LOCKED)

            listener.onAdditionalTapRequired()

            verify(mTapAgainViewController).show()
        }
        advanceUntilIdle()
    }

    @Test
    fun doubleTapRequired_shadeLocked_usesPerformHapticFeedback() = runTest {
        launch(Dispatchers.Main.immediate) {
            val listener = getFalsingTapListener()
            mStatusBarStateController.setState(SHADE_LOCKED)

            listener.onAdditionalTapRequired()
            verify(mVibratorHelper)
                .performHapticFeedback(eq(mView), eq(HapticFeedbackConstants.REJECT))

            verify(mTapAgainViewController).show()
        }
        advanceUntilIdle()
    }

    @Test
    fun testOnAttachRefreshStatusBarState() = runTest {
        launch(Dispatchers.Main.immediate) {
            mStatusBarStateController.setState(KEYGUARD)
            whenever(mKeyguardStateController.isKeyguardFadingAway()).thenReturn(false)
            mOnAttachStateChangeListeners.forEach { it.onViewAttachedToWindow(mView) }
            verify(mKeyguardStatusViewController)
                .setKeyguardStatusViewVisibility(
                    KEYGUARD /*statusBarState*/,
                    false /*keyguardFadingAway*/,
                    false /*goingToFullShade*/,
                    SHADE /*oldStatusBarState*/
                )
        }
        advanceUntilIdle()
    }

    @Test
    fun onLayoutChange_shadeCollapsed_bottomAreaAlphaIsZero() = runTest {
        // GIVEN bottomAreaShadeAlpha was updated before
        mNotificationPanelViewController.maybeAnimateBottomAreaAlpha()

        // WHEN a layout change is triggered with the shade being closed
        triggerLayoutChange()

        // THEN the bottomAreaAlpha is zero
        val bottomAreaAlpha by collectLastValue(mFakeKeyguardRepository.bottomAreaAlpha)
        assertThat(bottomAreaAlpha).isEqualTo(0f)
    }

    @Test
    fun onShadeExpanded_bottomAreaAlphaIsFullyOpaque() = runTest {
        // GIVEN bottomAreaShadeAlpha was updated before
        mNotificationPanelViewController.maybeAnimateBottomAreaAlpha()

        // WHEN the shade expanded
        val transitionDistance = mNotificationPanelViewController.maxPanelTransitionDistance
        mNotificationPanelViewController.expandedHeight = transitionDistance.toFloat()

        // THEN the bottomAreaAlpha is fully opaque
        val bottomAreaAlpha by collectLastValue(mFakeKeyguardRepository.bottomAreaAlpha)
        assertThat(bottomAreaAlpha).isEqualTo(1f)
    }

    @Test
    @EnableFlags(NotificationsHeadsUpRefactor.FLAG_NAME)
    fun shadeExpanded_whenHunIsPresent() = runTest {
        launch(mainDispatcher) {
            givenViewAttached()

            // WHEN a pinned heads up is present
            mFakeHeadsUpNotificationRepository.setNotifications(
                fakeHeadsUpRowRepository("key", isPinned = true)
            )
        }
        advanceUntilIdle()

        // THEN the panel should be visible
        assertThat(mNotificationPanelViewController.isExpanded).isTrue()
    }

    @Test
    @EnableFlags(NotificationsHeadsUpRefactor.FLAG_NAME)
    fun shadeExpanded_whenHunIsAnimatingAway() = runTest {
        launch(mainDispatcher) {
            givenViewAttached()

            // WHEN a heads up is animating away
            mFakeHeadsUpNotificationRepository.isHeadsUpAnimatingAway.value = true
        }
        advanceUntilIdle()

        // THEN the panel should be visible
        assertThat(mNotificationPanelViewController.isExpanded).isTrue()
    }

    private fun fakeHeadsUpRowRepository(key: String, isPinned: Boolean = false) =
        FakeHeadsUpRowRepository(key = key, elementKey = Any()).apply {
            this.isPinned.value = isPinned
        }
}
