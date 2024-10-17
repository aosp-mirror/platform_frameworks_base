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

import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_CLOSED;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPEN;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPENING;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.Point;
import android.os.PowerManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.constraintlayout.widget.ConstraintSet;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.DejankUtils;
import com.android.systemui.flags.DisableSceneContainer;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.ExpandableView.OnHeightChangedListener;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm;

import com.google.android.msdl.data.model.MSDLToken;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NotificationPanelViewControllerTest extends NotificationPanelViewControllerBaseTest {

    @Before
    public void before() {
        DejankUtils.setImmediate(true);
    }

    /**
     * When the Back gesture starts (progress 0%), the scrim will stay at 100% scale (1.0f).
     */
    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testBackGesture_min_scrimAtMaxScale() {
        mNotificationPanelViewController.onBackProgressed(0.0f);
        verify(mScrimController).applyBackScaling(1.0f);
    }

    /**
     * When the Back gesture is at max (progress 100%), the scrim will be scaled to its minimum.
     */
    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testBackGesture_max_scrimAtMinScale() {
        mNotificationPanelViewController.onBackProgressed(1.0f);
        verify(mScrimController).applyBackScaling(
                NotificationPanelViewController.SHADE_BACK_ANIM_MIN_SCALE);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onNotificationHeightChangeWhileOnKeyguardWillComputeMaxKeyguardNotifications() {
        mStatusBarStateController.setState(KEYGUARD);
        ArgumentCaptor<OnHeightChangedListener> captor =
                ArgumentCaptor.forClass(OnHeightChangedListener.class);
        verify(mNotificationStackScrollLayoutController)
                .setOnHeightChangedListener(captor.capture());
        OnHeightChangedListener listener = captor.getValue();

        clearInvocations(mNotificationStackSizeCalculator);
        listener.onHeightChanged(mock(ExpandableView.class), false);

        verify(mNotificationStackSizeCalculator)
                .computeMaxKeyguardNotifications(any(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onNotificationHeightChangeWhileInShadeWillNotComputeMaxKeyguardNotifications() {
        mStatusBarStateController.setState(SHADE);
        ArgumentCaptor<OnHeightChangedListener> captor =
                ArgumentCaptor.forClass(OnHeightChangedListener.class);
        verify(mNotificationStackScrollLayoutController)
                .setOnHeightChangedListener(captor.capture());
        OnHeightChangedListener listener = captor.getValue();

        clearInvocations(mNotificationStackSizeCalculator);
        listener.onHeightChanged(mock(ExpandableView.class), false);

        verify(mNotificationStackSizeCalculator, never())
                .computeMaxKeyguardNotifications(any(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void computeMaxKeyguardNotifications_lockscreenToShade_returnsExistingMax() {
        when(mAmbientState.getFractionToShade()).thenReturn(0.5f);
        mNotificationPanelViewController.setMaxDisplayedNotifications(-1);

        // computeMaxKeyguardNotifications sets maxAllowed to 0 at minimum if it updates the value
        assertThat(mNotificationPanelViewController.computeMaxKeyguardNotifications())
                .isEqualTo(-1);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void computeMaxKeyguardNotifications_noTransition_updatesMax() {
        when(mAmbientState.getFractionToShade()).thenReturn(0f);
        mNotificationPanelViewController.setMaxDisplayedNotifications(-1);

        // computeMaxKeyguardNotifications sets maxAllowed to 0 at minimum if it updates the value
        assertThat(mNotificationPanelViewController.computeMaxKeyguardNotifications())
                .isNotEqualTo(-1);
    }

    @Test
    @Ignore("b/261472011 - Test appears inconsistent across environments")
    public void getVerticalSpaceForLockscreenNotifications_useLockIconBottomPadding_returnsSpaceAvailable() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 180,
                /* lockIconPadding= */ 20,
                /* indicationPadding= */ 0,
                /* ambientPadding= */ 0);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenNotifications())
                .isEqualTo(80);
    }

    @Test
    @Ignore("b/261472011 - Test appears inconsistent across environments")
    public void getVerticalSpaceForLockscreenNotifications_useIndicationBottomPadding_returnsSpaceAvailable() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 180,
                /* lockIconPadding= */ 0,
                /* indicationPadding= */ 30,
                /* ambientPadding= */ 0);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenNotifications())
                .isEqualTo(70);
    }

    @Test
    @Ignore("b/261472011 - Test appears inconsistent across environments")
    public void getVerticalSpaceForLockscreenNotifications_useAmbientBottomPadding_returnsSpaceAvailable() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 180,
                /* lockIconPadding= */ 0,
                /* indicationPadding= */ 0,
                /* ambientPadding= */ 40);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenNotifications())
                .isEqualTo(60);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getVerticalSpaceForLockscreenShelf_useLockIconBottomPadding_returnsShelfHeight() {
        enableSplitShade(/* enabled= */ false);
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 20,
                /* indicationPadding= */ 0,
                /* ambientPadding= */ 0);

        when(mNotificationStackScrollLayoutController.getShelfHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(5);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(5);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getVerticalSpaceForLockscreenShelf_useIndicationBottomPadding_returnsZero() {
        enableSplitShade(/* enabled= */ false);
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 0,
                /* indicationPadding= */ 30,
                /* ambientPadding= */ 0);

        when(mNotificationStackScrollLayoutController.getShelfHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(0);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(0);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getVerticalSpaceForLockscreenShelf_useAmbientBottomPadding_returnsZero() {
        enableSplitShade(/* enabled= */ false);
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 0,
                /* indicationPadding= */ 0,
                /* ambientPadding= */ 40);

        when(mNotificationStackScrollLayoutController.getShelfHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(0);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(0);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getVerticalSpaceForLockscreenShelf_useLockIconPadding_returnsLessThanShelfHeight() {
        enableSplitShade(/* enabled= */ false);
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 10,
                /* indicationPadding= */ 8,
                /* ambientPadding= */ 0);

        when(mNotificationStackScrollLayoutController.getShelfHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(2);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(2);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getVerticalSpaceForLockscreenShelf_splitShade() {
        enableSplitShade(/* enabled= */ true);
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 10,
                /* indicationPadding= */ 8,
                /* ambientPadding= */ 0);

        when(mNotificationStackScrollLayoutController.getShelfHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(0);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(0);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testSetPanelScrimMinFractionWhenHeadsUpIsDragged() {
        mNotificationPanelViewController.setHeadsUpDraggingStartingHeight(
                mNotificationPanelViewController.getMaxPanelHeight() / 2);
        verify(mNotificationShadeDepthController).setPanelPullDownMinFraction(eq(0.5f));
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testSetDozing_notifiesNsslAndStateController() {
        mNotificationPanelViewController.setDozing(true /* dozing */, false /* animate */);
        verify(mNotificationStackScrollLayoutController).setDozing(eq(true), eq(false));
        assertThat(mStatusBarStateController.getDozeAmount()).isEqualTo(1f);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testOnDozeAmountChanged_positionClockAndNotificationsUsesUdfpsLocation() {
        // GIVEN UDFPS is enrolled and we're on the keyguard
        final Point udfpsLocationCenter = new Point(0, 100);
        final float udfpsRadius = 10f;
        when(mUpdateMonitor.isUdfpsEnrolled()).thenReturn(true);
        when(mAuthController.getUdfpsLocation()).thenReturn(udfpsLocationCenter);
        when(mAuthController.getUdfpsRadius()).thenReturn(udfpsRadius);
        mNotificationPanelViewController.getStatusBarStateListener().onStateChanged(KEYGUARD);

        // WHEN the doze amount changes
        mNotificationPanelViewController.mClockPositionAlgorithm = mock(
                KeyguardClockPositionAlgorithm.class);
        mNotificationPanelViewController.getStatusBarStateListener().onDozeAmountChanged(1f, 1f);

        // THEN the clock positions accounts for the UDFPS location & its worst case burn in
        final float udfpsTop = udfpsLocationCenter.y - udfpsRadius - mMaxUdfpsBurnInOffsetY;
        verify(mNotificationPanelViewController.mClockPositionAlgorithm).setup(
                anyInt(),
                anyFloat(),
                anyInt(),
                anyInt(),
                anyInt(),
                /* darkAmount */ eq(1f),
                anyFloat(),
                anyBoolean(),
                anyInt(),
                anyFloat(),
                anyInt(),
                anyBoolean(),
                /* udfpsTop */ eq(udfpsTop),
                anyFloat(),
                anyBoolean()
        );
    }


    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testSetExpandedHeight() {
        mNotificationPanelViewController.setExpandedHeight(200);
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testOnTouchEvent_expansionCanBeBlocked() {
        onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0));
        onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 200f, 0));
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);

        mNotificationPanelViewController.blockExpansionForCurrentTouch();
        onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 300f, 0));
        // Expansion should not have changed because it was blocked
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void test_pulsing_onTouchEvent_noTracking() {
        // GIVEN device is pulsing
        mNotificationPanelViewController.setPulsing(true);

        // WHEN touch DOWN & MOVE events received
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 200f /* y */,
                0 /* metaState */));

        // THEN touch is NOT tracked (since the device is pulsing)
        assertThat(mNotificationPanelViewController.isTracking()).isFalse();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void alternateBouncerVisible_onTouchEvent_notHandled() {
        // GIVEN alternate bouncer is visible
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // WHEN touch DOWN event received; THEN touch is NOT handled
        assertThat(onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */))).isFalse();

        // WHEN touch MOVE event received; THEN touch is NOT handled
        assertThat(onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 200f /* y */,
                0 /* metaState */))).isFalse();

    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void test_onTouchEvent_startTracking() {
        // GIVEN device is NOT pulsing
        mNotificationPanelViewController.setPulsing(false);

        // WHEN touch DOWN & MOVE events received
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 200f /* y */,
                0 /* metaState */));

        // THEN touch is tracked
        assertThat(mNotificationPanelViewController.isTracking()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onInterceptTouchEvent_nsslMigrationOff_userActivity() {
        mTouchHandler.onInterceptTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));

        verify(mCentralSurfaces).userActivity();
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onInterceptTouchEvent_nsslMigrationOn_userActivity_not_called() {
        mTouchHandler.onInterceptTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));

        verify(mCentralSurfaces, times(0)).userActivity();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testOnTouchEvent_expansionResumesAfterBriefTouch() {
        mFalsingManager.setIsClassifierEnabled(true);
        mFalsingManager.setIsFalseTouch(false);
        mNotificationPanelViewController.setForceFlingAnimationForTest(true);
        // Start shade collapse with swipe up
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 300f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_UP, 0f /* x */, 300f /* y */,
                0 /* metaState */));

        assertThat(mNotificationPanelViewController.isClosing()).isTrue();
        assertThat(mNotificationPanelViewController.isFlinging()).isTrue();

        // simulate touch that does not exceed touch slop
        onTouchEvent(MotionEvent.obtain(2L /* downTime */,
                2L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 300f /* y */,
                0 /* metaState */));

        mNotificationPanelViewController.setTouchSlopExceeded(false);

        onTouchEvent(MotionEvent.obtain(2L /* downTime */,
                2L /* eventTime */, MotionEvent.ACTION_UP, 0f /* x */, 300f /* y */,
                0 /* metaState */));

        // fling should still be called after a touch that does not exceed touch slop
        assertThat(mNotificationPanelViewController.isClosing()).isTrue();
        assertThat(mNotificationPanelViewController.isFlinging()).isTrue();
        mNotificationPanelViewController.setForceFlingAnimationForTest(false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testA11y_initializeNode() {
        AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();
        mAccessibilityDelegate.onInitializeAccessibilityNodeInfo(mView, nodeInfo);

        List<AccessibilityNodeInfo.AccessibilityAction> actionList = nodeInfo.getActionList();
        assertThat(actionList).containsAtLeastElementsIn(
                new AccessibilityNodeInfo.AccessibilityAction[] {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP}
        );
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testA11y_scrollForward() {
        mAccessibilityDelegate.performAccessibilityAction(
                mView,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId(),
                null);

        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testA11y_scrollUp() {
        mAccessibilityDelegate.performAccessibilityAction(
                mView,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(),
                null);

        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testKeyguardStatusViewInSplitShade_changesConstraintsDependingOnNotifications() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        mNotificationPanelViewController.updateResources();
        assertThat(getConstraintSetLayout(R.id.keyguard_status_view).endToEnd)
                .isEqualTo(R.id.qs_edge_guideline);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);
        mNotificationPanelViewController.updateResources();
        assertThat(getConstraintSetLayout(R.id.keyguard_status_view).endToEnd)
                .isEqualTo(ConstraintSet.PARENT_ID);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_splitShade_dozing_alwaysDozingOn_isCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ true, /* dozingAlwaysOn= */ true);

        assertKeyguardStatusViewCentered();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_splitShade_dozing_alwaysDozingOff_isNotCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ true, /* dozingAlwaysOn= */ false);

        assertKeyguardStatusViewNotCentered();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_splitShade_notDozing_alwaysDozingOn_isNotCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ true);

        assertKeyguardStatusViewNotCentered();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_splitShade_pulsing_isNotCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        when(mNotificationListContainer.hasPulsingNotifications()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ false);

        assertKeyguardStatusViewNotCentered();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_splitShade_notPulsing_isNotCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        when(mNotificationListContainer.hasPulsingNotifications()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ false);

        assertKeyguardStatusViewNotCentered();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_singleShade_isCentered() {
        enableSplitShade(/* enabled= */ false);
        // The conditions below would make the clock NOT be centered on split shade.
        // On single shade it should always be centered though.
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        when(mNotificationListContainer.hasPulsingNotifications()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ false);

        assertKeyguardStatusViewCentered();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_willPlayDelayedDoze_isCentered_thenNot() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        mNotificationPanelViewController.setWillPlayDelayedDozeAmountAnimation(true);
        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ false);
        assertKeyguardStatusViewCentered();

        mNotificationPanelViewController.setWillPlayDelayedDozeAmountAnimation(false);
        assertKeyguardStatusViewNotCentered();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_willPlayDelayedDoze_notifiesKeyguardMediaController() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        mNotificationPanelViewController.setWillPlayDelayedDozeAmountAnimation(true);

        verify(mKeyguardMediaController).setDozeWakeUpAnimationWaiting(true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void keyguardStatusView_willPlayDelayedDoze_isCentered_thenStillCenteredIfNoNotifs() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        mNotificationPanelViewController.setWillPlayDelayedDozeAmountAnimation(true);
        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ false);
        assertKeyguardStatusViewCentered();

        mNotificationPanelViewController.setWillPlayDelayedDozeAmountAnimation(false);
        assertKeyguardStatusViewCentered();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onKeyguardStatusViewHeightChange_animatesNextTopPaddingChangeForNSSL() {
        ArgumentCaptor<View.OnLayoutChangeListener> captor =
                ArgumentCaptor.forClass(View.OnLayoutChangeListener.class);
        verify(mKeyguardStatusView).addOnLayoutChangeListener(captor.capture());
        View.OnLayoutChangeListener listener = captor.getValue();

        clearInvocations(mNotificationStackScrollLayoutController);

        when(mKeyguardStatusView.getHeight()).thenReturn(0);
        listener.onLayoutChange(mKeyguardStatusView, /* left= */ 0, /* top= */ 0, /* right= */
                0, /* bottom= */ 0, /* oldLeft= */ 0, /* oldTop= */ 0, /* oldRight= */
                0, /* oldBottom = */ 200);

        verify(mNotificationStackScrollLayoutController).animateNextTopPaddingChange();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testCanCollapsePanelOnTouch_trueForKeyGuard() {
        mStatusBarStateController.setState(KEYGUARD);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testCanCollapsePanelOnTouch_trueWhenScrolledToBottom() {
        mStatusBarStateController.setState(SHADE);
        when(mNotificationStackScrollLayoutController.isScrolledToBottom()).thenReturn(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testCanCollapsePanelOnTouch_trueWhenInSettings() {
        mStatusBarStateController.setState(SHADE);
        when(mQsController.getExpanded()).thenReturn(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testCanCollapsePanelOnTouch_falseInDualPaneShade() {
        mStatusBarStateController.setState(SHADE);
        enableSplitShade(/* enabled= */ true);
        when(mQsController.getExpanded()).thenReturn(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isFalse();
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
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testCancelSwipeWhileLocked_notifiesKeyguardState() {
        mStatusBarStateController.setState(KEYGUARD);

        // Fling expanded (cancelling the keyguard exit swipe). We should notify keyguard state that
        // the fling occurred and did not dismiss the keyguard.
        mNotificationPanelViewController.flingToHeight(
                0f, true /* expand */, 1000f, 1f, false);
        mNotificationPanelViewController.cancelHeightAnimator();
        verify(mKeyguardStateController).notifyPanelFlingEnd();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testSwipe_exactlyToTarget_notifiesNssl() {
        // No over-expansion
        mNotificationPanelViewController.setOverExpansion(0f);
        // Fling to a target that is equal to the current position (i.e. a no-op fling).
        mNotificationPanelViewController.flingToHeight(
                0f,
                true,
                mNotificationPanelViewController.getExpandedHeight(),
                1f,
                false);
        // Verify that the NSSL is notified that the panel is *not* flinging.
        verify(mNotificationStackScrollLayoutController).setPanelFlinging(false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testRotatingToSplitShadeWithQsExpanded_transitionsToShadeLocked() {
        mStatusBarStateController.setState(KEYGUARD);
        when(mQsController.getExpanded()).thenReturn(true);

        enableSplitShade(true);

        assertThat(mStatusBarStateController.getState()).isEqualTo(SHADE_LOCKED);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testUnlockedSplitShadeTransitioningToKeyguard_closesQS() {
        enableSplitShade(true);
        mStatusBarStateController.setState(SHADE);
        mStatusBarStateController.setState(KEYGUARD);

        verify(mQsController).closeQs();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testLockedSplitShadeTransitioningToKeyguard_closesQS() {
        enableSplitShade(true);
        mStatusBarStateController.setState(SHADE_LOCKED);
        mStatusBarStateController.setState(KEYGUARD);

        verify(mQsController).closeQs();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testSwitchesToCorrectClockInSinglePaneShade() {
        mStatusBarStateController.setState(KEYGUARD);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testSwitchesToCorrectClockInSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController, times(2))
                .displayClock(LARGE, /* animate */ true);
        verify(mKeyguardStatusViewController, never())
                .displayClock(SMALL, /* animate */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testHasNotifications_switchesToLargeClockWhenEnteringSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);

        enableSplitShade(/* enabled= */ true);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testNoNotifications_switchesToLargeClockWhenEnteringSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);

        enableSplitShade(/* enabled= */ true);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testHasNotifications_switchesToSmallClockWhenExitingSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);

        enableSplitShade(/* enabled= */ false);

        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testNoNotifications_switchesToLargeClockWhenExitingSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);

        enableSplitShade(/* enabled= */ false);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void clockSize_mediaShowing_inSplitShade_onAod_isLarge() {
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        when(mMediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        clearInvocations(mKeyguardStatusViewController);

        mNotificationPanelViewController.setDozing(/* dozing= */ true, /* animate= */ false);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate= */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void clockSize_mediaShowing_inSplitShade_screenOff_notAod_isSmall() {
        when(mDozeParameters.getAlwaysOn()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        when(mMediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        clearInvocations(mKeyguardStatusViewController);

        mNotificationPanelViewController.setDozing(/* dozing= */ true, /* animate= */ false);

        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate= */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onQsSetExpansionHeightCalled_qsFullyExpandedOnKeyguard_showNSSL() {
        // GIVEN
        mStatusBarStateController.setState(KEYGUARD);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(false);
        when(mQsController.getFullyExpanded()).thenReturn(true);
        when(mQsController.getExpanded()).thenReturn(true);

        // WHEN
        int transitionDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);

        // THEN
        // We are interested in the last value of the stack alpha.
        ArgumentCaptor<Float> alphaCaptor = ArgumentCaptor.forClass(Float.class);
        verify(mNotificationStackScrollLayoutController, atLeastOnce())
                .setMaxAlphaForKeyguard(alphaCaptor.capture(), any());
        assertThat(alphaCaptor.getValue()).isEqualTo(1.0f);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onQsSetExpansionHeightCalled_qsFullyExpandedOnKeyguard_hideNSSL() {
        // GIVEN
        mStatusBarStateController.setState(KEYGUARD);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(false);
        when(mQsController.getFullyExpanded()).thenReturn(false);
        when(mQsController.getExpanded()).thenReturn(true);

        // WHEN
        int transitionDistance = mNotificationPanelViewController
                .getMaxPanelTransitionDistance() / 2;
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);

        // THEN
        // We are interested in the last value of the stack alpha.
        ArgumentCaptor<Float> alphaCaptor = ArgumentCaptor.forClass(Float.class);
        verify(mNotificationStackScrollLayoutController, atLeastOnce())
                .setMaxAlphaForKeyguard(alphaCaptor.capture(), any());
        assertThat(alphaCaptor.getValue()).isEqualTo(0.0f);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testSwitchesToBigClockInSplitShadeOnAodAnimateDisabled() {
        when(mScreenOffAnimationController.shouldAnimateClockChange()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);
        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);

        mNotificationPanelViewController.setDozing(true, false);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void switchesToBigClockInSplitShadeOn_landFlagOn_ForceSmallClock() {
        when(mScreenOffAnimationController.shouldAnimateClockChange()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ false);
        mNotificationPanelViewController.setDozing(false, false);
        mFeatureFlags.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, true);
        when(mResources.getBoolean(R.bool.force_small_clock_on_lockscreen)).thenReturn(true);
        when(mMediaDataManager.hasActiveMedia()).thenReturn(false);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);
        clearInvocations(mKeyguardStatusViewController);

        enableSplitShade(/* enabled= */ true);
        mNotificationPanelViewController.updateResources();

        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate */ false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void switchesToBigClockInSplitShadeOn_landFlagOff_DontForceSmallClock() {
        when(mScreenOffAnimationController.shouldAnimateClockChange()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ false);
        mNotificationPanelViewController.setDozing(false, false);
        mFeatureFlags.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, false);
        when(mResources.getBoolean(R.bool.force_small_clock_on_lockscreen)).thenReturn(true);
        when(mMediaDataManager.hasActiveMedia()).thenReturn(false);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);
        clearInvocations(mKeyguardStatusViewController);

        enableSplitShade(/* enabled= */ true);
        mNotificationPanelViewController.updateResources();

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testDisplaysSmallClockOnLockscreenInSplitShadeWhenMediaIsPlaying() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);
        when(mMediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true);

        // one notification + media player visible
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(true);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate */ true);

        // only media player visible
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()).thenReturn(false);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController, times(2)).displayClock(SMALL, true);
        verify(mKeyguardStatusViewController, never()).displayClock(LARGE, /* animate */ true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testFoldToAodAnimationCleansupInAnimationEnd() {
        ArgumentCaptor<Animator.AnimatorListener> animCaptor =
                ArgumentCaptor.forClass(Animator.AnimatorListener.class);
        ArgumentCaptor<ValueAnimator.AnimatorUpdateListener> updateCaptor =
                ArgumentCaptor.forClass(ValueAnimator.AnimatorUpdateListener.class);

        // Start fold animation & Capture Listeners
        mNotificationPanelViewController.getShadeFoldAnimator()
                .startFoldToAodAnimation(() -> {}, () -> {}, () -> {});
        verify(mViewPropertyAnimator).setListener(animCaptor.capture());
        verify(mViewPropertyAnimator).setUpdateListener(updateCaptor.capture());

        // End animation and validate listeners were unset
        animCaptor.getValue().onAnimationEnd(null);
        verify(mViewPropertyAnimator).setListener(null);
        verify(mViewPropertyAnimator).setUpdateListener(null);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testExpandWithQsMethodIsUsingLockscreenTransitionController() {
        enableSplitShade(/* enabled= */ true);
        mStatusBarStateController.setState(KEYGUARD);

        mNotificationPanelViewController.expandToQs();

        verify(mLockscreenShadeTransitionController).goToLockedShade(
                /* expandedView= */null, /* needsQSAnimation= */true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void setKeyguardStatusBarAlpha_setsAlphaOnKeyguardStatusBarController() {
        float statusBarAlpha = 0.5f;

        mNotificationPanelViewController.setKeyguardStatusBarAlpha(statusBarAlpha);

        verify(mKeyguardStatusBarViewController).setAlpha(statusBarAlpha);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testQsToBeImmediatelyExpandedWhenOpeningPanelInSplitShade() {
        enableSplitShade(/* enabled= */ true);
        mShadeExpansionStateManager.updateState(STATE_OPEN);
        verify(mQsController).setExpandImmediate(false);

        mShadeExpansionStateManager.updateState(STATE_CLOSED);
        verify(mQsController, times(2)).setExpandImmediate(false);

        mShadeExpansionStateManager.updateState(STATE_OPENING);
        verify(mQsController).setExpandImmediate(true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testQsNotToBeImmediatelyExpandedWhenGoingFromUnlockedToLocked() {
        enableSplitShade(/* enabled= */ true);
        mShadeExpansionStateManager.updateState(STATE_CLOSED);

        mStatusBarStateController.setState(KEYGUARD);
        // going to lockscreen would trigger STATE_OPENING
        mShadeExpansionStateManager.updateState(STATE_OPENING);

        verify(mQsController, never()).setExpandImmediate(true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testQsImmediateResetsWhenPanelOpensOrCloses() {
        mShadeExpansionStateManager.updateState(STATE_OPEN);
        mShadeExpansionStateManager.updateState(STATE_CLOSED);
        verify(mQsController, times(2)).setExpandImmediate(false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testQsExpansionChangedToDefaultWhenRotatingFromOrToSplitShade() {
        when(mCommandQueue.panelsEnabled()).thenReturn(true);

        // to make sure shade is in expanded state
        mNotificationPanelViewController.startInputFocusTransfer();

        // switch to split shade from portrait (default state)
        enableSplitShade(/* enabled= */ true);
        verify(mQsController).setExpanded(true);

        // switch to portrait from split shade
        enableSplitShade(/* enabled= */ false);
        verify(mQsController).setExpanded(false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void testPanelClosedWhenClosingQsInSplitShade() {
        mShadeExpansionStateManager.onPanelExpansionChanged(/* fraction= */ 1,
                /* expanded= */ true, /* tracking= */ false);
        enableSplitShade(/* enabled= */ true);
        mNotificationPanelViewController.setExpandedFraction(1f);

        assertThat(mNotificationPanelViewController.isClosing()).isFalse();
        mNotificationPanelViewController.animateCollapseQs(false);

        assertThat(mNotificationPanelViewController.isClosing()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getMaxPanelTransitionDistance_expanding_inSplitShade_returnsSplitShadeFullTransitionDistance() {
        enableSplitShade(true);
        mNotificationPanelViewController.expandToQs();

        int maxDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();

        assertThat(maxDistance).isEqualTo(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void isExpandingOrCollapsing_returnsTrue_whenQsLockscreenDragInProgress() {
        when(mQsController.getLockscreenShadeDragProgress()).thenReturn(0.5f);
        assertThat(mNotificationPanelViewController.isExpandingOrCollapsing()).isTrue();
    }


    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getMaxPanelTransitionDistance_inSplitShade_withHeadsUp_returnsBiggerValue() {
        enableSplitShade(true);
        mNotificationPanelViewController.expandToQs();
        when(mHeadsUpManager.isTrackingHeadsUp()).thenReturn(true);
        when(mQsController.calculatePanelHeightExpanded(anyInt())).thenReturn(10000);
        mNotificationPanelViewController.setHeadsUpDraggingStartingHeight(
                SPLIT_SHADE_FULL_TRANSITION_DISTANCE);

        int maxDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();

        // make sure we're ignoring the placeholder value for Qs max height
        assertThat(maxDistance).isLessThan(10000);
        assertThat(maxDistance).isGreaterThan(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getMaxPanelTransitionDistance_expandingSplitShade_keyguard_returnsNonSplitShadeValue() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(true);
        mNotificationPanelViewController.expandToQs();

        int maxDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();

        assertThat(maxDistance).isNotEqualTo(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getMaxPanelTransitionDistance_expanding_notSplitShade_returnsNonSplitShadeValue() {
        enableSplitShade(false);
        mNotificationPanelViewController.expandToQs();

        int maxDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();

        assertThat(maxDistance).isNotEqualTo(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onLayoutChange_fullWidth_updatesQSWithFullWithTrue() {
        setIsFullWidth(true);

        verify(mQsController).setNotificationPanelFullWidth(true);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onLayoutChange_notFullWidth_updatesQSWithFullWithFalse() {
        setIsFullWidth(false);

        verify(mQsController).setNotificationPanelFullWidth(false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onLayoutChange_qsNotSet_doesNotCrash() {
        mQuickSettingsController.setQs(null);

        triggerLayoutChange();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onEmptySpaceClicked_notDozingAndOnKeyguard_requestsFaceAuth() {
        StatusBarStateController.StateListener statusBarStateListener =
                mNotificationPanelViewController.getStatusBarStateListener();
        statusBarStateListener.onStateChanged(KEYGUARD);
        mNotificationPanelViewController.setDozing(false, false);

        // This sets the dozing state that is read when onMiddleClicked is eventually invoked.
        mTouchHandler.onTouch(mock(View.class), mDownMotionEvent);
        mEmptySpaceClickListenerCaptor.getValue().onEmptySpaceClicked(0, 0);

        verify(mDeviceEntryFaceAuthInteractor).onNotificationPanelClicked();
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void nsslFlagEnabled_allowOnlyExternalTouches() {

        // This sets the dozing state that is read when onMiddleClicked is eventually invoked.
        mTouchHandler.onTouch(mock(View.class), mDownMotionEvent);
        verify(mQsController, never()).disallowTouches();

        mNotificationPanelViewController.handleExternalInterceptTouch(mDownMotionEvent);
        verify(mQsController).disallowTouches();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onSplitShadeChanged_duringShadeExpansion_resetsOverScrollState() {
        // There was a bug where there was left-over overscroll state after going from split shade
        // to single shade.
        // Since on single shade we don't set overscroll values on QS nor Scrim, those values that
        // were there from split shade were never reset.
        // To prevent this, we will reset all overscroll state.
        enableSplitShade(true);
        reset(mQsController, mScrimController, mNotificationStackScrollLayoutController);

        mNotificationPanelViewController.setOverExpansion(123);
        verify(mQsController).setOverScrollAmount(123);
        verify(mScrimController).setNotificationsOverScrollAmount(123);
        verify(mNotificationStackScrollLayoutController).setOverExpansion(123);

        enableSplitShade(false);
        verify(mQsController).setOverScrollAmount(0);
        verify(mScrimController).setNotificationsOverScrollAmount(0);
        verify(mNotificationStackScrollLayoutController).setOverExpansion(0);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onSplitShadeChanged_alwaysResetsOverScrollState() {
        enableSplitShade(true);
        enableSplitShade(false);

        verify(mQsController, times(2)).setOverScrollAmount(0);
        verify(mScrimController, times(2)).setNotificationsOverScrollAmount(0);
        verify(mNotificationStackScrollLayoutController, times(2)).setOverExpansion(0);
        verify(mNotificationStackScrollLayoutController, times(2)).setOverScrollAmount(0);
    }

    /**
     * When shade is flinging to close and this fling is not intercepted,
     * {@link AmbientState#setIsClosing(boolean)} should be called before
     * {@link NotificationStackScrollLayoutController#onExpansionStopped()}
     * to ensure scrollY can be correctly set to be 0
     */
    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onShadeFlingClosingEnd_mAmbientStateSetClose_thenOnExpansionStopped() {
        // Given: Shade is expanded
        mNotificationPanelViewController.notifyExpandingFinished();
        mNotificationPanelViewController.setClosing(false);

        // When: Shade flings to close not canceled
        mNotificationPanelViewController.notifyExpandingStarted();
        mNotificationPanelViewController.setClosing(true);
        mNotificationPanelViewController.onFlingEnd(false);

        // Then: AmbientState's mIsClosing should be set to false
        // before mNotificationStackScrollLayoutController.onExpansionStopped() is called
        // to ensure NotificationStackScrollLayout.resetScrollPosition() -> resetScrollPosition
        // -> setOwnScrollY(0) can set scrollY to 0 when shade is closed
        InOrder inOrder = inOrder(mAmbientState, mNotificationStackScrollLayoutController);
        inOrder.verify(mAmbientState).setIsClosing(false);
        inOrder.verify(mNotificationStackScrollLayoutController).onExpansionStopped();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void onShadeFlingEnd_mExpandImmediateShouldBeReset() {
        mNotificationPanelViewController.onFlingEnd(false);

        verify(mQsController).setExpandImmediate(false);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void inUnlockedSplitShade_transitioningMaxTransitionDistance_makesShadeFullyExpanded() {
        mStatusBarStateController.setState(SHADE);
        enableSplitShade(true);
        int transitionDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);
        assertThat(mNotificationPanelViewController.isFullyExpanded()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void shadeFullyExpanded_inShadeState() {
        mStatusBarStateController.setState(SHADE);

        mNotificationPanelViewController.setExpandedHeight(0);
        assertThat(mNotificationPanelViewController.isShadeFullyExpanded()).isFalse();

        int transitionDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);
        assertThat(mNotificationPanelViewController.isShadeFullyExpanded()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void shadeFullyExpanded_onKeyguard() {
        mStatusBarStateController.setState(KEYGUARD);

        int transitionDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);
        assertThat(mNotificationPanelViewController.isShadeFullyExpanded()).isFalse();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void shadeFullyExpanded_onShadeLocked() {
        mStatusBarStateController.setState(SHADE_LOCKED);
        assertThat(mNotificationPanelViewController.isShadeFullyExpanded()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void shadeExpanded_whenHasHeight() {
        int transitionDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);
        assertThat(mNotificationPanelViewController.isExpanded()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void shadeExpanded_whenInstantExpanding() {
        mNotificationPanelViewController.expand(true);
        assertThat(mNotificationPanelViewController.isExpanded()).isTrue();
    }

    @Test
    @DisableSceneContainer
    public void shadeExpanded_whenHunIsPresent() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        assertThat(mNotificationPanelViewController.isExpanded()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void shadeExpanded_whenUnlockedOffscreenAnimationRunning() {
        when(mUnlockedScreenOffAnimationController.isAnimationPlaying()).thenReturn(true);
        assertThat(mNotificationPanelViewController.isExpanded()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void shadeExpanded_whenInputFocusTransferStarted() {
        when(mCommandQueue.panelsEnabled()).thenReturn(true);

        mNotificationPanelViewController.startInputFocusTransfer();

        assertThat(mNotificationPanelViewController.isExpanded()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void shadeNotExpanded_whenInputFocusTransferStartedButPanelsDisabled() {
        when(mCommandQueue.panelsEnabled()).thenReturn(false);

        mNotificationPanelViewController.startInputFocusTransfer();

        assertThat(mNotificationPanelViewController.isExpanded()).isFalse();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void cancelInputFocusTransfer_shadeCollapsed() {
        when(mCommandQueue.panelsEnabled()).thenReturn(true);
        mNotificationPanelViewController.startInputFocusTransfer();

        mNotificationPanelViewController.cancelInputFocusTransfer();

        assertThat(mNotificationPanelViewController.isExpanded()).isFalse();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void finishInputFocusTransfer_shadeFlingingOpen() {
        when(mCommandQueue.panelsEnabled()).thenReturn(true);
        mNotificationPanelViewController.startInputFocusTransfer();

        mNotificationPanelViewController.finishInputFocusTransfer(/* velocity= */ 0f);

        assertThat(mNotificationPanelViewController.isFlinging()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getFalsingThreshold_deviceNotInteractive_isQsThreshold() {
        PowerInteractor.Companion.setAsleepForTest(
                mPowerInteractor, PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        when(mQsController.getFalsingThreshold()).thenReturn(14);

        assertThat(mNotificationPanelViewController.getFalsingThreshold()).isEqualTo(14);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getFalsingThreshold_lastWakeNotDueToTouch_isQsThreshold() {
        PowerInteractor.Companion.setAwakeForTest(
                mPowerInteractor, PowerManager.WAKE_REASON_POWER_BUTTON);
        when(mQsController.getFalsingThreshold()).thenReturn(14);

        assertThat(mNotificationPanelViewController.getFalsingThreshold()).isEqualTo(14);
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    public void getFalsingThreshold_lastWakeDueToTouch_greaterThanQsThreshold() {
        PowerInteractor.Companion.setAwakeForTest(mPowerInteractor, PowerManager.WAKE_REASON_TAP);
        when(mQsController.getFalsingThreshold()).thenReturn(14);

        assertThat(mNotificationPanelViewController.getFalsingThreshold()).isGreaterThan(14);
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
