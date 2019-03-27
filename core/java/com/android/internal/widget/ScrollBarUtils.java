/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.UnsupportedAppUsage;

public class ScrollBarUtils {

    @UnsupportedAppUsage
    public static int getThumbLength(int size, int thickness, int extent, int range) {
        // Avoid the tiny thumb.
        final int minLength = thickness * 2;
        int length = Math.round((float) size * extent / range);
        if (length < minLength) {
            length = minLength;
        }
        return length;
    }

    public static int getThumbOffset(int size, int thumbLength, int extent, int range, int offset) {
        // Avoid the too-big thumb.
        int thumbOffset = Math.round((float) (size - thumbLength) * offset / (range - extent));
        if (thumbOffset > size - thumbLength) {
            thumbOffset = size - thumbLength;
        }
        return thumbOffset;
    }
}
