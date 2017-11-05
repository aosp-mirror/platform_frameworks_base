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
#include "renderstate/Scissor.h"

#include "Rect.h"

#include <utils/Log.h>

namespace android {
namespace uirenderer {

Scissor::Scissor()
        : mEnabled(false), mScissorX(0), mScissorY(0), mScissorWidth(0), mScissorHeight(0) {}

bool Scissor::setEnabled(bool enabled) {
    if (mEnabled != enabled) {
        if (enabled) {
            glEnable(GL_SCISSOR_TEST);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }
        mEnabled = enabled;
        return true;
    }
    return false;
}

bool Scissor::set(GLint x, GLint y, GLint width, GLint height) {
    if (mEnabled &&
        (x != mScissorX || y != mScissorY || width != mScissorWidth || height != mScissorHeight)) {
        if (x < 0) {
            width += x;
            x = 0;
        }
        if (y < 0) {
            height += y;
            y = 0;
        }
        if (width < 0) {
            width = 0;
        }
        if (height < 0) {
            height = 0;
        }
        glScissor(x, y, width, height);

        mScissorX = x;
        mScissorY = y;
        mScissorWidth = width;
        mScissorHeight = height;

        return true;
    }
    return false;
}

void Scissor::set(int viewportHeight, const Rect& clip) {
    // transform to Y-flipped GL space, and prevent negatives
    GLint x = std::max(0, (int)clip.left);
    GLint y = std::max(0, viewportHeight - (int)clip.bottom);
    GLint width = std::max(0, ((int)clip.right) - x);
    GLint height = std::max(0, (viewportHeight - (int)clip.top) - y);

    if (x != mScissorX || y != mScissorY || width != mScissorWidth || height != mScissorHeight) {
        glScissor(x, y, width, height);

        mScissorX = x;
        mScissorY = y;
        mScissorWidth = width;
        mScissorHeight = height;
    }
}

void Scissor::reset() {
    mScissorX = mScissorY = mScissorWidth = mScissorHeight = 0;
}

void Scissor::invalidate() {
    mEnabled = glIsEnabled(GL_SCISSOR_TEST);
    setEnabled(true);
    reset();
}

void Scissor::dump() {
    ALOGD("Scissor: enabled %d, %d %d %d %d", mEnabled, mScissorX, mScissorY, mScissorWidth,
          mScissorHeight);
}

} /* namespace uirenderer */
} /* namespace android */
