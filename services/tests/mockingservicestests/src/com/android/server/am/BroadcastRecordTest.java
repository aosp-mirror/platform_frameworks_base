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

import static android.app.ActivityManager.PROCESS_STATE_UNKNOWN;

import static com.android.server.am.BroadcastRecord.DELIVERY_DEFERRED;
import static com.android.server.am.BroadcastRecord.DELIVERY_DELIVERED;
import static com.android.server.am.BroadcastRecord.DELIVERY_PENDING;
import static com.android.server.am.BroadcastRecord.DELIVERY_SKIPPED;
import static com.android.server.am.BroadcastRecord.DELIVERY_TIMEOUT;
import static com.android.server.am.BroadcastRecord.calculateBlockedUntilBeyondCount;
import static com.android.server.am.BroadcastRecord.calculateDeferUntilActive;
import static com.android.server.am.BroadcastRecord.calculateUrgent;
import static com.android.server.am.BroadcastRecord.isReceiverEquals;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.content.IIntentReceiver;
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

import androidx.test.filters.SmallTest;

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
    private static final String TAG = "BroadcastRecordTest";

    private static final int USER0 = UserHandle.USER_SYSTEM;
    private static final String PACKAGE1 = "pkg1";
    private static final String PACKAGE2 = "pkg2";
    private static final String PACKAGE3 = "pkg3";

    private static final int SYSTEM_UID = android.os.Process.SYSTEM_UID;
    private static final int APP_UID = android.os.Process.FIRST_APPLICATION_UID;

    private static final BroadcastOptions OPT_DEFAULT = BroadcastOptions.makeBasic();
    private static final BroadcastOptions OPT_NONE = BroadcastOptions.makeBasic()
            .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
    private static final BroadcastOptions OPT_UNTIL_ACTIVE = BroadcastOptions.makeBasic()
            .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);

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
                calculateBlockedUntilBeyondCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 0)), false));
        assertArrayEquals(new int[] {-1},
                calculateBlockedUntilBeyondCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), -10)), false));
        assertArrayEquals(new int[] {-1},
                calculateBlockedUntilBeyondCount(List.of(
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
                calculateBlockedUntilBeyondCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 0),
                        createResolveInfo(PACKAGE2, getAppId(2), 0),
                        createResolveInfo(PACKAGE3, getAppId(3), 0)), false));
        assertArrayEquals(new int[] {-1,-1,-1},
                calculateBlockedUntilBeyondCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 10),
                        createResolveInfo(PACKAGE2, getAppId(2), 10),
                        createResolveInfo(PACKAGE3, getAppId(3), 10)), false));
    }

    @Test
    public void testIsPrioritized_Yes() {
        assertTrue(isPrioritized(List.of(
                createResolveInfo(PACKAGE1, getAppId(1), 10),
                createResolveInfo(PACKAGE2, getAppId(2), 0),
                createResolveInfo(PACKAGE3, getAppId(3), -10))));
        assertTrue(isPrioritized(List.of(
                createResolveInfo(PACKAGE1, getAppId(1), 10),
                createResolveInfo(PACKAGE2, getAppId(2), 0),
                createResolveInfo(PACKAGE3, getAppId(3), 0))));

        assertArrayEquals(new int[] {0,1,2},
                calculateBlockedUntilBeyondCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 10),
                        createResolveInfo(PACKAGE2, getAppId(2), 0),
                        createResolveInfo(PACKAGE3, getAppId(3), -10)), false));
        assertArrayEquals(new int[] {0,0,2,3,3},
                calculateBlockedUntilBeyondCount(List.of(
                        createResolveInfo(PACKAGE1, getAppId(1), 20),
                        createResolveInfo(PACKAGE2, getAppId(2), 20),
                        createResolveInfo(PACKAGE3, getAppId(3), 10),
                        createResolveInfo(PACKAGE3, getAppId(3), 0),
                        createResolveInfo(PACKAGE3, getAppId(3), 0)), false));
    }

    @Test
    public void testSetDeliveryState_Single() {
        final BroadcastRecord r = createBroadcastRecord(
                new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED), List.of(
                        createResolveInfoWithPriority(0)));
        assertEquals(DELIVERY_PENDING, r.getDeliveryState(0));
        assertBlocked(r, false);
        assertTerminalDeferredBeyond(r, 0, 0, 0);

        r.setDeliveryState(0, DELIVERY_DEFERRED, TAG);
        assertEquals(DELIVERY_DEFERRED, r.getDeliveryState(0));
        assertBlocked(r, false);
        assertTerminalDeferredBeyond(r, 0, 1, 1);

        // Identical state change has no effect
        r.setDeliveryState(0, DELIVERY_DEFERRED, TAG);
        assertEquals(DELIVERY_DEFERRED, r.getDeliveryState(0));
        assertBlocked(r, false);
        assertTerminalDeferredBeyond(r, 0, 1, 1);

        // Moving to terminal state updates counters
        r.setDeliveryState(0, DELIVERY_DELIVERED, TAG);
        assertEquals(DELIVERY_DELIVERED, r.getDeliveryState(0));
        assertBlocked(r, false);
        assertTerminalDeferredBeyond(r, 1, 0, 1);

        // Trying to change terminal state has no effect
        r.setDeliveryState(0, DELIVERY_TIMEOUT, TAG);
        assertEquals(DELIVERY_DELIVERED, r.getDeliveryState(0));
        assertBlocked(r, false);
        assertTerminalDeferredBeyond(r, 1, 0, 1);
    }

    @Test
    public void testSetDeliveryState_Unordered() {
        final BroadcastRecord r = createBroadcastRecord(
                new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED), List.of(
                        createResolveInfoWithPriority(0),
                        createResolveInfoWithPriority(0),
                        createResolveInfoWithPriority(0)));
        assertBlocked(r, false, false, false);
        assertTerminalDeferredBeyond(r, 0, 0, 0);

        // Even though we finish a middle item in the tranche, we're not
        // "beyond" it because there is still unfinished work before it
        r.setDeliveryState(1, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false);
        assertTerminalDeferredBeyond(r, 1, 0, 0);

        r.setDeliveryState(0, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false);
        assertTerminalDeferredBeyond(r, 2, 0, 2);

        r.setDeliveryState(2, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false);
        assertTerminalDeferredBeyond(r, 3, 0, 3);
    }

    @Test
    public void testSetDeliveryState_Ordered() {
        final BroadcastRecord r = createOrderedBroadcastRecord(
                new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED), List.of(
                        createResolveInfoWithPriority(0),
                        createResolveInfoWithPriority(0),
                        createResolveInfoWithPriority(0)));
        assertBlocked(r, false, true, true);
        assertTerminalDeferredBeyond(r, 0, 0, 0);

        r.setDeliveryState(0, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, true);
        assertTerminalDeferredBeyond(r, 1, 0, 1);

        r.setDeliveryState(1, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false);
        assertTerminalDeferredBeyond(r, 2, 0, 2);

        r.setDeliveryState(2, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false);
        assertTerminalDeferredBeyond(r, 3, 0, 3);
    }

    @Test
    public void testSetDeliveryState_DeferUntilActive() {
        final BroadcastRecord r = createBroadcastRecord(
                new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED), List.of(
                        createResolveInfoWithPriority(10),
                        createResolveInfoWithPriority(10),
                        createResolveInfoWithPriority(10),
                        createResolveInfoWithPriority(0),
                        createResolveInfoWithPriority(0),
                        createResolveInfoWithPriority(0),
                        createResolveInfoWithPriority(-10),
                        createResolveInfoWithPriority(-10),
                        createResolveInfoWithPriority(-10)));
        assertBlocked(r, false, false, false, true, true, true, true, true, true);
        assertTerminalDeferredBeyond(r, 0, 0, 0);

        r.setDeliveryState(0, DELIVERY_PENDING, TAG);
        r.setDeliveryState(1, DELIVERY_DEFERRED, TAG);
        r.setDeliveryState(2, DELIVERY_PENDING, TAG);
        r.setDeliveryState(3, DELIVERY_DEFERRED, TAG);
        r.setDeliveryState(4, DELIVERY_DEFERRED, TAG);
        r.setDeliveryState(5, DELIVERY_DEFERRED, TAG);
        r.setDeliveryState(6, DELIVERY_DEFERRED, TAG);
        r.setDeliveryState(7, DELIVERY_PENDING, TAG);
        r.setDeliveryState(8, DELIVERY_DEFERRED, TAG);

        // Verify deferred counts ratchet up, but we're not "beyond" the first
        // still-pending receiver
        assertBlocked(r, false, false, false, true, true, true, true, true, true);
        assertTerminalDeferredBeyond(r, 0, 6, 0);

        // We're still not "beyond" the first still-pending receiver, even when
        // we finish a receiver later in the first tranche
        r.setDeliveryState(2, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false, true, true, true, true, true, true);
        assertTerminalDeferredBeyond(r, 1, 6, 0);

        // Completing that last item in first tranche means we now unblock the
        // second tranche, and since it's entirely deferred, the third traunche
        // is unblocked too
        r.setDeliveryState(0, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false, false, false, false, false, false, false);
        assertTerminalDeferredBeyond(r, 2, 6, 7);

        // Moving a deferred item in an earlier tranche back to being pending
        // doesn't change the fact that we've already moved beyond it
        r.setDeliveryState(1, DELIVERY_PENDING, TAG);
        assertBlocked(r, false, false, false, false, false, false, false, false, false);
        assertTerminalDeferredBeyond(r, 2, 5, 7);
        r.setDeliveryState(1, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false, false, false, false, false, false, false);
        assertTerminalDeferredBeyond(r, 3, 5, 7);

        // Completing middle pending item is enough to fast-forward to end
        r.setDeliveryState(7, DELIVERY_DELIVERED, TAG);
        assertBlocked(r, false, false, false, false, false, false, false, false, false);
        assertTerminalDeferredBeyond(r, 4, 5, 9);

        // Moving everyone else directly into a finished state updates all the
        // terminal counters
        r.setDeliveryState(3, DELIVERY_SKIPPED, TAG);
        r.setDeliveryState(4, DELIVERY_SKIPPED, TAG);
        r.setDeliveryState(5, DELIVERY_SKIPPED, TAG);
        r.setDeliveryState(6, DELIVERY_SKIPPED, TAG);
        r.setDeliveryState(8, DELIVERY_SKIPPED, TAG);
        assertBlocked(r, false, false, false, false, false, false, false, false, false);
        assertTerminalDeferredBeyond(r, 9, 0, 9);
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
    public void testCalculateUrgent() {
        final Intent intent = new Intent();
        final Intent intentForeground = new Intent()
                .setFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        assertFalse(calculateUrgent(intent, null));
        assertTrue(calculateUrgent(intentForeground, null));

        {
            final BroadcastOptions opts = BroadcastOptions.makeBasic();
            assertFalse(calculateUrgent(intent, opts));
        }
        {
            final BroadcastOptions opts = BroadcastOptions.makeBasic();
            opts.setInteractive(true);
            assertTrue(calculateUrgent(intent, opts));
        }
        {
            final BroadcastOptions opts = BroadcastOptions.makeBasic();
            opts.setAlarmBroadcast(true);
            assertTrue(calculateUrgent(intent, opts));
        }
    }

    @Test
    public void testCalculateDeferUntilActive_App() {
        // Verify non-urgent behavior
        assertFalse(calculateDeferUntilActive(APP_UID, null, null, false, false));
        assertFalse(calculateDeferUntilActive(APP_UID, OPT_DEFAULT, null, false, false));
        assertFalse(calculateDeferUntilActive(APP_UID, OPT_NONE, null, false, false));
        assertTrue(calculateDeferUntilActive(APP_UID, OPT_UNTIL_ACTIVE, null, false, false));

        // Verify urgent behavior
        assertFalse(calculateDeferUntilActive(APP_UID, null, null, false, true));
        assertFalse(calculateDeferUntilActive(APP_UID, OPT_DEFAULT, null, false, true));
        assertFalse(calculateDeferUntilActive(APP_UID, OPT_NONE, null, false, true));
        assertTrue(calculateDeferUntilActive(APP_UID, OPT_UNTIL_ACTIVE, null, false, true));
    }

    @Test
    public void testCalculateDeferUntilActive_System() {
        BroadcastRecord.CORE_DEFER_UNTIL_ACTIVE = true;

        // Verify non-urgent behavior
        assertTrue(calculateDeferUntilActive(SYSTEM_UID, null, null, false, false));
        assertTrue(calculateDeferUntilActive(SYSTEM_UID, OPT_DEFAULT, null, false, false));
        assertFalse(calculateDeferUntilActive(SYSTEM_UID, OPT_NONE, null, false, false));
        assertTrue(calculateDeferUntilActive(SYSTEM_UID, OPT_UNTIL_ACTIVE, null, false, false));

        // Verify urgent behavior
        assertFalse(calculateDeferUntilActive(SYSTEM_UID, null, null, false, true));
        assertFalse(calculateDeferUntilActive(SYSTEM_UID, OPT_DEFAULT, null, false, true));
        assertFalse(calculateDeferUntilActive(SYSTEM_UID, OPT_NONE, null, false, true));
        assertTrue(calculateDeferUntilActive(SYSTEM_UID, OPT_UNTIL_ACTIVE, null, false, true));
    }

    @Test
    public void testCalculateDeferUntilActive_Overrides() {
        final IIntentReceiver resultTo = new IIntentReceiver.Default();

        // Ordered broadcasts never deferred; requested option is ignored
        assertFalse(calculateDeferUntilActive(APP_UID, OPT_UNTIL_ACTIVE, null, true, false));
        assertFalse(calculateDeferUntilActive(APP_UID, OPT_UNTIL_ACTIVE, resultTo, true, false));

        // Unordered with result is always deferred; requested option is ignored
        assertTrue(calculateDeferUntilActive(APP_UID, OPT_NONE, resultTo, false, false));
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

    private static ResolveInfo createResolveInfoWithPriority(int priority) {
        return createResolveInfo(PACKAGE1, getAppId(1), priority);
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

    private BroadcastRecord createBroadcastRecord(Intent intent,
            List<ResolveInfo> receivers) {
        return createBroadcastRecord(receivers, USER0, intent, null /* filterExtrasForReceiver */,
                null /* options */, false);
    }

    private BroadcastRecord createOrderedBroadcastRecord(Intent intent,
            List<ResolveInfo> receivers) {
        return createBroadcastRecord(receivers, USER0, intent, null /* filterExtrasForReceiver */,
                null /* options */, true);
    }

    private BroadcastRecord createBroadcastRecord(List<ResolveInfo> receivers, int userId,
            Intent intent) {
        return createBroadcastRecord(receivers, userId, intent, null /* filterExtrasForReceiver */,
                null /* options */, false);
    }

    private BroadcastRecord createBroadcastRecord(List<ResolveInfo> receivers, int userId,
            Intent intent, BroadcastOptions options) {
        return createBroadcastRecord(receivers, userId, intent, null /* filterExtrasForReceiver */,
                options, false);
    }

    private BroadcastRecord createBroadcastRecord(List<ResolveInfo> receivers, int userId,
            Intent intent, BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            BroadcastOptions options) {
        return createBroadcastRecord(receivers, userId, intent, filterExtrasForReceiver,
                options, false);
    }

    private BroadcastRecord createBroadcastRecord(List<ResolveInfo> receivers, int userId,
            Intent intent, BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            BroadcastOptions options, boolean ordered) {
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
                ordered /* serialized */,
                false /* sticky */,
                false /* initialSticky */,
                userId,
                BackgroundStartPrivileges.NONE,
                false /* timeoutExempt */,
                filterExtrasForReceiver,
                PROCESS_STATE_UNKNOWN);
    }

    private static int getAppId(int i) {
        return Process.FIRST_APPLICATION_UID + i;
    }

    private static boolean isPrioritized(List<Object> receivers) {
        return BroadcastRecord.isPrioritized(
                calculateBlockedUntilBeyondCount(receivers, false), false);
    }

    private static void assertBlocked(BroadcastRecord r, boolean... blocked) {
        assertEquals(r.receivers.size(), blocked.length);
        for (int i = 0; i < blocked.length; i++) {
            assertEquals("blocked " + i, blocked[i], r.isBlocked(i));
        }
    }

    private static void assertTerminalDeferredBeyond(BroadcastRecord r,
            int expectedTerminalCount, int expectedDeferredCount, int expectedBeyondCount) {
        assertEquals("terminal", expectedTerminalCount, r.terminalCount);
        assertEquals("deferred", expectedDeferredCount, r.deferredCount);
        assertEquals("beyond", expectedBeyondCount, r.beyondCount);
    }
}
