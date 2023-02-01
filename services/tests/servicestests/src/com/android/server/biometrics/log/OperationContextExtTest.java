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

import android.content.Intent;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationReason;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;

import static org.mockito.Mockito.when;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.InstanceId;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class OperationContextExtTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private BiometricContext mBiometricContext;

    @Test
    public void hasAidlContext() {
        OperationContextExt context = new OperationContextExt();
        assertThat(context.toAidlContext()).isNotNull();

        final OperationContext aidlContext = newAidlContext();

        context = new OperationContextExt(aidlContext);
        assertThat(context.toAidlContext()).isSameInstanceAs(aidlContext);

        final int id = 5;
        final byte reason = OperationReason.UNKNOWN;
        aidlContext.id = id;
        aidlContext.isAod = true;
        aidlContext.isCrypto = true;
        aidlContext.reason = reason;

        assertThat(context.getId()).isEqualTo(id);
        assertThat(context.isAod()).isTrue();
        assertThat(context.isCrypto()).isTrue();
        assertThat(context.getReason()).isEqualTo(reason);
    }

    @Test
    public void hasNoOrderWithoutSession() {
        OperationContextExt context = new OperationContextExt();
        assertThat(context.getOrderAndIncrement()).isEqualTo(-1);
        assertThat(context.getOrderAndIncrement()).isEqualTo(-1);
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

    private  void updatesFromSource(BiometricContextSessionInfo sessionInfo, int sessionType) {
        final int rotation = Surface.ROTATION_270;
        final int foldState = IBiometricContextListener.FoldState.HALF_OPENED;
        final int dockState = Intent.EXTRA_DOCK_STATE_CAR;

        when(mBiometricContext.getCurrentRotation()).thenReturn(rotation);
        when(mBiometricContext.getFoldState()).thenReturn(foldState);
        when(mBiometricContext.getDockedState()).thenReturn(dockState);
        when(mBiometricContext.isDisplayOn()).thenReturn(true);

        final OperationContextExt context = new OperationContextExt(newAidlContext());

        assertThat(context.update(mBiometricContext)).isSameInstanceAs(context);

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
