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

#pragma once

#include "GlLayer.h"
#include "Matrix.h"
#include "Rect.h"
#include "RenderNode.h"
#include "TessellationCache.h"
#include "Vector.h"
#include "font/FontUtil.h"
#include "utils/LinearAllocator.h"
#include "utils/PaintUtils.h"

#include <androidfw/ResourceTypes.h>

class SkBitmap;
class SkPaint;

namespace android {
namespace uirenderer {

struct ClipBase;
class OffscreenBuffer;
class RenderNode;
class DeferredLayerUpdater;

struct Vertex;

namespace VectorDrawable {
class Tree;
}

/**
 * Authoritative op list, used for generating the op ID enum, ID based LUTS, and
 * the functions to which they dispatch. Parameter macros are executed for each op,
 * in order, based on the op's type.
 *
 * There are 4 types of op, which defines dispatch/LUT capability:
 *
 *              | DisplayList |   Render    |    Merge    |
 * -------------|-------------|-------------|-------------|
 * PRE RENDER   |     Yes     |             |             |
 * RENDER ONLY  |             |     Yes     |             |
 * UNMERGEABLE  |     Yes     |     Yes     |             |
 * MERGEABLE    |     Yes     |     Yes     |     Yes     |
 *
 * PRE RENDER - These ops are recorded into DisplayLists, but can't be directly rendered. This
 *      may be because they need to be transformed into other op types (e.g. CirclePropsOp),
 *      be traversed to access multiple renderable ops within (e.g. RenderNodeOp), or because they
 *      modify renderbuffer lifecycle, instead of directly rendering content (the various LayerOps).
 *
 * RENDER ONLY - These ops cannot be recorded into DisplayLists, and are instead implicitly
 *      constructed from other commands/RenderNode properties. They cannot be merged.
 *
 * UNMERGEABLE - These ops can be recorded into DisplayLists and rendered directly, but do not
 *      support merged rendering.
 *
 * MERGEABLE - These ops can be recorded into DisplayLists and rendered individually, or merged
 *      under certain circumstances.
 */
#define MAP_OPS_BASED_ON_TYPE(PRE_RENDER_OP_FN, RENDER_ONLY_OP_FN, UNMERGEABLE_OP_FN, \
                              MERGEABLE_OP_FN)                                        \
    PRE_RENDER_OP_FN(RenderNodeOp)                                                    \
    PRE_RENDER_OP_FN(CirclePropsOp)                                                   \
    PRE_RENDER_OP_FN(RoundRectPropsOp)                                                \
    PRE_RENDER_OP_FN(BeginLayerOp)                                                    \
    PRE_RENDER_OP_FN(EndLayerOp)                                                      \
    PRE_RENDER_OP_FN(BeginUnclippedLayerOp)                                           \
    PRE_RENDER_OP_FN(EndUnclippedLayerOp)                                             \
    PRE_RENDER_OP_FN(VectorDrawableOp)                                                \
                                                                                      \
    RENDER_ONLY_OP_FN(ShadowOp)                                                       \
    RENDER_ONLY_OP_FN(LayerOp)                                                        \
    RENDER_ONLY_OP_FN(CopyToLayerOp)                                                  \
    RENDER_ONLY_OP_FN(CopyFromLayerOp)                                                \
                                                                                      \
    UNMERGEABLE_OP_FN(ArcOp)                                                          \
    UNMERGEABLE_OP_FN(BitmapMeshOp)                                                   \
    UNMERGEABLE_OP_FN(BitmapRectOp)                                                   \
    UNMERGEABLE_OP_FN(ColorOp)                                                        \
    UNMERGEABLE_OP_FN(FunctorOp)                                                      \
    UNMERGEABLE_OP_FN(LinesOp)                                                        \
    UNMERGEABLE_OP_FN(OvalOp)                                                         \
    UNMERGEABLE_OP_FN(PathOp)                                                         \
    UNMERGEABLE_OP_FN(PointsOp)                                                       \
    UNMERGEABLE_OP_FN(RectOp)                                                         \
    UNMERGEABLE_OP_FN(RoundRectOp)                                                    \
    UNMERGEABLE_OP_FN(SimpleRectsOp)                                                  \
    UNMERGEABLE_OP_FN(TextOnPathOp)                                                   \
    UNMERGEABLE_OP_FN(TextureLayerOp)                                                 \
                                                                                      \
    MERGEABLE_OP_FN(BitmapOp)                                                         \
    MERGEABLE_OP_FN(PatchOp)                                                          \
    MERGEABLE_OP_FN(TextOp)

/**
 * LUT generators, which will insert nullptr for unsupported ops
 */
#define NULLPTR_OP_FN(Type) nullptr,

#define BUILD_DEFERRABLE_OP_LUT(OP_FN) \
    { MAP_OPS_BASED_ON_TYPE(OP_FN, NULLPTR_OP_FN, OP_FN, OP_FN) }

#define BUILD_MERGEABLE_OP_LUT(OP_FN) \
    { MAP_OPS_BASED_ON_TYPE(NULLPTR_OP_FN, NULLPTR_OP_FN, NULLPTR_OP_FN, OP_FN) }

#define BUILD_RENDERABLE_OP_LUT(OP_FN) \
    { MAP_OPS_BASED_ON_TYPE(NULLPTR_OP_FN, OP_FN, OP_FN, OP_FN) }

#define BUILD_FULL_OP_LUT(OP_FN) \
    { MAP_OPS_BASED_ON_TYPE(OP_FN, OP_FN, OP_FN, OP_FN) }

/**
 * Op mapping functions, which skip unsupported ops.
 *
 * Note: Do not use for LUTS, since these do not preserve ID order.
 */
#define NULL_OP_FN(Type)

#define MAP_DEFERRABLE_OPS(OP_FN) MAP_OPS_BASED_ON_TYPE(OP_FN, NULL_OP_FN, OP_FN, OP_FN)

#define MAP_MERGEABLE_OPS(OP_FN) MAP_OPS_BASED_ON_TYPE(NULL_OP_FN, NULL_OP_FN, NULL_OP_FN, OP_FN)

#define MAP_RENDERABLE_OPS(OP_FN) MAP_OPS_BASED_ON_TYPE(NULL_OP_FN, OP_FN, OP_FN, OP_FN)

// Generate OpId enum
#define IDENTITY_FN(Type) Type,
namespace RecordedOpId {
enum {
    MAP_OPS_BASED_ON_TYPE(IDENTITY_FN, IDENTITY_FN, IDENTITY_FN, IDENTITY_FN) Count,
};
}
static_assert(RecordedOpId::RenderNodeOp == 0, "First index must be zero for LUTs to work");

#define BASE_PARAMS                                                                    \
    const Rect &unmappedBounds, const Matrix4 &localMatrix, const ClipBase *localClip, \
            const SkPaint *paint
#define BASE_PARAMS_PAINTLESS \
    const Rect &unmappedBounds, const Matrix4 &localMatrix, const ClipBase *localClip
#define SUPER(Type) RecordedOp(RecordedOpId::Type, unmappedBounds, localMatrix, localClip, paint)
#define SUPER_PAINTLESS(Type) \
    RecordedOp(RecordedOpId::Type, unmappedBounds, localMatrix, localClip, nullptr)

struct RecordedOp {
    /* ID from RecordedOpId - generally used for jumping into function tables */
    const int opId;

