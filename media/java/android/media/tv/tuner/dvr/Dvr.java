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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerConstants.Result;
import android.media.tv.tuner.filter.Filter;
import android.os.ParcelFileDescriptor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Digital Video Record (DVR) interface provides record control on Demux's output buffer and
 * playback control on Demux's input buffer.
 *
 * @hide
 */
@SystemApi
public class Dvr implements AutoCloseable {

    /** @hide */
    @IntDef(prefix = "TYPE_", value = {TYPE_RECORD, TYPE_PLAYBACK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * DVR for recording.
     */
    public static final int TYPE_RECORD = Constants.DvrType.RECORD;
    /**
     * DVR for playback of recorded programs.
     */
    public static final int TYPE_PLAYBACK = Constants.DvrType.PLAYBACK;


    final int mType;
    long mNativeContext;

    private native int nativeAttachFilter(Filter filter);
    private native int nativeDetachFilter(Filter filter);
    private native int nativeConfigureDvr(DvrSettings settings);
    private native int nativeStartDvr();
    private native int nativeStopDvr();
    private native int nativeFlushDvr();
    private native int nativeClose();
    private native void nativeSetFileDescriptor(int fd);

    protected Dvr(int type) {
        mType = type;
    }

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
     */
    public void close() {
        nativeClose();
    }

    /**
     * Sets file descriptor to read/write data.
     *
     * @param fd the file descriptor to read/write data.
     */
    public void setFileDescriptor(@NonNull ParcelFileDescriptor fd) {
        nativeSetFileDescriptor(fd.getFd());
    }

    @Type
    int getType() {
        return mType;
    }
}
