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

#ifndef ANDROID_HWUI_RECORDED_OP_H
#define ANDROID_HWUI_RECORDED_OP_H

#include "utils/LinearAllocator.h"
#include "Rect.h"
#include "Matrix.h"

#include "SkXfermode.h"

class SkBitmap;
class SkPaint;

namespace android {
namespace uirenderer {

class RenderNode;
struct Vertex;

/**
 * The provided macro is executed for each op type in order, with the results separated by commas.
 *
 * This serves as the authoritative list of ops, used for generating ID enum, and ID based LUTs.
 */
#define MAP_OPS(OP_FN) \
        OP_FN(BitmapOp) \
        OP_FN(RectOp) \
        OP_FN(RenderNodeOp) \
        OP_FN(SimpleRectsOp) \
        OP_FN(BeginLayerOp) \
        OP_FN(EndLayerOp) \
        OP_FN(LayerOp)

// Generate OpId enum
#define IDENTITY_FN(Type) Type,
namespace RecordedOpId {
    enum {
        MAP_OPS(IDENTITY_FN)
        Count,
    };
}
static_assert(RecordedOpId::BitmapOp == 0,
        "First index must be zero for LUTs to work");

#define BASE_PARAMS const Rect& unmappedBounds, const Matrix4& localMatrix, const Rect& localClipRect, const SkPaint* paint
#define BASE_PARAMS_PAINTLESS const Rect& unmappedBounds, const Matrix4& localMatrix, const Rect& localClipRect
#define SUPER(Type) RecordedOp(RecordedOpId::Type, unmappedBounds, localMatrix, localClipRect, paint)
#define SUPER_PAINTLESS(Type) RecordedOp(RecordedOpId::Type, unmappedBounds, localMatrix, localClipRect, nullptr)

struct RecordedOp {
    /* ID from RecordedOpId - generally used for jumping into function tables */
    const int opId;

    /* bounds in *local* space, without accounting for DisplayList transformation */
    const Rect unmappedBounds;

    /* transform in recording space (vs DisplayList origin) */
    const Matrix4 localMatrix;

    /* clip in recording space */
    const Rect localClipRect;

    /* optional paint, stored in base object to simplify merging logic */
    const SkPaint* paint;
protected:
    RecordedOp(unsigned int opId, BASE_PARAMS)
            : opId(opId)
            , unmappedBounds(unmappedBounds)
            , localMatrix(localMatrix)
            , localClipRect(localClipRect)
            , paint(paint) {}
};

struct RenderNodeOp : RecordedOp {
    RenderNodeOp(BASE_PARAMS_PAINTLESS, RenderNode* renderNode)
            : SUPER_PAINTLESS(RenderNodeOp)
            , renderNode(renderNode) {}
    RenderNode * renderNode; // not const, since drawing modifies it (somehow...)
};

struct BitmapOp : RecordedOp {
    BitmapOp(BASE_PARAMS, const SkBitmap* bitmap)
            : SUPER(BitmapOp)
            , bitmap(bitmap) {}
    const SkBitmap* bitmap;
    // TODO: asset atlas/texture id lookup?
};

struct RectOp : RecordedOp {
    RectOp(BASE_PARAMS)
            : SUPER(RectOp) {}
};

struct SimpleRectsOp : RecordedOp { // Filled, no AA (TODO: better name?)
    SimpleRectsOp(BASE_PARAMS, Vertex* vertices, size_t vertexCount)
            : SUPER(SimpleRectsOp)
            , vertices(vertices)
            , vertexCount(vertexCount) {}
    Vertex* vertices;
    const size_t vertexCount;
};

/**
 * Stateful operation! denotes the creation of an off-screen layer,
 * and that commands following will render into it.
 */
struct BeginLayerOp : RecordedOp {
    BeginLayerOp(BASE_PARAMS)
            : SUPER(BeginLayerOp) {}
};

/**
 * Stateful operation! Denotes end of off-screen layer, and that
 * commands since last BeginLayerOp should be drawn into parent FBO.
 *
 * State in this op is empty, it just serves to signal that a layer has been finished.
 */
struct EndLayerOp : RecordedOp {
    EndLayerOp()
            : RecordedOp(RecordedOpId::EndLayerOp, Rect(0, 0), Matrix4::identity(), Rect(0, 0), nullptr) {}
};

struct LayerOp : RecordedOp {
    LayerOp(BASE_PARAMS)
            : SUPER(LayerOp) {}
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RECORDED_OP_H