    /* bounds in *local* space, without accounting for DisplayList transformation, or stroke */
    const Rect unmappedBounds;

    /* transform in recording space (vs DisplayList origin) */
    const Matrix4 localMatrix;

    /* clip in recording space - nullptr if not clipped */
    const ClipBase* localClip;

    /* optional paint, stored in base object to simplify merging logic */
    const SkPaint* paint;

protected:
    RecordedOp(unsigned int opId, BASE_PARAMS)
            : opId(opId)
            , unmappedBounds(unmappedBounds)
            , localMatrix(localMatrix)
            , localClip(localClip)
            , paint(paint) {}
};

struct RenderNodeOp : RecordedOp {
    RenderNodeOp(BASE_PARAMS_PAINTLESS, RenderNode* renderNode)
            : SUPER_PAINTLESS(RenderNodeOp), renderNode(renderNode) {}
    RenderNode* renderNode;  // not const, since drawing modifies it

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
            : SUPER(ArcOp), startAngle(startAngle), sweepAngle(sweepAngle), useCenter(useCenter) {}
    const float startAngle;
    const float sweepAngle;
    const bool useCenter;
};

struct BitmapOp : RecordedOp {
    BitmapOp(BASE_PARAMS, Bitmap* bitmap) : SUPER(BitmapOp), bitmap(bitmap) {}
    Bitmap* bitmap;
};

struct BitmapMeshOp : RecordedOp {
    BitmapMeshOp(BASE_PARAMS, Bitmap* bitmap, int meshWidth, int meshHeight, const float* vertices,
                 const int* colors)
            : SUPER(BitmapMeshOp)
            , bitmap(bitmap)
            , meshWidth(meshWidth)
            , meshHeight(meshHeight)
            , vertices(vertices)
            , colors(colors) {}
    Bitmap* bitmap;
    const int meshWidth;
    const int meshHeight;
    const float* vertices;
    const int* colors;
};

struct BitmapRectOp : RecordedOp {
    BitmapRectOp(BASE_PARAMS, Bitmap* bitmap, const Rect& src)
            : SUPER(BitmapRectOp), bitmap(bitmap), src(src) {}
    Bitmap* bitmap;
    const Rect src;
};

struct CirclePropsOp : RecordedOp {
    CirclePropsOp(const Matrix4& localMatrix, const ClipBase* localClip, const SkPaint* paint,
                  float* x, float* y, float* radius)
            : RecordedOp(RecordedOpId::CirclePropsOp, Rect(), localMatrix, localClip, paint)
            , x(x)
            , y(y)
            , radius(radius) {}
    const float* x;
    const float* y;
    const float* radius;
};

struct ColorOp : RecordedOp {
    // Note: unbounded op that will fillclip, so no bounds/matrix needed
    ColorOp(const ClipBase* localClip, int color, SkBlendMode mode)
            : RecordedOp(RecordedOpId::ColorOp, Rect(), Matrix4::identity(), localClip, nullptr)
            , color(color)
            , mode(mode) {}
    const int color;
    const SkBlendMode mode;
};

struct FunctorOp : RecordedOp {
    // Note: undefined record-time bounds, since this op fills the clip
    // TODO: explicitly define bounds
    FunctorOp(const Matrix4& localMatrix, const ClipBase* localClip, Functor* functor)
            : RecordedOp(RecordedOpId::FunctorOp, Rect(), localMatrix, localClip, nullptr)
            , functor(functor) {}
    Functor* functor;
};

struct LinesOp : RecordedOp {
    LinesOp(BASE_PARAMS, const float* points, const int floatCount)
            : SUPER(LinesOp), points(points), floatCount(floatCount) {}
    const float* points;
    const int floatCount;
};

struct OvalOp : RecordedOp {
    OvalOp(BASE_PARAMS) : SUPER(OvalOp) {}
};

struct PatchOp : RecordedOp {
    PatchOp(BASE_PARAMS, Bitmap* bitmap, const Res_png_9patch* patch)
            : SUPER(PatchOp), bitmap(bitmap), patch(patch) {}
    Bitmap* bitmap;
    const Res_png_9patch* patch;
};

struct PathOp : RecordedOp {
    PathOp(BASE_PARAMS, const SkPath* path) : SUPER(PathOp), path(path) {}
    const SkPath* path;
};

struct PointsOp : RecordedOp {
    PointsOp(BASE_PARAMS, const float* points, const int floatCount)
            : SUPER(PointsOp), points(points), floatCount(floatCount) {}
    const float* points;
    const int floatCount;
};

struct RectOp : RecordedOp {
    RectOp(BASE_PARAMS) : SUPER(RectOp) {}
};

struct RoundRectOp : RecordedOp {
    RoundRectOp(BASE_PARAMS, float rx, float ry) : SUPER(RoundRectOp), rx(rx), ry(ry) {}
    const float rx;
    const float ry;
};

struct RoundRectPropsOp : RecordedOp {
    RoundRectPropsOp(const Matrix4& localMatrix, const ClipBase* localClip, const SkPaint* paint,
                     float* left, float* top, float* right, float* bottom, float* rx, float* ry)
            : RecordedOp(RecordedOpId::RoundRectPropsOp, Rect(), localMatrix, localClip, paint)
            , left(left)
            , top(top)
            , right(right)
            , bottom(bottom)
            , rx(rx)
            , ry(ry) {}
    const float* left;
    const float* top;
    const float* right;
    const float* bottom;
    const float* rx;
    const float* ry;
};

struct VectorDrawableOp : RecordedOp {
    VectorDrawableOp(VectorDrawable::Tree* tree, BASE_PARAMS_PAINTLESS)
            : SUPER_PAINTLESS(VectorDrawableOp), vectorDrawable(tree) {}
    VectorDrawable::Tree* vectorDrawable;
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
    ShadowOp(sp<TessellationCache::ShadowTask>& shadowTask, float casterAlpha)
            : RecordedOp(RecordedOpId::ShadowOp, Rect(), Matrix4::identity(), nullptr, nullptr)
            , shadowTask(shadowTask)
            , casterAlpha(casterAlpha){};
    sp<TessellationCache::ShadowTask> shadowTask;
    const float casterAlpha;
};

struct SimpleRectsOp : RecordedOp {  // Filled, no AA (TODO: better name?)
    SimpleRectsOp(BASE_PARAMS, Vertex* vertices, size_t vertexCount)
            : SUPER(SimpleRectsOp), vertices(vertices), vertexCount(vertexCount) {}
    Vertex* vertices;
    const size_t vertexCount;
};

struct TextOp : RecordedOp {
    TextOp(BASE_PARAMS, const glyph_t* glyphs, const float* positions, int glyphCount, float x,
           float y)
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

struct TextOnPathOp : RecordedOp {
    // TODO: explicitly define bounds
    TextOnPathOp(const Matrix4& localMatrix, const ClipBase* localClip, const SkPaint* paint,
                 const glyph_t* glyphs, int glyphCount, const SkPath* path, float hOffset,
                 float vOffset)
            : RecordedOp(RecordedOpId::TextOnPathOp, Rect(), localMatrix, localClip, paint)
            , glyphs(glyphs)
            , glyphCount(glyphCount)
            , path(path)
            , hOffset(hOffset)
            , vOffset(vOffset) {}
    const glyph_t* glyphs;
    const int glyphCount;

