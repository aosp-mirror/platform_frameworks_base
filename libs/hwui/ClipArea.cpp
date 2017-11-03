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

#include "utils/LinearAllocator.h"

#include <SkPath.h>
#include <limits>
#include <type_traits>

namespace android {
namespace uirenderer {

static void handlePoint(Rect& transformedBounds, const Matrix4& transform, float x, float y) {
    Vertex v = {x, y};
    transform.mapPoint(v.x, v.y);
    transformedBounds.expandToCover(v.x, v.y);
}

Rect transformAndCalculateBounds(const Rect& r, const Matrix4& transform) {
    const float kMinFloat = std::numeric_limits<float>::lowest();
    const float kMaxFloat = std::numeric_limits<float>::max();
    Rect transformedBounds = {kMaxFloat, kMaxFloat, kMinFloat, kMinFloat};
    handlePoint(transformedBounds, transform, r.left, r.top);
    handlePoint(transformedBounds, transform, r.right, r.top);
    handlePoint(transformedBounds, transform, r.left, r.bottom);
    handlePoint(transformedBounds, transform, r.right, r.bottom);
    return transformedBounds;
}

void ClipBase::dump() const {
    ALOGD("mode %d" RECT_STRING, mode, RECT_ARGS(rect));
}

/*
 * TransformedRectangle
 */

TransformedRectangle::TransformedRectangle() {}

TransformedRectangle::TransformedRectangle(const Rect& bounds, const Matrix4& transform)
        : mBounds(bounds), mTransform(transform) {}

bool TransformedRectangle::canSimplyIntersectWith(const TransformedRectangle& other) const {
    return mTransform == other.mTransform;
}

void TransformedRectangle::intersectWith(const TransformedRectangle& other) {
    mBounds.doIntersect(other.mBounds);
}

bool TransformedRectangle::isEmpty() const {
    return mBounds.isEmpty();
}

/*
 * RectangleList
 */

RectangleList::RectangleList() : mTransformedRectanglesCount(0) {}

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

bool RectangleList::intersectWith(const Rect& bounds, const Matrix4& transform) {
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
            bounds.doIntersect(tr.transformedBounds());
        }
    }
    return bounds;
}

