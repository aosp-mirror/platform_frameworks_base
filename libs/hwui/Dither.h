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

#ifndef ANDROID_HWUI_DITHER_H
#define ANDROID_HWUI_DITHER_H

#include <GLES3/gl3.h>

namespace android {
namespace uirenderer {

class Caches;
class Extensions;
class Program;

// Must be a power of two
#define DITHER_KERNEL_SIZE 4
// These must not use the .0f notation as they are used from GLSL
#define DITHER_KERNEL_SIZE_INV (1.0 / 4.0)
#define DITHER_KERNEL_SIZE_INV_SQUARE (1.0 / 16.0)

/**
 * Handles dithering for programs.
 */
class Dither {
public:
    Dither(Caches& caches);

    void clear();
    void setupProgram(Program& program, GLuint* textureUnit);

private:
    void bindDitherTexture();

    Caches& mCaches;
    bool mInitialized;
    GLuint mDitherTexture;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DITHER_H
