/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED;
import static android.content.Intent.ACTION_TIME_CHANGED;

import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_ALL;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY;
import static com.android.server.am.BroadcastConstants.DEFER_BOOT_COMPLETED_BROADCAST_NONE;
import static com.android.server.am.BroadcastRecord.calculateBlockedUntilTerminalCount;
import static com.android.server.am.BroadcastRecord.isReceiverEquals;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import android.app.ActivityManagerInternal;
import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.android.server.am.BroadcastDispatcher.DeferredBootCompletedBroadcastPerUser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Test class for {@link BroadcastRecord}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:BroadcastRecordTest
 */
@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class BroadcastRecordTest {

    private static final int USER0 = UserHandle.USER_SYSTEM;
    private static final int USER1 = USER0 + 1;
    private static final int[] USER_LIST = new int[] {USER0, USER1};
    private static final String PACKAGE1 = "pkg1";
    private static final String PACKAGE2 = "pkg2";
    private static final String PACKAGE3 = "pkg3";
    private static final String PACKAGE4 = "pkg4";
    private static final String[] PACKAGE_LIST = new String[] {PACKAGE1, PACKAGE2, PACKAGE3,
            PACKAGE4};

    @Mock ActivityManagerInternal mActivityManagerInternal;
    @Mock BroadcastQueue mQueue;
    @Mock ProcessRecord mProcess;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testIsPrioritized_Empty() {
        assertFalse(isPrioritized(List.of()));
    }

    @Test
    public void testIsPrioritized_Single() {
        assertFalse(isPrioritized(List.of(createResolveInfo(PACKAGE1, getAppId(1), 0))));
        assertFalse(isPrioritized(List.of(createResolveInfo(PACKAGE1, getAppId(1), -10))));
        assertFalse(isPrioritized(List.of(createResolveInfo(PACKAGE1, getAppId(1), 10))));

        assertArrayEquals(new int[] {-1},
                calculateBlockedUntilTerminalCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 0)), false));
        assertArrayEquals(new int[] {-1},
                calculateBlockedUntilTerminalCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), -10)), false));
        assertArrayEquals(new int[] {-1},
                calculateBlockedUntilTerminalCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 10)), false));
    }

    @Test
    public void testIsPrioritized_No() {
        assertFalse(isPrioritized(List.of(
                createResolveInfo(PACKAGE1, getAppId(1), 0),
                createResolveInfo(PACKAGE2, getAppId(2), 0),
                createResolveInfo(PACKAGE3, getAppId(3), 0))));
        assertFalse(isPrioritized(List.of(
                createResolveInfo(PACKAGE1, getAppId(1), 10),
                createResolveInfo(PACKAGE2, getAppId(2), 10),
                createResolveInfo(PACKAGE3, getAppId(3), 10))));

        assertArrayEquals(new int[] {-1,-1,-1},
                calculateBlockedUntilTerminalCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 0),
                        createResolveInfo(PACKAGE2, getAppId(2), 0),
                        createResolveInfo(PACKAGE3, getAppId(3), 0)), false));
        assertArrayEquals(new int[] {-1,-1,-1},
                calculateBlockedUntilTerminalCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 10),
                        createResolveInfo(PACKAGE2, getAppId(2), 10),
                        createResolveInfo(PACKAGE3, getAppId(3), 10)), false));
    }

    @Test
    public void testIsPrioritized_Yes() {
        assertTrue(isPrioritized(List.of(
                createResolveInfo(PACKAGE1, getAppId(1), -10),
                createResolveInfo(PACKAGE2, getAppId(2), 0),
                createResolveInfo(PACKAGE3, getAppId(3), 10))));
        assertTrue(isPrioritized(List.of(
                createResolveInfo(PACKAGE1, getAppId(1), 0),
                createResolveInfo(PACKAGE2, getAppId(2), 0),
                createResolveInfo(PACKAGE3, getAppId(3), 10))));

        assertArrayEquals(new int[] {0,1,2},
                calculateBlockedUntilTerminalCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), -10),
                        createResolveInfo(PACKAGE2, getAppId(2), 0),
                        createResolveInfo(PACKAGE3, getAppId(3), 10)), false));
        assertArrayEquals(new int[] {0,0,2,3,3},
                calculateBlockedUntilTerminalCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 0),
                        createResolveInfo(PACKAGE2, getAppId(2), 0),
                        createResolveInfo(PACKAGE3, getAppId(3), 10),
                        createResolveInfo(PACKAGE3, getAppId(3), 20),
                        createResolveInfo(PACKAGE3, getAppId(3), 20)), false));
    }

    @Test
    public void testGetReceiverIntent_Simple() {
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastRecord r = createBroadcastRecord(
                List.of(createResolveInfo(PACKAGE1, getAppId(1))), UserHandle.USER_ALL, intent);
        final Intent actual = r.getReceiverIntent(r.receivers.get(0));
        assertEquals(PACKAGE1, actual.getComponent().getPackageName());
        assertNull(r.intent.getComponent());
    }

    @Test
    public void testGetReceiverIntent_Filtered_Partial() {
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra(Intent.EXTRA_INDEX, 42);
        final BroadcastRecord r = createBroadcastRecord(
                List.of(createResolveInfo(PACKAGE1, getAppId(1))), UserHandle.USER_ALL, intent,
                (uid, extras) -> Bundle.EMPTY,
                null /* options */);
        final Intent actual = r.getReceiverIntent(r.receivers.get(0));
        assertEquals(PACKAGE1, actual.getComponent().getPackageName());
        assertEquals(-1, actual.getIntExtra(Intent.EXTRA_INDEX, -1));
        assertNull(r.intent.getComponent());
        assertEquals(42, r.intent.getIntExtra(Intent.EXTRA_INDEX, -1));
    }

    @Test
    public void testGetReceiverIntent_Filtered_Complete() {
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra(Intent.EXTRA_INDEX, 42);
        final BroadcastRecord r = createBroadcastRecord(
                List.of(createResolveInfo(PACKAGE1, getAppId(1))), UserHandle.USER_ALL, intent,
                (uid, extras) -> null,
                null /* options */);
        final Intent actual = r.getReceiverIntent(r.receivers.get(0));
        assertNull(actual);
        assertNull(r.intent.getComponent());
        assertEquals(42, r.intent.getIntExtra(Intent.EXTRA_INDEX, -1));
    }

    @Test
    public void testIsReceiverEquals() {
        final ResolveInfo info = createResolveInfo(PACKAGE1, getAppId(1));
        assertTrue(isReceiverEquals(info, info));
        assertTrue(isReceiverEquals(info, createResolveInfo(PACKAGE1, getAppId(1))));
        assertFalse(isReceiverEquals(info, createResolveInfo(PACKAGE2, getAppId(2))));
    }

    @Test
    public void testCleanupDisabledPackageReceivers() {
        final int user0 = UserHandle.USER_SYSTEM;
        final int user1 = user0 + 1;
        final String pkgToCleanup = "pkg.a";
        final String pkgOther = "pkg.b";

        // Receivers contain multiple-user (contains [pkg.a@u0, pkg.a@u1, pkg.b@u0, pkg.b@u1]).
        final List<ResolveInfo> receiversM = createReceiverInfos(
                new String[] { pkgToCleanup, pkgOther },
                new int[] { user0, user1 });
        // Receivers only contain one user (contains [pkg.a@u0, pkg.b@u0]).
        final List<ResolveInfo> receiversU0 = excludeReceivers(
                receiversM, null /* packageName */, user1);

        // With given package:
        // Send to all users, cleanup a package of all users.
        final BroadcastRecord recordAllAll = createBroadcastRecord(receiversM, UserHandle.USER_ALL,
                new Intent());
        cleanupDisabledPackageReceivers(recordAllAll, pkgToCleanup, UserHandle.USER_ALL);
        assertNull(verifyRemaining(recordAllAll, excludeReceivers(receiversM, pkgToCleanup, -1)));

        // Send to all users, cleanup a package of one user.
        final BroadcastRecord recordAllOne = createBroadcastRecord(receiversM, UserHandle.USER_ALL,
                new Intent());
        cleanupDisabledPackageReceivers(recordAllOne, pkgToCleanup, user0);
        assertNull(verifyRemaining(recordAllOne,
                excludeReceivers(receiversM, pkgToCleanup, user0)));

        // Send to one user, cleanup a package of all users.
        final BroadcastRecord recordOneAll = createBroadcastRecord(receiversU0, user0,
                new Intent());
        cleanupDisabledPackageReceivers(recordOneAll, pkgToCleanup, UserHandle.USER_ALL);
        assertNull(verifyRemaining(recordOneAll, excludeReceivers(receiversU0, pkgToCleanup, -1)));

        // Send to one user, cleanup a package one user.
        final BroadcastRecord recordOneOne = createBroadcastRecord(receiversU0, user0,
                new Intent());
        cleanupDisabledPackageReceivers(recordOneOne, pkgToCleanup, user0);
        assertNull(verifyRemaining(recordOneOne, excludeReceivers(receiversU0, pkgToCleanup, -1)));

        // Without given package (e.g. stop user):
        // Send to all users, cleanup one user.
        final BroadcastRecord recordAllM = createBroadcastRecord(receiversM, UserHandle.USER_ALL,
                new Intent());
        cleanupDisabledPackageReceivers(recordAllM, null /* packageName */, user1);
        assertNull(verifyRemaining(recordAllM,
                excludeReceivers(receiversM, null /* packageName */, user1)));

        // Send to one user, cleanup one user.
        final BroadcastRecord recordU0 = createBroadcastRecord(receiversU0, user0, new Intent());
        cleanupDisabledPackageReceivers(recordU0, null /* packageName */, user0);
        assertNull(verifyRemaining(recordU0, Collections.emptyList()));
    }

    // Test defer BOOT_COMPLETED and LOCKED_BOOT_COMPLETED broaddcasts.
    @Test
    public void testDeferBootCompletedBroadcast() {
        testDeferBootCompletedBroadcast_defer_none(ACTION_BOOT_COMPLETED);
        testDeferBootCompletedBroadcast_defer_all(ACTION_BOOT_COMPLETED);
        testDeferBootCompletedBroadcast_defer_background_restricted_only(ACTION_BOOT_COMPLETED);
        testDeferBootCompletedBroadcast_defer_none(ACTION_LOCKED_BOOT_COMPLETED);
        testDeferBootCompletedBroadcast_defer_all(ACTION_LOCKED_BOOT_COMPLETED);
        testDeferBootCompletedBroadcast_defer_background_restricted_only(
                ACTION_LOCKED_BOOT_COMPLETED);
    }

    // non-BOOT_COMPLETED broadcast does not get deferred.
    @Test
    public void testNoDeferOtherBroadcast() {
        // no split for non-BOOT_COMPLETED broadcasts.
        final BroadcastRecord br = createBootCompletedBroadcastRecord(ACTION_TIME_CHANGED);
        final int origReceiversSize = br.receivers.size();

        SparseArray<BroadcastRecord> deferred = br.splitDeferredBootCompletedBroadcastLocked(
                mActivityManagerInternal, DEFER_BOOT_COMPLETED_BROADCAST_ALL);
        // No receivers get deferred.
        assertEquals(0, deferred.size());
        assertEquals(origReceiversSize, br.receivers.size());
    }

    @Test
    public void testMatchesDeliveryGroup() {
        final List<ResolveInfo> receivers = List.of(createResolveInfo(PACKAGE1, getAppId(1)));

        final Intent intent1 = new Intent(Intent.ACTION_SERVICE_STATE);
        intent1.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        intent1.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 1);
        final BroadcastOptions options1 = BroadcastOptions.makeBasic();
        final BroadcastRecord record1 = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                intent1, options1);

        final Intent intent2 = new Intent(Intent.ACTION_SERVICE_STATE);
        intent2.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        intent2.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 2);
        final BroadcastOptions options2 = BroadcastOptions.makeBasic();
        final BroadcastRecord record2 = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                intent2, options2);

        assertTrue(record2.matchesDeliveryGroup(record1));
    }

    @Test
    public void testMatchesDeliveryGroup_withMatchingKey() {
        final List<ResolveInfo> receivers = List.of(createResolveInfo(PACKAGE1, getAppId(1)));

        final Intent intent1 = new Intent(Intent.ACTION_SERVICE_STATE);
        intent1.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        intent1.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 1);
        final BroadcastOptions options1 = BroadcastOptions.makeBasic();
        options1.setDeliveryGroupMatchingKey(Intent.ACTION_SERVICE_STATE, "key1");
        final BroadcastRecord record1 = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                intent1, options1);

        final Intent intent2 = new Intent(Intent.ACTION_SERVICE_STATE);
        intent2.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        intent2.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 2);
        final BroadcastOptions options2 = BroadcastOptions.makeBasic();
        options2.setDeliveryGroupMatchingKey(Intent.ACTION_SERVICE_STATE, "key2");
        final BroadcastRecord record2 = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                intent2, options2);

        final Intent intent3 = new Intent(Intent.ACTION_SERVICE_STATE);
        intent3.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 1);
        intent3.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 3);
        final BroadcastOptions options3 = BroadcastOptions.makeBasic();
        options3.setDeliveryGroupMatchingKey(Intent.ACTION_SERVICE_STATE, "key1");
        final BroadcastRecord record3 = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                intent3, options3);

        // record2 and record1 have different matching keys, so their delivery groups
        // shouldn't match
        assertFalse(record2.matchesDeliveryGroup(record1));
        // record3 and record2 have different matching keys, so their delivery groups
        // shouldn't match
        assertFalse(record3.matchesDeliveryGroup(record2));
        // record3 and record1 have same matching keys, so their delivery groups should match even
        // if the intent has different extras.
        assertTrue(record3.matchesDeliveryGroup(record1));
    }

    @Test
    public void testMatchesDeliveryGroup_withMatchingFilter() {
        final List<ResolveInfo> receivers = List.of(createResolveInfo(PACKAGE1, getAppId(1)));

        final Intent intent1 = new Intent(Intent.ACTION_SERVICE_STATE);
        intent1.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        intent1.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 1);
        intent1.putExtra(Intent.EXTRA_REASON, "reason1");
        final IntentFilter filter1 = new IntentFilter(Intent.ACTION_SERVICE_STATE);
        final PersistableBundle bundle1 = new PersistableBundle();
        bundle1.putInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        bundle1.putInt(SubscriptionManager.EXTRA_SLOT_INDEX, 1);
        filter1.setExtras(bundle1);
        final BroadcastOptions options1 = BroadcastOptions.makeBasic();
        options1.setDeliveryGroupMatchingFilter(filter1);
        final BroadcastRecord record1 = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                intent1, options1);

        final Intent intent2 = new Intent(Intent.ACTION_SERVICE_STATE);
        intent2.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        intent2.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 2);
        intent2.putExtra(Intent.EXTRA_REASON, "reason2");
        final IntentFilter filter2 = new IntentFilter(Intent.ACTION_SERVICE_STATE);
        final PersistableBundle bundle2 = new PersistableBundle();
        bundle2.putInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        bundle2.putInt(SubscriptionManager.EXTRA_SLOT_INDEX, 2);
        filter2.setExtras(bundle2);
        final BroadcastOptions options2 = BroadcastOptions.makeBasic();
        options2.setDeliveryGroupMatchingFilter(filter2);
        final BroadcastRecord record2 = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                intent2, options2);

        final Intent intent3 = new Intent(Intent.ACTION_SERVICE_STATE);
        intent3.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 1);
        intent3.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 3);
        intent3.putExtra(Intent.EXTRA_REASON, "reason3");
        final IntentFilter filter3 = new IntentFilter(Intent.ACTION_SERVICE_STATE);
        final PersistableBundle bundle3 = new PersistableBundle();
        bundle3.putInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
        bundle3.putInt(SubscriptionManager.EXTRA_SLOT_INDEX, 1);
        filter3.setExtras(bundle3);
        final BroadcastOptions options3 = BroadcastOptions.makeBasic();
        options3.setDeliveryGroupMatchingFilter(filter3);
        final BroadcastRecord record3 = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                intent3, options3);

        // record2's matchingFilter doesn't match record1's intent, so their delivery groups
        // shouldn't match
        assertFalse(record2.matchesDeliveryGroup(record1));
        // record3's matchingFilter doesn't match record2's intent, so their delivery groups
        // shouldn't match
        assertFalse(record3.matchesDeliveryGroup(record2));
        // record3's matchingFilter matches record1's intent, so their delivery groups should match.
        assertTrue(record3.matchesDeliveryGroup(record1));
    }

    private BroadcastRecord createBootCompletedBroadcastRecord(String action) {
        final List<ResolveInfo> receivers = createReceiverInfos(PACKAGE_LIST, USER_LIST);
        final BroadcastRecord br = createBroadcastRecord(receivers, UserHandle.USER_ALL,
                new Intent(action));
        assertEquals(PACKAGE_LIST.length * USER_LIST.length, br.receivers.size());
        return br;
    }

    // Test type DEFER_BOOT_COMPLETED_BROADCAST_NONE, this type does not defer any receiver.
    private void testDeferBootCompletedBroadcast_defer_none(String action) {
        final BroadcastRecord br = createBootCompletedBroadcastRecord(action);
        final int origReceiversSize = br.receivers.size();

        SparseArray<BroadcastRecord> deferred = br.splitDeferredBootCompletedBroadcastLocked(
                mActivityManagerInternal, DEFER_BOOT_COMPLETED_BROADCAST_NONE);
        // No receivers get deferred.
        assertEquals(0, deferred.size());
        assertEquals(origReceiversSize, br.receivers.size());
    }

    // Test type DEFER_BOOT_COMPLETED_BROADCAST_ALL, this type defer all receivers.
    private void testDeferBootCompletedBroadcast_defer_all(String action) {
        final BroadcastRecord br = createBootCompletedBroadcastRecord(action);

        SparseArray<BroadcastRecord> deferred = br.splitDeferredBootCompletedBroadcastLocked(
                mActivityManagerInternal, DEFER_BOOT_COMPLETED_BROADCAST_ALL);
        // original BroadcastRecord receivers list is empty now.
        assertTrue(br.receivers.isEmpty());

        assertEquals(PACKAGE_LIST.length * USER_LIST.length, deferred.size());
        for (int i = 0; i < PACKAGE_LIST.length; i++) {
            for (final int userId : USER_LIST) {
                final int uid = UserHandle.getUid(userId, getAppId(i));
                assertTrue(deferred.contains(uid));
                assertEquals(1, deferred.get(uid).receivers.size());
                final ResolveInfo info = (ResolveInfo) deferred.get(uid).receivers.get(0);
                assertEquals(PACKAGE_LIST[i], info.activityInfo.applicationInfo.packageName);
                assertEquals(uid, info.activityInfo.applicationInfo.uid);
            }
        }
    }

    // Test type DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY,
    // This type defers receiver whose app package is background restricted.
    private void testDeferBootCompletedBroadcast_defer_background_restricted_only(String action) {
        final BroadcastRecord br = createBootCompletedBroadcastRecord(action);
        final int origReceiversSize = br.receivers.size();

        // First half packages in PACKAGE_LIST, return BACKGROUND_RESTRICTED.
        for (int i = 0; i < PACKAGE_LIST.length / 2; i++) {
            for (int u = 0; u < USER_LIST.length; u++) {
                final int uid = UserHandle.getUid(USER_LIST[u], getAppId(i));
                doReturn(RESTRICTION_LEVEL_BACKGROUND_RESTRICTED).when(mActivityManagerInternal)
                        .getRestrictionLevel(eq(uid));
            }
        }

        // the second half packages in PACKAGE_LIST, return not BACKGROUND_RESTRICTED.
        for (int i = PACKAGE_LIST.length / 2; i < PACKAGE_LIST.length; i++) {
            for (int u = 0; u < USER_LIST.length; u++) {
                final int uid = UserHandle.getUid(USER_LIST[u], getAppId(i));
                doReturn(RESTRICTION_LEVEL_BACKGROUND_RESTRICTED - 10).when(
                        mActivityManagerInternal).getRestrictionLevel(eq(uid));
            }
        }

        SparseArray<BroadcastRecord> deferred = br.splitDeferredBootCompletedBroadcastLocked(
                mActivityManagerInternal,
                DEFER_BOOT_COMPLETED_BROADCAST_BACKGROUND_RESTRICTED_ONLY);
        // original BroadcastRecord receivers list is half now.
        assertEquals(origReceiversSize / 2, br.receivers.size());
        assertEquals(origReceiversSize / 2, deferred.size());

        for (int i = 0; i < PACKAGE_LIST.length / 2; i++) {
            for (int u = 0; u < USER_LIST.length; u++) {
                final int uid = UserHandle.getUid(USER_LIST[u], getAppId(i));
                assertTrue(deferred.contains(uid));
                assertEquals(1, deferred.get(uid).receivers.size());
                final ResolveInfo info = (ResolveInfo) deferred.get(uid).receivers.get(0);
                assertEquals(PACKAGE_LIST[i], info.activityInfo.applicationInfo.packageName);
                assertEquals(uid, info.activityInfo.applicationInfo.uid);
            }
        }

        for (int i = PACKAGE_LIST.length / 2; i < PACKAGE_LIST.length; i++) {
            for (int u = 0; u < USER_LIST.length; u++) {
                final int uid = UserHandle.getUid(USER_LIST[u], getAppId(i));
                boolean found = false;
                for (int r = 0; r < br.receivers.size(); r++) {
                    final ResolveInfo info = (ResolveInfo) br.receivers.get(r);
                    if (uid == info.activityInfo.applicationInfo.uid) {
                        found = true;
                        break;
                    }
                }
                assertTrue(found);
            }
        }
    }

    /**
     * Test the class {@link BroadcastDispatcher#DeferredBootCompletedBroadcastPerUser}
     */
    @Test
    public void testDeferBootCompletedBroadcast_dispatcher() {
        testDeferBootCompletedBroadcast_dispatcher_internal(ACTION_LOCKED_BOOT_COMPLETED, false);
        testDeferBootCompletedBroadcast_dispatcher_internal(ACTION_BOOT_COMPLETED, false);
        testDeferBootCompletedBroadcast_dispatcher_internal(ACTION_LOCKED_BOOT_COMPLETED, true);
        testDeferBootCompletedBroadcast_dispatcher_internal(ACTION_BOOT_COMPLETED, true);
    }

    private void testDeferBootCompletedBroadcast_dispatcher_internal(String action,
            boolean isAllUidReady) {
        final List<ResolveInfo> receivers = createReceiverInfos(PACKAGE_LIST, new int[] {USER0});
        final BroadcastRecord br = createBroadcastRecord(receivers, USER0, new Intent(action));
        assertEquals(PACKAGE_LIST.length, br.receivers.size());

        SparseArray<BroadcastRecord> deferred = br.splitDeferredBootCompletedBroadcastLocked(
                mActivityManagerInternal, DEFER_BOOT_COMPLETED_BROADCAST_ALL);
        // original BroadcastRecord receivers list is empty now.
        assertTrue(br.receivers.isEmpty());
        assertEquals(PACKAGE_LIST.length, deferred.size());

        DeferredBootCompletedBroadcastPerUser deferredPerUser =
                new DeferredBootCompletedBroadcastPerUser(USER0);
        deferredPerUser.enqueueBootCompletedBroadcasts(action, deferred);

        if (action.equals(ACTION_LOCKED_BOOT_COMPLETED)) {
            assertEquals(PACKAGE_LIST.length,
                    deferredPerUser.mDeferredLockedBootCompletedBroadcasts.size());
            assertTrue(deferredPerUser.mLockedBootCompletedBroadcastReceived);
            for (int i = 0; i < PACKAGE_LIST.length; i++) {
                final int uid = UserHandle.getUid(USER0, getAppId(i));
                if (!isAllUidReady) {
                    deferredPerUser.updateUidReady(uid);
                }
                BroadcastRecord d = deferredPerUser.dequeueDeferredBootCompletedBroadcast(
                        isAllUidReady);
                final ResolveInfo info = (ResolveInfo) d.receivers.get(0);
                assertEquals(PACKAGE_LIST[i], info.activityInfo.applicationInfo.packageName);
                assertEquals(uid, info.activityInfo.applicationInfo.uid);
            }
            assertEquals(0, deferredPerUser.mUidReadyForLockedBootCompletedBroadcast.size());
        } else if (action.equals(ACTION_BOOT_COMPLETED)) {
            assertEquals(PACKAGE_LIST.length,
                    deferredPerUser.mDeferredBootCompletedBroadcasts.size());
            assertTrue(deferredPerUser.mBootCompletedBroadcastReceived);
            for (int i = 0; i < PACKAGE_LIST.length; i++) {
                final int uid = UserHandle.getUid(USER0, getAppId(i));
                if (!isAllUidReady) {
                    deferredPerUser.updateUidReady(uid);
                }
                BroadcastRecord d = deferredPerUser.dequeueDeferredBootCompletedBroadcast(
                        isAllUidReady);
                final ResolveInfo info = (ResolveInfo) d.receivers.get(0);
                assertEquals(PACKAGE_LIST[i], info.activityInfo.applicationInfo.packageName);
                assertEquals(uid, info.activityInfo.applicationInfo.uid);
            }
            assertEquals(0, deferredPerUser.mUidReadyForBootCompletedBroadcast.size());
        }
    }

    private static void cleanupDisabledPackageReceivers(BroadcastRecord record,
            String packageName, int userId) {
        record.cleanupDisabledPackageReceiversLocked(packageName, null /* filterByClasses */,
                userId, true /* doit */);
    }

    private static String verifyRemaining(BroadcastRecord record,
            List<ResolveInfo> expectedRemainingReceivers) {
        final StringBuilder errorMsg = new StringBuilder();

        for (final Object receiver : record.receivers) {
            final ResolveInfo resolveInfo = (ResolveInfo) receiver;
            final ApplicationInfo appInfo = resolveInfo.activityInfo.applicationInfo;

            boolean foundExpected = false;
            for (final ResolveInfo expectedReceiver : expectedRemainingReceivers) {
                final ApplicationInfo expectedAppInfo =
                        expectedReceiver.activityInfo.applicationInfo;
                if (appInfo.packageName.equals(expectedAppInfo.packageName)
                        && UserHandle.getUserId(appInfo.uid) == UserHandle
                                .getUserId(expectedAppInfo.uid)) {
                    foundExpected = true;
                    break;
                }
            }
            if (!foundExpected) {
                errorMsg.append(appInfo.packageName).append("@")
                        .append('u').append(UserHandle.getUserId(appInfo.uid)).append(' ');
            }
        }

        return errorMsg.length() == 0 ? null
                : errorMsg.insert(0, "Contains unexpected receiver: ").toString();
    }

    private static ResolveInfo createResolveInfo(String packageName, int uid) {
        return createResolveInfo(packageName, uid, 0);
    }

    private static ResolveInfo createResolveInfo(String packageName, int uid, int priority) {
        final ResolveInfo resolveInfo = new ResolveInfo();
        final ActivityInfo activityInfo = new ActivityInfo();
        final ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        appInfo.uid = uid;
        activityInfo.applicationInfo = appInfo;
        activityInfo.packageName = packageName;
        activityInfo.name = packageName + ".MyReceiver";
        resolveInfo.activityInfo = activityInfo;
        resolveInfo.priority = priority;
        return resolveInfo;
    }

    /**
     * Generate (packages.length * userIds.length) receivers.
     */
    private static List<ResolveInfo> createReceiverInfos(String[] packages, int[] userIds) {
        final List<ResolveInfo> receivers = new ArrayList<>();
        for (int i = 0; i < packages.length; i++) {
            for (final int userId : userIds) {
                receivers.add(createResolveInfo(packages[i],
                        UserHandle.getUid(userId, getAppId(i))));
            }
        }
        return receivers;
    }

    /**
     * Create a new list which filters out item if package name or user id is matched.
     * Null package name or user id < 0 will be considered as don't care.
     */
    private static List<ResolveInfo> excludeReceivers(List<ResolveInfo> receivers,
            String packageName, int userId) {
        final List<ResolveInfo> excludedList = new ArrayList<>();
        for (final ResolveInfo receiver : receivers) {
            if ((packageName != null
                    && !packageName.equals(receiver.activityInfo.applicationInfo.packageName))
                    || (userId > -1 && userId != UserHandle
                            .getUserId(receiver.activityInfo.applicationInfo.uid))) {
                excludedList.add(receiver);
            }
        }
        return excludedList;
    }

    private BroadcastRecord createBroadcastRecord(List<ResolveInfo> receivers, int userId,
            Intent intent) {
        return createBroadcastRecord(receivers, userId, intent, null /* filterExtrasForReceiver */,
                null /* options */);
    }

    private BroadcastRecord createBroadcastRecord(List<ResolveInfo> receivers, int userId,
            Intent intent, BroadcastOptions options) {
        return createBroadcastRecord(receivers, userId, intent, null /* filterExtrasForReceiver */,
                options);
    }

    private BroadcastRecord createBroadcastRecord(List<ResolveInfo> receivers, int userId,
            Intent intent, BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            BroadcastOptions options) {
        return new BroadcastRecord(
                mQueue /* queue */,
                intent,
                mProcess /* callerApp */,
                PACKAGE1 /* callerPackage */,
                null /* callerFeatureId */,
                0 /* callingPid */,
                0 /* callingUid */,
                false /* callerInstantApp */,
                null /* resolvedType */,
                null /* requiredPermissions */,
                null /* excludedPermissions */,
                null /* excludedPackages */,
                0 /* appOp */,
                options,
                new ArrayList<>(receivers), // Make a copy to not affect the original list.
                null /* resultToApp */,
                null /* resultTo */,
                0 /* resultCode */,
                null /* resultData */,
                null /* resultExtras */,
                false /* serialized */,
                false /* sticky */,
                false /* initialSticky */,
                userId,
                BackgroundStartPrivileges.NONE,
                false /* timeoutExempt */,
                filterExtrasForReceiver);
    }

    private static int getAppId(int i) {
        return Process.FIRST_APPLICATION_UID + i;
    }

    private static boolean isPrioritized(List<Object> receivers) {
        return BroadcastRecord.isPrioritized(
                calculateBlockedUntilTerminalCount(receivers, false), false);
    }
}
