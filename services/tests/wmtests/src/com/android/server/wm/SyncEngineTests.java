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

import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.BLASTSyncEngine.METHOD_BLAST;
import static com.android.server.wm.BLASTSyncEngine.METHOD_NONE;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowContainer.SYNC_STATE_NONE;
import static com.android.server.wm.WindowState.BLAST_TIMEOUT_DURATION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.spy;

import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

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
        // Make sure a traversal is requested
        verify(mWm.mWindowPlacerLocked, times(1)).requestTraversal();

        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        bse.setReady(id);
        // Make sure a traversal is requested
        verify(mWm.mWindowPlacerLocked, times(2)).requestTraversal();
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
        // Make sure traversals requested (one for add and another for setReady)
        verify(mWm.mWindowPlacerLocked, times(2)).requestTraversal();
        bse.onSurfacePlacement();
        verify(listener, times(0)).onTransactionReady(anyInt(), any());

        mockWC.onSyncFinishedDrawing();
        // Make sure a (third) traversal is requested.
        verify(mWm.mWindowPlacerLocked, times(3)).requestTraversal();
        bse.onSurfacePlacement();
        verify(listener, times(1)).onTransactionReady(eq(id), notNull());
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
        // Make sure traversals requested (one for add and another for setReady)
        verify(mWm.mWindowPlacerLocked, times(2)).requestTraversal();
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
        assertFalse(botChildWC.isSyncFinished());
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

        int id = startSyncSet(bse, listener, METHOD_NONE);
        bse.addToSyncSet(id, mAppWindow.mToken);
        mAppWindow.prepareSync();
        assertFalse(mAppWindow.shouldSyncWithBuffers());

        mAppWindow.removeImmediately();
    }

    static int startSyncSet(BLASTSyncEngine engine,
            BLASTSyncEngine.TransactionReadyListener listener) {
        return startSyncSet(engine, listener, METHOD_BLAST);
    }

    static int startSyncSet(BLASTSyncEngine engine,
            BLASTSyncEngine.TransactionReadyListener listener, int method) {
        return engine.startSyncSet(listener, BLAST_TIMEOUT_DURATION, "", method);
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
