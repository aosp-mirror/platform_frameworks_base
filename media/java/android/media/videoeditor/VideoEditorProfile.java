/*
 * Copyright (C) 2011 The Android Open Source Project
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
package android.media.videoeditor;

/**
 * The VideoEditorProfile class is used to retrieve the
 * predefined videoeditor profile settings for videoeditor applications.
 * These settings are read-only.
 *
 * <p>The videoeditor profile specifies the following set of parameters:
 * <ul>
 * <li> max input video frame width
 * <li> max input video frame height
 * <li> max output video frame width
 * <li> max output video frame height
 * </ul>
 * {@hide}
 */
public class VideoEditorProfile
{
    static {
        System.loadLibrary("media_jni");
        native_init();
    }
    /**
     * The max input video frame width
     */
    public int maxInputVideoFrameWidth;

    /**
     * The max input video frame height
     */
    public int maxInputVideoFrameHeight;

    /**
     * The max ouput video frame width
     */
    public int maxOutputVideoFrameWidth;

    /**
     * The max ouput video frame height
     */
    public int maxOutputVideoFrameHeight;

    /**
     * Returns the videoeditor profile
     */
    public static VideoEditorProfile get() {
        return native_get_videoeditor_profile();
    }

    /**
     * Returns the supported profile by given video codec
     */
    public static int getExportProfile(int vidCodec) {
        int profile = -1;

        switch (vidCodec) {
            case MediaProperties.VCODEC_H263:
            case MediaProperties.VCODEC_H264:
            case MediaProperties.VCODEC_MPEG4:
                 profile = native_get_videoeditor_export_profile(vidCodec);
                 break;
            default :
               throw new IllegalArgumentException("Unsupported video codec" + vidCodec);
        }

        return profile;
    }

    /**
     * Returns the supported level by given video codec
     */
    public static int getExportLevel(int vidCodec) {
        int level = -1;

        switch (vidCodec) {
            case MediaProperties.VCODEC_H263:
            case MediaProperties.VCODEC_H264:
            case MediaProperties.VCODEC_MPEG4:
                 level = native_get_videoeditor_export_profile(vidCodec);
                 break;
            default :
               throw new IllegalArgumentException("Unsupported video codec" + vidCodec);
        }

        return level;
    }

    // Private constructor called by JNI
    private VideoEditorProfile(int inputWidth,
                             int inputHeight,
                             int outputWidth,
                             int outputHeight) {

        this.maxInputVideoFrameWidth = inputWidth;
        this.maxInputVideoFrameHeight = inputHeight;
        this.maxOutputVideoFrameWidth = outputWidth;
        this.maxOutputVideoFrameHeight = outputHeight;
    }

    // Methods implemented by JNI
    private static native final void native_init();
    private static native final VideoEditorProfile
        native_get_videoeditor_profile();
    private static native final int native_get_videoeditor_export_profile(int codec);
    private static native final int native_get_videoeditor_export_level(int level);

}
