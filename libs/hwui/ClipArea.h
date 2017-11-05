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

#include "Matrix.h"
#include "Rect.h"
#include "utils/Pair.h"

#include <SkRegion.h>

namespace android {
namespace uirenderer {

class LinearAllocator;

Rect transformAndCalculateBounds(const Rect& r, const Matrix4& transform);

class TransformedRectangle {
public:
    TransformedRectangle();
    TransformedRectangle(const Rect& bounds, const Matrix4& transform);

    bool canSimplyIntersectWith(const TransformedRectangle& other) const;
    void intersectWith(const TransformedRectangle& other);

    bool isEmpty() const;

    const Rect& getBounds() const { return mBounds; }

    Rect transformedBounds() const {
        Rect transformedBounds(transformAndCalculateBounds(mBounds, mTransform));
        return transformedBounds;
    }

    const Matrix4& getTransform() const { return mTransform; }

    void transform(const Matrix4& transform) {
        Matrix4 t;
        t.loadMultiply(transform, mTransform);
        mTransform = t;
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
    void transform(const Matrix4& transform);

    SkRegion convertToRegion(const SkRegion& clip) const;
    Rect calculateBounds() const;

    enum { kMaxTransformedRectangles = 5 };

private:
    int mTransformedRectanglesCount;
    TransformedRectangle mTransformedRectangles[kMaxTransformedRectangles];
};

enum class ClipMode {
    Rectangle,
    RectangleList,

    // region and path - intersected. if either is empty, don't use
    Region
};

struct ClipBase {
    explicit ClipBase(ClipMode mode) : mode(mode) {}
    explicit ClipBase(const Rect& rect) : mode(ClipMode::Rectangle), rect(rect) {}
    const ClipMode mode;
    bool intersectWithRoot = false;
    // Bounds of the clipping area, used to define the scissor, and define which
    // portion of the stencil is updated/used
    Rect rect;

    void dump() const;
};

struct ClipRect : ClipBase {
    explicit ClipRect(const Rect& rect) : ClipBase(rect) {}
};

struct ClipRectList : ClipBase {
    explicit ClipRectList(const RectangleList& rectList)
            : ClipBase(ClipMode::RectangleList), rectList(rectList) {}
    RectangleList rectList;
};

struct ClipRegion : ClipBase {
    explicit ClipRegion(const SkRegion& region) : ClipBase(ClipMode::Region), region(region) {}
    ClipRegion() : ClipBase(ClipMode::Region) {}
    SkRegion region;
};

class ClipArea {
public:
    ClipArea();

    void setViewportDimensions(int width, int height);

    bool isEmpty() const { return mClipRect.isEmpty(); }

    void setEmpty();
    void setClip(float left, float top, float right, float bottom);
    void clipRectWithTransform(const Rect& r, const mat4* transform, SkRegion::Op op);
    void clipPathWithTransform(const SkPath& path, const mat4* transform, SkRegion::Op op);

    const Rect& getClipRect() const { return mClipRect; }

    const SkRegion& getClipRegion() const { return mClipRegion; }

    const RectangleList& getRectangleList() const { return mRectangleList; }

    bool isRegion() const { return ClipMode::Region == mMode; }

    bool isSimple() const { return mMode == ClipMode::Rectangle; }

    bool isRectangleList() const { return mMode == ClipMode::RectangleList; }

    WARN_UNUSED_RESULT const ClipBase* serializeClip(LinearAllocator& allocator);
    WARN_UNUSED_RESULT const ClipBase* serializeIntersectedClip(
            LinearAllocator& allocator, const ClipBase* recordedClip,
            const Matrix4& recordedClipTransform);
    void applyClip(const ClipBase* recordedClip, const Matrix4& recordedClipTransform);

    static void applyTransformToRegion(const Matrix4& transform, SkRegion* region);

private:
    void enterRectangleMode();
    void rectangleModeClipRectWithTransform(const Rect& r, const mat4* transform, SkRegion::Op op);

    void enterRectangleListMode();
    void rectangleListModeClipRectWithTransform(const Rect& r, const mat4* transform,
                                                SkRegion::Op op);

    void enterRegionModeFromRectangleMode();
    void enterRegionModeFromRectangleListMode();
    void enterRegionMode();
    void regionModeClipRectWithTransform(const Rect& r, const mat4* transform, SkRegion::Op op);

    void clipRegion(const SkRegion& region, SkRegion::Op op);
    void ensureClipRegion();
    void onClipRegionUpdated();

    // Called by every state modifying public method.
    void onClipUpdated() {
        mPostViewportClipObserved = true;
        mLastSerialization = nullptr;
        mLastResolutionResult = nullptr;
    }

    SkRegion createViewportRegion() { return SkRegion(mViewportBounds.toSkIRect()); }

    void regionFromPath(const SkPath& path, SkRegion& pathAsRegion) {
        // TODO: this should not mask every path to the viewport - this makes it impossible to use
        // paths to clip to larger areas (which is valid e.g. with SkRegion::kReplace_Op)
        pathAsRegion.setPath(path, createViewportRegion());
    }

    ClipMode mMode;
    bool mPostViewportClipObserved = false;
    bool mReplaceOpObserved = false;

    /**
     * If mLastSerialization is non-null, it represents an already serialized copy
     * of the current clip state. If null, it has not been computed.
     */
    const ClipBase* mLastSerialization = nullptr;

    /**
     * This pair of pointers is a single entry cache of most recently seen
     */
    const ClipBase* mLastResolutionResult = nullptr;
    const ClipBase* mLastResolutionClip = nullptr;
    Matrix4 mLastResolutionTransform;

    Rect mViewportBounds;
    Rect mClipRect;
    SkRegion mClipRegion;
    RectangleList mRectangleList;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* CLIPAREA_H_ */
