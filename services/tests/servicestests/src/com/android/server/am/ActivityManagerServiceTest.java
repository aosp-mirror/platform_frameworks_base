/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.util.DebugUtils.valueToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IUidObserver;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.AppOpsService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Test class for {@link ActivityManagerService}.
 *
 * To run the tests, use
 *
 * runtest -c com.android.server.am.ActivityManagerServiceTest frameworks-services
 *
 * or the following steps:
 *
 * Build: m FrameworksServicesTests
 * Install: adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -e class com.android.server.am.ActivityManagerServiceTest -w \
 *     com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ActivityManagerServiceTest {
    private static final int TEST_UID = 111;

    @Mock private AppOpsService mAppOpsService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testIncrementProcStateSeqIfNeeded() {
        final ActivityManagerService ams = new ActivityManagerService(mAppOpsService);
        final UidRecord uidRec = new UidRecord(TEST_UID);

        assertEquals("Initially global seq counter should be 0", 0, ams.mProcStateSeqCounter);
        assertEquals("Initially seq counter in uidRecord should be 0", 0, uidRec.curProcStateSeq);

        // Uid state is not moving from background to foreground or vice versa.
        uidRec.setProcState = PROCESS_STATE_TOP;
        uidRec.curProcState = PROCESS_STATE_TOP;
        ams.incrementProcStateSeqIfNeeded(uidRec);
        assertEquals(0, ams.mProcStateSeqCounter);
        assertEquals(0, uidRec.curProcStateSeq);

        // Uid state is moving from foreground to background.
        uidRec.curProcState = PROCESS_STATE_FOREGROUND_SERVICE;
        uidRec.setProcState = PROCESS_STATE_SERVICE;
        ams.incrementProcStateSeqIfNeeded(uidRec);
        assertEquals(1, ams.mProcStateSeqCounter);
        assertEquals(1, uidRec.curProcStateSeq);

        // Explicitly setting the seq counter for more verification.
        ams.mProcStateSeqCounter = 42;

        // Uid state is not moving from background to foreground or vice versa.
        uidRec.setProcState = PROCESS_STATE_IMPORTANT_BACKGROUND;
        uidRec.curProcState = PROCESS_STATE_IMPORTANT_FOREGROUND;
        ams.incrementProcStateSeqIfNeeded(uidRec);
        assertEquals(42, ams.mProcStateSeqCounter);
        assertEquals(1, uidRec.curProcStateSeq);

        // Uid state is moving from background to foreground.
        uidRec.setProcState = PROCESS_STATE_LAST_ACTIVITY;
        uidRec.curProcState = PROCESS_STATE_TOP;
        ams.incrementProcStateSeqIfNeeded(uidRec);
        assertEquals(43, ams.mProcStateSeqCounter);
        assertEquals(43, uidRec.curProcStateSeq);
    }

    @Test
    public void testShouldIncrementProcStateSeq() {
        final ActivityManagerService ams = new ActivityManagerService(mAppOpsService);
        final UidRecord uidRec = new UidRecord(TEST_UID);

        final String error1 = "Seq should be incremented: prevState: %s, curState: %s";
        final String error2 = "Seq should not be incremented: prevState: %s, curState: %s";
        Function<String, String> errorMsg = errorTemplate -> {
            return String.format(errorTemplate,
                    valueToString(ActivityManager.class, "PROCESS_STATE_", uidRec.setProcState),
                    valueToString(ActivityManager.class, "PROCESS_STATE_", uidRec.curProcState));
        };

        // No change in uid state
        uidRec.setProcState = PROCESS_STATE_RECEIVER;
        uidRec.curProcState = PROCESS_STATE_RECEIVER;
        assertFalse(errorMsg.apply(error2), ams.shouldIncrementProcStateSeq(uidRec));

        // Foreground to foreground
        uidRec.setProcState = PROCESS_STATE_FOREGROUND_SERVICE;
        uidRec.curProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
        assertFalse(errorMsg.apply(error2), ams.shouldIncrementProcStateSeq(uidRec));

        // Background to background
        uidRec.setProcState = PROCESS_STATE_CACHED_ACTIVITY;
        uidRec.curProcState = PROCESS_STATE_CACHED_EMPTY;
        assertFalse(errorMsg.apply(error2), ams.shouldIncrementProcStateSeq(uidRec));

        // Background to background
        uidRec.setProcState = PROCESS_STATE_NONEXISTENT;
        uidRec.curProcState = PROCESS_STATE_CACHED_ACTIVITY;
        assertFalse(errorMsg.apply(error2), ams.shouldIncrementProcStateSeq(uidRec));

        // Background to foreground
        uidRec.setProcState = PROCESS_STATE_SERVICE;
        uidRec.curProcState = PROCESS_STATE_FOREGROUND_SERVICE;
        assertTrue(errorMsg.apply(error1), ams.shouldIncrementProcStateSeq(uidRec));

        // Foreground to background
        uidRec.setProcState = PROCESS_STATE_TOP;
        uidRec.curProcState = PROCESS_STATE_LAST_ACTIVITY;
        assertTrue(errorMsg.apply(error1), ams.shouldIncrementProcStateSeq(uidRec));
    }

    /**
     * This test verifies that process state changes are dispatched to observers based on the
     * changes they wanted to listen (this is specified when registering the observer).
     */
    @Test
    public void testDispatchUids_dispatchNeededChanges() throws RemoteException {
        final ActivityManagerService ams = new ActivityManagerService(mAppOpsService);
        when(mAppOpsService.checkOperation(AppOpsManager.OP_GET_USAGE_STATS, Process.myUid(), null))
                .thenReturn(AppOpsManager.MODE_ALLOWED);

        final int[] changesToObserve = {
            ActivityManager.UID_OBSERVER_PROCSTATE,
            ActivityManager.UID_OBSERVER_GONE,
            ActivityManager.UID_OBSERVER_IDLE,
            ActivityManager.UID_OBSERVER_ACTIVE,
            ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE
                    | ActivityManager.UID_OBSERVER_ACTIVE | ActivityManager.UID_OBSERVER_IDLE
        };
        final IUidObserver[] observers = new IUidObserver.Stub[changesToObserve.length];
        for (int i = 0; i < observers.length; ++i) {
            observers[i] = Mockito.mock(IUidObserver.Stub.class);
            when(observers[i].asBinder()).thenReturn((IBinder) observers[i]);
            ams.registerUidObserver(observers[i], changesToObserve[i] /* which */,
                    ActivityManager.PROCESS_STATE_UNKNOWN /* cutpoint */, null /* caller */);

            // When we invoke AMS.registerUidObserver, there are some interactions with observers[i]
            // mock in RemoteCallbackList class. We don't want to test those interactions and
            // at the same time, we don't want those to interfere with verifyNoMoreInteractions.
            // So, resetting the mock here.
            Mockito.reset(observers[i]);
        }

        // Add pending uid records each corresponding to a different change type UidRecord.CHANGE_*
        final int[] changesForPendingUidRecords = {
            UidRecord.CHANGE_PROCSTATE,
            UidRecord.CHANGE_GONE,
            UidRecord.CHANGE_GONE_IDLE,
            UidRecord.CHANGE_IDLE,
            UidRecord.CHANGE_ACTIVE
        };
        final int[] procStatesForPendingUidRecords = {
            ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
            ActivityManager.PROCESS_STATE_NONEXISTENT,
            ActivityManager.PROCESS_STATE_CACHED_EMPTY,
            ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
            ActivityManager.PROCESS_STATE_TOP
        };
        final Map<Integer, UidRecord.ChangeItem> changeItems = new HashMap<>();
        for (int i = 0; i < changesForPendingUidRecords.length; ++i) {
            final UidRecord.ChangeItem pendingChange = new UidRecord.ChangeItem();
            pendingChange.change = changesForPendingUidRecords[i];
            pendingChange.uid = i;
            pendingChange.processState = procStatesForPendingUidRecords[i];
            changeItems.put(changesForPendingUidRecords[i], pendingChange);
            ams.mPendingUidChanges.add(pendingChange);
        }

        ams.dispatchUidsChanged();
        // Verify the required changes have been dispatched to observers.
        for (int i = 0; i < observers.length; ++i) {
            final int changeToObserve = changesToObserve[i];
            final IUidObserver observerToTest = observers[i];
            if ((changeToObserve & ActivityManager.UID_OBSERVER_IDLE) != 0) {
                // Observer listens to uid idle changes, so change items corresponding to
                // UidRecord.CHANGE_IDLE or UidRecord.CHANGE_IDLE_GONE needs to be
                // delivered to this observer.
                final int[] changesToVerify = {
                    UidRecord.CHANGE_IDLE,
                    UidRecord.CHANGE_GONE_IDLE
                };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidIdle(changeItem.uid, changeItem.ephemeral);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_ACTIVE) != 0) {
                // Observer listens to uid active changes, so change items corresponding to
                // UidRecord.CHANGE_ACTIVE needs to be delivered to this observer.
                final int[] changesToVerify = { UidRecord.CHANGE_ACTIVE };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidActive(changeItem.uid);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_GONE) != 0) {
                // Observer listens to uid gone changes, so change items corresponding to
                // UidRecord.CHANGE_GONE or UidRecord.CHANGE_IDLE_GONE needs to be
                // delivered to this observer.
                final int[] changesToVerify = {
                        UidRecord.CHANGE_GONE,
                        UidRecord.CHANGE_GONE_IDLE
                };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidGone(changeItem.uid, changeItem.ephemeral);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_PROCSTATE) != 0) {
                // Observer listens to uid procState changes, so change items corresponding to
                // UidRecord.CHANGE_PROCSTATE or UidRecord.CHANGE_IDLE or UidRecord.CHANGE_ACTIVE
                // needs to be delivered to this observer.
                final int[] changesToVerify = {
                        UidRecord.CHANGE_PROCSTATE,
                        UidRecord.CHANGE_ACTIVE,
                        UidRecord.CHANGE_IDLE
                };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidStateChanged(
                                    changeItem.uid, changeItem.processState);
                        });
            }
            // Verify there are no other callbacks for this observer.
            verifyNoMoreInteractions(observerToTest);
        }
    }

    private interface ObserverChangesVerifier {
        void verify(IUidObserver observer, UidRecord.ChangeItem changeItem) throws RemoteException;
    }

    private void verifyObserverReceivedChanges(IUidObserver observer, int[] changesToVerify,
            Map<Integer, UidRecord.ChangeItem> changeItems, ObserverChangesVerifier verifier)
            throws RemoteException {
        for (int change : changesToVerify) {
            final UidRecord.ChangeItem changeItem = changeItems.get(change);
            verifier.verify(observer, changeItem);
        }
    }

    /**
     * This test verifies that process state changes are dispatched to observers only when they
     * change across the cutpoint (this is specified when registering the observer).
     */
    @Test
    public void testDispatchUidChanges_procStateCutpoint() throws RemoteException {
        final ActivityManagerService ams = new ActivityManagerService(mAppOpsService);
        final IUidObserver observer = Mockito.mock(IUidObserver.Stub.class);

        when(observer.asBinder()).thenReturn((IBinder) observer);
        ams.registerUidObserver(observer, ActivityManager.UID_OBSERVER_PROCSTATE /* which */,
                ActivityManager.PROCESS_STATE_SERVICE /* cutpoint */, null /* callingPackage */);
        // When we invoke AMS.registerUidObserver, there are some interactions with observer
        // mock in RemoteCallbackList class. We don't want to test those interactions and
        // at the same time, we don't want those to interfere with verifyNoMoreInteractions.
        // So, resetting the mock here.
        Mockito.reset(observer);

        final UidRecord.ChangeItem changeItem = new UidRecord.ChangeItem();
        changeItem.uid = TEST_UID;
        changeItem.change = UidRecord.CHANGE_PROCSTATE;
        changeItem.processState = ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
        ams.mPendingUidChanges.add(changeItem);
        ams.dispatchUidsChanged();
        // First process state message is always delivered regardless of whether the process state
        // change is above or below the cutpoint (PROCESS_STATE_SERVICE).
        verify(observer).onUidStateChanged(TEST_UID,
                ActivityManager.PROCESS_STATE_LAST_ACTIVITY);
        verifyNoMoreInteractions(observer);

        changeItem.processState = ActivityManager.PROCESS_STATE_RECEIVER;
        ams.mPendingUidChanges.add(changeItem);
        ams.dispatchUidsChanged();
        // Previous process state change is below cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is also below cutpoint, so no callback will be invoked.
        verifyNoMoreInteractions(observer);

        changeItem.processState = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
        ams.mPendingUidChanges.add(changeItem);
        ams.dispatchUidsChanged();
        // Previous process state change is below cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is above cutpoint, so callback will be invoked with the
        // current process state change.
        verify(observer).onUidStateChanged(TEST_UID,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        verifyNoMoreInteractions(observer);

        changeItem.processState = ActivityManager.PROCESS_STATE_TOP;
        ams.mPendingUidChanges.add(changeItem);
        ams.dispatchUidsChanged();
        // Previous process state change is above cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is also above cutpoint, so no callback will be invoked.
        verifyNoMoreInteractions(observer);

        changeItem.processState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
        ams.mPendingUidChanges.add(changeItem);
        ams.dispatchUidsChanged();
        // Previous process state change is above cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is below cutpoint, so callback will be invoked with the
        // current process state change.
        verify(observer).onUidStateChanged(TEST_UID, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        verifyNoMoreInteractions(observer);
    }

    /**
     * This test verifies that {@link ActivityManagerService#mValidateUids} which is a
     * part of dumpsys is correctly updated.
     */
    @Test
    public void testDispatchUidChanges_validateUidsUpdated() {
        final ActivityManagerService ams = new ActivityManagerService(mAppOpsService);

        final int[] changesForPendingItems = {
            UidRecord.CHANGE_PROCSTATE,
            UidRecord.CHANGE_GONE,
            UidRecord.CHANGE_GONE_IDLE,
            UidRecord.CHANGE_IDLE,
            UidRecord.CHANGE_ACTIVE
        };
        final int[] procStatesForPendingItems = {
            ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
            ActivityManager.PROCESS_STATE_CACHED_EMPTY,
            ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
            ActivityManager.PROCESS_STATE_SERVICE,
            ActivityManager.PROCESS_STATE_RECEIVER
        };
        final ArrayList<UidRecord.ChangeItem> pendingItemsForUids
                = new ArrayList<>(changesForPendingItems.length);
        for (int i = 0; i < changesForPendingItems.length; ++i) {
            final UidRecord.ChangeItem item = new UidRecord.ChangeItem();
            item.uid = i;
            item.change = changesForPendingItems[i];
            item.processState = procStatesForPendingItems[i];
            pendingItemsForUids.add(i, item);
        }

        // Verify that when there no observers listening to uid state changes, then there will
        // be no changes to validateUids.
        ams.mPendingUidChanges.addAll(pendingItemsForUids);
        ams.dispatchUidsChanged();
        assertEquals("No observers registered, so validateUids should be empty",
                0, ams.mValidateUids.size());

        final IUidObserver observer = Mockito.mock(IUidObserver.Stub.class);
        when(observer.asBinder()).thenReturn((IBinder) observer);
        ams.registerUidObserver(observer, 0, 0, null);
        // Verify that when observers are registered, then validateUids is correctly updated.
        ams.mPendingUidChanges.addAll(pendingItemsForUids);
        ams.dispatchUidsChanged();
        for (int i = 0; i < pendingItemsForUids.size(); ++i) {
            final UidRecord.ChangeItem item = pendingItemsForUids.get(i);
            final UidRecord validateUidRecord = ams.mValidateUids.get(item.uid);
            if (item.change == UidRecord.CHANGE_GONE || item.change == UidRecord.CHANGE_GONE_IDLE) {
                assertNull("validateUidRecord should be null since the change is either "
                        + "CHANGE_GONE or CHANGE_GONE_IDLE", validateUidRecord);
            } else {
                assertNotNull("validateUidRecord should not be null since the change is neither "
                        + "CHANGE_GONE nor CHANGE_GONE_IDLE", validateUidRecord);
                assertEquals("processState: " + item.processState + " curProcState: "
                        + validateUidRecord.curProcState + " should have been equal",
                        item.processState, validateUidRecord.curProcState);
                assertEquals("processState: " + item.processState + " setProcState: "
                        + validateUidRecord.curProcState + " should have been equal",
                        item.processState, validateUidRecord.setProcState);
                if (item.change == UidRecord.CHANGE_IDLE) {
                    assertTrue("UidRecord.idle should be updated to true for CHANGE_IDLE",
                            validateUidRecord.idle);
                } else if (item.change == UidRecord.CHANGE_ACTIVE) {
                    assertFalse("UidRecord.idle should be updated to false for CHANGE_ACTIVE",
                            validateUidRecord.idle);
                }
            }
        }

        // Verify that when uid state changes to CHANGE_GONE or CHANGE_GONE_IDLE, then it
        // will be removed from validateUids.
        assertNotEquals("validateUids should not be empty", 0, ams.mValidateUids.size());
        for (int i = 0; i < pendingItemsForUids.size(); ++i) {
            final UidRecord.ChangeItem item = pendingItemsForUids.get(i);
            // Assign CHANGE_GONE_IDLE to some items and CHANGE_GONE to the others, using even/odd
            // distribution for this assignment.
            item.change = (i % 2) == 0 ? UidRecord.CHANGE_GONE_IDLE : UidRecord.CHANGE_GONE;
        }
        ams.mPendingUidChanges.addAll(pendingItemsForUids);
        ams.dispatchUidsChanged();
        assertEquals("validateUids should be empty, validateUids: " + ams.mValidateUids,
                0, ams.mValidateUids.size());
    }
}