    const SkPath* path;
    const float hOffset;
    const float vOffset;
};

struct TextureLayerOp : RecordedOp {
    TextureLayerOp(BASE_PARAMS_PAINTLESS, DeferredLayerUpdater* layer)
            : SUPER_PAINTLESS(TextureLayerOp), layerHandle(layer) {}

    // Copy an existing TextureLayerOp, replacing the underlying matrix
    TextureLayerOp(const TextureLayerOp& op, const Matrix4& replacementMatrix)
            : RecordedOp(RecordedOpId::TextureLayerOp, op.unmappedBounds, replacementMatrix,
                         op.localClip, op.paint)
            , layerHandle(op.layerHandle) {}
    DeferredLayerUpdater* layerHandle;
};

////////////////////////////////////////////////////////////////////////////////////////////////////
// Layers
////////////////////////////////////////////////////////////////////////////////////////////////////

/**
 * Stateful operation! denotes the creation of an off-screen layer,
 * and that commands following will render into it.
 */
struct BeginLayerOp : RecordedOp {
    BeginLayerOp(BASE_PARAMS) : SUPER(BeginLayerOp) {}
};

/**
 * Stateful operation! Denotes end of off-screen layer, and that
 * commands since last BeginLayerOp should be drawn into parent FBO.
 *
 * State in this op is empty, it just serves to signal that a layer has been finished.
 */
struct EndLayerOp : RecordedOp {
    EndLayerOp()
            : RecordedOp(RecordedOpId::EndLayerOp, Rect(), Matrix4::identity(), nullptr, nullptr) {}
};

struct BeginUnclippedLayerOp : RecordedOp {
    BeginUnclippedLayerOp(BASE_PARAMS) : SUPER(BeginUnclippedLayerOp) {}
};

struct EndUnclippedLayerOp : RecordedOp {
    EndUnclippedLayerOp()
            : RecordedOp(RecordedOpId::EndUnclippedLayerOp, Rect(), Matrix4::identity(), nullptr,
                         nullptr) {}
};

struct CopyToLayerOp : RecordedOp {
    CopyToLayerOp(const RecordedOp& op, OffscreenBuffer** layerHandle)
            : RecordedOp(RecordedOpId::CopyToLayerOp, op.unmappedBounds, op.localMatrix,
                         nullptr,  // clip intentionally ignored
                         op.paint)
            , layerHandle(layerHandle) {}

