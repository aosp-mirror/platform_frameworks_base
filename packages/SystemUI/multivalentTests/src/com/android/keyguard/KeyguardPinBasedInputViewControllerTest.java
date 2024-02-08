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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.TestableLooper.RunWithLooper;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository;
import com.android.systemui.res.R;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper
public class KeyguardPinBasedInputViewControllerTest extends SysuiTestCase {

    @Mock
    private KeyguardPinBasedInputView mPinBasedInputView;
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
    @Mock
    private LiftToActivateListener mLiftToactivateListener;
    @Mock
    private EmergencyButtonController mEmergencyButtonController;
    private FalsingCollector mFalsingCollector = new FalsingCollectorFake();
    @Mock
    private View mDeleteButton;
    @Mock
    private View mOkButton;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    private NumPadKey[] mButtons = new NumPadKey[]{};

    private KeyguardPinBasedInputViewController mKeyguardPinViewController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mKeyguardMessageAreaControllerFactory.create(any(KeyguardMessageArea.class)))
                .thenReturn(mKeyguardMessageAreaController);
        when(mPinBasedInputView.getPasswordTextViewId()).thenReturn(1);
        when(mPinBasedInputView.findViewById(1)).thenReturn(mPasswordEntry);
        when(mPinBasedInputView.isAttachedToWindow()).thenReturn(true);
        when(mPinBasedInputView.getButtons()).thenReturn(mButtons);
        when(mPinBasedInputView.requireViewById(R.id.bouncer_message_area))
                .thenReturn(mKeyguardMessageArea);
        when(mPinBasedInputView.findViewById(R.id.delete_button))
                .thenReturn(mDeleteButton);
        when(mPinBasedInputView.findViewById(R.id.key_enter))
                .thenReturn(mOkButton);

        when(mPinBasedInputView.getResources()).thenReturn(getContext().getResources());
        KeyguardKeyboardInteractor keyguardKeyboardInteractor =
                new KeyguardKeyboardInteractor(new FakeKeyboardRepository());
        FakeFeatureFlags featureFlags = new FakeFeatureFlags();
        mSetFlagsRule.enableFlags(com.android.systemui.Flags.FLAG_REVAMPED_BOUNCER_MESSAGES);
        mKeyguardPinViewController = new KeyguardPinBasedInputViewController(mPinBasedInputView,
                mKeyguardUpdateMonitor, mSecurityMode, mLockPatternUtils, mKeyguardSecurityCallback,
                mKeyguardMessageAreaControllerFactory, mLatencyTracker, mLiftToactivateListener,
                mEmergencyButtonController, mFalsingCollector, featureFlags,
                mSelectedUserInteractor, keyguardKeyboardInteractor) {
            @Override
            public void onResume(int reason) {
                super.onResume(reason);
            }
        };
        mKeyguardPinViewController.init();
    }

    @Test
    public void onResume_requestsFocus() {
        mKeyguardPinViewController.onResume(KeyguardSecurityView.SCREEN_ON);
        verify(mPasswordEntry).requestFocus();
    }

    @Test
    public void testGetInitialMessageResId() {
        assertThat(mKeyguardPinViewController.getInitialMessageResId()).isNotEqualTo(0);
    }

    @Test
    public void testMessageIsSetWhenReset() {
        mKeyguardPinViewController.resetState();
        verify(mKeyguardMessageAreaController).setMessage(R.string.keyguard_enter_your_pin);
    }
}
