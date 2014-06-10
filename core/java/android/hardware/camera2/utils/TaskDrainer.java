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

import android.os.Handler;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import static com.android.internal.util.Preconditions.*;

/**
 * Keep track of multiple concurrent tasks starting and finishing by their key;
 * allow draining existing tasks and figuring out when all tasks have finished
 * (and new ones won't begin).
 *
 * <p>The initial state is to allow all tasks to be started and finished. A task may only be started
 * once, after which it must be finished before starting again. Likewise, finishing a task
 * that hasn't been started is also not allowed.</p>
 *
 * <p>When draining begins, no more new tasks can be started. This guarantees that at some
 * point when all the tasks are finished there will be no more collective new tasks,
 * at which point the {@link DrainListener#onDrained} callback will be invoked.</p>
 *
 *
 * @param <T>
 *          a type for the key that will represent tracked tasks;
 *          must implement {@code Object#equals}
 */
public class TaskDrainer<T> {
    /**
     * Fired asynchronously after draining has begun with {@link TaskDrainer#beginDrain}
     * <em>and</em> all tasks that were started have finished.
     */
    public interface DrainListener {
        /** All tasks have fully finished draining; there will be no more pending tasks. */
        public void onDrained();
    }

    private static final String TAG = "TaskDrainer";
    private final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private final Handler mHandler;
    private final DrainListener mListener;
    private final String mName;

    /** Set of tasks which have been started but not yet finished with #taskFinished */
    private final Set<T> mTaskSet = new HashSet<T>();
    private final Object mLock = new Object();

    private boolean mDraining = false;
    private boolean mDrainFinished = false;

    /**
     * Create a new task drainer; {@code onDrained} callbacks will be posted to the listener
     * via the {@code handler}.
     *
     * @param handler a non-{@code null} handler to use to post runnables to
     * @param listener a non-{@code null} listener where {@code onDrained} will be called
     */
    public TaskDrainer(Handler handler, DrainListener listener) {
        mHandler = checkNotNull(handler, "handler must not be null");
        mListener = checkNotNull(listener, "listener must not be null");
        mName = null;
    }

    /**
     * Create a new task drainer; {@code onDrained} callbacks will be posted to the listener
     * via the {@code handler}.
     *
     * @param handler a non-{@code null} handler to use to post runnables to
     * @param listener a non-{@code null} listener where {@code onDrained} will be called
     * @param name an optional name used for debug logging
     */
    public TaskDrainer(Handler handler, DrainListener listener, String name) {
        // XX: Probably don't need a handler at all here
        mHandler = checkNotNull(handler, "handler must not be null");
        mListener = checkNotNull(listener, "listener must not be null");
        mName = name;
    }

    /**
     * Mark an asynchronous task as having started.
     *
     * <p>A task cannot be started more than once without first having finished. Once
     * draining begins with {@link #beginDrain}, no new tasks can be started.</p>
     *
     * @param task a key to identify a task
     *
     * @see #taskFinished
     * @see #beginDrain
     *
     * @throws IllegalStateException
     *          If attempting to start a task which is already started (and not finished),
     *          or if attempting to start a task after draining has begun.
     */
    public void taskStarted(T task) {
        synchronized (mLock) {
            if (VERBOSE) {
                Log.v(TAG + "[" + mName + "]", "taskStarted " + task);
            }

            if (mDraining) {
                throw new IllegalStateException("Can't start more tasks after draining has begun");
            }

            if (!mTaskSet.add(task)) {
                throw new IllegalStateException("Task " + task + " was already started");
            }
        }
    }


    /**
     * Mark an asynchronous task as having finished.
     *
     * <p>A task cannot be finished if it hasn't started. Once finished, a task
     * cannot be finished again (unless it's started again).</p>
     *
     * @param task a key to identify a task
     *
     * @see #taskStarted
     * @see #beginDrain
     *
     * @throws IllegalStateException
     *          If attempting to start a task which is already finished (and not re-started),
     */
    public void taskFinished(T task) {
        synchronized (mLock) {
            if (VERBOSE) {
                Log.v(TAG + "[" + mName + "]", "taskFinished " + task);
            }

            if (!mTaskSet.remove(task)) {
                throw new IllegalStateException("Task " + task + " was already finished");
            }

            // If this is the last finished task and draining has already begun, fire #onDrained
            checkIfDrainFinished();
        }
    }

    /**
     * Do not allow any more tasks to be started; once all existing started tasks are finished,
     * fire the {@link DrainListener#onDrained} callback asynchronously.
     *
     * <p>This operation is idempotent; calling it more than once has no effect.</p>
     */
    public void beginDrain() {
        synchronized (mLock) {
            if (!mDraining) {
                if (VERBOSE) {
                    Log.v(TAG + "[" + mName + "]", "beginDrain started");
                }

                mDraining = true;

                // If all tasks that had started had already finished by now, fire #onDrained
                checkIfDrainFinished();
            } else {
                if (VERBOSE) {
                    Log.v(TAG + "[" + mName + "]", "beginDrain ignored");
                }
            }
        }
    }

    private void checkIfDrainFinished() {
        if (mTaskSet.isEmpty() && mDraining && !mDrainFinished) {
            mDrainFinished = true;
            postDrained();
        }
    }

    private void postDrained() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (VERBOSE) {
                    Log.v(TAG + "[" + mName + "]", "onDrained");
                }

                mListener.onDrained();
            }
        });
    }
}
