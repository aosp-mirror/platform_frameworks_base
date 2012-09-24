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

#include <GLES2/gl2.h>

#include "Properties.h"
#include "Stencil.h"

namespace android {
namespace uirenderer {

Stencil::Stencil(): mState(kDisabled) {
}

uint32_t Stencil::getStencilSize() {
    return STENCIL_BUFFER_SIZE;
}

void Stencil::clear() {
    glClearStencil(0);
    glClear(GL_STENCIL_BUFFER_BIT);
}

void Stencil::enableTest() {
    if (mState != kTest) {
        enable();
        glStencilFunc(GL_EQUAL, 0x1, 0x1);
        // We only want to test, let's keep everything
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        mState = kTest;
    }
}

void Stencil::enableWrite() {
    if (mState != kWrite) {
        enable();
        glStencilFunc(GL_ALWAYS, 0x1, 0x1);
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
