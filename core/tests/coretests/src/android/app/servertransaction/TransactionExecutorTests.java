/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.servertransaction.ActivityLifecycleItem.ON_CREATE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_DESTROY;
import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_RESTART;
import static android.app.servertransaction.ActivityLifecycleItem.ON_RESUME;
import static android.app.servertransaction.ActivityLifecycleItem.ON_START;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;
import static android.app.servertransaction.ActivityLifecycleItem.PRE_ON_CREATE;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

import java.util.ArrayList;

/** Test {@link TransactionExecutor} logic. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class TransactionExecutorTests {

    private TransactionExecutor mExecutor;
    private ClientTransactionHandler mTransactionHandler;
    private ActivityClientRecord mClientRecord;

    @Before
    public void setUp() throws Exception {
        mTransactionHandler = mock(ClientTransactionHandler.class);

        mClientRecord = new ActivityClientRecord();
        when(mTransactionHandler.getActivityClient(any())).thenReturn(mClientRecord);

        mExecutor = spy(new TransactionExecutor(mTransactionHandler));
    }

    @Test
    public void testLifecycleFromPreOnCreate() {
        mClientRecord.setState(PRE_ON_CREATE);
        assertArrayEquals(new int[] {}, path(PRE_ON_CREATE));
        assertArrayEquals(new int[] {ON_CREATE}, path(ON_CREATE));
        assertArrayEquals(new int[] {ON_CREATE, ON_START}, path(ON_START));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME}, path(ON_RESUME));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE}, path(ON_PAUSE));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP},
                path(ON_STOP));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY},
                path(ON_DESTROY));
    }

    @Test
    public void testLifecycleFromOnCreate() {
        mClientRecord.setState(ON_CREATE);
        assertArrayEquals(new int[] {}, path(ON_CREATE));
        assertArrayEquals(new int[] {ON_START}, path(ON_START));
        assertArrayEquals(new int[] {ON_START, ON_RESUME}, path(ON_RESUME));
        assertArrayEquals(new int[] {ON_START, ON_RESUME, ON_PAUSE}, path(ON_PAUSE));
        assertArrayEquals(new int[] {ON_START, ON_RESUME, ON_PAUSE, ON_STOP}, path(ON_STOP));
        assertArrayEquals(new int[] {ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY},
                path(ON_DESTROY));
    }

    @Test
    public void testLifecycleFromOnStart() {
        mClientRecord.setState(ON_START);
        assertArrayEquals(new int[] {ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_CREATE},
                path(ON_CREATE));
        assertArrayEquals(new int[] {}, path(ON_START));
        assertArrayEquals(new int[] {ON_RESUME}, path(ON_RESUME));
        assertArrayEquals(new int[] {ON_RESUME, ON_PAUSE}, path(ON_PAUSE));
        assertArrayEquals(new int[] {ON_RESUME, ON_PAUSE, ON_STOP}, path(ON_STOP));
        assertArrayEquals(new int[] {ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY}, path(ON_DESTROY));
    }

    @Test
    public void testLifecycleFromOnResume() {
        mClientRecord.setState(ON_RESUME);
        assertArrayEquals(new int[] {ON_PAUSE, ON_STOP, ON_DESTROY, ON_CREATE}, path(ON_CREATE));
        assertArrayEquals(new int[] {ON_PAUSE, ON_STOP, ON_RESTART, ON_START}, path(ON_START));
        assertArrayEquals(new int[] {}, path(ON_RESUME));
        assertArrayEquals(new int[] {ON_PAUSE}, path(ON_PAUSE));
        assertArrayEquals(new int[] {ON_PAUSE, ON_STOP}, path(ON_STOP));
        assertArrayEquals(new int[] {ON_PAUSE, ON_STOP, ON_DESTROY}, path(ON_DESTROY));
    }

    @Test
    public void testLifecycleFromOnPause() {
        mClientRecord.setState(ON_PAUSE);
        assertArrayEquals(new int[] {ON_STOP, ON_DESTROY, ON_CREATE}, path(ON_CREATE));
        assertArrayEquals(new int[] {ON_STOP, ON_RESTART, ON_START}, path(ON_START));
        assertArrayEquals(new int[] {ON_RESUME}, path(ON_RESUME));
        assertArrayEquals(new int[] {}, path(ON_PAUSE));
        assertArrayEquals(new int[] {ON_STOP}, path(ON_STOP));
        assertArrayEquals(new int[] {ON_STOP, ON_DESTROY}, path(ON_DESTROY));
    }

    @Test
    public void testLifecycleFromOnStop() {
        mClientRecord.setState(ON_STOP);
        assertArrayEquals(new int[] {ON_DESTROY, ON_CREATE}, path(ON_CREATE));
        assertArrayEquals(new int[] {ON_RESTART, ON_START}, path(ON_START));
        assertArrayEquals(new int[] {ON_RESTART, ON_START, ON_RESUME}, path(ON_RESUME));
        assertArrayEquals(new int[] {ON_RESTART, ON_START, ON_RESUME, ON_PAUSE}, path(ON_PAUSE));
        assertArrayEquals(new int[] {}, path(ON_STOP));
        assertArrayEquals(new int[] {ON_DESTROY}, path(ON_DESTROY));
    }

    @Test
    public void testLifecycleFromOnDestroy() {
        mClientRecord.setState(ON_DESTROY);
        assertArrayEquals(new int[] {ON_CREATE}, path(ON_CREATE));
        assertArrayEquals(new int[] {ON_CREATE, ON_START}, path(ON_START));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME}, path(ON_RESUME));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE}, path(ON_PAUSE));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP},
                path(ON_STOP));
        assertArrayEquals(new int[] {}, path(ON_DESTROY));
    }

    @Test
    public void testLifecycleExcludeLastItem() {
        mClientRecord.setState(PRE_ON_CREATE);
        assertArrayEquals(new int[] {}, pathExcludeLast(PRE_ON_CREATE));
        assertArrayEquals(new int[] {}, pathExcludeLast(ON_CREATE));
        assertArrayEquals(new int[] {ON_CREATE}, pathExcludeLast(ON_START));
        assertArrayEquals(new int[] {ON_CREATE, ON_START}, pathExcludeLast(ON_RESUME));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME}, pathExcludeLast(ON_PAUSE));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE},
                pathExcludeLast(ON_STOP));
        assertArrayEquals(new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP},
                pathExcludeLast(ON_DESTROY));
    }

    @Test
    public void testTransactionResolution() {
        ClientTransactionItem callback1 = mock(ClientTransactionItem.class);
        ClientTransactionItem callback2 = mock(ClientTransactionItem.class);
        ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        IBinder token = mock(IBinder.class);

        ClientTransaction transaction = new ClientTransaction(null /* client */,
                token /* activityToken */);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);
        transaction.setLifecycleStateRequest(stateRequest);

        transaction.preExecute(mTransactionHandler);
        mExecutor.execute(transaction);

        InOrder inOrder = inOrder(mTransactionHandler, callback1, callback2, stateRequest);
        inOrder.verify(callback1, times(1)).execute(eq(mTransactionHandler), eq(token), any());
        inOrder.verify(callback2, times(1)).execute(eq(mTransactionHandler), eq(token), any());
        inOrder.verify(stateRequest, times(1)).execute(eq(mTransactionHandler), eq(token), any());
    }

    @Test
    public void testRequiredStateResolution() {
        ActivityResultItem activityResultItem = new ActivityResultItem(new ArrayList<>());

        IBinder token = mock(IBinder.class);
        ClientTransaction transaction = new ClientTransaction(null /* client */,
                token /* activityToken */);
        transaction.addCallback(activityResultItem);

        mExecutor.executeCallbacks(transaction);

        verify(mExecutor, times(1)).cycleToPath(eq(mClientRecord), eq(ON_PAUSE));
    }

    private int[] path(int finish) {
        mExecutor.initLifecyclePath(mClientRecord.getLifecycleState(), finish,
                false /* excludeLastState */);
        return mExecutor.getLifecycleSequence();
    }

    private int[] pathExcludeLast(int finish) {
        mExecutor.initLifecyclePath(mClientRecord.getLifecycleState(), finish,
                true /* excludeLastState */);
        return mExecutor.getLifecycleSequence();
    }
}
