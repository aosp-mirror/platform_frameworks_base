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

package com.android.server.biometrics.log;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.AuthenticateReason;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.WakeReason;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@Presubmit
@SmallTest
public class BiometricFrameworkStatsLoggerTest {

    @Test
    public void testConvertsWakeReason_whenEmpty() {
        final OperationContextExt ctx = new OperationContextExt(false);

        final int reason = BiometricFrameworkStatsLogger.toProtoWakeReason(ctx);
        final int[] reasonDetails = BiometricFrameworkStatsLogger
                .toProtoWakeReasonDetails(ctx);

        assertThat(reason).isEqualTo(BiometricsProtoEnums.WAKE_REASON_UNKNOWN);
        assertThat(reasonDetails).isEmpty();
    }

    @Test
    public void testConvertsWakeReason_whenPowerReason() {
        final OperationContext context = new OperationContext();
        context.wakeReason = WakeReason.WAKE_MOTION;
        final OperationContextExt ctx = new OperationContextExt(context, false,
                BiometricAuthenticator.TYPE_NONE);

        final int reason = BiometricFrameworkStatsLogger.toProtoWakeReason(ctx);
        final int[] reasonDetails = BiometricFrameworkStatsLogger
                .toProtoWakeReasonDetails(
                        new OperationContextExt(context, false, BiometricAuthenticator.TYPE_NONE));

        assertThat(reason).isEqualTo(BiometricsProtoEnums.WAKE_REASON_WAKE_MOTION);
        assertThat(reasonDetails).isEmpty();
    }

    @Test
    public void testConvertsWakeReason_whenFaceReason() {
        final OperationContext context = new OperationContext();
        context.authenticateReason = AuthenticateReason.faceAuthenticateReason(
                AuthenticateReason.Face.ASSISTANT_VISIBLE);
        final OperationContextExt ctx = new OperationContextExt(context, false,
                BiometricAuthenticator.TYPE_NONE);

        final int reason = BiometricFrameworkStatsLogger.toProtoWakeReason(ctx);
        final int[] reasonDetails = BiometricFrameworkStatsLogger
                .toProtoWakeReasonDetails(ctx);

        assertThat(reason).isEqualTo(BiometricsProtoEnums.WAKE_REASON_UNKNOWN);
        assertThat(reasonDetails).asList().containsExactly(
                BiometricsProtoEnums.DETAILS_FACE_ASSISTANT_VISIBLE);
    }

    @Test
    public void testConvertsWakeReason_whenVendorReason() {
        final OperationContext context = new OperationContext();
        context.authenticateReason = AuthenticateReason.vendorAuthenticateReason(
                new AuthenticateReason.Vendor());
        final OperationContextExt ctx = new OperationContextExt(context, false,
                BiometricAuthenticator.TYPE_NONE);

        final int reason = BiometricFrameworkStatsLogger.toProtoWakeReason(ctx);
        final int[] reasonDetails = BiometricFrameworkStatsLogger
                .toProtoWakeReasonDetails(ctx);

        assertThat(reason).isEqualTo(BiometricsProtoEnums.WAKE_REASON_UNKNOWN);
        assertThat(reasonDetails).isEmpty();
    }


    @Test
    public void testConvertsWakeReason_whenPowerAndFaceReason() {
        final OperationContext context = new OperationContext();
        context.wakeReason = WakeReason.WAKE_KEY;
        context.authenticateReason = AuthenticateReason.faceAuthenticateReason(
                AuthenticateReason.Face.PRIMARY_BOUNCER_SHOWN);
        final OperationContextExt ctx = new OperationContextExt(context, false,
                BiometricAuthenticator.TYPE_NONE);

        final int reason = BiometricFrameworkStatsLogger.toProtoWakeReason(ctx);
        final int[] reasonDetails = BiometricFrameworkStatsLogger
                .toProtoWakeReasonDetails(ctx);

        assertThat(reason).isEqualTo(BiometricsProtoEnums.WAKE_REASON_WAKE_KEY);
        assertThat(reasonDetails).asList().containsExactly(
                BiometricsProtoEnums.DETAILS_FACE_PRIMARY_BOUNCER_SHOWN);
    }

    @Test
    public void testConvertsWakeReason_whenPowerAndVendorReason() {
        final OperationContext context = new OperationContext();
        context.wakeReason = WakeReason.LID;
        context.authenticateReason = AuthenticateReason.vendorAuthenticateReason(
                new AuthenticateReason.Vendor());
        final OperationContextExt ctx = new OperationContextExt(context, false,
                BiometricAuthenticator.TYPE_NONE);

        final int reason = BiometricFrameworkStatsLogger.toProtoWakeReason(ctx);
        final int[] reasonDetails = BiometricFrameworkStatsLogger
                .toProtoWakeReasonDetails(ctx);

        assertThat(reason).isEqualTo(BiometricsProtoEnums.WAKE_REASON_LID);
        assertThat(reasonDetails).isEmpty();
    }
}
