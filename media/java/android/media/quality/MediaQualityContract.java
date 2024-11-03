/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.quality;


import android.annotation.FlaggedApi;
import android.media.tv.flags.Flags;

/**
 * @hide
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public class MediaQualityContract {

    public interface BaseParameters {
        String PARAMETER_ID = "_id";
        String PARAMETER_NAME = "_name";
        String PARAMETER_PACKAGE = "_package";
        String PARAMETER_INPUT_ID = "_input_id";

    }
    public static final class PictureQuality implements BaseParameters {
        public static final String PARAMETER_BRIGHTNESS = "brightness";
        public static final String PARAMETER_CONTRAST = "contrast";
        public static final String PARAMETER_SHARPNESS = "sharpness";
        public static final String PARAMETER_SATURATION = "saturation";
    }

    public static final class SoundQuality implements BaseParameters {
        public static final String PARAMETER_BALANCE = "balance";
        public static final String PARAMETER_BASS = "bass";
        public static final String PARAMETER_TREBLE = "treble";
    }

    private MediaQualityContract() {
    }
}
