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

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Bundle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class AuthBiometricFaceToFingerprintViewTest extends SysuiTestCase {

    @Mock AuthBiometricView.Callback mCallback;

    private AuthBiometricFaceToFingerprintView mFaceToFpView;

    @Mock private Button mNegativeButton;
    @Mock private Button mCancelButton;
    @Mock private Button mConfirmButton;
    @Mock private Button mUseCredentialButton;
    @Mock private Button mTryAgainButton;

    @Mock private TextView mTitleView;
    @Mock private TextView mSubtitleView;
    @Mock private TextView mDescriptionView;
    @Mock private TextView mIndicatorView;
    @Mock private ImageView mIconView;
    @Mock private View mIconHolderView;
    @Mock private AuthBiometricFaceView.IconController mFaceIconController;
    @Mock private AuthBiometricFaceToFingerprintView.UdfpsIconController mUdfpsIconController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mFaceToFpView = new TestableView(mContext);
        mFaceToFpView.mFaceIconController = mFaceIconController;
        mFaceToFpView.mUdfpsIconController = mUdfpsIconController;
        mFaceToFpView.setCallback(mCallback);

        mFaceToFpView.mNegativeButton = mNegativeButton;
        mFaceToFpView.mCancelButton = mCancelButton;
        mFaceToFpView.mUseCredentialButton = mUseCredentialButton;
        mFaceToFpView.mConfirmButton = mConfirmButton;
        mFaceToFpView.mTryAgainButton = mTryAgainButton;
        mFaceToFpView.mIndicatorView = mIndicatorView;
    }

    @Test
    public void testStateUpdated_whenDialogAnimatedIn() {
        mFaceToFpView.onDialogAnimatedIn();
        verify(mFaceToFpView.mFaceIconController)
                .updateState(anyInt(), eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));
        verify(mFaceToFpView.mUdfpsIconController, never()).updateState(anyInt());
    }

    @Test
    public void testIconUpdatesState_whenDialogStateUpdated() {
        mFaceToFpView.onDialogAnimatedIn();
        verify(mFaceToFpView.mFaceIconController)
                .updateState(anyInt(), eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));
        verify(mFaceToFpView.mUdfpsIconController, never()).updateState(anyInt());

        mFaceToFpView.updateState(AuthBiometricFaceView.STATE_AUTHENTICATED);
        verify(mFaceToFpView.mFaceIconController).updateState(
                eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING),
                eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATED));
        verify(mFaceToFpView.mUdfpsIconController, never()).updateState(anyInt());

        assertEquals(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATED, mFaceToFpView.mState);
    }

    @Test
    public void testStateUpdated_whenSwitchToFingerprint() {
        mFaceToFpView.onDialogAnimatedIn();
        verify(mFaceToFpView.mFaceIconController)
                .updateState(anyInt(), eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));

        mFaceToFpView.updateState(AuthBiometricFaceToFingerprintView.STATE_ERROR);

        verify(mFaceToFpView.mFaceIconController).deactivate();
        verify(mFaceToFpView.mUdfpsIconController).updateState(
                eq(AuthBiometricFaceToFingerprintView.STATE_IDLE));
        verify(mConfirmButton).setVisibility(eq(View.GONE));

        mFaceToFpView.updateState(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING);

        verify(mFaceToFpView.mUdfpsIconController).updateState(
                eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));
    }

    @Test
    public void testStateUpdated_whenSwitchToFingerprint_invokesCallbacks() {
        class TestModalityListener implements ModalityListener {
            public int switchCount = 0;

            @Override
            public void onModalitySwitched(int oldModality, int newModality) {
                assertEquals(TYPE_FINGERPRINT, newModality);
                assertEquals(TYPE_FACE, oldModality);
                switchCount++;
            }
        }
        final TestModalityListener modalityListener = new TestModalityListener();

        mFaceToFpView.onDialogAnimatedIn();
        mFaceToFpView.setModalityListener(modalityListener);

        assertEquals(0, modalityListener.switchCount);

        mFaceToFpView.updateState(AuthBiometricFaceToFingerprintView.STATE_ERROR);
        mFaceToFpView.updateState(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING);

        assertEquals(1, modalityListener.switchCount);
    }

    @Test
    @Ignore("flaky, b/189031816")
    public void testModeUpdated_onSoftError_whenSwitchToFingerprint() {
        mFaceToFpView.onDialogAnimatedIn();
        mFaceToFpView.onAuthenticationFailed(TYPE_FACE, "no face");
        waitForIdleSync();

        verify(mIndicatorView).setText(
                eq(mContext.getString(R.string.fingerprint_dialog_use_fingerprint_instead)));
        verify(mCallback).onAction(
                eq(AuthBiometricView.Callback.ACTION_START_DELAYED_FINGERPRINT_SENSOR));

        // First we enter the error state, since we need to show the error animation/text. The
        // error state is later cleared based on a timer, and we enter STATE_AUTHENTICATING.
        assertEquals(AuthBiometricFaceToFingerprintView.STATE_ERROR, mFaceToFpView.mState);
    }

    @Test
    @Ignore("flaky, b/189031816")
    public void testModeUpdated_onHardError_whenSwitchToFingerprint() {
        mFaceToFpView.onDialogAnimatedIn();
        mFaceToFpView.onError(TYPE_FACE, "oh no!");
        waitForIdleSync();

        verify(mIndicatorView).setText(
                eq(mContext.getString(R.string.fingerprint_dialog_use_fingerprint_instead)));
        verify(mCallback).onAction(
                eq(AuthBiometricView.Callback.ACTION_START_DELAYED_FINGERPRINT_SENSOR));

        // First we enter the error state, since we need to show the error animation/text. The
        // error state is later cleared based on a timer, and we enter STATE_AUTHENTICATING.
        assertEquals(AuthBiometricFaceToFingerprintView.STATE_ERROR, mFaceToFpView.mState);
    }

    @Test
    public void testFingerprintOnlyStartsOnFirstError() {
        mFaceToFpView.onDialogAnimatedIn();
        verify(mFaceToFpView.mFaceIconController)
                .updateState(anyInt(), eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));

        mFaceToFpView.onDialogAnimatedIn();
        mFaceToFpView.updateState(AuthBiometricFaceToFingerprintView.STATE_ERROR);
        mFaceToFpView.updateState(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING);

        reset(mCallback);

        mFaceToFpView.onError(TYPE_FACE, "oh no!");
        mFaceToFpView.onAuthenticationFailed(TYPE_FACE, "no face");

        verify(mCallback, never()).onAction(
                eq(AuthBiometricView.Callback.ACTION_START_DELAYED_FINGERPRINT_SENSOR));
    }

    @Test
    public void testOnSaveState() {
        final FingerprintSensorPropertiesInternal sensorProps = createFingerprintSensorProps();
        mFaceToFpView.setFingerprintSensorProps(sensorProps);

        final Bundle savedState = new Bundle();
        mFaceToFpView.onSaveState(savedState);

        assertEquals(savedState.getInt(AuthDialog.KEY_BIOMETRIC_SENSOR_TYPE),
                mFaceToFpView.getActiveSensorType());
        assertEquals(savedState.getParcelable(AuthDialog.KEY_BIOMETRIC_SENSOR_PROPS), sensorProps);
    }

    @Test
    public void testRestoreState() {
        final Bundle savedState = new Bundle();
        savedState.putInt(AuthDialog.KEY_BIOMETRIC_SENSOR_TYPE, TYPE_FINGERPRINT);
        savedState.putParcelable(AuthDialog.KEY_BIOMETRIC_SENSOR_PROPS,
                createFingerprintSensorProps());

        mFaceToFpView.restoreState(savedState);

        assertEquals(mFaceToFpView.getActiveSensorType(), TYPE_FINGERPRINT);
        assertTrue(mFaceToFpView.isFingerprintUdfps());
    }

    @NonNull
    private static FingerprintSensorPropertiesInternal createFingerprintSensorProps() {
        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("componentId", "hardwareVersion",
                "firmwareVersion", "serialNumber", "softwareVersion"));

        return new FingerprintSensorPropertiesInternal(
                0 /* sensorId */,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequiresHardwareAuthToken */,
                540 /* sensorLocationX */,
                1600 /* sensorLocationY */,
                100 /* sensorRadius */);
    }

    public class TestableView extends AuthBiometricFaceToFingerprintView {
        public TestableView(Context context) {
            super(context, null, new MockInjector());
        }

        @Override
        protected int getDelayAfterAuthenticatedDurationMs() {
            return 0;
        }
    }

    private class MockInjector extends AuthBiometricView.Injector {
        @Override
        public Button getNegativeButton() {
            return mNegativeButton;
        }

        @Override
        public Button getCancelButton() {
            return mCancelButton;
        }

        @Override
        public Button getUseCredentialButton() {
            return mUseCredentialButton;
        }

        @Override
        public Button getConfirmButton() {
            return mConfirmButton;
        }

        @Override
        public Button getTryAgainButton() {
            return mTryAgainButton;
        }

        @Override
        public TextView getTitleView() {
            return mTitleView;
        }

        @Override
        public TextView getSubtitleView() {
            return mSubtitleView;
        }

        @Override
        public TextView getDescriptionView() {
            return mDescriptionView;
        }

        @Override
        public TextView getIndicatorView() {
            return mIndicatorView;
        }

        @Override
        public ImageView getIconView() {
            return mIconView;
        }

        @Override
        public View getIconHolderView() {
            return mIconHolderView;
        }

        @Override
        public int getDelayAfterError() {
            return 0;
        }

        @Override
        public int getMediumToLargeAnimationDurationMs() {
            return 0;
        }
    }
}
