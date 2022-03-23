/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <GrRecordingContext.h>

#include <experimental/type_traits>

#include "SkAndroidFrameworkUtils.h"
#include "SkCanvas.h"
#include "SkCanvasPriv.h"
#include "SkColor.h"
#include "SkData.h"
#include "SkDrawShadowInfo.h"
#include "SkImage.h"
#include "SkImageFilter.h"
#include "SkLatticeIter.h"
#include "SkMath.h"
#include "SkPicture.h"
#include "SkRSXform.h"
#include "SkRegion.h"
#include "SkTextBlob.h"
#include "SkVertices.h"
#include "VectorDrawable.h"
#include "pipeline/skia/AnimatedDrawables.h"
#include "pipeline/skia/FunctorDrawable.h"

namespace android {
namespace uirenderer {

#ifndef SKLITEDL_PAGE
#define SKLITEDL_PAGE 4096
#endif

// A stand-in for an optional SkRect which was not set, e.g. bounds for a saveLayer().
static const SkRect kUnset = {SK_ScalarInfinity, 0, 0, 0};
static const SkRect* maybe_unset(const SkRect& r) {
    return r.left() == SK_ScalarInfinity ? nullptr : &r;
}

// copy_v(dst, src,n, src,n, ...) copies an arbitrary number of typed srcs into dst.
static void copy_v(void* dst) {}

template <typename S, typename... Rest>
static void copy_v(void* dst, const S* src, int n, Rest&&... rest) {
    SkASSERTF(((uintptr_t)dst & (alignof(S) - 1)) == 0,
              "Expected %p to be aligned for at least %zu bytes.", dst, alignof(S));
    sk_careful_memcpy(dst, src, n * sizeof(S));
    copy_v(SkTAddOffset<void>(dst, n * sizeof(S)), std::forward<Rest>(rest)...);
}

// Helper for getting back at arrays which have been copy_v'd together after an Op.
template <typename D, typename T>
static const D* pod(const T* op, size_t offset = 0) {
    return SkTAddOffset<const D>(op + 1, offset);
}

namespace {

#define X(T) T,
enum class Type : uint8_t {
#include "DisplayListOps.in"
};
#undef X

struct Op {
    uint32_t type : 8;
    uint32_t skip : 24;
};
static_assert(sizeof(Op) == 4, "");

struct Flush final : Op {
    static const auto kType = Type::Flush;
    void draw(SkCanvas* c, const SkMatrix&) const { c->flush(); }
};

struct Save final : Op {
    static const auto kType = Type::Save;
    void draw(SkCanvas* c, const SkMatrix&) const { c->save(); }
};
struct Restore final : Op {
    static const auto kType = Type::Restore;
    void draw(SkCanvas* c, const SkMatrix&) const { c->restore(); }
};
struct SaveLayer final : Op {
    static const auto kType = Type::SaveLayer;
    SaveLayer(const SkRect* bounds, const SkPaint* paint, const SkImageFilter* backdrop,
              SkCanvas::SaveLayerFlags flags) {
        if (bounds) {
            this->bounds = *bounds;
        }
        if (paint) {
            this->paint = *paint;
        }
        this->backdrop = sk_ref_sp(backdrop);
        this->flags = flags;
    }
    SkRect bounds = kUnset;
    SkPaint paint;
    sk_sp<const SkImageFilter> backdrop;
    SkCanvas::SaveLayerFlags flags;
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->saveLayer({maybe_unset(bounds), &paint, backdrop.get(), flags});
    }
};
struct SaveBehind final : Op {
    static const auto kType = Type::SaveBehind;
    SaveBehind(const SkRect* subset) {
        if (subset) { this->subset = *subset; }
    }
    SkRect  subset = kUnset;
    void draw(SkCanvas* c, const SkMatrix&) const {
        SkAndroidFrameworkUtils::SaveBehind(c, &subset);
    }
};

struct Concat final : Op {
    static const auto kType = Type::Concat;
    Concat(const SkM44& matrix) : matrix(matrix) {}
    SkM44 matrix;
    void draw(SkCanvas* c, const SkMatrix&) const { c->concat(matrix); }
};
struct SetMatrix final : Op {
    static const auto kType = Type::SetMatrix;
    SetMatrix(const SkM44& matrix) : matrix(matrix) {}
    SkM44 matrix;
    void draw(SkCanvas* c, const SkMatrix& original) const {
        c->setMatrix(SkM44(original) * matrix);
    }
};
struct Scale final : Op {
    static const auto kType = Type::Scale;
    Scale(SkScalar sx, SkScalar sy) : sx(sx), sy(sy) {}
    SkScalar sx, sy;
    void draw(SkCanvas* c, const SkMatrix&) const { c->scale(sx, sy); }
};
struct Translate final : Op {
    static const auto kType = Type::Translate;
    Translate(SkScalar dx, SkScalar dy) : dx(dx), dy(dy) {}
    SkScalar dx, dy;
    void draw(SkCanvas* c, const SkMatrix&) const { c->translate(dx, dy); }
};

struct ClipPath final : Op {
    static const auto kType = Type::ClipPath;
    ClipPath(const SkPath& path, SkClipOp op, bool aa) : path(path), op(op), aa(aa) {}
    SkPath path;
    SkClipOp op;
    bool aa;
    void draw(SkCanvas* c, const SkMatrix&) const { c->clipPath(path, op, aa); }
};
struct ClipRect final : Op {
    static const auto kType = Type::ClipRect;
    ClipRect(const SkRect& rect, SkClipOp op, bool aa) : rect(rect), op(op), aa(aa) {}
    SkRect rect;
    SkClipOp op;
    bool aa;
    void draw(SkCanvas* c, const SkMatrix&) const { c->clipRect(rect, op, aa); }
};
struct ClipRRect final : Op {
    static const auto kType = Type::ClipRRect;
    ClipRRect(const SkRRect& rrect, SkClipOp op, bool aa) : rrect(rrect), op(op), aa(aa) {}
    SkRRect rrect;
    SkClipOp op;
    bool aa;
    void draw(SkCanvas* c, const SkMatrix&) const { c->clipRRect(rrect, op, aa); }
};
struct ClipRegion final : Op {
    static const auto kType = Type::ClipRegion;
    ClipRegion(const SkRegion& region, SkClipOp op) : region(region), op(op) {}
    SkRegion region;
    SkClipOp op;
    void draw(SkCanvas* c, const SkMatrix&) const { c->clipRegion(region, op); }
};

struct DrawPaint final : Op {
    static const auto kType = Type::DrawPaint;
    DrawPaint(const SkPaint& paint) : paint(paint) {}
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const { c->drawPaint(paint); }
};
struct DrawBehind final : Op {
    static const auto kType = Type::DrawBehind;
    DrawBehind(const SkPaint& paint) : paint(paint) {}
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const { SkCanvasPriv::DrawBehind(c, paint); }
};
struct DrawPath final : Op {
    static const auto kType = Type::DrawPath;
    DrawPath(const SkPath& path, const SkPaint& paint) : path(path), paint(paint) {}
    SkPath path;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const { c->drawPath(path, paint); }
};
struct DrawRect final : Op {
    static const auto kType = Type::DrawRect;
    DrawRect(const SkRect& rect, const SkPaint& paint) : rect(rect), paint(paint) {}
    SkRect rect;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const { c->drawRect(rect, paint); }
};
struct DrawRegion final : Op {
    static const auto kType = Type::DrawRegion;
    DrawRegion(const SkRegion& region, const SkPaint& paint) : region(region), paint(paint) {}
    SkRegion region;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const { c->drawRegion(region, paint); }
};
struct DrawOval final : Op {
    static const auto kType = Type::DrawOval;
    DrawOval(const SkRect& oval, const SkPaint& paint) : oval(oval), paint(paint) {}
    SkRect oval;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const { c->drawOval(oval, paint); }
};
struct DrawArc final : Op {
    static const auto kType = Type::DrawArc;
    DrawArc(const SkRect& oval, SkScalar startAngle, SkScalar sweepAngle, bool useCenter,
            const SkPaint& paint)
            : oval(oval)
            , startAngle(startAngle)
            , sweepAngle(sweepAngle)
            , useCenter(useCenter)
            , paint(paint) {}
    SkRect oval;
    SkScalar startAngle;
    SkScalar sweepAngle;
    bool useCenter;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->drawArc(oval, startAngle, sweepAngle, useCenter, paint);
    }
};
struct DrawRRect final : Op {
    static const auto kType = Type::DrawRRect;
    DrawRRect(const SkRRect& rrect, const SkPaint& paint) : rrect(rrect), paint(paint) {}
    SkRRect rrect;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const { c->drawRRect(rrect, paint); }
};
struct DrawDRRect final : Op {
    static const auto kType = Type::DrawDRRect;
    DrawDRRect(const SkRRect& outer, const SkRRect& inner, const SkPaint& paint)
            : outer(outer), inner(inner), paint(paint) {}
    SkRRect outer, inner;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const { c->drawDRRect(outer, inner, paint); }
};

struct DrawAnnotation final : Op {
    static const auto kType = Type::DrawAnnotation;
    DrawAnnotation(const SkRect& rect, SkData* value) : rect(rect), value(sk_ref_sp(value)) {}
    SkRect rect;
    sk_sp<SkData> value;
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->drawAnnotation(rect, pod<char>(this), value.get());
    }
};
struct DrawDrawable final : Op {
    static const auto kType = Type::DrawDrawable;
    DrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) : drawable(sk_ref_sp(drawable)) {
        if (matrix) {
            this->matrix = *matrix;
        }
    }
    sk_sp<SkDrawable> drawable;
    SkMatrix matrix = SkMatrix::I();
    // It is important that we call drawable->draw(c) here instead of c->drawDrawable(drawable).
    // Drawables are mutable and in cases, like RenderNodeDrawable, are not expected to produce the
    // same content if retained outside the duration of the frame. Therefore we resolve
    // them now and do not allow the canvas to take a reference to the drawable and potentially
    // keep it alive for longer than the frames duration (e.g. SKP serialization).
    void draw(SkCanvas* c, const SkMatrix&) const { drawable->draw(c, &matrix); }
};
struct DrawPicture final : Op {
    static const auto kType = Type::DrawPicture;
    DrawPicture(const SkPicture* picture, const SkMatrix* matrix, const SkPaint* paint)
            : picture(sk_ref_sp(picture)) {
        if (matrix) {
            this->matrix = *matrix;
        }
        if (paint) {
            this->paint = *paint;
            has_paint = true;
        }
    }
    sk_sp<const SkPicture> picture;
    SkMatrix matrix = SkMatrix::I();
    SkPaint paint;
    bool has_paint = false;  // TODO: why is a default paint not the same?
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->drawPicture(picture.get(), &matrix, has_paint ? &paint : nullptr);
    }
};

