/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.LinkedList;

/**
 * Internal utility class to keep track of process-global work that's outstanding and hasn't been
 * finished yet.
 *
 * New work will be {@link #queue queued}.
 *
 * It is possible to add 'finisher'-runnables that are {@link #waitToFinish guaranteed to be run}.
 * This is used to make sure the work has been finished.
 *
 * This was created for writing SharedPreference edits out asynchronously so we'd have a mechanism
 * to wait for the writes in Activity.onPause and similar places, but we may use this mechanism for
 * other things in the future.
 *
 * The queued asynchronous work is performed on a separate, dedicated thread.
 *
 * @hide
 */
public class QueuedWork {
    private static final String LOG_TAG = QueuedWork.class.getSimpleName();

    /** Delay for delayed runnables */
    private static final long DELAY = 50;

    /** Lock for this class */
    private static final Object sLock = new Object();

    /** Finishers {@link #addFinisher added} and not yet {@link #removeFinisher removed} */
    @GuardedBy("sLock")
    private static final LinkedList<Runnable> sFinishers = new LinkedList<>();

    /** {@link #getHandler() Lazily} created handler */
    @GuardedBy("sLock")
    private static Handler sHandler = null;

    /** Work queued via {@link #queue} */
    @GuardedBy("sLock")
    private static final LinkedList<Runnable> sWork = new LinkedList<>();

    /** If new work can be delayed or not */
    @GuardedBy("sLock")
    private static boolean sCanDelay = true;

    /**
     * Lazily create a handler on a separate thread.
     *
     * @return the handler
     */
    private static Handler getHandler() {
        synchronized (sLock) {
            if (sHandler == null) {
                HandlerThread handlerThread = new HandlerThread("queued-work-looper",
                        Process.THREAD_PRIORITY_BACKGROUND);
                handlerThread.start();

                sHandler = new QueuedWorkHandler(handlerThread.getLooper());
            }
            return sHandler;
        }
    }

    /**
     * Add a finisher-runnable to wait for {@link #queue asynchronously processed work}.
     *
     * Used by SharedPreferences$Editor#startCommit().
     *
     * Note that this doesn't actually start it running.  This is just a scratch set for callers
     * doing async work to keep updated with what's in-flight. In the common case, caller code
     * (e.g. SharedPreferences) will pretty quickly call remove() after an add(). The only time
     * these Runnables are run is from {@link #waitToFinish}.
     *
     * @param finisher The runnable to add as finisher
     */
    public static void addFinisher(Runnable finisher) {
        synchronized (sLock) {
            sFinishers.add(finisher);
        }
    }

    /**
     * Remove a previously {@link #addFinisher added} finisher-runnable.
     *
     * @param finisher The runnable to remove.
     */
    public static void removeFinisher(Runnable finisher) {
        synchronized (sLock) {
            sFinishers.remove(finisher);
        }
    }

    /**
     * Trigger queued work to be processed immediately. The queued work is processed on a separate
     * thread asynchronous. While doing that run and process all finishers on this thread. The
     * finishers can be implemented in a way to check weather the queued work is finished.
     *
     * Is called from the Activity base class's onPause(), after BroadcastReceiver's onReceive,
     * after Service command handling, etc. (so async work is never lost)
     */
    public static void waitToFinish() {
        Handler handler = getHandler();

        synchronized (sLock) {
            if (handler.hasMessages(QueuedWorkHandler.MSG_RUN)) {
                // Force the delayed work to be processed now
                handler.removeMessages(QueuedWorkHandler.MSG_RUN);
                handler.sendEmptyMessage(QueuedWorkHandler.MSG_RUN);
            }

            // We should not delay any work as this might delay the finishers
            sCanDelay = false;
        }

        try {
            while (true) {
                Runnable finisher;

                synchronized (sLock) {
                    finisher = sFinishers.poll();
                }

                if (finisher == null) {
                    break;
                }

                finisher.run();
            }
        } finally {
            sCanDelay = true;
        }
    }

    /**
     * Queue a work-runnable for processing asynchronously.
     *
     * @param work The new runnable to process
     * @param shouldDelay If the message should be delayed
     */
    public static void queue(Runnable work, boolean shouldDelay) {
        Handler handler = getHandler();

        synchronized (sLock) {
            sWork.add(work);

            if (shouldDelay && sCanDelay) {
                handler.sendEmptyMessageDelayed(QueuedWorkHandler.MSG_RUN, DELAY);
            } else {
                handler.sendEmptyMessage(QueuedWorkHandler.MSG_RUN);
            }
        }
    }

    /**
     * @return True iff there is any {@link #queue async work queued}.
     */
    public static boolean hasPendingWork() {
        synchronized (sLock) {
            return !sWork.isEmpty();
        }
    }

    private static class QueuedWorkHandler extends Handler {
        static final int MSG_RUN = 1;

        QueuedWorkHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            if (msg.what == MSG_RUN) {
                LinkedList<Runnable> work;

                synchronized (sWork) {
                    work = (LinkedList<Runnable>) sWork.clone();
                    sWork.clear();

                    // Remove all msg-s as all work will be processed now
                    removeMessages(MSG_RUN);
                }

                work.forEach(Runnable::run);
            }
        }
    }
}
