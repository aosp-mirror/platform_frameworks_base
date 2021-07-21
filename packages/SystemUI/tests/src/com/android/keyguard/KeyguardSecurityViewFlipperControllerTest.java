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

import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.SysuiTestCase;

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
public class KeyguardSecurityViewFlipperControllerTest extends SysuiTestCase {

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private KeyguardSecurityViewFlipper mView;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private KeyguardInputViewController.Factory mKeyguardSecurityViewControllerFactory;
    @Mock
    private EmergencyButtonController.Factory mEmergencyButtonControllerFactory;
    @Mock
    private EmergencyButtonController mEmergencyButtonController;
    @Mock
    private KeyguardInputViewController mKeyguardInputViewController;
    @Mock
    private KeyguardInputView mInputView;
    @Mock
    private WindowInsetsController mWindowInsetsController;
    @Mock
    private KeyguardSecurityCallback mKeyguardSecurityCallback;

    private KeyguardSecurityViewFlipperController mKeyguardSecurityViewFlipperController;

    @Before
    public void setup() {
        when(mKeyguardSecurityViewControllerFactory.create(
                any(KeyguardInputView.class), any(SecurityMode.class),
                any(KeyguardSecurityCallback.class)))
                .thenReturn(mKeyguardInputViewController);
        when(mView.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        when(mEmergencyButtonControllerFactory.create(any(EmergencyButton.class)))
                .thenReturn(mEmergencyButtonController);

        mKeyguardSecurityViewFlipperController = new KeyguardSecurityViewFlipperController(mView,
                mLayoutInflater, mKeyguardSecurityViewControllerFactory,
                mEmergencyButtonControllerFactory);
    }

    @Test
    public void showSecurityScreen_canInflateAllModes() {
        SecurityMode[] modes = SecurityMode.values();
        // Always return an invalid controller so that we're always making a new one.
        when(mKeyguardInputViewController.getSecurityMode()).thenReturn(SecurityMode.Invalid);
        for (SecurityMode mode : modes) {
            reset(mLayoutInflater);
            when(mLayoutInflater.inflate(anyInt(), eq(mView), eq(false)))
                    .thenReturn(mInputView);
            mKeyguardSecurityViewFlipperController.getSecurityView(mode, mKeyguardSecurityCallback);
            if (mode == SecurityMode.Invalid || mode == SecurityMode.None) {
                verify(mLayoutInflater, never()).inflate(
                        anyInt(), any(ViewGroup.class), anyBoolean());
            } else {
                verify(mLayoutInflater).inflate(anyInt(), eq(mView), eq(false));
            }
        }
    }
}
