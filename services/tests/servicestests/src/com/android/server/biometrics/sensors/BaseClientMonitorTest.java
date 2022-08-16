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

package com.android.server.biometrics.sensors;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
public class BaseClientMonitorTest {

    @Mock
    private Context mContext;
    @Mock
    private IBinder mToken;
    private @Mock ClientMonitorCallbackConverter mListener;
    @Mock
    private BiometricLogger mLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private ClientMonitorCallback mCallback;

    private TestClientMonitor mClientMonitor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mClientMonitor = new TestClientMonitor();
    }

    @Test
    public void preparesForDeath() throws RemoteException {
        verify(mToken).linkToDeath(eq(mClientMonitor), anyInt());

        mClientMonitor.binderDied();

        assertThat(mClientMonitor.mCanceled).isTrue();
        assertThat(mClientMonitor.getListener()).isNull();
    }

    @Test
    public void ignoresDeathWhenDone() {
        mClientMonitor.markAlreadyDone();
        mClientMonitor.binderDied();

        assertThat(mClientMonitor.mCanceled).isFalse();
    }

    @Test
    public void start() {
        mClientMonitor.start(mCallback);

        verify(mCallback).onClientStarted(eq(mClientMonitor));
    }

    @Test
    public void destroy() {
        mClientMonitor.destroy();
        mClientMonitor.destroy();

        assertThat(mClientMonitor.isAlreadyDone()).isTrue();
        verify(mToken).unlinkToDeath(eq(mClientMonitor), anyInt());
    }

    @Test
    public void hasRequestId() {
        assertThat(mClientMonitor.hasRequestId()).isFalse();

        final int id = 200;
        mClientMonitor.setRequestId(id);
        assertThat(mClientMonitor.hasRequestId()).isTrue();
        assertThat(mClientMonitor.getRequestId()).isEqualTo(id);
    }

    private class TestClientMonitor extends BaseClientMonitor implements Interruptable {
        public boolean mCanceled = false;

        TestClientMonitor() {
            super(mContext, mToken, mListener, 9 /* userId */, "foo" /* owner */, 2 /* cookie */,
                    5 /* sensorId */, mLogger, mBiometricContext);
        }

        @Override
        public int getProtoEnum() {
            return 0;
        }

        @Override
        public void cancel() {
            mCanceled = true;
        }

        @Override
        public void cancelWithoutStarting(@NonNull ClientMonitorCallback callback) {
            mCanceled = true;
        }
    }
}