struct DrawImage final : Op {
    static const auto kType = Type::DrawImage;
    DrawImage(sk_sp<const SkImage>&& image, SkScalar x, SkScalar y,
              const SkSamplingOptions& sampling, const SkPaint* paint, BitmapPalette palette)
            : image(std::move(image)), x(x), y(y), sampling(sampling), palette(palette) {
        if (paint) {
            this->paint = *paint;
        }
    }
    sk_sp<const SkImage> image;
    SkScalar x, y;
    SkSamplingOptions sampling;
    SkPaint paint;
    BitmapPalette palette;
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->drawImage(image.get(), x, y, sampling, &paint);
    }
};
struct DrawImageRect final : Op {
    static const auto kType = Type::DrawImageRect;
    DrawImageRect(sk_sp<const SkImage>&& image, const SkRect* src, const SkRect& dst,
                  const SkSamplingOptions& sampling, const SkPaint* paint,
                  SkCanvas::SrcRectConstraint constraint, BitmapPalette palette)
            : image(std::move(image)), dst(dst), sampling(sampling), constraint(constraint)
            , palette(palette) {
        this->src = src ? *src : SkRect::MakeIWH(this->image->width(), this->image->height());
        if (paint) {
            this->paint = *paint;
        }
    }
    sk_sp<const SkImage> image;
    SkRect src, dst;
    SkSamplingOptions sampling;
    SkPaint paint;
    SkCanvas::SrcRectConstraint constraint;
    BitmapPalette palette;
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->drawImageRect(image.get(), src, dst, sampling, &paint, constraint);
    }
};
struct DrawImageLattice final : Op {
    static const auto kType = Type::DrawImageLattice;
    DrawImageLattice(sk_sp<const SkImage>&& image, int xs, int ys, int fs, const SkIRect& src,
                     const SkRect& dst, SkFilterMode filter, const SkPaint* paint,
                     BitmapPalette palette)
            : image(std::move(image))
            , xs(xs)
            , ys(ys)
            , fs(fs)
            , src(src)
            , dst(dst)
            , filter(filter)
            , palette(palette) {
        if (paint) {
            this->paint = *paint;
        }
    }
    sk_sp<const SkImage> image;
    int xs, ys, fs;
    SkIRect src;
    SkRect dst;
    SkFilterMode filter;
    SkPaint paint;
    BitmapPalette palette;
    void draw(SkCanvas* c, const SkMatrix&) const {
        auto xdivs = pod<int>(this, 0), ydivs = pod<int>(this, xs * sizeof(int));
        auto colors = (0 == fs) ? nullptr : pod<SkColor>(this, (xs + ys) * sizeof(int));
        auto flags =
                (0 == fs) ? nullptr : pod<SkCanvas::Lattice::RectType>(
                                              this, (xs + ys) * sizeof(int) + fs * sizeof(SkColor));
        c->drawImageLattice(image.get(), {xdivs, ydivs, flags, xs, ys, &src, colors}, dst,
                            filter, &paint);
    }
};

