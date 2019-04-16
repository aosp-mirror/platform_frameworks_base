/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.utils;

import android.util.Log;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implement a shared/exclusive lock that can be closed.
 *
 * <p>A shared lock can be acquired if any other shared locks are also acquired. An
 * exclusive lock acquire will block until all shared locks have been released.</p>
 *
 * <p>Locks are re-entrant; trying to acquire another lock (of the same type)
 * while a lock is already held will immediately succeed.</p>
 *
 * <p>Acquiring to acquire a shared lock while holding an exclusive lock or vice versa is not
 * supported; attempting it will throw an {@link IllegalStateException}.</p>
 *
 * <p>If the lock is closed, all future and current acquires will immediately return {@code null}.
 * </p>
 */
public class CloseableLock implements AutoCloseable {

    private static final boolean VERBOSE = false;

    private final String TAG = "CloseableLock";
    private final String mName;

    private volatile boolean mClosed = false;

    /** If an exclusive lock is acquired by some thread. */
    private boolean mExclusive = false;
    /**
     * How many shared locks are acquired by any thread:
     *
     * <p>Reentrant locking increments this. If an exclusive lock is held,
     * this value will stay at 0.</p>
     */
    private int mSharedLocks = 0;

    private final ReentrantLock mLock = new ReentrantLock();
    /** This condition automatically releases mLock when waiting; re-acquiring it after notify */
    private final Condition mCondition = mLock.newCondition();

    /** How many times the current thread is holding the lock */
    private final ThreadLocal<Integer> mLockCount =
        new ThreadLocal<Integer>() {
            @Override protected Integer initialValue() {
                return 0;
            }
        };

    /**
     * Helper class to release a lock at the end of a try-with-resources statement.
     */
    public class ScopedLock implements AutoCloseable {
        private ScopedLock() {}

        /** Release the lock with {@link CloseableLock#releaseLock}. */
        @Override
        public void close() {
            releaseLock();
        }
    }

    /**
     * Create a new instance; starts out with 0 locks acquired.
     */
    public CloseableLock() {
        mName = "";
    }

    /**
     * Create a new instance; starts out with 0 locks acquired.
     *
     * @param name set an optional name for logging functionality
     */
    public CloseableLock(String name) {
        mName = name;
    }

    /**
     * Acquires the lock exclusively (blocking), marks it as closed, then releases the lock.
     *
     * <p>Marking a lock as closed will fail all further acquisition attempts;
     * it will also immediately unblock all other threads currently trying to acquire a lock.</p>
     *
     * <p>This operation is idempotent; calling it more than once has no effect.</p>
     *
     * @throws IllegalStateException
     *          if an attempt is made to {@code close} while this thread has a lock acquired
     */
    @Override
    public void close() {
        if (mClosed) {
            if (VERBOSE) {
                log("close - already closed; ignoring");
            }
            return;
        }

        ScopedLock scoper = acquireExclusiveLock();
        // Already closed by another thread?
        if (scoper == null) {
            return;
        } else if (mLockCount.get() != 1) {
            // Future: may want to add a #releaseAndClose to allow this.
            throw new IllegalStateException(
                    "Cannot close while one or more acquired locks are being held by this " +
                     "thread; release all other locks first");
        }

        try {
            mLock.lock();

            mClosed = true;
            mExclusive = false;
            mSharedLocks = 0;
            mLockCount.remove();

            // Notify all threads that are waiting to unblock and return immediately
            mCondition.signalAll();
        } finally {
            mLock.unlock();
        }

        if (VERBOSE) {
            log("close - completed");
        }
    }

    /**
     * Try to acquire the lock non-exclusively, blocking until the operation completes.
     *
     * <p>If the lock has already been closed, or being closed before this operation returns,
     * the call will immediately return {@code false}.</p>
     *
     * <p>If other threads hold a non-exclusive lock (and the lock is not yet closed),
     * this operation will return immediately. If another thread holds an exclusive lock,
     * this thread will block until the exclusive lock has been released.</p>
     *
     * <p>This lock is re-entrant; acquiring more than one non-exclusive lock per thread is
     * supported, and must be matched by an equal number of {@link #releaseLock} calls.</p>
     *
     * @return {@code ScopedLock} instance if the lock was acquired, or {@code null} if the lock
     *         was already closed.
     *
     * @throws IllegalStateException if this thread is already holding an exclusive lock
     */
    public ScopedLock acquireLock() {

        int ownedLocks;

        try {
            mLock.lock();

            // Lock is already closed, all further acquisitions will fail
            if (mClosed) {
                if (VERBOSE) {
                    log("acquire lock early aborted (already closed)");
                }
                return null;
            }

            ownedLocks = mLockCount.get();

            // This thread is already holding an exclusive lock
            if (mExclusive && ownedLocks > 0) {
                throw new IllegalStateException(
                        "Cannot acquire shared lock while holding exclusive lock");
            }

            // Is another thread holding the exclusive lock? Block until we can get in.
            while (mExclusive) {
                mCondition.awaitUninterruptibly();

                // Did another thread #close while we were waiting? Unblock immediately.
                if (mClosed) {
                    if (VERBOSE) {
                        log("acquire lock unblocked aborted (already closed)");
                    }
                    return null;
                }
            }

            mSharedLocks++;

            ownedLocks = mLockCount.get() + 1;
            mLockCount.set(ownedLocks);
        } finally {
            mLock.unlock();
        }

        if (VERBOSE) {
            log("acquired lock (local own count = " + ownedLocks + ")");
        }
        return new ScopedLock();
    }

