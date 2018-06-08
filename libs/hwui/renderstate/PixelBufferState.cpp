/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include "renderstate/PixelBufferState.h"

namespace android {
namespace uirenderer {

PixelBufferState::PixelBufferState() : mCurrentPixelBuffer(0) {}

bool PixelBufferState::bind(GLuint buffer) {
    if (mCurrentPixelBuffer != buffer) {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
        mCurrentPixelBuffer = buffer;
        return true;
    }
    return false;
}

bool PixelBufferState::unbind() {
    if (mCurrentPixelBuffer) {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        mCurrentPixelBuffer = 0;
        return true;
    }
    return false;
}

} /* namespace uirenderer */
} /* namespace android */
