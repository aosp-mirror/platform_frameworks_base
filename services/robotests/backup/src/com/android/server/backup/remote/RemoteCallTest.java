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

package com.android.server.backup.remote;

import static com.android.server.backup.testing.TestUtils.runToEndOfTasks;
import static com.android.server.backup.testing.TestUtils.uncheck;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.backup.IBackupCallback;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.testing.TestUtils.ThrowingRunnable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class RemoteCallTest {
    /** A {@link RemoteCallable} that calls the callback immediately. */
    private final RemoteCallable<IBackupCallback> IMMEDIATE_CALLABLE =
            callback -> callback.operationComplete(0);

    @Mock private RemoteCallable<IBackupCallback> mCallable;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testCall_whenCancelledAndImmediateCallableAndTimeOut0_returnsCancel()
            throws Exception {
        RemoteCall remoteCall = new RemoteCall(true, IMMEDIATE_CALLABLE, 0);

        RemoteResult result = runInWorkerThread(remoteCall::call);

        assertThat(result).isEqualTo(RemoteResult.FAILED_CANCELLED);
    }

    @Test
    public void testCall_whenCancelledAndImmediateCallableAndTimeOut0_doesNotCallCallable()
            throws Exception {
        RemoteCall remoteCall = new RemoteCall(true, IMMEDIATE_CALLABLE, 0);

        runInWorkerThread(remoteCall::call);

        verify(mCallable, never()).call(any());
    }

    @Test
    public void testCall_whenImmediateCallableAndTimeOut0AndCancelIsCalledBeforeCall_returnsCancel()
            throws Exception {
        RemoteCall remoteCall = new RemoteCall(IMMEDIATE_CALLABLE, 0);
        remoteCall.cancel();

        RemoteResult result = runInWorkerThread(remoteCall::call);

        assertThat(result).isEqualTo(RemoteResult.FAILED_CANCELLED);
    }

    @Test
    public void
            testCall_whenImmediateCallableAndTimeOut0AndCancelIsCalledBeforeCall_doesNotCallCallable()
                    throws Exception {
        RemoteCall remoteCall = new RemoteCall(IMMEDIATE_CALLABLE, 0);
        remoteCall.cancel();

        runInWorkerThread(remoteCall::call);

        verify(mCallable, never()).call(any());
    }

    @Test
    public void testCall_whenImmediateCallableAndTimeOut0_returnsTimeOut() throws Exception {
        RemoteCall remoteCall = new RemoteCall(IMMEDIATE_CALLABLE, 0);

        RemoteResult result = runInWorkerThread(remoteCall::call);

        assertThat(result).isEqualTo(RemoteResult.FAILED_TIMED_OUT);
    }

    @Test
    public void testCall_whenTimeOut0_doesNotCallCallable() throws Exception {
        RemoteCall remoteCall = new RemoteCall(mCallable, 0);

        runInWorkerThread(remoteCall::call);

        verify(mCallable, never()).call(any());
    }

    @Test
    public void testCall_whenTimesOutBeforeCallbackIsCalled_returnsTimeOut() throws Exception {
        ConditionVariable scheduled = new ConditionVariable(false);
        RemoteCall remoteCall =
                new RemoteCall(
                        callback -> {
                            postDelayed(
                                    Handler.getMain(), () -> callback.operationComplete(0), 1000);
                            scheduled.open();
                        },
                        500);

        Future<RemoteResult> result = runInWorkerThreadAsync(remoteCall::call);

        // Method runToEndOfTasks() will execute what was posted to the main handler, which is the
        // completion of the callback and the time-out (that was scheduled by RemoteCall). But to be
        // able to execute everything we have to ensure that runToEndOfTasks() is called *after*
        // everything has been scheduled, that's why we use the condition variable scheduled, that
        // is set to true (i.e. opened) when everything is scheduled, allowing us to run the tasks.
        scheduled.block();
        runToEndOfTasks(Looper.getMainLooper());
        assertThat(result.get()).isEqualTo(RemoteResult.FAILED_TIMED_OUT);
    }

    @Test
    public void testCall_whenTimesOutBeforeCancelIsCalled_returnsTimeOut() throws Exception {
        ConditionVariable scheduled = new ConditionVariable(false);
        RemoteCall remoteCall = new RemoteCall(callback -> scheduled.open(), 500);

        Future<RemoteResult> result = runInWorkerThreadAsync(remoteCall::call);

        scheduled.block();
        runToEndOfTasks(Looper.getMainLooper());
        remoteCall.cancel();
        assertThat(result.get()).isEqualTo(RemoteResult.FAILED_TIMED_OUT);
    }

    @Test
    public void testCall_whenCallbackIsCalledBeforeTimeOut_returnsResult() throws Exception {
        ConditionVariable scheduled = new ConditionVariable(false);
        RemoteCall remoteCall =
                new RemoteCall(
                        callback -> {
                            postDelayed(
                                    Handler.getMain(), () -> callback.operationComplete(3), 500);
                            scheduled.open();
                        },
                        1000);

        Future<RemoteResult> result = runInWorkerThreadAsync(remoteCall::call);

        scheduled.block();
        runToEndOfTasks(Looper.getMainLooper());
        assertThat(result.get()).isEqualTo(RemoteResult.of(3));
    }

    @Test
    public void testCall_whenCallbackIsCalledBeforeCancel_returnsResult() throws Exception {
        CompletableFuture<IBackupCallback> callbackFuture = new CompletableFuture<>();
        RemoteCall remoteCall = new RemoteCall(callbackFuture::complete, 1000);

        Future<RemoteResult> result = runInWorkerThreadAsync(remoteCall::call);

        // callbackFuture.get() will return when callable is executed (i.e. inside
        // remoteCall.call()), at which point we can complete it.
        IBackupCallback callback = callbackFuture.get();
        callback.operationComplete(3);
        remoteCall.cancel();
        assertThat(result.get()).isEqualTo(RemoteResult.of(3));
    }

    @Test
    public void testCall_whenCancelIsCalledBeforeCallbackButAfterCall_returnsCancel()
            throws Exception {
        CompletableFuture<IBackupCallback> callbackFuture = new CompletableFuture<>();
        RemoteCall remoteCall = new RemoteCall(callbackFuture::complete, 1000);

        Future<RemoteResult> result = runInWorkerThreadAsync(remoteCall::call);

        IBackupCallback callback = callbackFuture.get();
        remoteCall.cancel();
        callback.operationComplete(3);
        assertThat(result.get()).isEqualTo(RemoteResult.FAILED_CANCELLED);
    }

    @Test
    public void testCall_whenCancelIsCalledBeforeTimeOutButAfterCall_returnsCancel()
            throws Exception {
        ConditionVariable scheduled = new ConditionVariable(false);
        RemoteCall remoteCall = new RemoteCall(callback -> scheduled.open(), 1000);

        Future<RemoteResult> result = runInWorkerThreadAsync(remoteCall::call);

        scheduled.block();
        remoteCall.cancel();
        runToEndOfTasks(Looper.getMainLooper());
        assertThat(result.get()).isEqualTo(RemoteResult.FAILED_CANCELLED);
    }

    @Test
    public void testExecute_whenCallbackIsCalledBeforeTimeout_returnsResult() throws Exception {
        RemoteResult result =
                runInWorkerThread(
                        () -> RemoteCall.execute(callback -> callback.operationComplete(3), 1000));

        assertThat(result.get()).isEqualTo(3);
    }

    @Test
    public void testExecute_whenTimesOutBeforeCallback_returnsTimeOut() throws Exception {
        ConditionVariable scheduled = new ConditionVariable(false);

        Future<RemoteResult> result =
                runInWorkerThreadAsync(
                        () ->
                                RemoteCall.execute(
                                        callback -> {
                                            postDelayed(
                                                    Handler.getMain(),
                                                    () -> callback.operationComplete(0),
                                                    1000);
                                            scheduled.open();
                                        },
                                        500));

        scheduled.block();
        runToEndOfTasks(Looper.getMainLooper());
        assertThat(result.get()).isEqualTo(RemoteResult.FAILED_TIMED_OUT);
    }

    private static <T> Future<T> runInWorkerThreadAsync(Callable<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        new Thread(() -> future.complete(uncheck(supplier)), "test-worker-thread").start();
        return future;
    }

    private static <T> T runInWorkerThread(Callable<T> supplier) throws Exception {
        return runInWorkerThreadAsync(supplier).get();
    }

    /** Unchecked version of {@link Handler#postDelayed(Runnable, long)}. */
    private static void postDelayed(Handler handler, ThrowingRunnable runnable, long delayMillis) {
        handler.postDelayed(() -> uncheck(runnable), delayMillis);
    }
}
