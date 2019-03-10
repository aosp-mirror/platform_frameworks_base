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
 * limitations under the License.
 */

package com.android.server.wm;

import android.os.Process;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.function.Predicate;

/**
 * The common threading logic for persisters to use so that they can run in the same threads.
 * Methods in this class are synchronized on its instance, so caller could also synchronize on
 * its instance to perform modifications in items.
 */
class PersisterQueue {
    private static final String TAG = "PersisterQueue";
    private static final boolean DEBUG = false;

    /** When not flushing don't write out files faster than this */
    private static final long INTER_WRITE_DELAY_MS = 500;

    /**
     * When not flushing delay this long before writing the first file out. This gives the next task
     * being launched a chance to load its resources without this occupying IO bandwidth.
     */
    private static final long PRE_TASK_DELAY_MS = 3000;

    /** The maximum number of entries to keep in the queue before draining it automatically. */
    private static final int MAX_WRITE_QUEUE_LENGTH = 6;

    /** Special value for mWriteTime to mean don't wait, just write */
    private static final long FLUSH_QUEUE = -1;

    /** An {@link WriteQueueItem} that doesn't do anything. Used to trigger {@link
     * Listener#onPreProcessItem}. */
    static final WriteQueueItem EMPTY_ITEM = () -> { };

    private final long mInterWriteDelayMs;
    private final long mPreTaskDelayMs;
    private final LazyTaskWriterThread mLazyTaskWriterThread;
    private final ArrayList<WriteQueueItem> mWriteQueue = new ArrayList<>();

    private final ArrayList<Listener> mListeners = new ArrayList<>();

    /**
     * Value determines write delay mode as follows: < 0 We are Flushing. No delays between writes
     * until the image queue is drained and all tasks needing persisting are written to disk. There
     * is no delay between writes. == 0 We are Idle. Next writes will be delayed by
     * #PRE_TASK_DELAY_MS. > 0 We are Actively writing. Next write will be at this time. Subsequent
     * writes will be delayed by #INTER_WRITE_DELAY_MS.
     */
    private long mNextWriteTime = 0;

    PersisterQueue() {
        this(INTER_WRITE_DELAY_MS, PRE_TASK_DELAY_MS);
    }

    /** Used for tests to reduce waiting time. */
    @VisibleForTesting
    PersisterQueue(long interWriteDelayMs, long preTaskDelayMs) {
        if (interWriteDelayMs < 0 || preTaskDelayMs < 0) {
            throw new IllegalArgumentException("Both inter-write delay and pre-task delay need to"
                    + "be non-negative. inter-write delay: " + interWriteDelayMs
                    + "ms pre-task delay: " + preTaskDelayMs);
        }
        mInterWriteDelayMs = interWriteDelayMs;
        mPreTaskDelayMs = preTaskDelayMs;
        mLazyTaskWriterThread = new LazyTaskWriterThread("LazyTaskWriterThread");
    }

    synchronized void startPersisting() {
        if (!mLazyTaskWriterThread.isAlive()) {
            mLazyTaskWriterThread.start();
        }
    }

    /** Stops persisting thread. Should only be used in tests. */
    @VisibleForTesting
    void stopPersisting() throws InterruptedException {
        if (!mLazyTaskWriterThread.isAlive()) {
            return;
        }

        synchronized (this) {
            mLazyTaskWriterThread.interrupt();
        }
        mLazyTaskWriterThread.join();
    }

    synchronized void addItem(WriteQueueItem item, boolean flush) {
        mWriteQueue.add(item);

        if (flush || mWriteQueue.size() > MAX_WRITE_QUEUE_LENGTH) {
            mNextWriteTime = FLUSH_QUEUE;
        } else if (mNextWriteTime == 0) {
            mNextWriteTime = SystemClock.uptimeMillis() + mPreTaskDelayMs;
        }
        notify();
    }

    synchronized <T extends WriteQueueItem> T findLastItem(Predicate<T> predicate, Class<T> clazz) {
        for (int i = mWriteQueue.size() - 1; i >= 0; --i) {
            WriteQueueItem writeQueueItem = mWriteQueue.get(i);
            if (clazz.isInstance(writeQueueItem)) {
                T item = clazz.cast(writeQueueItem);
                if (predicate.test(item)) {
                    return item;
                }
            }
        }

        return null;
    }

    /**
     * Updates the last item found in the queue that matches the given item, or adds it to the end
     * of the queue if no such item is found.
     */
    synchronized <T extends WriteQueueItem> void updateLastOrAddItem(T item, boolean flush) {
        final T itemToUpdate = findLastItem(item::matches, (Class<T>) item.getClass());
        if (itemToUpdate == null) {
            addItem(item, flush);
        } else {
            itemToUpdate.updateFrom(item);
        }

        yieldIfQueueTooDeep();
    }

