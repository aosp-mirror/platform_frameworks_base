/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Intent;
import android.hardware.biometrics.AuthenticateOptions;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.DisplayState;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationReason;
import android.hardware.biometrics.common.OperationState;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.InstanceId;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Map;

@Presubmit
@SmallTest
public class OperationContextExtTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private BiometricContext mBiometricContext;

    @Test
    public void hasAidlContext() {
        OperationContextExt context = new OperationContextExt(false);
        assertThat(context.toAidlContext()).isNotNull();

        final OperationContext aidlContext = newAidlContext();

        context = new OperationContextExt(aidlContext, false, BiometricAuthenticator.TYPE_NONE);
        assertThat(context.toAidlContext()).isSameInstanceAs(aidlContext);

        final int id = 5;
        final byte reason = OperationReason.UNKNOWN;
        final int displayState = DisplayState.NO_UI;
        aidlContext.id = id;
        aidlContext.isAod = true;
        aidlContext.isCrypto = true;
        aidlContext.reason = reason;
        aidlContext.displayState = displayState;

        assertThat(context.getId()).isEqualTo(id);
        assertThat(context.isAod()).isTrue();
        assertThat(context.isCrypto()).isTrue();
        assertThat(context.getReason()).isEqualTo(reason);
        assertThat(context.getDisplayState()).isEqualTo(displayState);
    }

    @Test
    public void hasNoOrderWithoutSession() {
        OperationContextExt context = new OperationContextExt(false);
        assertThat(context.getOrderAndIncrement()).isEqualTo(-1);
        assertThat(context.getOrderAndIncrement()).isEqualTo(-1);
    }

    @Test
    public void mapsDisplayStatesToAidl() {
        final Map<Integer, Integer> map = Map.of(
                AuthenticateOptions.DISPLAY_STATE_UNKNOWN, DisplayState.UNKNOWN,
                AuthenticateOptions.DISPLAY_STATE_AOD, DisplayState.AOD,
                AuthenticateOptions.DISPLAY_STATE_NO_UI, DisplayState.NO_UI,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN, DisplayState.LOCKSCREEN,
                AuthenticateOptions.DISPLAY_STATE_SCREENSAVER, DisplayState.SCREENSAVER,
                100, DisplayState.UNKNOWN
        );

        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            final OperationContextExt context = new OperationContextExt(newAidlContext(), true,
                    BiometricAuthenticator.TYPE_NONE);
            when(mBiometricContext.getDisplayState()).thenReturn(entry.getKey());
            assertThat(context.update(mBiometricContext, context.isCrypto()).getDisplayState())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    public void updatesFromSourceForKeyguard() {
        final BiometricContextSessionInfo info =
                new BiometricContextSessionInfo(InstanceId.fakeInstanceId(9));
        when(mBiometricContext.getKeyguardEntrySessionInfo()).thenReturn(info);
        updatesFromSource(info, OperationReason.KEYGUARD);
    }

    @Test
    public void updatesFromSourceForBiometricPrompt() {
        final BiometricContextSessionInfo info =
                new BiometricContextSessionInfo(InstanceId.fakeInstanceId(9));
        when(mBiometricContext.getBiometricPromptSessionInfo()).thenReturn(info);
        updatesFromSource(info, OperationReason.BIOMETRIC_PROMPT);
    }

    @Test
    public void updatesFromSourceWithoutSession() {
        updatesFromSource(null, OperationReason.UNKNOWN);
    }

    private void updatesFromSource(BiometricContextSessionInfo sessionInfo, int sessionType) {
        final int rotation = Surface.ROTATION_270;
        final int foldState = IBiometricContextListener.FoldState.HALF_OPENED;
        final int dockState = Intent.EXTRA_DOCK_STATE_CAR;
        final int displayState = AuthenticateOptions.DISPLAY_STATE_AOD;

        when(mBiometricContext.getCurrentRotation()).thenReturn(rotation);
        when(mBiometricContext.getFoldState()).thenReturn(foldState);
        when(mBiometricContext.getDockedState()).thenReturn(dockState);
        when(mBiometricContext.isDisplayOn()).thenReturn(true);
        when(mBiometricContext.getDisplayState()).thenReturn(displayState);
        when(mBiometricContext.isHardwareIgnoringTouches()).thenReturn(true);

        final OperationContextExt context = new OperationContextExt(newAidlContext(),
                sessionType == OperationReason.BIOMETRIC_PROMPT,
                BiometricAuthenticator.TYPE_FINGERPRINT);

        assertThat(context.update(mBiometricContext, context.isCrypto())).isSameInstanceAs(context);

        if (sessionInfo != null) {
            assertThat(context.getId()).isEqualTo(sessionInfo.getId());
            final int order = context.getOrderAndIncrement();
            assertThat(context.getOrderAndIncrement()).isEqualTo(order + 1);
        } else {
            assertThat(context.getId()).isEqualTo(0);
        }
        assertThat(context.getReason()).isEqualTo(sessionType);
        assertThat(context.getDockState()).isEqualTo(dockState);
        assertThat(context.getFoldState()).isEqualTo(foldState);
        assertThat(context.getOrientation()).isEqualTo(rotation);
        assertThat(context.isDisplayOn()).isTrue();
        assertThat(context.getDisplayState()).isEqualTo(DisplayState.AOD);
        assertThat(
            context.getOperationState().getFingerprintOperationState().isHardwareIgnoringTouches
        ).isTrue();
    }

    @Test
    public void hasNullOperationState() {
        OperationContextExt context = new OperationContextExt(false);
        assertThat(context.toAidlContext()).isNotNull();

        final OperationContext aidlContext = newAidlContext();

        context = new OperationContextExt(aidlContext, false, BiometricAuthenticator.TYPE_NONE);
        assertThat(context.getOperationState()).isNull();
    }

    @Test
    public void hasFaceOperationState() {
        OperationContextExt context = new OperationContextExt(false);
        assertThat(context.toAidlContext()).isNotNull();

        final OperationContext aidlContext = newAidlContext();

        context = new OperationContextExt(aidlContext, false,
                BiometricAuthenticator.TYPE_FACE);
        assertThat(context.getOperationState().getTag()).isEqualTo(
                OperationState.faceOperationState);
    }

    @Test
    public void hasFingerprintOperationState() {
        OperationContextExt context = new OperationContextExt(false);
        assertThat(context.toAidlContext()).isNotNull();

        final OperationContext aidlContext = newAidlContext();

        context = new OperationContextExt(aidlContext, false,
                BiometricAuthenticator.TYPE_FINGERPRINT);
        assertThat(context.getOperationState().getTag()).isEqualTo(
                OperationState.fingerprintOperationState);
    }

    private static OperationContext newAidlContext() {
        final OperationContext aidlContext = new OperationContext();
        aidlContext.id = -1;
        aidlContext.isAod = false;
        aidlContext.isCrypto = false;
        aidlContext.reason = 0;
        return aidlContext;
    }
}
