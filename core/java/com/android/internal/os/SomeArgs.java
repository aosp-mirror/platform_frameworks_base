/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.os;

/**
 * Helper class for passing more arguments though a message
 * and avoiding allocation of a custom class for wrapping the
 * arguments. This class maintains a pool of instances and
 * it is responsibility of the client to recycle and instance
 * once it is no longer used.
 */
public final class SomeArgs {

    private static final int MAX_POOL_SIZE = 10;

    private static SomeArgs sPool;
    private static int sPoolSize;
    private static Object sPoolLock = new Object();

    private SomeArgs mNext;

    private boolean mInPool;

    static final int WAIT_NONE = 0;
    static final int WAIT_WAITING = 1;
    static final int WAIT_FINISHED = 2;
    int mWaitState = WAIT_NONE;

    public Object arg1;
    public Object arg2;
    public Object arg3;
    public Object arg4;
    public Object arg5;
    public Object arg6;
    public Object arg7;
    public Object arg8;
    public int argi1;
    public int argi2;
    public int argi3;
    public int argi4;
    public int argi5;
    public int argi6;

    private SomeArgs() {
        /* do nothing - reduce visibility */
    }

    public static SomeArgs obtain() {
        synchronized (sPoolLock) {
            if (sPoolSize > 0) {
                SomeArgs args = sPool;
                sPool = sPool.mNext;
                args.mNext = null;
                args.mInPool = false;
                sPoolSize--;
                return args;
            } else {
                return new SomeArgs();
            }
        }
    }

    public void complete() {
        synchronized (this) {
            if (mWaitState != WAIT_WAITING) {
                throw new IllegalStateException("Not waiting");
            }
            mWaitState = WAIT_FINISHED;
            notifyAll();
        }
    }

    public void recycle() {
        if (mInPool) {
            throw new IllegalStateException("Already recycled.");
        }
        if (mWaitState != WAIT_NONE) {
            return;
        }
        synchronized (sPoolLock) {
            clear();
            if (sPoolSize < MAX_POOL_SIZE) {
                mNext = sPool;
                mInPool = true;
                sPool = this;
                sPoolSize++;
            }
        }
    }

    private void clear() {
        arg1 = null;
        arg2 = null;
        arg3 = null;
        arg4 = null;
        arg5 = null;
        arg6 = null;
        arg7 = null;
        argi1 = 0;
        argi2 = 0;
        argi3 = 0;
        argi4 = 0;
        argi5 = 0;
        argi6 = 0;
    }
}