    // Records a handle to the Layer object, since the Layer itself won't be
    // constructed until after this operation is constructed.
    OffscreenBuffer** layerHandle;
};

// draw the parameter layer underneath
struct CopyFromLayerOp : RecordedOp {
    CopyFromLayerOp(const RecordedOp& op, OffscreenBuffer** layerHandle)
            : RecordedOp(RecordedOpId::CopyFromLayerOp, op.unmappedBounds, op.localMatrix,
                         nullptr,  // clip intentionally ignored
                         op.paint)
            , layerHandle(layerHandle) {}

    // Records a handle to the Layer object, since the Layer itself won't be
    // constructed until after this operation is constructed.
    OffscreenBuffer** layerHandle;
};

/**
 * Draws an OffscreenBuffer.
 *
 * Alpha, mode, and colorfilter are embedded, since LayerOps are always dynamically generated,
 * when creating/tracking a SkPaint* during defer isn't worth the bother.
 */
struct LayerOp : RecordedOp {
    // Records a one-use (saveLayer) layer for drawing.
    LayerOp(BASE_PARAMS, OffscreenBuffer** layerHandle)
            : SUPER_PAINTLESS(LayerOp)
            , layerHandle(layerHandle)
            , alpha(paint ? paint->getAlpha() / 255.0f : 1.0f)
            , mode(PaintUtils::getBlendModeDirect(paint))
            , colorFilter(paint ? paint->getColorFilter() : nullptr) {}

    explicit LayerOp(RenderNode& node)
            : RecordedOp(RecordedOpId::LayerOp, Rect(node.getWidth(), node.getHeight()),
                         Matrix4::identity(), nullptr, nullptr)
            , layerHandle(node.getLayerHandle())
            , alpha(node.properties().layerProperties().alpha() / 255.0f)
            , mode(node.properties().layerProperties().xferMode())
            , colorFilter(node.properties().layerProperties().colorFilter()) {}

    // Records a handle to the Layer object, since the Layer itself won't be
    // constructed until after this operation is constructed.
    OffscreenBuffer** layerHandle;
    const float alpha;
    const SkBlendMode mode;

    // pointer to object owned by either LayerProperties, or a recorded Paint object in a
    // BeginLayerOp. Lives longer than LayerOp in either case, so no skia ref counting is used.
    SkColorFilter* colorFilter;
};

};  // namespace uirenderer
};  // namespace android
