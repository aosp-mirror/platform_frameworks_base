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
import android.util.DebugUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * An abstract class that performs asynchronous loading of data. While Loaders are active
 * they should monitor the source of their data and deliver new results when the contents
 * change.
 *
 * <p>Subclasses generally must implement at least {@link #onStartLoading()},
 * {@link #onStopLoading()}, {@link #onForceLoad()}, and {@link #onReset()}.
 *
 * @param <D> The result returned when the load is complete
 */
public class Loader<D> {
    int mId;
    OnLoadCompleteListener<D> mListener;
    Context mContext;
    boolean mStarted = false;
    boolean mReset = true;

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
     * Return whether this load has been started.  That is, its {@link #startLoading()}
     * has been called and no calls to {@link #stopLoading()} or
     * {@link #reset()} have yet been made.
     */
    public boolean isStarted() {
        return mStarted;
    }

    /**
     * Return whether this load has been reset.  That is, either the loader
     * has not yet been started for the first time, or its {@link #reset()}
     * has been called.
     */
    public boolean isReset() {
        return mReset;
    }

    /**
     * Starts an asynchronous load of the contacts list data. When the result is ready the callbacks
     * will be called on the UI thread. If a previous load has been completed and is still valid
     * the result may be passed to the callbacks immediately. The loader will monitor the source of
     * the data set and may deliver future callbacks if the source changes. Calling
     * {@link #stopLoading} will stop the delivery of callbacks.
     *
     * <p>This updates the Loader's internal state so that
     * {@link #isStarted()} and {@link #isReset()} will return the correct
     * values, and then calls the implementation's {@link #onStartLoading()}.
     *
     * <p>Must be called from the UI thread.
     */
    public void startLoading() {
        mStarted = true;
        mReset = false;
        onStartLoading();
    }

    /**
     * Subclasses must implement this to take care of loading their data,
     * as per {@link #startLoading()}.  This is not called by clients directly,
     * but as a result of a call to {@link #startLoading()}.
     */
    protected void onStartLoading() {
    }

    /**
     * Force an asynchronous load. Unlike {@link #startLoading()} this will ignore a previously
     * loaded data set and load a new one.  This simply calls through to the
     * implementation's {@link #onForceLoad()}.
     *
     * <p>Must be called from the UI thread.
     */
    public void forceLoad() {
        onForceLoad();
    }

    /**
     * Subclasses must implement this to take care of requests to {@link #forceLoad()}.
     */
    protected void onForceLoad() {
    }

    /**
     * Stops delivery of updates until the next time {@link #startLoading()} is called.
     *
     * <p>This updates the Loader's internal state so that
     * {@link #isStarted()} will return the correct
     * value, and then calls the implementation's {@link #onStopLoading()}.
     *
     * <p>Must be called from the UI thread.
     */
    public void stopLoading() {
        mStarted = false;
        onStopLoading();
    }

    /**
     * Subclasses must implement this to take care of stopping their loader,
     * as per {@link #stopLoading()}.  This is not called by clients directly,
     * but as a result of a call to {@link #stopLoading()}.
     */
    protected void onStopLoading() {
    }

    /**
     * Resets the state of the Loader.  The Loader should at this point free
     * all of its resources, since it may never be called again; however, its
     * {@link #startLoading()} may later be called at which point it must be
     * able to start running again.
     *
     * <p>This updates the Loader's internal state so that
     * {@link #isStarted()} and {@link #isReset()} will return the correct
     * values, and then calls the implementation's {@link #onReset()}.
     *
     * <p>Must be called from the UI thread.
     */
    public void reset() {
        onReset();
        mReset = true;
        mStarted = false;
    }

    /**
     * Subclasses must implement this to take care of resetting their loader,
     * as per {@link #reset()}.  This is not called by clients directly,
     * but as a result of a call to {@link #reset()}.
     */
    protected void onReset() {
    }

    /**
     * Called when {@link ForceLoadContentObserver} detects a change.  Calls {@link #forceLoad()}
     * by default.
     *
     * <p>Must be called from the UI thread
     */
    public void onContentChanged() {
        forceLoad();
    }

    /**
     * For debugging, converts an instance of the Loader's data class to
     * a string that can be printed.  Must handle a null data.
     */
    public String dataToString(D data) {
        StringBuilder sb = new StringBuilder(64);
        DebugUtils.buildShortClassTag(data, sb);
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        DebugUtils.buildShortClassTag(this, sb);
        sb.append(" id=");
        sb.append(mId);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Print the Loader's state into the given stream.
     *
     * @param prefix Text to print at the front of each line.
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer A PrintWriter to which the dump is to be set.
     * @param args Additional arguments to the dump request.
     */
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.print(prefix); writer.print("mId="); writer.print(mId);
                writer.print(" mListener="); writer.println(mListener);
        writer.print(prefix); writer.print("mStarted="); writer.print(mStarted);
                writer.print(" mReset="); writer.println(mReset);
    }
}