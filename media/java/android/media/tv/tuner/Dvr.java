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

package android.media.tv.tuner;

import android.annotation.NonNull;
import android.media.tv.tuner.Tuner.DvrCallback;
import android.media.tv.tuner.Tuner.Filter;
import android.os.ParcelFileDescriptor;

/** @hide */
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
    private native int nativeRead(int size);
    private native int nativeRead(byte[] bytes, int offset, int size);
    private native int nativeWrite(int size);
    private native int nativeWrite(byte[] bytes, int offset, int size);

    private Dvr() {}

    /**
     * Attaches a filter to DVR interface for recording.
     *
     * @param filter the filter to be attached.
     * @return result status of the operation.
     */
    public int attachFilter(Filter filter) {
        return nativeAttachFilter(filter);
    }

    /**
     * Detaches a filter from DVR interface.
     *
     * @param filter the filter to be detached.
     * @return result status of the operation.
     */
    public int detachFilter(Filter filter) {
        return nativeDetachFilter(filter);
    }

    /**
     * Configures the DVR.
     *
     * @param settings the settings of the DVR interface.
     * @return result status of the operation.
     */
    public int configure(DvrSettings settings) {
        return nativeConfigureDvr(settings);
    }

    /**
     * Starts DVR.
     *
     * Starts consuming playback data or producing data for recording.
     *
     * @return result status of the operation.
     */
    public int start() {
        return nativeStartDvr();
    }

    /**
     * Stops DVR.
     *
     * Stops consuming playback data or producing data for recording.
     *
     * @return result status of the operation.
     */
    public int stop() {
        return nativeStopDvr();
    }

    /**
     * Flushed DVR data.
     *
     * @return result status of the operation.
     */
    public int flush() {
        return nativeFlushDvr();
    }

    /**
     * closes the DVR instance to release resources.
     *
     * @return result status of the operation.
     */
    public int close() {
        return nativeClose();
    }

    /**
     * Sets file descriptor to read/write data.
     */
    public void setFileDescriptor(ParcelFileDescriptor fd) {
        nativeSetFileDescriptor(fd.getFd());
    }

    /**
     * Reads data from the file for DVR playback.
     */
    public int read(int size) {
        return nativeRead(size);
    }

    /**
     * Reads data from the buffer for DVR playback.
     */
    public int read(@NonNull byte[] bytes, int offset, int size) {
        if (size + offset > bytes.length) {
            throw new ArrayIndexOutOfBoundsException(
                    "Array length=" + bytes.length + ", offset=" + offset + ", size=" + size);
        }
        return nativeRead(bytes, offset, size);
    }

    /**
     * Writes recording data to file.
     */
    public int write(int size) {
        return nativeWrite(size);
    }

    /**
     * Writes recording data to buffer.
     */
    public int write(@NonNull byte[] bytes, int offset, int size) {
        return nativeWrite(bytes, offset, size);
    }
}
