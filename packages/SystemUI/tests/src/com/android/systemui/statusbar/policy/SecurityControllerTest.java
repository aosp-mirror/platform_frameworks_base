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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.supervision.SupervisionManager;
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
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.security.IKeyChainService;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SecurityControllerTest extends SysuiTestCase {
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();
    @Rule public final CheckFlagsRule checkFlags = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final ComponentName DEVICE_OWNER_COMPONENT =
            new ComponentName("com.android.foo", "bar");

    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Mock private IKeyChainService.Stub mKeyChainService;
    @Mock private UserManager mUserManager;
    @Mock private UserTracker mUserTracker;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private Handler mHandler;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private SupervisionManager mSupervisionManager;

    private SecurityControllerImpl mSecurityController;
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
                Mockito.mock(DumpManager.class),
                () -> mSupervisionManager);

        verify(mBroadcastDispatcher).registerReceiverWithHandler(
                brCaptor.capture(),
                any(),
                any(),
                any());

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

    @Test
    @RequiresFlagsDisabled(android.app.supervision.flags.Flags.FLAG_DEPRECATE_DPM_SUPERVISION_APIS)
    public void isParentalControlsEnabled_usingDpm_supervisionIsProfileOwner() {
        when(mDevicePolicyManager.getProfileOwnerOrDeviceOwnerSupervisionComponent(any()))
                .thenReturn(DEVICE_OWNER_COMPONENT);

        assertTrue(mSecurityController.isParentalControlsEnabled());
    }

    @Test
    @RequiresFlagsDisabled(android.app.supervision.flags.Flags.FLAG_DEPRECATE_DPM_SUPERVISION_APIS)
    public void isParentalControlsEnabled_usingDpm_supervisionIsNotProfileOwner() {
        when(mDevicePolicyManager.getProfileOwnerOrDeviceOwnerSupervisionComponent(any()))
                .thenReturn(null);

        assertFalse(mSecurityController.isParentalControlsEnabled());
    }

    @Test
    @RequiresFlagsEnabled(android.app.supervision.flags.Flags.FLAG_DEPRECATE_DPM_SUPERVISION_APIS)
    public void isParentalControlsEnabled_usingSupervisionManager_supervisionIsEnabled() {
        when(mSupervisionManager.isSupervisionEnabledForUser(anyInt()))
                .thenReturn(true);

        assertTrue(mSecurityController.isParentalControlsEnabled());
    }

    @Test
    @RequiresFlagsEnabled(android.app.supervision.flags.Flags.FLAG_DEPRECATE_DPM_SUPERVISION_APIS)
    public void isParentalControlsEnabled_usingSupervisionManager_supervisionIsNotEnabled() {
        when(mSupervisionManager.isSupervisionEnabledForUser(anyInt()))
                .thenReturn(false);

        assertFalse(mSecurityController.isParentalControlsEnabled());
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