    /**
     * Try to acquire the lock exclusively, blocking until all other threads release their locks.
     *
     * <p>If the lock has already been closed, or being closed before this operation returns,
     * the call will immediately return {@code false}.</p>
     *
     * <p>If any other threads are holding a lock, this thread will block until all
     * other locks are released.</p>
     *
     * <p>This lock is re-entrant; acquiring more than one exclusive lock per thread is supported,
     * and must be matched by an equal number of {@link #releaseLock} calls.</p>
     *
     * @return {@code ScopedLock} instance if the lock was acquired, or {@code null} if the lock
     *         was already closed.
     *
     * @throws IllegalStateException
     *          if an attempt is made to acquire an exclusive lock while already holding a lock
     */
    public ScopedLock acquireExclusiveLock() {

        int ownedLocks;

        try {
            mLock.lock();

            // Lock is already closed, all further acquisitions will fail
            if (mClosed) {
                if (VERBOSE) {
                    log("acquire exclusive lock early aborted (already closed)");
                }
                return null;
            }

            ownedLocks = mLockCount.get();

            // This thread is already holding a shared lock
            if (!mExclusive && ownedLocks > 0) {
                throw new IllegalStateException(
                        "Cannot acquire exclusive lock while holding shared lock");
            }

            /*
             * Is another thread holding the lock? Block until we can get in.
             *
             * If we are already holding the lock, always let it through since
             * we are just reentering the exclusive lock.
             */
            while (ownedLocks == 0 && (mExclusive || mSharedLocks > 0)) {
                mCondition.awaitUninterruptibly();

             // Did another thread #close while we were waiting? Unblock immediately.
                if (mClosed) {
                    if (VERBOSE) {
                        log("acquire exclusive lock unblocked aborted (already closed)");
                    }
                    return null;
                }
            }

            mExclusive = true;

            ownedLocks = mLockCount.get() + 1;
            mLockCount.set(ownedLocks);
        } finally {
            mLock.unlock();
        }

        if (VERBOSE) {
            log("acquired exclusive lock (local own count = " + ownedLocks + ")");
        }
        return new ScopedLock();
    }

    /**
     * Release a single lock that was acquired.
     *
     * <p>Any other thread that is blocked and trying to acquire a lock will get a chance
     * to acquire the lock.</p>
     *
     * @throws IllegalStateException if no locks were acquired, or if the lock was already closed
     */
    public void releaseLock() {
        if (mLockCount.get() <= 0) {
            throw new IllegalStateException(
                    "Cannot release lock that was not acquired by this thread");
        }

        int ownedLocks;

        try {
            mLock.lock();

            // Lock is already closed, it couldn't have been acquired in the first place
            if (mClosed) {
                throw new IllegalStateException("Do not release after the lock has been closed");
            }

            if (!mExclusive) {
                mSharedLocks--;
            } else {
                if (mSharedLocks != 0) {
                    throw new AssertionError("Too many shared locks " + mSharedLocks);
                }
            }

            ownedLocks = mLockCount.get() - 1;
            mLockCount.set(ownedLocks);

            if (ownedLocks == 0 && mExclusive) {
                // Wake up any threads that might be waiting for the exclusive lock to be released
                mExclusive = false;
                mCondition.signalAll();
            } else if (ownedLocks == 0 && mSharedLocks == 0) {
                // Wake up any threads that might be trying to get the exclusive lock
                mCondition.signalAll();
            }
        } finally {
            mLock.unlock();
        }

        if (VERBOSE) {
             log("released lock (local lock count " + ownedLocks + ")");
        }
    }

    private void log(String what) {
        Log.v(TAG + "[" + mName + "]", what);
    }

}
