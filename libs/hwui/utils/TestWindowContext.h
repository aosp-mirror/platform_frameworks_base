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
#ifndef TESTWINDOWCONTEXT_H_
#define TESTWINDOWCONTEXT_H_

#include <cutils/compiler.h>

class SkBitmap;
class SkCanvas;

namespace android {

namespace uirenderer {

/**
  Wraps all libui/libgui classes and types that external tests depend on,
  exposing only primitive Skia types.
*/

class ANDROID_API TestWindowContext {

public:

    TestWindowContext();
    ~TestWindowContext();

    /// We need to know the size of the window.
    void initialize(int width, int height);

    /// Returns a canvas to draw into; NULL if not yet initialize()d.
    SkCanvas* prepareToDraw();

    /// Flushes all drawing commands to HWUI; no-op if not yet initialize()d.
    void finishDrawing();

    /// Blocks until HWUI has processed all pending drawing commands;
    /// no-op if not yet initialize()d.
    void fence();

    /// Returns false if not yet initialize()d.
    bool capturePixels(SkBitmap* bmp);

private:
    /// Hidden implementation.
    class TestWindowData;

    TestWindowData* mData;

};

}  // namespace uirenderer
}  // namespace android

#endif  // TESTWINDOWCONTEXT_H_

