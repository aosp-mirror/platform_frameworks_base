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

#include "renderstate/Stencil.h"

#include "Caches.h"
#include "Debug.h"
#include "Extensions.h"
#include "Properties.h"

#include <GLES2/gl2ext.h>

namespace android {
namespace uirenderer {

#if DEBUG_STENCIL
#define STENCIL_WRITE_VALUE 0xff
#define STENCIL_MASK_VALUE 0xff
#else
#define STENCIL_WRITE_VALUE 0x1
#define STENCIL_MASK_VALUE 0x1
#endif

uint8_t Stencil::getStencilSize() {
    return STENCIL_BUFFER_SIZE;
}

/**
 * This method will return either GL_STENCIL_INDEX4_OES if supported,
 * GL_STENCIL_INDEX8 if not.
 *
 * Layers can't use a single bit stencil because multi-rect ClipArea needs a high enough
 * stencil resolution to represent the summation of multiple intersecting rect geometries.
 */
GLenum Stencil::getLayerStencilFormat() {
#if !DEBUG_STENCIL
    const Extensions& extensions = Caches::getInstance().extensions();
    if (extensions.has4BitStencil()) {
        return GL_STENCIL_INDEX4_OES;
    }
#endif
    return GL_STENCIL_INDEX8;
}

void Stencil::clear() {
    glStencilMask(0xff);
    glClearStencil(0);
    glClear(GL_STENCIL_BUFFER_BIT);

    if (mState == StencilState::Test) {
        // reset to test state, with immutable stencil
        glStencilMask(0);
    }
}

void Stencil::enableTest(int incrementThreshold) {
    if (mState != StencilState::Test) {
        enable();
        if (incrementThreshold > 0) {
            glStencilFunc(GL_EQUAL, incrementThreshold, 0xff);
        } else {
            glStencilFunc(GL_EQUAL, STENCIL_WRITE_VALUE, STENCIL_MASK_VALUE);
        }
        // We only want to test, let's keep everything
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glStencilMask(0);
        mState = StencilState::Test;
    }
}

void Stencil::enableWrite(int incrementThreshold) {
    if (mState != StencilState::Write) {
        enable();
        if (incrementThreshold > 0) {
            glStencilFunc(GL_ALWAYS, 1, 0xff);
            // The test always passes so the first two values are meaningless
            glStencilOp(GL_INCR, GL_INCR, GL_INCR);
        } else {
            glStencilFunc(GL_ALWAYS, STENCIL_WRITE_VALUE, STENCIL_MASK_VALUE);
            // The test always passes so the first two values are meaningless
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        }
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glStencilMask(0xff);
        mState = StencilState::Write;
    }
}

void Stencil::enableDebugTest(GLint value, bool greater) {
    enable();
    glStencilFunc(greater ? GL_LESS : GL_EQUAL, value, 0xffffffff);
    // We only want to test, let's keep everything
    glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    mState = StencilState::Test;
    glStencilMask(0);
}

void Stencil::enableDebugWrite() {
    enable();
    glStencilFunc(GL_ALWAYS, 0x1, 0xffffffff);
    // The test always passes so the first two values are meaningless
    glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    mState = StencilState::Write;
    glStencilMask(0xff);
}

void Stencil::enable() {
    if (mState == StencilState::Disabled) {
        glEnable(GL_STENCIL_TEST);
    }
}

void Stencil::disable() {
    if (mState != StencilState::Disabled) {
        glDisable(GL_STENCIL_TEST);
        mState = StencilState::Disabled;
    }
}

void Stencil::dump() {
    ALOGD("Stencil: state %d", mState);
}

}; // namespace uirenderer
}; // namespace android
