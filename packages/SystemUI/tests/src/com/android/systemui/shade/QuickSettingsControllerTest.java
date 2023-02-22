/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static com.android.systemui.statusbar.StatusBarState.SHADE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.MediaHierarchyManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.shade.transition.ShadeTransitionController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.QsFrameTranslateController;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import dagger.Lazy;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class QuickSettingsControllerTest extends SysuiTestCase {

    private static final int SPLIT_SHADE_FULL_TRANSITION_DISTANCE = 400;

    private QuickSettingsController mQsController;

    @Mock private Resources mResources;
    @Mock private KeyguardBottomAreaView mQsFrame;
    @Mock private KeyguardStatusBarView mKeyguardStatusBar;
    @Mock private QS mQs;
    @Mock private QSFragment mQSFragment;

    @Mock private Lazy<NotificationPanelViewController> mPanelViewControllerLazy;
    @Mock private NotificationPanelViewController mNotificationPanelViewController;
    @Mock private NotificationPanelView mPanelView;
    @Mock private ViewGroup mQsHeader;
    @Mock private ViewParent mPanelViewParent;
    @Mock private QsFrameTranslateController mQsFrameTranslateController;
    @Mock private ShadeTransitionController mShadeTransitionController;
    @Mock private PulseExpansionHandler mPulseExpansionHandler;
    @Mock private NotificationRemoteInputManager mNotificationRemoteInputManager;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    @Mock private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock private NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock private ShadeHeaderController mShadeHeaderController;
    @Mock private StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private ScrimController mScrimController;
    @Mock private MediaDataManager mMediaDataManager;
    @Mock private MediaHierarchyManager mMediaHierarchyManager;
    @Mock private AmbientState mAmbientState;
    @Mock private RecordingController mRecordingController;
    @Mock private FalsingManager mFalsingManager;
    @Mock private FalsingCollector mFalsingCollector;
    @Mock private AccessibilityManager mAccessibilityManager;
    @Mock private LockscreenGestureLogger mLockscreenGestureLogger;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private InteractionJankMonitor mInteractionJankMonitor;
    @Mock private ShadeLogger mShadeLogger;

    @Mock private DumpManager mDumpManager;
    @Mock private DozeParameters mDozeParameters;
    @Mock private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private UiEventLogger mUiEventLogger;

    private SysuiStatusBarStateController mStatusBarStateController;

    private Handler mMainHandler;
    private LockscreenShadeTransitionController.Callback mLockscreenShadeTransitionCallback;

    private final ShadeExpansionStateManager mShadeExpansionStateManager =
            new ShadeExpansionStateManager();

    private FragmentHostManager.FragmentListener mFragmentListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mPanelViewControllerLazy.get()).thenReturn(mNotificationPanelViewController);
        mStatusBarStateController = new StatusBarStateControllerImpl(mUiEventLogger, mDumpManager,
                mInteractionJankMonitor, mShadeExpansionStateManager);

        KeyguardStatusView keyguardStatusView = new KeyguardStatusView(mContext);
        keyguardStatusView.setId(R.id.keyguard_status_view);

        when(mPanelView.getResources()).thenReturn(mResources);
        when(mPanelView.getContext()).thenReturn(getContext());
        when(mPanelView.findViewById(R.id.keyguard_header)).thenReturn(mKeyguardStatusBar);
        when(mNotificationStackScrollLayoutController.getHeight()).thenReturn(1000);
        when(mPanelView.findViewById(R.id.qs_frame)).thenReturn(mQsFrame);
        when(mPanelView.findViewById(R.id.keyguard_status_view))
                .thenReturn(mock(KeyguardStatusView.class));
        when(mQs.getView()).thenReturn(mPanelView);
        when(mQSFragment.getView()).thenReturn(mPanelView);

        when(mNotificationRemoteInputManager.isRemoteInputActive())
                .thenReturn(false);
        when(mInteractionJankMonitor.begin(any(), anyInt()))
                .thenReturn(true);
        when(mInteractionJankMonitor.end(anyInt()))
                .thenReturn(true);

        when(mPanelView.getParent()).thenReturn(mPanelViewParent);
        when(mQs.getHeader()).thenReturn(mQsHeader);

        doAnswer(invocation -> {
            mLockscreenShadeTransitionCallback = invocation.getArgument(0);
            return null;
        }).when(mLockscreenShadeTransitionController).addCallback(any());


        mMainHandler = new Handler(Looper.getMainLooper());

        mQsController = new QuickSettingsController(
                mPanelViewControllerLazy,
                mPanelView,
                mQsFrameTranslateController,
                mShadeTransitionController,
                mPulseExpansionHandler,
                mNotificationRemoteInputManager,
                mShadeExpansionStateManager,
                mStatusBarKeyguardViewManager,
                mNotificationStackScrollLayoutController,
                mLockscreenShadeTransitionController,
                mNotificationShadeDepthController,
                mShadeHeaderController,
                mStatusBarTouchableRegionManager,
                mKeyguardStateController,
                mKeyguardBypassController,
                mKeyguardUpdateMonitor,
                mScrimController,
                mMediaDataManager,
                mMediaHierarchyManager,
                mAmbientState,
                mRecordingController,
                mFalsingManager,
                mFalsingCollector,
                mAccessibilityManager,
                mLockscreenGestureLogger,
                mMetricsLogger,
                mFeatureFlags,
                mInteractionJankMonitor,
                mShadeLogger
        );

        mFragmentListener = mQsController.getQsFragmentListener();
    }

    @After
    public void tearDown() {
        mNotificationPanelViewController.cancelHeightAnimator();
        mMainHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void testOnTouchEvent_isConflictingExpansionGestureSet() {
        assertThat(mQsController.isConflictingExpansionGesture()).isFalse();
        mShadeExpansionStateManager.onPanelExpansionChanged(1f, true, false, 0f);
        mQsController.handleTouch(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */), false, false);
        assertThat(mQsController.isConflictingExpansionGesture()).isTrue();
    }

    @Test
    public void testCloseQsSideEffects() {
        enableSplitShade(true);
        mQsController.setExpandImmediate(true);
        mQsController.setExpanded(true);
        mQsController.closeQs();

        assertThat(mQsController.getExpanded()).isEqualTo(false);
        assertThat(mQsController.isExpandImmediate()).isEqualTo(false);
    }

    @Test
    public void testLargeScreenHeaderMadeActiveForLargeScreen() {
        mStatusBarStateController.setState(SHADE);
        when(mResources.getBoolean(R.bool.config_use_large_screen_shade_header)).thenReturn(true);
        mQsController.updateResources();
        verify(mShadeHeaderController).setLargeScreenActive(true);

        when(mResources.getBoolean(R.bool.config_use_large_screen_shade_header)).thenReturn(false);
        mQsController.updateResources();
        verify(mShadeHeaderController).setLargeScreenActive(false);
    }

    @Test
    public void testPanelStaysOpenWhenClosingQs() {
        mShadeExpansionStateManager.onPanelExpansionChanged(/* fraction= */ 1,
                /* expanded= */ true, /* tracking= */ false, /* dragDownPxAmount= */ 0);
        mNotificationPanelViewController.setExpandedFraction(1f);

        float shadeExpandedHeight = mQsController.getShadeExpandedHeight();
        mQsController.animateCloseQs(false);

        assertThat(mQsController.getShadeExpandedHeight()).isEqualTo(shadeExpandedHeight);
    }

    @Test
    public void interceptTouchEvent_withinQs_shadeExpanded_startsQsTracking() {
        mQsController.setQs(mQs);
        when(mQsFrame.getX()).thenReturn(0f);
        when(mQsFrame.getWidth()).thenReturn(1000);
        when(mQsHeader.getTop()).thenReturn(0);
        when(mQsHeader.getBottom()).thenReturn(1000);

        mQsController.setShadeExpandedHeight(1f);
        mQsController.onIntercept(
                createMotionEvent(0, 0, MotionEvent.ACTION_DOWN));
        mQsController.onIntercept(
                createMotionEvent(0, 500, MotionEvent.ACTION_MOVE));

        assertThat(mQsController.isTracking()).isTrue();
    }

    @Test
    public void interceptTouchEvent_withinQs_shadeExpanded_inSplitShade_doesNotStartQsTracking() {
        enableSplitShade(true);
        mQsController.setQs(mQs);
        when(mQsFrame.getX()).thenReturn(0f);
        when(mQsFrame.getWidth()).thenReturn(1000);
        when(mQsHeader.getTop()).thenReturn(0);
        when(mQsHeader.getBottom()).thenReturn(1000);

        mQsController.setShadeExpandedHeight(1f);
        mQsController.onIntercept(
                createMotionEvent(0, 0, MotionEvent.ACTION_DOWN));
        mQsController.onIntercept(
                createMotionEvent(0, 500, MotionEvent.ACTION_MOVE));

        assertThat(mQsController.isTracking()).isFalse();
    }

    @Test
    public void onQsFragmentAttached_fullWidth_setsFullWidthTrueOnQS() {
        setIsFullWidth(true);
        mFragmentListener.onFragmentViewCreated(QS.TAG, mQSFragment);

        verify(mQSFragment).setIsNotificationPanelFullWidth(true);
    }

    @Test
    public void onQsFragmentAttached_notFullWidth_setsFullWidthFalseOnQS() {
        setIsFullWidth(false);
        mFragmentListener.onFragmentViewCreated(QS.TAG, mQSFragment);

        verify(mQSFragment).setIsNotificationPanelFullWidth(false);
    }

    @Test
    public void getMaxPanelTransitionDistance_inSplitShade_withHeadsUp_returnsBiggerValue() {
        enableSplitShade(true);
        mNotificationPanelViewController.expandWithQs();
        when(mHeadsUpManager.isTrackingHeadsUp()).thenReturn(true);

        mNotificationPanelViewController.setHeadsUpDraggingStartingHeight(
                SPLIT_SHADE_FULL_TRANSITION_DISTANCE);

        assertThat(mQsController.calculatePanelHeightExpanded(0))
                .isGreaterThan(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    public void setQsExpansion_lockscreenShadeTransitionInProgress_usesLockscreenSquishiness() {
        float squishinessFraction = 0.456f;
        mQsController.setQs(mQs);
        when(mLockscreenShadeTransitionController.getQsSquishTransitionFraction())
                .thenReturn(squishinessFraction);
        when(mNotificationStackScrollLayoutController.getNotificationSquishinessFraction())
                .thenReturn(0.987f);
        // Call setTransitionToFullShadeAmount to get into the full shade transition in progress
        // state.
        mLockscreenShadeTransitionCallback.setTransitionToFullShadeAmount(234, false, 0);

        mQsController.setExpansionHeight(123);

        // First for setTransitionToFullShadeAmount and then setQsExpansion
        verify(mQs, times(2)).setQsExpansion(anyFloat(), anyFloat(), anyFloat(),
                eq(squishinessFraction)
        );
    }

    @Test
    public void setQsExpansion_lockscreenShadeTransitionNotInProgress_usesStandardSquishiness() {
        float lsSquishinessFraction = 0.456f;
        float nsslSquishinessFraction = 0.987f;
        mQsController.setQs(mQs);
        when(mLockscreenShadeTransitionController.getQsSquishTransitionFraction())
                .thenReturn(lsSquishinessFraction);
        when(mNotificationStackScrollLayoutController.getNotificationSquishinessFraction())
                .thenReturn(nsslSquishinessFraction);

        mQsController.setExpansionHeight(123);

        verify(mQs).setQsExpansion(anyFloat(), anyFloat(), anyFloat(), eq(nsslSquishinessFraction)
        );
    }

    @Test
    public void shadeExpanded_onKeyguard() {
        mStatusBarStateController.setState(KEYGUARD);
        // set maxQsExpansion in NPVC
        int maxQsExpansion = 123;
        mQsController.setQs(mQs);
        when(mQs.getDesiredHeight()).thenReturn(maxQsExpansion);

        int oldMaxHeight = mQsController.updateHeightsOnShadeLayoutChange();
        mQsController.handleShadeLayoutChanged(oldMaxHeight);

        mQsController.setExpansionHeight(maxQsExpansion);
        assertThat(mQsController.computeExpansionFraction()).isEqualTo(1f);
    }

    private static MotionEvent createMotionEvent(int x, int y, int action) {
        return MotionEvent.obtain(
                /* downTime= */ 0, /* eventTime= */ 0, action, x, y, /* metaState= */ 0);
    }

    private void enableSplitShade(boolean enabled) {
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(enabled);
        mQsController.updateResources();
    }

    private void setIsFullWidth(boolean fullWidth) {
        mQsController.setNotificationPanelFullWidth(fullWidth);
        triggerLayoutChange();
    }

    private void triggerLayoutChange() {
        int oldMaxHeight = mQsController.updateHeightsOnShadeLayoutChange();
        mQsController.handleShadeLayoutChanged(oldMaxHeight);
    }
}
