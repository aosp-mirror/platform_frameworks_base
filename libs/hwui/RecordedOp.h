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

#include "font/FontUtil.h"
#include "Matrix.h"
#include "Rect.h"
#include "RenderNode.h"
#include "utils/LinearAllocator.h"
#include "Vector.h"

#include "SkXfermode.h"

class SkBitmap;
class SkPaint;

namespace android {
namespace uirenderer {

class OffscreenBuffer;
class RenderNode;
struct Vertex;

/**
 * On of the provided macros is executed for each op type in order. The first will be used for ops
 * that cannot merge, and the second for those that can.
 *
 * This serves as the authoritative list of ops, used for generating ID enum, and ID based LUTs.
 */
#define MAP_OPS_BASED_ON_MERGEABILITY(U_OP_FN, M_OP_FN) \
        U_OP_FN(ArcOp) \
        M_OP_FN(BitmapOp) \
        U_OP_FN(LinesOp) \
        U_OP_FN(OvalOp) \
        U_OP_FN(PathOp) \
        U_OP_FN(PointsOp) \
        U_OP_FN(RectOp) \
        U_OP_FN(RenderNodeOp) \
        U_OP_FN(RoundRectOp) \
        U_OP_FN(ShadowOp) \
        U_OP_FN(SimpleRectsOp) \
        M_OP_FN(TextOp) \
        U_OP_FN(BeginLayerOp) \
        U_OP_FN(EndLayerOp) \
        U_OP_FN(LayerOp)

/**
 * The provided macro is executed for each op type in order. This is used in cases where
 * merge-ability of ops doesn't matter.
 */
#define MAP_OPS(OP_FN) \
        MAP_OPS_BASED_ON_MERGEABILITY(OP_FN, OP_FN)

#define NULL_OP_FN(Type)

#define MAP_MERGED_OPS(OP_FN) \
        MAP_OPS_BASED_ON_MERGEABILITY(NULL_OP_FN, OP_FN)

// Generate OpId enum
#define IDENTITY_FN(Type) Type,
namespace RecordedOpId {
    enum {
        MAP_OPS(IDENTITY_FN)
        Count,
    };
}
static_assert(RecordedOpId::ArcOp == 0,
        "First index must be zero for LUTs to work");

#define BASE_PARAMS const Rect& unmappedBounds, const Matrix4& localMatrix, const Rect& localClipRect, const SkPaint* paint
#define BASE_PARAMS_PAINTLESS const Rect& unmappedBounds, const Matrix4& localMatrix, const Rect& localClipRect
#define SUPER(Type) RecordedOp(RecordedOpId::Type, unmappedBounds, localMatrix, localClipRect, paint)
#define SUPER_PAINTLESS(Type) RecordedOp(RecordedOpId::Type, unmappedBounds, localMatrix, localClipRect, nullptr)

struct RecordedOp {
    /* ID from RecordedOpId - generally used for jumping into function tables */
    const int opId;

    /* bounds in *local* space, without accounting for DisplayList transformation, or stroke */
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
    RenderNode * renderNode; // not const, since drawing modifies it

