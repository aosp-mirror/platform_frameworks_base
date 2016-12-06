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

#include "RecordingCanvas.h"

#include "DeferredLayerUpdater.h"
#include "RecordedOp.h"
#include "RenderNode.h"
#include "VectorDrawable.h"

namespace android {
namespace uirenderer {

RecordingCanvas::RecordingCanvas(size_t width, size_t height)
        : mState(*this)
        , mResourceCache(ResourceCache::getInstance()) {
    resetRecording(width, height);
}

RecordingCanvas::~RecordingCanvas() {
    LOG_ALWAYS_FATAL_IF(mDisplayList,
            "Destroyed a RecordingCanvas during a record!");
}

void RecordingCanvas::resetRecording(int width, int height) {
    LOG_ALWAYS_FATAL_IF(mDisplayList,
            "prepareDirty called a second time during a recording!");
    mDisplayList = new DisplayList();

    mState.initializeRecordingSaveStack(width, height);

    mDeferredBarrierType = DeferredBarrierType::InOrder;
    mState.setDirtyClip(false);
}

DisplayList* RecordingCanvas::finishRecording() {
    restoreToCount(1);
    mPaintMap.clear();
    mRegionMap.clear();
    mPathMap.clear();
    DisplayList* displayList = mDisplayList;
    mDisplayList = nullptr;
    mSkiaCanvasProxy.reset(nullptr);
    return displayList;
}

void RecordingCanvas::insertReorderBarrier(bool enableReorder) {
    if (enableReorder) {
        mDeferredBarrierType = DeferredBarrierType::OutOfOrder;
        mDeferredBarrierClip = getRecordedClip();
    } else {
        mDeferredBarrierType = DeferredBarrierType::InOrder;
        mDeferredBarrierClip = nullptr;
    }
}

SkCanvas* RecordingCanvas::asSkCanvas() {
    LOG_ALWAYS_FATAL_IF(!mDisplayList,
            "attempting to get an SkCanvas when we are not recording!");
    if (!mSkiaCanvasProxy) {
        mSkiaCanvasProxy.reset(new SkiaCanvasProxy(this));
    }

    // SkCanvas instances default to identity transform, but should inherit
    // the state of this Canvas; if this code was in the SkiaCanvasProxy
    // constructor, we couldn't cache mSkiaCanvasProxy.
    SkMatrix parentTransform;
    getMatrix(&parentTransform);
    mSkiaCanvasProxy.get()->setMatrix(parentTransform);

    return mSkiaCanvasProxy.get();
}

// ----------------------------------------------------------------------------
// CanvasStateClient implementation
// ----------------------------------------------------------------------------

void RecordingCanvas::onViewportInitialized() {
}

void RecordingCanvas::onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {
    if (removed.flags & Snapshot::kFlagIsFboLayer) {
        addOp(alloc().create_trivial<EndLayerOp>());
    } else if (removed.flags & Snapshot::kFlagIsLayer) {
        addOp(alloc().create_trivial<EndUnclippedLayerOp>());
    }
}

// ----------------------------------------------------------------------------
// android/graphics/Canvas state operations
// ----------------------------------------------------------------------------
// Save (layer)
int RecordingCanvas::save(SaveFlags::Flags flags) {
    return mState.save((int) flags);
}

void RecordingCanvas::RecordingCanvas::restore() {
    mState.restore();
}

void RecordingCanvas::restoreToCount(int saveCount) {
    mState.restoreToCount(saveCount);
}

int RecordingCanvas::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* paint, SaveFlags::Flags flags) {
    // force matrix/clip isolation for layer
    flags |= SaveFlags::MatrixClip;
    bool clippedLayer = flags & SaveFlags::ClipToLayer;

    const Snapshot& previous = *mState.currentSnapshot();

    // initialize the snapshot as though it almost represents an FBO layer so deferred draw
    // operations will be able to store and restore the current clip and transform info, and
    // quick rejection will be correct (for display lists)

    Rect unmappedBounds(left, top, right, bottom);
    unmappedBounds.roundOut();

    // determine clipped bounds relative to previous viewport.
    Rect visibleBounds = unmappedBounds;
    previous.transform->mapRect(visibleBounds);

    if (CC_UNLIKELY(!clippedLayer
            && previous.transform->rectToRect()
            && visibleBounds.contains(previous.getRenderTargetClip()))) {
        // unlikely case where an unclipped savelayer is recorded with a clip it can use,
        // as none of its unaffected/unclipped area is visible
        clippedLayer = true;
        flags |= SaveFlags::ClipToLayer;
    }

    visibleBounds.doIntersect(previous.getRenderTargetClip());
    visibleBounds.snapToPixelBoundaries();
    visibleBounds.doIntersect(Rect(previous.getViewportWidth(), previous.getViewportHeight()));

    // Map visible bounds back to layer space, and intersect with parameter bounds
    Rect layerBounds = visibleBounds;
    if (CC_LIKELY(!layerBounds.isEmpty())) {
        // if non-empty, can safely map by the inverse transform
        Matrix4 inverse;
        inverse.loadInverse(*previous.transform);
        inverse.mapRect(layerBounds);
        layerBounds.doIntersect(unmappedBounds);
    }

    int saveValue = mState.save((int) flags);
    Snapshot& snapshot = *mState.writableSnapshot();

    // layerBounds is in original bounds space, but clipped by current recording clip
    if (!layerBounds.isEmpty() && !unmappedBounds.isEmpty()) {
        if (CC_LIKELY(clippedLayer)) {
            auto previousClip = getRecordedClip(); // capture before new snapshot clip has changed
            if (addOp(alloc().create_trivial<BeginLayerOp>(
                    unmappedBounds,
                    *previous.transform, // transform to *draw* with
                    previousClip, // clip to *draw* with
                    refPaint(paint))) >= 0) {
                snapshot.flags |= Snapshot::kFlagIsLayer | Snapshot::kFlagIsFboLayer;
                snapshot.initializeViewport(unmappedBounds.getWidth(), unmappedBounds.getHeight());
                snapshot.transform->loadTranslate(-unmappedBounds.left, -unmappedBounds.top, 0.0f);

                Rect clip = layerBounds;
                clip.translate(-unmappedBounds.left, -unmappedBounds.top);
                snapshot.resetClip(clip.left, clip.top, clip.right, clip.bottom);
                snapshot.roundRectClipState = nullptr;
                return saveValue;
            }
        } else {
            if (addOp(alloc().create_trivial<BeginUnclippedLayerOp>(
                    unmappedBounds,
                    *mState.currentSnapshot()->transform,
                    getRecordedClip(),
                    refPaint(paint))) >= 0) {
                snapshot.flags |= Snapshot::kFlagIsLayer;
                return saveValue;
            }
        }
    }

    // Layer not needed, so skip recording it...
    if (CC_LIKELY(clippedLayer)) {
        // ... and set empty clip to reject inner content, if possible
        snapshot.resetClip(0, 0, 0, 0);
    }
    return saveValue;
}

// Matrix
void RecordingCanvas::rotate(float degrees) {
    if (degrees == 0) return;

    mState.rotate(degrees);
}

void RecordingCanvas::scale(float sx, float sy) {
    if (sx == 1 && sy == 1) return;

    mState.scale(sx, sy);
}

void RecordingCanvas::skew(float sx, float sy) {
    mState.skew(sx, sy);
}

void RecordingCanvas::translate(float dx, float dy) {
    if (dx == 0 && dy == 0) return;

    mState.translate(dx, dy, 0);
}

// Clip
bool RecordingCanvas::getClipBounds(SkRect* outRect) const {
    *outRect = mState.getLocalClipBounds().toSkRect();
    return !(outRect->isEmpty());
}
bool RecordingCanvas::quickRejectRect(float left, float top, float right, float bottom) const {
    return mState.quickRejectConservative(left, top, right, bottom);
}
bool RecordingCanvas::quickRejectPath(const SkPath& path) const {
    SkRect bounds = path.getBounds();
    return mState.quickRejectConservative(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom);
}
bool RecordingCanvas::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    return mState.clipRect(left, top, right, bottom, op);
}
bool RecordingCanvas::clipPath(const SkPath* path, SkRegion::Op op) {
    return mState.clipPath(path, op);
}
bool RecordingCanvas::clipRegion(const SkRegion* region, SkRegion::Op op) {
    return mState.clipRegion(region, op);
}

