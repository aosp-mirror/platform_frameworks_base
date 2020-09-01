/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import static android.view.WindowInsets.Type.ime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.WindowInsetsController;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
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
public class KeyguardSecurityContainerTest extends SysuiTestCase {

    @Mock
    private KeyguardSecurityModel mKeyguardSecurityModel;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardSecurityContainer.SecurityCallback mSecurityCallback;
    @Mock
    private KeyguardSecurityView mSecurityView;
    @Mock
    private WindowInsetsController mWindowInsetsController;
    @Mock
    private KeyguardSecurityViewFlipper mSecurityViewFlipper;
    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();
    private KeyguardSecurityContainer mKeyguardSecurityContainer;

    @Before
    public void setup() {
        mDependency.injectTestDependency(KeyguardStateController.class, mKeyguardStateController);
        mDependency.injectTestDependency(KeyguardSecurityModel.class, mKeyguardSecurityModel);
        mDependency.injectTestDependency(KeyguardUpdateMonitor.class, mKeyguardUpdateMonitor);
        mKeyguardSecurityContainer = new KeyguardSecurityContainer(getContext()) {
            @Override
            protected KeyguardSecurityView getSecurityView(
                    KeyguardSecurityModel.SecurityMode securityMode) {
                return mSecurityView;
            }
        };
        mKeyguardSecurityContainer.mSecurityViewFlipper = mSecurityViewFlipper;
        when(mSecurityViewFlipper.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        mKeyguardSecurityContainer.setSecurityCallback(mSecurityCallback);
    }

    @Test
    public void showSecurityScreen_canInflateAllModes() {
        Context context = getContext();

        for (int theme : new int[] {R.style.Theme_SystemUI, R.style.Theme_SystemUI_Light}) {
            context.setTheme(theme);
            final LayoutInflater inflater = LayoutInflater.from(context);
            KeyguardSecurityModel.SecurityMode[] modes =
                    KeyguardSecurityModel.SecurityMode.values();
            for (KeyguardSecurityModel.SecurityMode mode : modes) {
                final int resId = mKeyguardSecurityContainer.getLayoutIdFor(mode);
                if (resId == 0) {
                    continue;
                }
                inflater.inflate(resId, null /* root */, false /* attach */);
            }
        }
    }

    @Test
    public void startDisappearAnimation_animatesKeyboard() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.Password);
        mKeyguardSecurityContainer.showPrimarySecurityScreen(false /* turningOff */);

        mKeyguardSecurityContainer.startDisappearAnimation(null);
        verify(mSecurityView).startDisappearAnimation(eq(null));
        verify(mWindowInsetsController).controlWindowInsetsAnimation(eq(ime()), anyLong(), any(),
                any(), any());
    }
}