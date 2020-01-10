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
import android.annotation.Nullable;
import android.media.tv.tuner.Tuner.FilterCallback;
import android.media.tv.tuner.filter.FilterConfiguration;
import android.media.tv.tuner.filter.Settings;

/**
 * Tuner data filter.
 *
 * <p> This class is used to filter wanted data according to the filter's configuration.
 * @hide
 */
public class Filter implements AutoCloseable {
    private long mNativeContext;
    private FilterCallback mCallback;
    int mId;

    private native int nativeConfigureFilter(
            int type, int subType, FilterConfiguration settings);
    private native int nativeGetId();
    private native int nativeSetDataSource(Tuner.Filter source);
    private native int nativeStartFilter();
    private native int nativeStopFilter();
    private native int nativeFlushFilter();
    private native int nativeRead(byte[] buffer, int offset, int size);
    private native int nativeClose();

    private Filter(int id) {
        mId = id;
    }

    private void onFilterStatus(int status) {
    }

    /**
     * Configures the filter.
     *
     * @param settings the settings of the filter.
     * @return result status of the operation.
     * @hide
     */
    public int configure(FilterConfiguration settings) {
        int subType = -1;
        Settings s = settings.getSettings();
        if (s != null) {
            subType = s.getType();
        }
        return nativeConfigureFilter(settings.getType(), subType, settings);
    }

    /**
     * Gets the filter Id.
     *
     * @return the hardware resource Id for the filter.
     * @hide
     */
    public int getId() {
        return nativeGetId();
    }

    /**
     * Sets the filter's data source.
     *
     * A filter uses demux as data source by default. If the data was packetized
     * by multiple protocols, multiple filters may need to work together to
     * extract all protocols' header. Then a filter's data source can be output
     * from another filter.
     *
     * @param source the filter instance which provides data input. Switch to
     * use demux as data source if the filter instance is NULL.
     * @return result status of the operation.
     * @hide
     */
    public int setDataSource(@Nullable Tuner.Filter source) {
        return nativeSetDataSource(source);
    }

    /**
     * Starts the filter.
     *
     * @return result status of the operation.
     * @hide
     */
    public int start() {
        return nativeStartFilter();
    }


    /**
     * Stops the filter.
     *
     * @return result status of the operation.
     * @hide
     */
    public int stop() {
        return nativeStopFilter();
    }

    /**
     * Flushes the filter.
     *
     * @return result status of the operation.
     * @hide
     */
    public int flush() {
        return nativeFlushFilter();
    }

    /** @hide */
    public int read(@NonNull byte[] buffer, int offset, int size) {
        size = Math.min(size, buffer.length - offset);
        return nativeRead(buffer, offset, size);
    }

    /**
     * Release the Filter instance.
     *
     * @hide
     */
    @Override
    public void close() {
        nativeClose();
    }
}
