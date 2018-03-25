/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "NinePatchPeeker.h"

#include <hwui/Canvas.h>

#include <jni.h>

class SkAndroidCodec;

using namespace android;

struct ImageDecoder {
    // These need to stay in sync with ImageDecoder.java's Allocator constants.
    enum Allocator {
        kDefault_Allocator      = 0,
        kSoftware_Allocator     = 1,
        kSharedMemory_Allocator = 2,
        kHardware_Allocator     = 3,
    };

    // These need to stay in sync with ImageDecoder.java's Error constants.
    enum Error {
        kSourceException     = 1,
        kSourceIncomplete    = 2,
        kSourceMalformedData = 3,
    };

    // These need to stay in sync with PixelFormat.java's Format constants.
    enum PixelFormat {
        kUnknown     =  0,
        kTranslucent = -3,
        kOpaque      = -1,
    };

    std::unique_ptr<SkAndroidCodec> mCodec;
    sk_sp<NinePatchPeeker> mPeeker;

    ImageDecoder()
        :mPeeker(new NinePatchPeeker)
    {}
};

// Creates a Java Canvas object from canvas, calls jimageDecoder's PostProcess on it, and then
// releases the Canvas.
// Caller needs to check for exceptions.
jint postProcessAndRelease(JNIEnv* env, jobject jimageDecoder, std::unique_ptr<Canvas> canvas);
