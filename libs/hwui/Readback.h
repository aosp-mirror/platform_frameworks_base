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

#include <SkRefCnt.h>

#include "CopyRequest.h"
#include "Matrix.h"
#include "Rect.h"
#include "renderthread/RenderThread.h"

class SkBitmap;
class SkImage;
struct SkRect;

namespace android {
class Bitmap;
class GraphicBuffer;
class Surface;
namespace uirenderer {

class DeferredLayerUpdater;
class Layer;

class Readback {
public:
    explicit Readback(renderthread::RenderThread& thread) : mRenderThread(thread) {}
    /**
     * Copies the surface's most recently queued buffer into the provided bitmap.
     */
    void copySurfaceInto(ANativeWindow* window, const std::shared_ptr<CopyRequest>& request);

    CopyResult copyHWBitmapInto(Bitmap* hwBitmap, SkBitmap* bitmap);
    CopyResult copyImageInto(const sk_sp<SkImage>& image, SkBitmap* bitmap);

    CopyResult copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap);

private:
    CopyResult copyImageInto(const sk_sp<SkImage>& image, const Rect& srcRect, SkBitmap* bitmap);

    bool copyLayerInto(Layer* layer, const SkRect* srcRect, const SkRect* dstRect,
                       SkBitmap* bitmap);

    renderthread::RenderThread& mRenderThread;
};

}  // namespace uirenderer
}  // namespace android
