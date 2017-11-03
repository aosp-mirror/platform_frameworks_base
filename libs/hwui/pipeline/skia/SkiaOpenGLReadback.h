/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

#include "OpenGLReadback.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

class SkiaOpenGLReadback : public OpenGLReadback {
public:
    SkiaOpenGLReadback(renderthread::RenderThread& thread) : OpenGLReadback(thread) {}

protected:
    virtual CopyResult copyImageInto(EGLImageKHR eglImage, const Matrix4& imgTransform,
                                     int imgWidth, int imgHeight, const Rect& srcRect,
                                     SkBitmap* bitmap) override;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
