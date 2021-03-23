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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowInsetsController;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
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

    private KeyguardSecurityContainerController mKeyguardSecurityContainerController;
    private KeyguardPasswordViewController mKeyguardPasswordViewController;
    private KeyguardPasswordView mKeyguardPasswordView;

    @Before
    public void setup() {
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
                mConfigurationController)
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
}
