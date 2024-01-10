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

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.StringParceledListSlice;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.UserManager;
import android.security.IKeyChainService;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SecurityControllerTest extends SysuiTestCase {
    private static final ComponentName DEVICE_OWNER_COMPONENT =
            new ComponentName("com.android.foo", "bar");

    private final DevicePolicyManager mDevicePolicyManager = mock(DevicePolicyManager.class);
    private final IKeyChainService.Stub mKeyChainService = mock(IKeyChainService.Stub.class);
    private final UserManager mUserManager = mock(UserManager.class);
    private final UserTracker mUserTracker = mock(UserTracker.class);
    private final BroadcastDispatcher mBroadcastDispatcher = mock(BroadcastDispatcher.class);
    private final Handler mHandler = mock(Handler.class);
    private SecurityControllerImpl mSecurityController;
    private ConnectivityManager mConnectivityManager = mock(ConnectivityManager.class);
    private FakeExecutor mMainExecutor;
    private FakeExecutor mBgExecutor;
    private BroadcastReceiver mBroadcastReceiver;

    @Before
    public void setUp() throws Exception {
        mContext.addMockSystemService(Context.DEVICE_POLICY_SERVICE, mDevicePolicyManager);
        mContext.addMockSystemService(Context.USER_SERVICE, mUserManager);
        mContext.addMockSystemService(Context.CONNECTIVITY_SERVICE, mConnectivityManager);

        Intent intent = new Intent(IKeyChainService.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        mContext.addMockService(comp, mKeyChainService);

        when(mUserManager.getUserInfo(anyInt())).thenReturn(new UserInfo());
        when(mUserManager.isUserUnlocked(any())).thenReturn(true);

        when(mKeyChainService.getUserCaAliases())
                .thenReturn(new StringParceledListSlice(new ArrayList<String>()));
        // Without this line, mKeyChainService gets wrapped in a proxy when Stub.asInterface() is
        // used on it, and the mocking above does not work.
        when(mKeyChainService.queryLocalInterface("android.security.IKeyChainService"))
                .thenReturn(mKeyChainService);

        ArgumentCaptor<BroadcastReceiver> brCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        mMainExecutor = new FakeExecutor(new FakeSystemClock());
        mBgExecutor = new FakeExecutor(new FakeSystemClock());
        mSecurityController = new SecurityControllerImpl(
                mContext,
                mUserTracker,
                mHandler,
                mBroadcastDispatcher,
                mMainExecutor,
                mBgExecutor,
                Mockito.mock(DumpManager.class));

        verify(mBroadcastDispatcher).registerReceiverWithHandler(
                brCaptor.capture(),
                anyObject(),
                anyObject(),
                anyObject());

        mBroadcastReceiver = brCaptor.getValue();
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
    public void testGetDeviceOwnerComponentOnAnyUser() {
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                .thenReturn(DEVICE_OWNER_COMPONENT);
        assertEquals(mSecurityController.getDeviceOwnerComponentOnAnyUser(),
                DEVICE_OWNER_COMPONENT);
    }

    @Test
    public void testIsFinancedDevice() {
        when(mDevicePolicyManager.isFinancedDevice()).thenReturn(true);
        // TODO(b/259908270): remove
        when(mDevicePolicyManager.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_FINANCED);
        assertEquals(mSecurityController.isFinancedDevice(), true);
    }

    @Test
    public void testWorkAccount() throws Exception {
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

        when(mKeyChainService.getUserCaAliases())
                .thenReturn(new StringParceledListSlice(Arrays.asList("One CA Alias")));

        refreshCACerts(MANAGED_USER_ID);
        mBgExecutor.runAllReady();

        assertTrue(mSecurityController.hasCACertInWorkProfile());
    }

    @Test
    public void testCaCertLoader() throws Exception {
        assertFalse(mSecurityController.hasCACertInCurrentUser());

        // With a CA cert
        when(mKeyChainService.getUserCaAliases())
                .thenReturn(new StringParceledListSlice(Arrays.asList("One CA Alias")));

        refreshCACerts(0);
        mBgExecutor.runAllReady();

        assertTrue(mSecurityController.hasCACertInCurrentUser());

        // Exception
        when(mKeyChainService.getUserCaAliases())
                .thenThrow(new AssertionError("Test AssertionError"))
                .thenReturn(new StringParceledListSlice(new ArrayList<String>()));

        refreshCACerts(0);
        mBgExecutor.runAllReady();

        assertTrue(mSecurityController.hasCACertInCurrentUser());

        refreshCACerts(0);
        mBgExecutor.runAllReady();

        assertFalse(mSecurityController.hasCACertInCurrentUser());
    }

    @Test
    public void testNetworkRequest() {
        verify(mConnectivityManager, times(1)).registerNetworkCallback(argThat(
                (NetworkRequest request) ->
                        request.equals(new NetworkRequest.Builder()
                                .clearCapabilities().addTransportType(TRANSPORT_VPN).build())
                ), any(NetworkCallback.class));
    }

    @Test
    public void testRemoveCallbackWhileDispatch_doesntCrash() {
        final AtomicBoolean remove = new AtomicBoolean(false);
        SecurityController.SecurityControllerCallback callback =
                new SecurityController.SecurityControllerCallback() {
                    @Override
                    public void onStateChanged() {
                        if (remove.get()) {
                            mSecurityController.removeCallback(this);
                        }
                    }
                };
        mSecurityController.addCallback(callback);
        // Add another callback so the iteration continues
        mSecurityController.addCallback(() -> {});
        mBgExecutor.runAllReady();
        remove.set(true);

        mSecurityController.onUserSwitched(10);
        mBgExecutor.runAllReady();
    }

    /**
     * refresh CA certs by sending a user unlocked broadcast for the desired user
     */
    private void refreshCACerts(int userId) {
        Intent intent = new Intent(Intent.ACTION_USER_UNLOCKED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        mBroadcastReceiver.onReceive(mContext, intent);
    }
}
