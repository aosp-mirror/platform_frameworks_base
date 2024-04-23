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

package com.android.server;

import static com.android.testutils.ContextUtils.mockService;
import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.UserIdInt;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.Credentials;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.net.VpnProfile;
import com.android.server.connectivity.Vpn;
import com.android.server.connectivity.VpnProfileStore;
import com.android.server.net.LockdownVpnTracker;
import com.android.testutils.HandlerUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VpnManagerServiceTest extends VpnTestBase {
    private static final String CONTEXT_ATTRIBUTION_TAG = "VPN_MANAGER";

    private static final int TIMEOUT_MS = 2_000;

    @Mock Context mContext;
    @Mock Context mContextWithoutAttributionTag;
    @Mock Context mSystemContext;
    @Mock Context mUserAllContext;
    private HandlerThread mHandlerThread;
    @Mock private Vpn mVpn;
    @Mock private INetworkManagementService mNms;
    @Mock private ConnectivityManager mCm;
    @Mock private UserManager mUserManager;
    @Mock private INetd mNetd;
    @Mock private PackageManager mPackageManager;
    @Mock private VpnProfileStore mVpnProfileStore;
    @Mock private LockdownVpnTracker mLockdownVpnTracker;

    private VpnManagerServiceDependencies mDeps;
    private VpnManagerService mService;
    private BroadcastReceiver mUserPresentReceiver;
    private BroadcastReceiver mIntentReceiver;
    private final String mNotMyVpnPkg = "com.not.my.vpn";

    class VpnManagerServiceDependencies extends VpnManagerService.Dependencies {
        @Override
        public HandlerThread makeHandlerThread() {
            return mHandlerThread;
        }

        @Override
        public INetworkManagementService getINetworkManagementService() {
            return mNms;
        }

        @Override
        public INetd getNetd() {
            return mNetd;
        }

        @Override
        public Vpn createVpn(Looper looper, Context context, INetworkManagementService nms,
                INetd netd, @UserIdInt int userId) {
            return mVpn;
        }

        @Override
        public VpnProfileStore getVpnProfileStore() {
            return mVpnProfileStore;
        }

        @Override
        public LockdownVpnTracker createLockDownVpnTracker(Context context, Handler handler,
                Vpn vpn, VpnProfile profile) {
            return mLockdownVpnTracker;
        }

        @Override
        public @UserIdInt int getMainUserId() {
            return UserHandle.USER_SYSTEM;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread("TestVpnManagerService");
        mDeps = new VpnManagerServiceDependencies();

        // The attribution tag is a dependency for IKE library to collect VPN metrics correctly
        // and thus should not be changed without updating the IKE code.
        doReturn(mContext)
                .when(mContextWithoutAttributionTag)
                .createAttributionContext(CONTEXT_ATTRIBUTION_TAG);

        doReturn(mUserAllContext).when(mContext).createContextAsUser(UserHandle.ALL, 0);
        doReturn(mSystemContext).when(mContext).createContextAsUser(UserHandle.SYSTEM, 0);
        doReturn(mPackageManager).when(mContext).getPackageManager();
        setMockedPackages(mPackageManager, sPackages);

        mockService(mContext, ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mCm);
        mockService(mContext, UserManager.class, Context.USER_SERVICE, mUserManager);
        doReturn(SYSTEM_USER).when(mUserManager).getUserInfo(eq(SYSTEM_USER_ID));

        mService = new VpnManagerService(mContextWithoutAttributionTag, mDeps);
        mService.systemReady();

        final ArgumentCaptor<BroadcastReceiver> intentReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        final ArgumentCaptor<BroadcastReceiver> userPresentReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mSystemContext).registerReceiver(
                userPresentReceiverCaptor.capture(), any(), any(), any());
        verify(mUserAllContext, times(2)).registerReceiver(
                intentReceiverCaptor.capture(), any(), any(), any());
        mUserPresentReceiver = userPresentReceiverCaptor.getValue();
        mIntentReceiver = intentReceiverCaptor.getValue();

        // Add user to create vpn in mVpn
        onUserStarted(SYSTEM_USER_ID);
        assertNotNull(mService.mVpns.get(SYSTEM_USER_ID));
    }

    @Test
    public void testUpdateAppExclusionList() {
        // Start vpn
        mService.startVpnProfile(TEST_VPN_PKG);
        verify(mVpn).startVpnProfile(eq(TEST_VPN_PKG));

        // Remove package due to package replaced.
        onPackageRemoved(PKGS[0], PKG_UIDS[0], true /* isReplacing */);
        verify(mVpn, never()).refreshPlatformVpnAppExclusionList();

        // Add package due to package replaced.
        onPackageAdded(PKGS[0], PKG_UIDS[0], true /* isReplacing */);
        verify(mVpn, never()).refreshPlatformVpnAppExclusionList();

        // Remove package
        onPackageRemoved(PKGS[0], PKG_UIDS[0], false /* isReplacing */);
        verify(mVpn).refreshPlatformVpnAppExclusionList();

        // Add the package back
        onPackageAdded(PKGS[0], PKG_UIDS[0], false /* isReplacing */);
        verify(mVpn, times(2)).refreshPlatformVpnAppExclusionList();
    }

    @Test
    public void testStartVpnProfileFromDiffPackage() {
        assertThrows(
                SecurityException.class, () -> mService.startVpnProfile(mNotMyVpnPkg));
    }

    @Test
    public void testStopVpnProfileFromDiffPackage() {
        assertThrows(SecurityException.class, () -> mService.stopVpnProfile(mNotMyVpnPkg));
    }

    @Test
    public void testGetProvisionedVpnProfileStateFromDiffPackage() {
        assertThrows(SecurityException.class, () ->
                mService.getProvisionedVpnProfileState(mNotMyVpnPkg));
    }

    @Test
    public void testGetProvisionedVpnProfileState() {
        mService.getProvisionedVpnProfileState(TEST_VPN_PKG);
        verify(mVpn).getProvisionedVpnProfileState(TEST_VPN_PKG);
    }

    private Intent buildIntent(String action, String packageName, int userId, int uid,
            boolean isReplacing) {
        final Intent intent = new Intent(action);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.putExtra(Intent.EXTRA_REPLACING, isReplacing);
        if (packageName != null) {
            intent.setData(Uri.fromParts("package" /* scheme */, packageName, null /* fragment */));
        }

        return intent;
    }

    private void sendIntent(Intent intent) {
        sendIntent(mIntentReceiver, mContext, intent);
    }

    private void sendIntent(BroadcastReceiver receiver, Context context, Intent intent) {
        final Handler h = mHandlerThread.getThreadHandler();

        // Send in handler thread.
        h.post(() -> receiver.onReceive(context, intent));
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
    }

    private void onUserStarted(int userId) {
        sendIntent(buildIntent(Intent.ACTION_USER_STARTED,
                null /* packageName */, userId, -1 /* uid */, false /* isReplacing */));
    }

    private void onUserUnlocked(int userId) {
        sendIntent(buildIntent(Intent.ACTION_USER_UNLOCKED,
                null /* packageName */, userId, -1 /* uid */, false /* isReplacing */));
    }

    private void onUserStopped(int userId) {
        sendIntent(buildIntent(Intent.ACTION_USER_STOPPED,
                null /* packageName */, userId, -1 /* uid */, false /* isReplacing */));
    }

    private void onLockDownReset() {
        sendIntent(buildIntent(LockdownVpnTracker.ACTION_LOCKDOWN_RESET, null /* packageName */,
                UserHandle.USER_SYSTEM, -1 /* uid */, false /* isReplacing */));
    }

    private void onPackageAdded(String packageName, int userId, int uid, boolean isReplacing) {
        sendIntent(buildIntent(Intent.ACTION_PACKAGE_ADDED, packageName, userId, uid, isReplacing));
    }

    private void onPackageAdded(String packageName, int uid, boolean isReplacing) {
        onPackageAdded(packageName, UserHandle.USER_SYSTEM, uid, isReplacing);
    }

    private void onPackageRemoved(String packageName, int userId, int uid, boolean isReplacing) {
        sendIntent(buildIntent(Intent.ACTION_PACKAGE_REMOVED, packageName, userId, uid,
                isReplacing));
    }

    private void onPackageRemoved(String packageName, int uid, boolean isReplacing) {
        onPackageRemoved(packageName, UserHandle.USER_SYSTEM, uid, isReplacing);
    }

    @Test
    public void testReceiveIntentFromNonHandlerThread() {
        assertThrows(IllegalStateException.class, () ->
                mIntentReceiver.onReceive(mContext, buildIntent(Intent.ACTION_PACKAGE_REMOVED,
                        PKGS[0], UserHandle.USER_SYSTEM, PKG_UIDS[0], true /* isReplacing */)));

        assertThrows(IllegalStateException.class, () ->
                mUserPresentReceiver.onReceive(mContext, new Intent(Intent.ACTION_USER_PRESENT)));
    }

    private void setupLockdownVpn(String packageName) {
        final byte[] profileTag = packageName.getBytes(StandardCharsets.UTF_8);
        doReturn(profileTag).when(mVpnProfileStore).get(Credentials.LOCKDOWN_VPN);
    }

    private void setupVpnProfile(String profileName) {
        final VpnProfile profile = new VpnProfile(profileName);
        profile.name = profileName;
        profile.server = "192.0.2.1";
        profile.dnsServers = "8.8.8.8";
        profile.type = VpnProfile.TYPE_IPSEC_XAUTH_PSK;
        final byte[] encodedProfile = profile.encode();
        doReturn(encodedProfile).when(mVpnProfileStore).get(Credentials.VPN + profileName);
    }

    @Test
    public void testUserPresent() {
        // Verify that LockDownVpnTracker is not created.
        verify(mLockdownVpnTracker, never()).init();

        setupLockdownVpn(TEST_VPN_PKG);
        setupVpnProfile(TEST_VPN_PKG);

        // mUserPresentReceiver only registers ACTION_USER_PRESENT intent and does no verification
        // on action, so an empty intent is enough.
        sendIntent(mUserPresentReceiver, mSystemContext, new Intent());

        verify(mLockdownVpnTracker).init();
        verify(mSystemContext).unregisterReceiver(mUserPresentReceiver);
        verify(mUserAllContext, never()).unregisterReceiver(any());
    }

    @Test
    public void testUpdateLockdownVpn() {
        setupLockdownVpn(TEST_VPN_PKG);
        onUserUnlocked(SYSTEM_USER_ID);

        // Will not create lockDownVpnTracker w/o valid profile configured in the keystore
        verify(mLockdownVpnTracker, never()).init();

        setupVpnProfile(TEST_VPN_PKG);

        // Remove the user from mVpns
        onUserStopped(SYSTEM_USER_ID);
        onUserUnlocked(SYSTEM_USER_ID);
        verify(mLockdownVpnTracker, never()).init();

        // Add user back
        onUserStarted(SYSTEM_USER_ID);
        verify(mLockdownVpnTracker).init();

        // Trigger another update. The existing LockDownVpnTracker should be shut down and
        // initialize another one.
        onUserUnlocked(SYSTEM_USER_ID);
        verify(mLockdownVpnTracker).shutdown();
        verify(mLockdownVpnTracker, times(2)).init();
    }

    @Test
    public void testLockdownReset() {
        // Init LockdownVpnTracker
        setupLockdownVpn(TEST_VPN_PKG);
        setupVpnProfile(TEST_VPN_PKG);
        onUserUnlocked(SYSTEM_USER_ID);
        verify(mLockdownVpnTracker).init();

        onLockDownReset();
        verify(mLockdownVpnTracker).reset();
    }

    @Test
    public void testLockdownResetWhenLockdownVpnTrackerIsNotInit() {
        setupLockdownVpn(TEST_VPN_PKG);
        setupVpnProfile(TEST_VPN_PKG);

        onLockDownReset();

        // LockDownVpnTracker is not created. Lockdown reset will not take effect.
        verify(mLockdownVpnTracker, never()).reset();
    }

    @Test
    public void testIsVpnLockdownEnabled() {
        // Vpn is created but the VPN lockdown is not enabled.
        assertFalse(mService.isVpnLockdownEnabled(SYSTEM_USER_ID));

        // Set lockdown for the SYSTEM_USER_ID VPN.
        doReturn(true).when(mVpn).getLockdown();
        assertTrue(mService.isVpnLockdownEnabled(SYSTEM_USER_ID));

        // Even lockdown is enabled but no Vpn is created for SECONDARY_USER.
        assertFalse(mService.isVpnLockdownEnabled(SECONDARY_USER.id));
    }

    @Test
    public void testGetVpnLockdownAllowlist() {
        doReturn(null).when(mVpn).getLockdownAllowlist();
        assertNull(mService.getVpnLockdownAllowlist(SYSTEM_USER_ID));

        final List<String> expected = List.of(PKGS);
        doReturn(expected).when(mVpn).getLockdownAllowlist();
        assertEquals(expected, mService.getVpnLockdownAllowlist(SYSTEM_USER_ID));

        // Even lockdown is enabled but no Vpn is created for SECONDARY_USER.
        assertNull(mService.getVpnLockdownAllowlist(SECONDARY_USER.id));
    }

    @Test
    public void testGetFromVpnProfileStore() {
        final String name = Credentials.VPN + TEST_VPN_PKG;
        mService.getFromVpnProfileStore(name);
        verify(mVpnProfileStore).get(name);
    }

    @Test
    public void testPutIntoVpnProfileStore() {
        final String name = Credentials.VPN + TEST_VPN_PKG;
        final VpnProfile vpnProfile = new VpnProfile(TEST_VPN_PKG);
        final byte[] encodedProfile = vpnProfile.encode();

        mService.putIntoVpnProfileStore(name, encodedProfile);
        verify(mVpnProfileStore).put(name, encodedProfile);
    }

    @Test
    public void testRemoveFromVpnProfileStore() {
        final String name = Credentials.VPN + TEST_VPN_PKG;
        mService.removeFromVpnProfileStore(name);
        verify(mVpnProfileStore).remove(name);
    }

    @Test
    public void testListFromVpnProfileStore() {
        final String name = Credentials.VPN + TEST_VPN_PKG;
        mService.listFromVpnProfileStore(name);
        verify(mVpnProfileStore).list(name);
    }
}