struct DrawTextBlob final : Op {
    static const auto kType = Type::DrawTextBlob;
    DrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y, const SkPaint& paint)
        : blob(sk_ref_sp(blob)), x(x), y(y), paint(paint), drawTextBlobMode(gDrawTextBlobMode) {}
    sk_sp<const SkTextBlob> blob;
    SkScalar x, y;
    SkPaint paint;
    DrawTextBlobMode drawTextBlobMode;
    void draw(SkCanvas* c, const SkMatrix&) const { c->drawTextBlob(blob.get(), x, y, paint); }
};

struct DrawPatch final : Op {
    static const auto kType = Type::DrawPatch;
    DrawPatch(const SkPoint cubics[12], const SkColor colors[4], const SkPoint texs[4],
              SkBlendMode bmode, const SkPaint& paint)
            : xfermode(bmode), paint(paint) {
        copy_v(this->cubics, cubics, 12);
        if (colors) {
            copy_v(this->colors, colors, 4);
            has_colors = true;
        }
        if (texs) {
            copy_v(this->texs, texs, 4);
            has_texs = true;
        }
    }
    SkPoint cubics[12];
    SkColor colors[4];
    SkPoint texs[4];
    SkBlendMode xfermode;
    SkPaint paint;
    bool has_colors = false;
    bool has_texs = false;
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->drawPatch(cubics, has_colors ? colors : nullptr, has_texs ? texs : nullptr, xfermode,
                     paint);
    }
};
struct DrawPoints final : Op {
    static const auto kType = Type::DrawPoints;
    DrawPoints(SkCanvas::PointMode mode, size_t count, const SkPaint& paint)
            : mode(mode), count(count), paint(paint) {}
    SkCanvas::PointMode mode;
    size_t count;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->drawPoints(mode, count, pod<SkPoint>(this), paint);
    }
};
struct DrawVertices final : Op {
    static const auto kType = Type::DrawVertices;
    DrawVertices(const SkVertices* v, SkBlendMode m, const SkPaint& p)
            : vertices(sk_ref_sp(const_cast<SkVertices*>(v))), mode(m), paint(p) {}
    sk_sp<SkVertices> vertices;
    SkBlendMode mode;
    SkPaint paint;
    void draw(SkCanvas* c, const SkMatrix&) const {
        c->drawVertices(vertices, mode, paint);
    }
};
struct DrawAtlas final : Op {
    static const auto kType = Type::DrawAtlas;
    DrawAtlas(const SkImage* atlas, int count, SkBlendMode mode, const SkSamplingOptions& sampling,
              const SkRect* cull, const SkPaint* paint, bool has_colors)
            : atlas(sk_ref_sp(atlas)), count(count), mode(mode), sampling(sampling)
            , has_colors(has_colors) {
        if (cull) {
            this->cull = *cull;
        }
        if (paint) {
            this->paint = *paint;
        }
    }
    sk_sp<const SkImage> atlas;
    int count;
    SkBlendMode mode;
    SkSamplingOptions sampling;
    SkRect cull = kUnset;
    SkPaint paint;
    bool has_colors;
    void draw(SkCanvas* c, const SkMatrix&) const {
        auto xforms = pod<SkRSXform>(this, 0);
        auto texs = pod<SkRect>(this, count * sizeof(SkRSXform));
        auto colors = has_colors ? pod<SkColor>(this, count * (sizeof(SkRSXform) + sizeof(SkRect)))
                                 : nullptr;
        c->drawAtlas(atlas.get(), xforms, texs, colors, count, mode, sampling, maybe_unset(cull),
                     &paint);
    }
};
struct DrawShadowRec final : Op {
    static const auto kType = Type::DrawShadowRec;
    DrawShadowRec(const SkPath& path, const SkDrawShadowRec& rec) : fPath(path), fRec(rec) {}
    SkPath fPath;
    SkDrawShadowRec fRec;
    void draw(SkCanvas* c, const SkMatrix&) const { c->private_draw_shadow_rec(fPath, fRec); }
};

