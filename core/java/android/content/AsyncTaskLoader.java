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

package android.content;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;

/**
 * Abstract Loader that provides an {@link AsyncTask} to do the work.  See
 * {@link Loader} and {@link android.app.LoaderManager} for more details.
 *
 * <p>Here is an example implementation of an AsyncTaskLoader subclass that
 * loads the currently installed applications from the package manager.  This
 * implementation takes care of retrieving the application labels and sorting
 * its result set from them, monitoring for changes to the installed
 * applications, and rebuilding the list when a change in configuration requires
 * this (such as a locale change).
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/LoaderCustom.java
 *      loader}
 *
 * <p>An example implementation of a fragment that uses the above loader to show
 * the currently installed applications in a list is below.
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/LoaderCustom.java
 *      fragment}
 *
 * @param <D> the data type to be loaded.
 */
public abstract class AsyncTaskLoader<D> extends Loader<D> {
    static final String TAG = "AsyncTaskLoader";
    static final boolean DEBUG = false;

    final class LoadTask extends AsyncTask<Void, Void, D> implements Runnable {

        D result;
        boolean waiting;

        private CountDownLatch done = new CountDownLatch(1);

        /* Runs on a worker thread */
        @Override
        protected D doInBackground(Void... params) {
            if (DEBUG) Slog.v(TAG, this + " >>> doInBackground");
            result = AsyncTaskLoader.this.onLoadInBackground();
            if (DEBUG) Slog.v(TAG, this + "  <<< doInBackground");
            return result;
        }

        /* Runs on the UI thread */
        @Override
        protected void onPostExecute(D data) {
            if (DEBUG) Slog.v(TAG, this + " onPostExecute");
            try {
                AsyncTaskLoader.this.dispatchOnLoadComplete(this, data);
            } finally {
                done.countDown();
            }
        }

        @Override
        protected void onCancelled() {
            if (DEBUG) Slog.v(TAG, this + " onCancelled");
            try {
                AsyncTaskLoader.this.dispatchOnCancelled(this, result);
            } finally {
                done.countDown();
            }
        }

        @Override
        public void run() {
            waiting = false;
            AsyncTaskLoader.this.executePendingTask();
        }
    }

    volatile LoadTask mTask;
    volatile LoadTask mCancellingTask;

    long mUpdateThrottle;
    long mLastLoadCompleteTime = -10000;
    Handler mHandler;

    public AsyncTaskLoader(Context context) {
        super(context);
    }

    /**
     * Set amount to throttle updates by.  This is the minimum time from
     * when the last {@link #onLoadInBackground()} call has completed until
     * a new load is scheduled.
     *
     * @param delayMS Amount of delay, in milliseconds.
     */
    public void setUpdateThrottle(long delayMS) {
        mUpdateThrottle = delayMS;
        if (delayMS != 0) {
            mHandler = new Handler();
        }
    }

    @Override
    protected void onForceLoad() {
        super.onForceLoad();
        cancelLoad();
        mTask = new LoadTask();
        if (DEBUG) Slog.v(TAG, "Preparing load: mTask=" + mTask);
        executePendingTask();
    }

    /**
     * Attempt to cancel the current load task. See {@link AsyncTask#cancel(boolean)}
     * for more info.  Must be called on the main thread of the process.
     *
     * <p>Cancelling is not an immediate operation, since the load is performed
     * in a background thread.  If there is currently a load in progress, this
     * method requests that the load be cancelled, and notes this is the case;
     * once the background thread has completed its work its remaining state
     * will be cleared.  If another load request comes in during this time,
     * it will be held until the cancelled load is complete.
     *
     * @return Returns <tt>false</tt> if the task could not be cancelled,
     *         typically because it has already completed normally, or
     *         because {@link #startLoading()} hasn't been called; returns
     *         <tt>true</tt> otherwise.
     */
    public boolean cancelLoad() {
        if (DEBUG) Slog.v(TAG, "cancelLoad: mTask=" + mTask);
        if (mTask != null) {
            if (mCancellingTask != null) {
                // There was a pending task already waiting for a previous
                // one being canceled; just drop it.
                if (DEBUG) Slog.v(TAG,
                        "cancelLoad: still waiting for cancelled task; dropping next");
                if (mTask.waiting) {
                    mTask.waiting = false;
                    mHandler.removeCallbacks(mTask);
                }
                mTask = null;
                return false;
            } else if (mTask.waiting) {
                // There is a task, but it is waiting for the time it should
                // execute.  We can just toss it.
                if (DEBUG) Slog.v(TAG, "cancelLoad: task is waiting, dropping it");
                mTask.waiting = false;
                mHandler.removeCallbacks(mTask);
                mTask = null;
                return false;
            } else {
                boolean cancelled = mTask.cancel(false);
                if (DEBUG) Slog.v(TAG, "cancelLoad: cancelled=" + cancelled);
                if (cancelled) {
                    mCancellingTask = mTask;
                    onCancelLoadInBackground();
                }
                mTask = null;
                return cancelled;
            }
        }
        return false;
    }

