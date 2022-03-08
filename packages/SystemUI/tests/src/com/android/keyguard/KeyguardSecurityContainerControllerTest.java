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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.WindowInsetsController;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class KeyguardSecurityContainerControllerTest extends SysuiTestCase {
    private static final int VIEW_WIDTH = 1600;

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
    private KeyguardMessageArea mKeyguardMessageArea;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private EmergencyButtonController mEmergencyButtonController;
    @Mock
    private Resources mResources;
    @Mock
    private FalsingCollector mFalsingCollector;
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
        when(mView.getWidth()).thenReturn(VIEW_WIDTH);
        when(mAdminSecondaryLockScreenControllerFactory.create(any(KeyguardSecurityCallback.class)))
                .thenReturn(mAdminSecondaryLockScreenController);
        when(mSecurityViewFlipper.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        mKeyguardPasswordView = spy(new KeyguardPasswordView(getContext()));
        when(mKeyguardPasswordView.getRootView()).thenReturn(mSecurityViewFlipper);
        when(mKeyguardPasswordView.findViewById(R.id.keyguard_message_area))
                .thenReturn(mKeyguardMessageArea);
        when(mKeyguardPasswordView.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        mKeyguardPasswordViewController = new KeyguardPasswordViewController(
                (KeyguardPasswordView) mKeyguardPasswordView, mKeyguardUpdateMonitor,
                SecurityMode.Password, mLockPatternUtils, null,
                mKeyguardMessageAreaControllerFactory, null, null, mEmergencyButtonController,
                null, mock(Resources.class), null);

        mKeyguardSecurityContainerController = new KeyguardSecurityContainerController.Factory(
                mView, mAdminSecondaryLockScreenControllerFactory, mLockPatternUtils,
                mKeyguardUpdateMonitor, mKeyguardSecurityModel, mMetricsLogger, mUiEventLogger,
                mKeyguardStateController, mKeyguardSecurityViewFlipperController,
                mConfigurationController, mFalsingCollector)
                .create(mSecurityCallback);
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
        verify(mView, times(0)).setOneHandedMode(anyBoolean());

        // Update rotation. Should trigger update
        mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;

        mKeyguardSecurityContainerController.updateResources();
        verify(mView, times(1)).setOneHandedMode(anyBoolean());
    }

    @Test
    public void updateKeyguardPosition_callsThroughToViewInOneHandedMode() {
        when(mView.isOneHandedMode()).thenReturn(true);
        mKeyguardSecurityContainerController.updateKeyguardPosition(VIEW_WIDTH / 3f);
        verify(mView).setOneHandedModeLeftAligned(true, false);

        mKeyguardSecurityContainerController.updateKeyguardPosition((VIEW_WIDTH / 3f) * 2);
        verify(mView).setOneHandedModeLeftAligned(false, false);
    }

    @Test
    public void updateKeyguardPosition_ignoredInTwoHandedMode() {
        when(mView.isOneHandedMode()).thenReturn(false);
        mKeyguardSecurityContainerController.updateKeyguardPosition(1.0f);
        verify(mView, never()).setOneHandedModeLeftAligned(anyBoolean(), anyBoolean());
    }

    private void touchDownLeftSide() {
        mKeyguardSecurityContainerController.mGlobalTouchListener.onTouchEvent(
                MotionEvent.obtain(
                        /* downTime= */0,
                        /* eventTime= */0,
                        MotionEvent.ACTION_DOWN,
                        /* x= */VIEW_WIDTH / 3f,
                        /* y= */0,
                        /* metaState= */0));
    }

    private void touchDownRightSide() {
        mKeyguardSecurityContainerController.mGlobalTouchListener.onTouchEvent(
                MotionEvent.obtain(
                        /* downTime= */0,
                        /* eventTime= */0,
                        MotionEvent.ACTION_DOWN,
                        /* x= */(VIEW_WIDTH / 3f) * 2,
                        /* y= */0,
                        /* metaState= */0));
    }

    @Test
    public void onInterceptTap_inhibitsFalsingInOneHandedMode() {
        when(mView.isOneHandedMode()).thenReturn(true);
        when(mView.isOneHandedModeLeftAligned()).thenReturn(true);

        touchDownLeftSide();
        verify(mFalsingCollector, never()).avoidGesture();

        // Now on the right.
        touchDownRightSide();
        verify(mFalsingCollector).avoidGesture();

        // Move and re-test
        reset(mFalsingCollector);
        when(mView.isOneHandedModeLeftAligned()).thenReturn(false);

        // On the right...
        touchDownRightSide();
        verify(mFalsingCollector, never()).avoidGesture();

        touchDownLeftSide();
        verify(mFalsingCollector).avoidGesture();
    }

    @Test
    public void showSecurityScreen_oneHandedMode_bothFlagsDisabled_noOneHandedMode() {
        setUpKeyguardFlags(
                /* deviceConfigCanUseOneHandedKeyguard= */false,
                /* sysuiResourceCanUseOneHandedKeyguard= */false);

        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                eq(SecurityMode.Pattern), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewController);

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Pattern);
        verify(mView).setOneHandedMode(false);
    }

    @Test
    public void showSecurityScreen_oneHandedMode_deviceFlagDisabled_noOneHandedMode() {
        setUpKeyguardFlags(
                /* deviceConfigCanUseOneHandedKeyguard= */false,
                /* sysuiResourceCanUseOneHandedKeyguard= */true);

        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                eq(SecurityMode.Pattern), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewController);

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Pattern);
        verify(mView).setOneHandedMode(false);
    }

    @Test
    public void showSecurityScreen_oneHandedMode_sysUiFlagDisabled_noOneHandedMode() {
        setUpKeyguardFlags(
                /* deviceConfigCanUseOneHandedKeyguard= */true,
                /* sysuiResourceCanUseOneHandedKeyguard= */false);

        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                eq(SecurityMode.Pattern), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewController);

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Pattern);
        verify(mView).setOneHandedMode(false);
    }

    @Test
    public void showSecurityScreen_oneHandedMode_bothFlagsEnabled_oneHandedMode() {
        setUpKeyguardFlags(
                /* deviceConfigCanUseOneHandedKeyguard= */true,
                /* sysuiResourceCanUseOneHandedKeyguard= */true);

        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                eq(SecurityMode.Pattern), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewController);

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Pattern);
        verify(mView).setOneHandedMode(true);
    }

    @Test
    public void showSecurityScreen_twoHandedMode_bothFlagsEnabled_noOneHandedMode() {
        setUpKeyguardFlags(
                /* deviceConfigCanUseOneHandedKeyguard= */true,
                /* sysuiResourceCanUseOneHandedKeyguard= */true);

        when(mKeyguardSecurityViewFlipperController.getSecurityView(
                eq(SecurityMode.Password), any(KeyguardSecurityCallback.class)))
                .thenReturn((KeyguardInputViewController) mKeyguardPasswordViewController);

        mKeyguardSecurityContainerController.showSecurityScreen(SecurityMode.Password);
        verify(mView).setOneHandedMode(false);
    }

    private void setUpKeyguardFlags(
            boolean deviceConfigCanUseOneHandedKeyguard,
            boolean sysuiResourceCanUseOneHandedKeyguard) {
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_enableDynamicKeyguardPositioning))
                .thenReturn(deviceConfigCanUseOneHandedKeyguard);
        when(mResources.getBoolean(
                R.bool.can_use_one_handed_bouncer))
                .thenReturn(sysuiResourceCanUseOneHandedKeyguard);
    }
}
