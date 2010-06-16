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

/**
 * Abstract Loader that provides an {@link AsyncTask} to do the work.
 *
 * @param <D> the data type to be loaded.
 */
public abstract class AsyncTaskLoader<D> extends Loader<D> {
    final class LoadTask extends AsyncTask<Void, Void, D> {

        private D result;

        /* Runs on a worker thread */
        @Override
        protected D doInBackground(Void... params) {
            result = AsyncTaskLoader.this.loadInBackground();
            return result;
        }

        /* Runs on the UI thread */
        @Override
        protected void onPostExecute(D data) {
            AsyncTaskLoader.this.dispatchOnLoadComplete(data);
        }

        @Override
        protected void onCancelled() {
            AsyncTaskLoader.this.onCancelled(result);
        }
    }

    LoadTask mTask;

    public AsyncTaskLoader(Context context) {
        super(context);
    }

    /**
     * Force an asynchronous load. Unlike {@link #startLoading()} this will ignore a previously
     * loaded data set and load a new one.
     */
    @Override
    public void forceLoad() {
        cancelLoad();
        mTask = new LoadTask();
        mTask.execute((Void[]) null);
    }

    /**
     * Attempt to cancel the current load task. See {@link AsyncTask#cancel(boolean)}
     * for more info.
     *
     * @return <tt>false</tt> if the task could not be canceled,
     *         typically because it has already completed normally, or
     *         because {@link #startLoading()} hasn't been called, and
     *         <tt>true</tt> otherwise
     */
    public boolean cancelLoad() {
        if (mTask != null) {
            boolean cancelled = mTask.cancel(false);
            mTask = null;
            return cancelled;
        }
        return false;
    }

    /**
     * Called if the task was canceled before it was completed.  Gives the class a chance
     * to properly dispose of the result.
     */
    public void onCancelled(D data) {
    }

    void dispatchOnLoadComplete(D data) {
        mTask = null;
        deliverResult(data);
    }

    /**
     * Called on a worker thread to perform the actual load. Implementations should not deliver the
     * results directly, but should return them from this method, which will eventually end up
     * calling deliverResult on the UI thread. If implementations need to process
     * the results on the UI thread they may override deliverResult and do so
     * there.
     *
     * @return the result of the load
     */
    public abstract D loadInBackground();
}
