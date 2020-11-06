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

#include <SkAndroidFrameworkUtils.h>
#include <SkCanvas.h>
#include <SkPath.h>
#include <log/log.h>

#include "CanvasOpTypes.h"

#include <experimental/type_traits>

namespace android::uirenderer {

template <CanvasOpType T>
struct CanvasOp;

struct CanvasOpTraits {
    CanvasOpTraits() = delete;

    template<class T>
    using draw_t = decltype(std::integral_constant<void (T::*)(SkCanvas*) const, &T::draw>{});

    template <class T>
    static constexpr bool can_draw = std::experimental::is_detected_v<draw_t, T>;
};

#define ASSERT_DRAWABLE() private: constexpr void _check_drawable() \
    { static_assert(CanvasOpTraits::can_draw<std::decay_t<decltype(*this)>>); }

// ----------------------------------------------
//   State Ops
//  ---------------------------------------------

template <>
struct CanvasOp<CanvasOpType::Save> {
    void draw(SkCanvas* canvas) const { canvas->save(); }
    ASSERT_DRAWABLE()
};

template <>
struct CanvasOp<CanvasOpType::SaveLayer> {
    SkCanvas::SaveLayerRec saveLayerRec;
    void draw(SkCanvas* canvas) const { canvas->saveLayer(saveLayerRec); }
    ASSERT_DRAWABLE()
};

template <>
struct CanvasOp<CanvasOpType::SaveBehind> {
    SkRect bounds;
    void draw(SkCanvas* canvas) const { SkAndroidFrameworkUtils::SaveBehind(canvas, &bounds); }
    ASSERT_DRAWABLE()
};

template <>
struct CanvasOp<CanvasOpType::Restore> {
    void draw(SkCanvas* canvas) const { canvas->restore(); }
    ASSERT_DRAWABLE()
};

template <>
struct CanvasOp<CanvasOpType::BeginZ> {
};
template <>
struct CanvasOp<CanvasOpType::EndZ> {};

// ----------------------------------------------
//   Clip Ops
//  ---------------------------------------------

template <>
struct CanvasOp<CanvasOpType::ClipRect> {
    SkRect rect;
    SkClipOp clipOp;
    void draw(SkCanvas* canvas) const { canvas->clipRect(rect, clipOp); }
    ASSERT_DRAWABLE()
};

template <>
struct CanvasOp<CanvasOpType::ClipPath> {
    SkPath path;
    SkClipOp op;
    void draw(SkCanvas* canvas) const { canvas->clipPath(path, op, true); }
    ASSERT_DRAWABLE()
};

// ----------------------------------------------
//   Drawing Ops
//  ---------------------------------------------

template <>
struct CanvasOp<CanvasOpType::DrawColor> {
    SkColor4f color;
    SkBlendMode mode;
    void draw(SkCanvas* canvas) const { canvas->drawColor(color, mode); }
    ASSERT_DRAWABLE()
};

template <>
struct CanvasOp<CanvasOpType::DrawRect> {
    SkRect rect;
    SkPaint paint;
    void draw(SkCanvas* canvas) const { canvas->drawRect(rect, paint); }
    ASSERT_DRAWABLE()
};


// cleanup our macros
#undef ASSERT_DRAWABLE

}  // namespace android::uirenderer