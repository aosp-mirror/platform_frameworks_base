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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.rotationresolver.RotationResolverInternal;
import android.service.rotationresolver.RotationResolutionRequest;
import android.view.Surface;

import androidx.test.core.app.ApplicationProvider;
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
    private static final String PACKAGE_NAME = "test_pkg";
    private static final String CLASS_NAME = "test_class";

    @Mock
    RotationResolverInternal.RotationResolverCallbackInternal mMockCallbackInternal;
    @Mock
    PackageManager mMockPackageManager;

    private Context mContext;
    private CancellationSignal mCancellationSignal;
    private RotationResolverManagerPerUserService mService;
    private RotationResolutionRequest mRequest;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        // setup context.
        mContext = spy(ApplicationProvider.getApplicationContext());
        doReturn(PACKAGE_NAME).when(mMockPackageManager).getRotationResolverPackageName();
        doReturn(createTestingResolveInfo()).when(mMockPackageManager).resolveServiceAsUser(any(),
                anyInt(), anyInt());
        doReturn(mMockPackageManager).when(mContext).getPackageManager();
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());

        // setup a spy for the RotationResolverManagerPerUserService.
        final RotationResolverManagerService mainService = new RotationResolverManagerService(
                mContext);
        final Object lock = new Object();
        mService = new RotationResolverManagerPerUserService(mainService, lock,
                mContext.getUserId());

        mCancellationSignal = new CancellationSignal();

        mRequest = new RotationResolutionRequest("", Surface.ROTATION_0, Surface.ROTATION_0,
                true, 1000L);
        this.mService.mCurrentRequest = new RemoteRotationResolverService.RotationRequest(
                mMockCallbackInternal, mRequest, mCancellationSignal, lock);

        this.mService.getMaster().mIsServiceEnabled = true;

        ComponentName componentName = new ComponentName(PACKAGE_NAME, CLASS_NAME);
        this.mService.mRemoteService = new MockRemoteRotationResolverService(mContext,
                componentName, mContext.getUserId(), /* idleUnbindTimeoutMs */60000L);
    }

    @Test
    public void testResolveRotation_callOnSuccess() {
        mService.mCurrentRequest = null;

        RotationResolverInternal.RotationResolverCallbackInternal callbackInternal =
                Mockito.mock(RotationResolverInternal.RotationResolverCallbackInternal.class);

        mService.resolveRotationLocked(callbackInternal, mRequest, mCancellationSignal);
        verify(callbackInternal).onSuccess(anyInt());
    }

    @Test
    public void testResolveRotation_noCrashWhenCancelled() {
        RotationResolverInternal.RotationResolverCallbackInternal callbackInternal =
                Mockito.mock(RotationResolverInternal.RotationResolverCallbackInternal.class);

        final CancellationSignal cancellationSignal = new CancellationSignal();
        mService.resolveRotationLocked(callbackInternal, mRequest, cancellationSignal);
        cancellationSignal.cancel();
    }

    private ResolveInfo createTestingResolveInfo() {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = PACKAGE_NAME;
        resolveInfo.serviceInfo.name = CLASS_NAME;
        resolveInfo.serviceInfo.permission = Manifest.permission.BIND_ROTATION_RESOLVER_SERVICE;
        return resolveInfo;
    }

    static class MockRemoteRotationResolverService extends RemoteRotationResolverService {
        MockRemoteRotationResolverService(Context context, ComponentName serviceName, int userId,
                long idleUnbindTimeoutMs) {
            super(context, serviceName, userId, idleUnbindTimeoutMs);
        }

        @Override
        public void resolveRotation(RotationRequest request) {
            request.mCallbackInternal.onSuccess(request.mRemoteRequest.getProposedRotation());
        }
    }
}
