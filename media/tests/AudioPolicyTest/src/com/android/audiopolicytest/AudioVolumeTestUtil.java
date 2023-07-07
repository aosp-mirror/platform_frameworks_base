/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.audiopolicytest;

import android.media.AudioAttributes;
import android.media.audiopolicy.AudioProductStrategy;

class AudioVolumeTestUtil {
    // Default matches the invalid (empty) attributes from native.
    // The difference is the input source default which is not aligned between native and java
    public static final AudioAttributes DEFAULT_ATTRIBUTES =
            AudioProductStrategy.getDefaultAttributes();
    public static final AudioAttributes INVALID_ATTRIBUTES = new AudioAttributes.Builder().build();

    public static int resetVolumeIndex(int indexMin, int indexMax) {
        return (indexMax + indexMin) / 2;
    }

    public static int incrementVolumeIndex(int index, int indexMin, int indexMax) {
        return (index + 1 > indexMax) ? resetVolumeIndex(indexMin, indexMax) : ++index;
    }

}
