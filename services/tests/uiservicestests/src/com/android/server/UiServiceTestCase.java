/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;

import com.android.server.pm.UserManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class UiServiceTestCase {
    @Mock protected PackageManagerInternal mPmi;
    @Mock protected UserManagerInternal mUmi;
    @Mock protected UriGrantsManagerInternal mUgmInternal;

    protected static final String PKG_N_MR1 = "com.example.n_mr1";
    protected static final String PKG_O = "com.example.o";
    protected static final String PKG_P = "com.example.p";
    protected static final String PKG_R = "com.example.r";

    protected static final int UID_N_MR1 = 10001;
    protected static final int UID_O = 10002;
    protected static final int UID_P = 10003;
    protected static final int UID_R = 10004;

    @Rule
    public TestableContext mContext =
            spy(new TestableContext(InstrumentationRegistry.getContext(), null));

    protected final int mUid = Binder.getCallingUid();
    protected final @UserIdInt int mUserId = UserHandle.getUserId(mUid);
    protected final UserHandle mUser = UserHandle.of(mUserId);
    protected final String mPkg = mContext.getPackageName();

    protected TestableContext getContext() {
        return mContext;
    }

    @Before
    public final void setup() {
        MockitoAnnotations.initMocks(this);

        // Share classloader to allow package access.
        System.setProperty("dexmaker.share_classloader", "true");

        // Assume some default packages
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPmi);
        when(mPmi.getPackageTargetSdkVersion(anyString()))
                .thenAnswer((iom) -> {
                    switch ((String) iom.getArgument(0)) {
                        case PKG_N_MR1:
                            return Build.VERSION_CODES.N_MR1;
                        case PKG_O:
                            return Build.VERSION_CODES.O;
                        case PKG_P:
                            return Build.VERSION_CODES.P;
                        case PKG_R:
                            return Build.VERSION_CODES.R;
                        default:
                            return Build.VERSION_CODES.CUR_DEVELOPMENT;
                    }
                });

        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mUmi);
        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, mUgmInternal);
        when(mUgmInternal.checkGrantUriPermission(
                anyInt(), anyString(), any(Uri.class), anyInt(), anyInt())).thenReturn(-1);

        Mockito.doReturn(new Intent()).when(mContext).registerReceiverAsUser(
                any(), any(), any(), any(), any());
        Mockito.doReturn(new Intent()).when(mContext).registerReceiver(any(), any());
        Mockito.doReturn(new Intent()).when(mContext).registerReceiver(any(), any(), anyInt());
        Mockito.doNothing().when(mContext).unregisterReceiver(any());
    }

    @After
    public final void cleanUpMockito() {
        Mockito.framework().clearInlineMocks();
    }
}
