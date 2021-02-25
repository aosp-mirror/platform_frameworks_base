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

package com.android.systemui.statusbar.phone;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.IdRes;
import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricSourceType;
import android.os.PowerManager;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardClockSwitch;
import com.android.keyguard.KeyguardClockSwitchController;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardStatusViewController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.dagger.KeyguardQsUserSwitchComponent;
import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.keyguard.dagger.KeyguardUserSwitcherComponent;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.qs.QSDetailDisplayer;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wm.shell.animation.FlingAnimationUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationPanelViewTest extends SysuiTestCase {

    @Mock
    private StatusBar mStatusBar;
    @Mock
    private NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock
    private KeyguardBottomAreaView mKeyguardBottomArea;
    @Mock
    private KeyguardBottomAreaView mQsFrame;
    @Mock
    private ViewGroup mBigClockContainer;
    @Mock
    private NotificationIconAreaController mNotificationAreaController;
    @Mock
    private HeadsUpManagerPhone mHeadsUpManager;
    @Mock
    private NotificationShelfController mNotificationShelfController;
    @Mock
    private NotificationGroupManagerLegacy mGroupManager;
    @Mock
    private KeyguardStatusBarView mKeyguardStatusBar;
    @Mock
    private HeadsUpTouchHelper.Callback mHeadsUpCallback;
    @Mock
    private PanelBar mPanelBar;
    @Mock
    private KeyguardUpdateMonitor mUpdateMonitor;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private NotificationPanelView mView;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private DynamicPrivacyController mDynamicPrivacyController;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private DozeLog mDozeLog;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private VibratorHelper mVibratorHelper;
    @Mock
    private LatencyTracker mLatencyTracker;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private Resources mResources;
    @Mock
    private Configuration mConfiguration;
    private DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    @Mock
    private KeyguardClockSwitch mKeyguardClockSwitch;
    private PanelViewController.TouchHandler mTouchHandler;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private MediaHierarchyManager mMediaHiearchyManager;
    @Mock
    private ConversationNotificationManager mConversationNotificationManager;
    @Mock
    private BiometricUnlockController mBiometricUnlockController;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private KeyguardStatusViewComponent.Factory mKeyguardStatusViewComponentFactory;
    @Mock
    private KeyguardQsUserSwitchComponent.Factory mKeyguardQsUserSwitchComponentFactory;
    @Mock
    private KeyguardUserSwitcherComponent.Factory mKeyguardUserSwitcherComponentFactory;
    @Mock
    private QSDetailDisplayer mQSDetailDisplayer;
    @Mock
    private KeyguardStatusViewComponent mKeyguardStatusViewComponent;
    @Mock
    private KeyguardClockSwitchController mKeyguardClockSwitchController;
    @Mock
    private KeyguardStatusViewController mKeyguardStatusViewController;
    @Mock
    private NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    @Mock
    private AuthController mAuthController;
    @Mock
    private ScrimController mScrimController;
    @Mock
    private MediaDataManager mMediaDataManager;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private AmbientState mAmbientState;
    @Mock
    private UserManager mUserManager;
    @Mock
    private UiEventLogger mUiEventLogger;

    private SysuiStatusBarStateController mStatusBarStateController;
    private NotificationPanelViewController mNotificationPanelViewController;
    private View.AccessibilityDelegate mAccessibiltyDelegate;
    private NotificationsQuickSettingsContainer mNotificationContainerParent;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mStatusBarStateController = new StatusBarStateControllerImpl(mUiEventLogger);

        when(mAuthController.isUdfpsEnrolled(anyInt())).thenReturn(false);
        when(mHeadsUpCallback.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mResources);
        when(mResources.getConfiguration()).thenReturn(mConfiguration);
        mConfiguration.orientation = ORIENTATION_PORTRAIT;
        when(mResources.getDisplayMetrics()).thenReturn(mDisplayMetrics);
        mDisplayMetrics.density = 100;
        when(mResources.getBoolean(R.bool.config_enableNotificationShadeDrag)).thenReturn(true);
        when(mResources.getDimensionPixelSize(R.dimen.qs_panel_width)).thenReturn(400);
        when(mResources.getDimensionPixelSize(R.dimen.notification_panel_width)).thenReturn(400);
        when(mView.getContext()).thenReturn(getContext());
        when(mView.findViewById(R.id.keyguard_header)).thenReturn(mKeyguardStatusBar);
        when(mView.findViewById(R.id.keyguard_clock_container)).thenReturn(mKeyguardClockSwitch);
        when(mView.findViewById(R.id.notification_stack_scroller))
                .thenReturn(mNotificationStackScrollLayout);
        when(mNotificationStackScrollLayout.getController())
                .thenReturn(mNotificationStackScrollLayoutController);
        when(mNotificationStackScrollLayoutController.getHeight()).thenReturn(1000);
        when(mNotificationStackScrollLayoutController.getHeadsUpCallback())
                .thenReturn(mHeadsUpCallback);
        when(mView.findViewById(R.id.keyguard_bottom_area)).thenReturn(mKeyguardBottomArea);
        when(mKeyguardBottomArea.getLeftView()).thenReturn(mock(KeyguardAffordanceView.class));
        when(mKeyguardBottomArea.getRightView()).thenReturn(mock(KeyguardAffordanceView.class));
        when(mKeyguardBottomArea.animate()).thenReturn(mock(ViewPropertyAnimator.class));
        when(mView.findViewById(R.id.big_clock_container)).thenReturn(mBigClockContainer);
        when(mView.findViewById(R.id.qs_frame)).thenReturn(mQsFrame);
        when(mView.findViewById(R.id.keyguard_status_view))
                .thenReturn(mock(KeyguardStatusView.class));
        mNotificationContainerParent = new NotificationsQuickSettingsContainer(getContext(), null);
        mNotificationContainerParent.addView(newViewWithId(R.id.qs_frame));
        mNotificationContainerParent.addView(newViewWithId(R.id.notification_stack_scroller));
        when(mView.findViewById(R.id.notification_container_parent))
                .thenReturn(mNotificationContainerParent);
        FlingAnimationUtils.Builder flingAnimationUtilsBuilder = new FlingAnimationUtils.Builder(
                mDisplayMetrics);

        doAnswer((Answer<Void>) invocation -> {
            mTouchHandler = invocation.getArgument(0);
            return null;
        }).when(mView).setOnTouchListener(any(PanelViewController.TouchHandler.class));

        NotificationWakeUpCoordinator coordinator =
                new NotificationWakeUpCoordinator(
                        mock(HeadsUpManagerPhone.class),
                        new StatusBarStateControllerImpl(new UiEventLoggerFake()),
                        mKeyguardBypassController,
                        mDozeParameters);
        PulseExpansionHandler expansionHandler = new PulseExpansionHandler(
                mContext,
                coordinator,
                mKeyguardBypassController, mHeadsUpManager,
                mock(NotificationRoundnessManager.class),
                mStatusBarStateController,
                new FalsingManagerFake(), new FalsingCollectorFake());
        when(mKeyguardStatusViewComponentFactory.build(any()))
                .thenReturn(mKeyguardStatusViewComponent);
        when(mKeyguardStatusViewComponent.getKeyguardClockSwitchController())
                .thenReturn(mKeyguardClockSwitchController);
        when(mKeyguardStatusViewComponent.getKeyguardStatusViewController())
                .thenReturn(mKeyguardStatusViewController);

        mNotificationPanelViewController = new NotificationPanelViewController(mView,
                mResources,
                mLayoutInflater,
                coordinator, expansionHandler, mDynamicPrivacyController, mKeyguardBypassController,
                new FalsingManagerFake(), new FalsingCollectorFake(), mShadeController,
                mNotificationLockscreenUserManager, mNotificationEntryManager,
                mKeyguardStateController, mStatusBarStateController, mDozeLog,
                mDozeParameters, mCommandQueue, mVibratorHelper,
                mLatencyTracker, mPowerManager, mAccessibilityManager, 0, mUpdateMonitor,
                mMetricsLogger, mActivityManager, mConfigurationController,
                () -> flingAnimationUtilsBuilder, mStatusBarTouchableRegionManager,
                mConversationNotificationManager, mMediaHiearchyManager,
                mBiometricUnlockController, mStatusBarKeyguardViewManager,
                mNotificationStackScrollLayoutController,
                mKeyguardStatusViewComponentFactory,
                mKeyguardQsUserSwitchComponentFactory,
                mKeyguardUserSwitcherComponentFactory,
                mQSDetailDisplayer,
                mGroupManager,
                mNotificationAreaController,
                mAuthController,
                mScrimController,
                mUserManager,
                mMediaDataManager,
                mAmbientState,
                mFeatureFlags);
        mNotificationPanelViewController.initDependencies(
                mStatusBar,
                mNotificationShelfController);
        mNotificationPanelViewController.setHeadsUpManager(mHeadsUpManager);
        mNotificationPanelViewController.setBar(mPanelBar);

        ArgumentCaptor<View.AccessibilityDelegate> accessibilityDelegateArgumentCaptor =
                ArgumentCaptor.forClass(View.AccessibilityDelegate.class);
        verify(mView).setAccessibilityDelegate(accessibilityDelegateArgumentCaptor.capture());
        mAccessibiltyDelegate = accessibilityDelegateArgumentCaptor.getValue();
        mNotificationPanelViewController.mStatusBarStateController
                .addCallback(mNotificationPanelViewController.mStatusBarStateListener);
        mNotificationPanelViewController
                .setHeadsUpAppearanceController(mock(HeadsUpAppearanceController.class));
    }

    @Test
    public void testSetDozing_notifiesNsslAndStateController() {
        mNotificationPanelViewController.setDozing(true /* dozing */, false /* animate */,
                null /* touch */);
        verify(mNotificationStackScrollLayoutController)
                .setDozing(eq(true), eq(false), eq(null));
        assertThat(mStatusBarStateController.getDozeAmount()).isEqualTo(1f);
    }

    @Test
    public void testSetExpandedHeight() {
        mNotificationPanelViewController.setExpandedHeight(200);
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);
    }

    @Test
    public void testAffordanceLaunchingListener() {
        Consumer<Boolean> listener = spy((showing) -> { });
        mNotificationPanelViewController.setExpandedFraction(1f);
        mNotificationPanelViewController.setLaunchAffordanceListener(listener);
        mNotificationPanelViewController.launchCamera(false /* animate */,
                StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP);
        verify(listener).accept(eq(true));

        mNotificationPanelViewController.onAffordanceLaunchEnded();
        verify(listener).accept(eq(false));
    }

    @Test
    public void testOnTouchEvent_expansionCanBeBlocked() {
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 200f /* y */,
                0 /* metaState */));
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);
        assertThat(mNotificationPanelViewController.isTrackingBlocked()).isFalse();

        mNotificationPanelViewController.blockExpansionForCurrentTouch();
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 300f /* y */,
                0 /* metaState */));
        // Expansion should not have changed because it was blocked
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);
        assertThat(mNotificationPanelViewController.isTrackingBlocked()).isTrue();

        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_UP, 0f /* x */, 300f /* y */,
                0 /* metaState */));
        assertThat(mNotificationPanelViewController.isTrackingBlocked()).isFalse();
    }

    @Test
    public void testKeyguardStatusBarVisibility_hiddenForBypass() {
        when(mUpdateMonitor.shouldListenForFace()).thenReturn(true);
        mNotificationPanelViewController.mKeyguardUpdateCallback.onBiometricRunningStateChanged(
                true, BiometricSourceType.FACE);
        verify(mKeyguardStatusBar, never()).setVisibility(View.VISIBLE);

        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mNotificationPanelViewController.mKeyguardUpdateCallback.onFinishedGoingToSleep(0);
        mNotificationPanelViewController.mKeyguardUpdateCallback.onBiometricRunningStateChanged(
                true, BiometricSourceType.FACE);
        verify(mKeyguardStatusBar, never()).setVisibility(View.VISIBLE);
    }

    @Test
    public void testA11y_initializeNode() {
        AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();
        mAccessibiltyDelegate.onInitializeAccessibilityNodeInfo(mView, nodeInfo);

        List<AccessibilityNodeInfo.AccessibilityAction> actionList = nodeInfo.getActionList();
        assertThat(actionList).containsAtLeastElementsIn(
                new AccessibilityNodeInfo.AccessibilityAction[] {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP}
        );
    }

    @Test
    public void testA11y_scrollForward() {
        mAccessibiltyDelegate.performAccessibilityAction(
                mView,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId(),
                null);

        verify(mStatusBarKeyguardViewManager).showBouncer(true);
    }

    @Test
    public void testA11y_scrollUp() {
        mAccessibiltyDelegate.performAccessibilityAction(
                mView,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(),
                null);

        verify(mStatusBarKeyguardViewManager).showBouncer(true);
    }

    @Test
    public void testAllChildrenOfNotificationContainer_haveIds() {
        enableSplitShade();
        mNotificationContainerParent.removeAllViews();
        mNotificationContainerParent.addView(newViewWithId(1));
        mNotificationContainerParent.addView(newViewWithId(View.NO_ID));

        mNotificationPanelViewController.updateResources();

        assertThat(mNotificationContainerParent.getChildAt(0).getId()).isEqualTo(1);
        assertThat(mNotificationContainerParent.getChildAt(1).getId()).isNotEqualTo(View.NO_ID);
    }

    @Test
    public void testSinglePaneShadeLayout_isAlignedToParent() {
        when(mFeatureFlags.isTwoColumnNotificationShadeEnabled()).thenReturn(false);

        mNotificationPanelViewController.updateResources();

        assertThat(getConstraintSetLayout(R.id.qs_frame).endToEnd)
                .isEqualTo(ConstraintSet.PARENT_ID);
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).startToStart)
                .isEqualTo(ConstraintSet.PARENT_ID);
    }

    @Test
    public void testSplitShadeLayout_isAlignedToGuideline() {
        enableSplitShade();

        mNotificationPanelViewController.updateResources();

        assertThat(getConstraintSetLayout(R.id.qs_frame).endToEnd)
                .isEqualTo(R.id.qs_edge_guideline);
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).startToStart)
                .isEqualTo(R.id.qs_edge_guideline);
    }

    @Test
    public void testSinglePaneShadeLayout_childrenHaveConstantWidth() {
        when(mFeatureFlags.isTwoColumnNotificationShadeEnabled()).thenReturn(false);

        mNotificationPanelViewController.updateResources();

        assertThat(getConstraintSetLayout(R.id.qs_frame).mWidth)
                .isEqualTo(mResources.getDimensionPixelSize(R.dimen.qs_panel_width));
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).mWidth)
                .isEqualTo(mResources.getDimensionPixelSize(R.dimen.notification_panel_width));
    }

    @Test
    public void testSplitShadeLayout_childrenHaveZeroWidth() {
        enableSplitShade();

        mNotificationPanelViewController.updateResources();

        assertThat(getConstraintSetLayout(R.id.qs_frame).mWidth).isEqualTo(0);
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).mWidth).isEqualTo(0);
    }

    @Test
    public void testOnDragDownEvent_horizontalTranslationIsZeroForSplitShade() {
        when(mNotificationStackScrollLayoutController.getWidth()).thenReturn(350f);
        when(mView.getWidth()).thenReturn(800);
        enableSplitShade();

        onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN,
                200f /* x position */, 0f, 0));

        verify(mQsFrame).setTranslationX(0);
    }

    @Test
    public void testCanCollapsePanelOnTouch_trueForKeyGuard() {
        mStatusBarStateController.setState(KEYGUARD);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    public void testCanCollapsePanelOnTouch_trueWhenScrolledToBottom() {
        mStatusBarStateController.setState(SHADE);
        when(mNotificationStackScrollLayoutController.isScrolledToBottom()).thenReturn(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    public void testCanCollapsePanelOnTouch_trueWhenInSettings() {
        mStatusBarStateController.setState(SHADE);
        mNotificationPanelViewController.setQsExpanded(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    public void testCanCollapsePanelOnTouch_falseInDualPaneShade() {
        mStatusBarStateController.setState(SHADE);
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(true);
        when(mFeatureFlags.isTwoColumnNotificationShadeEnabled()).thenReturn(true);
        mNotificationPanelViewController.setQsExpanded(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isFalse();
    }

    private View newViewWithId(int id) {
        View view = new View(mContext);
        view.setId(id);
        ConstraintLayout.LayoutParams layoutParams = new ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // required as cloning ConstraintSet fails if view doesn't have layout params
        view.setLayoutParams(layoutParams);
        return view;
    }

    private ConstraintSet.Layout getConstraintSetLayout(@IdRes int id) {
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(mNotificationContainerParent);
        return constraintSet.getConstraint(id).layout;
    }

    private void enableSplitShade() {
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(true);
        when(mFeatureFlags.isTwoColumnNotificationShadeEnabled()).thenReturn(true);
    }

    private void onTouchEvent(MotionEvent ev) {
        mTouchHandler.onTouch(mView, ev);
    }
}
