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

package com.android.server.biometrics.sensors.face.aidl;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.Face;
import android.hardware.face.FaceEnrollOptions;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.biometrics.Flags;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.function.Consumer;

@Presubmit
@SmallTest
public class FaceEnrollClientTest {

    private static final byte[] HAT = new byte[69];
    private static final int USER_ID = 12;
    private static final int ENROLL_SOURCE = FaceEnrollOptions.ENROLL_REASON_SUW;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock
    private ISession mHal;
    @Mock
    private IBinder mToken;
    @Mock
    private ClientMonitorCallbackConverter mClientMonitorCallbackConverter;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private BiometricUtils<Face> mUtils;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    private AidlResponseHandler mAidlResponseHandler;
    @Captor
    private ArgumentCaptor<OperationContextExt> mOperationContextCaptor;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mContextInjector;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mStartHalConsumer;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setup() {
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
    }

    @Test
    public void enrollNoContext_v1() throws RemoteException {
        final FaceEnrollClient client = createClient(1);
        client.start(mCallback);

        verify(mHal).enroll(any(), anyByte(), any(), any());
        verify(mHal, never()).enrollWithContext(any(), anyByte(), any(), any(), any());
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DE_HIDL)
    public void enrollWithContext_v2() throws RemoteException {
        final FaceEnrollClient client = createClient(2);
        client.start(mCallback);

        InOrder order = inOrder(mHal, mBiometricContext);
        order.verify(mBiometricContext).updateContext(
                mOperationContextCaptor.capture(), anyBoolean());

        final OperationContext aidlContext = mOperationContextCaptor.getValue().toAidlContext();
        order.verify(mHal).enrollWithContext(any(), anyByte(), any(), any(), same(aidlContext));
        verify(mHal, never()).enroll(any(), anyByte(), any(), any());
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DE_HIDL)
    public void notifyHalWhenContextChanges() throws RemoteException {
        final FaceEnrollClient client = createClient(3);
        client.start(mCallback);

        final ArgumentCaptor<OperationContext> captor =
                ArgumentCaptor.forClass(OperationContext.class);
        verify(mHal).enrollWithContext(any(), anyByte(), any(), any(), captor.capture());
        OperationContext opContext = captor.getValue();

        // fake an update to the context
        verify(mBiometricContext).subscribe(
                mOperationContextCaptor.capture(), mContextInjector.capture());
        assertThat(opContext).isSameInstanceAs(
                mOperationContextCaptor.getValue().toAidlContext());
        mContextInjector.getValue().accept(opContext);
        verify(mHal).onContextChanged(same(opContext));

        client.stopHalOperation();
        verify(mBiometricContext).unsubscribe(same(mOperationContextCaptor.getValue()));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void subscribeContextAndStartHal() throws RemoteException {
        final FaceEnrollClient client = createClient(3);
        client.start(mCallback);

        verify(mBiometricContext).subscribe(
                mOperationContextCaptor.capture(), mStartHalConsumer.capture(),
                mContextInjector.capture(), any());

        mStartHalConsumer.getValue().accept(mOperationContextCaptor.getValue().toAidlContext());
        final ArgumentCaptor<OperationContext> captor =
                ArgumentCaptor.forClass(OperationContext.class);

        verify(mHal).enrollWithContext(any(), anyByte(), any(), any(), captor.capture());

        OperationContext opContext = captor.getValue();

        assertThat(opContext).isSameInstanceAs(
                mOperationContextCaptor.getValue().toAidlContext());

        mContextInjector.getValue().accept(opContext);

        verify(mHal).onContextChanged(same(opContext));

        client.stopHalOperation();

        verify(mBiometricContext).unsubscribe(same(mOperationContextCaptor.getValue()));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DE_HIDL)
    public void enrollWithFaceOptions() throws RemoteException {
        final FaceEnrollClient client = createClient(4);
        client.start(mCallback);

        verify(mHal).enrollWithOptions(any());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void enrollWithFaceOptionsAfterSubscribingContext() throws RemoteException {
        final FaceEnrollClient client = createClient(4);
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumer.capture(), any(), any());

        mStartHalConsumer.getValue().accept(mOperationContextCaptor.getValue().toAidlContext());

        verify(mHal).enrollWithOptions(any());
    }

    @Test
    public void testEnrollWithReasonLogsMetric() throws RemoteException {
        final FaceEnrollClient client = createClient(4);
        client.start(mCallback);
        client.onEnrollResult(new Face("face", 1 /* faceId */, 20 /* deviceId */), 0);

        verify(mBiometricLogger).logOnEnrolled(anyInt(), anyLong(), anyBoolean(),
                eq(BiometricsProtoEnums.ENROLLMENT_SOURCE_SUW));
    }

    private FaceEnrollClient createClient() throws RemoteException {
        return createClient(200 /* version */);
    }

    private FaceEnrollClient createClient(int version) throws RemoteException {
        when(mHal.getInterfaceVersion()).thenReturn(version);

        final AidlSession aidl = new AidlSession(version, mHal, USER_ID, mAidlResponseHandler);
        return new FaceEnrollClient(mContext, () -> aidl, mToken, mClientMonitorCallbackConverter,
                USER_ID, HAT, "com.foo.bar", 44 /* requestId */,
                mUtils, new int[0] /* disabledFeatures */, 6 /* timeoutSec */,
                null /* previewSurface */, 8 /* sensorId */,
                mBiometricLogger, mBiometricContext, 5 /* maxTemplatesPerUser */,
                true /* debugConsent */,
                (new FaceEnrollOptions.Builder()).setEnrollReason(ENROLL_SOURCE).build());
    }
}
