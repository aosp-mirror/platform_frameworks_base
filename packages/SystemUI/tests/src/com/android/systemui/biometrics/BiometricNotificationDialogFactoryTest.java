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

package com.android.systemui.biometrics;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.ExecutionException;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BiometricNotificationDialogFactoryTest extends SysuiTestCase {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    FingerprintManager mFingerprintManager;
    @Mock
    FaceManager mFaceManager;
    @Mock
    SystemUIDialog mDialog;

    private Context mContextSpy;
    private final ArgumentCaptor<DialogInterface.OnClickListener> mOnClickListenerArgumentCaptor =
            ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
    private final ArgumentCaptor<Intent> mIntentArgumentCaptor =
            ArgumentCaptor.forClass(Intent.class);
    private BiometricNotificationDialogFactory mDialogFactory;

    @Before
    public void setUp() throws ExecutionException, InterruptedException {
        mContext.addMockSystemService(FingerprintManager.class, mFingerprintManager);
        mContext.addMockSystemService(FaceManager.class, mFaceManager);

        when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(true);

        mContextSpy = spy(mContext);
        mDialogFactory = new BiometricNotificationDialogFactory();
    }

    @Test
    public void testFingerprintReEnrollDialog_onRemovalSucceeded() {
        mDialogFactory.createReenrollDialog(mContextSpy, mDialog,
                BiometricSourceType.FINGERPRINT);

        verify(mDialog).setPositiveButton(anyInt(), mOnClickListenerArgumentCaptor.capture());

        DialogInterface.OnClickListener positiveOnClickListener =
                mOnClickListenerArgumentCaptor.getValue();
        positiveOnClickListener.onClick(null, DialogInterface.BUTTON_POSITIVE);
        ArgumentCaptor<FingerprintManager.RemovalCallback> removalCallbackArgumentCaptor =
                ArgumentCaptor.forClass(FingerprintManager.RemovalCallback.class);

        verify(mFingerprintManager).removeAll(anyInt(), removalCallbackArgumentCaptor.capture());

        removalCallbackArgumentCaptor.getValue().onRemovalSucceeded(null /* fp */,
                0 /* remaining */);

        verify(mContextSpy).startActivity(mIntentArgumentCaptor.capture());
        assertThat(mIntentArgumentCaptor.getValue().getAction()).isEqualTo(
                Settings.ACTION_FINGERPRINT_ENROLL);
    }

    @Test
    public void testFingerprintReEnrollDialog_onRemovalError() {
        mDialogFactory.createReenrollDialog(mContextSpy, mDialog,
                BiometricSourceType.FINGERPRINT);

        verify(mDialog).setPositiveButton(anyInt(), mOnClickListenerArgumentCaptor.capture());

        DialogInterface.OnClickListener positiveOnClickListener =
                mOnClickListenerArgumentCaptor.getValue();
        positiveOnClickListener.onClick(null, DialogInterface.BUTTON_POSITIVE);
        ArgumentCaptor<FingerprintManager.RemovalCallback> removalCallbackArgumentCaptor =
                ArgumentCaptor.forClass(FingerprintManager.RemovalCallback.class);

        verify(mFingerprintManager).removeAll(anyInt(), removalCallbackArgumentCaptor.capture());

        removalCallbackArgumentCaptor.getValue().onRemovalError(null /* fp */,
                0 /* errmsgId */, "Error" /* errString */);

        verify(mContextSpy, never()).startActivity(any());
    }

    @Test
    public void testFaceReEnrollDialog_onRemovalSucceeded() {
        mDialogFactory.createReenrollDialog(mContextSpy, mDialog,
                BiometricSourceType.FACE);

        verify(mDialog).setPositiveButton(anyInt(), mOnClickListenerArgumentCaptor.capture());

        DialogInterface.OnClickListener positiveOnClickListener =
                mOnClickListenerArgumentCaptor.getValue();
        positiveOnClickListener.onClick(null, DialogInterface.BUTTON_POSITIVE);
        ArgumentCaptor<FaceManager.RemovalCallback> removalCallbackArgumentCaptor =
                ArgumentCaptor.forClass(FaceManager.RemovalCallback.class);

        verify(mFaceManager).removeAll(anyInt(), removalCallbackArgumentCaptor.capture());

        removalCallbackArgumentCaptor.getValue().onRemovalSucceeded(null /* fp */,
                0 /* remaining */);

        verify(mContextSpy).startActivity(mIntentArgumentCaptor.capture());
        assertThat(mIntentArgumentCaptor.getValue().getAction()).isEqualTo(
                "android.settings.FACE_ENROLL");
    }

    @Test
    public void testFaceReEnrollDialog_onRemovalError() {
        mDialogFactory.createReenrollDialog(mContextSpy, mDialog,
                BiometricSourceType.FACE);

        verify(mDialog).setPositiveButton(anyInt(), mOnClickListenerArgumentCaptor.capture());

        DialogInterface.OnClickListener positiveOnClickListener =
                mOnClickListenerArgumentCaptor.getValue();
        positiveOnClickListener.onClick(null, DialogInterface.BUTTON_POSITIVE);
        ArgumentCaptor<FaceManager.RemovalCallback> removalCallbackArgumentCaptor =
                ArgumentCaptor.forClass(FaceManager.RemovalCallback.class);

        verify(mFaceManager).removeAll(anyInt(), removalCallbackArgumentCaptor.capture());

        removalCallbackArgumentCaptor.getValue().onRemovalError(null /* face */,
                0 /* errmsgId */, "Error" /* errString */);

        verify(mContextSpy, never()).startActivity(any());
    }
}
