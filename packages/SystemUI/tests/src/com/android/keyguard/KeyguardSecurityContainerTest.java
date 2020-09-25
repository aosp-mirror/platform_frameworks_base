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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
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
public class KeyguardSecurityContainerTest extends SysuiTestCase {

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private WindowInsetsController mWindowInsetsController;
    @Mock
    private KeyguardSecurityViewFlipper mSecurityViewFlipper;

    private KeyguardSecurityContainer mKeyguardSecurityContainer;

    @Before
    public void setup() {
        when(mSecurityViewFlipper.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        mKeyguardSecurityContainer = new KeyguardSecurityContainer(getContext());
        mKeyguardSecurityContainer.mSecurityViewFlipper = mSecurityViewFlipper;
    }

    @Test
    public void startDisappearAnimation_animatesKeyboard() {
        mKeyguardSecurityContainer.startDisappearAnimation(SecurityMode.Password);
        verify(mWindowInsetsController).controlWindowInsetsAnimation(eq(ime()), anyLong(), any(),
                any(), any());
    }
}