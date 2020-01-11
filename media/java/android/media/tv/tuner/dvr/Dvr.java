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
import android.media.tv.tuner.Tuner.DvrCallback;
import android.media.tv.tuner.Tuner.Filter;
import android.media.tv.tuner.TunerConstants.Result;
import android.os.ParcelFileDescriptor;

/**
 * Digital Video Record (DVR) interface provides record control on Demux's output buffer and
 * playback control on Demux's input buffer.
 *
 * @hide
 */
public class Dvr {
    private long mNativeContext;
    private DvrCallback mCallback;

    private native int nativeAttachFilter(Filter filter);
    private native int nativeDetachFilter(Filter filter);
    private native int nativeConfigureDvr(DvrSettings settings);
    private native int nativeStartDvr();
    private native int nativeStopDvr();
    private native int nativeFlushDvr();
    private native int nativeClose();
    private native void nativeSetFileDescriptor(int fd);
    private native int nativeRead(long size);
    private native int nativeRead(byte[] bytes, long offset, long size);
    private native int nativeWrite(long size);
    private native int nativeWrite(byte[] bytes, long offset, long size);

    private Dvr() {}

    /**
     * Attaches a filter to DVR interface for recording.
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
     *
     * @return result status of the operation.
     */
    @Result
    public int close() {
        return nativeClose();
    }

    /**
     * Sets file descriptor to read/write data.
     *
     * @param fd the file descriptor to read/write data.
     */
    public void setFileDescriptor(@NonNull ParcelFileDescriptor fd) {
        nativeSetFileDescriptor(fd.getFd());
    }

    /**
     * Reads data from the file for DVR playback.
     *
     * @param size the maximum number of bytes to read.
     * @return the number of bytes read.
     */
    public int read(@BytesLong long size) {
        return nativeRead(size);
    }

    /**
     * Reads data from the buffer for DVR playback and copies to the given byte array.
     *
     * @param bytes the byte array to store the data.
     * @param offset the index of the first byte in {@code bytes} to copy to.
     * @param size the maximum number of bytes to read.
     * @return the number of bytes read.
     */
    public int read(@NonNull byte[] bytes, @BytesLong long offset, @BytesLong long size) {
        if (size + offset > bytes.length) {
            throw new ArrayIndexOutOfBoundsException(
                    "Array length=" + bytes.length + ", offset=" + offset + ", size=" + size);
        }
        return nativeRead(bytes, offset, size);
    }

    /**
     * Writes recording data to file.
     *
     * @param size the maximum number of bytes to write.
     * @return the number of bytes written.
     */
    public int write(@BytesLong long size) {
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
    public int write(@NonNull byte[] bytes, @BytesLong long offset, @BytesLong long size) {
        return nativeWrite(bytes, offset, size);
    }
}
