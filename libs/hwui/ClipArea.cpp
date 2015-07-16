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
#include "ClipArea.h"

#include <SkPath.h>
#include <limits>

#include "Rect.h"

namespace android {
namespace uirenderer {

static bool intersect(Rect& r, const Rect& r2) {
    bool hasIntersection = r.intersect(r2);
    if (!hasIntersection) {
        r.setEmpty();
    }
    return hasIntersection;
}

static void handlePoint(Rect& transformedBounds, const Matrix4& transform, float x, float y) {
    Vertex v;
    v.x = x;
    v.y = y;
    transform.mapPoint(v.x, v.y);
    transformedBounds.expandToCoverVertex(v.x, v.y);
}

Rect transformAndCalculateBounds(const Rect& r, const Matrix4& transform) {
    const float kMinFloat = std::numeric_limits<float>::lowest();
    const float kMaxFloat = std::numeric_limits<float>::max();
    Rect transformedBounds = { kMaxFloat, kMaxFloat, kMinFloat, kMinFloat };
    handlePoint(transformedBounds, transform, r.left, r.top);
    handlePoint(transformedBounds, transform, r.right, r.top);
    handlePoint(transformedBounds, transform, r.left, r.bottom);
    handlePoint(transformedBounds, transform, r.right, r.bottom);
    return transformedBounds;
}

/*
 * TransformedRectangle
 */

TransformedRectangle::TransformedRectangle() {
}

TransformedRectangle::TransformedRectangle(const Rect& bounds,
        const Matrix4& transform)
        : mBounds(bounds)
        , mTransform(transform) {
}

bool TransformedRectangle::canSimplyIntersectWith(
        const TransformedRectangle& other) const {

    return mTransform == other.mTransform;
}

bool TransformedRectangle::intersectWith(const TransformedRectangle& other) {
    Rect translatedBounds(other.mBounds);
    return intersect(mBounds, translatedBounds);
}

bool TransformedRectangle::isEmpty() const {
    return mBounds.isEmpty();
}

/*
 * RectangleList
 */

RectangleList::RectangleList()
        : mTransformedRectanglesCount(0) {
}

bool RectangleList::isEmpty() const {
    if (mTransformedRectanglesCount < 1) {
        return true;
    }

    for (int i = 0; i < mTransformedRectanglesCount; i++) {
        if (mTransformedRectangles[i].isEmpty()) {
            return true;
        }
    }
    return false;
}

int RectangleList::getTransformedRectanglesCount() const {
    return mTransformedRectanglesCount;
}

const TransformedRectangle& RectangleList::getTransformedRectangle(int i) const {
    return mTransformedRectangles[i];
}

void RectangleList::setEmpty() {
    mTransformedRectanglesCount = 0;
}

void RectangleList::set(const Rect& bounds, const Matrix4& transform) {
    mTransformedRectanglesCount = 1;
    mTransformedRectangles[0] = TransformedRectangle(bounds, transform);
}

bool RectangleList::intersectWith(const Rect& bounds,
        const Matrix4& transform) {
    TransformedRectangle newRectangle(bounds, transform);

    // Try to find a rectangle with a compatible transformation
    int index = 0;
    for (; index < mTransformedRectanglesCount; index++) {
        TransformedRectangle& tr(mTransformedRectangles[index]);
        if (tr.canSimplyIntersectWith(newRectangle)) {
            tr.intersectWith(newRectangle);
            return true;
        }
    }

    // Add it to the list if there is room
    if (index < kMaxTransformedRectangles) {
        mTransformedRectangles[index] = newRectangle;
        mTransformedRectanglesCount += 1;
        return true;
    }

    // This rectangle list is full
    return false;
}

Rect RectangleList::calculateBounds() const {
    Rect bounds;
    for (int index = 0; index < mTransformedRectanglesCount; index++) {
        const TransformedRectangle& tr(mTransformedRectangles[index]);
        if (index == 0) {
            bounds = tr.transformedBounds();
        } else {
            bounds.intersect(tr.transformedBounds());
        }
    }
    return bounds;
}

static SkPath pathFromTransformedRectangle(const Rect& bounds,
        const Matrix4& transform) {
    SkPath rectPath;
    SkPath rectPathTransformed;
    rectPath.addRect(bounds.left, bounds.top, bounds.right, bounds.bottom);
    SkMatrix skTransform;
    transform.copyTo(skTransform);
    rectPath.transform(skTransform, &rectPathTransformed);
    return rectPathTransformed;
}

SkRegion RectangleList::convertToRegion(const SkRegion& clip) const {
    SkRegion rectangleListAsRegion;
    for (int index = 0; index < mTransformedRectanglesCount; index++) {
        const TransformedRectangle& tr(mTransformedRectangles[index]);
        SkPath rectPathTransformed = pathFromTransformedRectangle(
                tr.getBounds(), tr.getTransform());
        if (index == 0) {
            rectangleListAsRegion.setPath(rectPathTransformed, clip);
        } else {
            SkRegion rectRegion;
            rectRegion.setPath(rectPathTransformed, clip);
            rectangleListAsRegion.op(rectRegion, SkRegion::kIntersect_Op);
        }
    }
    return rectangleListAsRegion;
}

/*
 * ClipArea
 */

ClipArea::ClipArea()
        : mMode(kModeRectangle) {
}

/*
 * Interface
 */

void ClipArea::setViewportDimensions(int width, int height) {
    mViewportBounds.set(0, 0, width, height);
    mClipRect = mViewportBounds;
}

void ClipArea::setEmpty() {
    mMode = kModeRectangle;
    mClipRect.setEmpty();
    mClipRegion.setEmpty();
    mRectangleList.setEmpty();
}

void ClipArea::setClip(float left, float top, float right, float bottom) {
    mMode = kModeRectangle;
    mClipRect.set(left, top, right, bottom);
    mClipRegion.setEmpty();
}

bool ClipArea::clipRectWithTransform(float left, float top, float right,
        float bottom, const mat4* transform, SkRegion::Op op) {
    Rect r(left, top, right, bottom);
    return clipRectWithTransform(r, transform, op);
}

bool ClipArea::clipRectWithTransform(const Rect& r, const mat4* transform,
        SkRegion::Op op) {
    switch (mMode) {
    case kModeRectangle:
        return rectangleModeClipRectWithTransform(r, transform, op);
    case kModeRectangleList:
        return rectangleListModeClipRectWithTransform(r, transform, op);
    case kModeRegion:
        return regionModeClipRectWithTransform(r, transform, op);
    }
    return false;
}

bool ClipArea::clipRegion(const SkRegion& region, SkRegion::Op op) {
    enterRegionMode();
    mClipRegion.op(region, op);
    onClipRegionUpdated();
    return true;
}

bool ClipArea::clipPathWithTransform(const SkPath& path, const mat4* transform,
        SkRegion::Op op) {
    SkMatrix skTransform;
    transform->copyTo(skTransform);
    SkPath transformed;
    path.transform(skTransform, &transformed);
    SkRegion region;
    regionFromPath(transformed, region);
    return clipRegion(region, op);
}

/*
 * Rectangle mode
 */

void ClipArea::enterRectangleMode() {
    // Entering rectangle mode discards any
    // existing clipping information from the other modes.
    // The only way this occurs is by a clip setting operation.
    mMode = kModeRectangle;
}

bool ClipArea::rectangleModeClipRectWithTransform(const Rect& r,
        const mat4* transform, SkRegion::Op op) {

    if (op == SkRegion::kReplace_Op && transform->rectToRect()) {
        mClipRect = r;
        transform->mapRect(mClipRect);
        return true;
    } else if (op != SkRegion::kIntersect_Op) {
        enterRegionMode();
        return regionModeClipRectWithTransform(r, transform, op);
    }

    if (transform->rectToRect()) {
        Rect transformed(r);
        transform->mapRect(transformed);
        bool hasIntersection = mClipRect.intersect(transformed);
        if (!hasIntersection) {
            mClipRect.setEmpty();
        }
        return true;
    }

    enterRectangleListMode();
    return rectangleListModeClipRectWithTransform(r, transform, op);
}

bool ClipArea::rectangleModeClipRectWithTransform(float left, float top,
        float right, float bottom, const mat4* transform, SkRegion::Op op) {
    Rect r(left, top, right, bottom);
    bool result = rectangleModeClipRectWithTransform(r, transform, op);
    mClipRect = mRectangleList.calculateBounds();
    return result;
}

/*
 * RectangleList mode implementation
 */

void ClipArea::enterRectangleListMode() {
    // Is is only legal to enter rectangle list mode from
    // rectangle mode, since rectangle list mode cannot represent
    // all clip areas that can be represented by a region.
    ALOG_ASSERT(mMode == kModeRectangle);
    mMode = kModeRectangleList;
    mRectangleList.set(mClipRect, Matrix4::identity());
}

bool ClipArea::rectangleListModeClipRectWithTransform(const Rect& r,
        const mat4* transform, SkRegion::Op op) {
    if (op != SkRegion::kIntersect_Op
            || !mRectangleList.intersectWith(r, *transform)) {
        enterRegionMode();
        return regionModeClipRectWithTransform(r, transform, op);
    }
    return true;
}

bool ClipArea::rectangleListModeClipRectWithTransform(float left, float top,
        float right, float bottom, const mat4* transform, SkRegion::Op op) {
    Rect r(left, top, right, bottom);
    return rectangleListModeClipRectWithTransform(r, transform, op);
}

/*
 * Region mode implementation
 */

void ClipArea::enterRegionMode() {
    Mode oldMode = mMode;
    mMode = kModeRegion;
    if (oldMode != kModeRegion) {
        if (oldMode == kModeRectangle) {
            mClipRegion.setRect(mClipRect.left, mClipRect.top,
                    mClipRect.right, mClipRect.bottom);
        } else {
            mClipRegion = mRectangleList.convertToRegion(createViewportRegion());
            onClipRegionUpdated();
        }
    }
}

bool ClipArea::regionModeClipRectWithTransform(const Rect& r,
        const mat4* transform, SkRegion::Op op) {
    SkPath transformedRect = pathFromTransformedRectangle(r, *transform);
    SkRegion transformedRectRegion;
    regionFromPath(transformedRect, transformedRectRegion);
    mClipRegion.op(transformedRectRegion, op);
    onClipRegionUpdated();
    return true;
}

bool ClipArea::regionModeClipRectWithTransform(float left, float top,
        float right, float bottom, const mat4* transform, SkRegion::Op op) {
    return regionModeClipRectWithTransform(Rect(left, top, right, bottom),
            transform, op);
}

void ClipArea::onClipRegionUpdated() {
    if (!mClipRegion.isEmpty()) {
        mClipRect.set(mClipRegion.getBounds());

        if (mClipRegion.isRect()) {
            mClipRegion.setEmpty();
            enterRectangleMode();
        }
    } else {
        mClipRect.setEmpty();
    }
}

} /* namespace uirenderer */
} /* namespace android */