    /**
     * Called if the task was canceled before it was completed.  Gives the class a chance
     * to properly dispose of the result.
     */
    public void onCanceled(D data) {
    }

    void executePendingTask() {
        if (mCancellingTask == null && mTask != null) {
            if (mTask.waiting) {
                mTask.waiting = false;
                mHandler.removeCallbacks(mTask);
            }
            if (mUpdateThrottle > 0) {
                long now = SystemClock.uptimeMillis();
                if (now < (mLastLoadCompleteTime+mUpdateThrottle)) {
                    // Not yet time to do another load.
                    if (DEBUG) Slog.v(TAG, "Waiting until "
                            + (mLastLoadCompleteTime+mUpdateThrottle)
                            + " to execute: " + mTask);
                    mTask.waiting = true;
                    mHandler.postAtTime(mTask, mLastLoadCompleteTime+mUpdateThrottle);
                    return;
                }
            }
            if (DEBUG) Slog.v(TAG, "Executing: " + mTask);
            mTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
        }
    }

    void dispatchOnCancelled(LoadTask task, D data) {
        onCanceled(data);
        if (mCancellingTask == task) {
            if (DEBUG) Slog.v(TAG, "Cancelled task is now canceled!");
            mLastLoadCompleteTime = SystemClock.uptimeMillis();
            mCancellingTask = null;
            executePendingTask();
        }
    }

    void dispatchOnLoadComplete(LoadTask task, D data) {
        if (mTask != task) {
            if (DEBUG) Slog.v(TAG, "Load complete of old task, trying to cancel");
            dispatchOnCancelled(task, data);
        } else {
            if (isAbandoned()) {
                // This cursor has been abandoned; just cancel the new data.
                onCanceled(data);
            } else {
                mLastLoadCompleteTime = SystemClock.uptimeMillis();
                mTask = null;
                if (DEBUG) Slog.v(TAG, "Delivering result");
                deliverResult(data);
            }
        }
    }

    /**
     */
    public abstract D loadInBackground();

    /**
     * Called on a worker thread to perform the actual load. Implementations should not deliver the
     * result directly, but should return them from this method, which will eventually end up
     * calling {@link #deliverResult} on the UI thread. If implementations need to process
     * the results on the UI thread they may override {@link #deliverResult} and do so
     * there.
     *
     * @return Implementations must return the result of their load operation.
     */
    protected D onLoadInBackground() {
        return loadInBackground();
    }

    /**
     * Override this method to try to abort the computation currently taking
     * place on a background thread.
     *
     * Note that when this method is called, it is possible that {@link #loadInBackground}
     * has not started yet or has already completed.
     */
    protected void onCancelLoadInBackground() {
    }

    /**
     * Returns true if the current execution of {@link #loadInBackground()} is being canceled.
     *
     * @return True if the current execution of {@link #loadInBackground()} is being canceled.
     */
    protected boolean isLoadInBackgroundCanceled() {
        return mCancellingTask != null;
    }

    /**
     * Locks the current thread until the loader completes the current load
     * operation. Returns immediately if there is no load operation running.
     * Should not be called from the UI thread: calling it from the UI
     * thread would cause a deadlock.
     * <p>
     * Use for testing only.  <b>Never</b> call this from a UI thread.
     *
     * @hide
     */
    public void waitForLoader() {
        LoadTask task = mTask;
        if (task != null) {
            try {
                task.done.await();
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        if (mTask != null) {
            writer.print(prefix); writer.print("mTask="); writer.print(mTask);
                    writer.print(" waiting="); writer.println(mTask.waiting);
        }
        if (mCancellingTask != null) {
            writer.print(prefix); writer.print("mCancellingTask="); writer.print(mCancellingTask);
                    writer.print(" waiting="); writer.println(mCancellingTask.waiting);
        }
        if (mUpdateThrottle != 0) {
            writer.print(prefix); writer.print("mUpdateThrottle=");
                    TimeUtils.formatDuration(mUpdateThrottle, writer);
                    writer.print(" mLastLoadCompleteTime=");
                    TimeUtils.formatDuration(mLastLoadCompleteTime,
                            SystemClock.uptimeMillis(), writer);
                    writer.println();
        }
    }
}
