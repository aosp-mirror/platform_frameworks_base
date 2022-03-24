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

#include "Matrix.h"
#include "Rect.h"
#include "renderthread/RenderThread.h"

#include <SkBitmap.h>

namespace android {
class Bitmap;
class GraphicBuffer;
class Surface;
namespace uirenderer {

class DeferredLayerUpdater;
class Layer;

// Keep in sync with PixelCopy.java codes
enum class CopyResult {
    Success = 0,
    UnknownError = 1,
    Timeout = 2,
    SourceEmpty = 3,
    SourceInvalid = 4,
    DestinationInvalid = 5,
};

class Readback {
public:
    explicit Readback(renderthread::RenderThread& thread) : mRenderThread(thread) {}
    /**
     * Copies the surface's most recently queued buffer into the provided bitmap.
     */
    CopyResult copySurfaceInto(ANativeWindow* window, const Rect& srcRect, SkBitmap* bitmap);

    CopyResult copyHWBitmapInto(Bitmap* hwBitmap, SkBitmap* bitmap);
    CopyResult copyImageInto(const sk_sp<SkImage>& image, SkBitmap* bitmap);

    CopyResult copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap);

private:
    CopyResult copySurfaceIntoLegacy(ANativeWindow* window, const Rect& srcRect, SkBitmap* bitmap);
    CopyResult copyImageInto(const sk_sp<SkImage>& image, const Rect& srcRect, SkBitmap* bitmap);

    bool copyLayerInto(Layer* layer, const SkRect* srcRect, const SkRect* dstRect,
                       SkBitmap* bitmap);

    renderthread::RenderThread& mRenderThread;
};

}  // namespace uirenderer
}  // namespace android
