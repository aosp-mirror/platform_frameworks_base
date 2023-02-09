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

import static android.view.WindowInsets.Type.ime;

import static com.android.keyguard.KeyguardSecurityContainer.MODE_DEFAULT;
import static com.android.keyguard.KeyguardSecurityContainer.MODE_ONE_HANDED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricSourceType;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsetsController;

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
import com.android.systemui.log.SessionTracker;
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
    private KeyguardSecurityContainer.SecurityCallback mSecurityCallback;
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
    private Resources mResources;
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

    @Captor
    private ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardUpdateMonitorCallback;
    @Captor
    private ArgumentCaptor<KeyguardSecurityContainer.SwipeListener> mSwipeListenerArgumentCaptor;

    private Configuration mConfiguration;

    private KeyguardSecurityContainerController mKeyguardSecurityContainerController;
    private KeyguardPasswordViewController mKeyguardPasswordViewController;
    private KeyguardPasswordView mKeyguardPasswordView;

    @Before
    public void setup() {
        mConfiguration = new Configuration();
        mConfiguration.setToDefaults(); // Defaults to ORIENTATION_UNDEFINED.

        when(mResources.getConfiguration()).thenReturn(mConfiguration);
        when(mView.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mResources);
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
        mKeyguardPasswordViewController = new KeyguardPasswordViewController(
                (KeyguardPasswordView) mKeyguardPasswordView, mKeyguardUpdateMonitor,
                SecurityMode.Password, mLockPatternUtils, null,
                mKeyguardMessageAreaControllerFactory, null, null, mEmergencyButtonController,
                null, mock(Resources.class), null, mKeyguardViewController);

        mKeyguardSecurityContainerController = new KeyguardSecurityContainerController.Factory(
                mView, mAdminSecondaryLockScreenControllerFactory, mLockPatternUtils,
                mKeyguardUpdateMonitor, mKeyguardSecurityModel, mMetricsLogger, mUiEventLogger,
                mKeyguardStateController, mKeyguardSecurityViewFlipperController,
                mConfigurationController, mFalsingCollector, mFalsingManager,
                mUserSwitcherController, mFeatureFlags, mGlobalSettings,
                mSessionTracker, Optional.of(mSideFpsController), mFalsingA11yDelegate).create(
                mSecurityCallback);
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
                        any(SecurityMode.class), any(KeyguardSecurityCallback.class));
            } else {
                verify(mKeyguardSecurityViewFlipperController).getSecurityView(
                        eq(mode), any(KeyguardSecurityCallback.class));
            }
        }
    }

    @Test
    public void startDisappearAnimation_animatesKeyboard() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                SecurityMode.Password);
        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                eq(SecurityMode.Password), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewController);
        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Password);

        mKeyguardSecurityContainerController.startDisappearAnimation(null);
        verify(mWindowInsetsController).controlWindowInsetsAnimation(
                eq(ime()), anyLong(), any(), any(), any());
    }

    @Test
    public void onResourcesUpdate_callsThroughOnRotationChange() {
        // Rotation is the same, shouldn't cause an update
        mKeyguardSecurityContainerController.updateResources();
        verify(mView, never()).initMode(eq(MODE_DEFAULT), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));

        // Update rotation. Should trigger update
        mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;

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
        when(mResources.getBoolean(R.bool.can_use_one_handed_bouncer)).thenReturn(false);
        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                eq(SecurityMode.Pattern), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewController);

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Pattern);
        verify(mView).initMode(eq(MODE_DEFAULT), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));
    }

    @Test
    public void showSecurityScreen_oneHandedMode_flagEnabled_oneHandedMode() {
        when(mResources.getBoolean(R.bool.can_use_one_handed_bouncer)).thenReturn(true);
        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                eq(SecurityMode.Pattern), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewController);

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Pattern);
        verify(mView).initMode(eq(MODE_ONE_HANDED), eq(mGlobalSettings), eq(mFalsingManager),
                eq(mUserSwitcherController),
                any(KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback.class),
                eq(mFalsingA11yDelegate));
    }

    @Test
    public void showSecurityScreen_twoHandedMode_flagEnabled_noOneHandedMode() {
        when(mResources.getBoolean(R.bool.can_use_one_handed_bouncer)).thenReturn(true);
        setupGetSecurityView();

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Password);
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

        setupGetSecurityView();

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Password);
        verify(mView).initMode(anyInt(), any(GlobalSettings.class), any(FalsingManager.class),
                any(UserSwitcherController.class),
                captor.capture(),
                eq(mFalsingA11yDelegate));
        captor.getValue().showUnlockToContinueMessage();
        verify(mKeyguardPasswordViewControllerMock).showMessage(
                getContext().getString(R.string.keyguard_unlock_to_continue), null);
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
    public void onBouncerVisibilityChanged_allConditionsGood_sideFpsHintShown() {
        setupConditionsToEnableSideFpsHint();
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);

        verify(mSideFpsController).show(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).hide(any());
    }

    @Test
    public void onBouncerVisibilityChanged_fpsSensorNotRunning_sideFpsHintHidden() {
        setupConditionsToEnableSideFpsHint();
        setFingerprintDetectionRunning(false);
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);

        verify(mSideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).show(any());
    }

    @Test
    public void onBouncerVisibilityChanged_withoutSidedSecurity_sideFpsHintHidden() {
        setupConditionsToEnableSideFpsHint();
        setSideFpsHintEnabledFromResources(false);
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);

        verify(mSideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).show(any());
    }

    @Test
    public void onBouncerVisibilityChanged_unlockingWithFingerprintNotAllowed_sideFpsHintHidden() {
        setupConditionsToEnableSideFpsHint();
        setUnlockingWithFingerprintAllowed(false);
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);

        verify(mSideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).show(any());
    }

    @Test
    public void onBouncerVisibilityChanged_sideFpsHintShown_sideFpsHintHidden() {
        setupGetSecurityView();
        setupConditionsToEnableSideFpsHint();
        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);
        verify(mSideFpsController, atLeastOnce()).show(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.INVISIBLE);

        verify(mSideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).show(any());
    }

    @Test
    public void onBouncerVisibilityChanged_resetsScale() {
        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.INVISIBLE);

        verify(mView).resetScale();
    }

    @Test
    public void onStartingToHide_sideFpsHintShown_sideFpsHintHidden() {
        setupGetSecurityView();
        setupConditionsToEnableSideFpsHint();
        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);
        verify(mSideFpsController, atLeastOnce()).show(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onStartingToHide();

        verify(mSideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).show(any());
    }

    @Test
    public void onPause_sideFpsHintShown_sideFpsHintHidden() {
        setupGetSecurityView();
        setupConditionsToEnableSideFpsHint();
        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);
        verify(mSideFpsController, atLeastOnce()).show(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onPause();

        verify(mSideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).show(any());
    }

    @Test
    public void onResume_sideFpsHintShouldBeShown_sideFpsHintShown() {
        setupGetSecurityView();
        setupConditionsToEnableSideFpsHint();
        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onResume(0);

        verify(mSideFpsController).show(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).hide(any());
    }

    @Test
    public void onResume_sideFpsHintShouldNotBeShown_sideFpsHintHidden() {
        setupGetSecurityView();
        setupConditionsToEnableSideFpsHint();
        setSideFpsHintEnabledFromResources(false);
        mKeyguardSecurityContainerController.onBouncerVisibilityChanged(View.VISIBLE);
        reset(mSideFpsController);

        mKeyguardSecurityContainerController.onResume(0);

        verify(mSideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        verify(mSideFpsController, never()).show(any());
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
        verify(mSecurityCallback, never()).finish(anyBoolean(), anyInt());
        assertThat(mKeyguardSecurityContainerController.getCurrentSecurityMode())
                .isEqualTo(SecurityMode.PIN);
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
        setupGetSecurityView();

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
        setupGetSecurityView();

        registeredSwipeListener.onSwipeUp();

        verify(mKeyguardPasswordViewControllerMock).showMessage(null, null);
    }

    @Test
    public void onSwipeUp_whenFaceDetectionIsNotTriggered_retainsBouncerMessage() {
        KeyguardSecurityContainer.SwipeListener registeredSwipeListener =
                getRegisteredSwipeListener();
        when(mKeyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER))
                .thenReturn(false);
        setupGetSecurityView();

        registeredSwipeListener.onSwipeUp();

        verify(mKeyguardPasswordViewControllerMock, never()).showMessage(null, null);
    }

    @Test
    public void onDensityorFontScaleChanged() {
        ArgumentCaptor<ConfigurationController.ConfigurationListener>
                configurationListenerArgumentCaptor = ArgumentCaptor.forClass(
                ConfigurationController.ConfigurationListener.class);
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());
        configurationListenerArgumentCaptor.getValue().onDensityOrFontScaleChanged();

        verify(mView).onDensityOrFontScaleChanged();
        verify(mKeyguardSecurityViewFlipperController).clearViews();
        verify(mKeyguardSecurityViewFlipperController).getSecurityView(any(SecurityMode.class),
                any(KeyguardSecurityCallback.class));
    }

    @Test
    public void onThemeChanged() {
        ArgumentCaptor<ConfigurationController.ConfigurationListener>
                configurationListenerArgumentCaptor = ArgumentCaptor.forClass(
                ConfigurationController.ConfigurationListener.class);
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());
        configurationListenerArgumentCaptor.getValue().onThemeChanged();

        verify(mView).reloadColors();
        verify(mKeyguardSecurityViewFlipperController).clearViews();
        verify(mKeyguardSecurityViewFlipperController).getSecurityView(any(SecurityMode.class),
                any(KeyguardSecurityCallback.class));
    }

    @Test
    public void onUiModeChanged() {
        ArgumentCaptor<ConfigurationController.ConfigurationListener>
                configurationListenerArgumentCaptor = ArgumentCaptor.forClass(
                ConfigurationController.ConfigurationListener.class);
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());
        configurationListenerArgumentCaptor.getValue().onUiModeChanged();

        verify(mView).reloadColors();
        verify(mKeyguardSecurityViewFlipperController).clearViews();
        verify(mKeyguardSecurityViewFlipperController).getSecurityView(any(SecurityMode.class),
                any(KeyguardSecurityCallback.class));
    }

    private KeyguardSecurityContainer.SwipeListener getRegisteredSwipeListener() {
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mView).setSwipeListener(mSwipeListenerArgumentCaptor.capture());
        return mSwipeListenerArgumentCaptor.getValue();
    }

    private void setupConditionsToEnableSideFpsHint() {
        attachView();
        setSideFpsHintEnabledFromResources(true);
        setFingerprintDetectionRunning(true);
        setUnlockingWithFingerprintAllowed(true);
    }

    private void attachView() {
        mKeyguardSecurityContainerController.onViewAttached();
        verify(mKeyguardUpdateMonitor).registerCallback(mKeyguardUpdateMonitorCallback.capture());
    }

    private void setFingerprintDetectionRunning(boolean running) {
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(running);
        mKeyguardUpdateMonitorCallback.getValue().onBiometricRunningStateChanged(running,
                BiometricSourceType.FINGERPRINT);
    }

    private void setSideFpsHintEnabledFromResources(boolean enabled) {
        when(mResources.getBoolean(R.bool.config_show_sidefps_hint_on_bouncer)).thenReturn(
                enabled);
    }

    private void setUnlockingWithFingerprintAllowed(boolean allowed) {
        when(mKeyguardUpdateMonitor.isUnlockingWithFingerprintAllowed()).thenReturn(allowed);
    }

    private void setupGetSecurityView() {
        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                any(), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewControllerMock);
    }
}
