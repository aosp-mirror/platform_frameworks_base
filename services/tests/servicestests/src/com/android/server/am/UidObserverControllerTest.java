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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_CAPABILITY_ALL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NETWORK;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_RECENT;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IUidObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DebugUtils;
import android.util.Pair;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.android.server.am.UidObserverController.ChangeRecord;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@SmallTest
public class UidObserverControllerTest {
    private static final int TEST_UID1 = 1111;
    private static final int TEST_UID2 = 2222;
    private static final int TEST_UID3 = 3333;

    private static final String TEST_PKG1 = "com.example1";
    private static final String TEST_PKG2 = "com.example2";
    private static final String TEST_PKG3 = "com.example3";

    private UidObserverController mUidObserverController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mUidObserverController = new UidObserverController(mock(Handler.class));
    }

    @Test
    public void testEnqueueUidChange() {
        int change = mUidObserverController.enqueueUidChange(null, TEST_UID1,
                UidRecord.CHANGE_ACTIVE, PROCESS_STATE_FOREGROUND_SERVICE,
                PROCESS_CAPABILITY_ALL, 0, false);
        assertEquals("expected=ACTIVE,actual=" + changeToStr(change),
                UidRecord.CHANGE_ACTIVE, change);
        assertPendingChange(TEST_UID1, UidRecord.CHANGE_ACTIVE, PROCESS_STATE_FOREGROUND_SERVICE,
                PROCESS_CAPABILITY_ALL, 0, false, null);
        final ChangeRecord record1 = getLatestPendingChange(TEST_UID1);
        assertNull(getLatestPendingChange(TEST_UID2));

        final ChangeRecord record2 = new ChangeRecord();
        change = mUidObserverController.enqueueUidChange(record2, TEST_UID2,
                UidRecord.CHANGE_CACHED, PROCESS_STATE_CACHED_RECENT, PROCESS_CAPABILITY_NONE,
                99, true);
        assertEquals("expected=ACTIVE,actual=" + changeToStr(change),
                UidRecord.CHANGE_CACHED, change);
        assertPendingChange(TEST_UID1, UidRecord.CHANGE_ACTIVE, PROCESS_STATE_FOREGROUND_SERVICE,
                PROCESS_CAPABILITY_ALL, 0, false, null);
        assertPendingChange(TEST_UID2, UidRecord.CHANGE_CACHED, PROCESS_STATE_CACHED_RECENT,
                PROCESS_CAPABILITY_NONE, 99, true, record2);

        change = mUidObserverController.enqueueUidChange(record1, TEST_UID1,
                UidRecord.CHANGE_UNCACHED, PROCESS_STATE_TOP, PROCESS_CAPABILITY_ALL, 0, false);
        assertEquals("expected=ACTIVE|UNCACHED,actual=" + changeToStr(change),
                UidRecord.CHANGE_ACTIVE | UidRecord.CHANGE_UNCACHED, change);
        assertPendingChange(TEST_UID1, UidRecord.CHANGE_ACTIVE | UidRecord.CHANGE_UNCACHED,
                PROCESS_STATE_TOP, PROCESS_CAPABILITY_ALL, 0, false, record1);
        assertPendingChange(TEST_UID2, UidRecord.CHANGE_CACHED, PROCESS_STATE_CACHED_RECENT,
                PROCESS_CAPABILITY_NONE, 99, true, record2);
    }

    @Test
    public void testMergeWithPendingChange() {
        // Map of expectedChange -> {(currentChange, pendingChange)}
        final SparseArray<Pair<Integer, Integer>> changesToVerify = new SparseArray<>();

        changesToVerify.put(UidRecord.CHANGE_ACTIVE,
                Pair.create(UidRecord.CHANGE_ACTIVE, UidRecord.CHANGE_IDLE));
        changesToVerify.put(UidRecord.CHANGE_IDLE,
                Pair.create(UidRecord.CHANGE_IDLE, UidRecord.CHANGE_ACTIVE));
        changesToVerify.put(UidRecord.CHANGE_CACHED,
                Pair.create(UidRecord.CHANGE_CACHED, UidRecord.CHANGE_UNCACHED));
        changesToVerify.put(UidRecord.CHANGE_UNCACHED,
                Pair.create(UidRecord.CHANGE_UNCACHED, UidRecord.CHANGE_CACHED));
        changesToVerify.put(UidRecord.CHANGE_ACTIVE | UidRecord.CHANGE_UNCACHED,
                Pair.create(UidRecord.CHANGE_ACTIVE, UidRecord.CHANGE_UNCACHED));
        changesToVerify.put(UidRecord.CHANGE_IDLE | UidRecord.CHANGE_CACHED,
                Pair.create(UidRecord.CHANGE_IDLE, UidRecord.CHANGE_CACHED));
        changesToVerify.put(UidRecord.CHANGE_GONE,
                Pair.create(UidRecord.CHANGE_GONE, UidRecord.CHANGE_ACTIVE));
        changesToVerify.put(UidRecord.CHANGE_GONE,
                Pair.create(UidRecord.CHANGE_GONE, UidRecord.CHANGE_CACHED));
        changesToVerify.put(UidRecord.CHANGE_PROCSTATE | UidRecord.CHANGE_CAPABILITY,
                Pair.create(UidRecord.CHANGE_PROCSTATE, UidRecord.CHANGE_CAPABILITY));

        for (int i = 0; i < changesToVerify.size(); ++i) {
            final int expectedChange = changesToVerify.keyAt(i);
            final int currentChange = changesToVerify.valueAt(i).first;
            final int pendingChange = changesToVerify.valueAt(i).second;
            assertEquals("current=" + changeToStr(currentChange) + ", pending="
                            + changeToStr(pendingChange) + "exp=" + changeToStr(expectedChange),
                    expectedChange, UidObserverController.mergeWithPendingChange(
                    currentChange, pendingChange));
        }
    }

    @Test
    public void testDispatchUidsChanged() throws RemoteException {
        addPendingChange(TEST_UID1, UidRecord.CHANGE_ACTIVE | UidRecord.CHANGE_PROCSTATE,
                PROCESS_STATE_TOP, 0, PROCESS_CAPABILITY_ALL, false);

        final IUidObserver observer1 = mock(IUidObserver.Stub.class);
        registerObserver(observer1,
                ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_ACTIVE,
                PROCESS_STATE_IMPORTANT_FOREGROUND, TEST_PKG2, TEST_UID2);
        final IUidObserver observer2 = mock(IUidObserver.Stub.class);
        registerObserver(observer2,
                ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_CAPABILITY,
                PROCESS_STATE_SERVICE, TEST_PKG3, TEST_UID3);

        mUidObserverController.dispatchUidsChanged();
        verify(observer1).onUidStateChanged(TEST_UID1, PROCESS_STATE_TOP,
                0, PROCESS_CAPABILITY_ALL);
        verify(observer1).onUidActive(TEST_UID1);
        verifyNoMoreInteractions(observer1);
        verify(observer2).onUidStateChanged(TEST_UID1, PROCESS_STATE_TOP,
                0, PROCESS_CAPABILITY_ALL);
        verifyNoMoreInteractions(observer2);

        addPendingChange(TEST_UID1, UidRecord.CHANGE_PROCSTATE, PROCESS_STATE_IMPORTANT_BACKGROUND,
                99, PROCESS_CAPABILITY_FOREGROUND_LOCATION, false);
        mUidObserverController.dispatchUidsChanged();
        verify(observer1).onUidStateChanged(TEST_UID1, PROCESS_STATE_IMPORTANT_BACKGROUND,
                99, PROCESS_CAPABILITY_FOREGROUND_LOCATION);
        verifyNoMoreInteractions(observer1);
        verifyNoMoreInteractions(observer2);

        addPendingChange(TEST_UID1, UidRecord.CHANGE_PROCSTATE, PROCESS_STATE_RECEIVER,
                111, PROCESS_CAPABILITY_NONE, false);
        mUidObserverController.dispatchUidsChanged();
        verify(observer2).onUidStateChanged(TEST_UID1, PROCESS_STATE_RECEIVER,
                111, PROCESS_CAPABILITY_NONE);
        verifyNoMoreInteractions(observer1);
        verifyNoMoreInteractions(observer2);

        addPendingChange(TEST_UID1, UidRecord.CHANGE_PROCSTATE | UidRecord.CHANGE_CAPABILITY,
                PROCESS_STATE_RECEIVER, 111, PROCESS_CAPABILITY_NETWORK, false);
        mUidObserverController.dispatchUidsChanged();
        verify(observer2).onUidStateChanged(TEST_UID1, PROCESS_STATE_RECEIVER,
                111, PROCESS_CAPABILITY_NETWORK);
        verifyNoMoreInteractions(observer1);
        verifyNoMoreInteractions(observer2);

        unregisterObserver(observer1);

        addPendingChange(TEST_UID1, UidRecord.CHANGE_PROCSTATE, PROCESS_STATE_TOP,
                112, PROCESS_CAPABILITY_ALL, false);
        mUidObserverController.dispatchUidsChanged();
        verify(observer2).onUidStateChanged(TEST_UID1, PROCESS_STATE_TOP,
                112, PROCESS_CAPABILITY_ALL);
        verifyNoMoreInteractions(observer1);
        verifyNoMoreInteractions(observer2);

        unregisterObserver(observer2);

        addPendingChange(TEST_UID1, UidRecord.CHANGE_PROCSTATE, PROCESS_STATE_CACHED_RECENT,
                112, PROCESS_CAPABILITY_NONE, false);
        mUidObserverController.dispatchUidsChanged();
        verifyNoMoreInteractions(observer1);
        verifyNoMoreInteractions(observer2);
    }

    private void registerObserver(IUidObserver observer, int which, int cutpoint,
            String callingPackage, int callingUid) {
        when(observer.asBinder()).thenReturn((IBinder) observer);
        mUidObserverController.register(observer, which, cutpoint, callingPackage, callingUid);
        Mockito.reset(observer);
    }

    private void unregisterObserver(IUidObserver observer) {
        when(observer.asBinder()).thenReturn((IBinder) observer);
        mUidObserverController.unregister(observer);
        Mockito.reset(observer);
    }

    private void addPendingChange(int uid, int change, int procState, long procStateSeq,
            int capability, boolean ephemeral) {
        final ChangeRecord record = new ChangeRecord();
        record.uid = uid;
        record.change = change;
        record.procState = procState;
        record.procStateSeq = procStateSeq;
        record.capability = capability;
        record.ephemeral = ephemeral;
        mUidObserverController.getPendingUidChangesForTest().add(record);
    }

    private void assertPendingChange(int uid, int change, int procState, long procStateSeq,
            int capability, boolean ephemeral, ChangeRecord expectedRecord) {
        final ChangeRecord record = getLatestPendingChange(uid);
        assertNotNull(record);
        if (expectedRecord != null) {
            assertEquals(expectedRecord, record);
        }
        assertEquals(change, record.change);
        assertEquals(procState, record.procState);
        assertEquals(procStateSeq, record.procStateSeq);
        assertEquals(capability, record.capability);
        assertEquals(ephemeral, record.ephemeral);
    }

    private ChangeRecord getLatestPendingChange(int uid) {
        final ArrayList<ChangeRecord> changeRecords = mUidObserverController
                .getPendingUidChangesForTest();
        for (int i = changeRecords.size() - 1; i >= 0; --i) {
            final ChangeRecord record = changeRecords.get(i);
            if (record.uid == uid) {
                return record;
            }
        }
        return null;
    }

    private static String changeToStr(int change) {
        return DebugUtils.flagsToString(UidRecord.class, "CHANGE_", change);
    }
}