    /**
     * Holds the transformation between the projection surface ViewGroup and this RenderNode
     * drawing instance. Represents any translations / transformations done within the drawing of
     * the compositing ancestor ViewGroup's draw, before the draw of the View represented by this
     * DisplayList draw instance.
     *
     * Note: doesn't include transformation within the RenderNode, or its properties.
     */
    Matrix4 transformFromCompositingAncestor;
    bool skipInOrderDraw = false;
};

////////////////////////////////////////////////////////////////////////////////////////////////////
// Standard Ops
////////////////////////////////////////////////////////////////////////////////////////////////////

struct ArcOp : RecordedOp {
    ArcOp(BASE_PARAMS, float startAngle, float sweepAngle, bool useCenter)
            : SUPER(ArcOp)
            , startAngle(startAngle)
            , sweepAngle(sweepAngle)
            , useCenter(useCenter) {}
    const float startAngle;
    const float sweepAngle;
    const bool useCenter;
};

struct BitmapOp : RecordedOp {
    BitmapOp(BASE_PARAMS, const SkBitmap* bitmap)
            : SUPER(BitmapOp)
            , bitmap(bitmap) {}
    const SkBitmap* bitmap;
    // TODO: asset atlas/texture id lookup?
};

struct LinesOp : RecordedOp {
    LinesOp(BASE_PARAMS, const float* points, const int floatCount)
            : SUPER(LinesOp)
            , points(points)
            , floatCount(floatCount) {}
    const float* points;
    const int floatCount;
};

struct OvalOp : RecordedOp {
    OvalOp(BASE_PARAMS)
            : SUPER(OvalOp) {}
};

struct PathOp : RecordedOp {
    PathOp(BASE_PARAMS, const SkPath* path)
            : SUPER(PathOp)
            , path(path) {}
    const SkPath* path;
};

struct PointsOp : RecordedOp {
    PointsOp(BASE_PARAMS, const float* points, const int floatCount)
            : SUPER(PointsOp)
            , points(points)
            , floatCount(floatCount) {}
    const float* points;
    const int floatCount;
};

struct RectOp : RecordedOp {
    RectOp(BASE_PARAMS)
            : SUPER(RectOp) {}
};

struct RoundRectOp : RecordedOp {
    RoundRectOp(BASE_PARAMS, float rx, float ry)
            : SUPER(RoundRectOp)
            , rx(rx)
            , ry(ry) {}
    const float rx;
    const float ry;
};

/**
 * Real-time, dynamic-lit shadow.
 *
 * Uses invalid/empty bounds and matrix since ShadowOp bounds aren't known at defer time,
 * and are resolved dynamically, and transform isn't needed.
 *
 * State construction handles these properties specially, ignoring matrix/bounds.
 */
struct ShadowOp : RecordedOp {
    ShadowOp(const RenderNodeOp& casterOp, float casterAlpha, const SkPath* casterPath,
            const Rect& clipRect, const Vector3& lightCenter)
            : RecordedOp(RecordedOpId::ShadowOp, Rect(), Matrix4::identity(), clipRect, nullptr)
            , shadowMatrixXY(casterOp.localMatrix)
            , shadowMatrixZ(casterOp.localMatrix)
            , casterAlpha(casterAlpha)
            , casterPath(casterPath)
            , lightCenter(lightCenter) {
        const RenderNode& node = *casterOp.renderNode;
        node.applyViewPropertyTransforms(shadowMatrixXY, false);
        node.applyViewPropertyTransforms(shadowMatrixZ, true);
    };
    Matrix4 shadowMatrixXY;
    Matrix4 shadowMatrixZ;
    const float casterAlpha;
    const SkPath* casterPath;
    const Vector3 lightCenter;
};

struct SimpleRectsOp : RecordedOp { // Filled, no AA (TODO: better name?)
    SimpleRectsOp(BASE_PARAMS, Vertex* vertices, size_t vertexCount)
            : SUPER(SimpleRectsOp)
            , vertices(vertices)
            , vertexCount(vertexCount) {}
    Vertex* vertices;
    const size_t vertexCount;
};

struct TextOp : RecordedOp {
    TextOp(BASE_PARAMS, const glyph_t* glyphs, const float* positions, int glyphCount,
            float x, float y)
            : SUPER(TextOp)
            , glyphs(glyphs)
            , positions(positions)
            , glyphCount(glyphCount)
            , x(x)
            , y(y) {}
    const glyph_t* glyphs;
    const float* positions;
    const int glyphCount;
    const float x;
    const float y;
};

////////////////////////////////////////////////////////////////////////////////////////////////////
// Layers
////////////////////////////////////////////////////////////////////////////////////////////////////


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

/**
 * Draws an OffscreenBuffer.
 *
 * Alpha, mode, and colorfilter are embedded, since LayerOps are always dynamically generated,
 * when creating/tracking a SkPaint* during defer isn't worth the bother.
 */
struct LayerOp : RecordedOp {
    // Records a one-use (saveLayer) layer for drawing. Once drawn, the layer will be destroyed.
    LayerOp(BASE_PARAMS, OffscreenBuffer** layerHandle)
            : SUPER_PAINTLESS(LayerOp)
            , layerHandle(layerHandle)
            , alpha(paint->getAlpha() / 255.0f)
            , mode(PaintUtils::getXfermodeDirect(paint))
            , colorFilter(paint->getColorFilter())
            , destroy(true) {}

    LayerOp(RenderNode& node)
        : RecordedOp(RecordedOpId::LayerOp, Rect(node.getWidth(), node.getHeight()), Matrix4::identity(), Rect(node.getWidth(), node.getHeight()), nullptr)
        , layerHandle(node.getLayerHandle())
        , alpha(node.properties().layerProperties().alpha() / 255.0f)
        , mode(node.properties().layerProperties().xferMode())
        , colorFilter(node.properties().layerProperties().colorFilter())
        , destroy(false) {}

    // Records a handle to the Layer object, since the Layer itself won't be
    // constructed until after this operation is constructed.
    OffscreenBuffer** layerHandle;
    const float alpha;
    const SkXfermode::Mode mode;

    // pointer to object owned by either LayerProperties, or a recorded Paint object in a
    // BeginLayerOp. Lives longer than LayerOp in either case, so no skia ref counting is used.
    SkColorFilter* colorFilter;

    // whether to destroy the layer, once rendered
    const bool destroy;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RECORDED_OP_H
