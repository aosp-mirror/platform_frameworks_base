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

#include "SkiaUtils.h"

#include <SkBlendMode.h>
#include <SkCanvas.h>
#include <SkDrawable.h>
#include <SkMatrix.h>
#include <utils/RefBase.h>

namespace android {
namespace uirenderer {

class RenderNode;
class RenderProperties;

namespace skiapipeline {

class SkiaDisplayList;

/**
 * This drawable wraps a RenderNode and enables it to be recorded into a list
 * of Skia drawing commands.
 */
class RenderNodeDrawable : public SkDrawable {
public:
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
                                bool inReorderingSection = false);

    ~RenderNodeDrawable();

    /**
     * Draws into the canvas this render node and its children. If the node is marked as a
     * projection receiver then all projected children (excluding direct children) will be drawn
     * last. Any projected node not matching those requirements will not be drawn by this function.
     */
    void forceDraw(SkCanvas* canvas) const;

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

    /**
     * Sets a pointer to a display list of the parent render node. The display list is used when
     * drawing backward projected nodes, when this node is a projection receiver.
     */
    void setProjectedDisplayList(SkiaDisplayList* projectedDisplayList) {
        mProjectedDisplayList = projectedDisplayList;
    }

protected:
    /*
     * Return the (conservative) bounds of what the drawable will draw.
     */
    virtual SkRect onGetBounds() override {
        // We don't want to enable a record time quick reject because the properties
        // of the RenderNode may be updated on subsequent frames.
        return SkRectMakeLargest();
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
     * Walks recursively the display list and draws the content of backward projected nodes.
     *
     * @param canvas used to draw the backward projected nodes
     * @param displayList is a display list that contains a projection receiver
     * @param nestLevel should be always 0. Used to track how far we are from the receiver.
     */
    void drawBackwardsProjectedNodes(SkCanvas* canvas, const SkiaDisplayList& displayList,
                                     int nestLevel = 0) const;

    /**
     * Applies the rendering properties of a view onto a SkCanvas.
     */
    static void setViewProperties(const RenderProperties& properties, SkCanvas* canvas,
                                  float* alphaMultiplier, bool ignoreLayer = false);

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

    /*
     * True if the render node is in a reordering section
     */
    bool mInReorderingSection;

    /*
     *  Draw the content into a canvas, depending on the render node layer type and mComposeLayer.
     */
    void drawContent(SkCanvas* canvas) const;

    /*
     * display list that is searched for any render nodes with getProjectBackwards==true
     */
    SkiaDisplayList* mProjectedDisplayList = nullptr;

    /**
     * Allow BackdropFilterDrawable to apply same render properties onto SkCanvas.
     */
    friend class BackdropFilterDrawable;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
