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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.window.flags.Flags.FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;

import android.app.IApplicationThread;
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Build/Install/Run:
 *  atest WmTests:ClientLifecycleManagerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ClientLifecycleManagerTests extends SystemServiceTestsBase {

    @Mock
    private IBinder mClientBinder;
    @Mock
    private IApplicationThread mClient;
    @Mock
    private IApplicationThread.Stub mNonBinderClient;
    @Mock
    private ClientTransaction mTransaction;
    @Mock
    private ClientTransactionItem mTransactionItem;
    @Mock
    private ActivityLifecycleItem mLifecycleItem;
    @Captor
    private ArgumentCaptor<ClientTransaction> mTransactionCaptor;

    private WindowManagerService mWms;
    private ClientLifecycleManager mLifecycleManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mWms = mSystemServicesTestRule.getWindowManagerService();
        mLifecycleManager = spy(new ClientLifecycleManager());
        mLifecycleManager.setWindowManager(mWms);

        doReturn(true).when(mLifecycleItem).isActivityLifecycleItem();
        doReturn(mClientBinder).when(mClient).asBinder();
        doReturn(mNonBinderClient).when(mNonBinderClient).asBinder();
    }

    @Test
    public void testScheduleTransaction_recycleBinderClientTransaction() throws Exception {
        final ClientTransaction item = spy(ClientTransaction.obtain(mClient));

        mLifecycleManager.scheduleTransaction(item);

        verify(item).recycle();
    }

    @Test
    public void testScheduleTransaction_notRecycleNonBinderClientTransaction() throws Exception {
        final ClientTransaction item = spy(ClientTransaction.obtain(mNonBinderClient));

        mLifecycleManager.scheduleTransaction(item);

        verify(item, never()).recycle();
    }

    @Test
    public void testScheduleTransactionItem_notBundle() throws RemoteException {
        mSetFlagsRule.disableFlags(FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);

        // Use non binder client to get non-recycled ClientTransaction.
        mLifecycleManager.scheduleTransactionItem(mNonBinderClient, mTransactionItem);

        verify(mLifecycleManager).scheduleTransaction(mTransactionCaptor.capture());
        ClientTransaction transaction = mTransactionCaptor.getValue();
        assertEquals(1, transaction.getCallbacks().size());
        assertEquals(mTransactionItem, transaction.getCallbacks().get(0));
        assertNull(transaction.getLifecycleStateRequest());
        assertNull(transaction.getTransactionItems());

        clearInvocations(mLifecycleManager);
        mLifecycleManager.scheduleTransactionItem(mNonBinderClient, mLifecycleItem);

        verify(mLifecycleManager).scheduleTransaction(mTransactionCaptor.capture());
        transaction = mTransactionCaptor.getValue();
        assertNull(transaction.getCallbacks());
        assertEquals(mLifecycleItem, transaction.getLifecycleStateRequest());
    }

    @Test
    public void testScheduleTransactionItem() throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);
        spyOn(mWms.mWindowPlacerLocked);
        doReturn(true).when(mWms.mWindowPlacerLocked).isTraversalScheduled();

        // Use non binder client to get non-recycled ClientTransaction.
        mLifecycleManager.scheduleTransactionItem(mNonBinderClient, mTransactionItem);

        // When there is traversal scheduled, add transaction items to pending.
        assertEquals(1, mLifecycleManager.mPendingTransactions.size());
        ClientTransaction transaction =
                mLifecycleManager.mPendingTransactions.get(mNonBinderClient);
        assertEquals(1, transaction.getTransactionItems().size());
        assertEquals(mTransactionItem, transaction.getTransactionItems().get(0));
        // TODO(b/324203798): cleanup after remove UnsupportedAppUsage
        assertEquals(1, transaction.getCallbacks().size());
        assertEquals(mTransactionItem, transaction.getCallbacks().get(0));
        assertNull(transaction.getLifecycleStateRequest());
        verify(mLifecycleManager, never()).scheduleTransaction(any());

        // Add new transaction item to the existing pending.
        clearInvocations(mLifecycleManager);
        mLifecycleManager.scheduleTransactionItem(mNonBinderClient, mLifecycleItem);

        assertEquals(1, mLifecycleManager.mPendingTransactions.size());
        transaction = mLifecycleManager.mPendingTransactions.get(mNonBinderClient);
        assertEquals(2, transaction.getTransactionItems().size());
        assertEquals(mTransactionItem, transaction.getTransactionItems().get(0));
        assertEquals(mLifecycleItem, transaction.getTransactionItems().get(1));
        // TODO(b/324203798): cleanup after remove UnsupportedAppUsage
        assertEquals(1, transaction.getCallbacks().size());
        assertEquals(mTransactionItem, transaction.getCallbacks().get(0));
        assertEquals(mLifecycleItem, transaction.getLifecycleStateRequest());
        verify(mLifecycleManager, never()).scheduleTransaction(any());
    }

    @Test
    public void testScheduleTransactionItemUnlocked() throws RemoteException {
        // Use non binder client to get non-recycled ClientTransaction.
        mLifecycleManager.scheduleTransactionItemNow(mNonBinderClient, mTransactionItem);

        // Dispatch immediately.
        assertTrue(mLifecycleManager.mPendingTransactions.isEmpty());
        verify(mLifecycleManager).scheduleTransaction(any());
    }

    @Test
    public void testScheduleTransactionAndLifecycleItems_notBundle() throws RemoteException {
        mSetFlagsRule.disableFlags(FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);

        // Use non binder client to get non-recycled ClientTransaction.
        mLifecycleManager.scheduleTransactionAndLifecycleItems(mNonBinderClient, mTransactionItem,
                mLifecycleItem);

        verify(mLifecycleManager).scheduleTransaction(mTransactionCaptor.capture());
        final ClientTransaction transaction = mTransactionCaptor.getValue();
        assertEquals(1, transaction.getCallbacks().size());
        assertEquals(mTransactionItem, transaction.getCallbacks().get(0));
        assertEquals(mLifecycleItem, transaction.getLifecycleStateRequest());
    }

    @Test
    public void testScheduleTransactionAndLifecycleItems() throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);
        spyOn(mWms.mWindowPlacerLocked);
        doReturn(true).when(mWms.mWindowPlacerLocked).isTraversalScheduled();

        // Use non binder client to get non-recycled ClientTransaction.
        mLifecycleManager.scheduleTransactionAndLifecycleItems(mNonBinderClient, mTransactionItem,
                mLifecycleItem);

        assertEquals(1, mLifecycleManager.mPendingTransactions.size());
        final ClientTransaction transaction =
                mLifecycleManager.mPendingTransactions.get(mNonBinderClient);
        assertEquals(2, transaction.getTransactionItems().size());
        assertEquals(mTransactionItem, transaction.getTransactionItems().get(0));
        assertEquals(mLifecycleItem, transaction.getTransactionItems().get(1));
        // TODO(b/324203798): cleanup after remove UnsupportedAppUsage
        assertEquals(1, transaction.getCallbacks().size());
        assertEquals(mTransactionItem, transaction.getCallbacks().get(0));
        assertEquals(mLifecycleItem, transaction.getLifecycleStateRequest());
        verify(mLifecycleManager, never()).scheduleTransaction(any());
    }

    @Test
    public void testScheduleTransactionAndLifecycleItems_shouldDispatchImmediately()
            throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);
        spyOn(mWms.mWindowPlacerLocked);
        doReturn(true).when(mWms.mWindowPlacerLocked).isTraversalScheduled();

        // Use non binder client to get non-recycled ClientTransaction.
        mLifecycleManager.scheduleTransactionAndLifecycleItems(mNonBinderClient, mTransactionItem,
                mLifecycleItem, true /* shouldDispatchImmediately */);

        verify(mLifecycleManager).scheduleTransaction(any());
        assertTrue(mLifecycleManager.mPendingTransactions.isEmpty());
    }

    @Test
    public void testDispatchPendingTransactions() throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);

        mLifecycleManager.mPendingTransactions.put(mClientBinder, mTransaction);

        mLifecycleManager.dispatchPendingTransactions();

        assertTrue(mLifecycleManager.mPendingTransactions.isEmpty());
        verify(mTransaction).schedule();
        verify(mTransaction).recycle();
    }

    @Test
    public void testLayoutDeferred() throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);
        spyOn(mWms.mWindowPlacerLocked);
        doReturn(false).when(mWms.mWindowPlacerLocked).isInLayout();
        doReturn(false).when(mWms.mWindowPlacerLocked).isTraversalScheduled();
        doReturn(true).when(mWms.mWindowPlacerLocked).isLayoutDeferred();

        // Queue transactions during layout deferred.
        mLifecycleManager.scheduleTransactionItem(mNonBinderClient, mLifecycleItem);

        verify(mLifecycleManager, never()).scheduleTransaction(any());

        // Continue queueing when there are multi-level defer.
        mLifecycleManager.onLayoutContinued();

        verify(mLifecycleManager, never()).scheduleTransaction(any());

        // Immediately dispatch when layout continue without ongoing/scheduled layout.
        doReturn(false).when(mWms.mWindowPlacerLocked).isLayoutDeferred();

        mLifecycleManager.onLayoutContinued();

        verify(mLifecycleManager).scheduleTransaction(any());
    }
}