static SkPath pathFromTransformedRectangle(const Rect& bounds, const Matrix4& transform) {
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
        SkPath rectPathTransformed =
                pathFromTransformedRectangle(tr.getBounds(), tr.getTransform());
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

void RectangleList::transform(const Matrix4& transform) {
    for (int index = 0; index < mTransformedRectanglesCount; index++) {
        mTransformedRectangles[index].transform(transform);
    }
}

/*
 * ClipArea
 */

ClipArea::ClipArea() : mMode(ClipMode::Rectangle) {}

/*
 * Interface
 */

void ClipArea::setViewportDimensions(int width, int height) {
    mPostViewportClipObserved = false;
    mViewportBounds.set(0, 0, width, height);
    mClipRect = mViewportBounds;
}

void ClipArea::setEmpty() {
    onClipUpdated();
    mMode = ClipMode::Rectangle;
    mClipRect.setEmpty();
    mClipRegion.setEmpty();
    mRectangleList.setEmpty();
}

void ClipArea::setClip(float left, float top, float right, float bottom) {
    onClipUpdated();
    mMode = ClipMode::Rectangle;
    mClipRect.set(left, top, right, bottom);
    mClipRegion.setEmpty();
}

void ClipArea::clipRectWithTransform(const Rect& r, const mat4* transform, SkRegion::Op op) {
    if (op == SkRegion::kReplace_Op) mReplaceOpObserved = true;
    if (!mPostViewportClipObserved && op == SkRegion::kIntersect_Op) op = SkRegion::kReplace_Op;
    onClipUpdated();
    switch (mMode) {
        case ClipMode::Rectangle:
            rectangleModeClipRectWithTransform(r, transform, op);
            break;
        case ClipMode::RectangleList:
            rectangleListModeClipRectWithTransform(r, transform, op);
            break;
        case ClipMode::Region:
            regionModeClipRectWithTransform(r, transform, op);
            break;
    }
}

void ClipArea::clipRegion(const SkRegion& region, SkRegion::Op op) {
    if (op == SkRegion::kReplace_Op) mReplaceOpObserved = true;
    if (!mPostViewportClipObserved && op == SkRegion::kIntersect_Op) op = SkRegion::kReplace_Op;
    onClipUpdated();
    enterRegionMode();
    mClipRegion.op(region, op);
    onClipRegionUpdated();
}

void ClipArea::clipPathWithTransform(const SkPath& path, const mat4* transform, SkRegion::Op op) {
    if (op == SkRegion::kReplace_Op) mReplaceOpObserved = true;
    if (!mPostViewportClipObserved && op == SkRegion::kIntersect_Op) op = SkRegion::kReplace_Op;
    onClipUpdated();
    SkMatrix skTransform;
    transform->copyTo(skTransform);
    SkPath transformed;
    path.transform(skTransform, &transformed);
    SkRegion region;
    regionFromPath(transformed, region);
    enterRegionMode();
    mClipRegion.op(region, op);
    onClipRegionUpdated();
}

/*
 * Rectangle mode
 */

void ClipArea::enterRectangleMode() {
    // Entering rectangle mode discards any
    // existing clipping information from the other modes.
    // The only way this occurs is by a clip setting operation.
    mMode = ClipMode::Rectangle;
}

void ClipArea::rectangleModeClipRectWithTransform(const Rect& r, const mat4* transform,
                                                  SkRegion::Op op) {
    if (op == SkRegion::kReplace_Op && transform->rectToRect()) {
        mClipRect = r;
        transform->mapRect(mClipRect);
        return;
    } else if (op != SkRegion::kIntersect_Op) {
        enterRegionMode();
        regionModeClipRectWithTransform(r, transform, op);
        return;
    }

    if (transform->rectToRect()) {
        Rect transformed(r);
        transform->mapRect(transformed);
        mClipRect.doIntersect(transformed);
        return;
    }

    enterRectangleListMode();
    rectangleListModeClipRectWithTransform(r, transform, op);
}

/*
 * RectangleList mode implementation
 */

void ClipArea::enterRectangleListMode() {
    // Is is only legal to enter rectangle list mode from
    // rectangle mode, since rectangle list mode cannot represent
    // all clip areas that can be represented by a region.
    ALOG_ASSERT(mMode == ClipMode::Rectangle);
    mMode = ClipMode::RectangleList;
    mRectangleList.set(mClipRect, Matrix4::identity());
}

void ClipArea::rectangleListModeClipRectWithTransform(const Rect& r, const mat4* transform,
                                                      SkRegion::Op op) {
    if (op != SkRegion::kIntersect_Op || !mRectangleList.intersectWith(r, *transform)) {
        enterRegionMode();
        regionModeClipRectWithTransform(r, transform, op);
    }
}

/*
 * Region mode implementation
 */

void ClipArea::enterRegionMode() {
    ClipMode oldMode = mMode;
    mMode = ClipMode::Region;
    if (oldMode != ClipMode::Region) {
        if (oldMode == ClipMode::Rectangle) {
            mClipRegion.setRect(mClipRect.toSkIRect());
        } else {
            mClipRegion = mRectangleList.convertToRegion(createViewportRegion());
            onClipRegionUpdated();
        }
    }
}

void ClipArea::regionModeClipRectWithTransform(const Rect& r, const mat4* transform,
                                               SkRegion::Op op) {
    SkPath transformedRect = pathFromTransformedRectangle(r, *transform);
    SkRegion transformedRectRegion;
    regionFromPath(transformedRect, transformedRectRegion);
    mClipRegion.op(transformedRectRegion, op);
    onClipRegionUpdated();
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

/**
 * Clip serialization
 */

const ClipBase* ClipArea::serializeClip(LinearAllocator& allocator) {
    if (!mPostViewportClipObserved) {
        // Only initial clip-to-viewport observed, so no serialization of clip necessary
        return nullptr;
    }

    static_assert(std::is_trivially_destructible<Rect>::value,
                  "expect Rect to be trivially destructible");
    static_assert(std::is_trivially_destructible<RectangleList>::value,
                  "expect RectangleList to be trivially destructible");

    if (mLastSerialization == nullptr) {
        ClipBase* serialization = nullptr;
        switch (mMode) {
            case ClipMode::Rectangle:
                serialization = allocator.create<ClipRect>(mClipRect);
                break;
            case ClipMode::RectangleList:
                serialization = allocator.create<ClipRectList>(mRectangleList);
                serialization->rect = mRectangleList.calculateBounds();
                break;
            case ClipMode::Region:
                serialization = allocator.create<ClipRegion>(mClipRegion);
                serialization->rect.set(mClipRegion.getBounds());
                break;
        }
        serialization->intersectWithRoot = mReplaceOpObserved;
        // TODO: this is only done for draw time, should eventually avoid for record time
        serialization->rect.snapToPixelBoundaries();
        mLastSerialization = serialization;
    }
    return mLastSerialization;
}

inline static const RectangleList& getRectList(const ClipBase* scb) {
    return reinterpret_cast<const ClipRectList*>(scb)->rectList;
}

inline static const SkRegion& getRegion(const ClipBase* scb) {
    return reinterpret_cast<const ClipRegion*>(scb)->region;
}

// Conservative check for too many rectangles to fit in rectangle list.
// For simplicity, doesn't account for rect merging
static bool cannotFitInRectangleList(const ClipArea& clipArea, const ClipBase* scb) {
    int currentRectCount = clipArea.isRectangleList()
                                   ? clipArea.getRectangleList().getTransformedRectanglesCount()
                                   : 1;
    int recordedRectCount = (scb->mode == ClipMode::RectangleList)
                                    ? getRectList(scb).getTransformedRectanglesCount()
                                    : 1;
    return currentRectCount + recordedRectCount > RectangleList::kMaxTransformedRectangles;
}

static const ClipRect sEmptyClipRect(Rect(0, 0));

const ClipBase* ClipArea::serializeIntersectedClip(LinearAllocator& allocator,
                                                   const ClipBase* recordedClip,
                                                   const Matrix4& recordedClipTransform) {
    // if no recordedClip passed, just serialize current state
    if (!recordedClip) return serializeClip(allocator);

    // if either is empty, clip is empty
    if (CC_UNLIKELY(recordedClip->rect.isEmpty()) || mClipRect.isEmpty()) return &sEmptyClipRect;

    if (!mLastResolutionResult || recordedClip != mLastResolutionClip ||
        recordedClipTransform != mLastResolutionTransform) {
        mLastResolutionClip = recordedClip;
        mLastResolutionTransform = recordedClipTransform;

        if (CC_LIKELY(mMode == ClipMode::Rectangle && recordedClip->mode == ClipMode::Rectangle &&
                      recordedClipTransform.rectToRect())) {
            // common case - result is a single rectangle
            auto rectClip = allocator.create<ClipRect>(recordedClip->rect);
            recordedClipTransform.mapRect(rectClip->rect);
            rectClip->rect.doIntersect(mClipRect);
            rectClip->rect.snapToPixelBoundaries();
            mLastResolutionResult = rectClip;
        } else if (CC_UNLIKELY(mMode == ClipMode::Region ||
                               recordedClip->mode == ClipMode::Region ||
                               cannotFitInRectangleList(*this, recordedClip))) {
            // region case
            SkRegion other;
            switch (recordedClip->mode) {
                case ClipMode::Rectangle:
                    if (CC_LIKELY(recordedClipTransform.rectToRect())) {
                        // simple transform, skip creating SkPath
                        Rect resultClip(recordedClip->rect);
                        recordedClipTransform.mapRect(resultClip);
                        other.setRect(resultClip.toSkIRect());
                    } else {
                        SkPath transformedRect = pathFromTransformedRectangle(
                                recordedClip->rect, recordedClipTransform);
                        other.setPath(transformedRect, createViewportRegion());
                    }
                    break;
                case ClipMode::RectangleList: {
                    RectangleList transformedList(getRectList(recordedClip));
                    transformedList.transform(recordedClipTransform);
                    other = transformedList.convertToRegion(createViewportRegion());
                    break;
                }
                case ClipMode::Region:
                    other = getRegion(recordedClip);
                    applyTransformToRegion(recordedClipTransform, &other);
            }

            ClipRegion* regionClip = allocator.create<ClipRegion>();
            switch (mMode) {
                case ClipMode::Rectangle:
                    regionClip->region.op(mClipRect.toSkIRect(), other, SkRegion::kIntersect_Op);
                    break;
                case ClipMode::RectangleList:
                    regionClip->region.op(mRectangleList.convertToRegion(createViewportRegion()),
                                          other, SkRegion::kIntersect_Op);
                    break;
                case ClipMode::Region:
                    regionClip->region.op(mClipRegion, other, SkRegion::kIntersect_Op);
                    break;
            }
            // Don't need to snap, since region's in int bounds
            regionClip->rect.set(regionClip->region.getBounds());
            mLastResolutionResult = regionClip;
        } else {
            auto rectListClip = allocator.create<ClipRectList>(mRectangleList);
            auto&& rectList = rectListClip->rectList;
            if (mMode == ClipMode::Rectangle) {
                rectList.set(mClipRect, Matrix4::identity());
            }

            if (recordedClip->mode == ClipMode::Rectangle) {
                rectList.intersectWith(recordedClip->rect, recordedClipTransform);
            } else {
                const RectangleList& other = getRectList(recordedClip);
                for (int i = 0; i < other.getTransformedRectanglesCount(); i++) {
                    auto&& tr = other.getTransformedRectangle(i);
                    Matrix4 totalTransform(recordedClipTransform);
                    totalTransform.multiply(tr.getTransform());
                    rectList.intersectWith(tr.getBounds(), totalTransform);
                }
            }
            rectListClip->rect = rectList.calculateBounds();
            rectListClip->rect.snapToPixelBoundaries();
            mLastResolutionResult = rectListClip;
        }
    }
    return mLastResolutionResult;
}

void ClipArea::applyClip(const ClipBase* clip, const Matrix4& transform) {
    if (!clip) return;  // nothing to do

    if (CC_LIKELY(clip->mode == ClipMode::Rectangle)) {
        clipRectWithTransform(clip->rect, &transform, SkRegion::kIntersect_Op);
    } else if (CC_LIKELY(clip->mode == ClipMode::RectangleList)) {
        auto&& rectList = getRectList(clip);
        for (int i = 0; i < rectList.getTransformedRectanglesCount(); i++) {
            auto&& tr = rectList.getTransformedRectangle(i);
            Matrix4 totalTransform(transform);
            totalTransform.multiply(tr.getTransform());
            clipRectWithTransform(tr.getBounds(), &totalTransform, SkRegion::kIntersect_Op);
        }
    } else {
        SkRegion region(getRegion(clip));
        applyTransformToRegion(transform, &region);
        clipRegion(region, SkRegion::kIntersect_Op);
    }
}

void ClipArea::applyTransformToRegion(const Matrix4& transform, SkRegion* region) {
    if (transform.rectToRect() && !transform.isPureTranslate()) {
        // handle matrices with scale manually by mapping each rect
        SkRegion other;
        SkRegion::Iterator it(*region);
        while (!it.done()) {
            Rect rect(it.rect());
            transform.mapRect(rect);
            rect.snapGeometryToPixelBoundaries(true);
            other.op(rect.left, rect.top, rect.right, rect.bottom, SkRegion::kUnion_Op);
            it.next();
        }
        region->swap(other);
    } else {
        // TODO: handle non-translate transforms properly!
        region->translate(transform.getTranslateX(), transform.getTranslateY());
    }
}

} /* namespace uirenderer */
} /* namespace android */