// ----------------------------------------------------------------------------
// android/graphics/Canvas draw operations
// ----------------------------------------------------------------------------
void RecordingCanvas::drawColor(int color, SkXfermode::Mode mode) {
    addOp(alloc().create_trivial<ColorOp>(
            getRecordedClip(),
            color,
            mode));
}

void RecordingCanvas::drawPaint(const SkPaint& paint) {
    SkRect bounds;
    if (getClipBounds(&bounds)) {
        drawRect(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, paint);
    }
}

static Rect calcBoundsOfPoints(const float* points, int floatCount) {
    Rect unmappedBounds(points[0], points[1], points[0], points[1]);
    for (int i = 2; i < floatCount; i += 2) {
        unmappedBounds.expandToCover(points[i], points[i + 1]);
    }
    return unmappedBounds;
}

// Geometry
void RecordingCanvas::drawPoints(const float* points, int floatCount, const SkPaint& paint) {
    if (CC_UNLIKELY(floatCount < 2 || PaintUtils::paintWillNotDraw(paint))) return;
    floatCount &= ~0x1; // round down to nearest two

    addOp(alloc().create_trivial<PointsOp>(
            calcBoundsOfPoints(points, floatCount),
            *mState.currentSnapshot()->transform,
            getRecordedClip(),
            refPaint(&paint), refBuffer<float>(points, floatCount), floatCount));
}

