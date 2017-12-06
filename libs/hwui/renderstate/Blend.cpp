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
#include <renderstate/Blend.h>
#include "Program.h"

#include "ShadowTessellator.h"

namespace android {
namespace uirenderer {

/**
 * Structure mapping Skia xfermodes to OpenGL blending factors.
 */
struct Blender {
    SkBlendMode mode;
    GLenum src;
    GLenum dst;
};

// assumptions made by lookup tables in either this file or ProgramCache
static_assert(0 == static_cast<int>(SkBlendMode::kClear), "SkBlendMode enums have changed");
static_assert(1 == static_cast<int>(SkBlendMode::kSrc), "SkBlendMode enums have changed");
static_assert(2 == static_cast<int>(SkBlendMode::kDst), "SkBlendMode enums have changed");
static_assert(3 == static_cast<int>(SkBlendMode::kSrcOver), "SkBlendMode enums have changed");
static_assert(4 == static_cast<int>(SkBlendMode::kDstOver), "SkBlendMode enums have changed");
static_assert(5 == static_cast<int>(SkBlendMode::kSrcIn), "SkBlendMode enums have changed");
static_assert(6 == static_cast<int>(SkBlendMode::kDstIn), "SkBlendMode enums have changed");
static_assert(7 == static_cast<int>(SkBlendMode::kSrcOut), "SkBlendMode enums have changed");
static_assert(8 == static_cast<int>(SkBlendMode::kDstOut), "SkBlendMode enums have changed");
static_assert(9 == static_cast<int>(SkBlendMode::kSrcATop), "SkBlendMode enums have changed");
static_assert(10 == static_cast<int>(SkBlendMode::kDstATop), "SkBlendMode enums have changed");
static_assert(11 == static_cast<int>(SkBlendMode::kXor), "SkBlendMode enums have changed");
static_assert(12 == static_cast<int>(SkBlendMode::kPlus), "SkBlendMode enums have changed");
static_assert(13 == static_cast<int>(SkBlendMode::kModulate), "SkBlendMode enums have changed");
static_assert(14 == static_cast<int>(SkBlendMode::kScreen), "SkBlendMode enums have changed");
static_assert(15 == static_cast<int>(SkBlendMode::kOverlay), "SkBlendMode enums have changed");
static_assert(16 == static_cast<int>(SkBlendMode::kDarken), "SkBlendMode enums have changed");
static_assert(17 == static_cast<int>(SkBlendMode::kLighten), "SkBlendMode enums have changed");

// In this array, the index of each Blender equals the value of the first
// entry. For instance, gBlends[1] == gBlends[SkBlendMode::kSrc]
const Blender kBlends[] = {
    { SkBlendMode::kClear,    GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kSrc,      GL_ONE,                 GL_ZERO },
    { SkBlendMode::kDst,      GL_ZERO,                GL_ONE },
    { SkBlendMode::kSrcOver,  GL_ONE,                 GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kDstOver,  GL_ONE_MINUS_DST_ALPHA, GL_ONE },
    { SkBlendMode::kSrcIn,    GL_DST_ALPHA,           GL_ZERO },
    { SkBlendMode::kDstIn,    GL_ZERO,                GL_SRC_ALPHA },
    { SkBlendMode::kSrcOut,   GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkBlendMode::kDstOut,   GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kSrcATop,  GL_DST_ALPHA,           GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kDstATop,  GL_ONE_MINUS_DST_ALPHA, GL_SRC_ALPHA },
    { SkBlendMode::kXor,      GL_ONE_MINUS_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kPlus,     GL_ONE,                 GL_ONE },
    { SkBlendMode::kModulate, GL_ZERO,                GL_SRC_COLOR },
    { SkBlendMode::kScreen,   GL_ONE,                 GL_ONE_MINUS_SRC_COLOR }
};

// This array contains the swapped version of each SkBlendMode. For instance
// this array's SrcOver blending mode is actually DstOver. You can refer to
// createLayer() for more information on the purpose of this array.
const Blender kBlendsSwap[] = {
    { SkBlendMode::kClear,    GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkBlendMode::kSrc,      GL_ZERO,                GL_ONE },
    { SkBlendMode::kDst,      GL_ONE,                 GL_ZERO },
    { SkBlendMode::kSrcOver,  GL_ONE_MINUS_DST_ALPHA, GL_ONE },
    { SkBlendMode::kDstOver,  GL_ONE,                 GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kSrcIn,    GL_ZERO,                GL_SRC_ALPHA },
    { SkBlendMode::kDstIn,    GL_DST_ALPHA,           GL_ZERO },
    { SkBlendMode::kSrcOut,   GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kDstOut,   GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkBlendMode::kSrcATop,  GL_ONE_MINUS_DST_ALPHA, GL_SRC_ALPHA },
    { SkBlendMode::kDstATop,  GL_DST_ALPHA,           GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kXor,      GL_ONE_MINUS_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA },
    { SkBlendMode::kPlus,     GL_ONE,                 GL_ONE },
    { SkBlendMode::kModulate, GL_DST_COLOR,           GL_ZERO },
    { SkBlendMode::kScreen,   GL_ONE_MINUS_DST_COLOR, GL_ONE }
};

Blend::Blend()
    : mEnabled(false)
    , mSrcMode(GL_ZERO)
    , mDstMode(GL_ZERO) {
    // gl blending off by default
}

void Blend::invalidate() {
    syncEnabled();
    mSrcMode = mDstMode = GL_ZERO;
}

void Blend::syncEnabled() {
    if (mEnabled) {
        glEnable(GL_BLEND);
    } else {
        glDisable(GL_BLEND);
    }
}

void Blend::getFactors(SkBlendMode mode, ModeOrderSwap modeUsage, GLenum* outSrc, GLenum* outDst) {
    int index = static_cast<int>(mode);
    *outSrc = (modeUsage == ModeOrderSwap::Swap) ? kBlendsSwap[index].src : kBlends[index].src;
    *outDst = (modeUsage == ModeOrderSwap::Swap) ? kBlendsSwap[index].dst : kBlends[index].dst;
}

void Blend::setFactors(GLenum srcMode, GLenum dstMode) {
    if ((srcMode == GL_ZERO || srcMode == GL_ONE) && dstMode == GL_ZERO) {
        // disable blending
        if (mEnabled) {
            glDisable(GL_BLEND);
            mEnabled = false;
        }
    } else {
        // enable blending
        if (!mEnabled) {
            glEnable(GL_BLEND);
            mEnabled = true;
        }

        if (srcMode != mSrcMode || dstMode != mDstMode) {
            glBlendFunc(srcMode, dstMode);
            mSrcMode = srcMode;
            mDstMode = dstMode;
        }
    }
}

void Blend::dump() {
    ALOGD("Blend: enabled %d, func src %d, dst %d", mEnabled, mSrcMode, mDstMode);
}

} /* namespace uirenderer */
} /* namespace android */

