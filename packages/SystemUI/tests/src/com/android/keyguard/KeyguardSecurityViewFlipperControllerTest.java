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

import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.WindowInsetsController;

import androidx.asynclayoutinflater.view.AsyncLayoutInflater;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper()
public class KeyguardSecurityViewFlipperControllerTest extends SysuiTestCase {

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private KeyguardSecurityViewFlipper mView;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private AsyncLayoutInflater mAsyncLayoutInflater;
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
    @Mock
    private FeatureFlags mFeatureFlags;

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
        when(mView.getContext()).thenReturn(getContext());

        mKeyguardSecurityViewFlipperController = new KeyguardSecurityViewFlipperController(mView,
                mLayoutInflater, mAsyncLayoutInflater, mKeyguardSecurityViewControllerFactory,
                mEmergencyButtonControllerFactory, mFeatureFlags);
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
            mKeyguardSecurityViewFlipperController.getSecurityView(mode, mKeyguardSecurityCallback,
                    controller -> {
                        if (mode == SecurityMode.Invalid || mode == SecurityMode.None) {
                            verify(mLayoutInflater, never()).inflate(
                                    anyInt(), any(ViewGroup.class), anyBoolean());
                        } else {
                            verify(mLayoutInflater).inflate(anyInt(), eq(mView), eq(false));
                        }
                    });
        }
    }

    @Test
    public void getSecurityView_NotInflated() {
        mKeyguardSecurityViewFlipperController.clearViews();
        mKeyguardSecurityViewFlipperController.getSecurityView(SecurityMode.PIN,
                mKeyguardSecurityCallback,
                controller -> {});
        verify(mAsyncLayoutInflater).inflate(anyInt(), eq(mView), any(
                AsyncLayoutInflater.OnInflateFinishedListener.class));
    }

    @Test
    public void asynchronouslyInflateView() {
        mKeyguardSecurityViewFlipperController.asynchronouslyInflateView(SecurityMode.PIN,
                mKeyguardSecurityCallback, null);
        verify(mAsyncLayoutInflater).inflate(anyInt(), eq(mView), any(
                AsyncLayoutInflater.OnInflateFinishedListener.class));
    }

    @Test
    public void asynchronouslyInflateView_setNeedsInput() {
        ArgumentCaptor<AsyncLayoutInflater.OnInflateFinishedListener> argumentCaptor =
                ArgumentCaptor.forClass(AsyncLayoutInflater.OnInflateFinishedListener.class);
        mKeyguardSecurityViewFlipperController.asynchronouslyInflateView(SecurityMode.PIN,
                mKeyguardSecurityCallback, null);
        verify(mAsyncLayoutInflater).inflate(anyInt(), eq(mView), argumentCaptor.capture());
        argumentCaptor.getValue().onInflateFinished(
                LayoutInflater.from(getContext()).inflate(R.layout.keyguard_password_view, null),
                R.layout.keyguard_password_view, mView);
    }

    @Test
    public void onDensityOrFontScaleChanged() {
        mKeyguardSecurityViewFlipperController.clearViews();
        verify(mView).removeAllViews();
    }
}