struct DrawVectorDrawable final : Op {
    static const auto kType = Type::DrawVectorDrawable;
    DrawVectorDrawable(VectorDrawableRoot* tree)
            : mRoot(tree)
            , mBounds(tree->stagingProperties().getBounds())
            , palette(tree->computePalette()) {
        // Recording, so use staging properties
        tree->getPaintFor(&paint, tree->stagingProperties());
    }

    void draw(SkCanvas* canvas, const SkMatrix&) const {
        mRoot->draw(canvas, mBounds, paint);
    }

    sp<VectorDrawableRoot> mRoot;
    SkRect mBounds;
    SkPaint paint;
    BitmapPalette palette;
};

struct DrawRippleDrawable final : Op {
    static const auto kType = Type::DrawRippleDrawable;
    DrawRippleDrawable(const skiapipeline::RippleDrawableParams& params) : mParams(params) {}

    void draw(SkCanvas* canvas, const SkMatrix&) const {
        skiapipeline::AnimatedRippleDrawable::draw(canvas, mParams);
    }

    skiapipeline::RippleDrawableParams mParams;
};

struct DrawWebView final : Op {
    static const auto kType = Type::DrawWebView;
    DrawWebView(skiapipeline::FunctorDrawable* drawable) : drawable(sk_ref_sp(drawable)) {}
    sk_sp<skiapipeline::FunctorDrawable> drawable;
    // We can't invoke SkDrawable::draw directly, because VkFunctorDrawable expects
    // SkDrawable::onSnapGpuDrawHandler callback instead of SkDrawable::onDraw.
    // SkCanvas::drawDrawable/SkGpuDevice::drawDrawable has the logic to invoke
    // onSnapGpuDrawHandler.
private:
    // Unfortunately WebView does not have complex clip information serialized, and we only perform
    // best-effort stencil fill for GLES. So for Vulkan we create an intermediate layer if the
    // canvas clip is complex.
    static bool needsCompositedLayer(SkCanvas* c) {
        if (Properties::getRenderPipelineType() != RenderPipelineType::SkiaVulkan) {
            return false;
        }
        SkRegion clipRegion;
        // WebView's rasterizer has access to simple clips, so for Vulkan we only need to check if
        // the clip is more complex than a rectangle.
        c->temporary_internal_getRgnClip(&clipRegion);
        return clipRegion.isComplex();
    }

    mutable SkImageInfo mLayerImageInfo;
    mutable sk_sp<SkSurface> mLayerSurface = nullptr;

public:
    void draw(SkCanvas* c, const SkMatrix&) const {
        if (needsCompositedLayer(c)) {
            // What we do now is create an offscreen surface, sized by the clip bounds.
            // We won't apply a clip while drawing - clipping will be performed when compositing the
            // surface back onto the original canvas. Note also that we're not using saveLayer
            // because the webview functor still doesn't respect the canvas clip stack.
            const SkIRect deviceBounds = c->getDeviceClipBounds();
            if (mLayerSurface == nullptr || c->imageInfo() != mLayerImageInfo) {
                GrRecordingContext* directContext = c->recordingContext();
                mLayerImageInfo =
                        c->imageInfo().makeWH(deviceBounds.width(), deviceBounds.height());
                mLayerSurface = SkSurface::MakeRenderTarget(directContext, SkBudgeted::kYes,
                                                            mLayerImageInfo, 0,
                                                            kTopLeft_GrSurfaceOrigin, nullptr);
            }

            SkCanvas* layerCanvas = mLayerSurface->getCanvas();

            SkAutoCanvasRestore(layerCanvas, true);
            layerCanvas->clear(SK_ColorTRANSPARENT);

            // Preserve the transform from the original canvas, but now the clip rectangle is
            // anchored at the origin so we need to transform the clipped content to the origin.
            SkM44 mat4(c->getLocalToDevice());
            mat4.postTranslate(-deviceBounds.fLeft, -deviceBounds.fTop);
            layerCanvas->concat(mat4);
            layerCanvas->drawDrawable(drawable.get());

            SkAutoCanvasRestore acr(c, true);

            // Temporarily use an identity transform, because this is just blitting to the parent
            // canvas with an offset.
            SkMatrix invertedMatrix;
            if (!c->getTotalMatrix().invert(&invertedMatrix)) {
                ALOGW("Unable to extract invert canvas matrix; aborting VkFunctor draw");
                return;
            }
            c->concat(invertedMatrix);
            mLayerSurface->draw(c, deviceBounds.fLeft, deviceBounds.fTop);
        } else {
            c->drawDrawable(drawable.get());
        }
    }
};
}

