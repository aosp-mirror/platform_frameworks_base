/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.server.devicepolicy;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.devicepolicy.DevicePolicyManagerServiceTestable.OwnersTestable;

import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Pair;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

public class DevicePolicyManagerServiceMigrationTest extends DpmTestBase {
    private DpmMockContext mContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getContext();

        when(mContext.packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(true);
    }

    public void testMigration() throws Exception {
        final File user10dir = mMockContext.addUser(10, 0);
        final File user11dir = mMockContext.addUser(11, UserInfo.FLAG_MANAGED_PROFILE);
        final File user12dir = mMockContext.addUser(12, 0);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForAdmin(admin2, UserHandle.getUid(10, 123));
        setUpPackageManagerForAdmin(admin3, UserHandle.getUid(11, 456));

        // Create the legacy owners & policies file.
        DpmTestUtils.writeToFile(
                (new File(mContext.dataDir, OwnersTestable.LEGACY_FILE)).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest/legacy_device_owner.xml"));

        DpmTestUtils.writeToFile(
                (new File(mContext.systemUserDataDir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest/legacy_device_policies.xml"));

        DpmTestUtils.writeToFile(
                (new File(user10dir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest/legacy_device_policies_10.xml"));
        DpmTestUtils.writeToFile(
                (new File(user11dir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest/legacy_device_policies_11.xml"));

        // Set up UserManager
        when(mMockContext.userManagerInternal.getBaseUserRestrictions(
                eq(UserHandle.USER_SYSTEM))).thenReturn(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_RECORD_AUDIO));

        when(mMockContext.userManagerInternal.getBaseUserRestrictions(
                eq(10))).thenReturn(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_SMS,
                UserManager.DISALLOW_OUTGOING_CALLS,
                UserManager.DISALLOW_WALLPAPER,
                UserManager.DISALLOW_RECORD_AUDIO));

        when(mMockContext.userManagerInternal.getBaseUserRestrictions(
                eq(11))).thenReturn(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_SMS,
                UserManager.DISALLOW_OUTGOING_CALLS,
                UserManager.DISALLOW_WALLPAPER,
                UserManager.DISALLOW_RECORD_AUDIO));

        final Map<Integer, Bundle> newBaseRestrictions = new HashMap<>();

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Integer userId = (Integer) invocation.getArguments()[0];
                Bundle bundle = (Bundle) invocation.getArguments()[1];

                newBaseRestrictions.put(userId, bundle);

                return null;
            }
        }).when(mContext.userManagerInternal).setBaseUserRestrictionsByDpmsForMigration(
                anyInt(), any(Bundle.class));

        // Initialize DPM/DPMS and let it migrate the persisted information.
        // (Need clearCallingIdentity() to pass permission checks.)

        final DevicePolicyManagerServiceTestable dpms;

        final long ident = mContext.binder.clearCallingIdentity();
        try {
            LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

            dpms = new DevicePolicyManagerServiceTestable(mContext, dataDir);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }

        assertTrue(dpms.mOwners.hasDeviceOwner());
        assertFalse(dpms.mOwners.hasProfileOwner(UserHandle.USER_SYSTEM));
        assertTrue(dpms.mOwners.hasProfileOwner(10));
        assertTrue(dpms.mOwners.hasProfileOwner(11));
        assertFalse(dpms.mOwners.hasProfileOwner(12));

        // Now all information should be migrated.
        assertFalse(dpms.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration());
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(
                UserHandle.USER_SYSTEM));
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(10));
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(11));
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(12));

        // Check the new base restrictions.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_RECORD_AUDIO
                ),
                newBaseRestrictions.get(UserHandle.USER_SYSTEM));

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER,
                        UserManager.DISALLOW_SMS,
                        UserManager.DISALLOW_OUTGOING_CALLS,
                        UserManager.DISALLOW_RECORD_AUDIO,
                        UserManager.DISALLOW_WALLPAPER
                ),
                newBaseRestrictions.get(10));

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER,
                        UserManager.DISALLOW_SMS,
                        UserManager.DISALLOW_OUTGOING_CALLS,
                        UserManager.DISALLOW_WALLPAPER,
                        UserManager.DISALLOW_RECORD_AUDIO
                ),
                newBaseRestrictions.get(11));

        // Check the new owner restrictions.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER
                ),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions());

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_REMOVE_USER
                ),
                dpms.getProfileOwnerAdminLocked(10).ensureUserRestrictions());

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_REMOVE_USER
                ),
                dpms.getProfileOwnerAdminLocked(11).ensureUserRestrictions());
    }

    public void testMigration2_profileOwnerOnUser0() throws Exception {
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Create the legacy owners & policies file.
        DpmTestUtils.writeToFile(
                (new File(mContext.dataDir, OwnersTestable.LEGACY_FILE)).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest2/legacy_device_owner.xml"));

        DpmTestUtils.writeToFile(
                (new File(mContext.systemUserDataDir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest2/legacy_device_policies.xml"));

        // Set up UserManager
        when(mMockContext.userManagerInternal.getBaseUserRestrictions(
                eq(UserHandle.USER_SYSTEM))).thenReturn(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_RECORD_AUDIO,
                UserManager.DISALLOW_SMS,
                UserManager.DISALLOW_OUTGOING_CALLS));

        final Map<Integer, Bundle> newBaseRestrictions = new HashMap<>();

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Integer userId = (Integer) invocation.getArguments()[0];
                Bundle bundle = (Bundle) invocation.getArguments()[1];

                newBaseRestrictions.put(userId, bundle);

                return null;
            }
        }).when(mContext.userManagerInternal).setBaseUserRestrictionsByDpmsForMigration(
                anyInt(), any(Bundle.class));

        // Initialize DPM/DPMS and let it migrate the persisted information.
        // (Need clearCallingIdentity() to pass permission checks.)

        final DevicePolicyManagerServiceTestable dpms;

        final long ident = mContext.binder.clearCallingIdentity();
        try {
            LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

            dpms = new DevicePolicyManagerServiceTestable(mContext, dataDir);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }
        assertFalse(dpms.mOwners.hasDeviceOwner());
        assertTrue(dpms.mOwners.hasProfileOwner(UserHandle.USER_SYSTEM));

        // Now all information should be migrated.
        assertFalse(dpms.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration());
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(
                UserHandle.USER_SYSTEM));

        // Check the new base restrictions.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_RECORD_AUDIO
                ),
                newBaseRestrictions.get(UserHandle.USER_SYSTEM));

        // Check the new owner restrictions.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER,
                        UserManager.DISALLOW_SMS,
                        UserManager.DISALLOW_OUTGOING_CALLS
                ),
                dpms.getProfileOwnerAdminLocked(UserHandle.USER_SYSTEM).ensureUserRestrictions());
    }
}
