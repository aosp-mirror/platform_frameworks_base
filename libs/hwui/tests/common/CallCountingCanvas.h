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

#include <SkCanvasVirtualEnforcer.h>
#include <SkNoDrawCanvas.h>

enum class SkBlendMode;

namespace android {
namespace uirenderer {
namespace test {

class CallCountingCanvas final : public SkCanvasVirtualEnforcer<SkNoDrawCanvas> {
private:
    int START_MARKER;
public:
    CallCountingCanvas() : SkCanvasVirtualEnforcer<SkNoDrawCanvas>(1, 1) {}

    int sumTotalDrawCalls() {
        // Dirty hack assumes we're nothing but ints between START_MARKET and END_MARKER
        int* cur = &START_MARKER + 1;
        int* end = &END_MARKER;
        int sum = 0;
        while (cur != end) {
            sum += *cur;
            cur++;
        }
        return sum;
    }

    int drawPaintCount = 0;
    void onDrawPaint(const SkPaint& paint) override {
        drawPaintCount++;
    }

    int drawBehindCount = 0;
    void onDrawBehind(const SkPaint&) override {
        drawBehindCount++;
    }

    int drawRectCount = 0;
    void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
        drawRectCount++;
    }

    int drawRRectCount = 0;
    void onDrawRRect(const SkRRect& rrect, const SkPaint& paint) override {
        drawRRectCount++;
    }

    int drawDRRectCount = 0;
    void onDrawDRRect(const SkRRect& outer, const SkRRect& inner,
                      const SkPaint& paint) override {
        drawDRRectCount++;
    }

    int drawOvalCount = 0;
    void onDrawOval(const SkRect& rect, const SkPaint& paint) override {
        drawOvalCount++;
    }

    int drawArcCount = 0;
    void onDrawArc(const SkRect& rect, SkScalar startAngle, SkScalar sweepAngle, bool useCenter,
                   const SkPaint& paint) override {
        drawArcCount++;
    }

    int drawPathCount = 0;
    void onDrawPath(const SkPath& path, const SkPaint& paint) override {
        drawPathCount++;
    }

    int drawRegionCount = 0;
    void onDrawRegion(const SkRegion& region, const SkPaint& paint) override {
        drawRegionCount++;
    }

    int drawTextBlobCount = 0;
    void onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y,
                        const SkPaint& paint) override {
        drawTextBlobCount++;
    }

    int drawPatchCount = 0;
    void onDrawPatch(const SkPoint cubics[12], const SkColor colors[4],
                     const SkPoint texCoords[4], SkBlendMode mode,
                     const SkPaint& paint) override {
        drawPatchCount++;
    }

    int drawPoints = 0;
    void onDrawPoints(SkCanvas::PointMode mode, size_t count, const SkPoint pts[],
                      const SkPaint& paint) override {
        drawPoints++;
    }

    int drawImageRectCount = 0;
    void onDrawImageRect2(const SkImage*, const SkRect&, const SkRect&, const SkSamplingOptions&,
                          const SkPaint*, SkCanvas::SrcRectConstraint) override {
        drawImageRectCount++;
    }

    int drawImageLatticeCount = 0;
    void onDrawImageLattice2(const SkImage* image, const SkCanvas::Lattice& lattice,
                             const SkRect& dst, SkFilterMode, const SkPaint* paint) override {
        drawImageLatticeCount++;
    }

    int drawAtlasCount = 0;
    void onDrawAtlas2(const SkImage* atlas, const SkRSXform xform[], const SkRect rect[],
                      const SkColor colors[], int count, SkBlendMode mode, const SkSamplingOptions&,
                      const SkRect* cull, const SkPaint* paint) override {
        drawAtlasCount++;
    }

    int drawAnnotationCount = 0;
    void onDrawAnnotation(const SkRect& rect, const char key[], SkData* value) override {
        drawAnnotationCount++;
    }

    int drawShadowRecCount = 0;
    void onDrawShadowRec(const SkPath&, const SkDrawShadowRec&) override {
        drawShadowRecCount++;
    }

    int drawDrawableCount = 0;
    void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override {
        drawDrawableCount++;
    }

    int drawPictureCount = 0;
    void onDrawPicture(const SkPicture* picture, const SkMatrix* matrix,
                       const SkPaint* paint) override {
        drawPictureCount++;
    }

    int drawVerticesCount = 0;
    void onDrawVerticesObject (const SkVertices *vertices, SkBlendMode mode,
                        const SkPaint &paint) override {
        drawVerticesCount++;
    }

private:
    int END_MARKER;
};

} /* namespace test */
} /* namespace uirenderer */
} /* namespace android */