template <typename T, typename... Args>
void* DisplayListData::push(size_t pod, Args&&... args) {
    size_t skip = SkAlignPtr(sizeof(T) + pod);
    SkASSERT(skip < (1 << 24));
    if (fUsed + skip > fReserved) {
        static_assert(SkIsPow2(SKLITEDL_PAGE), "This math needs updating for non-pow2.");
        // Next greater multiple of SKLITEDL_PAGE.
        fReserved = (fUsed + skip + SKLITEDL_PAGE) & ~(SKLITEDL_PAGE - 1);
        fBytes.realloc(fReserved);
        LOG_ALWAYS_FATAL_IF(fBytes.get() == nullptr, "realloc(%zd) failed", fReserved);
    }
    SkASSERT(fUsed + skip <= fReserved);
    auto op = (T*)(fBytes.get() + fUsed);
    fUsed += skip;
    new (op) T{std::forward<Args>(args)...};
    op->type = (uint32_t)T::kType;
    op->skip = skip;
    return op + 1;
}

template <typename Fn, typename... Args>
inline void DisplayListData::map(const Fn fns[], Args... args) const {
    auto end = fBytes.get() + fUsed;
    for (const uint8_t* ptr = fBytes.get(); ptr < end;) {
        auto op = (const Op*)ptr;
        auto type = op->type;
        auto skip = op->skip;
        if (auto fn = fns[type]) {  // We replace no-op functions with nullptrs
            fn(op, args...);        // to avoid the overhead of a pointless call.
        }
        ptr += skip;
    }
}

void DisplayListData::flush() {
    this->push<Flush>(0);
}

void DisplayListData::save() {
    this->push<Save>(0);
}
void DisplayListData::restore() {
    this->push<Restore>(0);
}
void DisplayListData::saveLayer(const SkRect* bounds, const SkPaint* paint,
                                const SkImageFilter* backdrop, SkCanvas::SaveLayerFlags flags) {
    this->push<SaveLayer>(0, bounds, paint, backdrop, flags);
}

void DisplayListData::saveBehind(const SkRect* subset) {
    this->push<SaveBehind>(0, subset);
}

void DisplayListData::concat(const SkM44& m) {
    this->push<Concat>(0, m);
}
void DisplayListData::setMatrix(const SkM44& matrix) {
    this->push<SetMatrix>(0, matrix);
}
void DisplayListData::scale(SkScalar sx, SkScalar sy) {
    this->push<Scale>(0, sx, sy);
}
void DisplayListData::translate(SkScalar dx, SkScalar dy) {
    this->push<Translate>(0, dx, dy);
}

void DisplayListData::clipPath(const SkPath& path, SkClipOp op, bool aa) {
    this->push<ClipPath>(0, path, op, aa);
}
void DisplayListData::clipRect(const SkRect& rect, SkClipOp op, bool aa) {
    this->push<ClipRect>(0, rect, op, aa);
}
void DisplayListData::clipRRect(const SkRRect& rrect, SkClipOp op, bool aa) {
    this->push<ClipRRect>(0, rrect, op, aa);
}
void DisplayListData::clipRegion(const SkRegion& region, SkClipOp op) {
    this->push<ClipRegion>(0, region, op);
}

void DisplayListData::drawPaint(const SkPaint& paint) {
    this->push<DrawPaint>(0, paint);
}
void DisplayListData::drawBehind(const SkPaint& paint) {
    this->push<DrawBehind>(0, paint);
}
void DisplayListData::drawPath(const SkPath& path, const SkPaint& paint) {
    this->push<DrawPath>(0, path, paint);
}
void DisplayListData::drawRect(const SkRect& rect, const SkPaint& paint) {
    this->push<DrawRect>(0, rect, paint);
}
void DisplayListData::drawRegion(const SkRegion& region, const SkPaint& paint) {
    this->push<DrawRegion>(0, region, paint);
}
void DisplayListData::drawOval(const SkRect& oval, const SkPaint& paint) {
    this->push<DrawOval>(0, oval, paint);
}
void DisplayListData::drawArc(const SkRect& oval, SkScalar startAngle, SkScalar sweepAngle,
                              bool useCenter, const SkPaint& paint) {
    this->push<DrawArc>(0, oval, startAngle, sweepAngle, useCenter, paint);
}
void DisplayListData::drawRRect(const SkRRect& rrect, const SkPaint& paint) {
    this->push<DrawRRect>(0, rrect, paint);
}
void DisplayListData::drawDRRect(const SkRRect& outer, const SkRRect& inner, const SkPaint& paint) {
    this->push<DrawDRRect>(0, outer, inner, paint);
}

void DisplayListData::drawAnnotation(const SkRect& rect, const char* key, SkData* value) {
    size_t bytes = strlen(key) + 1;
    void* pod = this->push<DrawAnnotation>(bytes, rect, value);
    copy_v(pod, key, bytes);
}
void DisplayListData::drawDrawable(SkDrawable* drawable, const SkMatrix* matrix) {
    this->push<DrawDrawable>(0, drawable, matrix);
}
void DisplayListData::drawPicture(const SkPicture* picture, const SkMatrix* matrix,
                                  const SkPaint* paint) {
    this->push<DrawPicture>(0, picture, matrix, paint);
}
void DisplayListData::drawImage(sk_sp<const SkImage> image, SkScalar x, SkScalar y,
                                const SkSamplingOptions& sampling, const SkPaint* paint,
                                BitmapPalette palette) {
    this->push<DrawImage>(0, std::move(image), x, y, sampling, paint, palette);
}
void DisplayListData::drawImageRect(sk_sp<const SkImage> image, const SkRect* src,
                                    const SkRect& dst, const SkSamplingOptions& sampling,
                                    const SkPaint* paint, SkCanvas::SrcRectConstraint constraint,
                                    BitmapPalette palette) {
    this->push<DrawImageRect>(0, std::move(image), src, dst, sampling, paint, constraint, palette);
}
void DisplayListData::drawImageLattice(sk_sp<const SkImage> image, const SkCanvas::Lattice& lattice,
                                       const SkRect& dst, SkFilterMode filter, const SkPaint* paint,
                                       BitmapPalette palette) {
    int xs = lattice.fXCount, ys = lattice.fYCount;
    int fs = lattice.fRectTypes ? (xs + 1) * (ys + 1) : 0;
    size_t bytes = (xs + ys) * sizeof(int) + fs * sizeof(SkCanvas::Lattice::RectType) +
                   fs * sizeof(SkColor);
    SkASSERT(lattice.fBounds);
    void* pod = this->push<DrawImageLattice>(bytes, std::move(image), xs, ys, fs, *lattice.fBounds,
                                             dst, filter, paint, palette);
    copy_v(pod, lattice.fXDivs, xs, lattice.fYDivs, ys, lattice.fColors, fs, lattice.fRectTypes,
           fs);
}

