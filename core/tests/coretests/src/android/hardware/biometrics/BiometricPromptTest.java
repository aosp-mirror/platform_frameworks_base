/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.biometrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.Executor;


@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class BiometricPromptTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private Context mContext;
    @Mock
    private IAuthService mService;
    private BiometricPrompt mBiometricPrompt;

    private CancellationSignal mCancellationSignal;

    private final TestLooper mLooper = new TestLooper();
    private final Handler mHandler = new Handler(mLooper.getLooper());
    private final Executor mExecutor = mHandler::post;

    @Before
    public void setUp() throws RemoteException {
        mBiometricPrompt = new BiometricPrompt.Builder(mContext)
                .setUseDefaultSubtitle()
                .setUseDefaultTitle()
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .setService(mService)
                .build();

        mCancellationSignal = new CancellationSignal();
        when(mService.authenticate(any(), anyLong(), anyInt(), any(), anyString(), any()))
                .thenReturn(0L);
        when(mContext.getPackageName()).thenReturn("BiometricPromptTest");
    }

    @Test
    public void testCancellationAfterAuthenticationFailed() throws RemoteException {
        ArgumentCaptor<IBiometricServiceReceiver> biometricServiceReceiverCaptor =
                ArgumentCaptor.forClass(IBiometricServiceReceiver.class);
        BiometricPrompt.AuthenticationCallback callback =
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
            }};
        mBiometricPrompt.authenticate(mCancellationSignal, mExecutor, callback);
        mLooper.dispatchAll();

        verify(mService).authenticate(any(), anyLong(), anyInt(),
                biometricServiceReceiverCaptor.capture(), anyString(), any());

        biometricServiceReceiverCaptor.getValue().onAuthenticationFailed();
        mLooper.dispatchAll();
        mCancellationSignal.cancel();

        verify(mService).cancelAuthentication(any(), anyString(), anyLong());
    }
}