    /**
     * Removes all items with which given predicate returns {@code true}.
     */
    synchronized <T extends WriteQueueItem> void removeItems(Predicate<T> predicate,
            Class<T> clazz) {
        for (int i = mWriteQueue.size() - 1; i >= 0; --i) {
            WriteQueueItem writeQueueItem = mWriteQueue.get(i);
            if (clazz.isInstance(writeQueueItem)) {
                T item = clazz.cast(writeQueueItem);
                if (predicate.test(item)) {
                    if (DEBUG) Slog.d(TAG, "Removing " + item + " from write queue.");
                    mWriteQueue.remove(i);
                }
            }
        }
    }

    synchronized void flush() {
        mNextWriteTime = FLUSH_QUEUE;
        notifyAll();
        do {
            try {
                wait();
            } catch (InterruptedException e) {
            }
        } while (mNextWriteTime == FLUSH_QUEUE);
    }

    void yieldIfQueueTooDeep() {
        boolean stall = false;
        synchronized (this) {
            if (mNextWriteTime == FLUSH_QUEUE) {
                stall = true;
            }
        }
        if (stall) {
            Thread.yield();
        }
    }

    void addListener(Listener listener) {
        mListeners.add(listener);
    }

    @VisibleForTesting
    boolean removeListener(Listener listener) {
        return mListeners.remove(listener);
    }

    private void processNextItem() throws InterruptedException {
        // This part is extracted into a method so that the GC can clearly see the end of the
        // scope of the variable 'item'.  If this part was in the loop in LazyTaskWriterThread, the
        // last item it processed would always "leak".
        // See https://b.corp.google.com/issues/64438652#comment7

        // If mNextWriteTime, then don't delay between each call to saveToXml().
        final WriteQueueItem item;
        synchronized (this) {
            if (mNextWriteTime != FLUSH_QUEUE) {
                // The next write we don't have to wait so long.
                mNextWriteTime = SystemClock.uptimeMillis() + mInterWriteDelayMs;
                if (DEBUG) {
                    Slog.d(TAG, "Next write time may be in " + mInterWriteDelayMs
                            + " msec. (" + mNextWriteTime + ")");
                }
            }

            while (mWriteQueue.isEmpty()) {
                if (mNextWriteTime != 0) {
                    mNextWriteTime = 0; // idle.
                    notify(); // May need to wake up flush().
                }
                // Make sure we exit this thread correctly when interrupted before going to
                // indefinite wait.
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                if (DEBUG) Slog.d(TAG, "LazyTaskWriter: waiting indefinitely.");
                wait();
                // Invariant: mNextWriteTime is either FLUSH_QUEUE or PRE_WRITE_DELAY_MS
                // from now.
            }
            item = mWriteQueue.remove(0);

            long now = SystemClock.uptimeMillis();
            if (DEBUG) {
                Slog.d(TAG, "LazyTaskWriter: now=" + now + " mNextWriteTime=" + mNextWriteTime
                        + " mWriteQueue.size=" + mWriteQueue.size());
            }
            while (now < mNextWriteTime) {
                if (DEBUG) {
                    Slog.d(TAG, "LazyTaskWriter: waiting " + (mNextWriteTime - now));
                }
                wait(mNextWriteTime - now);
                now = SystemClock.uptimeMillis();
            }

            // Got something to do.
        }

        item.process();
    }

    interface WriteQueueItem<T extends WriteQueueItem<T>> {
        void process();

        default void updateFrom(T item) {}

        default boolean matches(T item) {
            return false;
        }
    }

    interface Listener {
        /**
         * Called before {@link PersisterQueue} tries to process next item.
         *
         * Note if the queue is empty, this callback will be called before the indefinite wait. This
         * will be called once when {@link PersisterQueue} starts the internal thread before the
         * indefinite wait.
         *
         * This callback is called w/o locking the instance of {@link PersisterQueue}.
         *
         * @param queueEmpty {@code true} if the queue is empty, which indicates {@link
         * PersisterQueue} is likely to enter indefinite wait; or {@code false} if there is still
         * item to process.
         */
        void onPreProcessItem(boolean queueEmpty);
    }

    private class LazyTaskWriterThread extends Thread {

        private LazyTaskWriterThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                while (true) {
                    final boolean probablyDone;
                    synchronized (PersisterQueue.this) {
                        probablyDone = mWriteQueue.isEmpty();
                    }

                    for (int i = mListeners.size() - 1; i >= 0; --i) {
                        mListeners.get(i).onPreProcessItem(probablyDone);
                    }

                    processNextItem();
                }
            } catch (InterruptedException e) {
                Slog.e(TAG, "Persister thread is exiting. Should never happen in prod, but"
                        + "it's OK in tests.");
            }
        }
    }
}
