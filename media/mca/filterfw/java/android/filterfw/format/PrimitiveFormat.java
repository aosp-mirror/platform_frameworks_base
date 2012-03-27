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

import android.filterfw.core.FrameFormat;
import android.filterfw.core.MutableFrameFormat;

/**
 * @hide
 */
public class PrimitiveFormat {

    public static MutableFrameFormat createByteFormat(int count, int target) {
        return createFormat(FrameFormat.TYPE_BYTE, count, target);
    }

    public static MutableFrameFormat createInt16Format(int count, int target) {
        return createFormat(FrameFormat.TYPE_INT16, count, target);
    }

    public static MutableFrameFormat createInt32Format(int count, int target) {
        return createFormat(FrameFormat.TYPE_INT32, count, target);
    }

    public static MutableFrameFormat createFloatFormat(int count, int target) {
        return createFormat(FrameFormat.TYPE_FLOAT, count, target);
    }

    public static MutableFrameFormat createDoubleFormat(int count, int target) {
        return createFormat(FrameFormat.TYPE_DOUBLE, count, target);
    }

    public static MutableFrameFormat createByteFormat(int target) {
        return createFormat(FrameFormat.TYPE_BYTE, target);
    }

    public static MutableFrameFormat createInt16Format(int target) {
        return createFormat(FrameFormat.TYPE_INT16, target);
    }

    public static MutableFrameFormat createInt32Format(int target) {
        return createFormat(FrameFormat.TYPE_INT32, target);
    }

    public static MutableFrameFormat createFloatFormat(int target) {
        return createFormat(FrameFormat.TYPE_FLOAT, target);
    }

    public static MutableFrameFormat createDoubleFormat(int target) {
        return createFormat(FrameFormat.TYPE_DOUBLE, target);
    }

    private static MutableFrameFormat createFormat(int baseType, int count, int target) {
        MutableFrameFormat result = new MutableFrameFormat(baseType, target);
        result.setDimensions(count);
        return result;
    }

    private static MutableFrameFormat createFormat(int baseType, int target) {
        MutableFrameFormat result = new MutableFrameFormat(baseType, target);
        result.setDimensionCount(1);
        return result;
    }
}
