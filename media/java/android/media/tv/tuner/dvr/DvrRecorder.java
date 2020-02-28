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
import android.media.tv.tuner.TunerConstants.Result;
import android.media.tv.tuner.filter.Filter;
import android.os.ParcelFileDescriptor;

/**
 * Digital Video Record (DVR) recorder class which provides record control on Demux's output buffer.
 *
 * @hide
 */
@SystemApi
public class DvrRecorder implements AutoCloseable {
    private long mNativeContext;

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
        return nativeStartDvr();
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
        return nativeStopDvr();
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
        return nativeFlushDvr();
    }

    /**
     * Closes the DVR instance to release resources.
     */
    @Override
    public void close() {
        nativeClose();
    }

    /**
     * Sets file descriptor to write data.
     *
     * <p>When a write operation of the filter object is happening, this method should not be
     * called.
     *
     * @param fd the file descriptor to write data.
     * @see #write(long)
     * @see #write(byte[], long, long)
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
     * @param bytes the byte array stores the data to be written to DVR.
     * @param offset the index of the first byte in {@code bytes} to be written to DVR.
     * @param size the maximum number of bytes to write.
     * @return the number of bytes written.
     */
    @BytesLong
    public long write(@NonNull byte[] bytes, @BytesLong long offset, @BytesLong long size) {
        return nativeWrite(bytes, offset, size);
    }
}
