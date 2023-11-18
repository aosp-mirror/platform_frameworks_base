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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;

import android.app.IApplicationThread;
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
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
public class ClientLifecycleManagerTests {

    @Mock
    private IApplicationThread mClient;
    @Mock
    private IApplicationThread.Stub mNonBinderClient;
    @Mock
    private ClientTransactionItem mTransactionItem;
    @Mock
    private ActivityLifecycleItem mLifecycleItem;
    @Captor
    private ArgumentCaptor<ClientTransaction> mTransactionCaptor;

    private ClientLifecycleManager mLifecycleManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mLifecycleManager = spy(new ClientLifecycleManager());

        doReturn(true).when(mLifecycleItem).isActivityLifecycleItem();
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
    public void testScheduleTransactionItem() throws RemoteException {
        doNothing().when(mLifecycleManager).scheduleTransaction(any());
        mLifecycleManager.scheduleTransactionItem(mClient, mTransactionItem);

        verify(mLifecycleManager).scheduleTransaction(mTransactionCaptor.capture());
        ClientTransaction transaction = mTransactionCaptor.getValue();
        assertEquals(1, transaction.getCallbacks().size());
        assertEquals(mTransactionItem, transaction.getCallbacks().get(0));
        assertNull(transaction.getLifecycleStateRequest());
        assertNull(transaction.getTransactionItems());

        clearInvocations(mLifecycleManager);
        mLifecycleManager.scheduleTransactionItem(mClient, mLifecycleItem);

        verify(mLifecycleManager).scheduleTransaction(mTransactionCaptor.capture());
        transaction = mTransactionCaptor.getValue();
        assertNull(transaction.getCallbacks());
        assertEquals(mLifecycleItem, transaction.getLifecycleStateRequest());
    }

    @Test
    public void testScheduleTransactionAndLifecycleItems() throws RemoteException {
        doNothing().when(mLifecycleManager).scheduleTransaction(any());
        mLifecycleManager.scheduleTransactionAndLifecycleItems(mClient, mTransactionItem,
                mLifecycleItem);

        verify(mLifecycleManager).scheduleTransaction(mTransactionCaptor.capture());
        final ClientTransaction transaction = mTransactionCaptor.getValue();
        assertEquals(1, transaction.getCallbacks().size());
        assertEquals(mTransactionItem, transaction.getCallbacks().get(0));
        assertEquals(mLifecycleItem, transaction.getLifecycleStateRequest());
    }
}
