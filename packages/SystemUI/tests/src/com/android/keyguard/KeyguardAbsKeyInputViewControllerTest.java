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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.os.SystemClock;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper.RunWithLooper;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardAbsKeyInputView.KeyDownListener;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.haptics.msdl.FakeMSDLPlayer;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.res.R;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import com.google.android.msdl.data.model.MSDLToken;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
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

    private FakeFeatureFlags mFeatureFlags;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private UserActivityNotifier mUserActivityNotifier;
    private KeyguardAbsKeyInputViewController mKeyguardAbsKeyInputViewController;
    private KosmosJavaAdapter mKosmosJavaAdapter = new KosmosJavaAdapter(this);
    private final BouncerHapticPlayer mBouncerHapticPlayer =
            mKosmosJavaAdapter.getBouncerHapticHelper();
    private final FakeMSDLPlayer mMSDLPlayer = mKosmosJavaAdapter.getMsdlPlayer();

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
        when(mAbsKeyInputView.getResources()).thenReturn(getContext().getResources());
        mFeatureFlags = new FakeFeatureFlags();
        mKeyguardAbsKeyInputViewController = createTestObject();
        mKeyguardAbsKeyInputViewController.init();
        reset(mKeyguardMessageAreaController);  // Clear out implicit call to init.
    }

    private KeyguardAbsKeyInputViewController createTestObject() {
        return new KeyguardAbsKeyInputViewController(mAbsKeyInputView,
                mKeyguardUpdateMonitor, mSecurityMode, mLockPatternUtils, mKeyguardSecurityCallback,
                mKeyguardMessageAreaControllerFactory, mLatencyTracker, mFalsingCollector,
                mEmergencyButtonController, mFeatureFlags, mSelectedUserInteractor,
                mBouncerHapticPlayer, mUserActivityNotifier) {
            @Override
            void resetState() {
            }

            @Override
            public void onResume(int reason) {
                super.onResume(reason);
            }

            @Override
            protected int getInitialMessageResId() {
                return 0;
            }
        };
    }

    @Test
    @EnableFlags(Flags.FLAG_REVAMPED_BOUNCER_MESSAGES)
    public void withFeatureFlagOn_oldMessage_isHidden() {
        KeyguardAbsKeyInputViewController underTest = createTestObject();

        underTest.init();

        verify(mKeyguardMessageAreaController).disable();
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

    @Test
    public void onPromptReasonNone_doesNotSetMessage() {
        mKeyguardAbsKeyInputViewController.showPromptReason(0);
        verify(mKeyguardMessageAreaController, never()).setMessage(
                getContext().getResources().getString(R.string.kg_prompt_reason_restart_password),
                false);
    }

    @Test
    public void onPromptReason_setsMessage() {
        when(mAbsKeyInputView.getPromptReasonStringRes(1)).thenReturn(
                R.string.kg_prompt_reason_restart_password);
        mKeyguardAbsKeyInputViewController.showPromptReason(1);
        verify(mKeyguardMessageAreaController).setMessage(
                getContext().getResources().getString(R.string.kg_prompt_reason_restart_password),
                false);
    }


    @Test
    public void testReset() {
        mKeyguardAbsKeyInputViewController.reset();
        verify(mKeyguardMessageAreaController).setMessage("", false);
    }

    @Test
    public void testOnViewAttached() {
        reset(mLockPatternUtils);
        mKeyguardAbsKeyInputViewController.onViewAttached();
        verify(mLockPatternUtils).getLockoutAttemptDeadline(anyInt());
    }

    @Test
    public void testLockedOut_verifyPasswordAndUnlock_doesNotEnableViewInput() {
        mKeyguardAbsKeyInputViewController.handleAttemptLockout(SystemClock.elapsedRealtime());
        verify(mAbsKeyInputView).setPasswordEntryInputEnabled(false);
        verify(mAbsKeyInputView).setPasswordEntryEnabled(false);
        verify(mAbsKeyInputView, never()).setPasswordEntryInputEnabled(true);
        verify(mAbsKeyInputView, never()).setPasswordEntryEnabled(true);
    }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    public void onPasswordChecked_withMSDLFeedback_withMatch_playsUnlockToken() {
        mKeyguardAbsKeyInputViewController.onPasswordChecked(0, true, 100, true);
        assertThat(mMSDLPlayer.getLatestTokenPlayed()).isEqualTo(MSDLToken.UNLOCK);
    }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    public void onPasswordChecked_withoutMSDLFeedback_withMatch_doesNotPlayToken() {
        mKeyguardAbsKeyInputViewController.onPasswordChecked(0, true, 100, true);
        assertThat(mMSDLPlayer.getLatestTokenPlayed()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    public void onPasswordChecked_withMSDLFeedback_withoutMatch_playsFailureToken() {
        mKeyguardAbsKeyInputViewController.onPasswordChecked(0, false, 100, true);
        assertThat(mMSDLPlayer.getLatestTokenPlayed()).isEqualTo(MSDLToken.FAILURE);
    }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    public void onPasswordChecked_withoutMSDLFeedback_withoutMatch_doesNotPlayToken() {
        mKeyguardAbsKeyInputViewController.onPasswordChecked(0, false, 100, true);
        assertThat(mMSDLPlayer.getLatestTokenPlayed()).isNull();
    }
}
