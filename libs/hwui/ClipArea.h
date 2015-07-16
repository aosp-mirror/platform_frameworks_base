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
#ifndef CLIPAREA_H
#define CLIPAREA_H

#include <SkRegion.h>

#include "Matrix.h"
#include "Rect.h"
#include "utils/Pair.h"

namespace android {
namespace uirenderer {

Rect transformAndCalculateBounds(const Rect& r, const Matrix4& transform);

class TransformedRectangle {
public:
    TransformedRectangle();
    TransformedRectangle(const Rect& bounds, const Matrix4& transform);

    bool canSimplyIntersectWith(const TransformedRectangle& other) const;
    bool intersectWith(const TransformedRectangle& other);

    bool isEmpty() const;

    const Rect& getBounds() const {
        return mBounds;
    }

    Rect transformedBounds() const {
        Rect transformedBounds(transformAndCalculateBounds(mBounds, mTransform));
        return transformedBounds;
    }

    const Matrix4& getTransform() const {
        return mTransform;
    }

private:
    Rect mBounds;
    Matrix4 mTransform;
};

class RectangleList {
public:
    RectangleList();

    bool isEmpty() const;
    int getTransformedRectanglesCount() const;
    const TransformedRectangle& getTransformedRectangle(int i) const;

    void setEmpty();
    void set(const Rect& bounds, const Matrix4& transform);
    bool intersectWith(const Rect& bounds, const Matrix4& transform);

    SkRegion convertToRegion(const SkRegion& clip) const;
    Rect calculateBounds() const;

private:
    enum {
        kMaxTransformedRectangles = 5
    };

    int mTransformedRectanglesCount;
    TransformedRectangle mTransformedRectangles[kMaxTransformedRectangles];
};

class ClipArea {
public:
    ClipArea();

    void setViewportDimensions(int width, int height);

    bool isEmpty() const {
        return mClipRect.isEmpty();
    }

    void setEmpty();
    void setClip(float left, float top, float right, float bottom);
    bool clipRectWithTransform(float left, float top, float right, float bottom,
            const mat4* transform, SkRegion::Op op = SkRegion::kIntersect_Op);
    bool clipRectWithTransform(const Rect& r, const mat4* transform,
            SkRegion::Op op = SkRegion::kIntersect_Op);
    bool clipRegion(const SkRegion& region, SkRegion::Op op = SkRegion::kIntersect_Op);
    bool clipPathWithTransform(const SkPath& path, const mat4* transform,
            SkRegion::Op op);

    const Rect& getClipRect() const {
        return mClipRect;
    }

    const SkRegion& getClipRegion() const {
        return mClipRegion;
    }

    const RectangleList& getRectangleList() const {
        return mRectangleList;
    }

    bool isRegion() const {
        return kModeRegion == mMode;
    }

    bool isSimple() const {
        return mMode == kModeRectangle;
    }

    bool isRectangleList() const {
        return mMode == kModeRectangleList;
    }

private:
    void enterRectangleMode();
    bool rectangleModeClipRectWithTransform(const Rect& r, const mat4* transform, SkRegion::Op op);
    bool rectangleModeClipRectWithTransform(float left, float top, float right,
            float bottom, const mat4* transform, SkRegion::Op op);

    void enterRectangleListMode();
    bool rectangleListModeClipRectWithTransform(float left, float top,
            float right, float bottom, const mat4* transform, SkRegion::Op op);
    bool rectangleListModeClipRectWithTransform(const Rect& r,
            const mat4* transform, SkRegion::Op op);

    void enterRegionModeFromRectangleMode();
    void enterRegionModeFromRectangleListMode();
    void enterRegionMode();
    bool regionModeClipRectWithTransform(const Rect& r, const mat4* transform,
            SkRegion::Op op);
    bool regionModeClipRectWithTransform(float left, float top, float right,
            float bottom, const mat4* transform, SkRegion::Op op);

    void ensureClipRegion();
    void onClipRegionUpdated();
    bool clipRegionOp(float left, float top, float right, float bottom,
            SkRegion::Op op);

    SkRegion createViewportRegion() {
        return SkRegion(mViewportBounds.toSkIRect());
    }

    void regionFromPath(const SkPath& path, SkRegion& pathAsRegion) {
        // TODO: this should not mask every path to the viewport - this makes it impossible to use
        // paths to clip to larger areas (which is valid e.g. with SkRegion::kReplace_Op)
        pathAsRegion.setPath(path, createViewportRegion());
    }

    enum Mode {
        kModeRectangle,
        kModeRegion,
        kModeRectangleList
    };

    Mode mMode;
    Rect mViewportBounds;
    Rect mClipRect;
    SkRegion mClipRegion;
    RectangleList mRectangleList;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* CLIPAREA_H_ */
