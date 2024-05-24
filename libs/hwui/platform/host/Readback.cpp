/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "Readback.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {

void Readback::copySurfaceInto(ANativeWindow* window, const std::shared_ptr<CopyRequest>& request) {
}

CopyResult Readback::copyHWBitmapInto(Bitmap* hwBitmap, SkBitmap* bitmap) {
    return CopyResult::UnknownError;
}

CopyResult Readback::copyLayerInto(DeferredLayerUpdater* deferredLayer, SkBitmap* bitmap) {
    return CopyResult::UnknownError;
}

CopyResult Readback::copyImageInto(const sk_sp<SkImage>& image, SkBitmap* bitmap) {
    return CopyResult::UnknownError;
}

CopyResult Readback::copyImageInto(const sk_sp<SkImage>& image, const Rect& srcRect,
                                   SkBitmap* bitmap) {
    return CopyResult::UnknownError;
}

bool Readback::copyLayerInto(Layer* layer, const SkRect* srcRect, const SkRect* dstRect,
                             SkBitmap* bitmap) {
    return false;
}

} /* namespace uirenderer */
} /* namespace android */
