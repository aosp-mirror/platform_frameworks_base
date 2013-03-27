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

#include "Caches.h"
#include "Dither.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Must be a power of two
#define DITHER_KERNEL_SIZE 4

///////////////////////////////////////////////////////////////////////////////
// Lifecycle
///////////////////////////////////////////////////////////////////////////////

void Dither::bindDitherTexture() {
    if (!mInitialized) {
        const uint8_t pattern[] = {
             0,  8,  2, 10,
            12,  4, 14,  6,
             3, 11,  1,  9,
            15,  7, 13,  5
        };

        glGenTextures(1, &mDitherTexture);
        glBindTexture(GL_TEXTURE_2D, mDitherTexture);

        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, DITHER_KERNEL_SIZE, DITHER_KERNEL_SIZE, 0,
                GL_ALPHA, GL_UNSIGNED_BYTE, &pattern);

        mInitialized = true;
    } else {
        glBindTexture(GL_TEXTURE_2D, mDitherTexture);
    }
}

void Dither::clear() {
    if (mInitialized) {
        glDeleteTextures(1, &mDitherTexture);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Program management
///////////////////////////////////////////////////////////////////////////////

void Dither::setupProgram(Program* program, GLuint* textureUnit) {
    GLuint textureSlot = (*textureUnit)++;
    Caches::getInstance().activeTexture(textureSlot);

    bindDitherTexture();

    float ditherSize = 1.0f / DITHER_KERNEL_SIZE;
    glUniform1i(program->getUniform("ditherSampler"), textureSlot);
    glUniform1f(program->getUniform("ditherSize"), ditherSize);
    glUniform1f(program->getUniform("ditherSizeSquared"), ditherSize * ditherSize);
}

}; // namespace uirenderer
}; // namespace android
