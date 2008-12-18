/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * The AudioFormat class is used to access a number of audio format and
 * channel configuration constants.
 * 
 * {@hide Pending API council review}
 */
public class AudioFormat {
    
    //---------------------------------------------------------
    // Constants
    //--------------------
    /** Invalid audio data format */
    public static final int ENCODING_INVALID = 0;
    /** Default audio data format */
    public static final int ENCODING_DEFAULT = 1;
    /** Audio data format: PCM 16 bit per sample */
    public static final int ENCODING_PCM_16BIT = 2; // accessed by native code
    /** Audio data format: PCM 8 bit per sample */
    public static final int ENCODING_PCM_8BIT = 3;  // accessed by native code

    /** Invalid audio channel configuration */
    public static final int CHANNEL_CONFIGURATION_INVALID   = 0;
    /** Default audio channel configuration */
    public static final int CHANNEL_CONFIGURATION_DEFAULT   = 1;
    /** Mono audio configuration */
    public static final int CHANNEL_CONFIGURATION_MONO      = 2;
    /** Stereo (2 channel) audio configuration */
    public static final int CHANNEL_CONFIGURATION_STEREO    = 3;

}



