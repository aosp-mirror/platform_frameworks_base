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
// Lifecycle
///////////////////////////////////////////////////////////////////////////////

Dither::Dither(): mCaches(NULL), mInitialized(false), mDitherTexture(0) {
}

void Dither::bindDitherTexture() {
    if (!mInitialized) {
        bool useFloatTexture = Extensions::getInstance().hasFloatTextures();

        glGenTextures(1, &mDitherTexture);
        mCaches->bindTexture(mDitherTexture);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

        if (useFloatTexture) {
            // We use a R16F texture, let's remap the alpha channel to the
            // red channel to avoid changing the shader sampling code on GL ES 3.0+
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_RED);

            float dither = 1.0f / (255.0f * DITHER_KERNEL_SIZE * DITHER_KERNEL_SIZE);
            const GLfloat pattern[] = {
                 0 * dither,  8 * dither,  2 * dither, 10 * dither,
                12 * dither,  4 * dither, 14 * dither,  6 * dither,
                 3 * dither, 11 * dither,  1 * dither,  9 * dither,
                15 * dither,  7 * dither, 13 * dither,  5 * dither
            };

            glPixelStorei(GL_UNPACK_ALIGNMENT, sizeof(GLfloat));
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, DITHER_KERNEL_SIZE, DITHER_KERNEL_SIZE, 0,
                    GL_RED, GL_FLOAT, &pattern);
        } else {
            const uint8_t pattern[] = {
                 0,  8,  2, 10,
                12,  4, 14,  6,
                 3, 11,  1,  9,
                15,  7, 13,  5
            };

            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, DITHER_KERNEL_SIZE, DITHER_KERNEL_SIZE, 0,
                    GL_ALPHA, GL_UNSIGNED_BYTE, &pattern);
        }

        mInitialized = true;
    } else {
        mCaches->bindTexture(mDitherTexture);
    }
}

void Dither::clear() {
    if (mInitialized) {
        mCaches->deleteTexture(mDitherTexture);
        mInitialized = false;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Program management
///////////////////////////////////////////////////////////////////////////////

void Dither::setupProgram(Program* program, GLuint* textureUnit) {
    if (!mCaches) mCaches = &Caches::getInstance();

    GLuint textureSlot = (*textureUnit)++;
    mCaches->activeTexture(textureSlot);

    bindDitherTexture();

    glUniform1i(program->getUniform("ditherSampler"), textureSlot);
}

}; // namespace uirenderer
}; // namespace android
