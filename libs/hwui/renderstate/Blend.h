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
#ifndef RENDERSTATE_BLEND_H
#define RENDERSTATE_BLEND_H

#include "Vertex.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <SkBlendMode.h>
#include <memory>

namespace android {
namespace uirenderer {

class Blend {
    friend class RenderState;

public:
    // dictates whether to swap src/dst
    enum class ModeOrderSwap {
        NoSwap,
        Swap,
    };
    void syncEnabled();

    static void getFactors(SkBlendMode mode, ModeOrderSwap modeUsage, GLenum* outSrc,
                           GLenum* outDst);
    void setFactors(GLenum src, GLenum dst);

    bool getEnabled() { return mEnabled; }
    void getFactors(GLenum* src, GLenum* dst) {
        *src = mSrcMode;
        *dst = mDstMode;
    }

    void dump();

private:
    Blend();
    void invalidate();
    bool mEnabled;
    GLenum mSrcMode;
    GLenum mDstMode;
};

} /* namespace uirenderer */
} /* namespace android */

#endif  // RENDERSTATE_BLEND_H
