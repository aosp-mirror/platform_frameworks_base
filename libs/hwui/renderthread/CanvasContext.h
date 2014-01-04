/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef CANVASCONTEXT_H_
#define CANVASCONTEXT_H_

#include <cutils/compiler.h>
#include <EGL/egl.h>

namespace android {
namespace uirenderer {
namespace renderthread {

class GlobalContext;

// This per-renderer class manages the bridge between the global EGL context
// and the render surface.
class CanvasContext {
public:
    ANDROID_API CanvasContext();
    ANDROID_API ~CanvasContext();

    ANDROID_API bool setSurface(EGLNativeWindowType window);
    ANDROID_API bool swapBuffers();
    ANDROID_API bool makeCurrent();

    ANDROID_API static bool useGlobalPBufferSurface();

private:

    GlobalContext* mGlobalContext;
    EGLSurface mEglSurface;
    bool mDirtyRegionsEnabled;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* CANVASCONTEXT_H_ */
