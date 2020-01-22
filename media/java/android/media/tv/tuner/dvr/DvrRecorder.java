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

/**
 * Digital Video Record (DVR) recorder class which provides record control on Demux's output buffer.
 *
 * @hide
 */
@SystemApi
public class DvrRecorder extends Dvr {
    private native long nativeWrite(long size);
    private native long nativeWrite(byte[] bytes, long offset, long size);

    private DvrRecorder() {
        super(Dvr.TYPE_RECORD);
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
