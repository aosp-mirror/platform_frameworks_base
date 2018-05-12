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
 * limitations under the License
 */

package com.android.keyguard;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.KeyEvent;
import android.view.LayoutInflater;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class KeyguardPinBasedInputViewTest extends SysuiTestCase {

    @Mock
    private PasswordTextView mPasswordEntry;
    @Mock
    private SecurityMessageDisplay mSecurityMessageDisplay;
    @InjectMocks
    private KeyguardPinBasedInputView mKeyguardPinView;

    @Before
    public void setup() {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        mKeyguardPinView =
                (KeyguardPinBasedInputView) inflater.inflate(R.layout.keyguard_pin_view, null);
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void onResume_requestsFocus() {
        mKeyguardPinView.onResume(KeyguardSecurityView.SCREEN_ON);
        verify(mPasswordEntry).requestFocus();
    }

    @Test
    public void onKeyDown_clearsSecurityMessage() {
        mKeyguardPinView.onKeyDown(KeyEvent.KEYCODE_0, mock(KeyEvent.class));
        verify(mSecurityMessageDisplay).setMessage(eq(""));
    }

    @Test
    public void onKeyDown_noSecurityMessageInteraction() {
        mKeyguardPinView.onKeyDown(KeyEvent.KEYCODE_UNKNOWN, mock(KeyEvent.class));
        verifyZeroInteractions(mSecurityMessageDisplay);
    }
}
