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

package com.android.server.biometrics.sensors;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class LockoutResetDispatcherTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);

    @Mock
    private IBinder mBinder;
    @Mock
    private IBiometricServiceLockoutResetCallback mCallback;

    private LockoutResetDispatcher mDispatcher;

    @Before
    public void setup() {
        when(mCallback.asBinder()).thenReturn(mBinder);
        mDispatcher = new LockoutResetDispatcher(mContext);
    }

    @Test
    public void linksToDeath() throws Exception {
        mDispatcher.addCallback(mCallback, "package");
        verify(mBinder).linkToDeath(eq(mDispatcher), anyInt());
    }

    @Test
    public void notifyLockoutReset() throws Exception {
        final int sensorId = 24;

        mDispatcher.addCallback(mCallback, "some.package");
        mDispatcher.notifyLockoutResetCallbacks(sensorId);

        final ArgumentCaptor<IRemoteCallback> captor =
                ArgumentCaptor.forClass(IRemoteCallback.class);
        verify(mCallback).onLockoutReset(eq(sensorId), captor.capture());
        captor.getValue().sendResult(new Bundle());
    }

    @Test
    public void releaseWakeLockOnDeath() {
        mDispatcher.addCallback(mCallback, "a.b.cee");
        mDispatcher.binderDied(mBinder);

        // would be better to check the wake lock
        // but this project lacks the extended mockito support to do it
        assertThat(mDispatcher.mClientCallbacks).isEmpty();
    }

    @Test
    public void releaseCorrectWakeLockOnDeath() {
        mDispatcher.addCallback(mCallback, "a.b");
        mDispatcher.binderDied(mock(IBinder.class));

        assertThat(mDispatcher.mClientCallbacks).hasSize(1);
    }
}
