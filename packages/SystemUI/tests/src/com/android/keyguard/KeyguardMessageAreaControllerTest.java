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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricSourceType;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.Editable;
import android.text.TextWatcher;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class KeyguardMessageAreaControllerTest extends SysuiTestCase {
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardMessageArea mKeyguardMessageArea;
    private KeyguardMessageAreaController mMessageAreaController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mMessageAreaController = new KeyguardMessageAreaController.Factory(
                mKeyguardUpdateMonitor, mConfigurationController).create(
                mKeyguardMessageArea);
    }

    @Test
    public void onAttachedToWindow_registersConfigurationCallback() {
        ArgumentCaptor<ConfigurationListener> configurationListenerArgumentCaptor =
                ArgumentCaptor.forClass(ConfigurationListener.class);

        mMessageAreaController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());

        mMessageAreaController.onViewDetached();
        verify(mConfigurationController).removeCallback(
                eq(configurationListenerArgumentCaptor.getValue()));
    }

    @Test
    public void onAttachedToWindow_registersKeyguardUpdateMontiorCallback() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);

        mMessageAreaController.onViewAttached();
        verify(mKeyguardUpdateMonitor).registerCallback(
                keyguardUpdateMonitorCallbackArgumentCaptor.capture());

        mMessageAreaController.onViewDetached();
        verify(mKeyguardUpdateMonitor).removeCallback(
                eq(keyguardUpdateMonitorCallbackArgumentCaptor.getValue()));
    }

    @Test
    public void testClearsTextField() {
        mMessageAreaController.setMessage("");
        verify(mKeyguardMessageArea).setMessage("", /* animate= */ true);
    }

    @Test
    public void textChanged_AnnounceForAccessibility() {
        ArgumentCaptor<TextWatcher> textWatcherArgumentCaptor = ArgumentCaptor.forClass(
                TextWatcher.class);
        mMessageAreaController.onViewAttached();
        verify(mKeyguardMessageArea).addTextChangedListener(textWatcherArgumentCaptor.capture());

        textWatcherArgumentCaptor.getValue().afterTextChanged(
                Editable.Factory.getInstance().newEditable("abc"));
        verify(mKeyguardMessageArea).removeCallbacks(any(Runnable.class));
        verify(mKeyguardMessageArea).postDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void testSetBouncerVisible() {
        mMessageAreaController.setIsVisible(true);
        verify(mKeyguardMessageArea).setIsVisible(true);
    }

    @Test
    public void testGetMessage() {
        String msg = "abc";
        when(mKeyguardMessageArea.getText()).thenReturn(msg);
        assertThat(mMessageAreaController.getMessage()).isEqualTo(msg);
    }

    @Test
    public void testFingerprintMessageUpdate() {
        String msg = "fpMessage";
        mMessageAreaController.setMessage(
                msg, BiometricSourceType.FINGERPRINT
        );
        verify(mKeyguardMessageArea).setMessage(msg, /* animate= */ true);

        String msg2 = "fpMessage2";
        mMessageAreaController.setMessage(
                msg2, BiometricSourceType.FINGERPRINT
        );
        verify(mKeyguardMessageArea).setMessage(msg2, /* animate= */ true);
    }

    @Test
    public void testFaceMessageDroppedWhileFingerprintMessageShowing() {
        String fpMsg = "fpMessage";
        mMessageAreaController.setMessage(
                fpMsg, BiometricSourceType.FINGERPRINT
        );
        verify(mKeyguardMessageArea).setMessage(eq(fpMsg), /* animate= */ anyBoolean());

        String faceMessage = "faceMessage";
        mMessageAreaController.setMessage(
                faceMessage, BiometricSourceType.FACE
        );
        verify(mKeyguardMessageArea, never())
                .setMessage(eq(faceMessage), /* animate= */ anyBoolean());
    }

    @Test
    public void testGenericMessageShowsAfterFingerprintMessageShowing() {
        String fpMsg = "fpMessage";
        mMessageAreaController.setMessage(
                fpMsg, BiometricSourceType.FINGERPRINT
        );
        verify(mKeyguardMessageArea).setMessage(eq(fpMsg), /* animate= */ anyBoolean());

        String genericMessage = "genericMessage";
        mMessageAreaController.setMessage(
                genericMessage, null
        );
        verify(mKeyguardMessageArea)
                .setMessage(eq(genericMessage), /* animate= */ anyBoolean());
    }
}
