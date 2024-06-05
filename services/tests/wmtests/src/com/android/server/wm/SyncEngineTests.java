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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.BLASTSyncEngine.METHOD_NONE;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowContainer.SYNC_STATE_NONE;
import static com.android.server.wm.WindowContainer.SYNC_STATE_READY;
import static com.android.server.wm.WindowState.BLAST_TIMEOUT_DURATION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.spy;

import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.server.testutils.StubTransaction;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Test class for {@link BLASTSyncEngine}.
 *
 * Build/Install/Run:
 *  atest WmTests:SyncEngineTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class SyncEngineTests extends WindowTestsBase {

    @Before
    public void setUp() {
        spyOn(mWm.mWindowPlacerLocked);
    }

    @Test
    public void testTrivialSyncCallback() {
        TestWindowContainer mockWC = new TestWindowContainer(mWm, false /* waiter */);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, mockWC);
        // The traversal is not requested because ready is not set.
        verify(mWm.mWindowPlacerLocked, times(0)).requestTraversal();

        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        bse.setReady(id);
        // Make sure a traversal is requested
        verify(mWm.mWindowPlacerLocked).requestTraversal();
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());

        // make sure it was cleaned-up (no second callback)
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(anyInt(), any());
    }

    @Test
    public void testWaitingSyncCallback() {
        TestWindowContainer mockWC = new TestWindowContainer(mWm, true /* waiter */);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, mockWC);
        bse.setReady(id);
        // Make sure traversals requested.
        verify(mWm.mWindowPlacerLocked).requestTraversal();
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        assertTrue(mockWC.onSyncFinishedDrawing());
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());

        // The sync is not finished for a relaunching activity.
        id = startSyncSet(bse, listener);
        final ActivityRecord r = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final WindowState w = mock(WindowState.class);
        doReturn(true).when(w).isVisibleRequested();
        doReturn(true).when(w).fillsParent();
        doReturn(true).when(w).isSyncFinished(any());
        r.mChildren.add(w);
        bse.addToSyncSet(id, r);
        r.onSyncFinishedDrawing();
        assertTrue(r.isSyncFinished(r.getSyncGroup()));
        r.startRelaunching();
        assertFalse(r.isSyncFinished(r.getSyncGroup()));
        r.finishRelaunching();
        assertTrue(r.isSyncFinished(r.getSyncGroup()));
        assertEquals(SYNC_STATE_READY, r.mSyncState);

        // If the container has finished the sync, isSyncFinished should not change the sync state.
        final BLASTSyncEngine.SyncGroup syncGroup = r.getSyncGroup();
        r.finishSync(mTransaction, syncGroup, false /* cancel */);
        assertEquals(SYNC_STATE_NONE, r.mSyncState);
        assertTrue(r.isSyncFinished(syncGroup));
        assertEquals(SYNC_STATE_NONE, r.mSyncState);
    }

    @Test
    public void testFinishSyncByStartingWindow() {
        final ActivityRecord taskRoot = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = taskRoot.getTask();
        final ActivityRecord translucentTop = new ActivityBuilder(mAtm).setTask(task)
                .setActivityTheme(android.R.style.Theme_Translucent).build();
        createWindow(null, TYPE_BASE_APPLICATION, taskRoot, "win");
        final WindowState startingWindow = createWindow(null, TYPE_APPLICATION_STARTING,
                translucentTop, "starting");
        startingWindow.mStartingData = new SnapshotStartingData(mWm, null, 0);
        task.mSharedStartingData = startingWindow.mStartingData;
        task.prepareSync();

        final BLASTSyncEngine.SyncGroup group = mock(BLASTSyncEngine.SyncGroup.class);
        assertFalse(task.isSyncFinished(group));
        startingWindow.onSyncFinishedDrawing();
        assertTrue(task.isSyncFinished(group));
    }

    @Test
    public void testInvisibleSyncCallback() {
        TestWindowContainer mockWC = new TestWindowContainer(mWm, true /* waiter */);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, mockWC);
        bse.setReady(id);
        // Make sure traversals requested.
        verify(mWm.mWindowPlacerLocked).requestTraversal();
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        // Finish sync if invisible.
        mockWC.mVisibleRequested = false;
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());
        assertEquals(SYNC_STATE_NONE, mockWC.mSyncState);
    }

    @Test
    public void testWaitForChildrenCallback() {
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC2 = new TestWindowContainer(mWm, true /* waiter */);
        parentWC.addChild(childWC, POSITION_TOP);
        parentWC.addChild(childWC2, POSITION_TOP);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, parentWC);
        bse.setReady(id);
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        parentWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        childWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        childWC2.onSyncFinishedDrawing();
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());
        assertEquals(SYNC_STATE_NONE, parentWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, childWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, childWC2.mSyncState);
    }

    @Test
    public void testWaitForParentCallback() {
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC = new TestWindowContainer(mWm, true /* waiter */);
        parentWC.addChild(childWC, POSITION_TOP);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, parentWC);
        bse.setReady(id);
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        childWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        parentWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());
        assertEquals(SYNC_STATE_NONE, parentWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, childWC.mSyncState);
    }

    @Test
    public void testFillsParent() {
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer topChildWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer botChildWC = new TestWindowContainer(mWm, true /* waiter */);
        topChildWC.mFillsParent = botChildWC.mFillsParent = true;
        parentWC.addChild(topChildWC, POSITION_TOP);
        parentWC.addChild(botChildWC, POSITION_BOTTOM);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, parentWC);
        bse.setReady(id);
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        parentWC.onSyncFinishedDrawing();
        topChildWC.onSyncFinishedDrawing();
        // Even though bottom isn't finished, we should see callback because it is occluded by top.
        assertFalse(botChildWC.isSyncFinished(botChildWC.getSyncGroup()));
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());

        assertEquals(SYNC_STATE_NONE, parentWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, botChildWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, topChildWC.mSyncState);
    }

    @Test
    public void testReparentOut() {
        TestWindowContainer nonMemberParentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer topChildWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer botChildWC = new TestWindowContainer(mWm, true /* waiter */);
        parentWC.addChild(topChildWC, POSITION_TOP);
        parentWC.addChild(botChildWC, POSITION_BOTTOM);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, parentWC);
        bse.setReady(id);
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        parentWC.onSyncFinishedDrawing();
        topChildWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        // reparent out cancels
        botChildWC.reparent(nonMemberParentWC, POSITION_TOP);
        assertEquals(SYNC_STATE_NONE, botChildWC.mSyncState);

        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());
        assertEquals(SYNC_STATE_NONE, parentWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, topChildWC.mSyncState);
    }

    @Test
    public void testReparentIn() {
        TestWindowContainer nonMemberParentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer topChildWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer botChildWC = new TestWindowContainer(mWm, true /* waiter */);
        parentWC.addChild(topChildWC, POSITION_TOP);
        nonMemberParentWC.addChild(botChildWC, POSITION_BOTTOM);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, parentWC);
        bse.setReady(id);
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        parentWC.onSyncFinishedDrawing();
        topChildWC.onSyncFinishedDrawing();

        // No-longer finished because new child
        botChildWC.reparent(parentWC, POSITION_BOTTOM);
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        botChildWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());
        assertEquals(SYNC_STATE_NONE, parentWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, topChildWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, botChildWC.mSyncState);

        // If the appearance of window won't change after reparenting, its sync state can be kept.
        final WindowState w = createWindow(null, TYPE_BASE_APPLICATION, "win");
        parentWC.onRequestedOverrideConfigurationChanged(w.getConfiguration());
        w.reparent(botChildWC, POSITION_TOP);
        parentWC.prepareSync();
        // Assume the window has drawn with the latest configuration.
        makeLastConfigReportedToClient(w, true /* visible */);
        assertTrue(w.onSyncFinishedDrawing());
        assertEquals(SYNC_STATE_READY, w.mSyncState);
        w.reparent(topChildWC, POSITION_TOP);
        assertEquals(SYNC_STATE_READY, w.mSyncState);
    }

    @Test
    public void testRemoval() {
        // Need different transactions to verify stuff
        mWm.mTransactionFactory = () -> spy(new StubTransaction());
        TestWindowContainer rootWC = new TestWindowContainer(mWm, false /* waiter */);
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer topChildWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer botChildWC = new TestWindowContainer(mWm, true /* waiter */);
        rootWC.addChild(parentWC, POSITION_TOP);
        parentWC.addChild(topChildWC, POSITION_TOP);
        parentWC.addChild(botChildWC, POSITION_BOTTOM);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, parentWC);
        final BLASTSyncEngine.SyncGroup syncGroup = parentWC.mSyncGroup;
        bse.setReady(id);
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        parentWC.onSyncFinishedDrawing();
        topChildWC.removeImmediately();
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        // Removal should merge transaction into parent
        verify(parentWC.mSyncTransaction, times(1)).merge(eq(topChildWC.mSyncTransaction));
        assertEquals(SYNC_STATE_NONE, topChildWC.mSyncState);

        // Removal of a sync-root should merge transaction into orphan
        parentWC.removeImmediately();
        final SurfaceControl.Transaction orphan = syncGroup.getOrphanTransaction();
        verify(orphan, times(1)).merge(eq(parentWC.mSyncTransaction));

        // Then the orphan transaction should be merged into sync
        bse.onSurfacePlacement();
        final ArgumentCaptor<SurfaceControl.Transaction> merged =
                ArgumentCaptor.forClass(SurfaceControl.Transaction.class);
        verify(listener, times(1)).onTransactionReady(eq(id), merged.capture());
        final SurfaceControl.Transaction mergedTransaction = merged.getValue();
        verify(mergedTransaction, times(1)).merge(eq(orphan));

        assertEquals(SYNC_STATE_NONE, parentWC.mSyncState);
        assertEquals(SYNC_STATE_NONE, botChildWC.mSyncState);
    }

    @Test
    public void testNonBlastMethod() {
        mAppWindow = createWindow(null, TYPE_BASE_APPLICATION, "mAppWindow");

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        final int id = startSyncSet(bse, listener);
        bse.setSyncMethod(id, METHOD_NONE);
        bse.addToSyncSet(id, mAppWindow.mToken);
        mAppWindow.prepareSync();
        assertFalse(mAppWindow.shouldSyncWithBuffers());

        mAppWindow.removeImmediately();
    }

    @Test
    public void testQueueSyncSet() {
        final TestHandler testHandler = new TestHandler(null);
        TestWindowContainer mockWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer mockWC2 = new TestWindowContainer(mWm, true /* waiter */);

        final BLASTSyncEngine bse = createTestBLASTSyncEngine(testHandler);

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        int id = startSyncSet(bse, listener);
        bse.addToSyncSet(id, mockWC);
        bse.setReady(id);
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(eq(id), notNull());

        final int[] nextId = new int[]{-1};
        bse.queueSyncSet(
                () -> nextId[0] = startSyncSet(bse, listener),
                () -> {
                    bse.setReady(nextId[0]);
                    bse.addToSyncSet(nextId[0], mockWC2);
                });

        // Make sure it is queued
        assertEquals(-1, nextId[0]);

        // Finish the original sync and see that we've started a new sync-set immediately but
        // that the readiness was posted.
        mockWC.onSyncFinishedDrawing();
        verify(mWm.mWindowPlacerLocked).requestTraversal();
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());

        assertTrue(nextId[0] != -1);
        assertFalse(bse.isReady(nextId[0]));

        // now make sure the applySync callback was posted.
        testHandler.flush();
        assertTrue(bse.isReady(nextId[0]));
    }

    @Test
    public void testStratifiedParallel() {
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC = new TestWindowContainer(mWm, true /* waiter */);
        parentWC.addChild(childWC, POSITION_TOP);
        childWC.mVisibleRequested = true;
        childWC.mFillsParent = true;

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listenerChild = mock(
                BLASTSyncEngine.TransactionReadyListener.class);
        BLASTSyncEngine.TransactionReadyListener listenerParent = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        // Start a sync-set for the "inner" stuff
        int childSync = startSyncSet(bse, listenerChild);
        bse.addToSyncSet(childSync, childWC);
        bse.setReady(childSync);

        // Start sync-set for the "outer" stuff but explicitly parallel (it should ignore child)
        int parentSync = startSyncSet(bse, listenerParent, true /* parallel */);
        bse.addToSyncSet(parentSync, parentWC);
        bse.setReady(parentSync);

        bse.onSurfacePlacement();
        // Nothing should have happened yet
        verify(listenerChild, times(0)).onTransactionReady(anyInt(), any());
        verify(listenerParent, times(0)).onTransactionReady(anyInt(), any());

        // Now, make PARENT ready, since they are in parallel, this should work
        parentWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();

        // Parent should become ready while child is still waiting.
        verify(listenerParent, times(1)).onTransactionReady(eq(parentSync), notNull());
        verify(listenerChild, times(0)).onTransactionReady(anyInt(), any());

        // Child should still work
        childWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();
        verify(listenerChild, times(1)).onTransactionReady(eq(childSync), notNull());
    }

    @Test
    public void testDependencies() {
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC2 = new TestWindowContainer(mWm, true /* waiter */);
        parentWC.addChild(childWC, POSITION_TOP);
        childWC.mVisibleRequested = true;
        childWC.mFillsParent = true;
        childWC2.mVisibleRequested = true;
        childWC2.mFillsParent = true;

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        // This is non-parallel, so it is waiting on the child as-well
        int sync1 = startSyncSet(bse, listener);
        bse.addToSyncSet(sync1, parentWC);
        bse.setReady(sync1);

        // Create one which will end-up depending on the *next* sync
        int sync2 = startSyncSet(bse, listener, true /* parallel */);

        // If another sync tries to sync on the same subtree, it must now serialize with the other.
        int sync3 = startSyncSet(bse, listener, true /* parallel */);
        bse.addToSyncSet(sync3, childWC);
        bse.addToSyncSet(sync3, childWC2);
        bse.setReady(sync3);

        // This will depend on sync3.
        int sync4 = startSyncSet(bse, listener, true /* parallel */);
        bse.addToSyncSet(sync4, childWC2);
        bse.setReady(sync4);

        // This makes sync2 depend on sync3. Since both sync2 and sync4 depend on sync3, when sync3
        // finishes, sync2 should run first since it was created first.
        bse.addToSyncSet(sync2, childWC2);
        bse.setReady(sync2);

        childWC.onSyncFinishedDrawing();
        childWC2.onSyncFinishedDrawing();
        bse.onSurfacePlacement();

        // Nothing should be ready yet since everything ultimately depends on sync1.
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        parentWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();

        // They should all be ready, now, so just verify that the order is expected
        InOrder readyOrder = Mockito.inOrder(listener);
        // sync1 is the first one, so it should call ready first.
        readyOrder.verify(listener).onTransactionReady(eq(sync1), any());
        // everything else depends on sync3, so it should call ready next.
        readyOrder.verify(listener).onTransactionReady(eq(sync3), any());
        // both sync2 and sync4 depend on sync3, but sync2 started first, so it should go next.
        readyOrder.verify(listener).onTransactionReady(eq(sync2), any());
        readyOrder.verify(listener).onTransactionReady(eq(sync4), any());
    }

    @Test
    public void testStratifiedParallelParentFirst() {
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC = new TestWindowContainer(mWm, true /* waiter */);
        parentWC.addChild(childWC, POSITION_TOP);
        childWC.mVisibleRequested = true;
        childWC.mFillsParent = true;

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        // This is parallel, so it should ignore children
        int sync1 = startSyncSet(bse, listener, true /* parallel */);
        bse.addToSyncSet(sync1, parentWC);
        bse.setReady(sync1);

        int sync2 = startSyncSet(bse, listener, true /* parallel */);
        bse.addToSyncSet(sync2, childWC);
        bse.setReady(sync2);

        childWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();

        // Sync2 should have run in parallel
        verify(listener, times(1)).onTransactionReady(eq(sync2), any());
        verify(listener, times(0)).onTransactionReady(eq(sync1), any());

        parentWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();

        verify(listener, times(1)).onTransactionReady(eq(sync1), any());
    }

    @Test
    public void testDependencyCycle() {
        TestWindowContainer parentWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC2 = new TestWindowContainer(mWm, true /* waiter */);
        TestWindowContainer childWC3 = new TestWindowContainer(mWm, true /* waiter */);
        parentWC.addChild(childWC, POSITION_TOP);
        childWC.mVisibleRequested = true;
        childWC.mFillsParent = true;
        childWC2.mVisibleRequested = true;
        childWC2.mFillsParent = true;
        childWC3.mVisibleRequested = true;
        childWC3.mFillsParent = true;

        final BLASTSyncEngine bse = createTestBLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener listener = mock(
                BLASTSyncEngine.TransactionReadyListener.class);

        // This is non-parallel, so it is waiting on the child as-well
        int sync1 = startSyncSet(bse, listener);
        bse.addToSyncSet(sync1, parentWC);
        bse.setReady(sync1);

        // Sync 2 depends on sync1 AND childWC2
        int sync2 = startSyncSet(bse, listener, true /* parallel */);
        bse.addToSyncSet(sync2, childWC);
        bse.addToSyncSet(sync2, childWC2);
        bse.setReady(sync2);

        // Sync 3 depends on sync2 AND childWC3
        int sync3 = startSyncSet(bse, listener, true /* parallel */);
        bse.addToSyncSet(sync3, childWC2);
        bse.addToSyncSet(sync3, childWC3);
        bse.setReady(sync3);

        // Now make sync1 depend on WC3 (which would make it depend on sync3). This would form
        // a cycle, so it should instead move childWC3 into sync1.
        bse.addToSyncSet(sync1, childWC3);

        // Sync3 should no-longer have childWC3 as a root-member since a window can currently only
        // be directly watched by 1 syncgroup maximum (due to implementation of isSyncFinished).
        assertFalse(bse.getSyncSet(sync3).mRootMembers.contains(childWC3));

        childWC3.onSyncFinishedDrawing();
        childWC2.onSyncFinishedDrawing();
        parentWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();

        // make sure sync3 hasn't run even though all its (original) members are ready
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        // Now finish the last container and make sure everything finishes (didn't "deadlock" due
        // to a dependency cycle.
        childWC.onSyncFinishedDrawing();
        bse.onSurfacePlacement();

        InOrder readyOrder = Mockito.inOrder(listener);
        readyOrder.verify(listener).onTransactionReady(eq(sync1), any());
        readyOrder.verify(listener).onTransactionReady(eq(sync2), any());
        readyOrder.verify(listener).onTransactionReady(eq(sync3), any());
    }

    static int startSyncSet(BLASTSyncEngine engine,
            BLASTSyncEngine.TransactionReadyListener listener) {
        return engine.startSyncSet(listener, BLAST_TIMEOUT_DURATION, "Test", false /* parallel */);
    }

    static int startSyncSet(BLASTSyncEngine engine,
            BLASTSyncEngine.TransactionReadyListener listener, boolean parallel) {
        return engine.startSyncSet(listener, BLAST_TIMEOUT_DURATION, "Test", parallel);
    }

    static class TestWindowContainer extends WindowContainer {
        final boolean mWaiter;
        boolean mVisibleRequested = true;
        boolean mFillsParent = false;

        TestWindowContainer(WindowManagerService wms, boolean waiter) {
            super(wms);
            mWaiter = waiter;
            mDisplayContent = wms.getDefaultDisplayContentLocked();
        }

        @Override
        boolean prepareSync() {
            if (!super.prepareSync()) {
                return false;
            }
            if (mWaiter) {
                mSyncState = SYNC_STATE_WAITING_FOR_DRAW;
            }
            return true;
        }

        @Override
        void createSurfaceControl(boolean force) {
            // nothing
        }

        @Override
        boolean isVisibleRequested() {
            return mVisibleRequested;
        }

        @Override
        boolean fillsParent() {
            return mFillsParent;
        }
    }
}
