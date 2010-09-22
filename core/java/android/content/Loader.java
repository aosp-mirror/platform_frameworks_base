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

import android.database.ContentObserver;
import android.os.Handler;

/**
 * An abstract class that performs asynchronous loading of data. While Loaders are active
 * they should monitor the source of their data and deliver new results when the contents
 * change.
 *
 * @param <D> The result returned when the load is complete
 */
public abstract class Loader<D> {
    int mId;
    OnLoadCompleteListener<D> mListener;
    Context mContext;

    public final class ForceLoadContentObserver extends ContentObserver {
        public ForceLoadContentObserver() {
            super(new Handler());
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            onContentChanged();
        }
    }

    public interface OnLoadCompleteListener<D> {
        /**
         * Called on the thread that created the Loader when the load is complete.
         *
         * @param loader the loader that completed the load
         * @param data the result of the load
         */
        public void onLoadComplete(Loader<D> loader, D data);
    }

    /**
     * Stores away the application context associated with context. Since Loaders can be used
     * across multiple activities it's dangerous to store the context directly.
     *
     * @param context used to retrieve the application context.
     */
    public Loader(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Sends the result of the load to the registered listener. Should only be called by subclasses.
     *
     * Must be called from the UI thread.
     *
     * @param data the result of the load
     */
    public void deliverResult(D data) {
        if (mListener != null) {
            mListener.onLoadComplete(this, data);
        }
    }

    /**
     * @return an application context retrieved from the Context passed to the constructor.
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * @return the ID of this loader
     */
    public int getId() {
        return mId;
    }

    /**
     * Registers a class that will receive callbacks when a load is complete. The callbacks will
     * be called on the UI thread so it's safe to pass the results to widgets.
     *
     * Must be called from the UI thread
     */
    public void registerListener(int id, OnLoadCompleteListener<D> listener) {
        if (mListener != null) {
            throw new IllegalStateException("There is already a listener registered");
        }
        mListener = listener;
        mId = id;
    }

    /**
     * Must be called from the UI thread
     */
    public void unregisterListener(OnLoadCompleteListener<D> listener) {
        if (mListener == null) {
            throw new IllegalStateException("No listener register");
        }
        if (mListener != listener) {
            throw new IllegalArgumentException("Attempting to unregister the wrong listener");
        }
        mListener = null;
    }

    /**
     * Starts an asynchronous load of the contacts list data. When the result is ready the callbacks
     * will be called on the UI thread. If a previous load has been completed and is still valid
     * the result may be passed to the callbacks immediately. The loader will monitor the source of
     * the data set and may deliver future callbacks if the source changes. Calling
     * {@link #stopLoading} will stop the delivery of callbacks.
     *
     * Must be called from the UI thread
     */
    public abstract void startLoading();

    /**
     * Force an asynchronous load. Unlike {@link #startLoading()} this will ignore a previously
     * loaded data set and load a new one.
     */
    public abstract void forceLoad();

    /**
     * Stops delivery of updates until the next time {@link #startLoading()} is called
     *
     * Must be called from the UI thread
     */
    public abstract void stopLoading();

    /**
     * Destroys the loader and frees its resources, making it unusable.
     *
     * Must be called from the UI thread
     */
    public abstract void destroy();

    /**
     * Called when {@link ForceLoadContentObserver} detects a change.  Calls {@link #forceLoad()}
     * by default.
     *
     * Must be called from the UI thread
     */
    public void onContentChanged() {
        forceLoad();
    }
}