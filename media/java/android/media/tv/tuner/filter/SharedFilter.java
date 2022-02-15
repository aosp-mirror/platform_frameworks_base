/*
 * Copyright 2021 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.BytesLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.Tuner.Result;
import android.media.tv.tuner.TunerUtils;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Tuner shared data filter.
 *
 * <p>This class is used to filter wanted data in a different process.
 *
 * @hide
 */
@SystemApi
public final class SharedFilter implements AutoCloseable {
    /** @hide */
    @IntDef(prefix = "STATUS_", value = {STATUS_INACCESSIBLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {}

    /**
     * The status of a shared filter that its data becomes inaccessible.
     */
    public static final int STATUS_INACCESSIBLE = 1 << 7;

    private static final String TAG = "SharedFilter";

    private long mNativeContext;
    private SharedFilterCallback mCallback;
    private Executor mExecutor;
    private Object mCallbackLock = null;
    private boolean mIsClosed = false;
    private boolean mIsAccessible = true;
    private Object mLock = null;

    private native int nativeStartSharedFilter();
    private native int nativeStopSharedFilter();
    private native int nativeFlushSharedFilter();
    private native int nativeSharedRead(byte[] buffer, long offset, long size);
    private native int nativeSharedClose();

    // Called by JNI
    private SharedFilter() {
        mCallbackLock = new Object();
        mLock = new Object();
    }

    private void onFilterStatus(int status) {
        synchronized (mLock) {
            if (status == STATUS_INACCESSIBLE) {
                mIsAccessible = false;
            }
        }
        synchronized (mCallbackLock) {
            if (mCallback != null && mExecutor != null) {
                mExecutor.execute(() -> {
                    synchronized (mCallbackLock) {
                        if (mCallback != null) {
                            mCallback.onFilterStatusChanged(this, status);
                        }
                    }
                });
            }
        }
    }

    private void onFilterEvent(FilterEvent[] events) {
        synchronized (mCallbackLock) {
            if (mCallback != null && mExecutor != null) {
                mExecutor.execute(() -> {
                    synchronized (mCallbackLock) {
                        if (mCallback != null) {
                            mCallback.onFilterEvent(this, events);
                        } else {
                            for (FilterEvent event : events) {
                                if (event instanceof MediaEvent) {
                                    ((MediaEvent)event).release();
                                }
                            }
                        }
                    }
                });
            } else {
                for (FilterEvent event : events) {
                    if (event instanceof MediaEvent) {
                        ((MediaEvent)event).release();
                    }
                }
            }
        }
    }

    /** @hide */
    public void setCallback(SharedFilterCallback cb, Executor executor) {
        synchronized (mCallbackLock) {
            mCallback = cb;
            mExecutor = executor;
        }
    }

    /** @hide */
    public SharedFilterCallback getCallback() {
        synchronized (mCallbackLock) { return mCallback; }
    }

    /**
     * Starts filtering data.
     *
     * <p>Does nothing if the filter is already started.
     *
     * @return result status of the operation.
     */
    @Result
    public int start() {
        synchronized (mLock) {
            TunerUtils.checkResourceAccessible(TAG, mIsAccessible);
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeStartSharedFilter();
        }
    }

    /**
     * Stops filtering data.
     *
     * <p>Does nothing if the filter is stopped or not started.
     *
     * @return result status of the operation.
     */
    @Result
    public int stop() {
        synchronized (mLock) {
            TunerUtils.checkResourceAccessible(TAG, mIsAccessible);
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeStopSharedFilter();
        }
    }

    /**
     * Flushes the shared filter.
     *
     * <p>The data which is already produced by filter but not consumed yet will
     * be cleared.
     *
     * @return result status of the operation.
     */
    @Result
    public int flush() {
        synchronized (mLock) {
            TunerUtils.checkResourceAccessible(TAG, mIsAccessible);
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeFlushSharedFilter();
        }
    }

    /**
     * Copies filtered data from shared filter output to the given byte array.
     *
     * @param buffer the buffer to store the filtered data.
     * @param offset the index of the first byte in {@code buffer} to write.
     * @param size the maximum number of bytes to read.
     * @return the number of bytes read.
     */
    public int read(@NonNull byte[] buffer, @BytesLong long offset, @BytesLong long size) {
        synchronized (mLock) {
            TunerUtils.checkResourceAccessible(TAG, mIsAccessible);
            TunerUtils.checkResourceState(TAG, mIsClosed);
            size = Math.min(size, buffer.length - offset);
            return nativeSharedRead(buffer, offset, size);
        }
    }

    /**
     * Stops filtering data and releases the shared filter instance.
     */
    @Override
    public void close() {
        synchronized (mLock) {
            if (mIsClosed) {
                return;
            }
            synchronized (mCallbackLock) {
                mCallback = null;
                mExecutor = null;
            }
            nativeSharedClose();
            mIsClosed = true;
            mCallbackLock = null;
         }
    }
}