void RecordingCanvas::drawLines(const float* points, int floatCount, const SkPaint& paint) {
    if (CC_UNLIKELY(floatCount < 4 || PaintUtils::paintWillNotDraw(paint))) return;
    floatCount &= ~0x3; // round down to nearest four

    addOp(alloc().create_trivial<LinesOp>(
            calcBoundsOfPoints(points, floatCount),
            *mState.currentSnapshot()->transform,
            getRecordedClip(),
            refPaint(&paint), refBuffer<float>(points, floatCount), floatCount));
}

void RecordingCanvas::drawRect(float left, float top, float right, float bottom, const SkPaint& paint) {
    if (CC_UNLIKELY(PaintUtils::paintWillNotDraw(paint))) return;

    addOp(alloc().create_trivial<RectOp>(
            Rect(left, top, right, bottom),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(&paint)));
}

void RecordingCanvas::drawSimpleRects(const float* rects, int vertexCount, const SkPaint* paint) {
    if (rects == nullptr) return;

    Vertex* rectData = (Vertex*) mDisplayList->allocator.create_trivial_array<Vertex>(vertexCount);
    Vertex* vertex = rectData;

    float left = FLT_MAX;
    float top = FLT_MAX;
    float right = FLT_MIN;
    float bottom = FLT_MIN;
    for (int index = 0; index < vertexCount; index += 4) {
        float l = rects[index + 0];
        float t = rects[index + 1];
        float r = rects[index + 2];
        float b = rects[index + 3];

        Vertex::set(vertex++, l, t);
        Vertex::set(vertex++, r, t);
        Vertex::set(vertex++, l, b);
        Vertex::set(vertex++, r, b);

        left = std::min(left, l);
        top = std::min(top, t);
        right = std::max(right, r);
        bottom = std::max(bottom, b);
    }
    addOp(alloc().create_trivial<SimpleRectsOp>(
            Rect(left, top, right, bottom),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(paint), rectData, vertexCount));
}

void RecordingCanvas::drawRegion(const SkRegion& region, const SkPaint& paint) {
    if (CC_UNLIKELY(PaintUtils::paintWillNotDraw(paint))) return;

    if (paint.getStyle() == SkPaint::kFill_Style
            && (!paint.isAntiAlias() || mState.currentTransform()->isSimple())) {
        int count = 0;
        Vector<float> rects;
        SkRegion::Iterator it(region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            rects.push(r.fLeft);
            rects.push(r.fTop);
            rects.push(r.fRight);
            rects.push(r.fBottom);
            count += 4;
            it.next();
        }
        drawSimpleRects(rects.array(), count, &paint);
    } else {
        SkRegion::Iterator it(region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            drawRect(r.fLeft, r.fTop, r.fRight, r.fBottom, paint);
            it.next();
        }
    }
}

