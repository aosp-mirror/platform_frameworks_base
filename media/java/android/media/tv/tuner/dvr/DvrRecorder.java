/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.dvr;

import android.annotation.BytesLong;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.Tuner.Result;
import android.media.tv.tuner.TunerUtils;
import android.media.tv.tuner.filter.Filter;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import com.android.internal.util.FrameworkStatsLog;

import java.util.concurrent.Executor;


/**
 * Digital Video Record (DVR) recorder class which provides record control on Demux's output buffer.
 *
 * @hide
 */
@SystemApi
public class DvrRecorder implements AutoCloseable {
    private static final String TAG = "TvTunerRecord";
    private long mNativeContext;
    private OnRecordStatusChangedListener mListener;
    private Executor mExecutor;
    private int mUserId;
    private static int sInstantId = 0;
    private int mSegmentId = 0;
    private int mOverflow;
    private Boolean mIsStopped = true;
    private final Object mListenerLock = new Object();

    private native int nativeAttachFilter(Filter filter);
    private native int nativeDetachFilter(Filter filter);
    private native int nativeConfigureDvr(DvrSettings settings);
    private native int nativeStartDvr();
    private native int nativeStopDvr();
    private native int nativeFlushDvr();
    private native int nativeClose();
    private native void nativeSetFileDescriptor(int fd);
    private native long nativeWrite(long size);
    private native long nativeWrite(byte[] bytes, long offset, long size);

    private DvrRecorder() {
        mUserId = Process.myUid();
        mSegmentId = (sInstantId & 0x0000ffff) << 16;
        sInstantId++;
    }

    /** @hide */
    public void setListener(
            @NonNull Executor executor, @NonNull OnRecordStatusChangedListener listener) {
        synchronized (mListenerLock) {
            mExecutor = executor;
            mListener = listener;
        }
    }

    private void onRecordStatusChanged(int status) {
        if (status == Filter.STATUS_OVERFLOW) {
            mOverflow++;
        }
        synchronized (mListenerLock) {
            if (mExecutor != null && mListener != null) {
                mExecutor.execute(() -> {
                    synchronized (mListenerLock) {
                        if (mListener != null) {
                            mListener.onRecordStatusChanged(status);
                        }
                    }
                });
            }
        }
    }


    /**
     * Attaches a filter to DVR interface for recording.
     *
     * <p>There can be multiple filters attached. Attached filters are independent, so the order
     * doesn't matter.
     *
     * @param filter the filter to be attached.
     * @return result status of the operation.
     */
    @Result
    public int attachFilter(@NonNull Filter filter) {
        return nativeAttachFilter(filter);
    }

    /**
     * Detaches a filter from DVR interface.
     *
     * @param filter the filter to be detached.
     * @return result status of the operation.
     */
    @Result
    public int detachFilter(@NonNull Filter filter) {
        return nativeDetachFilter(filter);
    }

    /**
     * Configures the DVR.
     *
     * @param settings the settings of the DVR interface.
     * @return result status of the operation.
     */
    @Result
    public int configure(@NonNull DvrSettings settings) {
        return nativeConfigureDvr(settings);
    }

    /**
     * Starts DVR.
     *
     * <p>Starts consuming playback data or producing data for recording.
     * <p>Does nothing if the filter is stopped or not started.</p>
     *
     * @return result status of the operation.
     */
    @Result
    public int start() {
        mSegmentId =  (mSegmentId & 0xffff0000) | (((mSegmentId & 0x0000ffff) + 1) & 0x0000ffff);
        mOverflow = 0;
        Log.d(TAG, "Write Stats Log for Record.");
        FrameworkStatsLog
                .write(FrameworkStatsLog.TV_TUNER_DVR_STATUS, mUserId,
                    FrameworkStatsLog.TV_TUNER_DVR_STATUS__TYPE__RECORD,
                    FrameworkStatsLog.TV_TUNER_DVR_STATUS__STATE__STARTED, mSegmentId, 0);
        synchronized (mIsStopped) {
            int result = nativeStartDvr();
            if (result == Tuner.RESULT_SUCCESS) {
                mIsStopped = false;
            }
            return result;
        }
    }

    /**
     * Stops DVR.
     *
     * <p>Stops consuming playback data or producing data for recording.
     *
     * @return result status of the operation.
     */
    @Result
    public int stop() {
        Log.d(TAG, "Write Stats Log for Playback.");
        FrameworkStatsLog
                .write(FrameworkStatsLog.TV_TUNER_DVR_STATUS, mUserId,
                    FrameworkStatsLog.TV_TUNER_DVR_STATUS__TYPE__RECORD,
                    FrameworkStatsLog.TV_TUNER_DVR_STATUS__STATE__STOPPED, mSegmentId, mOverflow);
        synchronized (mIsStopped) {
            int result = nativeStopDvr();
            if (result == Tuner.RESULT_SUCCESS) {
                mIsStopped = true;
            }
            return result;
        }
    }

    /**
     * Flushed DVR data.
     *
     * <p>The data in DVR buffer is cleared.
     *
     * @return result status of the operation.
     */
    @Result
    public int flush() {
        synchronized (mIsStopped) {
            if (mIsStopped) {
                return nativeFlushDvr();
            }
            Log.w(TAG, "Cannot flush non-stopped Record DVR.");
            return Tuner.RESULT_INVALID_STATE;
        }
    }

    /**
     * Closes the DVR instance to release resources.
     */
    @Override
    public void close() {
        int res = nativeClose();
        if (res != Tuner.RESULT_SUCCESS) {
            TunerUtils.throwExceptionForResult(res, "failed to close DVR recorder");
        }
    }

    /**
     * Sets file descriptor to write data.
     *
     * <p>When a write operation of the filter object is happening, this method should not be
     * called.
     *
     * @param fd the file descriptor to write data.
     * @see #write(long)
     */
    public void setFileDescriptor(@NonNull ParcelFileDescriptor fd) {
        nativeSetFileDescriptor(fd.getFd());
    }

    /**
     * Writes recording data to file.
     *
     * @param size the maximum number of bytes to write.
     * @return the number of bytes written.
     */
    @BytesLong
    public long write(@BytesLong long size) {
        return nativeWrite(size);
    }

    /**
     * Writes recording data to buffer.
     *
     * @param buffer the byte array stores the data from DVR.
     * @param offset the index of the first byte in {@code buffer} to write the data from DVR.
     * @param size the maximum number of bytes to write.
     * @return the number of bytes written.
     */
    @BytesLong
    public long write(@NonNull byte[] buffer, @BytesLong long offset, @BytesLong long size) {
        if (size + offset > buffer.length) {
            throw new ArrayIndexOutOfBoundsException(
                    "Array length=" + buffer.length + ", offset=" + offset + ", size=" + size);
        }
        return nativeWrite(buffer, offset, size);
    }
}
