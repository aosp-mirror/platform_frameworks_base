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


package android.filterfw.format;

import android.compat.annotation.UnsupportedAppUsage;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.MutableFrameFormat;
import android.graphics.Bitmap;

/**
 * @hide
 */
public class ImageFormat {

    public static final String COLORSPACE_KEY = "colorspace";

    public static final int COLORSPACE_GRAY  = 1;
    public static final int COLORSPACE_RGB   = 2;
    public static final int COLORSPACE_RGBA  = 3;
    public static final int COLORSPACE_YUV   = 4;

    public static MutableFrameFormat create(int width,
                                            int height,
                                            int colorspace,
                                            int bytesPerSample,
                                            int target) {
        MutableFrameFormat result = new MutableFrameFormat(FrameFormat.TYPE_BYTE, target);
        result.setDimensions(width, height);
        result.setBytesPerSample(bytesPerSample);
        result.setMetaValue(COLORSPACE_KEY, colorspace);
        if (target == FrameFormat.TARGET_SIMPLE) {
            result.setObjectClass(Bitmap.class);
        }
        return result;
    }

    @UnsupportedAppUsage
    public static MutableFrameFormat create(int width,
                                            int height,
                                            int colorspace,
                                            int target) {
        return create(width,
                      height,
                      colorspace,
                      bytesPerSampleForColorspace(colorspace),
                      target);
    }

    @UnsupportedAppUsage
    public static MutableFrameFormat create(int colorspace, int target) {
        return create(FrameFormat.SIZE_UNSPECIFIED,
                      FrameFormat.SIZE_UNSPECIFIED,
                      colorspace,
                      bytesPerSampleForColorspace(colorspace),
                      target);
    }

    @UnsupportedAppUsage
    public static MutableFrameFormat create(int colorspace) {
        return create(FrameFormat.SIZE_UNSPECIFIED,
                      FrameFormat.SIZE_UNSPECIFIED,
                      colorspace,
                      bytesPerSampleForColorspace(colorspace),
                      FrameFormat.TARGET_UNSPECIFIED);
    }

    public static int bytesPerSampleForColorspace(int colorspace) {
        switch (colorspace) {
            case COLORSPACE_GRAY:
                return 1;
            case COLORSPACE_RGB:
                return 3;
            case COLORSPACE_RGBA:
                return 4;
            case COLORSPACE_YUV:
                return 3;
            default:
                throw new RuntimeException("Unknown colorspace id " + colorspace + "!");
        }
    }
}
