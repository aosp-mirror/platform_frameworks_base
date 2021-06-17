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

#include <inttypes.h>

namespace android::uirenderer {

enum class CanvasOpType : int8_t {
    // State ops
    // TODO: Eliminate the end ops by having the start include the end-at position
    Save,
    SaveLayer,
    SaveBehind,
    Restore,
    BeginZ,
    EndZ,

    // Clip ops
    ClipRect,
    ClipPath,

    // Drawing ops
    DRAW_OP_BEGIN,
    DrawColor = DRAW_OP_BEGIN,
    DrawRect,
    DrawRegion,
    DrawRoundRect,
    DrawRoundRectProperty,
    DrawDoubleRoundRect,
    DrawCircleProperty,
    DrawRippleDrawable,
    DrawCircle,
    DrawOval,
    DrawArc,
    DrawPaint,
    DrawPoint,
    DrawPoints,
    DrawPath,
    DrawLine,
    DrawLines,
    DrawVertices,
    DrawImage,
    DrawImageRect,
    // DrawImageLattice also used to draw 9 patches
    DrawImageLattice,
    DrawPicture,
    DrawLayer,
    DrawRenderNode,
    DRAW_OP_END = DrawRenderNode,

    // TODO: Rest

    COUNT  // must be last
};

static constexpr bool IsDrawOp(CanvasOpType t) {
    return CanvasOpType::DRAW_OP_BEGIN <= t && t <= CanvasOpType::DRAW_OP_END;
}

}  // namespace android::uirenderer