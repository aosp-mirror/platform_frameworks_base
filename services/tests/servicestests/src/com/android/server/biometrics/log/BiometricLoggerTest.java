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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.input.InputSensorInfo;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.biometrics.AuthenticationStatsCollector;
import com.android.server.biometrics.sensors.BaseClientMonitor;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class BiometricLoggerTest {

    private static final int DEFAULT_MODALITY = BiometricsProtoEnums.MODALITY_FINGERPRINT;
    private static final int DEFAULT_ACTION = BiometricsProtoEnums.ACTION_AUTHENTICATE;
    private static final int DEFAULT_CLIENT = BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Rule
    public TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());
    @Mock
    private BiometricFrameworkStatsLogger mSink;
    @Mock
    private AuthenticationStatsCollector mAuthenticationStatsCollector;
    @Mock
    private SensorManager mSensorManager;
    @Mock
    private BaseClientMonitor mClient;

    private OperationContextExt mOpContext;
    private BiometricLogger mLogger;

    @Before
    public void setUp() {
        mOpContext = new OperationContextExt(false);
        mContext.addMockSystemService(SensorManager.class, mSensorManager);
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(
                new Sensor(new InputSensorInfo("", "", 0, 0, Sensor.TYPE_LIGHT, 0, 0, 0, 0, 0, 0,
                        "", "", 0, 0, 0))
        );
    }

    private BiometricLogger createLogger() {
        return createLogger(DEFAULT_MODALITY, DEFAULT_ACTION, DEFAULT_CLIENT);
    }

    private BiometricLogger createLogger(int statsModality, int statsAction, int statsClient) {
        return new BiometricLogger(statsModality, statsAction, statsClient, mSink,
                mAuthenticationStatsCollector, mSensorManager);
    }

    @Test
    public void testAcquired() {
        mLogger = createLogger();

        final int acquiredInfo = 2;
        final int vendorCode = 3;
        final int targetUserId = 9;

        mLogger.logOnAcquired(mContext, mOpContext, acquiredInfo, vendorCode, targetUserId);

        verify(mSink).acquired(eq(mOpContext),
                eq(DEFAULT_MODALITY), eq(DEFAULT_ACTION), eq(DEFAULT_CLIENT), anyBoolean(),
                eq(acquiredInfo), eq(vendorCode), eq(targetUserId));
    }

    @Test
    public void testAuth() {
        mLogger = createLogger();

        final boolean authenticated = true;
        final boolean requireConfirmation = false;
        final int targetUserId = 11;
        final boolean isBiometricPrompt = true;

        mLogger.logOnAuthenticated(mContext, mOpContext,
                authenticated, requireConfirmation, targetUserId, isBiometricPrompt);

        verify(mSink).authenticate(eq(mOpContext),
                eq(DEFAULT_MODALITY), eq(DEFAULT_ACTION), eq(DEFAULT_CLIENT), anyBoolean(),
                anyLong(), anyInt(), eq(requireConfirmation),
                eq(targetUserId), any());

        verify(mAuthenticationStatsCollector).authenticate(eq(targetUserId), eq(authenticated));
    }

    @Test
    public void testEnroll() {
        mLogger = createLogger();

        final int targetUserId = 4;
        final long latency = 44;
        final boolean enrollSuccessful = true;

        mLogger.logOnEnrolled(targetUserId, latency, enrollSuccessful, -1);

        verify(mSink).enroll(
                eq(DEFAULT_MODALITY), eq(DEFAULT_ACTION), eq(DEFAULT_CLIENT),
                eq(targetUserId), eq(latency), eq(enrollSuccessful), anyFloat(), anyInt());
    }

    @Test
    public void testError() {
        mLogger = createLogger();

        final int error = 7;
        final int vendorCode = 11;
        final int targetUserId = 9;

        mLogger.logOnError(mContext, mOpContext, error, vendorCode, targetUserId);

        verify(mSink).error(eq(mOpContext),
                eq(DEFAULT_MODALITY), eq(DEFAULT_ACTION), eq(DEFAULT_CLIENT), anyBoolean(),
                anyLong(), eq(error), eq(vendorCode), eq(targetUserId));
    }

    @Test
    public void testBadModalityActsDisabled() {
        mLogger = createLogger(
                BiometricsProtoEnums.MODALITY_UNKNOWN, DEFAULT_ACTION, DEFAULT_CLIENT);
        testDisabledMetrics(true /* isBadConfig */);
    }

    @Test
    public void testBadActionActsDisabled() {
        mLogger = createLogger(
                DEFAULT_MODALITY, BiometricsProtoEnums.ACTION_UNKNOWN, DEFAULT_CLIENT);
        testDisabledMetrics(true /* isBadConfig */);
    }

    @Test
    public void testDisableLogger() {
        mLogger = createLogger();
        testDisabledMetrics(false /* isBadConfig */);
    }

    private void testDisabledMetrics(boolean isBadConfig) {
        mLogger.disableMetrics();
        mLogger.logOnAcquired(mContext, mOpContext,
                0 /* acquiredInfo */,
                1 /* vendorCode */,
                8 /* targetUserId */);
        mLogger.logOnAuthenticated(mContext, mOpContext,
                true /* authenticated */,
                true /* requireConfirmation */,
                4 /* targetUserId */,
                true/* isBiometricPrompt */);
        mLogger.logOnEnrolled(2 /* targetUserId */,
                10 /* latency */,
                true /* enrollSuccessful */,
                30 /* source */);
        mLogger.logOnError(mContext, mOpContext,
                4 /* error */,
                0 /* vendorCode */,
                6 /* targetUserId */);

        verify(mSink, never()).acquired(eq(mOpContext),
                anyInt(), anyInt(), anyInt(), anyBoolean(),
                anyInt(), anyInt(), anyInt());
        verify(mSink, never()).authenticate(eq(mOpContext),
                anyInt(), anyInt(), anyInt(), anyBoolean(),
                anyLong(), anyInt(), anyBoolean(), anyInt(), anyFloat());
        verify(mSink, never()).enroll(
                anyInt(), anyInt(), anyInt(), anyInt(), anyLong(), anyBoolean(), anyFloat(),
                anyInt());
        verify(mSink, never()).error(eq(mOpContext),
                anyInt(), anyInt(), anyInt(), anyBoolean(),
                anyLong(), anyInt(), anyInt(), anyInt());

        mLogger.logUnknownEnrollmentInFramework();
        mLogger.logUnknownEnrollmentInHal();

        verify(mSink, times(isBadConfig ? 0 : 1))
                .reportUnknownTemplateEnrolledHal(eq(DEFAULT_MODALITY));
        verify(mSink, times(isBadConfig ? 0 : 1))
                .reportUnknownTemplateEnrolledFramework(eq(DEFAULT_MODALITY));
    }

    @Test
    public void systemHealthBadHalTemplate() {
        mLogger = createLogger();
        mLogger.logUnknownEnrollmentInHal();
        verify(mSink).reportUnknownTemplateEnrolledHal(eq(DEFAULT_MODALITY));
    }

    @Test
    public void systemHealthBadFrameworkTemplate() {
        mLogger = createLogger();
        mLogger.logUnknownEnrollmentInFramework();
        verify(mSink).reportUnknownTemplateEnrolledFramework(eq(DEFAULT_MODALITY));
    }

    @Test
    public void testFingerprintsLoe() {
        mLogger = createLogger();
        mLogger.logFingerprintsLoe();
        verify(mSink).reportFingerprintsLoe(eq(DEFAULT_MODALITY));
    }

    @Test
    public void testALSCallback() {
        mLogger = createLogger();
        final CallbackWithProbe<Probe> callback =
                mLogger.getAmbientLightProbe(true /* startWithClient */);

        callback.onClientStarted(mClient);
        verify(mSensorManager).registerListener(any(), any(), anyInt());

        callback.onClientFinished(mClient, true /* success */);
        verify(mSensorManager).unregisterListener(any(SensorEventListener.class));
    }

    @Test
    public void testALSCallbackWhenLogsDisabled() {
        mLogger = createLogger();
        mLogger.disableMetrics();
        final CallbackWithProbe<Probe> callback =
                mLogger.getAmbientLightProbe(true /* startWithClient */);

        callback.onClientStarted(mClient);
        verify(mSensorManager, never()).registerListener(any(), any(), anyInt());

        callback.onClientFinished(mClient, true /* success */);
        verify(mSensorManager, never()).unregisterListener(any(SensorEventListener.class));
    }

    @Test
    public void testALSCallbackWhenDisabledAfterStarting() {
        mLogger = createLogger();
        final CallbackWithProbe<Probe> callback =
                mLogger.getAmbientLightProbe(true /* startWithClient */);

        callback.onClientStarted(mClient);
        verify(mSensorManager).registerListener(any(), any(), anyInt());

        mLogger.disableMetrics();
        verify(mSensorManager).unregisterListener(any(SensorEventListener.class));
    }

    @Test
    public void testALSCallbackDoesNotStart() {
        mLogger = createLogger();
        final CallbackWithProbe<Probe> callback =
                mLogger.getAmbientLightProbe(false /* startWithClient */);

        callback.onClientStarted(mClient);
        callback.onClientFinished(mClient, true /* success */);
        verify(mSensorManager, never()).registerListener(any(), any(), anyInt());
    }

    @Test
    public void testALSCallbackDestroyed() {
        mLogger = createLogger();
        final CallbackWithProbe<Probe> callback =
                mLogger.getAmbientLightProbe(true /* startWithClient */);

        callback.onClientStarted(mClient);
        callback.onClientFinished(mClient, false /* success */);

        reset(mSensorManager);
        callback.getProbe().enable();
        verify(mSensorManager, never()).registerListener(any(), any(), anyInt());
    }
}
