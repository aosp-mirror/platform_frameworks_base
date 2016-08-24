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

#include "VectorDrawable.h"

#include "PathParser.h"
#include "SkColorFilter.h"
#include "SkImageInfo.h"
#include "SkShader.h"
#include <utils/Log.h>
#include "utils/Macros.h"
#include "utils/VectorDrawableUtils.h"

#include <math.h>
#include <string.h>

namespace android {
namespace uirenderer {
namespace VectorDrawable {

const int Tree::MAX_CACHED_BITMAP_SIZE = 2048;

void Path::draw(SkCanvas* outCanvas, const SkMatrix& groupStackedMatrix, float scaleX, float scaleY,
        bool useStagingData) {
    float matrixScale = getMatrixScale(groupStackedMatrix);
    if (matrixScale == 0) {
        // When either x or y is scaled to 0, we don't need to draw anything.
        return;
    }

    SkMatrix pathMatrix(groupStackedMatrix);
    pathMatrix.postScale(scaleX, scaleY);

    //TODO: try apply the path matrix to the canvas instead of creating a new path.
    SkPath renderPath;
    renderPath.reset();

    if (useStagingData) {
        SkPath tmpPath;
        getStagingPath(&tmpPath);
        renderPath.addPath(tmpPath, pathMatrix);
    } else {
        renderPath.addPath(getUpdatedPath(), pathMatrix);
    }

    float minScale = fmin(scaleX, scaleY);
    float strokeScale = minScale * matrixScale;
    drawPath(outCanvas, renderPath, strokeScale, pathMatrix, useStagingData);
}

void Path::dump() {
    ALOGD("Path: %s has %zu points", mName.c_str(), mProperties.getData().points.size());
}

float Path::getMatrixScale(const SkMatrix& groupStackedMatrix) {
    // Given unit vectors A = (0, 1) and B = (1, 0).
    // After matrix mapping, we got A' and B'. Let theta = the angel b/t A' and B'.
    // Therefore, the final scale we want is min(|A'| * sin(theta), |B'| * sin(theta)),
    // which is (|A'| * |B'| * sin(theta)) / max (|A'|, |B'|);
    // If  max (|A'|, |B'|) = 0, that means either x or y has a scale of 0.
    //
    // For non-skew case, which is most of the cases, matrix scale is computing exactly the
    // scale on x and y axis, and take the minimal of these two.
    // For skew case, an unit square will mapped to a parallelogram. And this function will
    // return the minimal height of the 2 bases.
    SkVector skVectors[2];
    skVectors[0].set(0, 1);
    skVectors[1].set(1, 0);
    groupStackedMatrix.mapVectors(skVectors, 2);
    float scaleX = hypotf(skVectors[0].fX, skVectors[0].fY);
    float scaleY = hypotf(skVectors[1].fX, skVectors[1].fY);
    float crossProduct = skVectors[0].cross(skVectors[1]);
    float maxScale = fmax(scaleX, scaleY);

    float matrixScale = 0;
    if (maxScale > 0) {
        matrixScale = fabs(crossProduct) / maxScale;
    }
    return matrixScale;
}

// Called from UI thread during the initial setup/theme change.
Path::Path(const char* pathStr, size_t strLength) {
    PathParser::ParseResult result;
    Data data;
    PathParser::getPathDataFromAsciiString(&data, &result, pathStr, strLength);
    mStagingProperties.setData(data);
}

Path::Path(const Path& path) : Node(path) {
    mStagingProperties.syncProperties(path.mStagingProperties);
}

const SkPath& Path::getUpdatedPath() {
    if (mSkPathDirty) {
        mSkPath.reset();
        VectorDrawableUtils::verbsToPath(&mSkPath, mProperties.getData());
        mSkPathDirty = false;
    }
    return mSkPath;
}

void Path::getStagingPath(SkPath* outPath) {
    outPath->reset();
    VectorDrawableUtils::verbsToPath(outPath, mStagingProperties.getData());
}

void Path::syncProperties() {
    if (mStagingPropertiesDirty) {
        mProperties.syncProperties(mStagingProperties);
    } else {
        mStagingProperties.syncProperties(mProperties);
    }
    mStagingPropertiesDirty = false;
}

FullPath::FullPath(const FullPath& path) : Path(path) {
    mStagingProperties.syncProperties(path.mStagingProperties);
}

static void applyTrim(SkPath* outPath, const SkPath& inPath, float trimPathStart, float trimPathEnd,
        float trimPathOffset) {
    if (trimPathStart == 0.0f && trimPathEnd == 1.0f) {
        *outPath = inPath;
        return;
    }
    outPath->reset();
    if (trimPathStart == trimPathEnd) {
        // Trimmed path should be empty.
        return;
    }
    SkPathMeasure measure(inPath, false);
    float len = SkScalarToFloat(measure.getLength());
    float start = len * fmod((trimPathStart + trimPathOffset), 1.0f);
    float end = len * fmod((trimPathEnd + trimPathOffset), 1.0f);

    if (start > end) {
        measure.getSegment(start, len, outPath, true);
        if (end > 0) {
            measure.getSegment(0, end, outPath, true);
        }
    } else {
        measure.getSegment(start, end, outPath, true);
    }
}

const SkPath& FullPath::getUpdatedPath() {
    if (!mSkPathDirty && !mProperties.mTrimDirty) {
        return mTrimmedSkPath;
    }
    Path::getUpdatedPath();
    if (mProperties.getTrimPathStart() != 0.0f || mProperties.getTrimPathEnd() != 1.0f) {
        mProperties.mTrimDirty = false;
        applyTrim(&mTrimmedSkPath, mSkPath, mProperties.getTrimPathStart(),
                mProperties.getTrimPathEnd(), mProperties.getTrimPathOffset());
        return mTrimmedSkPath;
    } else {
        return mSkPath;
    }
}

void FullPath::getStagingPath(SkPath* outPath) {
    Path::getStagingPath(outPath);
    SkPath inPath = *outPath;
    applyTrim(outPath, inPath, mStagingProperties.getTrimPathStart(),
            mStagingProperties.getTrimPathEnd(), mStagingProperties.getTrimPathOffset());
}

void FullPath::dump() {
    Path::dump();
    ALOGD("stroke width, color, alpha: %f, %d, %f, fill color, alpha: %d, %f",
            mProperties.getStrokeWidth(), mProperties.getStrokeColor(), mProperties.getStrokeAlpha(),
            mProperties.getFillColor(), mProperties.getFillAlpha());
}


inline SkColor applyAlpha(SkColor color, float alpha) {
    int alphaBytes = SkColorGetA(color);
    return SkColorSetA(color, alphaBytes * alpha);
}

void FullPath::drawPath(SkCanvas* outCanvas, SkPath& renderPath, float strokeScale,
                        const SkMatrix& matrix, bool useStagingData){
    const FullPathProperties& properties = useStagingData ? mStagingProperties : mProperties;

    // Draw path's fill, if fill color or gradient is valid
    bool needsFill = false;
    SkPaint paint;
    if (properties.getFillGradient() != nullptr) {
        paint.setColor(applyAlpha(SK_ColorBLACK, properties.getFillAlpha()));
        SkShader* newShader = properties.getFillGradient()->newWithLocalMatrix(matrix);
        paint.setShader(newShader);
        needsFill = true;
    } else if (properties.getFillColor() != SK_ColorTRANSPARENT) {
        paint.setColor(applyAlpha(properties.getFillColor(), properties.getFillAlpha()));
        needsFill = true;
    }

    if (needsFill) {
        paint.setStyle(SkPaint::Style::kFill_Style);
        paint.setAntiAlias(true);
        SkPath::FillType ft = static_cast<SkPath::FillType>(properties.getFillType());
        renderPath.setFillType(ft);
        outCanvas->drawPath(renderPath, paint);
    }

    // Draw path's stroke, if stroke color or Gradient is valid
    bool needsStroke = false;
    if (properties.getStrokeGradient() != nullptr) {
        paint.setColor(applyAlpha(SK_ColorBLACK, properties.getStrokeAlpha()));
        SkShader* newShader = properties.getStrokeGradient()->newWithLocalMatrix(matrix);
        paint.setShader(newShader);
        needsStroke = true;
    } else if (properties.getStrokeColor() != SK_ColorTRANSPARENT) {
        paint.setColor(applyAlpha(properties.getStrokeColor(), properties.getStrokeAlpha()));
        needsStroke = true;
    }
    if (needsStroke) {
        paint.setStyle(SkPaint::Style::kStroke_Style);
        paint.setAntiAlias(true);
        paint.setStrokeJoin(SkPaint::Join(properties.getStrokeLineJoin()));
        paint.setStrokeCap(SkPaint::Cap(properties.getStrokeLineCap()));
        paint.setStrokeMiter(properties.getStrokeMiterLimit());
        paint.setStrokeWidth(properties.getStrokeWidth() * strokeScale);
        outCanvas->drawPath(renderPath, paint);
    }
}

void FullPath::syncProperties() {
    Path::syncProperties();

    if (mStagingPropertiesDirty) {
        mProperties.syncProperties(mStagingProperties);
    } else {
        // Update staging property with property values from animation.
        mStagingProperties.syncProperties(mProperties);
    }
    mStagingPropertiesDirty = false;
}

REQUIRE_COMPATIBLE_LAYOUT(FullPath::FullPathProperties::PrimitiveFields);

static_assert(sizeof(float) == sizeof(int32_t), "float is not the same size as int32_t");
static_assert(sizeof(SkColor) == sizeof(int32_t), "SkColor is not the same size as int32_t");

bool FullPath::FullPathProperties::copyProperties(int8_t* outProperties, int length) const {
    int propertyDataSize = sizeof(FullPathProperties::PrimitiveFields);
    if (length != propertyDataSize) {
        LOG_ALWAYS_FATAL("Properties needs exactly %d bytes, a byte array of size %d is provided",
                propertyDataSize, length);
        return false;
    }

    PrimitiveFields* out = reinterpret_cast<PrimitiveFields*>(outProperties);
    *out = mPrimitiveFields;
    return true;
}

void FullPath::FullPathProperties::setColorPropertyValue(int propertyId, int32_t value) {
    Property currentProperty = static_cast<Property>(propertyId);
    if (currentProperty == Property::strokeColor) {
        setStrokeColor(value);
    } else if (currentProperty == Property::fillColor) {
        setFillColor(value);
    } else {
        LOG_ALWAYS_FATAL("Error setting color property on FullPath: No valid property"
                " with id: %d", propertyId);
    }
}

void FullPath::FullPathProperties::setPropertyValue(int propertyId, float value) {
    Property property = static_cast<Property>(propertyId);
    switch (property) {
    case Property::strokeWidth:
        setStrokeWidth(value);
        break;
    case Property::strokeAlpha:
        setStrokeAlpha(value);
        break;
    case Property::fillAlpha:
        setFillAlpha(value);
        break;
    case Property::trimPathStart:
        setTrimPathStart(value);
        break;
    case Property::trimPathEnd:
        setTrimPathEnd(value);
        break;
    case Property::trimPathOffset:
        setTrimPathOffset(value);
        break;
    default:
        LOG_ALWAYS_FATAL("Invalid property id: %d for animation", propertyId);
        break;
    }
}

void ClipPath::drawPath(SkCanvas* outCanvas, SkPath& renderPath,
        float strokeScale, const SkMatrix& matrix, bool useStagingData){
    outCanvas->clipPath(renderPath, SkRegion::kIntersect_Op);
}

Group::Group(const Group& group) : Node(group) {
    mStagingProperties.syncProperties(group.mStagingProperties);
}

void Group::draw(SkCanvas* outCanvas, const SkMatrix& currentMatrix, float scaleX,
        float scaleY, bool useStagingData) {
    // TODO: Try apply the matrix to the canvas instead of passing it down the tree

    // Calculate current group's matrix by preConcat the parent's and
    // and the current one on the top of the stack.
    // Basically the Mfinal = Mviewport * M0 * M1 * M2;
    // Mi the local matrix at level i of the group tree.
    SkMatrix stackedMatrix;
    const GroupProperties& prop = useStagingData ? mStagingProperties : mProperties;
    getLocalMatrix(&stackedMatrix, prop);
    stackedMatrix.postConcat(currentMatrix);

    // Save the current clip information, which is local to this group.
    outCanvas->save();
    // Draw the group tree in the same order as the XML file.
    for (auto& child : mChildren) {
        child->draw(outCanvas, stackedMatrix, scaleX, scaleY, useStagingData);
    }
    // Restore the previous clip information.
    outCanvas->restore();
}

void Group::dump() {
    ALOGD("Group %s has %zu children: ", mName.c_str(), mChildren.size());
    ALOGD("Group translateX, Y : %f, %f, scaleX, Y: %f, %f", mProperties.getTranslateX(),
            mProperties.getTranslateY(), mProperties.getScaleX(), mProperties.getScaleY());
    for (size_t i = 0; i < mChildren.size(); i++) {
        mChildren[i]->dump();
    }
}

void Group::syncProperties() {
    // Copy over the dirty staging properties
    if (mStagingPropertiesDirty) {
        mProperties.syncProperties(mStagingProperties);
    } else {
        mStagingProperties.syncProperties(mProperties);
    }
    mStagingPropertiesDirty = false;
    for (auto& child : mChildren) {
        child->syncProperties();
    }
}

void Group::getLocalMatrix(SkMatrix* outMatrix, const GroupProperties& properties) {
    outMatrix->reset();
    // TODO: use rotate(mRotate, mPivotX, mPivotY) and scale with pivot point, instead of
    // translating to pivot for rotating and scaling, then translating back.
    outMatrix->postTranslate(-properties.getPivotX(), -properties.getPivotY());
    outMatrix->postScale(properties.getScaleX(), properties.getScaleY());
    outMatrix->postRotate(properties.getRotation(), 0, 0);
    outMatrix->postTranslate(properties.getTranslateX() + properties.getPivotX(),
            properties.getTranslateY() + properties.getPivotY());
}

void Group::addChild(Node* child) {
    mChildren.emplace_back(child);
    if (mPropertyChangedListener != nullptr) {
        child->setPropertyChangedListener(mPropertyChangedListener);
    }
}

bool Group::GroupProperties::copyProperties(float* outProperties, int length) const {
    int propertyCount = static_cast<int>(Property::count);
    if (length != propertyCount) {
        LOG_ALWAYS_FATAL("Properties needs exactly %d bytes, a byte array of size %d is provided",
                propertyCount, length);
        return false;
    }

    PrimitiveFields* out = reinterpret_cast<PrimitiveFields*>(outProperties);
    *out = mPrimitiveFields;
    return true;
}

// TODO: Consider animating the properties as float pointers
// Called on render thread
float Group::GroupProperties::getPropertyValue(int propertyId) const {
    Property currentProperty = static_cast<Property>(propertyId);
    switch (currentProperty) {
    case Property::rotate:
        return getRotation();
    case Property::pivotX:
        return getPivotX();
    case Property::pivotY:
        return getPivotY();
    case Property::scaleX:
        return getScaleX();
    case Property::scaleY:
        return getScaleY();
    case Property::translateX:
        return getTranslateX();
    case Property::translateY:
        return getTranslateY();
    default:
        LOG_ALWAYS_FATAL("Invalid property index: %d", propertyId);
        return 0;
    }
}

// Called on render thread
void Group::GroupProperties::setPropertyValue(int propertyId, float value) {
    Property currentProperty = static_cast<Property>(propertyId);
    switch (currentProperty) {
    case Property::rotate:
        setRotation(value);
        break;
    case Property::pivotX:
        setPivotX(value);
        break;
    case Property::pivotY:
        setPivotY(value);
        break;
    case Property::scaleX:
        setScaleX(value);
        break;
    case Property::scaleY:
        setScaleY(value);
        break;
    case Property::translateX:
        setTranslateX(value);
        break;
    case Property::translateY:
        setTranslateY(value);
        break;
    default:
        LOG_ALWAYS_FATAL("Invalid property index: %d", propertyId);
    }
}

bool Group::isValidProperty(int propertyId) {
    return GroupProperties::isValidProperty(propertyId);
}

bool Group::GroupProperties::isValidProperty(int propertyId) {
    return propertyId >= 0 && propertyId < static_cast<int>(Property::count);
}

int Tree::draw(Canvas* outCanvas, SkColorFilter* colorFilter,
        const SkRect& bounds, bool needsMirroring, bool canReuseCache) {
    // The imageView can scale the canvas in different ways, in order to
    // avoid blurry scaling, we have to draw into a bitmap with exact pixel
    // size first. This bitmap size is determined by the bounds and the
    // canvas scale.
    SkMatrix canvasMatrix;
    outCanvas->getMatrix(&canvasMatrix);
    float canvasScaleX = 1.0f;
    float canvasScaleY = 1.0f;
    if (canvasMatrix.getSkewX() == 0 && canvasMatrix.getSkewY() == 0) {
        // Only use the scale value when there's no skew or rotation in the canvas matrix.
        // TODO: Add a cts test for drawing VD on a canvas with negative scaling factors.
        canvasScaleX = fabs(canvasMatrix.getScaleX());
        canvasScaleY = fabs(canvasMatrix.getScaleY());
    }
    int scaledWidth = (int) (bounds.width() * canvasScaleX);
    int scaledHeight = (int) (bounds.height() * canvasScaleY);
    scaledWidth = std::min(Tree::MAX_CACHED_BITMAP_SIZE, scaledWidth);
    scaledHeight = std::min(Tree::MAX_CACHED_BITMAP_SIZE, scaledHeight);

    if (scaledWidth <= 0 || scaledHeight <= 0) {
        return 0;
    }

    mStagingProperties.setScaledSize(scaledWidth, scaledHeight);
    int saveCount = outCanvas->save(SaveFlags::MatrixClip);
    outCanvas->translate(bounds.fLeft, bounds.fTop);

    // Handle RTL mirroring.
    if (needsMirroring) {
        outCanvas->translate(bounds.width(), 0);
        outCanvas->scale(-1.0f, 1.0f);
    }
    mStagingProperties.setColorFilter(colorFilter);

    // At this point, canvas has been translated to the right position.
    // And we use this bound for the destination rect for the drawBitmap, so
    // we offset to (0, 0);
    SkRect tmpBounds = bounds;
    tmpBounds.offsetTo(0, 0);
    mStagingProperties.setBounds(tmpBounds);
    outCanvas->drawVectorDrawable(this);
    outCanvas->restoreToCount(saveCount);
    return scaledWidth * scaledHeight;
}

void Tree::drawStaging(Canvas* outCanvas) {
    bool redrawNeeded = allocateBitmapIfNeeded(&mStagingCache.bitmap,
            mStagingProperties.getScaledWidth(), mStagingProperties.getScaledHeight());
    // draw bitmap cache
    if (redrawNeeded || mStagingCache.dirty) {
        updateBitmapCache(&mStagingCache.bitmap, true);
        mStagingCache.dirty = false;
    }

    SkPaint tmpPaint;
    SkPaint* paint = updatePaint(&tmpPaint, &mStagingProperties);
    outCanvas->drawBitmap(mStagingCache.bitmap, 0, 0,
            mStagingCache.bitmap.width(), mStagingCache.bitmap.height(),
            mStagingProperties.getBounds().left(), mStagingProperties.getBounds().top(),
            mStagingProperties.getBounds().right(), mStagingProperties.getBounds().bottom(), paint);
}

SkPaint* Tree::getPaint() {
    return updatePaint(&mPaint, &mProperties);
}

// Update the given paint with alpha and color filter. Return nullptr if no color filter is
// specified and root alpha is 1. Otherwise, return updated paint.
SkPaint* Tree::updatePaint(SkPaint* outPaint, TreeProperties* prop) {
    if (prop->getRootAlpha() == 1.0f && prop->getColorFilter() == nullptr) {
        return nullptr;
    } else {
        outPaint->setColorFilter(prop->getColorFilter());
        outPaint->setFilterQuality(kLow_SkFilterQuality);
        outPaint->setAlpha(prop->getRootAlpha() * 255);
        return outPaint;
    }
}

const SkBitmap& Tree::getBitmapUpdateIfDirty() {
    bool redrawNeeded = allocateBitmapIfNeeded(&mCache.bitmap, mProperties.getScaledWidth(),
            mProperties.getScaledHeight());
    if (redrawNeeded || mCache.dirty) {
        updateBitmapCache(&mCache.bitmap, false);
        mCache.dirty = false;
    }
    return mCache.bitmap;
}

void Tree::updateBitmapCache(SkBitmap* outCache, bool useStagingData) {
    outCache->eraseColor(SK_ColorTRANSPARENT);
    SkCanvas outCanvas(*outCache);
    float viewportWidth = useStagingData ?
            mStagingProperties.getViewportWidth() : mProperties.getViewportWidth();
    float viewportHeight = useStagingData ?
            mStagingProperties.getViewportHeight() : mProperties.getViewportHeight();
    float scaleX = outCache->width() / viewportWidth;
    float scaleY = outCache->height() / viewportHeight;
    mRootNode->draw(&outCanvas, SkMatrix::I(), scaleX, scaleY, useStagingData);
}

bool Tree::allocateBitmapIfNeeded(SkBitmap* outCache, int width, int height) {
    if (!canReuseBitmap(*outCache, width, height)) {
        SkImageInfo info = SkImageInfo::Make(width, height,
                kN32_SkColorType, kPremul_SkAlphaType);
        outCache->setInfo(info);
        // TODO: Count the bitmap cache against app's java heap
        outCache->allocPixels(info);
        return true;
    }
    return false;
}

bool Tree::canReuseBitmap(const SkBitmap& bitmap, int width, int height) {
    return width == bitmap.width() && height == bitmap.height();
}

void Tree::onPropertyChanged(TreeProperties* prop) {
    if (prop == &mStagingProperties) {
        mStagingCache.dirty = true;
    } else {
        mCache.dirty = true;
    }
}

}; // namespace VectorDrawable

}; // namespace uirenderer
}; // namespace android