void DisplayListData::drawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
                                   const SkPaint& paint) {
    this->push<DrawTextBlob>(0, blob, x, y, paint);
    mHasText = true;
}

void DisplayListData::drawRippleDrawable(const skiapipeline::RippleDrawableParams& params) {
    this->push<DrawRippleDrawable>(0, params);
}

void DisplayListData::drawPatch(const SkPoint points[12], const SkColor colors[4],
                                const SkPoint texs[4], SkBlendMode bmode, const SkPaint& paint) {
    this->push<DrawPatch>(0, points, colors, texs, bmode, paint);
}
void DisplayListData::drawPoints(SkCanvas::PointMode mode, size_t count, const SkPoint points[],
                                 const SkPaint& paint) {
    void* pod = this->push<DrawPoints>(count * sizeof(SkPoint), mode, count, paint);
    copy_v(pod, points, count);
}
void DisplayListData::drawVertices(const SkVertices* vert, SkBlendMode mode, const SkPaint& paint) {
    this->push<DrawVertices>(0, vert, mode, paint);
}
void DisplayListData::drawAtlas(const SkImage* atlas, const SkRSXform xforms[], const SkRect texs[],
                                const SkColor colors[], int count, SkBlendMode xfermode,
                                const SkSamplingOptions& sampling, const SkRect* cull,
                                const SkPaint* paint) {
    size_t bytes = count * (sizeof(SkRSXform) + sizeof(SkRect));
    if (colors) {
        bytes += count * sizeof(SkColor);
    }
    void* pod = this->push<DrawAtlas>(bytes, atlas, count, xfermode, sampling, cull, paint,
                                      colors != nullptr);
    copy_v(pod, xforms, count, texs, count, colors, colors ? count : 0);
}
void DisplayListData::drawShadowRec(const SkPath& path, const SkDrawShadowRec& rec) {
    this->push<DrawShadowRec>(0, path, rec);
}
void DisplayListData::drawVectorDrawable(VectorDrawableRoot* tree) {
    this->push<DrawVectorDrawable>(0, tree);
}
void DisplayListData::drawWebView(skiapipeline::FunctorDrawable* drawable) {
    this->push<DrawWebView>(0, drawable);
}

typedef void (*draw_fn)(const void*, SkCanvas*, const SkMatrix&);
typedef void (*void_fn)(const void*);
typedef void (*color_transform_fn)(const void*, ColorTransform);

// All ops implement draw().
#define X(T)                                                    \
    [](const void* op, SkCanvas* c, const SkMatrix& original) { \
        ((const T*)op)->draw(c, original);                      \
    },
static const draw_fn draw_fns[] = {
#include "DisplayListOps.in"
};
#undef X

// Most state ops (matrix, clip, save, restore) have a trivial destructor.
#define X(T)                                                                                 \
    !std::is_trivially_destructible<T>::value ? [](const void* op) { ((const T*)op)->~T(); } \
                                              : (void_fn) nullptr,

static const void_fn dtor_fns[] = {
#include "DisplayListOps.in"
};
#undef X

void DisplayListData::draw(SkCanvas* canvas) const {
    SkAutoCanvasRestore acr(canvas, false);
    this->map(draw_fns, canvas, canvas->getTotalMatrix());
}

DisplayListData::~DisplayListData() {
    this->reset();
}

void DisplayListData::reset() {
    this->map(dtor_fns);

    // Leave fBytes and fReserved alone.
    fUsed = 0;
}

template <class T>
using has_paint_helper = decltype(std::declval<T>().paint);

template <class T>
constexpr bool has_paint = std::experimental::is_detected_v<has_paint_helper, T>;

template <class T>
using has_palette_helper = decltype(std::declval<T>().palette);

template <class T>
constexpr bool has_palette = std::experimental::is_detected_v<has_palette_helper, T>;

template <class T>
constexpr color_transform_fn colorTransformForOp() {
    if
        constexpr(has_paint<T> && has_palette<T>) {
            // It's a bitmap
            return [](const void* opRaw, ColorTransform transform) {
                // TODO: We should be const. Or not. Or just use a different map
                // Unclear, but this is the quick fix
                const T* op = reinterpret_cast<const T*>(opRaw);
                transformPaint(transform, const_cast<SkPaint*>(&(op->paint)), op->palette);
            };
        }
    else if
        constexpr(has_paint<T>) {
            return [](const void* opRaw, ColorTransform transform) {
                // TODO: We should be const. Or not. Or just use a different map
                // Unclear, but this is the quick fix
                const T* op = reinterpret_cast<const T*>(opRaw);
                transformPaint(transform, const_cast<SkPaint*>(&(op->paint)));
            };
        }
    else {
        return nullptr;
    }
}