void RecordingCanvas::drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint& paint) {
    if (CC_UNLIKELY(PaintUtils::paintWillNotDraw(paint))) return;

    if (CC_LIKELY(MathUtils::isPositive(rx) || MathUtils::isPositive(ry))) {
        addOp(alloc().create_trivial<RoundRectOp>(
                Rect(left, top, right, bottom),
                *(mState.currentSnapshot()->transform),
                getRecordedClip(),
                refPaint(&paint), rx, ry));
    } else {
        drawRect(left, top, right, bottom, paint);
    }
}

void RecordingCanvas::drawRoundRect(
        CanvasPropertyPrimitive* left, CanvasPropertyPrimitive* top,
        CanvasPropertyPrimitive* right, CanvasPropertyPrimitive* bottom,
        CanvasPropertyPrimitive* rx, CanvasPropertyPrimitive* ry,
        CanvasPropertyPaint* paint) {
    mDisplayList->ref(left);
    mDisplayList->ref(top);
    mDisplayList->ref(right);
    mDisplayList->ref(bottom);
    mDisplayList->ref(rx);
    mDisplayList->ref(ry);
    mDisplayList->ref(paint);
    refBitmapsInShader(paint->value.getShader());
    addOp(alloc().create_trivial<RoundRectPropsOp>(
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            &paint->value,
            &left->value, &top->value, &right->value, &bottom->value,
            &rx->value, &ry->value));
}

void RecordingCanvas::drawCircle(float x, float y, float radius, const SkPaint& paint) {
    // TODO: move to Canvas.h
    if (CC_UNLIKELY(radius <= 0 || PaintUtils::paintWillNotDraw(paint))) return;

    drawOval(x - radius, y - radius, x + radius, y + radius, paint);
}

void RecordingCanvas::drawCircle(
        CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
        CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint) {
    mDisplayList->ref(x);
    mDisplayList->ref(y);
    mDisplayList->ref(radius);
    mDisplayList->ref(paint);
    refBitmapsInShader(paint->value.getShader());
    addOp(alloc().create_trivial<CirclePropsOp>(
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            &paint->value,
            &x->value, &y->value, &radius->value));
}

void RecordingCanvas::drawOval(float left, float top, float right, float bottom, const SkPaint& paint) {
    if (CC_UNLIKELY(PaintUtils::paintWillNotDraw(paint))) return;

    addOp(alloc().create_trivial<OvalOp>(
            Rect(left, top, right, bottom),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(&paint)));
}

void RecordingCanvas::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint) {
    if (CC_UNLIKELY(PaintUtils::paintWillNotDraw(paint))) return;

    if (fabs(sweepAngle) >= 360.0f) {
        drawOval(left, top, right, bottom, paint);
    } else {
        addOp(alloc().create_trivial<ArcOp>(
                Rect(left, top, right, bottom),
                *(mState.currentSnapshot()->transform),
                getRecordedClip(),
                refPaint(&paint),
                startAngle, sweepAngle, useCenter));
    }
}

void RecordingCanvas::drawPath(const SkPath& path, const SkPaint& paint) {
    if (CC_UNLIKELY(PaintUtils::paintWillNotDraw(paint))) return;

    addOp(alloc().create_trivial<PathOp>(
            Rect(path.getBounds()),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(&paint), refPath(&path)));
}

void RecordingCanvas::drawVectorDrawable(VectorDrawableRoot* tree) {
    mDisplayList->ref(tree);
    mDisplayList->vectorDrawables.push_back(tree);
    addOp(alloc().create_trivial<VectorDrawableOp>(
            tree,
            Rect(tree->stagingProperties()->getBounds()),
            *(mState.currentSnapshot()->transform),
            getRecordedClip()));
}

