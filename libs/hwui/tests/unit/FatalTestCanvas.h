/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <SkCanvas.h>
#include <gtest/gtest.h>

namespace {

class TestCanvasBase : public SkCanvas {
public:
    TestCanvasBase(int width, int height) : SkCanvas(width, height) {}
    void onDrawAnnotation(const SkRect&, const char key[], SkData* value) {
        ADD_FAILURE() << "onDrawAnnotation not expected in this test";
    }
    void onDrawDRRect(const SkRRect&, const SkRRect&, const SkPaint&) {
        ADD_FAILURE() << "onDrawDRRect not expected in this test";
    }
    void onDrawTextBlob(const SkTextBlob* blob, SkScalar x, SkScalar y, const SkPaint& paint) {
        ADD_FAILURE() << "onDrawTextBlob not expected in this test";
    }
    void onDrawPatch(const SkPoint cubics[12], const SkColor colors[4], const SkPoint texCoords[4],
                     SkBlendMode, const SkPaint& paint) {
        ADD_FAILURE() << "onDrawPatch not expected in this test";
    }
    void onDrawPaint(const SkPaint&) { ADD_FAILURE() << "onDrawPaint not expected in this test"; }
    void onDrawRect(const SkRect&, const SkPaint&) {
        ADD_FAILURE() << "onDrawRect not expected in this test";
    }
    void onDrawRegion(const SkRegion& region, const SkPaint& paint) {
        ADD_FAILURE() << "onDrawRegion not expected in this test";
    }
    void onDrawOval(const SkRect&, const SkPaint&) {
        ADD_FAILURE() << "onDrawOval not expected in this test";
    }
    void onDrawArc(const SkRect&, SkScalar startAngle, SkScalar sweepAngle, bool useCenter,
                   const SkPaint&) {
        ADD_FAILURE() << "onDrawArc not expected in this test";
    }
    void onDrawRRect(const SkRRect&, const SkPaint&) {
        ADD_FAILURE() << "onDrawRRect not expected in this test";
    }
    void onDrawPoints(PointMode, size_t count, const SkPoint pts[], const SkPaint&) {
        ADD_FAILURE() << "onDrawPoints not expected in this test";
    }
    void onDrawVerticesObject(const SkVertices*, SkBlendMode, const SkPaint&) {
        ADD_FAILURE() << "onDrawVertices not expected in this test";
    }
    void onDrawAtlas2(const SkImage*, const SkRSXform[], const SkRect[], const SkColor[], int count,
                      SkBlendMode, const SkSamplingOptions&, const SkRect* cull, const SkPaint*) {
        ADD_FAILURE() << "onDrawAtlas not expected in this test";
    }
    void onDrawPath(const SkPath&, const SkPaint&) {
        ADD_FAILURE() << "onDrawPath not expected in this test";
    }
    void onDrawImage2(const SkImage*, SkScalar dx, SkScalar dy, const SkSamplingOptions&,
                      const SkPaint*) {
        ADD_FAILURE() << "onDrawImage not expected in this test";
    }
    void onDrawImageRect2(const SkImage*, const SkRect&, const SkRect&, const SkSamplingOptions&,
                          const SkPaint*, SrcRectConstraint) {
        ADD_FAILURE() << "onDrawImageRect not expected in this test";
    }
    void onDrawImageLattice2(const SkImage*, const Lattice& lattice, const SkRect& dst,
                             SkFilterMode, const SkPaint*) {
        ADD_FAILURE() << "onDrawImageLattice not expected in this test";
    }
    void onClipRRect(const SkRRect& rrect, SkClipOp, ClipEdgeStyle) {
        ADD_FAILURE() << "onClipRRect not expected in this test";
    }
    void onClipPath(const SkPath& path, SkClipOp, ClipEdgeStyle) {
        ADD_FAILURE() << "onClipPath not expected in this test";
    }
    void onClipRegion(const SkRegion& deviceRgn, SkClipOp) {
        ADD_FAILURE() << "onClipRegion not expected in this test";
    }
    void onDiscard() { ADD_FAILURE() << "onDiscard not expected in this test"; }
    void onDrawPicture(const SkPicture*, const SkMatrix*, const SkPaint*) {
        ADD_FAILURE() << "onDrawPicture not expected in this test";
    }

    int mDrawCounter = 0;  // counts how may draw calls of any kind were made to this canvas
};
}
