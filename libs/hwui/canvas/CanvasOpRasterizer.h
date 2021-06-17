/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <hwui/Bitmap.h>

#include <SkBitmap.h>
#include <SkCanvas.h>

#include "CanvasOps.h"

#include <experimental/type_traits>
#include <variant>

namespace android::uirenderer {

class CanvasOpBuffer;

void rasterizeCanvasBuffer(const CanvasOpBuffer& source, SkCanvas* destination);

class ImmediateModeRasterizer {
public:
    explicit ImmediateModeRasterizer(std::unique_ptr<SkCanvas>&& canvas) {
        mCanvas = canvas.get();
        mOwnership = std::move(canvas);
    }

    explicit ImmediateModeRasterizer(std::shared_ptr<SkCanvas> canvas) {
        mCanvas = canvas.get();
        mOwnership = std::move(canvas);
    }

    explicit ImmediateModeRasterizer(Bitmap& bitmap) {
        mCanvas = &(mOwnership.emplace<SkCanvas>(bitmap.getSkBitmap()));
    }

    template <CanvasOpType T>
    void draw(const CanvasOp<T>& op) {
        if constexpr (CanvasOpTraits::can_draw<CanvasOp<T>>) {
            op.draw(mCanvas);
        }
    }

private:
    SkCanvas* mCanvas;
    // Just here to keep mCanvas alive. Thankfully we never need to actually look inside this...
    std::variant<SkCanvas, std::shared_ptr<SkCanvas>, std::unique_ptr<SkCanvas>> mOwnership;
};

}  // namespace android::uirenderer
