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
    SkXfermode::Mode mode;
    GLenum src;
    GLenum dst;
};

// assumptions made by lookup tables in either this file or ProgramCache
static_assert(0 == SkXfermode::kClear_Mode, "SkXfermode enums have changed");
static_assert(1 == SkXfermode::kSrc_Mode, "SkXfermode enums have changed");
static_assert(2 == SkXfermode::kDst_Mode, "SkXfermode enums have changed");
static_assert(3 == SkXfermode::kSrcOver_Mode, "SkXfermode enums have changed");
static_assert(4 == SkXfermode::kDstOver_Mode, "SkXfermode enums have changed");
static_assert(5 == SkXfermode::kSrcIn_Mode, "SkXfermode enums have changed");
static_assert(6 == SkXfermode::kDstIn_Mode, "SkXfermode enums have changed");
static_assert(7 == SkXfermode::kSrcOut_Mode, "SkXfermode enums have changed");
static_assert(8 == SkXfermode::kDstOut_Mode, "SkXfermode enums have changed");
static_assert(9 == SkXfermode::kSrcATop_Mode, "SkXfermode enums have changed");
static_assert(10 == SkXfermode::kDstATop_Mode, "SkXfermode enums have changed");
static_assert(11 == SkXfermode::kXor_Mode, "SkXfermode enums have changed");
static_assert(12 == SkXfermode::kPlus_Mode, "SkXfermode enums have changed");
static_assert(13 == SkXfermode::kModulate_Mode, "SkXfermode enums have changed");
static_assert(14 == SkXfermode::kScreen_Mode, "SkXfermode enums have changed");
static_assert(15 == SkXfermode::kOverlay_Mode, "SkXfermode enums have changed");
static_assert(16 == SkXfermode::kDarken_Mode, "SkXfermode enums have changed");
static_assert(17 == SkXfermode::kLighten_Mode, "SkXfermode enums have changed");

// In this array, the index of each Blender equals the value of the first
// entry. For instance, gBlends[1] == gBlends[SkXfermode::kSrc_Mode]
const Blender kBlends[] = {
    { SkXfermode::kClear_Mode,    GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kSrc_Mode,      GL_ONE,                 GL_ZERO },
    { SkXfermode::kDst_Mode,      GL_ZERO,                GL_ONE },
    { SkXfermode::kSrcOver_Mode,  GL_ONE,                 GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kDstOver_Mode,  GL_ONE_MINUS_DST_ALPHA, GL_ONE },
    { SkXfermode::kSrcIn_Mode,    GL_DST_ALPHA,           GL_ZERO },
    { SkXfermode::kDstIn_Mode,    GL_ZERO,                GL_SRC_ALPHA },
    { SkXfermode::kSrcOut_Mode,   GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkXfermode::kDstOut_Mode,   GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kSrcATop_Mode,  GL_DST_ALPHA,           GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kDstATop_Mode,  GL_ONE_MINUS_DST_ALPHA, GL_SRC_ALPHA },
    { SkXfermode::kXor_Mode,      GL_ONE_MINUS_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kPlus_Mode,     GL_ONE,                 GL_ONE },
    { SkXfermode::kModulate_Mode, GL_ZERO,                GL_SRC_COLOR },
    { SkXfermode::kScreen_Mode,   GL_ONE,                 GL_ONE_MINUS_SRC_COLOR }
};

// This array contains the swapped version of each SkXfermode. For instance
// this array's SrcOver blending mode is actually DstOver. You can refer to
// createLayer() for more information on the purpose of this array.
const Blender kBlendsSwap[] = {
    { SkXfermode::kClear_Mode,    GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkXfermode::kSrc_Mode,      GL_ZERO,                GL_ONE },
    { SkXfermode::kDst_Mode,      GL_ONE,                 GL_ZERO },
    { SkXfermode::kSrcOver_Mode,  GL_ONE_MINUS_DST_ALPHA, GL_ONE },
    { SkXfermode::kDstOver_Mode,  GL_ONE,                 GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kSrcIn_Mode,    GL_ZERO,                GL_SRC_ALPHA },
    { SkXfermode::kDstIn_Mode,    GL_DST_ALPHA,           GL_ZERO },
    { SkXfermode::kSrcOut_Mode,   GL_ZERO,                GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kDstOut_Mode,   GL_ONE_MINUS_DST_ALPHA, GL_ZERO },
    { SkXfermode::kSrcATop_Mode,  GL_ONE_MINUS_DST_ALPHA, GL_SRC_ALPHA },
    { SkXfermode::kDstATop_Mode,  GL_DST_ALPHA,           GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kXor_Mode,      GL_ONE_MINUS_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA },
    { SkXfermode::kPlus_Mode,     GL_ONE,                 GL_ONE },
    { SkXfermode::kModulate_Mode, GL_DST_COLOR,           GL_ZERO },
    { SkXfermode::kScreen_Mode,   GL_ONE_MINUS_DST_COLOR, GL_ONE }
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

void Blend::getFactors(SkXfermode::Mode mode, ModeOrderSwap modeUsage, GLenum* outSrc, GLenum* outDst) {
    *outSrc = (modeUsage == ModeOrderSwap::Swap) ? kBlendsSwap[mode].src : kBlends[mode].src;
    *outDst = (modeUsage == ModeOrderSwap::Swap) ? kBlendsSwap[mode].dst : kBlends[mode].dst;
}

void Blend::setFactors(GLenum srcMode, GLenum dstMode) {
    if (srcMode == GL_ZERO && dstMode == GL_ZERO) {
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

