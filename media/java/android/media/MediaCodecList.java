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

/**
 * MediaCodecList class can be used to enumerate available codecs,
 * find a codec supporting a given format and query the capabilities
 * of a given codec.
*/
final public class MediaCodecList {
    /** Count the number of available codecs.
      */
    public static native final int countCodecs();

    /** Retrieve the codec name at the specified index. */
    public static native final String getCodecName(int index);

    /** Query if the codec at the specified index is an encoder. */
    public static native final boolean isEncoder(int index);

    /** Query the media types supported by the codec at the specified index */
    public static native final String[] getSupportedTypes(int index);

    public static final class CodecProfileLevel {
        /** Defined in the OpenMAX IL specs, depending on the type of media
          * this can be OMX_VIDEO_AVCPROFILETYPE, OMX_VIDEO_H263PROFILETYPE
          * or OMX_VIDEO_MPEG4PROFILETYPE.
        */
        public int profile;

        /** Defined in the OpenMAX IL specs, depending on the type of media
          * this can be OMX_VIDEO_AVCLEVELTYPE, OMX_VIDEO_H263LEVELTYPE
          * or OMX_VIDEO_MPEG4LEVELTYPE.
        */
        public int level;
    };

    public static final class CodecCapabilities {
        public CodecProfileLevel[] profileLevels;

        /** Defined in the OpenMAX IL specs, color format values are drawn from
          * OMX_COLOR_FORMATTYPE.
        */
        public int[] colorFormats;
    };
    public static native final CodecCapabilities getCodecCapabilities(
            int index, String type);

    private static native final void native_init();

    private MediaCodecList() {}

    static {
        System.loadLibrary("media_jni");
        native_init();
    }
}
