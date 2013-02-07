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

#include "Extensions.h"
#include "Properties.h"
#include "Stencil.h"

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

Stencil::Stencil(): mState(kDisabled) {
}

uint32_t Stencil::getStencilSize() {
    return STENCIL_BUFFER_SIZE;
}

GLenum Stencil::getSmallestStencilFormat() {
#if !DEBUG_STENCIL
    const Extensions& extensions = Extensions::getInstance();
    if (extensions.has1BitStencil()) {
        return GL_STENCIL_INDEX1_OES;
    } else if (extensions.has4BitStencil()) {
        return GL_STENCIL_INDEX4_OES;
    }
#endif
    return GL_STENCIL_INDEX8;
}

void Stencil::clear() {
    glClearStencil(0);
    glClear(GL_STENCIL_BUFFER_BIT);
}

void Stencil::enableTest() {
    if (mState != kTest) {
        enable();
        glStencilFunc(GL_EQUAL, STENCIL_WRITE_VALUE, STENCIL_MASK_VALUE);
        // We only want to test, let's keep everything
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        mState = kTest;
    }
}

void Stencil::enableWrite() {
    if (mState != kWrite) {
        enable();
        glStencilFunc(GL_ALWAYS, STENCIL_WRITE_VALUE, STENCIL_MASK_VALUE);
        // The test always passes so the first two values are meaningless
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        mState = kWrite;
    }
}

void Stencil::enableDebugTest(GLint value, bool greater) {
    enable();
    glStencilFunc(greater ? GL_LESS : GL_EQUAL, value, 0xffffffff);
    // We only want to test, let's keep everything
    glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    mState = kTest;
}

void Stencil::enableDebugWrite() {
    if (mState != kWrite) {
        enable();
        glStencilFunc(GL_ALWAYS, 0x1, 0xffffffff);
        // The test always passes so the first two values are meaningless
        glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        mState = kWrite;
    }
}

void Stencil::enable() {
    if (mState == kDisabled) {
        glEnable(GL_STENCIL_TEST);
    }
}

void Stencil::disable() {
    if (mState != kDisabled) {
        glDisable(GL_STENCIL_TEST);
        mState = kDisabled;
    }
}

}; // namespace uirenderer
}; // namespace android
