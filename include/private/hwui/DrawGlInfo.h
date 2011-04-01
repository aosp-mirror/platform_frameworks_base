/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_HWUI_DRAW_GL_INFO_H
#define ANDROID_HWUI_DRAW_GL_INFO_H

namespace android {
namespace uirenderer {

/**
 * Structure used by OpenGLRenderer::callDrawGLFunction() to pass and
 * receive data from OpenGL functors.
 */
struct DrawGlInfo {
    // Input: current clip rect
    int clipLeft;
    int clipTop;
    int clipRight;
    int clipBottom;

    // Input: is the render target an FBO
    bool isLayer;

    // Input: current transform matrix, in OpenGL format
    float transform[16];

    // Output: dirty region to redraw
    float dirtyLeft;
    float dirtyTop;
    float dirtyRight;
    float dirtyBottom;
}; // struct DrawGlInfo

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DRAW_GL_INFO_H
