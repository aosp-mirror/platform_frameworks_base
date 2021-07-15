/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.StringDef;
import android.os.Build;

import com.android.modules.annotation.MinSdk;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * MediaFeature defines various media features, e.g. hdr type.
 */
@MinSdk(Build.VERSION_CODES.S)
public final class MediaFeature {
     /**
     * Defines tye type of HDR(high dynamic range) video.
     */
    public static final class HdrType {
        private HdrType() {
        }

        /**
         * HDR type for dolby-vision.
         */
        public static final String DOLBY_VISION = "android.media.feature.hdr.dolby_vision";
        /**
         * HDR type for hdr10.
         */
        public static final String HDR10 = "android.media.feature.hdr.hdr10";
        /**
         * HDR type for hdr10+.
         */
        public static final String HDR10_PLUS = "android.media.feature.hdr.hdr10_plus";
        /**
         * HDR type for hlg.
         */
        public static final String HLG = "android.media.feature.hdr.hlg";
    }

    /** @hide */
    @StringDef({
            HdrType.DOLBY_VISION,
            HdrType.HDR10,
            HdrType.HDR10_PLUS,
            HdrType.HLG,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaHdrType {
    }
}