template<>
constexpr color_transform_fn colorTransformForOp<DrawTextBlob>() {
    return [](const void *opRaw, ColorTransform transform) {
        const DrawTextBlob *op = reinterpret_cast<const DrawTextBlob*>(opRaw);
        switch (op->drawTextBlobMode) {
        case DrawTextBlobMode::HctOutline:
            const_cast<SkPaint&>(op->paint).setColor(SK_ColorBLACK);
            break;
        case DrawTextBlobMode::HctInner:
            const_cast<SkPaint&>(op->paint).setColor(SK_ColorWHITE);
            break;
        default:
            transformPaint(transform, const_cast<SkPaint*>(&(op->paint)));
            break;
        }
    };
}

template <>
constexpr color_transform_fn colorTransformForOp<DrawRippleDrawable>() {
    return [](const void* opRaw, ColorTransform transform) {
        const DrawRippleDrawable* op = reinterpret_cast<const DrawRippleDrawable*>(opRaw);
        // Ripple drawable needs to contrast against the background, so we need the inverse color.
        SkColor color = transformColorInverse(transform, op->mParams.color);
        const_cast<DrawRippleDrawable*>(op)->mParams.color = color;
    };
}

#define X(T) colorTransformForOp<T>(),
static const color_transform_fn color_transform_fns[] = {
#include "DisplayListOps.in"
};
#undef X

void DisplayListData::applyColorTransform(ColorTransform transform) {
    this->map(color_transform_fns, transform);
}

RecordingCanvas::RecordingCanvas() : INHERITED(1, 1), fDL(nullptr) {}

void RecordingCanvas::reset(DisplayListData* dl, const SkIRect& bounds) {
    this->resetCanvas(bounds.right(), bounds.bottom());
    fDL = dl;
    mClipMayBeComplex = false;
    mSaveCount = mComplexSaveCount = 0;
}

sk_sp<SkSurface> RecordingCanvas::onNewSurface(const SkImageInfo&, const SkSurfaceProps&) {
    return nullptr;
}

void RecordingCanvas::onFlush() {
    fDL->flush();
}

void RecordingCanvas::willSave() {
    mSaveCount++;
    fDL->save();
}
SkCanvas::SaveLayerStrategy RecordingCanvas::getSaveLayerStrategy(const SaveLayerRec& rec) {
    fDL->saveLayer(rec.fBounds, rec.fPaint, rec.fBackdrop, rec.fSaveLayerFlags);
    return SkCanvas::kNoLayer_SaveLayerStrategy;
}
void RecordingCanvas::willRestore() {
    mSaveCount--;
    if (mSaveCount < mComplexSaveCount) {
        mClipMayBeComplex = false;
        mComplexSaveCount = 0;
    }
    fDL->restore();
}

bool RecordingCanvas::onDoSaveBehind(const SkRect* subset) {
    fDL->saveBehind(subset);
    return false;
}

void RecordingCanvas::didConcat44(const SkM44& m) {
    fDL->concat(m);
}
void RecordingCanvas::didSetM44(const SkM44& matrix) {
    fDL->setMatrix(matrix);
}
void RecordingCanvas::didScale(SkScalar sx, SkScalar sy) {
    fDL->scale(sx, sy);
}
void RecordingCanvas::didTranslate(SkScalar dx, SkScalar dy) {
    fDL->translate(dx, dy);
}

void RecordingCanvas::onClipRect(const SkRect& rect, SkClipOp op, ClipEdgeStyle style) {
    fDL->clipRect(rect, op, style == kSoft_ClipEdgeStyle);
    if (!getTotalMatrix().isScaleTranslate()) {
        setClipMayBeComplex();
    }
    this->INHERITED::onClipRect(rect, op, style);
}
void RecordingCanvas::onClipRRect(const SkRRect& rrect, SkClipOp op, ClipEdgeStyle style) {
    if (rrect.getType() > SkRRect::kRect_Type || !getTotalMatrix().isScaleTranslate()) {
        setClipMayBeComplex();
    }
    fDL->clipRRect(rrect, op, style == kSoft_ClipEdgeStyle);
    this->INHERITED::onClipRRect(rrect, op, style);
}
void RecordingCanvas::onClipPath(const SkPath& path, SkClipOp op, ClipEdgeStyle style) {
    setClipMayBeComplex();
    fDL->clipPath(path, op, style == kSoft_ClipEdgeStyle);
    this->INHERITED::onClipPath(path, op, style);
}
void RecordingCanvas::onClipRegion(const SkRegion& region, SkClipOp op) {
    if (region.isComplex() || !getTotalMatrix().isScaleTranslate()) {
        setClipMayBeComplex();
    }
    fDL->clipRegion(region, op);
    this->INHERITED::onClipRegion(region, op);
}

void RecordingCanvas::onDrawPaint(const SkPaint& paint) {
    fDL->drawPaint(paint);
}
void RecordingCanvas::onDrawBehind(const SkPaint& paint) {
    fDL->drawBehind(paint);
}
void RecordingCanvas::onDrawPath(const SkPath& path, const SkPaint& paint) {
    fDL->drawPath(path, paint);
}
void RecordingCanvas::onDrawRect(const SkRect& rect, const SkPaint& paint) {
    fDL->drawRect(rect, paint);
}
void RecordingCanvas::onDrawRegion(const SkRegion& region, const SkPaint& paint) {
    fDL->drawRegion(region, paint);
}
void RecordingCanvas::onDrawOval(const SkRect& oval, const SkPaint& paint) {
    fDL->drawOval(oval, paint);
}
void RecordingCanvas::onDrawArc(const SkRect& oval, SkScalar startAngle, SkScalar sweepAngle,
                                bool useCenter, const SkPaint& paint) {
    fDL->drawArc(oval, startAngle, sweepAngle, useCenter, paint);
}
void RecordingCanvas::onDrawRRect(const SkRRect& rrect, const SkPaint& paint) {
    fDL->drawRRect(rrect, paint);
}
void RecordingCanvas::onDrawDRRect(const SkRRect& out, const SkRRect& in, const SkPaint& paint) {
    fDL->drawDRRect(out, in, paint);
}

