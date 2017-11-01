/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.util;

import android.os.SystemClock;
import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.TimeoutException;

/**
 * This is a helper class for making an async one way call and
 * its async one way response response in a sync fashion within
 * a timeout. The key idea is to call the remote method with a
 * sequence number and a callback and then starting to wait for
 * the response. The remote method calls back with the result and
 * the sequence number. If the response comes within the timeout
 * and its sequence number is the one sent in the method invocation,
 * then the call succeeded. If the response does not come within
 * the timeout then the call failed.
 * <p>
 * Typical usage is:
 * </p>
 * <p><pre><code>
 * public class MyMethodCaller extends TimeoutRemoteCallHelper<Object> {
 *     // The one way remote method to call.
 *     private final IRemoteInterface mTarget;
 *
 *     // One way callback invoked when the remote method is done.
 *     private final IRemoteCallback mCallback = new IRemoteCallback.Stub() {
 *         public void onCompleted(Object result, int sequence) {
 *             onRemoteMethodResult(result, sequence);
 *         }
 *     };
 *
 *     public MyMethodCaller(IRemoteInterface target) {
 *         mTarget = target;
 *     }
 *
 *     public Object onCallMyMethod(Object arg) throws RemoteException {
 *         final int sequence = onBeforeRemoteCall();
 *         mTarget.myMethod(arg, sequence);
 *         return getResultTimed(sequence);
 *     }
 * }
 * </code></pre></p>
 *
 * @param <T> The type of the expected result.
 *
 * @hide
 */
public abstract class TimedRemoteCaller<T> {

    public static final long DEFAULT_CALL_TIMEOUT_MILLIS = 5000;

    private final Object mLock = new Object();

    private final long mCallTimeoutMillis;

    /** The callbacks we are waiting for, key == sequence id, value == 1 */
    @GuardedBy("mLock")
    private final SparseIntArray mAwaitedCalls = new SparseIntArray(1);

    /** The callbacks we received but for which the result has not yet been reported */
    @GuardedBy("mLock")
    private final SparseArray<T> mReceivedCalls = new SparseArray<>(1);

    @GuardedBy("mLock")
    private int mSequenceCounter;

    /**
     * Create a new timed caller.
     *
     * @param callTimeoutMillis The time to wait in {@link #getResultTimed} before a timed call will
     *                          be declared timed out
     */
    public TimedRemoteCaller(long callTimeoutMillis) {
        mCallTimeoutMillis = callTimeoutMillis;
    }

    /**
     * Indicate that a timed call will be made.
     *
     * @return The sequence id for the call
     */
    protected final int onBeforeRemoteCall() {
        synchronized (mLock) {
            int sequenceId;
            do {
                sequenceId = mSequenceCounter++;
            } while (mAwaitedCalls.get(sequenceId) != 0);

            mAwaitedCalls.put(sequenceId, 1);

            return sequenceId;
        }
    }

    /**
     * Indicate that the timed call has returned.
     *
     * @param result The result of the timed call
     * @param sequence The sequence id of the call (from {@link #onBeforeRemoteCall()})
     */
    protected final void onRemoteMethodResult(T result, int sequence) {
        synchronized (mLock) {
            // Do nothing if we do not await the call anymore as it must have timed out
            boolean containedSequenceId = mAwaitedCalls.get(sequence) != 0;
            if (containedSequenceId) {
                mAwaitedCalls.delete(sequence);
                mReceivedCalls.put(sequence, result);
                mLock.notifyAll();
            }
        }
    }

    /**
     * Wait until the timed call has returned.
     *
     * @param sequence The sequence id of the call (from {@link #onBeforeRemoteCall()})
     *
     * @return The result of the timed call (set in {@link #onRemoteMethodResult(Object, int)})
     */
    protected final T getResultTimed(int sequence) throws TimeoutException {
        final long startMillis = SystemClock.uptimeMillis();
        while (true) {
            try {
                synchronized (mLock) {
                    if (mReceivedCalls.indexOfKey(sequence) >= 0) {
                        return mReceivedCalls.removeReturnOld(sequence);
                    }
                    final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
                    final long waitMillis = mCallTimeoutMillis - elapsedMillis;
                    if (waitMillis <= 0) {
                        mAwaitedCalls.delete(sequence);
                        throw new TimeoutException("No response for sequence: " + sequence);
                    }
                    mLock.wait(waitMillis);
                }
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
    }
}
