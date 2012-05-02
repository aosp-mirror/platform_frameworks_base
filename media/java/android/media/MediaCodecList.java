/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media;

import android.media.MediaCodecInfo;

/**
 * MediaCodecList class can be used to enumerate available codecs,
 * find a codec supporting a given format and query the capabilities
 * of a given codec.
 */
final public class MediaCodecList {
    /**
     * Count the number of available codecs.
     */
    public static native final int getCodecCount();

    public static final MediaCodecInfo getCodecInfoAt(int index) {
        if (index < 0 || index > getCodecCount()) {
            throw new IllegalArgumentException();
        }

        return new MediaCodecInfo(index);
    }

    /* package private */ static native final String getCodecName(int index);

    /* package private */ static native final boolean isEncoder(int index);

    /* package private */ static native final String[] getSupportedTypes(int index);

    /* package private */ static native final MediaCodecInfo.CodecCapabilities
        getCodecCapabilities(int index, String type);

    private static native final void native_init();

    private MediaCodecList() {}

    static {
        System.loadLibrary("media_jni");
        native_init();
    }
}