// Bitmap-based
void RecordingCanvas::drawBitmap(const SkBitmap& bitmap, float left, float top, const SkPaint* paint) {
    save(SaveFlags::Matrix);
    translate(left, top);
    drawBitmap(&bitmap, paint);
    restore();
}

void RecordingCanvas::drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix,
                            const SkPaint* paint) {
    if (matrix.isIdentity()) {
        drawBitmap(&bitmap, paint);
    } else if (!(matrix.getType() & ~(SkMatrix::kScale_Mask | SkMatrix::kTranslate_Mask))
            && MathUtils::isPositive(matrix.getScaleX())
            && MathUtils::isPositive(matrix.getScaleY())) {
        // SkMatrix::isScaleTranslate() not available in L
        SkRect src;
        SkRect dst;
        bitmap.getBounds(&src);
        matrix.mapRect(&dst, src);
        drawBitmap(bitmap, src.fLeft, src.fTop, src.fRight, src.fBottom,
                   dst.fLeft, dst.fTop, dst.fRight, dst.fBottom, paint);
    } else {
        save(SaveFlags::Matrix);
        concat(matrix);
        drawBitmap(&bitmap, paint);
        restore();
    }
}

void RecordingCanvas::drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint) {
    if (srcLeft == 0 && srcTop == 0
            && srcRight == bitmap.width()
            && srcBottom == bitmap.height()
            && (srcBottom - srcTop == dstBottom - dstTop)
            && (srcRight - srcLeft == dstRight - dstLeft)) {
        // transform simple rect to rect drawing case into position bitmap ops, since they merge
        save(SaveFlags::Matrix);
        translate(dstLeft, dstTop);
        drawBitmap(&bitmap, paint);
        restore();
    } else {
        addOp(alloc().create_trivial<BitmapRectOp>(
                Rect(dstLeft, dstTop, dstRight, dstBottom),
                *(mState.currentSnapshot()->transform),
                getRecordedClip(),
                refPaint(paint), refBitmap(bitmap),
                Rect(srcLeft, srcTop, srcRight, srcBottom)));
    }
}

void RecordingCanvas::drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint) {
    int vertexCount = (meshWidth + 1) * (meshHeight + 1);
    addOp(alloc().create_trivial<BitmapMeshOp>(
            calcBoundsOfPoints(vertices, vertexCount * 2),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(paint), refBitmap(bitmap), meshWidth, meshHeight,
            refBuffer<float>(vertices, vertexCount * 2), // 2 floats per vertex
            refBuffer<int>(colors, vertexCount))); // 1 color per vertex
}

void RecordingCanvas::drawNinePatch(const SkBitmap& bitmap, const android::Res_png_9patch& patch,
            float dstLeft, float dstTop, float dstRight, float dstBottom,
            const SkPaint* paint) {
    addOp(alloc().create_trivial<PatchOp>(
            Rect(dstLeft, dstTop, dstRight, dstBottom),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(paint), refBitmap(bitmap), refPatch(&patch)));
}

// Text
void RecordingCanvas::drawGlyphs(const uint16_t* glyphs, const float* positions, int glyphCount,
            const SkPaint& paint, float x, float y, float boundsLeft, float boundsTop,
            float boundsRight, float boundsBottom, float totalAdvance) {
    if (!glyphs || !positions || glyphCount <= 0 || PaintUtils::paintWillNotDrawText(paint)) return;
    glyphs = refBuffer<glyph_t>(glyphs, glyphCount);
    positions = refBuffer<float>(positions, glyphCount * 2);

    // TODO: either must account for text shadow in bounds, or record separate ops for text shadows
    addOp(alloc().create_trivial<TextOp>(
            Rect(boundsLeft, boundsTop, boundsRight, boundsBottom),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(&paint), glyphs, positions, glyphCount, x, y));
    drawTextDecorations(x, y, totalAdvance, paint);
}

void RecordingCanvas::drawGlyphsOnPath(const uint16_t* glyphs, int glyphCount, const SkPath& path,
            float hOffset, float vOffset, const SkPaint& paint) {
    if (!glyphs || glyphCount <= 0 || PaintUtils::paintWillNotDrawText(paint)) return;
    glyphs = refBuffer<glyph_t>(glyphs, glyphCount);
    addOp(alloc().create_trivial<TextOnPathOp>(
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(&paint), glyphs, glyphCount, refPath(&path), hOffset, vOffset));
}

