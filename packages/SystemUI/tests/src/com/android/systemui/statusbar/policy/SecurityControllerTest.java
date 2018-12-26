/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.StringParceledListSlice;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.security.IKeyChainService;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.statusbar.policy.SecurityController.SecurityControllerCallback;
import com.android.systemui.SysuiTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class SecurityControllerTest extends SysuiTestCase implements SecurityControllerCallback {
    private final DevicePolicyManager mDevicePolicyManager = mock(DevicePolicyManager.class);
    private final IKeyChainService.Stub mKeyChainService = mock(IKeyChainService.Stub.class);
    private final UserManager mUserManager = mock(UserManager.class);
    private SecurityControllerImpl mSecurityController;
    private CountDownLatch mStateChangedLatch;
    private ConnectivityManager mConnectivityManager = mock(ConnectivityManager.class);

    // implementing SecurityControllerCallback
    @Override
    public void onStateChanged() {
        mStateChangedLatch.countDown();
    }

    @Before
    public void setUp() throws Exception {
        mContext.addMockSystemService(Context.DEVICE_POLICY_SERVICE, mDevicePolicyManager);
        mContext.addMockSystemService(Context.USER_SERVICE, mUserManager);
        mContext.addMockSystemService(Context.CONNECTIVITY_SERVICE, mConnectivityManager);

        Intent intent = new Intent(IKeyChainService.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        mContext.addMockService(comp, mKeyChainService);

        when(mUserManager.getUserInfo(anyInt())).thenReturn(new UserInfo());

        when(mKeyChainService.getUserCaAliases())
                .thenReturn(new StringParceledListSlice(new ArrayList<String>()));
        // Without this line, mKeyChainService gets wrapped in a proxy when Stub.asInterface() is
        // used on it, and the mocking above does not work.
        when(mKeyChainService.queryLocalInterface("android.security.IKeyChainService"))
                .thenReturn(mKeyChainService);

        // Wait for callbacks from 1) the CACertLoader and 2) the onUserSwitched() function in the
        // constructor of mSecurityController
        mStateChangedLatch = new CountDownLatch(2);
        // TODO: Migrate this test to TestableLooper and use a handler attached
        // to that.
        mSecurityController = new SecurityControllerImpl(mContext,
                new Handler(Looper.getMainLooper()), this);
    }

    @After
    public void tearDown() {
        mSecurityController.removeCallback(this);
    }

    @Test
    public void testIsDeviceManaged() {
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        assertTrue(mSecurityController.isDeviceManaged());

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        assertFalse(mSecurityController.isDeviceManaged());
    }

    @Test
    public void testGetDeviceOwnerOrganizationName() {
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn("organization");
        assertEquals("organization", mSecurityController.getDeviceOwnerOrganizationName());
    }

    @Test
    public void testWorkAccount() throws Exception {
        // Wait for the callbacks from setUp()
        assertTrue(mStateChangedLatch.await(1, TimeUnit.SECONDS));
        assertFalse(mSecurityController.hasCACertInCurrentUser());

        final int PRIMARY_USER_ID = 0;
        final int MANAGED_USER_ID = 1;
        List<UserInfo> profiles = Arrays.asList(new UserInfo(PRIMARY_USER_ID, "Primary",
                                                             UserInfo.FLAG_PRIMARY),
                                                new UserInfo(MANAGED_USER_ID, "Working",
                                                             UserInfo.FLAG_MANAGED_PROFILE));
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);
        assertTrue(mSecurityController.hasWorkProfile());
        assertFalse(mSecurityController.hasCACertInWorkProfile());

        mStateChangedLatch = new CountDownLatch(1);

        when(mKeyChainService.getUserCaAliases())
                .thenReturn(new StringParceledListSlice(Arrays.asList("One CA Alias")));

        mSecurityController.new CACertLoader()
                           .execute(MANAGED_USER_ID);

        assertTrue(mStateChangedLatch.await(3, TimeUnit.SECONDS));
        assertTrue(mSecurityController.hasCACertInWorkProfile());
    }

    @Test
    public void testCaCertLoader() throws Exception {
        // Wait for the callbacks from setUp()
        assertTrue(mStateChangedLatch.await(1, TimeUnit.SECONDS));
        assertFalse(mSecurityController.hasCACertInCurrentUser());

        // With a CA cert
        mStateChangedLatch = new CountDownLatch(1);

        when(mKeyChainService.getUserCaAliases())
                .thenReturn(new StringParceledListSlice(Arrays.asList("One CA Alias")));

        mSecurityController.new CACertLoader()
                           .execute(0);

        assertTrue(mStateChangedLatch.await(3, TimeUnit.SECONDS));
        assertTrue(mSecurityController.hasCACertInCurrentUser());

        // Exception

        mStateChangedLatch = new CountDownLatch(1);

        when(mKeyChainService.getUserCaAliases())
                .thenThrow(new AssertionError("Test AssertionError"))
                .thenReturn(new StringParceledListSlice(new ArrayList<String>()));

        mSecurityController.new CACertLoader()
                           .execute(0);

        assertFalse(mStateChangedLatch.await(1, TimeUnit.SECONDS));
        assertTrue(mSecurityController.hasCACertInCurrentUser());
        // The retry takes 30s
        //assertTrue(mStateChangedLatch.await(31, TimeUnit.SECONDS));
        //assertFalse(mSecurityController.hasCACertInCurrentUser());
    }

    @Test
    public void testNetworkRequest() {
        verify(mConnectivityManager, times(1)).registerNetworkCallback(argThat(
                (NetworkRequest request) -> request.networkCapabilities.getUids() == null
                        && request.networkCapabilities.getCapabilities().length == 0
                ), any(NetworkCallback.class));
    }
}
