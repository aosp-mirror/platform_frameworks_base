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
#ifndef RENDERSTATE_SCISSOR_H
#define RENDERSTATE_SCISSOR_H

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

namespace android {
namespace uirenderer {

class Rect;

class Scissor {
    friend class RenderState;

public:
    bool setEnabled(bool enabled);
    bool set(GLint x, GLint y, GLint width, GLint height);
    void set(int viewportHeight, const Rect& clip);
    void reset();
    bool isEnabled() { return mEnabled; }
    void dump();

private:
    Scissor();
    void invalidate();
    bool mEnabled;
    GLint mScissorX;
    GLint mScissorY;
    GLint mScissorWidth;
    GLint mScissorHeight;
};

} /* namespace uirenderer */
} /* namespace android */

#endif  // RENDERSTATE_SCISSOR_H
