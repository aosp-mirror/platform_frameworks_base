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
import static android.app.servertransaction.ActivityLifecycleItem.UNDEFINED;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.app.servertransaction.ActivityLifecycleItem.LifecycleState;
import android.app.servertransaction.TestUtils.LaunchActivityItemBuilder;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Test {@link TransactionExecutor} logic.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:TransactionExecutorTests
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TransactionExecutorTests {

    private TransactionExecutor mExecutor;
    private TransactionExecutorHelper mExecutorHelper;
    private ClientTransactionHandler mTransactionHandler;
    private ActivityClientRecord mClientRecord;

    @Before
    public void setUp() throws Exception {
        mTransactionHandler = mock(ClientTransactionHandler.class);

        mClientRecord = new ActivityClientRecord();
        when(mTransactionHandler.getActivityClient(any())).thenReturn(mClientRecord);

        mExecutor = spy(new TransactionExecutor(mTransactionHandler));
        mExecutorHelper = new TransactionExecutorHelper();
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
        assertArrayEquals(new int[] {ON_STOP}, path(ON_STOP));
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

    @Test(expected = IllegalArgumentException.class)
    public void testLifecycleUndefinedStartState() {
        mClientRecord.setState(UNDEFINED);
        path(ON_CREATE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLifecycleUndefinedFinishState() {
        mClientRecord.setState(PRE_ON_CREATE);
        path(UNDEFINED);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLifecycleInvalidPreOnCreateFinishState() {
        mClientRecord.setState(ON_CREATE);
        path(PRE_ON_CREATE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLifecycleInvalidOnRestartStartState() {
        mClientRecord.setState(ON_RESTART);
        path(ON_RESUME);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLifecycleInvalidOnRestartFinishState() {
        mClientRecord.setState(ON_CREATE);
        path(ON_RESTART);
    }

    @Test
    public void testTransactionResolution() {
        ClientTransactionItem callback1 = mock(ClientTransactionItem.class);
        when(callback1.getPostExecutionState()).thenReturn(UNDEFINED);
        ClientTransactionItem callback2 = mock(ClientTransactionItem.class);
        when(callback2.getPostExecutionState()).thenReturn(UNDEFINED);
        ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        IBinder token = mock(IBinder.class);
        when(stateRequest.getActivityToken()).thenReturn(token);
        when(mTransactionHandler.getActivity(token)).thenReturn(mock(Activity.class));

        ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);
        transaction.setLifecycleStateRequest(stateRequest);

        transaction.preExecute(mTransactionHandler);
        mExecutor.execute(transaction);

        InOrder inOrder = inOrder(mTransactionHandler, callback1, callback2, stateRequest);
        inOrder.verify(callback1).execute(eq(mTransactionHandler), any());
        inOrder.verify(callback2).execute(eq(mTransactionHandler), any());
        inOrder.verify(stateRequest).execute(eq(mTransactionHandler), eq(mClientRecord), any());
    }

    @Test
    public void testExecuteTransactionItems_transactionResolution() {
        ClientTransactionItem callback1 = mock(ClientTransactionItem.class);
        when(callback1.getPostExecutionState()).thenReturn(UNDEFINED);
        ClientTransactionItem callback2 = mock(ClientTransactionItem.class);
        when(callback2.getPostExecutionState()).thenReturn(UNDEFINED);
        ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        IBinder token = mock(IBinder.class);
        when(stateRequest.getActivityToken()).thenReturn(token);
        when(mTransactionHandler.getActivity(token)).thenReturn(mock(Activity.class));

        ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addTransactionItem(callback1);
        transaction.addTransactionItem(callback2);
        transaction.addTransactionItem(stateRequest);

        transaction.preExecute(mTransactionHandler);
        mExecutor.execute(transaction);

        InOrder inOrder = inOrder(mTransactionHandler, callback1, callback2, stateRequest);
        inOrder.verify(callback1).execute(eq(mTransactionHandler), any());
        inOrder.verify(callback2).execute(eq(mTransactionHandler), any());
        inOrder.verify(stateRequest).execute(eq(mTransactionHandler), eq(mClientRecord), any());
    }

    @Test
    public void testDoNotLaunchDestroyedActivity() {
        final Map<IBinder, DestroyActivityItem> activitiesToBeDestroyed = new ArrayMap<>();
        when(mTransactionHandler.getActivitiesToBeDestroyed()).thenReturn(activitiesToBeDestroyed);
        // Assume launch transaction is still in queue, so there is no client record.
        when(mTransactionHandler.getActivityClient(any())).thenReturn(null);

        // An incoming destroy transaction enters binder thread (preExecute).
        final IBinder token = mock(IBinder.class);
        final ClientTransaction destroyTransaction = ClientTransaction.obtain(null /* client */);
        destroyTransaction.setLifecycleStateRequest(
                DestroyActivityItem.obtain(token, false /* finished */, 0 /* configChanges */));
        destroyTransaction.preExecute(mTransactionHandler);
        // The activity should be added to to-be-destroyed container.
        assertEquals(1, activitiesToBeDestroyed.size());

        // A previous queued launch transaction runs on main thread (execute).
        final ClientTransaction launchTransaction = ClientTransaction.obtain(null /* client */);
        final LaunchActivityItem launchItem =
                spy(new LaunchActivityItemBuilder(token, new Intent(), new ActivityInfo()).build());
        launchTransaction.addCallback(launchItem);
        mExecutor.execute(launchTransaction);

        // The launch transaction should not be executed because its token is in the
        // to-be-destroyed container.
        verify(launchItem, never()).execute(any(), any());

        // After the destroy transaction has been executed, the token should be removed.
        mExecutor.execute(destroyTransaction);
        assertTrue(activitiesToBeDestroyed.isEmpty());
    }

    @Test
    public void testExecuteTransactionItems_doNotLaunchDestroyedActivity() {
        final Map<IBinder, DestroyActivityItem> activitiesToBeDestroyed = new ArrayMap<>();
        when(mTransactionHandler.getActivitiesToBeDestroyed()).thenReturn(activitiesToBeDestroyed);
        // Assume launch transaction is still in queue, so there is no client record.
        when(mTransactionHandler.getActivityClient(any())).thenReturn(null);

        // An incoming destroy transaction enters binder thread (preExecute).
        final IBinder token = mock(IBinder.class);
        final ClientTransaction destroyTransaction = ClientTransaction.obtain(null /* client */);
        destroyTransaction.addTransactionItem(
                DestroyActivityItem.obtain(token, false /* finished */, 0 /* configChanges */));
        destroyTransaction.preExecute(mTransactionHandler);
        // The activity should be added to to-be-destroyed container.
        assertEquals(1, activitiesToBeDestroyed.size());

        // A previous queued launch transaction runs on main thread (execute).
        final ClientTransaction launchTransaction = ClientTransaction.obtain(null /* client */);
        final LaunchActivityItem launchItem =
                spy(new LaunchActivityItemBuilder(token, new Intent(), new ActivityInfo()).build());
        launchTransaction.addTransactionItem(launchItem);
        mExecutor.execute(launchTransaction);

        // The launch transaction should not be executed because its token is in the
        // to-be-destroyed container.
        verify(launchItem, never()).execute(any(), any());

        // After the destroy transaction has been executed, the token should be removed.
        mExecutor.execute(destroyTransaction);
        assertTrue(activitiesToBeDestroyed.isEmpty());
    }

    @Test
    public void testActivityResultRequiredStateResolution() {
        when(mTransactionHandler.getActivity(any())).thenReturn(mock(Activity.class));

        PostExecItem postExecItem = new PostExecItem(ON_RESUME);

        ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addCallback(postExecItem);

        // Verify resolution that should get to onPause
        mClientRecord.setState(ON_RESUME);
        mExecutor.executeCallbacks(transaction);
        verify(mExecutor).cycleToPath(eq(mClientRecord), eq(ON_PAUSE), eq(transaction));

        // Verify resolution that should get to onStart
        mClientRecord.setState(ON_STOP);
        mExecutor.executeCallbacks(transaction);
        verify(mExecutor).cycleToPath(eq(mClientRecord), eq(ON_START), eq(transaction));
    }

    @Test
    public void testExecuteTransactionItems_activityResultRequiredStateResolution() {
        when(mTransactionHandler.getActivity(any())).thenReturn(mock(Activity.class));

        PostExecItem postExecItem = new PostExecItem(ON_RESUME);

        ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addTransactionItem(postExecItem);

        // Verify resolution that should get to onPause
        mClientRecord.setState(ON_RESUME);
        mExecutor.executeTransactionItems(transaction);
        verify(mExecutor).cycleToPath(eq(mClientRecord), eq(ON_PAUSE), eq(transaction));

        // Verify resolution that should get to onStart
        mClientRecord.setState(ON_STOP);
        mExecutor.executeTransactionItems(transaction);
        verify(mExecutor).cycleToPath(eq(mClientRecord), eq(ON_START), eq(transaction));
    }

    @Test
    public void testClosestStateResolutionForSameState() {
        final int[] allStates = new int[] {
                ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY};

        mClientRecord.setState(ON_CREATE);
        assertEquals(ON_CREATE, mExecutorHelper.getClosestOfStates(mClientRecord,
                shuffledArray(allStates)));

        mClientRecord.setState(ON_START);
        assertEquals(ON_START, mExecutorHelper.getClosestOfStates(mClientRecord,
                shuffledArray(allStates)));

        mClientRecord.setState(ON_RESUME);
        assertEquals(ON_RESUME, mExecutorHelper.getClosestOfStates(mClientRecord,
                shuffledArray(allStates)));

        mClientRecord.setState(ON_PAUSE);
        assertEquals(ON_PAUSE, mExecutorHelper.getClosestOfStates(mClientRecord,
                shuffledArray(allStates)));

        mClientRecord.setState(ON_STOP);
        assertEquals(ON_STOP, mExecutorHelper.getClosestOfStates(mClientRecord,
                shuffledArray(allStates)));

        mClientRecord.setState(ON_DESTROY);
        assertEquals(ON_DESTROY, mExecutorHelper.getClosestOfStates(mClientRecord,
                shuffledArray(allStates)));

        mClientRecord.setState(PRE_ON_CREATE);
        assertEquals(PRE_ON_CREATE, mExecutorHelper.getClosestOfStates(mClientRecord,
                new int[] {PRE_ON_CREATE}));
    }

    @Test
    public void testClosestStateResolution() {
        mClientRecord.setState(PRE_ON_CREATE);
        assertEquals(ON_CREATE, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY})));
        assertEquals(ON_START, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY})));
        assertEquals(ON_RESUME, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY})));
        assertEquals(ON_PAUSE, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_PAUSE, ON_STOP, ON_DESTROY})));
        assertEquals(ON_STOP, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_STOP, ON_DESTROY})));
        assertEquals(ON_DESTROY, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_DESTROY})));
    }

    @Test
    public void testClosestStateResolutionFromOnCreate() {
        mClientRecord.setState(ON_CREATE);
        assertEquals(ON_START, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY})));
    }

    @Test
    public void testClosestStateResolutionFromOnStart() {
        mClientRecord.setState(ON_START);
        assertEquals(ON_RESUME, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_RESUME, ON_PAUSE, ON_DESTROY})));
        assertEquals(ON_STOP, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_STOP})));
        assertEquals(ON_CREATE, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE})));
    }

    @Test
    public void testClosestStateResolutionFromOnResume() {
        mClientRecord.setState(ON_RESUME);
        assertEquals(ON_PAUSE, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_START, ON_PAUSE, ON_STOP, ON_DESTROY})));
        assertEquals(ON_DESTROY, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_DESTROY})));
        assertEquals(ON_START, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_START})));
        assertEquals(ON_CREATE, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE})));
    }

    @Test
    public void testClosestStateResolutionFromOnPause() {
        mClientRecord.setState(ON_PAUSE);
        assertEquals(ON_RESUME, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_START, ON_RESUME, ON_DESTROY})));
        assertEquals(ON_STOP, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_START, ON_STOP, ON_DESTROY})));
        assertEquals(ON_START, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_START})));
        assertEquals(ON_CREATE, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE})));
    }

    @Test
    public void testClosestStateResolutionFromOnStop() {
        mClientRecord.setState(ON_STOP);
        assertEquals(ON_RESUME, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_RESUME, ON_PAUSE, ON_DESTROY})));
        assertEquals(ON_DESTROY, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_DESTROY})));
        assertEquals(ON_START, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE})));
    }

    @Test
    public void testClosestStateResolutionFromOnDestroy() {
        mClientRecord.setState(ON_DESTROY);
        assertEquals(ON_CREATE, mExecutorHelper.getClosestOfStates(mClientRecord, shuffledArray(
                new int[] {ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP})));
    }

    @Test
    public void testClosestStateResolutionToUndefined() {
        mClientRecord.setState(ON_CREATE);
        assertEquals(UNDEFINED,
                mExecutorHelper.getClosestPreExecutionState(mClientRecord, UNDEFINED));
    }

    @Test
    public void testClosestStateResolutionToOnResume() {
        mClientRecord.setState(ON_DESTROY);
        assertEquals(ON_START,
                mExecutorHelper.getClosestPreExecutionState(mClientRecord, ON_RESUME));
        mClientRecord.setState(ON_START);
        assertEquals(ON_START,
                mExecutorHelper.getClosestPreExecutionState(mClientRecord, ON_RESUME));
        mClientRecord.setState(ON_PAUSE);
        assertEquals(ON_PAUSE,
                mExecutorHelper.getClosestPreExecutionState(mClientRecord, ON_RESUME));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testActivityItemNullRecordThrowsException() {
        final ActivityTransactionItem activityItem = mock(ActivityTransactionItem.class);
        when(activityItem.getPostExecutionState()).thenReturn(UNDEFINED);
        final IBinder token = mock(IBinder.class);
        final ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addCallback(activityItem);
        when(mTransactionHandler.getActivityClient(token)).thenReturn(null);

        mExecutor.executeCallbacks(transaction);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExecuteTransactionItems_activityItemNullRecordThrowsException() {
        final ActivityTransactionItem activityItem = mock(ActivityTransactionItem.class);
        when(activityItem.getPostExecutionState()).thenReturn(UNDEFINED);
        final IBinder token = mock(IBinder.class);
        final ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addTransactionItem(activityItem);
        when(mTransactionHandler.getActivityClient(token)).thenReturn(null);

        mExecutor.executeTransactionItems(transaction);
    }

    @Test
    public void testActivityItemExecute() {
        final IBinder token = mock(IBinder.class);
        final ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        final ActivityTransactionItem activityItem = mock(ActivityTransactionItem.class);
        when(activityItem.getPostExecutionState()).thenReturn(UNDEFINED);
        when(activityItem.getActivityToken()).thenReturn(token);
        transaction.addCallback(activityItem);
        final ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        transaction.setLifecycleStateRequest(stateRequest);
        when(stateRequest.getActivityToken()).thenReturn(token);
        when(mTransactionHandler.getActivity(token)).thenReturn(mock(Activity.class));

        mExecutor.execute(transaction);

        final InOrder inOrder = inOrder(activityItem, stateRequest);
        inOrder.verify(activityItem).execute(eq(mTransactionHandler), eq(mClientRecord), any());
        inOrder.verify(stateRequest).execute(eq(mTransactionHandler), eq(mClientRecord), any());
    }

    @Test
    public void testExecuteTransactionItems_activityItemExecute() {
        final IBinder token = mock(IBinder.class);
        final ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        final ActivityTransactionItem activityItem = mock(ActivityTransactionItem.class);
        when(activityItem.getPostExecutionState()).thenReturn(UNDEFINED);
        when(activityItem.getActivityToken()).thenReturn(token);
        transaction.addTransactionItem(activityItem);
        final ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        transaction.addTransactionItem(stateRequest);
        when(stateRequest.getActivityToken()).thenReturn(token);
        when(mTransactionHandler.getActivity(token)).thenReturn(mock(Activity.class));

        mExecutor.execute(transaction);

        final InOrder inOrder = inOrder(activityItem, stateRequest);
        inOrder.verify(activityItem).execute(eq(mTransactionHandler), eq(mClientRecord), any());
        inOrder.verify(stateRequest).execute(eq(mTransactionHandler), eq(mClientRecord), any());
    }

    private static int[] shuffledArray(int[] inputArray) {
        final List<Integer> list = Arrays.stream(inputArray).boxed().collect(Collectors.toList());
        Collections.shuffle(list);
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    private int[] path(int finish) {
        return mExecutorHelper.getLifecyclePath(mClientRecord.getLifecycleState(), finish,
                false /* excludeLastState */).toArray();
    }

    private int[] pathExcludeLast(int finish) {
        return mExecutorHelper.getLifecyclePath(mClientRecord.getLifecycleState(), finish,
                true /* excludeLastState */).toArray();
    }

    /** A transaction item that requires some specific post-execution state. */
    private static class PostExecItem extends StubItem {

        @LifecycleState
        private int mPostExecutionState;

        PostExecItem(@LifecycleState int state) {
            mPostExecutionState = state;
        }

        @Override
        public int getPostExecutionState() {
            return mPostExecutionState;
        }
    }

    /** Stub implementation of a transaction item that works as a base class for items in tests. */
    private static class StubItem extends ClientTransactionItem  {

        private StubItem() {
        }

        private StubItem(@NonNull Parcel in) {
        }

        @Override
        public void execute(@NonNull ClientTransactionHandler client,
                @NonNull PendingTransactionActions pendingActions) {
        }

        @Override
        public void recycle() {
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
        }

        public static final Parcelable.Creator<StubItem> CREATOR = new Parcelable.Creator<>() {
            public StubItem createFromParcel(@NonNull Parcel in) {
                return new StubItem(in);
            }

            public StubItem[] newArray(int size) {
                return new StubItem[size];
            }
        };
    }
}