void RecordingCanvas::drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) {
    addOp(alloc().create_trivial<BitmapOp>(
            Rect(bitmap->width(), bitmap->height()),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(paint), refBitmap(*bitmap)));
}

void RecordingCanvas::drawRenderNode(RenderNode* renderNode) {
    auto&& stagingProps = renderNode->stagingProperties();
    RenderNodeOp* op = alloc().create_trivial<RenderNodeOp>(
            Rect(stagingProps.getWidth(), stagingProps.getHeight()),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            renderNode);
    int opIndex = addOp(op);
    if (CC_LIKELY(opIndex >= 0)) {
        int childIndex = mDisplayList->addChild(op);

        // update the chunk's child indices
        DisplayList::Chunk& chunk = mDisplayList->chunks.back();
        chunk.endChildIndex = childIndex + 1;

        if (renderNode->stagingProperties().isProjectionReceiver()) {
            // use staging property, since recording on UI thread
            mDisplayList->projectionReceiveIndex = opIndex;
        }
    }
}

void RecordingCanvas::drawLayer(DeferredLayerUpdater* layerHandle) {
    // We ref the DeferredLayerUpdater due to its thread-safe ref-counting semantics.
    mDisplayList->ref(layerHandle);

    // Note that the backing layer has *not* yet been updated, so don't trust
    // its width, height, transform, etc...!
    addOp(alloc().create_trivial<TextureLayerOp>(
            Rect(layerHandle->getWidth(), layerHandle->getHeight()),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            layerHandle->backingLayer()));
}

void RecordingCanvas::callDrawGLFunction(Functor* functor,
        GlFunctorLifecycleListener* listener) {
    mDisplayList->functors.push_back({functor, listener});
    mDisplayList->ref(listener);
    addOp(alloc().create_trivial<FunctorOp>(
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            functor));
}

int RecordingCanvas::addOp(RecordedOp* op) {
    // skip op with empty clip
    if (op->localClip && op->localClip->rect.isEmpty()) {
        // NOTE: this rejection happens after op construction/content ref-ing, so content ref'd
        // and held by renderthread isn't affected by clip rejection.
        // Could rewind alloc here if desired, but callers would have to not touch op afterwards.
        return -1;
    }

    int insertIndex = mDisplayList->ops.size();
    mDisplayList->ops.push_back(op);
    if (mDeferredBarrierType != DeferredBarrierType::None) {
        // op is first in new chunk
        mDisplayList->chunks.emplace_back();
        DisplayList::Chunk& newChunk = mDisplayList->chunks.back();
        newChunk.beginOpIndex = insertIndex;
        newChunk.endOpIndex = insertIndex + 1;
        newChunk.reorderChildren = (mDeferredBarrierType == DeferredBarrierType::OutOfOrder);
        newChunk.reorderClip = mDeferredBarrierClip;

        int nextChildIndex = mDisplayList->children.size();
        newChunk.beginChildIndex = newChunk.endChildIndex = nextChildIndex;
        mDeferredBarrierType = DeferredBarrierType::None;
    } else {
        // standard case - append to existing chunk
        mDisplayList->chunks.back().endOpIndex = insertIndex + 1;
    }
    return insertIndex;
}

void RecordingCanvas::refBitmapsInShader(const SkShader* shader) {
    if (!shader) return;

    // If this paint has an SkShader that has an SkBitmap add
    // it to the bitmap pile
    SkBitmap bitmap;
    SkShader::TileMode xy[2];
    if (shader->isABitmap(&bitmap, nullptr, xy)) {
        refBitmap(bitmap);
        return;
    }
    SkShader::ComposeRec rec;
    if (shader->asACompose(&rec)) {
        refBitmapsInShader(rec.fShaderA);
        refBitmapsInShader(rec.fShaderB);
        return;
    }
}

}; // namespace uirenderer
}; // namespace android
