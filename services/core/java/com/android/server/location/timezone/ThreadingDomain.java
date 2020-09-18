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

package com.android.server.location.timezone;

import android.annotation.NonNull;

import com.android.internal.util.Preconditions;

/**
 * A class that can be used to enforce / indicate a set of components that need to share threading
 * behavior such as a shared lock object and a common thread, with async execution support.
 *
 * <p>It is <em>not</em> essential that the object returned by {@link #getLockObject()} is only used
 * when executing on the domain's thread, but users should be careful to avoid deadlocks when
 * multiple locks / threads are in use. Generally sticking to a single thread / lock is safest.
 */
abstract class ThreadingDomain {

    @NonNull private final Object mLockObject;

    ThreadingDomain() {
        mLockObject = new Object();
    }

    /**
     * Returns the common lock object for this threading domain that can be used for synchronized ()
     * blocks. The lock is unique to this threading domain.
     */
    @NonNull
    Object getLockObject() {
        return mLockObject;
    }

    /**
     * Returns the Thread associated with this threading domain.
     */
    @NonNull
    abstract Thread getThread();

    /**
     * Asserts the currently executing thread is the one associated with this threading domain.
     * Generally useful for documenting expectations in the code. By asserting a single thread is
     * being used within a set of components, a lot of races can be avoided.
     */
    void assertCurrentThread() {
        Preconditions.checkArgument(Thread.currentThread() == getThread());
    }

    /**
     * Execute the supplied runnable on the threading domain's thread.
     */
    abstract void post(@NonNull Runnable runnable);

    /**
     * Execute the supplied runnable on the threading domain's thread with a delay.
     */
    abstract void postDelayed(@NonNull Runnable runnable, long delayMillis);

    abstract void postDelayed(Runnable r, Object token, long delayMillis);

    abstract void removeQueuedRunnables(Object token);

    /**
     * Creates a new {@link SingleRunnableQueue} that can be used to ensure that (at most) a
     * single runnable for a given purpose is ever queued. Create new ones for different purposes.
     */
    SingleRunnableQueue createSingleRunnableQueue() {
        return new SingleRunnableQueue();
    }

    /**
     * A class that allows up to one {@link Runnable} to be queued on the handler, i.e. calling any
     * of the methods will cancel the execution of any previously queued / delayed runnable. All
     * methods must be called from the {@link ThreadingDomain}'s thread.
     */
    final class SingleRunnableQueue {

        /**
         * Runs the supplied {@link Runnable} synchronously on the threading domain's thread,
         * cancelling any queued but not-yet-executed {@link Runnable} previously added by this.
         * This method must be called from the threading domain's thread.
         */
        void runSynchronously(Runnable r) {
            cancel();
            r.run();
        }

        /**
         * Posts the supplied {@link Runnable} asynchronously and delayed on the threading domain
         * handler thread, cancelling any queued but not-yet-executed {@link Runnable} previously
         * added by this. This method must be called from the threading domain's thread.
         */
        void runDelayed(Runnable r, long delayMillis) {
            cancel();
            ThreadingDomain.this.postDelayed(r, this, delayMillis);
        }

        /**
         * Cancels any queued but not-yet-executed {@link Runnable} previously added by this.
         */
        public void cancel() {
            assertCurrentThread();
            removeQueuedRunnables(this);
        }
    }
}
