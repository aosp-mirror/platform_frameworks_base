/*
 ** Copyright 2017, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.view.WindowManagerInternal;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;


/**
 * Tests for AccessibilityServiceConnection
 */
public class AccessibilityServiceConnectionTest {
    static final ComponentName COMPONENT_NAME = new ComponentName(
            "com.android.server.accessibility", "AccessibilityServiceConnectionTest");
    static final int SERVICE_ID = 42;

    AccessibilityServiceConnection mConnection;
    @Mock AccessibilityManagerService.UserState mMockUserState;
    @Mock Context mMockContext;
    @Mock AccessibilityServiceInfo mMockServiceInfo;
    @Mock ResolveInfo mMockResolveInfo;
    @Mock AccessibilityManagerService.SecurityPolicy mMockSecurityPolicy;
    @Mock AccessibilityClientConnection.SystemSupport mMockSystemSupport;
    @Mock WindowManagerInternal mMockWindowManagerInternal;
    @Mock GlobalActionPerformer mMockGlobalActionPerformer;
    @Mock KeyEventDispatcher mMockKeyEventDispatcher;


    @BeforeClass
    public static void oneTimeInitialization() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mMockSystemSupport.getKeyEventDispatcher()).thenReturn(mMockKeyEventDispatcher);
        when(mMockServiceInfo.getResolveInfo()).thenReturn(mMockResolveInfo);
        mMockResolveInfo.serviceInfo = mock(ServiceInfo.class);
        mMockResolveInfo.serviceInfo.applicationInfo = mock(ApplicationInfo.class);

        mConnection = new AccessibilityServiceConnection(mMockUserState, mMockContext,
                COMPONENT_NAME, mMockServiceInfo, SERVICE_ID, new Handler(), new Object(),
                mMockSecurityPolicy, mMockSystemSupport, mMockWindowManagerInternal,
                mMockGlobalActionPerformer);
    }

    @Test
    public void bind_requestsContextToBindService() {
        mConnection.bindLocked();
        verify(mMockContext).bindServiceAsUser(any(Intent.class), eq(mConnection),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE),
                any(UserHandle.class));
    }

    @Test
    public void unbind_requestsContextToUnbindService() {
        mConnection.unbindLocked();
        verify(mMockContext).unbindService(mConnection);
    }

    @Test
    public void bindConnectUnbind_linksAndUnlinksToServiceDeath() throws RemoteException {
        IBinder mockBinder = mock(IBinder.class);
        when(mMockUserState.getBindingServicesLocked())
                .thenReturn(new HashSet<>(Arrays.asList(COMPONENT_NAME)));
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mockBinder);
        verify(mockBinder).linkToDeath(eq(mConnection), anyInt());
        mConnection.unbindLocked();
        verify(mockBinder).unlinkToDeath(eq(mConnection), anyInt());
    }
}
