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
import android.util.Log;
import android.util.TimeUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.ExecutionException;

/**
 * Abstract Loader that provides an {@link AsyncTask} to do the work.
 *
 * @param <D> the data type to be loaded.
 */
public abstract class AsyncTaskLoader<D> extends Loader<D> {

    private static final String TAG = "AsyncTaskLoader";

    final class LoadTask extends AsyncTask<Void, Void, D> implements Runnable {

        D result;
        boolean waiting;

        /* Runs on a worker thread */
        @Override
        protected D doInBackground(Void... params) {
            result = AsyncTaskLoader.this.onLoadInBackground();
            return result;
        }

        /* Runs on the UI thread */
        @Override
        protected void onPostExecute(D data) {
            AsyncTaskLoader.this.dispatchOnLoadComplete(this, data);
        }

        @Override
        protected void onCancelled() {
            AsyncTaskLoader.this.dispatchOnCancelled(this, result);
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
        if (mTask != null) {
            if (mCancellingTask != null) {
                // There was a pending task already waiting for a previous
                // one being canceled; just drop it.
                if (mTask.waiting) {
                    mTask.waiting = false;
                    mHandler.removeCallbacks(mTask);
                }
                mTask = null;
                return false;
            } else if (mTask.waiting) {
                // There is a task, but it is waiting for the time it should
                // execute.  We can just toss it.
                mTask.waiting = false;
                mHandler.removeCallbacks(mTask);
                mTask = null;
                return false;
            } else {
                boolean cancelled = mTask.cancel(false);
                if (cancelled) {
                    mCancellingTask = mTask;
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
    public void onCancelled(D data) {
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
                    mTask.waiting = true;
                    mHandler.postAtTime(mTask, mLastLoadCompleteTime+mUpdateThrottle);
                    return;
                }
            }
            mTask.execute((Void[]) null);
        }
    }

    void dispatchOnCancelled(LoadTask task, D data) {
        onCancelled(data);
        if (mCancellingTask == task) {
            mLastLoadCompleteTime = SystemClock.uptimeMillis();
            mCancellingTask = null;
            executePendingTask();
        }
    }

    void dispatchOnLoadComplete(LoadTask task, D data) {
        if (mTask != task) {
            dispatchOnCancelled(task, data);
        } else {
            mLastLoadCompleteTime = SystemClock.uptimeMillis();
            mTask = null;
            deliverResult(data);
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
     * Locks the current thread until the loader completes the current load
     * operation. Returns immediately if there is no load operation running.
     * Should not be called from the UI thread.
     * <p>
     * Use for testing only.  <b>Never</b> call this from a UI thread.
     */
    public void waitForLoader() {
        LoadTask task = mTask;
        if (task != null) {
            try {
                task.get();
            } catch (InterruptedException e) {
                Log.w(TAG, e);
            } catch (ExecutionException e) {
                throw new RuntimeException("An error occured while executing waitForLoader()",
                        e.getCause());
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
