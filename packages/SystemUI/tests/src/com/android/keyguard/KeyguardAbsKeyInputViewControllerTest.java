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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.KeyEvent;

import androidx.test.filters.SmallTest;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardAbsKeyInputView.KeyDownListener;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.classifier.FalsingCollectorFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class KeyguardAbsKeyInputViewControllerTest extends SysuiTestCase {

    @Mock
    private KeyguardAbsKeyInputView mAbsKeyInputView;
    @Mock
    private PasswordTextView mPasswordEntry;
    @Mock
    private BouncerKeyguardMessageArea mKeyguardMessageArea;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private SecurityMode mSecurityMode;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private KeyguardSecurityCallback mKeyguardSecurityCallback;
    @Mock
    private KeyguardMessageAreaController.Factory mKeyguardMessageAreaControllerFactory;
    @Mock
    private KeyguardMessageAreaController mKeyguardMessageAreaController;
    @Mock
    private LatencyTracker mLatencyTracker;
    private final FalsingCollector mFalsingCollector = new FalsingCollectorFake();
    @Mock
    private EmergencyButtonController mEmergencyButtonController;

    private KeyguardAbsKeyInputViewController mKeyguardAbsKeyInputViewController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mKeyguardMessageAreaControllerFactory.create(any(KeyguardMessageArea.class)))
                .thenReturn(mKeyguardMessageAreaController);
        when(mAbsKeyInputView.getPasswordTextViewId()).thenReturn(1);
        when(mAbsKeyInputView.findViewById(1)).thenReturn(mPasswordEntry);
        when(mAbsKeyInputView.isAttachedToWindow()).thenReturn(true);
        when(mAbsKeyInputView.requireViewById(R.id.bouncer_message_area))
                .thenReturn(mKeyguardMessageArea);
        mKeyguardAbsKeyInputViewController = new KeyguardAbsKeyInputViewController(mAbsKeyInputView,
                mKeyguardUpdateMonitor, mSecurityMode, mLockPatternUtils, mKeyguardSecurityCallback,
                mKeyguardMessageAreaControllerFactory, mLatencyTracker, mFalsingCollector,
                mEmergencyButtonController) {
            @Override
            void resetState() {
            }

            @Override
            public void onResume(int reason) {
                super.onResume(reason);
            }
        };
        mKeyguardAbsKeyInputViewController.init();
        reset(mKeyguardMessageAreaController);  // Clear out implicit call to init.
    }

    @Test
    public void onKeyDown_clearsSecurityMessage() {
        ArgumentCaptor<KeyDownListener> onKeyDownListenerArgumentCaptor =
                ArgumentCaptor.forClass(KeyDownListener.class);
        verify(mAbsKeyInputView).setKeyDownListener(onKeyDownListenerArgumentCaptor.capture());
        onKeyDownListenerArgumentCaptor.getValue().onKeyDown(
                KeyEvent.KEYCODE_0, mock(KeyEvent.class));
        verify(mKeyguardSecurityCallback).userActivity();
        verify(mKeyguardMessageAreaController).setMessage(eq(""));
    }

    @Test
    public void onKeyDown_noSecurityMessageInteraction() {
        ArgumentCaptor<KeyDownListener> onKeyDownListenerArgumentCaptor =
                ArgumentCaptor.forClass(KeyDownListener.class);
        verify(mAbsKeyInputView).setKeyDownListener(onKeyDownListenerArgumentCaptor.capture());
        onKeyDownListenerArgumentCaptor.getValue().onKeyDown(
                KeyEvent.KEYCODE_UNKNOWN, mock(KeyEvent.class));
        verifyZeroInteractions(mKeyguardSecurityCallback);
        verifyZeroInteractions(mKeyguardMessageAreaController);
    }
}
