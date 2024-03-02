/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.policy;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.os.Handler;
import android.os.PowerManager;
import android.os.test.TestLooper;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableResources;
import android.view.Window;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit tests for {@link SideFpsEventHandler}.
 *
 * <p>Run with <code>atest SideFpsEventHandlerTest</code>.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class SideFpsEventHandlerTest {

    private static final List<Integer> sAllStates =
            List.of(
                    BiometricStateListener.STATE_IDLE,
                    BiometricStateListener.STATE_ENROLLING,
                    BiometricStateListener.STATE_KEYGUARD_AUTH,
                    BiometricStateListener.STATE_BP_AUTH,
                    BiometricStateListener.STATE_AUTH_OTHER);

    private static final Integer AUTO_DISMISS_DIALOG = 500;

    @Rule
    public TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private SideFpsToast mDialog;
    @Mock
    private Window mWindow;

    private final TestLooper mLooper = new TestLooper();
    private SideFpsEventHandler mEventHandler;
    private BiometricStateListener mBiometricStateListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mContext.addMockSystemService(PackageManager.class, mPackageManager);
        mContext.addMockSystemService(FingerprintManager.class, mFingerprintManager);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_sideFpsToastTimeout, AUTO_DISMISS_DIALOG);

        when(mDialog.getWindow()).thenReturn(mWindow);

        mEventHandler =
                new SideFpsEventHandler(
                        mContext,
                        new Handler(mLooper.getLooper()),
                        mContext.getSystemService(PowerManager.class),
                        (ctx) -> mDialog);
    }

    @Test
    public void ignoresWithoutFingerprintFeature() {
        when(mPackageManager.hasSystemFeature(eq(PackageManager.FEATURE_FINGERPRINT)))
                .thenReturn(false);

        assertThat(mEventHandler.shouldConsumeSinglePress(60L)).isFalse();

        mLooper.dispatchAll();
        verify(mDialog, never()).show();
    }

    @Test
    public void ignoresWithoutSfps() throws Exception {
        setupWithSensor(false /* hasSfps */, true /* initialized */);

        for (int state : sAllStates) {
            setBiometricState(state);
            assertThat(mEventHandler.shouldConsumeSinglePress(200L)).isFalse();

            mLooper.dispatchAll();
            verify(mDialog, never()).show();
        }
    }

    @Test
    public void ignoresWhileWaitingForSfps() throws Exception {
        setupWithSensor(true /* hasSfps */, false /* initialized */);

        for (int state : sAllStates) {
            setBiometricState(state);
            assertThat(mEventHandler.shouldConsumeSinglePress(400L)).isFalse();

            mLooper.dispatchAll();
            verify(mDialog, never()).show();
        }
    }

    @Test
    public void ignoresWhenIdleOrUnknown() throws Exception {
        setupWithSensor(true /* hasSidefps */, true /* initialized */);

        setBiometricState(BiometricStateListener.STATE_IDLE);
        assertThat(mEventHandler.shouldConsumeSinglePress(80000L)).isFalse();

        setBiometricState(BiometricStateListener.STATE_AUTH_OTHER);
        assertThat(mEventHandler.shouldConsumeSinglePress(90000L)).isFalse();

        mLooper.dispatchAll();
        verify(mDialog, never()).show();
    }

    @Test
    public void ignoresOnKeyguard() throws Exception {
        setupWithSensor(true /* hasSfps */, true /* initialized */);

        setBiometricState(BiometricStateListener.STATE_KEYGUARD_AUTH);
        assertThat(mEventHandler.shouldConsumeSinglePress(80000L)).isFalse();

        mLooper.dispatchAll();
        verify(mDialog, never()).show();
    }

    @Test
    public void doesNotpromptWhenBPisActive() throws Exception {
        setupWithSensor(true /* hasSideFps */, true /* initialized */);

        setBiometricState(BiometricStateListener.STATE_BP_AUTH);
        assertThat(mEventHandler.shouldConsumeSinglePress(80000L)).isTrue();

        mLooper.dispatchAll();
        verify(mDialog, never()).show();
    }

    @Test
    public void promptsWhenEnrolling() throws Exception {
        setupWithSensor(true /* hasSfps */, true /* initialized */);

        setBiometricState(BiometricStateListener.STATE_ENROLLING);
        assertThat(mEventHandler.shouldConsumeSinglePress(80000L)).isTrue();

        mLooper.dispatchAll();
        verify(mDialog).show();
    }

    @Test
    public void dialogDismissesAfterTime() throws Exception {
        setupWithSensor(true /* hasSfps */, true /* initialized */);

        setBiometricState(BiometricStateListener.STATE_ENROLLING);
        when(mDialog.isShowing()).thenReturn(true);
        assertThat(mEventHandler.shouldConsumeSinglePress(80000L)).isTrue();

        mLooper.dispatchAll();
        verify(mDialog).show();
        mLooper.moveTimeForward(AUTO_DISMISS_DIALOG);
        mLooper.dispatchAll();
        verify(mDialog).dismiss();
    }

    @Test
    public void dialogDoesNotDismissOnSensorTouch() throws Exception {
        setupWithSensor(true /* hasSfps */, true /* initialized */);

        setBiometricState(BiometricStateListener.STATE_ENROLLING);
        when(mDialog.isShowing()).thenReturn(true);
        assertThat(mEventHandler.shouldConsumeSinglePress(80000L)).isTrue();

        mLooper.dispatchAll();
        verify(mDialog).show();

        mBiometricStateListener.onBiometricAction(BiometricStateListener.ACTION_SENSOR_TOUCH);
        mLooper.moveTimeForward(AUTO_DISMISS_DIALOG - 1);
        mLooper.dispatchAll();

        verify(mDialog, never()).dismiss();
    }

    private void setBiometricState(@BiometricStateListener.State int newState) {
        if (mBiometricStateListener != null) {
            mBiometricStateListener.onStateChanged(newState);
            mLooper.dispatchAll();
        }
    }

    private void setupWithSensor(boolean hasSfps, boolean initialized) throws Exception {
        when(mPackageManager.hasSystemFeature(eq(PackageManager.FEATURE_FINGERPRINT)))
                .thenReturn(true);
        when(mFingerprintManager.isPowerbuttonFps()).thenReturn(hasSfps);
        mEventHandler.onFingerprintSensorReady();

        ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback> fpCallbackCaptor =
                ArgumentCaptor.forClass(IFingerprintAuthenticatorsRegisteredCallback.class);
        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(fpCallbackCaptor.capture());
        if (initialized) {
            fpCallbackCaptor
                    .getValue()
                    .onAllAuthenticatorsRegistered(
                            List.of(mock(FingerprintSensorPropertiesInternal.class)));
            if (hasSfps) {
                ArgumentCaptor<BiometricStateListener> captor =
                        ArgumentCaptor.forClass(BiometricStateListener.class);
                verify(mFingerprintManager).registerBiometricStateListener(captor.capture());
                mBiometricStateListener = captor.getValue();
            }
        }
    }
}
