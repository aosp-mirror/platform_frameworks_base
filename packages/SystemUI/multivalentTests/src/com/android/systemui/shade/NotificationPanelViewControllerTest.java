/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.shade;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.DejankUtils;
import com.android.systemui.flags.DisableSceneContainer;

import com.google.android.msdl.data.model.MSDLToken;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NotificationPanelViewControllerTest extends NotificationPanelViewControllerBaseTest {

    @Before
    public void before() {
        DejankUtils.setImmediate(true);
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_SHADE_EXPANDS_ON_STATUS_BAR_LONG_PRESS)
    public void onStatusBarLongPress_shadeExpands() {
        long downTime = 42L;
        // Start touch session with down event
        onTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1f, 1f, 0));
        // Status bar triggers long press expand
        mNotificationPanelViewController.onStatusBarLongPress(
                MotionEvent.obtain(downTime, downTime + 27L, MotionEvent.ACTION_MOVE, 1f, 1f, 0));
        assertThat(mNotificationPanelViewController.isExpanded()).isTrue();
        // Shade ignores the rest of the long press's touch session
        assertThat(onTouchEvent(
                MotionEvent.obtain(downTime, downTime + 42L, MotionEvent.ACTION_MOVE, 1f, 1f,
                        0))).isFalse();

        // Start new touch session
        long downTime2 = downTime + 100L;
        assertThat(onTouchEvent(
                MotionEvent.obtain(downTime2, downTime2, MotionEvent.ACTION_DOWN, 1f, 1f,
                        0))).isTrue();
        // Shade no longer ignoring touches
        assertThat(onTouchEvent(
                MotionEvent.obtain(downTime2, downTime2 + 2L, MotionEvent.ACTION_MOVE, 1f, 1f,
                        0))).isTrue();
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_SHADE_EXPANDS_ON_STATUS_BAR_LONG_PRESS)
    public void onStatusBarLongPress_qsExpands() {
        long downTime = 42L;
        // Start with shade already expanded
        mNotificationPanelViewController.setExpandedFraction(1F);

        // Start touch session with down event
        onTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1f, 1f, 0));
        // Status bar triggers long press expand
        mNotificationPanelViewController.onStatusBarLongPress(
                MotionEvent.obtain(downTime, downTime + 27L, MotionEvent.ACTION_MOVE, 1f, 1f, 0));
        assertThat(mNotificationPanelViewController.isExpanded()).isTrue();
        // Shade expands to QS
        verify(mQsController, atLeastOnce()).flingQs(0F, ShadeViewController.FLING_EXPAND);
        // Shade ignores the rest of the long press's touch session
        assertThat(onTouchEvent(
                MotionEvent.obtain(downTime, downTime + 42L, MotionEvent.ACTION_MOVE, 1f, 1f,
                        0))).isFalse();

        // Start new touch session
        long downTime2 = downTime + 100L;
        assertThat(onTouchEvent(
                MotionEvent.obtain(downTime2, downTime2, MotionEvent.ACTION_DOWN, 1f, 1f,
                        0))).isTrue();
        // Shade no longer ignoring touches
        assertThat(onTouchEvent(
                MotionEvent.obtain(downTime2, downTime2 + 2L, MotionEvent.ACTION_MOVE, 1f, 1f,
                        0))).isTrue();

    }

    @Test
    public void onInterceptTouchEvent_nsslMigrationOn_userActivity_not_called() {
        mTouchHandler.onInterceptTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));

        verify(mCentralSurfaces, times(0)).userActivity();
    }

    @Test
    @Ignore("b/341163515 - fails to clean up animators correctly")
    public void testSwipeWhileLocked_notifiesKeyguardState() {
        mStatusBarStateController.setState(KEYGUARD);

        // Fling expanded (cancelling the keyguard exit swipe). We should notify keyguard state that
        // the fling occurred and did not dismiss the keyguard.
        mNotificationPanelViewController.flingToHeight(
                0f, true /* expand */, 1000f, 1f, false);
        verify(mKeyguardStateController).notifyPanelFlingStart(false /* dismissKeyguard */);

        // Fling un-expanded, which is a keyguard exit fling when we're in KEYGUARD state.
        mNotificationPanelViewController.flingToHeight(
                0f, false /* expand */, 1000f, 1f, false);
        verify(mKeyguardStateController).notifyPanelFlingStart(true /* dismissKeyguard */);
    }

    @Test
    public void nsslFlagEnabled_allowOnlyExternalTouches() {

        // This sets the dozing state that is read when onMiddleClicked is eventually invoked.
        mTouchHandler.onTouch(mock(View.class), mDownMotionEvent);
        verify(mQsController, never()).disallowTouches();

        mNotificationPanelViewController.handleExternalInterceptTouch(mDownMotionEvent);
        verify(mQsController).disallowTouches();
    }

    @Test
    @DisableSceneContainer
    public void shadeExpanded_whenHunIsPresent() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        assertThat(mNotificationPanelViewController.isExpanded()).isTrue();
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_MSDL_FEEDBACK)
    public void performHapticFeedback_withMSDL_forGestureStart_deliversDragThresholdToken() {
        mNotificationPanelViewController
                .performHapticFeedback(HapticFeedbackConstants.GESTURE_START);

        assertThat(mMSDLPlayer.getLatestTokenPlayed())
                .isEqualTo(MSDLToken.SWIPE_THRESHOLD_INDICATOR);
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_MSDL_FEEDBACK)
    public void performHapticFeedback_withMSDL_forReject_deliversFailureToken() {
        mNotificationPanelViewController
                .performHapticFeedback(HapticFeedbackConstants.REJECT);

        assertThat(mMSDLPlayer.getLatestTokenPlayed()).isEqualTo(MSDLToken.FAILURE);
    }
}
