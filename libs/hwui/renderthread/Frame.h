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

#pragma once

#include <stdint.h>

struct SkRect;
typedef void* EGLSurface;

namespace android {
namespace uirenderer {
namespace renderthread {

class Frame {
public:
    Frame(int32_t width, int32_t height, int32_t bufferAge)
            : mWidth(width), mHeight(height), mBufferAge(bufferAge) {}

    int32_t width() const { return mWidth; }
    int32_t height() const { return mHeight; }

    // See: https://www.khronos.org/registry/egl/extensions/EXT/EGL_EXT_buffer_age.txt
    // for what this means
    int32_t bufferAge() const { return mBufferAge; }

private:
    Frame() {}
    friend class EglManager;

    int32_t mWidth;
    int32_t mHeight;
    int32_t mBufferAge;

    EGLSurface mSurface;

    // Maps from 0,0 in top-left to 0,0 in bottom-left
    // If out is not an int32_t[4] you're going to have a bad time
    void map(const SkRect& in, int32_t* out) const;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
