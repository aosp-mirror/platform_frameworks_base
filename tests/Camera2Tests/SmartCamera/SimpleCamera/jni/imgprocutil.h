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

// Some native low-level image processing functions.


#ifndef ANDROID_FILTERFW_JNI_IMGPROCUTIL_H
#define ANDROID_FILTERFW_JNI_IMGPROCUTIL_H

inline int getIntensityFast(int R, int G, int B) {
    return (R + R + R + B + G + G + G + G) >> 3;  // see http://stackoverflow.com/a/596241
}

inline int clamp(int min, int val, int max) {
    return val < min ? min : (val > max ? max : val);
        // Note that for performance reasons, this function does *not* check if min < max!
}

#endif // ANDROID_FILTERFW_JNI_IMGPROCUTIL_H
