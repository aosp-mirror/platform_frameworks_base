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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.WindowInsetsController;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.InjectionInflationController;

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
    private LayoutInflater mLayoutInflater;
    @Mock
    private InjectionInflationController mInjectionInflationController;
    @Mock
    private KeyguardInputViewController.Factory mKeyguardSecurityViewControllerFactory;
    @Mock
    private KeyguardInputViewController mKeyguardInputViewController;
    @Mock
    private KeyguardInputView mInputView;
    @Mock
    private KeyguardSecurityContainer.SecurityCallback mSecurityCallback;
    @Mock
    private WindowInsetsController mWindowInsetsController;
    @Mock
    private KeyguardSecurityViewFlipper mSecurityViewFlipper;

    private KeyguardSecurityContainerController mKeyguardSecurityContainerController;

    @Before
    public void setup() {
        when(mAdminSecondaryLockScreenControllerFactory.create(any(KeyguardSecurityCallback.class)))
                .thenReturn(mAdminSecondaryLockScreenController);
        when(mInjectionInflationController.injectable(mLayoutInflater)).thenReturn(mLayoutInflater);
        when(mKeyguardSecurityViewControllerFactory.create(
                any(KeyguardInputView.class), any(SecurityMode.class),
                any(KeyguardSecurityCallback.class)))
                .thenReturn(mKeyguardInputViewController);
        when(mView.getSecurityViewFlipper()).thenReturn(mSecurityViewFlipper);
        when(mSecurityViewFlipper.getWindowInsetsController()).thenReturn(mWindowInsetsController);

        mKeyguardSecurityContainerController = new KeyguardSecurityContainerController(
                mView,  mAdminSecondaryLockScreenControllerFactory, mLockPatternUtils,
                mKeyguardUpdateMonitor, mKeyguardSecurityModel, mMetricsLogger, mUiEventLogger,
                mKeyguardStateController, mLayoutInflater, mInjectionInflationController,
                mKeyguardSecurityViewControllerFactory
        );

        mKeyguardSecurityContainerController.setSecurityCallback(mSecurityCallback);
    }

    @Test
    public void showSecurityScreen_canInflateAllModes() {
        KeyguardSecurityModel.SecurityMode[] modes =
                KeyguardSecurityModel.SecurityMode.values();
        for (KeyguardSecurityModel.SecurityMode mode : modes) {
            reset(mLayoutInflater);
            when(mLayoutInflater.inflate(anyInt(), eq(mSecurityViewFlipper), eq(false)))
                    .thenReturn(mInputView);
            when(mKeyguardInputViewController.getSecurityMode()).thenReturn(mode);
            mKeyguardSecurityContainerController.showSecurityScreen(mode);
            if (mode == SecurityMode.Invalid || mode == SecurityMode.None) {
                verify(mLayoutInflater, never()).inflate(
                        anyInt(), any(ViewGroup.class), anyBoolean());
            } else {
                verify(mLayoutInflater).inflate(anyInt(), eq(mSecurityViewFlipper), eq(false));
            }
        }
    }

    @Test
    public void startDisappearAnimation_animatesKeyboard() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.Password);
        when(mKeyguardInputViewController.getSecurityMode()).thenReturn(
                KeyguardSecurityModel.SecurityMode.Password);
        when(mLayoutInflater.inflate(anyInt(), eq(mSecurityViewFlipper), eq(false)))
                .thenReturn(mInputView);
        mKeyguardSecurityContainerController.showPrimarySecurityScreen(false /* turningOff */);

        mKeyguardSecurityContainerController.startDisappearAnimation(null);
        verify(mKeyguardInputViewController).startDisappearAnimation(eq(null));
    }
}
