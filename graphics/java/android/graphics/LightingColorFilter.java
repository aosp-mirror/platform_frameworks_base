/*
 * Copyright (C) 2006 The Android Open Source Project
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

// This file was generated from the C++ include file: SkColorFilter.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

package android.graphics;

public class LightingColorFilter extends ColorFilter {

    /**
     * Create a colorfilter that multiplies the RGB channels by one color, and then adds a second color,
     * pinning the result for each component to [0..255]. The alpha components of the mul and add arguments
     * are ignored.
     */
    public LightingColorFilter(int mul, int add) {
        native_instance = native_CreateLightingFilter(mul, add);
        nativeColorFilter = nCreateLightingFilter(native_instance, mul, add);
    }

    private static native int native_CreateLightingFilter(int mul, int add);
    private static native int nCreateLightingFilter(int nativeFilter, int mul, int add);
}
