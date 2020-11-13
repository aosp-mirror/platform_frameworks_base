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

package com.android.server.rotationresolver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.rotationresolver.RotationResolverInternal;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.rotationresolver.RotationResolverManagerPerUserServiceTest}
 */
@SmallTest
public class RotationResolverManagerPerUserServiceTest {

    @Mock
    Context mContext;

    @Mock
    RotationResolverInternal.RotationResolverCallbackInternal mMockCallbackInternal;

    @Mock
    ComponentName mMockComponentName;

    private CancellationSignal mCancellationSignal;

    private RotationResolverManagerPerUserService mSpyService;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        // setup context mock
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());

        // setup a spy for the RotationResolverManagerPerUserService.
        final RotationResolverManagerService mainService = new RotationResolverManagerService(
                mContext);
        final RotationResolverManagerPerUserService mService =
                new RotationResolverManagerPerUserService(mainService, /* Lock */ new Object(),
                        mContext.getUserId());

        mCancellationSignal = new CancellationSignal();
        mSpyService = Mockito.spy(mService);

        mSpyService.mCurrentRequest = new RemoteRotationResolverService.RotationRequest(
                mMockCallbackInternal, Surface.ROTATION_0, Surface.ROTATION_0, "", 1000L,
                mCancellationSignal);

        mSpyService.getMaster().mIsServiceEnabled = true;

        mSpyService.mRemoteService = new MockRemoteRotationResolverService(mContext,
                mMockComponentName, mContext.getUserId(),
                /* idleUnbindTimeoutMs */60000L,
                /* Lock */ new Object());
    }

    @Test
    public void testResolveRotation_callOnSuccess() {
        doReturn(true).when(mSpyService).isServiceAvailableLocked();
        mSpyService.mCurrentRequest = null;

        RotationResolverInternal.RotationResolverCallbackInternal callbackInternal =
                Mockito.mock(RotationResolverInternal.RotationResolverCallbackInternal.class);

        mSpyService.resolveRotationLocked(callbackInternal, Surface.ROTATION_0, Surface.ROTATION_0,
                "", 1000L, mCancellationSignal);
        verify(callbackInternal).onSuccess(anyInt());
    }

    @Test
    public void testResolveRotation_noCrashWhenCancelled() {
        doReturn(true).when(mSpyService).isServiceAvailableLocked();

        RotationResolverInternal.RotationResolverCallbackInternal callbackInternal =
                Mockito.mock(RotationResolverInternal.RotationResolverCallbackInternal.class);

        final CancellationSignal cancellationSignal = new CancellationSignal();
        mSpyService.resolveRotationLocked(callbackInternal, Surface.ROTATION_0, Surface.ROTATION_0,
                "", 1000L, cancellationSignal);
        cancellationSignal.cancel();

        verify(mSpyService.mCurrentRequest).cancelInternal();
    }

    static class MockRemoteRotationResolverService extends RemoteRotationResolverService {
        MockRemoteRotationResolverService(Context context, ComponentName serviceName,
                int userId, long idleUnbindTimeoutMs, Object lock) {
            super(context, serviceName, userId, idleUnbindTimeoutMs, lock);
        }

        @Override
        public void resolveRotationLocked(RotationRequest request) {
            request.mCallbackInternal.onSuccess(request.mProposedRotation);
        }
    }
}
