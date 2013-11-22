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
 * the timeout then the call failed. Older result received when
 * waiting for the result are ignored.
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

    private static final int UNDEFINED_SEQUENCE = -1;

    private final Object mLock = new Object();

    private final long mCallTimeoutMillis;

    private int mSequenceCounter;

    private int mReceivedSequence = UNDEFINED_SEQUENCE;

    private int mAwaitedSequence = UNDEFINED_SEQUENCE;

    private T mResult;

    public TimedRemoteCaller(long callTimeoutMillis) {
        mCallTimeoutMillis = callTimeoutMillis;
    }

    public final int onBeforeRemoteCall() {
        synchronized (mLock) {
            mAwaitedSequence = mSequenceCounter++;
            return mAwaitedSequence;
        }
    }

    public final T getResultTimed(int sequence) throws TimeoutException {
        synchronized (mLock) {
            final boolean success = waitForResultTimedLocked(sequence);
            if (!success) {
                throw new TimeoutException("No reponse for sequence: " + sequence);
            }
            T result = mResult;
            mResult = null;
            return result;
        }
    }

    public final void onRemoteMethodResult(T result, int sequence) {
        synchronized (mLock) {
            if (sequence == mAwaitedSequence) {
                mReceivedSequence = sequence;
                mResult = result;
                mLock.notifyAll();
            }
        }
    }

    private boolean waitForResultTimedLocked(int sequence) {
        final long startMillis = SystemClock.uptimeMillis();
        while (true) {
            try {
                if (mReceivedSequence == sequence) {
                    return true;
                }
                final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
                final long waitMillis = mCallTimeoutMillis - elapsedMillis;
                if (waitMillis <= 0) {
                    return false;
                }
                mLock.wait(waitMillis);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
    }
}
