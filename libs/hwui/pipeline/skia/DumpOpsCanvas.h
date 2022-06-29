/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "RenderNode.h"
#include "SkiaDisplayList.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * DumpOpsCanvas prints drawing ops from a SkiaDisplayList into a std::ostream. Children render
 * nodes are walked recursively and their drawing ops are printed as well.
 */
class DumpOpsCanvas : public SkCanvas {
public:
    DumpOpsCanvas(std::ostream& output, int level, const SkiaDisplayList& displayList)
            : mOutput(output)
            , mLevel(level)
            , mDisplayList(displayList)
            , mIdent((level + 1) * 2, ' ') {}

protected:
    void onClipRect(const SkRect& rect, SkClipOp, ClipEdgeStyle) override {
        mOutput << mIdent << "clipRect" << std::endl;
    }

    void onClipRRect(const SkRRect& rrect, SkClipOp, ClipEdgeStyle) override {
        mOutput << mIdent << "clipRRect" << std::endl;
    }

    void onClipPath(const SkPath& path, SkClipOp, ClipEdgeStyle) override {
        mOutput << mIdent << "clipPath" << std::endl;
    }

    void onClipRegion(const SkRegion& deviceRgn, SkClipOp) override {
        mOutput << mIdent << "clipRegion" << std::endl;
    }

    void onResetClip() override { mOutput << mIdent << "resetClip" << std::endl; }

    void onDrawPaint(const SkPaint&) override { mOutput << mIdent << "drawPaint" << std::endl; }

    void onDrawPath(const SkPath&, const SkPaint&) override {
        mOutput << mIdent << "drawPath" << std::endl;
    }

    void onDrawRect(const SkRect&, const SkPaint&) override {
        mOutput << mIdent << "drawRect" << std::endl;
    }

    void onDrawRegion(const SkRegion&, const SkPaint&) override {
        mOutput << mIdent << "drawRegion" << std::endl;
    }

    void onDrawOval(const SkRect&, const SkPaint&) override {
        mOutput << mIdent << "drawOval" << std::endl;
    }

    void onDrawArc(const SkRect&, SkScalar, SkScalar, bool, const SkPaint&) override {
        mOutput << mIdent << "drawArc" << std::endl;
    }

    void onDrawRRect(const SkRRect&, const SkPaint&) override {
        mOutput << mIdent << "drawRRect" << std::endl;
    }

    void onDrawDRRect(const SkRRect&, const SkRRect&, const SkPaint&) override {
        mOutput << mIdent << "drawDRRect" << std::endl;
    }

    void onDrawTextBlob(const SkTextBlob*, SkScalar, SkScalar, const SkPaint&) override {
        mOutput << mIdent << "drawTextBlob" << std::endl;
    }

    void onDrawImage2(const SkImage*, SkScalar dx, SkScalar dy, const SkSamplingOptions&,
                      const SkPaint*) override {
        mOutput << mIdent << "drawImage" << std::endl;
    }

    void onDrawImageRect2(const SkImage*, const SkRect&, const SkRect&, const SkSamplingOptions&,
                          const SkPaint*, SrcRectConstraint) override {
        mOutput << mIdent << "drawImageRect" << std::endl;
    }

    void onDrawImageLattice2(const SkImage*, const Lattice& lattice, const SkRect& dst,
                             SkFilterMode, const SkPaint*) override {
        mOutput << mIdent << "drawImageLattice" << std::endl;
    }

    void onDrawPoints(SkCanvas::PointMode, size_t, const SkPoint[], const SkPaint&) override {
        mOutput << mIdent << "drawPoints" << std::endl;
    }

    void onDrawPicture(const SkPicture*, const SkMatrix*, const SkPaint*) override {
        mOutput << mIdent << "drawPicture" << std::endl;
    }

    void onDrawDrawable(SkDrawable* drawable, const SkMatrix*) override {
        mOutput << mIdent;
        auto renderNodeDrawable = getRenderNodeDrawable(drawable);
        if (nullptr != renderNodeDrawable) {
            mOutput << std::string(mLevel * 2, ' ') << "drawRenderNode";
            renderNodeDrawable->getRenderNode()->output(mOutput, mLevel + 1);
            return;
        }
        auto glFunctorDrawable = getFunctorDrawable(drawable);
        if (nullptr != glFunctorDrawable) {
            mOutput << std::string(mLevel * 2, ' ') << "drawGLFunctorDrawable" << std::endl;
            return;
        }

        mOutput << std::string(mLevel * 2, ' ') << "drawDrawable" << std::endl;
    }

private:
    const RenderNodeDrawable* getRenderNodeDrawable(SkDrawable* drawable) {
        for (auto& child : mDisplayList.mChildNodes) {
            if (drawable == &child) {
                return &child;
            }
        }
        return nullptr;
    }

    FunctorDrawable* getFunctorDrawable(SkDrawable* drawable) {
        for (auto& child : mDisplayList.mChildFunctors) {
            if (drawable == child) {
                return child;
            }
        }
        return nullptr;
    }

    std::ostream& mOutput;
    int mLevel;
    const SkiaDisplayList& mDisplayList;
    std::string mIdent;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
