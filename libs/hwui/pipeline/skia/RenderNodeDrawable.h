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
#include <SkDrawable.h>
#include <SkMatrix.h>
#include <utils/RefBase.h>

namespace android {
namespace uirenderer {

class RenderNode;
class RenderProperties;

namespace skiapipeline {

/**
 * This drawable wraps a RenderNode and enables it to be recorded into a list
 * of Skia drawing commands.
 */
class RenderNodeDrawable : public SkDrawable {
public:
    /**
     * This struct contains a pointer to a node that is to be
     * projected into the drawing order of its closest ancestor
     * (excluding its parent) that is marked as a projection
     * receiver. The matrix is used to ensure that the node is
     * drawn with same matrix as it would have prior to projection.
     */
    struct ProjectedChild {
        const RenderNodeDrawable* node;
        const SkMatrix matrix;
    };

    /**
     * Creates a new RenderNodeDrawable backed by a render node.
     *
     * @param node that has to be drawn
     * @param canvas is a recording canvas used to extract its matrix
     * @param composeLayer if the node's layer type is RenderLayer this flag determines whether
     *      we should draw into the contents of the layer or compose the existing contents of the
     *      layer into the canvas.
     */
    explicit RenderNodeDrawable(RenderNode* node, SkCanvas* canvas, bool composeLayer = true,
            bool inReorderingSection = false)
            : mRenderNode(node)
            , mRecordedTransform(canvas->getTotalMatrix())
            , mComposeLayer(composeLayer)
            , mInReorderingSection(inReorderingSection) {}

    /**
     * Draws into the canvas this render node and its children. If the node is marked as a
     * projection receiver then all projected children (excluding direct children) will be drawn
     * last. Any projected node not matching those requirements will not be drawn by this function.
     */
    void forceDraw(SkCanvas* canvas);

    /**
     * Returns readonly render properties for this render node.
     */
    const RenderProperties& getNodeProperties() const;

    /**
     * The renderNode (and its properties) that is to be drawn
     */
    RenderNode* getRenderNode() const { return mRenderNode.get(); }

    /**
     * Returns the transform on the canvas at time of recording and is used for
     * computing total transform without rerunning DL contents.
     */
    const SkMatrix& getRecordedMatrix() const { return mRecordedTransform; }

protected:
    /*
     * Return the (conservative) bounds of what the drawable will draw.
     */
    virtual SkRect onGetBounds() override {
        // We don't want to enable a record time quick reject because the properties
        // of the RenderNode may be updated on subsequent frames.
        return SkRect::MakeLargest();
    }
    /**
     * This function draws into a canvas as forceDraw, but does nothing if the render node has a
     * non-zero elevation.
     */
    virtual void onDraw(SkCanvas* canvas) override;

private:
    /*
     * Render node that is wrapped by this class.
     */
    sp<RenderNode> mRenderNode;

    /**
     * Applies the rendering properties of a view onto a SkCanvas.
     */
    static void setViewProperties(const RenderProperties& properties, SkCanvas* canvas,
            float* alphaMultiplier);

    /**
     * Stores transform on the canvas at time of recording and is used for
     * computing total transform without rerunning DL contents.
     */
    const SkMatrix mRecordedTransform;

    /**
     * If mRenderNode's layer type is RenderLayer this flag determines whether we
     * should draw into the contents of the layer or compose the existing contents
     * of the layer into the canvas.
     */
    const bool mComposeLayer;

    /**
     * List to which we will add any projected children we encounter while walking our descendents.
     * This pointer is valid only while the node (including its children) is actively being drawn.
     */
    std::vector<ProjectedChild>* mProjectedChildrenTarget = nullptr;

    /**
     * The value to which we should set our children's mProjectedChildrenTarget. We use two pointers
     * (mProjectedChildrenTarget and mNextProjectedChildrenTarget) because we need to skip over our
     * parent when looking for a projection receiver.
     */
    std::vector<ProjectedChild>* mNextProjectedChildrenTarget = nullptr;

    /*
     * True if the render node is in a reordering section
     */
    bool mInReorderingSection;

    /*
     *  Draw the content into a canvas, depending on the render node layer type and mComposeLayer.
     */
    void drawContent(SkCanvas* canvas) const;
};

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
