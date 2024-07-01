/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricSourceType;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.logging.KeyguardUpdateMonitorLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import dagger.Lazy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Random;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4.class)
public class KeyguardStateControllerTest extends SysuiTestCase {

    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private Lazy<KeyguardUnlockAnimationController> mKeyguardUnlockAnimationControllerLazy;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private KeyguardUpdateMonitorLogger mLogger;
    @Mock
    private FeatureFlags mFeatureFlags;

    @Captor
    private ArgumentCaptor<KeyguardUpdateMonitorCallback> mUpdateCallbackCaptor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mKeyguardStateController = new KeyguardStateControllerImpl(
                mContext,
                mKeyguardUpdateMonitor,
                mLockPatternUtils,
                mKeyguardUnlockAnimationControllerLazy,
                mLogger,
                mDumpManager,
                mFeatureFlags,
                mSelectedUserInteractor);
    }

    @Test
    public void testAddCallback_registersListener() {
        verify(mKeyguardUpdateMonitor).registerCallback(any());
    }

    @Test
    public void testFaceAuthEnrolleddChanged_calledWhenFaceEnrollmentStateChanges() {
        KeyguardStateController.Callback callback = mock(KeyguardStateController.Callback.class);

        when(mKeyguardUpdateMonitor.isFaceEnabledAndEnrolled()).thenReturn(false);
        verify(mKeyguardUpdateMonitor).registerCallback(mUpdateCallbackCaptor.capture());
        mKeyguardStateController.addCallback(callback);
        assertThat(mKeyguardStateController.isFaceEnrolledAndEnabled()).isFalse();

        when(mKeyguardUpdateMonitor.isFaceEnabledAndEnrolled()).thenReturn(true);
        mUpdateCallbackCaptor.getValue().onBiometricEnrollmentStateChanged(
                BiometricSourceType.FACE);

        assertThat(mKeyguardStateController.isFaceEnrolledAndEnabled()).isTrue();
        verify(callback).onFaceEnrolledChanged();
    }

    @Test
    public void testIsShowing() {
        assertThat(mKeyguardStateController.isShowing()).isFalse();
        mKeyguardStateController.notifyKeyguardState(true /* showing */, false /* occluded */);
        assertThat(mKeyguardStateController.isShowing()).isTrue();
    }

    @Test
    public void testIsMethodSecure() {
        assertThat(mKeyguardStateController.isMethodSecure()).isFalse();

        when(mLockPatternUtils.isSecure(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);
        assertThat(mKeyguardStateController.isMethodSecure()).isTrue();
    }

    @Test
    public void testIsOccluded() {
        assertThat(mKeyguardStateController.isOccluded()).isFalse();
        mKeyguardStateController.notifyKeyguardState(false /* showing */, true /* occluded */);
        assertThat(mKeyguardStateController.isOccluded()).isTrue();
    }

    @Test
    public void testCanSkipLockScreen() {
        // Can skip because LockPatternUtils#isSecure is false
        assertThat(mKeyguardStateController.canDismissLockScreen()).isTrue();

        // Cannot skip after there's a password/pin/pattern
        when(mLockPatternUtils.isSecure(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);
        assertThat(mKeyguardStateController.canDismissLockScreen()).isFalse();

        // Unless user is authenticated
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);
        assertThat(mKeyguardStateController.canDismissLockScreen()).isTrue();
    }

    @Test
    public void testCanSkipLockScreen_updateCalledOnFacesCleared() {
        verify(mKeyguardUpdateMonitor).registerCallback(mUpdateCallbackCaptor.capture());

        // Cannot skip after there's a password/pin/pattern
        when(mLockPatternUtils.isSecure(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);
        assertThat(mKeyguardStateController.canDismissLockScreen()).isFalse();

        // Unless user is authenticated
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(anyInt())).thenReturn(true);
        mUpdateCallbackCaptor.getValue().onFacesCleared();
        assertThat(mKeyguardStateController.canDismissLockScreen()).isTrue();
    }

    @Test
    public void testCanSkipLockScreen_updateCalledOnFingerprintssCleared() {
        verify(mKeyguardUpdateMonitor).registerCallback(mUpdateCallbackCaptor.capture());

        // Cannot skip after there's a password/pin/pattern
        when(mLockPatternUtils.isSecure(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);
        assertThat(mKeyguardStateController.canDismissLockScreen()).isFalse();

        // Unless user is authenticated
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(anyInt())).thenReturn(true);
        mUpdateCallbackCaptor.getValue().onFingerprintsCleared();
        assertThat(mKeyguardStateController.canDismissLockScreen()).isTrue();
    }

    @Test
    public void testIsUnlocked() {
        // Is unlocked whenever the keyguard is not showing
        assertThat(mKeyguardStateController.isShowing()).isFalse();
        assertThat(mKeyguardStateController.isUnlocked()).isTrue();

        // Unlocked if showing, but insecure
        mKeyguardStateController.notifyKeyguardState(true /* showing */, false /* occluded */);
        assertThat(mKeyguardStateController.isUnlocked()).isTrue();

        // Locked if showing, and requires password
        when(mLockPatternUtils.isSecure(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);
        assertThat(mKeyguardStateController.isUnlocked()).isFalse();

        // But unlocked after #getUserCanSkipBouncer allows it
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);
        assertThat(mKeyguardStateController.isUnlocked()).isTrue();
    }

    @Test
    public void testIsTrusted() {
        assertThat(mKeyguardStateController.isTrusted()).isFalse();

        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);

        assertThat(mKeyguardStateController.isTrusted()).isTrue();
    }

    @Test
    public void testCallbacksAreInvoked() {
        KeyguardStateController.Callback callback = mock(KeyguardStateController.Callback.class);
        mKeyguardStateController.addCallback(callback);

        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        ((KeyguardStateControllerImpl) mKeyguardStateController).update(false /* alwaysUpdate */);

        verify(callback).onUnlockedChanged();
    }

    @Test
    public void testKeyguardDismissAmountCallbackInvoked() {
        KeyguardStateController.Callback callback = mock(KeyguardStateController.Callback.class);
        mKeyguardStateController.addCallback(callback);

        mKeyguardStateController.notifyKeyguardDismissAmountChanged(100f, false);

        verify(callback).onKeyguardDismissAmountChanged();
    }

    @Test
    public void testOnEnabledTrustAgentsChangedCallback() {
        final Random random = new Random();

        verify(mKeyguardUpdateMonitor).registerCallback(mUpdateCallbackCaptor.capture());
        final KeyguardStateController.Callback stateCallback =
                mock(KeyguardStateController.Callback.class);
        mKeyguardStateController.addCallback(stateCallback);

        when(mLockPatternUtils.isSecure(anyInt())).thenReturn(true);
        mUpdateCallbackCaptor.getValue().onEnabledTrustAgentsChanged(random.nextInt());
        verify(stateCallback).onUnlockedChanged();
    }
}
