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

import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.vcn.VcnConfig;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.UUID;

/** Tests for {@link VcnManagementService}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnManagementServiceTest {
    private static final ParcelUuid TEST_UUID_1 = new ParcelUuid(new UUID(0, 0));
    private static final SubscriptionInfo TEST_SUBSCRIPTION_INFO =
            new SubscriptionInfo(
                    1 /* id */,
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
    private final VcnManagementService mVcnMgmtSvc;

    public VcnManagementServiceTest() throws Exception {
        setupSystemService(mConnMgr, Context.CONNECTIVITY_SERVICE, ConnectivityManager.class);
        setupSystemService(mTelMgr, Context.TELEPHONY_SERVICE, TelephonyManager.class);
        setupSystemService(
                mSubMgr, Context.TELEPHONY_SUBSCRIPTION_SERVICE, SubscriptionManager.class);

        doReturn(mTestLooper.getLooper()).when(mMockDeps).getLooper();
        doReturn(Process.FIRST_APPLICATION_UID).when(mMockDeps).getBinderCallingUid();

        setupMockedCarrierPrivilege(true);
        mVcnMgmtSvc = new VcnManagementService(mMockContext, mMockDeps);
    }

    private void setupSystemService(Object service, String name, Class<?> serviceClass) {
        doReturn(name).when(mMockContext).getSystemServiceName(serviceClass);
        doReturn(service).when(mMockContext).getSystemService(name);
    }

    private void setupMockedCarrierPrivilege(boolean isPrivileged) {
        doReturn(Collections.singletonList(TEST_SUBSCRIPTION_INFO))
                .when(mSubMgr)
                .getSubscriptionsInGroup(any());
        doReturn(isPrivileged)
                .when(mTelMgr)
                .hasCarrierPrivileges(eq(TEST_SUBSCRIPTION_INFO.getSubscriptionId()));
    }

    @Test
    public void testSystemReady() throws Exception {
        mVcnMgmtSvc.systemReady();

        verify(mConnMgr)
                .registerNetworkProvider(any(VcnManagementService.VcnNetworkProvider.class));
    }

    @Test
    public void testSetVcnConfigRequiresNonSystemServer() throws Exception {
        doReturn(Process.SYSTEM_UID).when(mMockDeps).getBinderCallingUid();

        try {
            mVcnMgmtSvc.setVcnConfig(TEST_UUID_1, new VcnConfig.Builder().build());
            fail("Expected IllegalStateException exception for system server");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testSetVcnConfigRequiresSystemUser() throws Exception {
        doReturn(UserHandle.getUid(UserHandle.MIN_SECONDARY_USER_ID, Process.FIRST_APPLICATION_UID))
                .when(mMockDeps)
                .getBinderCallingUid();

        try {
            mVcnMgmtSvc.setVcnConfig(TEST_UUID_1, new VcnConfig.Builder().build());
            fail("Expected security exception for non system user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testSetVcnConfigRequiresCarrierPrivileges() throws Exception {
        setupMockedCarrierPrivilege(false);

        try {
            mVcnMgmtSvc.setVcnConfig(TEST_UUID_1, new VcnConfig.Builder().build());
            fail("Expected security exception for missing carrier privileges");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testClearVcnConfigRequiresNonSystemServer() throws Exception {
        doReturn(Process.SYSTEM_UID).when(mMockDeps).getBinderCallingUid();

        try {
            mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1);
            fail("Expected IllegalStateException exception for system server");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testClearVcnConfigRequiresSystemUser() throws Exception {
        doReturn(UserHandle.getUid(UserHandle.MIN_SECONDARY_USER_ID, Process.FIRST_APPLICATION_UID))
                .when(mMockDeps)
                .getBinderCallingUid();

        try {
            mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1);
            fail("Expected security exception for non system user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testClearVcnConfigRequiresCarrierPrivileges() throws Exception {
        setupMockedCarrierPrivilege(false);

        try {
            mVcnMgmtSvc.clearVcnConfig(TEST_UUID_1);
            fail("Expected security exception for missing carrier privileges");
        } catch (SecurityException expected) {
        }
    }
}