void RecordingCanvas::onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) {
    fDL->drawDrawable(drawable, matrix);
}
void RecordingCanvas::onDrawPicture(const SkPicture* picture, const SkMatrix* matrix,
                                    const SkPaint* paint) {
    fDL->drawPicture(picture, matrix, paint);
}
void RecordingCanvas::onDrawAnnotation(const SkRect& rect, const char key[], SkData* val) {
    fDL->drawAnnotation(rect, key, val);
}

void RecordingCanvas::onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
                                     const SkPaint& paint) {
    fDL->drawTextBlob(blob, x, y, paint);
}

void RecordingCanvas::drawRippleDrawable(const skiapipeline::RippleDrawableParams& params) {
    fDL->drawRippleDrawable(params);
}

void RecordingCanvas::drawImage(const sk_sp<SkImage>& image, SkScalar x, SkScalar y,
                                const SkSamplingOptions& sampling, const SkPaint* paint,
                                BitmapPalette palette) {
    fDL->drawImage(image, x, y, sampling, paint, palette);
}

void RecordingCanvas::drawImageRect(const sk_sp<SkImage>& image, const SkRect& src,
                                    const SkRect& dst, const SkSamplingOptions& sampling,
                                    const SkPaint* paint, SrcRectConstraint constraint,
                                    BitmapPalette palette) {
    fDL->drawImageRect(image, &src, dst, sampling, paint, constraint, palette);
}

void RecordingCanvas::drawImageLattice(const sk_sp<SkImage>& image, const Lattice& lattice,
                                       const SkRect& dst, SkFilterMode filter, const SkPaint* paint,
                                       BitmapPalette palette) {
    if (!image || dst.isEmpty()) {
        return;
    }

    SkIRect bounds;
    Lattice latticePlusBounds = lattice;
    if (!latticePlusBounds.fBounds) {
        bounds = SkIRect::MakeWH(image->width(), image->height());
        latticePlusBounds.fBounds = &bounds;
    }

    if (SkLatticeIter::Valid(image->width(), image->height(), latticePlusBounds)) {
        fDL->drawImageLattice(image, latticePlusBounds, dst, filter, paint, palette);
    } else {
        SkSamplingOptions sampling(filter, SkMipmapMode::kNone);
        fDL->drawImageRect(image, nullptr, dst, sampling, paint, kFast_SrcRectConstraint, palette);
    }
}

void RecordingCanvas::onDrawImage2(const SkImage* img, SkScalar x, SkScalar y,
                                   const SkSamplingOptions& sampling, const SkPaint* paint) {
    fDL->drawImage(sk_ref_sp(img), x, y, sampling, paint, BitmapPalette::Unknown);
}

void RecordingCanvas::onDrawImageRect2(const SkImage* img, const SkRect& src, const SkRect& dst,
                                       const SkSamplingOptions& sampling, const SkPaint* paint,
                                       SrcRectConstraint constraint) {
    fDL->drawImageRect(sk_ref_sp(img), &src, dst, sampling, paint, constraint,
                       BitmapPalette::Unknown);
}

void RecordingCanvas::onDrawImageLattice2(const SkImage* img, const SkCanvas::Lattice& lattice,
                                          const SkRect& dst, SkFilterMode filter,
                                          const SkPaint* paint) {
    fDL->drawImageLattice(sk_ref_sp(img), lattice, dst, filter, paint, BitmapPalette::Unknown);
}

void RecordingCanvas::onDrawPatch(const SkPoint cubics[12], const SkColor colors[4],
                                  const SkPoint texCoords[4], SkBlendMode bmode,
                                  const SkPaint& paint) {
    fDL->drawPatch(cubics, colors, texCoords, bmode, paint);
}
void RecordingCanvas::onDrawPoints(SkCanvas::PointMode mode, size_t count, const SkPoint pts[],
                                   const SkPaint& paint) {
    fDL->drawPoints(mode, count, pts, paint);
}
void RecordingCanvas::onDrawVerticesObject(const SkVertices* vertices,
                                           SkBlendMode mode, const SkPaint& paint) {
    fDL->drawVertices(vertices, mode, paint);
}
void RecordingCanvas::onDrawAtlas2(const SkImage* atlas, const SkRSXform xforms[],
                                   const SkRect texs[], const SkColor colors[], int count,
                                   SkBlendMode bmode, const SkSamplingOptions& sampling,
                                   const SkRect* cull, const SkPaint* paint) {
    fDL->drawAtlas(atlas, xforms, texs, colors, count, bmode, sampling, cull, paint);
}
void RecordingCanvas::onDrawShadowRec(const SkPath& path, const SkDrawShadowRec& rec) {
    fDL->drawShadowRec(path, rec);
}

void RecordingCanvas::drawVectorDrawable(VectorDrawableRoot* tree) {
    fDL->drawVectorDrawable(tree);
}

void RecordingCanvas::drawWebView(skiapipeline::FunctorDrawable* drawable) {
    fDL->drawWebView(drawable);
}

}  // namespace uirenderer
}  // namespace android
