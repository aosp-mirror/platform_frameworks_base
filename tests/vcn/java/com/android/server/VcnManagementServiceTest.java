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

package com.android.server;

import static android.net.ConnectivityManager.NetworkCallback;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_ACTIVE;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_SAFE_MODE;
import static android.telephony.TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
import static android.telephony.TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS;

import static com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import static com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionTrackerCallback;
import static com.android.server.vcn.VcnTestUtils.setupSystemService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.net.Uri;
import android.net.vcn.IVcnStatusCallback;
import android.net.vcn.IVcnUnderlyingNetworkPolicyListener;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnConfigTest;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.net.vcn.VcnManager;
import android.net.vcn.VcnUnderlyingNetworkPolicy;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.VcnManagementService.VcnCallback;
import com.android.server.VcnManagementService.VcnStatusCallbackInfo;
import com.android.server.vcn.TelephonySubscriptionTracker;
import com.android.server.vcn.Vcn;
import com.android.server.vcn.VcnContext;
import com.android.server.vcn.VcnNetworkProvider;
import com.android.server.vcn.util.PersistableBundleUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

/** Tests for {@link VcnManagementService}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnManagementServiceTest {
    private static final String TEST_PACKAGE_NAME =
            VcnManagementServiceTest.class.getPackage().getName();
    private static final String TEST_PACKAGE_NAME_2 = "TEST_PKG_2";
    private static final String TEST_CB_PACKAGE_NAME =
            VcnManagementServiceTest.class.getPackage().getName() + ".callback";
    private static final ParcelUuid TEST_UUID_1 = new ParcelUuid(new UUID(0, 0));
    private static final ParcelUuid TEST_UUID_2 = new ParcelUuid(new UUID(1, 1));
    private static final ParcelUuid TEST_UUID_3 = new ParcelUuid(new UUID(2, 2));
    private static final VcnConfig TEST_VCN_CONFIG;
    private static final VcnConfig TEST_VCN_CONFIG_PKG_2;
    private static final int TEST_UID = Process.FIRST_APPLICATION_UID;

    static {
        final Context mockConfigContext = mock(Context.class);

        doReturn(TEST_PACKAGE_NAME).when(mockConfigContext).getOpPackageName();
        TEST_VCN_CONFIG = VcnConfigTest.buildTestConfig(mockConfigContext);

        doReturn(TEST_PACKAGE_NAME_2).when(mockConfigContext).getOpPackageName();
        TEST_VCN_CONFIG_PKG_2 = VcnConfigTest.buildTestConfig(mockConfigContext);
    }

    private static final Map<ParcelUuid, VcnConfig> TEST_VCN_CONFIG_MAP =
            Collections.unmodifiableMap(Collections.singletonMap(TEST_UUID_1, TEST_VCN_CONFIG));

    private static final int TEST_SUBSCRIPTION_ID = 1;
    private static final int TEST_SUBSCRIPTION_ID_2 = 2;
    private static final SubscriptionInfo TEST_SUBSCRIPTION_INFO =
            new SubscriptionInfo(
                    TEST_SUBSCRIPTION_ID /* id */,
                    "" /* iccId */,
                    0 /* simSlotIndex */,
                    "Carrier" /* displayName */,
                    "Carrier" /* carrierName */,
                    0 /* nameSource */,
                    255 /* iconTint */,
                    "12345" /* number */,
                    0 /* roaming */,
                    null /* icon */,
                    "0" /* mcc */,
                    "0" /* mnc */,
                    "0" /* countryIso */,
                    false /* isEmbedded */,
                    null /* nativeAccessRules */,
                    null /* cardString */,
                    false /* isOpportunistic */,
                    TEST_UUID_1.toString() /* groupUUID */,
                    0 /* carrierId */,
                    0 /* profileClass */);

    private final Context mMockContext = mock(Context.class);
    private final VcnManagementService.Dependencies mMockDeps =
            mock(VcnManagementService.Dependencies.class);
    private final TestLooper mTestLooper = new TestLooper();
    private final ConnectivityManager mConnMgr = mock(ConnectivityManager.class);
    private final TelephonyManager mTelMgr = mock(TelephonyManager.class);
    private final SubscriptionManager mSubMgr = mock(SubscriptionManager.class);
    private final AppOpsManager mAppOpsMgr = mock(AppOpsManager.class);
    private final VcnContext mVcnContext = mock(VcnContext.class);
    private final PersistableBundleUtils.LockingReadWriteHelper mConfigReadWriteHelper =
            mock(PersistableBundleUtils.LockingReadWriteHelper.class);
    private final TelephonySubscriptionTracker mSubscriptionTracker =
            mock(TelephonySubscriptionTracker.class);

    private final ArgumentCaptor<VcnCallback> mVcnCallbackCaptor =
            ArgumentCaptor.forClass(VcnCallback.class);

    private final VcnManagementService mVcnMgmtSvc;

    private final IVcnUnderlyingNetworkPolicyListener mMockPolicyListener =
            mock(IVcnUnderlyingNetworkPolicyListener.class);
    private final IVcnStatusCallback mMockStatusCallback = mock(IVcnStatusCallback.class);
    private final IBinder mMockIBinder = mock(IBinder.class);

    public VcnManagementServiceTest() throws Exception {
        setupSystemService(
                mMockContext, mConnMgr, Context.CONNECTIVITY_SERVICE, ConnectivityManager.class);
        setupSystemService(
                mMockContext, mTelMgr, Context.TELEPHONY_SERVICE, TelephonyManager.class);
        setupSystemService(
                mMockContext,
                mSubMgr,
                Context.TELEPHONY_SUBSCRIPTION_SERVICE,
                SubscriptionManager.class);
        setupSystemService(mMockContext, mAppOpsMgr, Context.APP_OPS_SERVICE, AppOpsManager.class);

        doReturn(TEST_PACKAGE_NAME).when(mMockContext).getOpPackageName();

        doReturn(mMockContext).when(mVcnContext).getContext();
        doReturn(mTestLooper.getLooper()).when(mMockDeps).getLooper();
        doReturn(TEST_UID).when(mMockDeps).getBinderCallingUid();
        doReturn(mVcnContext)
                .when(mMockDeps)
                .newVcnContext(
                        eq(mMockContext),
                        eq(mTestLooper.getLooper()),
                        any(VcnNetworkProvider.class),
                        anyBoolean());
        doReturn(mSubscriptionTracker)
                .when(mMockDeps)
                .newTelephonySubscriptionTracker(
                        eq(mMockContext),
                        eq(mTestLooper.getLooper()),
                        any(TelephonySubscriptionTrackerCallback.class));
        doReturn(mConfigReadWriteHelper)
                .when(mMockDeps)
                .newPersistableBundleLockingReadWriteHelper(any());

        // Setup VCN instance generation
        doAnswer((invocation) -> {
            // Mock-within a doAnswer is safe, because it doesn't actually run nested.
            return mock(Vcn.class);
        }).when(mMockDeps).newVcn(any(), any(), any(), any(), any());

        final PersistableBundle bundle =
                PersistableBundleUtils.fromMap(
                        TEST_VCN_CONFIG_MAP,
                        PersistableBundleUtils::fromParcelUuid,
                        VcnConfig::toPersistableBundle);
        doReturn(bundle).when(mConfigReadWriteHelper).readFromDisk();

        setupMockedCarrierPrivilege(true);
        mVcnMgmtSvc = new VcnManagementService(mMockContext, mMockDeps);
        setupActiveSubscription(TEST_UUID_1);

        doReturn(mMockIBinder).when(mMockPolicyListener).asBinder();
        doReturn(mMockIBinder).when(mMockStatusCallback).asBinder();

        // Make sure the profiles are loaded.
        mTestLooper.dispatchAll();
    }

    @Before
    public void setUp() {
        doNothing()
                .when(mMockContext)
                .enforceCallingOrSelfPermission(
                        eq(android.Manifest.permission.NETWORK_FACTORY), any());
    }


    private void setupMockedCarrierPrivilege(boolean isPrivileged) {
        setupMockedCarrierPrivilege(isPrivileged, TEST_PACKAGE_NAME);
    }

    private void setupMockedCarrierPrivilege(boolean isPrivileged, String pkg) {
        doReturn(Collections.singletonList(TEST_SUBSCRIPTION_INFO))
                .when(mSubMgr)
                .getSubscriptionsInGroup(any());
        doReturn(mTelMgr)
                .when(mTelMgr)
                .createForSubscriptionId(eq(TEST_SUBSCRIPTION_INFO.getSubscriptionId()));
        doReturn(
                        isPrivileged
                                ? CARRIER_PRIVILEGE_STATUS_HAS_ACCESS
                                : CARRIER_PRIVILEGE_STATUS_NO_ACCESS)
                .when(mTelMgr)
                .checkCarrierPrivilegesForPackage(eq(pkg));
    }

    @Test
    public void testSystemReady() throws Exception {
        mVcnMgmtSvc.systemReady();
        mTestLooper.dispatchAll();

        verify(mConnMgr).registerNetworkProvider(any(VcnNetworkProvider.class));
        verify(mSubscriptionTracker).register();
        verify(mConnMgr)
                .registerNetworkCallback(
                        eq(new NetworkRequest.Builder().clearCapabilities().build()),
                        any(NetworkCallback.class));
    }

    @Test
    public void testNonSystemServerRealConfigFileAccessPermission() throws Exception {
        // Attempt to build a real instance of the dependencies, and verify we cannot write to the
        // file.
        VcnManagementService.Dependencies deps = new VcnManagementService.Dependencies();
        PersistableBundleUtils.LockingReadWriteHelper configReadWriteHelper =
                deps.newPersistableBundleLockingReadWriteHelper(
                        VcnManagementService.VCN_CONFIG_FILE);

        // Even tests should not be able to read/write configs from disk; SELinux policies restrict
        // it to only the system server.
        // Reading config should always return null since the file "does not exist", and writing
        // should throw an IOException.
        assertNull(configReadWriteHelper.readFromDisk());

        try {
            configReadWriteHelper.writeToDisk(new PersistableBundle());
            fail("Expected IOException due to SELinux policy");
        } catch (FileNotFoundException expected) {
        }
    }

    @Test
    public void testLoadVcnConfigsOnStartup() throws Exception {
        mTestLooper.dispatchAll();

        assertEquals(TEST_VCN_CONFIG_MAP, mVcnMgmtSvc.getConfigs());
        verify(mConfigReadWriteHelper).readFromDisk();
    }

    private TelephonySubscriptionSnapshot triggerSubscriptionTrackerCbAndGetSnapshot(
            ParcelUuid activeDataSubGrp, Set<ParcelUuid> activeSubscriptionGroups) {
        return triggerSubscriptionTrackerCbAndGetSnapshot(
                activeDataSubGrp, activeSubscriptionGroups, Collections.emptyMap());
    }

    private TelephonySubscriptionSnapshot triggerSubscriptionTrackerCbAndGetSnapshot(
            ParcelUuid activeDataSubGrp,
            Set<ParcelUuid> activeSubscriptionGroups,
            Map<Integer, ParcelUuid> subIdToGroupMap) {
        return triggerSubscriptionTrackerCbAndGetSnapshot(
                activeDataSubGrp,
                activeSubscriptionGroups,
                subIdToGroupMap,
                true /* hasCarrierPrivileges */);
    }

    private TelephonySubscriptionSnapshot triggerSubscriptionTrackerCbAndGetSnapshot(
            ParcelUuid activeDataSubGrp,
            Set<ParcelUuid> activeSubscriptionGroups,
            Map<Integer, ParcelUuid> subIdToGroupMap,
            boolean hasCarrierPrivileges) {
        return triggerSubscriptionTrackerCbAndGetSnapshot(
                TEST_SUBSCRIPTION_ID,
                activeDataSubGrp,
                activeSubscriptionGroups,
                subIdToGroupMap,
                hasCarrierPrivileges);
    }

    private TelephonySubscriptionSnapshot triggerSubscriptionTrackerCbAndGetSnapshot(
            int activeDataSubId,
            ParcelUuid activeDataSubGrp,
            Set<ParcelUuid> activeSubscriptionGroups,
            Map<Integer, ParcelUuid> subIdToGroupMap,
            boolean hasCarrierPrivileges) {
        final TelephonySubscriptionSnapshot snapshot =
                buildSubscriptionSnapshot(
                        activeDataSubId,
                        activeDataSubGrp,
                        activeSubscriptionGroups,
                        subIdToGroupMap,
                        hasCarrierPrivileges);

        final TelephonySubscriptionTrackerCallback cb = getTelephonySubscriptionTrackerCallback();
        cb.onNewSnapshot(snapshot);

        return snapshot;
    }

    private TelephonySubscriptionSnapshot buildSubscriptionSnapshot(
            int activeDataSubId,
            ParcelUuid activeDataSubGrp,
            Set<ParcelUuid> activeSubscriptionGroups,
            Map<Integer, ParcelUuid> subIdToGroupMap,
            boolean hasCarrierPrivileges) {
        final TelephonySubscriptionSnapshot snapshot = mock(TelephonySubscriptionSnapshot.class);
        doReturn(activeSubscriptionGroups).when(snapshot).getActiveSubscriptionGroups();
        doReturn(activeDataSubGrp).when(snapshot).getActiveDataSubscriptionGroup();
        doReturn(activeDataSubId).when(snapshot).getActiveDataSubscriptionId();

        final Set<String> privilegedPackages =
                (activeSubscriptionGroups == null || activeSubscriptionGroups.isEmpty())
                        ? Collections.emptySet()
                        : Collections.singleton(TEST_PACKAGE_NAME);
        doReturn(hasCarrierPrivileges)
                .when(snapshot)
                .packageHasPermissionsForSubscriptionGroup(
                        argThat(val -> activeSubscriptionGroups.contains(val)),
                        eq(TEST_PACKAGE_NAME));

        doAnswer(invocation -> {
            return subIdToGroupMap.get(invocation.getArgument(0));
        }).when(snapshot).getGroupForSubId(anyInt());

        doAnswer(invocation -> {
            final ParcelUuid subGrp = invocation.getArgument(0);
            final Set<Integer> subIds = new ArraySet<>();
            for (Entry<Integer, ParcelUuid> entry : subIdToGroupMap.entrySet()) {
                if (entry.getValue().equals(subGrp)) {
                    subIds.add(entry.getKey());
                }
            }
            return subIds;
        }).when(snapshot).getAllSubIdsInGroup(any());

        return snapshot;
    }

    private void setupActiveSubscription(ParcelUuid activeDataSubGrp) {
        mVcnMgmtSvc.setLastSnapshot(
                buildSubscriptionSnapshot(
                        TEST_SUBSCRIPTION_ID,
                        activeDataSubGrp,
                        Collections.emptySet(),
                        Collections.emptyMap(),
                        true /* hasCarrierPrivileges */));
    }

    private TelephonySubscriptionTrackerCallback getTelephonySubscriptionTrackerCallback() {
        final ArgumentCaptor<TelephonySubscriptionTrackerCallback> captor =
                ArgumentCaptor.forClass(TelephonySubscriptionTrackerCallback.class);
        verify(mMockDeps)
                .newTelephonySubscriptionTracker(
                        eq(mMockContext), eq(mTestLooper.getLooper()), captor.capture());
        return captor.getValue();
    }

    private BroadcastReceiver getPackageChangeReceiver() {
        final ArgumentCaptor<BroadcastReceiver> captor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext).registerReceiver(captor.capture(), argThat(filter -> {
            return filter.hasAction(Intent.ACTION_PACKAGE_ADDED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_REPLACED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_REMOVED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_DATA_CLEARED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        }), any(), any());
        return captor.getValue();
    }

    private Vcn startAndGetVcnInstance(ParcelUuid uuid) {
        mVcnMgmtSvc.setVcnConfig(uuid, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        return mVcnMgmtSvc.getAllVcns().get(uuid);
    }

    @Test
    public void testTelephonyNetworkTrackerCallbackStartsInstances() throws Exception {
        // Add a record for a non-active SIM
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);

        TelephonySubscriptionSnapshot snapshot =
                triggerSubscriptionTrackerCbAndGetSnapshot(
                        TEST_UUID_1, new ArraySet<>(Arrays.asList(TEST_UUID_1, TEST_UUID_2)));
        verify(mMockDeps)
                .newVcnContext(
                        eq(mMockContext),
                        eq(mTestLooper.getLooper()),
                        any(VcnNetworkProvider.class),
                        anyBoolean());

        // Verify that only the VCN for the active data SIM was started.
        verify(mMockDeps)
                .newVcn(eq(mVcnContext), eq(TEST_UUID_1), eq(TEST_VCN_CONFIG), eq(snapshot), any());
        verify(mMockDeps, never())
                .newVcn(eq(mVcnContext), eq(TEST_UUID_2), eq(TEST_VCN_CONFIG), eq(snapshot), any());
    }

    @Test
    public void testTelephonyNetworkTrackerCallbackSwitchingActiveDataStartsAndStopsInstances()
            throws Exception {
        // Add a record for a non-active SIM
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        final Vcn vcn = startAndGetVcnInstance(TEST_UUID_1);

        TelephonySubscriptionSnapshot snapshot =
                triggerSubscriptionTrackerCbAndGetSnapshot(
                        TEST_UUID_2, new ArraySet<>(Arrays.asList(TEST_UUID_1, TEST_UUID_2)));

        // Verify that a new VCN for UUID_2 was started, and the old instance was torn down
        // immediately
        verify(mMockDeps)
                .newVcn(eq(mVcnContext), eq(TEST_UUID_2), eq(TEST_VCN_CONFIG), eq(snapshot), any());
        verify(vcn).teardownAsynchronously();
        assertEquals(1, mVcnMgmtSvc.getAllVcns().size());
        assertFalse(mVcnMgmtSvc.getAllVcns().containsKey(TEST_UUID_1));
        assertTrue(mVcnMgmtSvc.getAllVcns().containsKey(TEST_UUID_2));
    }

    @Test
    public void testTelephonyNetworkTrackerCallbackStopsInstances() throws Exception {
        setupActiveSubscription(TEST_UUID_2);

        final TelephonySubscriptionTrackerCallback cb = getTelephonySubscriptionTrackerCallback();
        final Vcn vcn = startAndGetVcnInstance(TEST_UUID_2);
        mVcnMgmtSvc.addVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        triggerSubscriptionTrackerCbAndGetSnapshot(null, Collections.emptySet());
        mTestLooper.dispatchAll();

        verify(vcn).teardownAsynchronously();
        verify(mMockPolicyListener).onPolicyChanged();
    }

    @Test
    public void testTelephonyNetworkTrackerCallbackSwitchToNewSubscriptionImmediatelyTearsDown()
            throws Exception {
        setupActiveSubscription(TEST_UUID_2);

        final TelephonySubscriptionTrackerCallback cb = getTelephonySubscriptionTrackerCallback();
        final Vcn vcn = startAndGetVcnInstance(TEST_UUID_2);

        // Simulate switch to different default data subscription that does not have a VCN.
        triggerSubscriptionTrackerCbAndGetSnapshot(
                TEST_SUBSCRIPTION_ID,
                null /* activeDataSubscriptionGroup */,
                Collections.emptySet(),
                Collections.emptyMap(),
                false /* hasCarrierPrivileges */);
        mTestLooper.dispatchAll();

        verify(vcn).teardownAsynchronously();
        assertEquals(0, mVcnMgmtSvc.getAllVcns().size());
    }

    @Test
    public void testPackageChangeListenerRegistered() throws Exception {
        verify(mMockContext).registerReceiver(any(BroadcastReceiver.class), argThat(filter -> {
            return filter.hasAction(Intent.ACTION_PACKAGE_ADDED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_REPLACED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_REMOVED);
        }), any(), any());
    }

    @Test
    public void testPackageChangeListener_packageAdded() throws Exception {
        final BroadcastReceiver receiver = getPackageChangeReceiver();

        verify(mMockContext).registerReceiver(any(), argThat(filter -> {
            return filter.hasAction(Intent.ACTION_PACKAGE_ADDED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_REPLACED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_REMOVED);
        }), any(), any());

        receiver.onReceive(mMockContext, new Intent(Intent.ACTION_PACKAGE_ADDED));
        verify(mSubscriptionTracker).handleSubscriptionsChanged();
    }

    @Test
    public void testPackageChangeListener_packageRemoved() throws Exception {
        final BroadcastReceiver receiver = getPackageChangeReceiver();

        verify(mMockContext).registerReceiver(any(), argThat(filter -> {
            return filter.hasAction(Intent.ACTION_PACKAGE_REMOVED)
                    && filter.hasAction(Intent.ACTION_PACKAGE_REMOVED);
        }), any(), any());

        receiver.onReceive(mMockContext, new Intent(Intent.ACTION_PACKAGE_REMOVED));
        verify(mSubscriptionTracker).handleSubscriptionsChanged();
    }

    @Test
    public void testPackageChangeListener_packageDataCleared() throws Exception {
        triggerSubscriptionTrackerCbAndGetSnapshot(TEST_UUID_1, Collections.singleton(TEST_UUID_1));
        final Vcn vcn = mVcnMgmtSvc.getAllVcns().get(TEST_UUID_1);

        final BroadcastReceiver receiver = getPackageChangeReceiver();
        assertEquals(TEST_VCN_CONFIG_MAP, mVcnMgmtSvc.getConfigs());

        final Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED);
        intent.setData(Uri.parse("package:" + TEST_PACKAGE_NAME));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(TEST_UID));

        receiver.onReceive(mMockContext, intent);
        mTestLooper.dispatchAll();
        verify(vcn).teardownAsynchronously();
        assertTrue(mVcnMgmtSvc.getConfigs().isEmpty());
        verify(mConfigReadWriteHelper).writeToDisk(any(PersistableBundle.class));
    }

    @Test
    public void testPackageChangeListener_packageFullyRemoved() throws Exception {
        triggerSubscriptionTrackerCbAndGetSnapshot(TEST_UUID_1, Collections.singleton(TEST_UUID_1));
        final Vcn vcn = mVcnMgmtSvc.getAllVcns().get(TEST_UUID_1);

        final BroadcastReceiver receiver = getPackageChangeReceiver();
        assertEquals(TEST_VCN_CONFIG_MAP, mVcnMgmtSvc.getConfigs());

        final Intent intent = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intent.setData(Uri.parse("package:" + TEST_PACKAGE_NAME));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(TEST_UID));

        receiver.onReceive(mMockContext, intent);
        mTestLooper.dispatchAll();
        verify(vcn).teardownAsynchronously();
        assertTrue(mVcnMgmtSvc.getConfigs().isEmpty());
        verify(mConfigReadWriteHelper).writeToDisk(any(PersistableBundle.class));
    }

    @Test
    public void testSetVcnConfigRequiresNonSystemServer() throws Exception {
        doReturn(Process.SYSTEM_UID).when(mMockDeps).getBinderCallingUid();

        try {
            mVcnMgmtSvc.setVcnConfig(TEST_UUID_1, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
            fail("Expected IllegalStateException exception for system server");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testSetVcnConfigRequiresSystemUser() throws Exception {
        doReturn(UserHandle.getUid(UserHandle.MIN_SECONDARY_USER_ID, TEST_UID))
                .when(mMockDeps)
                .getBinderCallingUid();

        try {
            mVcnMgmtSvc.setVcnConfig(TEST_UUID_1, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
            fail("Expected security exception for non system user");
        } catch (SecurityException expected) {
            verify(mMockPolicyListener, never()).onPolicyChanged();
        }
    }

    @Test
    public void testSetVcnConfigRequiresCarrierPrivileges() throws Exception {
        setupMockedCarrierPrivilege(false);

        try {
            mVcnMgmtSvc.setVcnConfig(TEST_UUID_1, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
            fail("Expected security exception for missing carrier privileges");
        } catch (SecurityException expected) {
            verify(mMockPolicyListener, never()).onPolicyChanged();
        }
    }

    @Test
    public void testSetVcnConfigMismatchedPackages() throws Exception {
        try {
            mVcnMgmtSvc.setVcnConfig(TEST_UUID_1, TEST_VCN_CONFIG, TEST_PACKAGE_NAME_2);
            fail("Expected exception due to mismatched packages in config and method call");
        } catch (IllegalArgumentException expected) {
            verify(mMockPolicyListener, never()).onPolicyChanged();
        }
    }

    @Test
    public void testSetVcnConfig() throws Exception {
        // Use a different UUID to simulate a new VCN config.
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        assertEquals(TEST_VCN_CONFIG, mVcnMgmtSvc.getConfigs().get(TEST_UUID_2));
        verify(mConfigReadWriteHelper).writeToDisk(any(PersistableBundle.class));
    }

    @Test
    public void testSetVcnConfigNonActiveSimDoesNotStartVcn() throws Exception {
        // Use a different UUID to simulate a new VCN config.
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        assertEquals(TEST_VCN_CONFIG, mVcnMgmtSvc.getConfigs().get(TEST_UUID_2));
        verify(mConfigReadWriteHelper).writeToDisk(any(PersistableBundle.class));

        verify(mMockDeps, never()).newVcn(any(), any(), any(), any(), any());
    }

    @Test
    public void testSetVcnConfigActiveSimTearsDownExistingVcnsImmediately() throws Exception {
        final Vcn vcn = startAndGetVcnInstance(TEST_UUID_1);

        // Use a different UUID to simulate a new VCN config.
        setupActiveSubscription(TEST_UUID_2);
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);

        verify(mMockDeps, times(2)).newVcn(any(), any(), any(), any(), any());
        verify(vcn).teardownAsynchronously();
        assertEquals(1, mVcnMgmtSvc.getAllVcns().size());
        assertFalse(mVcnMgmtSvc.getAllVcns().containsKey(TEST_UUID_1));
        assertTrue(mVcnMgmtSvc.getAllVcns().containsKey(TEST_UUID_2));
    }

    @Test
    public void testSetVcnConfigTestModeRequiresPermission() throws Exception {
        doThrow(new SecurityException("Requires MANAGE_TEST_NETWORKS"))
                .when(mMockContext)
                .enforceCallingPermission(
                        eq(android.Manifest.permission.MANAGE_TEST_NETWORKS), any());

        final VcnConfig vcnConfig =
                new VcnConfig.Builder(mMockContext)
                        .addGatewayConnectionConfig(
                                VcnGatewayConnectionConfigTest.buildTestConfig())
                        .setIsTestModeProfile()
                        .build();

        try {
            mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, vcnConfig, TEST_PACKAGE_NAME);
            fail("Expected exception due to using test-mode without permission");
        } catch (SecurityException e) {
            verify(mMockPolicyListener, never()).onPolicyChanged();
        }
    }

    @Test
    public void testSetVcnConfigNotifiesStatusCallback() throws Exception {
        triggerSubscriptionTrackerCbAndGetSnapshot(TEST_UUID_2, Collections.singleton(TEST_UUID_2));

        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_2, mMockStatusCallback, TEST_PACKAGE_NAME);
        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_NOT_CONFIGURED);

        // Use a different UUID to simulate a new VCN config.
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);

        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_ACTIVE);
    }

    @Test
    public void testClearVcnConfigRequiresNonSystemServer() throws Exception {
        doReturn(Process.SYSTEM_UID).when(mMockDeps).getBinderCallingUid();

        try {
            mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1, TEST_PACKAGE_NAME);
            fail("Expected IllegalStateException exception for system server");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testClearVcnConfigRequiresSystemUser() throws Exception {
        doReturn(UserHandle.getUid(UserHandle.MIN_SECONDARY_USER_ID, TEST_UID))
                .when(mMockDeps)
                .getBinderCallingUid();

        try {
            mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1, TEST_PACKAGE_NAME);
            fail("Expected security exception for non system user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testClearVcnConfigRequiresCarrierPrivilegesOrProvisioningPackage()
            throws Exception {
        setupMockedCarrierPrivilege(false);

        try {
            mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1, TEST_PACKAGE_NAME_2);
            fail("Expected security exception for missing carrier privileges");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testClearVcnConfigMismatchedPackages() throws Exception {
        try {
            mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1, TEST_PACKAGE_NAME_2);
            fail("Expected security exception due to mismatched packages");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testClearVcnConfig_callerIsProvisioningPackage() throws Exception {
        // Lose carrier privileges to test that provisioning package is sufficient.
        setupMockedCarrierPrivilege(false);

        mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1, TEST_PACKAGE_NAME);
        assertTrue(mVcnMgmtSvc.getConfigs().isEmpty());
        verify(mConfigReadWriteHelper).writeToDisk(any(PersistableBundle.class));
    }

    @Test
    public void testClearVcnConfig_callerIsCarrierPrivileged() throws Exception {
        setupMockedCarrierPrivilege(true, TEST_PACKAGE_NAME_2);

        mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1, TEST_PACKAGE_NAME_2);
        assertTrue(mVcnMgmtSvc.getConfigs().isEmpty());
        verify(mConfigReadWriteHelper).writeToDisk(any(PersistableBundle.class));
    }

    @Test
    public void testClearVcnConfigNotifiesStatusCallback() throws Exception {
        setupSubscriptionAndStartVcn(TEST_SUBSCRIPTION_ID, TEST_UUID_2, true /* isActive */);
        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_2, mMockStatusCallback, TEST_PACKAGE_NAME);
        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_ACTIVE);

        mVcnMgmtSvc.clearVcnConfig(TEST_UUID_2, TEST_PACKAGE_NAME);

        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_NOT_CONFIGURED);
    }

    @Test
    public void testSetVcnConfigClearVcnConfigStartsUpdatesAndTearsDownVcns() throws Exception {
        setupActiveSubscription(TEST_UUID_2);

        // Use a different UUID to simulate a new VCN config.
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        final Map<ParcelUuid, Vcn> vcnInstances = mVcnMgmtSvc.getAllVcns();
        final Vcn vcnInstance = vcnInstances.get(TEST_UUID_2);
        assertEquals(1, vcnInstances.size());
        assertEquals(TEST_VCN_CONFIG, mVcnMgmtSvc.getConfigs().get(TEST_UUID_2));
        verify(mConfigReadWriteHelper).writeToDisk(any(PersistableBundle.class));

        // Verify Vcn is started
        verify(mMockDeps)
                .newVcn(eq(mVcnContext), eq(TEST_UUID_2), eq(TEST_VCN_CONFIG), any(), any());

        // Verify Vcn is updated if it was previously started
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        verify(vcnInstance).updateConfig(TEST_VCN_CONFIG);

        // Verify Vcn is stopped if it was already started
        mVcnMgmtSvc.clearVcnConfig(TEST_UUID_2, TEST_PACKAGE_NAME);
        verify(vcnInstance).teardownAsynchronously();
    }

    @Test
    public void testGetConfiguredSubscriptionGroupsRequiresSystemUser() throws Exception {
        doReturn(UserHandle.getUid(UserHandle.MIN_SECONDARY_USER_ID, TEST_UID))
                .when(mMockDeps)
                .getBinderCallingUid();

        try {
            mVcnMgmtSvc.getConfiguredSubscriptionGroups(TEST_PACKAGE_NAME);
            fail("Expected security exception for non system user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testGetConfiguredSubscriptionGroupsMismatchedPackages() throws Exception {
        doThrow(new SecurityException())
                .when(mAppOpsMgr)
                .checkPackage(TEST_UID, TEST_PACKAGE_NAME_2);

        try {
            mVcnMgmtSvc.getConfiguredSubscriptionGroups(TEST_PACKAGE_NAME_2);
            fail("Expected security exception due to mismatched packages");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testGetConfiguredSubscriptionGroups() throws Exception {
        setupMockedCarrierPrivilege(true, TEST_PACKAGE_NAME_2);
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        mVcnMgmtSvc.setVcnConfig(TEST_UUID_3, TEST_VCN_CONFIG_PKG_2, TEST_PACKAGE_NAME_2);

        // Assert that if UUIDs 1, 2 and 3 are provisioned, the caller only gets ones that they are
        // privileged for, or are the provisioning package of.
        triggerSubscriptionTrackerCbAndGetSnapshot(TEST_UUID_1, Collections.singleton(TEST_UUID_1));
        final List<ParcelUuid> subGrps =
                mVcnMgmtSvc.getConfiguredSubscriptionGroups(TEST_PACKAGE_NAME);
        assertEquals(Arrays.asList(new ParcelUuid[] {TEST_UUID_1, TEST_UUID_2}), subGrps);
    }

    @Test
    public void testAddVcnUnderlyingNetworkPolicyListener() throws Exception {
        mVcnMgmtSvc.addVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        verify(mMockIBinder).linkToDeath(any(), anyInt());
    }

    @Test(expected = SecurityException.class)
    public void testAddVcnUnderlyingNetworkPolicyListenerInvalidPermission() {
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mMockContext)
                .checkCallingOrSelfPermission(any());

        mVcnMgmtSvc.addVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);
    }

    @Test
    public void testRemoveVcnUnderlyingNetworkPolicyListener() {
        mVcnMgmtSvc.addVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        mVcnMgmtSvc.removeVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);
    }

    @Test(expected = SecurityException.class)
    public void testRemoveVcnUnderlyingNetworkPolicyListenerInvalidPermission() {
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mMockContext)
                .checkCallingOrSelfPermission(any());

        mVcnMgmtSvc.removeVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);
    }

    @Test
    public void testRemoveVcnUnderlyingNetworkPolicyListenerNeverRegistered() {
        mVcnMgmtSvc.removeVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);
    }

    private void verifyMergedNetworkCapabilities(
            NetworkCapabilities mergedCapabilities,
            int transportType,
            boolean isVcnManaged,
            boolean isRestricted) {
        assertTrue(mergedCapabilities.hasTransport(transportType));
        assertEquals(
                !isVcnManaged,
                mergedCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED));
        assertEquals(
                !isRestricted,
                mergedCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED));
    }

    private void setupSubscriptionAndStartVcn(int subId, ParcelUuid subGrp, boolean isVcnActive) {
        setupSubscriptionAndStartVcn(subId, subGrp, isVcnActive, true /* hasCarrierPrivileges */);
    }

    private void setupSubscriptionAndStartVcn(
            int subId, ParcelUuid subGrp, boolean isVcnActive, boolean hasCarrierPrivileges) {
        mVcnMgmtSvc.systemReady();
        mTestLooper.dispatchAll();

        triggerSubscriptionTrackerCbAndGetSnapshot(
                subGrp,
                Collections.singleton(subGrp),
                Collections.singletonMap(subId, subGrp),
                hasCarrierPrivileges);

        final Vcn vcn = startAndGetVcnInstance(subGrp);
        doReturn(isVcnActive ? VCN_STATUS_CODE_ACTIVE : VCN_STATUS_CODE_SAFE_MODE)
                .when(vcn)
                .getStatus();
    }

    private NetworkCapabilities.Builder getNetworkCapabilitiesBuilderForTransport(
            int subId, int transport) {
        final NetworkCapabilities.Builder ncBuilder =
                new NetworkCapabilities.Builder()
                        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                        .addTransportType(transport);
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            ncBuilder.setSubscriptionIds(Collections.singleton(subId));
        }

        return ncBuilder;
    }

    private VcnUnderlyingNetworkPolicy startVcnAndGetPolicyForTransport(
            int subId, ParcelUuid subGrp, boolean isVcnActive, int transport) {
        setupSubscriptionAndStartVcn(subId, subGrp, isVcnActive);

        return mVcnMgmtSvc.getUnderlyingNetworkPolicy(
                getNetworkCapabilitiesBuilderForTransport(subId, transport).build(),
                new LinkProperties());
    }

    @Test
    public void testGetUnderlyingNetworkPolicyCellular() throws Exception {
        final VcnUnderlyingNetworkPolicy policy =
                startVcnAndGetPolicyForTransport(
                        TEST_SUBSCRIPTION_ID, TEST_UUID_2, true /* isActive */, TRANSPORT_CELLULAR);

        assertFalse(policy.isTeardownRequested());
        verifyMergedNetworkCapabilities(
                policy.getMergedNetworkCapabilities(),
                TRANSPORT_CELLULAR,
                true /* isVcnManaged */,
                false /* isRestricted */);
    }

    @Test
    public void testGetUnderlyingNetworkPolicyCellular_safeMode() throws Exception {
        final VcnUnderlyingNetworkPolicy policy =
                startVcnAndGetPolicyForTransport(
                        TEST_SUBSCRIPTION_ID,
                        TEST_UUID_2,
                        false /* isActive */,
                        TRANSPORT_CELLULAR);

        assertFalse(policy.isTeardownRequested());
        verifyMergedNetworkCapabilities(
                policy.getMergedNetworkCapabilities(),
                NetworkCapabilities.TRANSPORT_CELLULAR,
                false /* isVcnManaged */,
                false /* isRestricted */);
    }

    @Test
    public void testGetUnderlyingNetworkPolicyWifi() throws Exception {
        final VcnUnderlyingNetworkPolicy policy =
                startVcnAndGetPolicyForTransport(
                        TEST_SUBSCRIPTION_ID, TEST_UUID_2, true /* isActive */, TRANSPORT_WIFI);

        assertFalse(policy.isTeardownRequested());
        verifyMergedNetworkCapabilities(
                policy.getMergedNetworkCapabilities(),
                NetworkCapabilities.TRANSPORT_WIFI,
                true /* isVcnManaged */,
                true /* isRestricted */);
    }

    @Test
    public void testGetUnderlyingNetworkPolicyVcnWifi_safeMode() throws Exception {
        final VcnUnderlyingNetworkPolicy policy =
                startVcnAndGetPolicyForTransport(
                        TEST_SUBSCRIPTION_ID, TEST_UUID_2, false /* isActive */, TRANSPORT_WIFI);

        assertFalse(policy.isTeardownRequested());
        verifyMergedNetworkCapabilities(
                policy.getMergedNetworkCapabilities(),
                NetworkCapabilities.TRANSPORT_WIFI,
                false /* isVcnManaged */,
                true /* isRestricted */);
    }

    private void setupTrackedCarrierWifiNetwork(NetworkCapabilities caps) {
        mVcnMgmtSvc.systemReady();
        mTestLooper.dispatchAll();

        final ArgumentCaptor<NetworkCallback> captor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mConnMgr)
                .registerNetworkCallback(
                        eq(new NetworkRequest.Builder().clearCapabilities().build()),
                        captor.capture());
        captor.getValue().onCapabilitiesChanged(mock(Network.class, CALLS_REAL_METHODS), caps);
    }

    @Test
    public void testGetUnderlyingNetworkPolicyVcnWifi_unrestrictingExistingNetworkRequiresRestart()
            throws Exception {
        final NetworkCapabilities existingNetworkCaps =
                getNetworkCapabilitiesBuilderForTransport(TEST_SUBSCRIPTION_ID, TRANSPORT_WIFI)
                        .removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
                        .build();
        setupTrackedCarrierWifiNetwork(existingNetworkCaps);

        // Trigger test without VCN instance alive; expect restart due to change of NOT_RESTRICTED
        // immutable capability
        final VcnUnderlyingNetworkPolicy policy =
                mVcnMgmtSvc.getUnderlyingNetworkPolicy(
                        getNetworkCapabilitiesBuilderForTransport(
                                        TEST_SUBSCRIPTION_ID, TRANSPORT_WIFI)
                                .build(),
                        new LinkProperties());
        assertTrue(policy.isTeardownRequested());
    }

    @Test
    public void testGetUnderlyingNetworkPolicyVcnWifi_restrictingExistingNetworkRequiresRestart()
            throws Exception {
        final NetworkCapabilities existingNetworkCaps =
                getNetworkCapabilitiesBuilderForTransport(TEST_SUBSCRIPTION_ID, TRANSPORT_WIFI)
                        .build();
        setupTrackedCarrierWifiNetwork(existingNetworkCaps);

        final VcnUnderlyingNetworkPolicy policy =
                startVcnAndGetPolicyForTransport(
                        TEST_SUBSCRIPTION_ID, TEST_UUID_2, false /* isActive */, TRANSPORT_WIFI);

        assertTrue(policy.isTeardownRequested());
    }

    @Test
    public void testGetUnderlyingNetworkPolicyNonVcnNetwork() throws Exception {
        setupSubscriptionAndStartVcn(TEST_SUBSCRIPTION_ID, TEST_UUID_1, true /* isActive */);

        NetworkCapabilities nc =
                new NetworkCapabilities.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                        .setNetworkSpecifier(new TelephonyNetworkSpecifier(TEST_SUBSCRIPTION_ID_2))
                        .build();

        VcnUnderlyingNetworkPolicy policy =
                mVcnMgmtSvc.getUnderlyingNetworkPolicy(nc, new LinkProperties());

        assertFalse(policy.isTeardownRequested());
        assertEquals(nc, policy.getMergedNetworkCapabilities());
    }

    @Test(expected = SecurityException.class)
    public void testGetUnderlyingNetworkPolicyInvalidPermission() {
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mMockContext)
                .checkCallingOrSelfPermission(any());

        mVcnMgmtSvc.getUnderlyingNetworkPolicy(new NetworkCapabilities(), new LinkProperties());
    }

    @Test
    public void testSubscriptionSnapshotUpdateNotifiesVcn() {
        setupActiveSubscription(TEST_UUID_2);

        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        final Map<ParcelUuid, Vcn> vcnInstances = mVcnMgmtSvc.getAllVcns();
        final Vcn vcnInstance = vcnInstances.get(TEST_UUID_2);

        TelephonySubscriptionSnapshot snapshot =
                triggerSubscriptionTrackerCbAndGetSnapshot(
                        TEST_UUID_2, Collections.singleton(TEST_UUID_2));

        verify(vcnInstance).updateSubscriptionSnapshot(eq(snapshot));
    }

    @Test
    public void testAddNewVcnUpdatesPolicyListener() throws Exception {
        setupActiveSubscription(TEST_UUID_2);

        mVcnMgmtSvc.addVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);

        verify(mMockPolicyListener).onPolicyChanged();
    }

    @Test
    public void testRemoveVcnUpdatesPolicyListener() throws Exception {
        setupActiveSubscription(TEST_UUID_2);

        mVcnMgmtSvc.setVcnConfig(TEST_UUID_2, TEST_VCN_CONFIG, TEST_PACKAGE_NAME);
        mVcnMgmtSvc.addVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        mVcnMgmtSvc.clearVcnConfig(TEST_UUID_2, TEST_PACKAGE_NAME);

        verify(mMockPolicyListener).onPolicyChanged();
    }

    @Test
    public void testVcnSubIdChangeUpdatesPolicyListener() throws Exception {
        setupActiveSubscription(TEST_UUID_2);

        startAndGetVcnInstance(TEST_UUID_2);
        mVcnMgmtSvc.addVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        triggerSubscriptionTrackerCbAndGetSnapshot(
                TEST_UUID_2,
                Collections.singleton(TEST_UUID_2),
                Collections.singletonMap(TEST_SUBSCRIPTION_ID, TEST_UUID_2));

        verify(mMockPolicyListener).onPolicyChanged();
    }

    private void triggerVcnSafeMode(
            @NonNull ParcelUuid subGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            boolean isInSafeMode)
            throws Exception {
        verify(mMockDeps)
                .newVcn(
                        eq(mVcnContext),
                        eq(subGroup),
                        eq(TEST_VCN_CONFIG),
                        eq(snapshot),
                        mVcnCallbackCaptor.capture());

        VcnCallback vcnCallback = mVcnCallbackCaptor.getValue();
        vcnCallback.onSafeModeStatusChanged(isInSafeMode);
    }

    private void verifyVcnSafeModeChangesNotifiesPolicyListeners(boolean enterSafeMode)
            throws Exception {
        TelephonySubscriptionSnapshot snapshot =
                triggerSubscriptionTrackerCbAndGetSnapshot(
                        TEST_UUID_1, Collections.singleton(TEST_UUID_1));

        mVcnMgmtSvc.addVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        triggerVcnSafeMode(TEST_UUID_1, snapshot, enterSafeMode);

        verify(mMockPolicyListener).onPolicyChanged();
    }

    @Test
    public void testVcnEnteringSafeModeNotifiesPolicyListeners() throws Exception {
        verifyVcnSafeModeChangesNotifiesPolicyListeners(true /* enterSafeMode */);
    }

    @Test
    public void testVcnExitingSafeModeNotifiesPolicyListeners() throws Exception {
        verifyVcnSafeModeChangesNotifiesPolicyListeners(false /* enterSafeMode */);
    }

    private void triggerVcnStatusCallbackOnSafeModeStatusChanged(
            @NonNull ParcelUuid subGroup,
            @NonNull String pkgName,
            int uid,
            boolean hasPermissionsforSubGroup)
            throws Exception {
        TelephonySubscriptionSnapshot snapshot =
                triggerSubscriptionTrackerCbAndGetSnapshot(
                        subGroup, Collections.singleton(subGroup));

        setupSubscriptionAndStartVcn(
                TEST_SUBSCRIPTION_ID, subGroup, true /* isActive */, hasPermissionsforSubGroup);

        doReturn(hasPermissionsforSubGroup)
                .when(snapshot)
                .packageHasPermissionsForSubscriptionGroup(eq(subGroup), eq(pkgName));

        mVcnMgmtSvc.registerVcnStatusCallback(subGroup, mMockStatusCallback, pkgName);

        triggerVcnSafeMode(subGroup, snapshot, true /* enterSafeMode */);
    }

    @Test
    public void testVcnStatusCallbackOnSafeModeStatusChangedWithCarrierPrivileges()
            throws Exception {
        triggerVcnStatusCallbackOnSafeModeStatusChanged(
                TEST_UUID_1, TEST_PACKAGE_NAME, TEST_UID, true /* hasPermissionsforSubGroup */);

        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_SAFE_MODE);
    }

    @Test
    public void testVcnStatusCallbackOnSafeModeStatusChangedWithoutCarrierPrivileges()
            throws Exception {
        triggerVcnStatusCallbackOnSafeModeStatusChanged(
                TEST_UUID_1, TEST_PACKAGE_NAME, TEST_UID, false /* hasPermissionsforSubGroup */);

        verify(mMockStatusCallback, never())
                .onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_SAFE_MODE);
    }

    @Test
    public void testRegisterVcnStatusCallback() throws Exception {
        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_1, mMockStatusCallback, TEST_PACKAGE_NAME);

        Map<IBinder, VcnStatusCallbackInfo> callbacks = mVcnMgmtSvc.getAllStatusCallbacks();
        VcnStatusCallbackInfo cbInfo = callbacks.get(mMockIBinder);

        assertNotNull(cbInfo);
        assertEquals(TEST_UUID_1, cbInfo.mSubGroup);
        assertEquals(mMockStatusCallback, cbInfo.mCallback);
        assertEquals(TEST_PACKAGE_NAME, cbInfo.mPkgName);
        assertEquals(TEST_UID, cbInfo.mUid);
        verify(mMockIBinder).linkToDeath(eq(cbInfo), anyInt());

        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_NOT_CONFIGURED);
    }

    @Test
    public void testRegisterVcnStatusCallback_MissingPermission() throws Exception {
        setupSubscriptionAndStartVcn(
                TEST_SUBSCRIPTION_ID,
                TEST_UUID_1,
                true /* isActive */,
                false /* hasCarrierPrivileges */);

        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_1, mMockStatusCallback, TEST_PACKAGE_NAME);

        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_NOT_CONFIGURED);
    }

    @Test
    public void testRegisterVcnStatusCallback_VcnInactive() throws Exception {
        setupSubscriptionAndStartVcn(
                TEST_SUBSCRIPTION_ID,
                TEST_UUID_1,
                true /* isActive */,
                true /* hasCarrierPrivileges */);

        // VCN is currently active. Lose carrier privileges for TEST_PACKAGE so the VCN goes
        // inactive.
        final TelephonySubscriptionSnapshot snapshot =
                triggerSubscriptionTrackerCbAndGetSnapshot(
                        TEST_UUID_1,
                        Collections.singleton(TEST_UUID_1),
                        Collections.singletonMap(TEST_SUBSCRIPTION_ID, TEST_UUID_1),
                        false /* hasCarrierPrivileges */);
        mTestLooper.dispatchAll();

        // Giving TEST_PACKAGE privileges again will restart the VCN (which will indicate ACTIVE
        // when the status callback is registered). Instead, setup permissions for TEST_CB_PACKAGE
        // so that it's permissioned to receive INACTIVE (instead of NOT_CONFIGURED) without
        // reactivating the VCN.
        doReturn(true)
                .when(snapshot)
                .packageHasPermissionsForSubscriptionGroup(
                        eq(TEST_UUID_1), eq(TEST_CB_PACKAGE_NAME));

        mVcnMgmtSvc.registerVcnStatusCallback(
                TEST_UUID_1, mMockStatusCallback, TEST_CB_PACKAGE_NAME);

        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_INACTIVE);
    }

    @Test
    public void testRegisterVcnStatusCallback_VcnActive() throws Exception {
        setupSubscriptionAndStartVcn(
                TEST_SUBSCRIPTION_ID,
                TEST_UUID_1,
                true /* isActive */,
                true /* hasCarrierPrivileges */);

        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_1, mMockStatusCallback, TEST_PACKAGE_NAME);

        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_ACTIVE);
    }

    @Test
    public void testRegisterVcnStatusCallback_VcnSafeMode() throws Exception {
        setupSubscriptionAndStartVcn(
                TEST_SUBSCRIPTION_ID,
                TEST_UUID_1,
                false /* isActive */,
                true /* hasCarrierPrivileges */);

        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_1, mMockStatusCallback, TEST_PACKAGE_NAME);

        verify(mMockStatusCallback).onVcnStatusChanged(VcnManager.VCN_STATUS_CODE_SAFE_MODE);
    }

    @Test(expected = IllegalStateException.class)
    public void testRegisterVcnStatusCallbackDuplicate() {
        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_1, mMockStatusCallback, TEST_PACKAGE_NAME);
        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_1, mMockStatusCallback, TEST_PACKAGE_NAME);
    }

    @Test
    public void testUnregisterVcnStatusCallback() {
        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_1, mMockStatusCallback, TEST_PACKAGE_NAME);
        Map<IBinder, VcnStatusCallbackInfo> callbacks = mVcnMgmtSvc.getAllStatusCallbacks();
        VcnStatusCallbackInfo cbInfo = callbacks.get(mMockIBinder);

        mVcnMgmtSvc.unregisterVcnStatusCallback(mMockStatusCallback);
        assertTrue(mVcnMgmtSvc.getAllStatusCallbacks().isEmpty());
        verify(mMockIBinder).unlinkToDeath(eq(cbInfo), anyInt());
    }

    @Test(expected = SecurityException.class)
    public void testRegisterVcnStatusCallbackInvalidPackage() {
        doThrow(new SecurityException()).when(mAppOpsMgr).checkPackage(TEST_UID, TEST_PACKAGE_NAME);

        mVcnMgmtSvc.registerVcnStatusCallback(TEST_UUID_1, mMockStatusCallback, TEST_PACKAGE_NAME);
    }

    @Test
    public void testUnregisterVcnStatusCallbackNeverRegistered() {
        mVcnMgmtSvc.unregisterVcnStatusCallback(mMockStatusCallback);

        assertTrue(mVcnMgmtSvc.getAllStatusCallbacks().isEmpty());
    }
}
