/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static com.android.keyguard.KeyguardSecurityContainer.MODE_DEFAULT;
import static com.android.keyguard.KeyguardSecurityContainer.MODE_ONE_HANDED;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricOverlayConstants;
import android.media.AudioManager;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.SideFpsController;
import com.android.systemui.biometrics.SideFpsUiRequestSource;
import com.android.systemui.classifier.FalsingA11yDelegate;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.settings.GlobalSettings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class KeyguardSecurityContainerControllerTest extends SysuiTestCase {
    private static final int TARGET_USER_ID = 100;
    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();
    @Mock
    private KeyguardSecurityContainer mView;
    @Mock
    private AdminSecondaryLockScreenController.Factory mAdminSecondaryLockScreenControllerFactory;
    @Mock
    private AdminSecondaryLockScreenController mAdminSecondaryLockScreenController;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardSecurityModel mKeyguardSecurityModel;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private KeyguardInputViewController mInputViewController;
    @Mock
    private WindowInsetsController mWindowInsetsController;
    @Mock
    private KeyguardSecurityViewFlipper mSecurityViewFlipper;
    @Mock
    private KeyguardSecurityViewFlipperController mKeyguardSecurityViewFlipperController;
    @Mock
    private KeyguardMessageAreaController.Factory mKeyguardMessageAreaControllerFactory;
    @Mock
    private KeyguardMessageAreaController mKeyguardMessageAreaController;
    @Mock
    private BouncerKeyguardMessageArea mKeyguardMessageArea;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private EmergencyButtonController mEmergencyButtonController;
    @Mock
    private FalsingCollector mFalsingCollector;
    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private GlobalSettings mGlobalSettings;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private UserSwitcherController mUserSwitcherController;
    @Mock
    private SessionTracker mSessionTracker;
    @Mock
    private KeyguardViewController mKeyguardViewController;
    @Mock
    private SideFpsController mSideFpsController;
    @Mock
    private KeyguardPasswordViewController mKeyguardPasswordViewControllerMock;
    @Mock
    private FalsingA11yDelegate mFalsingA11yDelegate;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private ViewMediatorCallback mViewMediatorCallback;
    @Mock
    private AudioManager mAudioManager;

    @Captor
    private ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardUpdateMonitorCallback;
    @Captor
    private ArgumentCaptor<KeyguardSecurityContainer.SwipeListener> mSwipeListenerArgumentCaptor;

    @Captor
    private ArgumentCaptor<KeyguardSecurityViewFlipperController.OnViewInflatedCallback>
            mOnViewInflatedCallbackArgumentCaptor;

    private KeyguardSecurityContainerController mKeyguardSecurityContainerController;
    private KeyguardPasswordViewController mKeyguardPasswordViewController;
    private KeyguardPasswordView mKeyguardPasswordView;
    private TestableResources mTestableResources;

    @Before
    public void setup() {
        mTestableResources = mContext.getOrCreateTestableResources();
        mTestableResources.getResources().getConfiguration().orientation =
                Configuration.ORIENTATION_UNDEFINED;

        when(mView.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mTestableResources.getResources());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(/* width=  */ 0, /* height= */
                0);
        lp.gravity = 0;
        when(mView.getLayoutParams()).thenReturn(lp);
        when(mAdminSecondaryLockScreenControllerFactory.create(any(KeyguardSecurityCallback.class)))
                .thenReturn(mAdminSecondaryLockScreenController);
        when(mSecurityViewFlipper.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        mKeyguardPasswordView = spy((KeyguardPasswordView) LayoutInflater.from(mContext).inflate(
                R.layout.keyguard_password_view, null));
        when(mKeyguardPasswordView.getRootView()).thenReturn(mSecurityViewFlipper);
        when(mKeyguardPasswordView.requireViewById(R.id.bouncer_message_area))
                .thenReturn(mKeyguardMessageArea);
        when(mKeyguardMessageAreaControllerFactory.create(any(KeyguardMessageArea.class)))
                .thenReturn(mKeyguardMessageAreaController);
        when(mKeyguardPasswordView.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(SecurityMode.PIN);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        mKeyguardPasswordViewController = new KeyguardPasswordViewController(
                (KeyguardPasswordView) mKeyguardPasswordView, mKeyguardUpdateMonitor,
                SecurityMode.Password, mLockPatternUtils, null,
                mKeyguardMessageAreaControllerFactory, null, null, mEmergencyButtonController,
                null, mock(Resources.class), null, mKeyguardViewController);

        mKeyguardSecurityContainerController = new KeyguardSecurityContainerController(
                mView, mAdminSecondaryLockScreenControllerFactory, mLockPatternUtils,
                mKeyguardUpdateMonitor, mKeyguardSecurityModel, mMetricsLogger, mUiEventLogger,
                mKeyguardStateController, mKeyguardSecurityViewFlipperController,
                mConfigurationController, mFalsingCollector, mFalsingManager,
                mUserSwitcherController, mFeatureFlags, mGlobalSettings,
                mSessionTracker, Optional.of(mSideFpsController), mFalsingA11yDelegate,
                mTelephonyManager, mViewMediatorCallback, mAudioManager,
                mock(KeyguardFaceAuthInteractor.class));
    }

    @Test
    public void onInitConfiguresViewMode() {
        mKeyguardSecurityContainerController.onInit();
        verify(mView).initMode(eq(MODE_DEFAULT), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));
    }

    @Test
    public void showSecurityScreen_canInflateAllModes() {
        SecurityMode[] modes = SecurityMode.values();
        for (SecurityMode mode : modes) {
            when(mInputViewController.getSecurityMode()).thenReturn(mode);
            mKeyguardSecurityContainerController.showSecurityScreen(mode);
            if (mode == SecurityMode.Invalid) {
                verify(mKeyguardSecurityViewFlipperController, never()).getSecurityView(
                        any(SecurityMode.class), any(KeyguardSecurityCallback.class), any(
                                KeyguardSecurityViewFlipperController.OnViewInflatedCallback.class)
                );
            } else {
                verify(mKeyguardSecurityViewFlipperController).getSecurityView(
                        eq(mode), any(KeyguardSecurityCallback.class), any(
                                KeyguardSecurityViewFlipperController.OnViewInflatedCallback.class)
                );
            }
        }
    }

    @Test
    public void onResourcesUpdate_callsThroughOnRotationChange() {
        clearInvocations(mView);

        // Rotation is the same, shouldn't cause an update
        mKeyguardSecurityContainerController.updateResources();
        verify(mView, never()).initMode(eq(MODE_DEFAULT), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));

        // Update rotation. Should trigger update
        mTestableResources.getResources().getConfiguration().orientation =
                Configuration.ORIENTATION_LANDSCAPE;

        mKeyguardSecurityContainerController.updateResources();
        verify(mView).initMode(eq(MODE_DEFAULT), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));
    }

    private void touchDown() {
        mKeyguardSecurityContainerController.mGlobalTouchListener.onTouchEvent(
                MotionEvent.obtain(
                        /* downTime= */0,
                        /* eventTime= */0,
                        MotionEvent.ACTION_DOWN,
                        /* x= */0,
                        /* y= */0,
                        /* metaState= */0));
    }

    @Test
    public void onInterceptTap_inhibitsFalsingInSidedSecurityMode() {

        when(mView.isTouchOnTheOtherSideOfSecurity(any())).thenReturn(false);
        touchDown();
        verify(mFalsingCollector, never()).avoidGesture();

        when(mView.isTouchOnTheOtherSideOfSecurity(any())).thenReturn(true);
        touchDown();
        verify(mFalsingCollector).avoidGesture();
    }

    @Test
    public void showSecurityScreen_oneHandedMode_flagDisabled_noOneHandedMode() {
        mTestableResources.addOverride(R.bool.can_use_one_handed_bouncer, false);
        setupGetSecurityView(SecurityMode.Pattern);

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Pattern);
        verify(mView).initMode(eq(MODE_DEFAULT), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));
    }

    @Test
    public void showSecurityScreen_oneHandedMode_flagEnabled_oneHandedMode() {
        mTestableResources.addOverride(R.bool.can_use_one_handed_bouncer, true);
        setupGetSecurityView(SecurityMode.Pattern);
        verify(mView).initMode(eq(MODE_ONE_HANDED), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));
    }

    @Test
    public void showSecurityScreen_twoHandedMode_flagEnabled_noOneHandedMode() {
        mTestableResources.addOverride(R.bool.can_use_one_handed_bouncer, true);
        setupGetSecurityView(SecurityMode.Password);

        verify(mView).initMode(eq(MODE_DEFAULT), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));
    }

    @Test
    public void addUserSwitcherCallback() {
        ArgumentCaptor<KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback>
                captor = ArgumentCaptor.forClass(
                KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class);
        setupGetSecurityView(SecurityMode.Password);

        verify(mView).initMode(anyInt(), any(GlobalSettings.class), any(FalsingManager.class),
                any(UserSwitcherController.class),
                captor.capture(),
                eq(mFalsingA11yDelegate));
        captor.getValue().showUnlockToContinueMessage();
        getViewControllerImmediately();
        verify(mKeyguardPasswordViewControllerMock).showMessage(
                /* message= */ getContext().getString(R.string.keyguard_unlock_to_continue),
                /* colorState= */ null,
                /* animated= */ true);
    }

    @Test
    public void addUserSwitchCallback() {
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mUserSwitcherController)
                .addUserSwitchCallback(any(UserSwitcherController.UserSwitchCallback.class));
        mKeyguardSecurityContainerController.onViewDetached();
        verify(mUserSwitcherController)
                .removeUserSwitchCallback(any(UserSwitcherController.UserSwitchCallback.class));
    }

    @Test
    public void onBouncerVisibilityChanged_resetsScale() {
        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(false);
        verify(mView).resetScale();
    }

    @Test
    public void showNextSecurityScreenOrFinish_setsSecurityScreenToPinAfterSimPinUnlock() {
        // GIVEN the current security method is SimPin
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(TARGET_USER_ID)).thenReturn(false);
        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.SimPin);

        // WHEN a request is made from the SimPin screens to show the next security method
        when(mKeyguardSecurityModel.getSecurityMode(TARGET_USER_ID)).thenReturn(SecurityMode.PIN);
        mKeyguardSecurityContainerController.showNextSecurityScreenOrFinish(
                /* authenticated= */true,
                TARGET_USER_ID,
                /* bypassSecondaryLockScreen= */true,
                SecurityMode.SimPin);

        // THEN the next security method of PIN is set, and the keyguard is not marked as done

        verify(mViewMediatorCallback, never()).keyguardDonePending(anyBoolean(), anyInt());
        verify(mViewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt());
        assertThat(mKeyguardSecurityContainerController.getCurrentSecurityMode())
                .isEqualTo(SecurityMode.PIN);
    }

    @Test
    public void showNextSecurityScreenOrFinish_DeviceNotSecure() {
        // GIVEN the current security method is SimPin
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(TARGET_USER_ID)).thenReturn(false);
        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.SimPin);

        // WHEN a request is made from the SimPin screens to show the next security method
        when(mKeyguardSecurityModel.getSecurityMode(TARGET_USER_ID)).thenReturn(SecurityMode.None);
        mKeyguardSecurityContainerController.showNextSecurityScreenOrFinish(
                /* authenticated= */true,
                TARGET_USER_ID,
                /* bypassSecondaryLockScreen= */true,
                SecurityMode.SimPin);

        // THEN the next security method of None will dismiss keyguard.
        verify(mViewMediatorCallback).keyguardDone(anyBoolean(), anyInt());
    }

    @Test
    public void showNextSecurityScreenOrFinish_ignoresCallWhenSecurityMethodHasChanged() {
        //GIVEN current security mode has been set to PIN
        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.PIN);

        //WHEN a request comes from SimPin to dismiss the security screens
        boolean keyguardDone = mKeyguardSecurityContainerController.showNextSecurityScreenOrFinish(
                /* authenticated= */true,
                TARGET_USER_ID,
                /* bypassSecondaryLockScreen= */true,
                SecurityMode.SimPin);

        //THEN no action has happened, which will not dismiss the security screens
        assertThat(keyguardDone).isEqualTo(false);
        verify(mKeyguardUpdateMonitor, never()).getUserHasTrust(anyInt());
    }

    @Test
    public void onSwipeUp_whenFaceDetectionIsNotRunning_initiatesFaceAuth() {
        KeyguardSecurityContainer.SwipeListener registeredSwipeListener =
                getRegisteredSwipeListener();
        when(mKeyguardUpdateMonitor.isFaceDetectionRunning()).thenReturn(false);
        setupGetSecurityView(SecurityMode.Password);

        registeredSwipeListener.onSwipeUp();

        verify(mKeyguardUpdateMonitor).requestFaceAuth(
                FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER);
    }

    @Test
    public void onSwipeUp_whenFaceDetectionIsRunning_doesNotInitiateFaceAuth() {
        KeyguardSecurityContainer.SwipeListener registeredSwipeListener =
                getRegisteredSwipeListener();
        when(mKeyguardUpdateMonitor.isFaceDetectionRunning()).thenReturn(true);

        registeredSwipeListener.onSwipeUp();

        verify(mKeyguardUpdateMonitor, never())
                .requestFaceAuth(FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER);
    }

    @Test
    public void onSwipeUp_whenFaceDetectionIsTriggered_hidesBouncerMessage() {
        KeyguardSecurityContainer.SwipeListener registeredSwipeListener =
                getRegisteredSwipeListener();
        when(mKeyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER))
                .thenReturn(true);
        setupGetSecurityView(SecurityMode.Password);

        clearInvocations(mKeyguardSecurityViewFlipperController);
        registeredSwipeListener.onSwipeUp();
        getViewControllerImmediately();

        verify(mKeyguardPasswordViewControllerMock).showMessage(/* message= */
                null, /* colorState= */ null, /* animated= */ true);
    }

    @Test
    public void onSwipeUp_whenFaceDetectionIsNotTriggered_retainsBouncerMessage() {
        KeyguardSecurityContainer.SwipeListener registeredSwipeListener =
                getRegisteredSwipeListener();
        when(mKeyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER))
                .thenReturn(false);
        setupGetSecurityView(SecurityMode.Password);

        registeredSwipeListener.onSwipeUp();

        verify(mKeyguardPasswordViewControllerMock, never()).showMessage(/* message= */
                null, /* colorState= */ null, /* animated= */ true);
    }

    @Test
    public void onDensityOrFontScaleChanged() {
        ArgumentCaptor<ConfigurationController.ConfigurationListener>
                configurationListenerArgumentCaptor = ArgumentCaptor.forClass(
                ConfigurationController.ConfigurationListener.class);
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());
        clearInvocations(mKeyguardSecurityViewFlipperController);

        configurationListenerArgumentCaptor.getValue().onDensityOrFontScaleChanged();

        verify(mKeyguardSecurityViewFlipperController).clearViews();
        verify(mKeyguardSecurityViewFlipperController).asynchronouslyInflateView(
                eq(SecurityMode.PIN),
                any(KeyguardSecurityCallback.class),
                mOnViewInflatedCallbackArgumentCaptor.capture());

        mOnViewInflatedCallbackArgumentCaptor.getValue().onViewInflated(mInputViewController);

        verify(mView).onDensityOrFontScaleChanged();
    }

    @Test
    public void onThemeChanged() {
        ArgumentCaptor<ConfigurationController.ConfigurationListener>
                configurationListenerArgumentCaptor = ArgumentCaptor.forClass(
                ConfigurationController.ConfigurationListener.class);
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());
        clearInvocations(mKeyguardSecurityViewFlipperController);

        configurationListenerArgumentCaptor.getValue().onThemeChanged();

        verify(mKeyguardSecurityViewFlipperController).clearViews();
        verify(mKeyguardSecurityViewFlipperController).asynchronouslyInflateView(
                eq(SecurityMode.PIN),
                any(KeyguardSecurityCallback.class),
                mOnViewInflatedCallbackArgumentCaptor.capture());

        mOnViewInflatedCallbackArgumentCaptor.getValue().onViewInflated(mInputViewController);

        verify(mView).reset();
        verify(mKeyguardSecurityViewFlipperController).reset();
        verify(mView).reloadColors();
    }

    @Test
    public void onUiModeChanged() {
        ArgumentCaptor<ConfigurationController.ConfigurationListener>
                configurationListenerArgumentCaptor = ArgumentCaptor.forClass(
                ConfigurationController.ConfigurationListener.class);
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());
        clearInvocations(mKeyguardSecurityViewFlipperController);

        configurationListenerArgumentCaptor.getValue().onUiModeChanged();

        verify(mKeyguardSecurityViewFlipperController).clearViews();
        verify(mKeyguardSecurityViewFlipperController).asynchronouslyInflateView(
                eq(SecurityMode.PIN),
                any(KeyguardSecurityCallback.class),
                mOnViewInflatedCallbackArgumentCaptor.capture());

        mOnViewInflatedCallbackArgumentCaptor.getValue().onViewInflated(mInputViewController);

        verify(mView).reloadColors();
    }

    @Test
    public void testHasDismissActions() {
        assertFalse("Action not set yet", mKeyguardSecurityContainerController.hasDismissActions());
        mKeyguardSecurityContainerController.setOnDismissAction(mock(
                        ActivityStarter.OnDismissAction.class),
                null /* cancelAction */);
        assertTrue("Action should exist", mKeyguardSecurityContainerController.hasDismissActions());
    }

    @Test
    public void testWillRunDismissFromKeyguardIsTrue() {
        ActivityStarter.OnDismissAction action = mock(ActivityStarter.OnDismissAction.class);
        when(action.willRunAnimationOnKeyguard()).thenReturn(true);
        mKeyguardSecurityContainerController.setOnDismissAction(action, null /* cancelAction */);

        mKeyguardSecurityContainerController.finish(false /* strongAuth */, 0 /* currentUser */);

        assertThat(mKeyguardSecurityContainerController.willRunDismissFromKeyguard()).isTrue();
    }

    @Test
    public void testWillRunDismissFromKeyguardIsFalse() {
        ActivityStarter.OnDismissAction action = mock(ActivityStarter.OnDismissAction.class);
        when(action.willRunAnimationOnKeyguard()).thenReturn(false);
        mKeyguardSecurityContainerController.setOnDismissAction(action, null /* cancelAction */);

        mKeyguardSecurityContainerController.finish(false /* strongAuth */, 0 /* currentUser */);

        assertThat(mKeyguardSecurityContainerController.willRunDismissFromKeyguard()).isFalse();
    }

    @Test
    public void testWillRunDismissFromKeyguardIsFalseWhenNoDismissActionSet() {
        mKeyguardSecurityContainerController.setOnDismissAction(null /* action */,
                null /* cancelAction */);

        mKeyguardSecurityContainerController.finish(false /* strongAuth */, 0 /* currentUser */);

        assertThat(mKeyguardSecurityContainerController.willRunDismissFromKeyguard()).isFalse();
    }

    @Test
    public void testSecurityCallbackFinish() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        when(mKeyguardUpdateMonitor.isUserUnlocked(0)).thenReturn(true);
        mKeyguardSecurityContainerController.finish(true, 0);
        verify(mViewMediatorCallback).keyguardDone(anyBoolean(), anyInt());
    }

    @Test
    public void testSecurityCallbackFinish_cannotDismissLockScreenAndNotStrongAuth() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        mKeyguardSecurityContainerController.finish(false, 0);
        verify(mViewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt());
    }

    @Test
    public void testOnStartingToHide() {
        mKeyguardSecurityContainerController.onStartingToHide();
        verify(mKeyguardSecurityViewFlipperController).getSecurityView(any(SecurityMode.class),
                any(KeyguardSecurityCallback.class),
                mOnViewInflatedCallbackArgumentCaptor.capture());

        mOnViewInflatedCallbackArgumentCaptor.getValue().onViewInflated(mInputViewController);
        verify(mInputViewController).onStartingToHide();
    }

    @Test
    public void testGravityReappliedOnConfigurationChange() {
        // Set initial gravity
        mTestableResources.addOverride(R.integer.keyguard_host_view_gravity,
                Gravity.CENTER);
        mTestableResources.addOverride(
                R.bool.can_use_one_handed_bouncer, false);

        // Kick off the initial pass...
        mKeyguardSecurityContainerController.onInit();
        verify(mView).setLayoutParams(any());
        clearInvocations(mView);

        // Now simulate a config change
        mTestableResources.addOverride(R.integer.keyguard_host_view_gravity,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);

        mKeyguardSecurityContainerController.updateResources();
        verify(mView).setLayoutParams(any());
    }

    @Test
    public void testGravityUsesOneHandGravityWhenApplicable() {
        mTestableResources.addOverride(
                R.integer.keyguard_host_view_gravity,
                Gravity.CENTER);
        mTestableResources.addOverride(
                R.integer.keyguard_host_view_one_handed_gravity,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);

        // Start disabled.
        mTestableResources.addOverride(
                R.bool.can_use_one_handed_bouncer, false);

        mKeyguardSecurityContainerController.onInit();
        verify(mView).setLayoutParams(argThat(
                (ArgumentMatcher<FrameLayout.LayoutParams>) argument ->
                        argument.gravity == Gravity.CENTER));
        clearInvocations(mView);

        // And enable
        mTestableResources.addOverride(
                R.bool.can_use_one_handed_bouncer, true);

        mKeyguardSecurityContainerController.updateResources();
        verify(mView).setLayoutParams(argThat(
                (ArgumentMatcher<FrameLayout.LayoutParams>) argument ->
                        argument.gravity == (Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM)));
    }

    @Test
    public void testUpdateKeyguardPositionDelegatesToSecurityContainer() {
        mKeyguardSecurityContainerController.updateKeyguardPosition(1.0f);
        verify(mView).updatePositionByTouchX(1.0f);
    }

    @Test
    public void testReinflateViewFlipper() {
        KeyguardSecurityViewFlipperController.OnViewInflatedCallback onViewInflatedCallback =
                controller -> {
                };
        mKeyguardSecurityContainerController.reinflateViewFlipper(onViewInflatedCallback);
        verify(mKeyguardSecurityViewFlipperController).clearViews();
        verify(mKeyguardSecurityViewFlipperController).asynchronouslyInflateView(
                any(SecurityMode.class),
                any(KeyguardSecurityCallback.class), eq(onViewInflatedCallback));
    }

    @Test
    public void testSideFpsControllerShow() {
        mKeyguardSecurityContainerController.updateSideFpsVisibility(/* isVisible= */ true);
        verify(mSideFpsController).show(
                SideFpsUiRequestSource.PRIMARY_BOUNCER,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD);
    }

    @Test
    public void testSideFpsControllerHide() {
        mKeyguardSecurityContainerController.updateSideFpsVisibility(/* isVisible= */ false);
        verify(mSideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
    }

    private KeyguardSecurityContainer.SwipeListener getRegisteredSwipeListener() {
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mView).setSwipeListener(mSwipeListenerArgumentCaptor.capture());
        return mSwipeListenerArgumentCaptor.getValue();
    }

    private void setupGetSecurityView(SecurityMode securityMode) {
        mKeyguardSecurityContainerController.showSecurityScreen(securityMode);
        getViewControllerImmediately();
    }

    private void getViewControllerImmediately() {
        verify(mKeyguardSecurityViewFlipperController, atLeastOnce()).getSecurityView(
                any(SecurityMode.class), any(),
                mOnViewInflatedCallbackArgumentCaptor.capture());
        mOnViewInflatedCallbackArgumentCaptor.getValue().onViewInflated(
                (KeyguardInputViewController) mKeyguardPasswordViewControllerMock);

    }
}
