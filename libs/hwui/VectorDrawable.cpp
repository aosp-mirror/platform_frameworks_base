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

void Path::draw(SkCanvas* outCanvas, const SkMatrix& groupStackedMatrix, float scaleX, float scaleY) {
    float matrixScale = getMatrixScale(groupStackedMatrix);
    if (matrixScale == 0) {
        // When either x or y is scaled to 0, we don't need to draw anything.
        return;
    }

    const SkPath updatedPath = getUpdatedPath();
    SkMatrix pathMatrix(groupStackedMatrix);
    pathMatrix.postScale(scaleX, scaleY);

    //TODO: try apply the path matrix to the canvas instead of creating a new path.
    SkPath renderPath;
    renderPath.reset();
    renderPath.addPath(updatedPath, pathMatrix);

    float minScale = fmin(scaleX, scaleY);
    float strokeScale = minScale * matrixScale;
    drawPath(outCanvas, renderPath, strokeScale, pathMatrix);
}

void Path::setPathData(const Data& data) {
    if (mData == data) {
        return;
    }
    // Updates the path data. Note that we don't generate a new Skia path right away
    // because there are cases where the animation is changing the path data, but the view
    // that hosts the VD has gone off screen, in which case we won't even draw. So we
    // postpone the Skia path generation to the draw time.
    mData = data;
    mSkPathDirty = true;
}

void Path::dump() {
    ALOGD("Path: %s has %zu points", mName.c_str(), mData.points.size());
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
Path::Path(const char* pathStr, size_t strLength) {
    PathParser::ParseResult result;
    PathParser::getPathDataFromString(&mData, &result, pathStr, strLength);
    if (!result.failureOccurred) {
        VectorDrawableUtils::verbsToPath(&mSkPath, mData);
    }
}

Path::Path(const Data& data) {
    mData = data;
    // Now we need to construct a path
    VectorDrawableUtils::verbsToPath(&mSkPath, data);
}

Path::Path(const Path& path) : Node(path) {
    mData = path.mData;
    VectorDrawableUtils::verbsToPath(&mSkPath, mData);
}

bool Path::canMorph(const Data& morphTo) {
    return VectorDrawableUtils::canMorph(mData, morphTo);
}

bool Path::canMorph(const Path& path) {
    return canMorph(path.mData);
}

const SkPath& Path::getUpdatedPath() {
    if (mSkPathDirty) {
        mSkPath.reset();
        VectorDrawableUtils::verbsToPath(&mSkPath, mData);
        mSkPathDirty = false;
    }
    return mSkPath;
}

void Path::setPath(const char* pathStr, size_t strLength) {
    PathParser::ParseResult result;
    mSkPathDirty = true;
    PathParser::getPathDataFromString(&mData, &result, pathStr, strLength);
}

FullPath::FullPath(const FullPath& path) : Path(path) {
    mProperties = path.mProperties;
    SkRefCnt_SafeAssign(mStrokeGradient, path.mStrokeGradient);
    SkRefCnt_SafeAssign(mFillGradient, path.mFillGradient);
}

const SkPath& FullPath::getUpdatedPath() {
    if (!mSkPathDirty && !mTrimDirty) {
        return mTrimmedSkPath;
    }
    Path::getUpdatedPath();
    if (mProperties.trimPathStart != 0.0f || mProperties.trimPathEnd != 1.0f) {
        applyTrim();
        return mTrimmedSkPath;
    } else {
        return mSkPath;
    }
}

void FullPath::updateProperties(float strokeWidth, SkColor strokeColor, float strokeAlpha,
        SkColor fillColor, float fillAlpha, float trimPathStart, float trimPathEnd,
        float trimPathOffset, float strokeMiterLimit, int strokeLineCap, int strokeLineJoin,
        int fillType) {
    mProperties.strokeWidth = strokeWidth;
    mProperties.strokeColor = strokeColor;
    mProperties.strokeAlpha = strokeAlpha;
    mProperties.fillColor = fillColor;
    mProperties.fillAlpha = fillAlpha;
    mProperties.strokeMiterLimit = strokeMiterLimit;
    mProperties.strokeLineCap = strokeLineCap;
    mProperties.strokeLineJoin = strokeLineJoin;
    mProperties.fillType = fillType;

    // If any trim property changes, mark trim dirty and update the trim path
    setTrimPathStart(trimPathStart);
    setTrimPathEnd(trimPathEnd);
    setTrimPathOffset(trimPathOffset);
}

inline SkColor applyAlpha(SkColor color, float alpha) {
    int alphaBytes = SkColorGetA(color);
    return SkColorSetA(color, alphaBytes * alpha);
}

void FullPath::drawPath(SkCanvas* outCanvas, SkPath& renderPath, float strokeScale,
                        const SkMatrix& matrix){
    // Draw path's fill, if fill color or gradient is valid
    bool needsFill = false;
    if (mFillGradient != nullptr) {
        mPaint.setColor(applyAlpha(SK_ColorBLACK, mProperties.fillAlpha));
        SkShader* newShader = mFillGradient->newWithLocalMatrix(matrix);
        mPaint.setShader(newShader);
        needsFill = true;
    } else if (mProperties.fillColor != SK_ColorTRANSPARENT) {
        mPaint.setColor(applyAlpha(mProperties.fillColor, mProperties.fillAlpha));
        needsFill = true;
    }

    if (needsFill) {
        mPaint.setStyle(SkPaint::Style::kFill_Style);
        mPaint.setAntiAlias(true);
        SkPath::FillType ft = static_cast<SkPath::FillType>(mProperties.fillType);
        renderPath.setFillType(ft);
        outCanvas->drawPath(renderPath, mPaint);
    }

    // Draw path's stroke, if stroke color or gradient is valid
    bool needsStroke = false;
    if (mStrokeGradient != nullptr) {
        mPaint.setColor(applyAlpha(SK_ColorBLACK, mProperties.strokeAlpha));
        SkShader* newShader = mStrokeGradient->newWithLocalMatrix(matrix);
        mPaint.setShader(newShader);
        needsStroke = true;
    } else if (mProperties.strokeColor != SK_ColorTRANSPARENT) {
        mPaint.setColor(applyAlpha(mProperties.strokeColor, mProperties.strokeAlpha));
        needsStroke = true;
    }
    if (needsStroke) {
        mPaint.setStyle(SkPaint::Style::kStroke_Style);
        mPaint.setAntiAlias(true);
        mPaint.setStrokeJoin(SkPaint::Join(mProperties.strokeLineJoin));
        mPaint.setStrokeCap(SkPaint::Cap(mProperties.strokeLineCap));
        mPaint.setStrokeMiter(mProperties.strokeMiterLimit);
        mPaint.setStrokeWidth(mProperties.strokeWidth * strokeScale);
        outCanvas->drawPath(renderPath, mPaint);
    }
}

/**
 * Applies trimming to the specified path.
 */
void FullPath::applyTrim() {
    if (mProperties.trimPathStart == 0.0f && mProperties.trimPathEnd == 1.0f) {
        // No trimming necessary.
        return;
    }
    SkPathMeasure measure(mSkPath, false);
    float len = SkScalarToFloat(measure.getLength());
    float start = len * fmod((mProperties.trimPathStart + mProperties.trimPathOffset), 1.0f);
    float end = len * fmod((mProperties.trimPathEnd + mProperties.trimPathOffset), 1.0f);

    mTrimmedSkPath.reset();
    if (start > end) {
        measure.getSegment(start, len, &mTrimmedSkPath, true);
        measure.getSegment(0, end, &mTrimmedSkPath, true);
    } else {
        measure.getSegment(start, end, &mTrimmedSkPath, true);
    }
    mTrimDirty = false;
}

REQUIRE_COMPATIBLE_LAYOUT(FullPath::Properties);

static_assert(sizeof(float) == sizeof(int32_t), "float is not the same size as int32_t");
static_assert(sizeof(SkColor) == sizeof(int32_t), "SkColor is not the same size as int32_t");

bool FullPath::getProperties(int8_t* outProperties, int length) {
    int propertyDataSize = sizeof(Properties);
    if (length != propertyDataSize) {
        LOG_ALWAYS_FATAL("Properties needs exactly %d bytes, a byte array of size %d is provided",
                propertyDataSize, length);
        return false;
    }
    Properties* out = reinterpret_cast<Properties*>(outProperties);
    *out = mProperties;
    return true;
}

void FullPath::setColorPropertyValue(int propertyId, int32_t value) {
    Property currentProperty = static_cast<Property>(propertyId);
    if (currentProperty == Property::StrokeColor) {
        mProperties.strokeColor = value;
    } else if (currentProperty == Property::FillColor) {
        mProperties.fillColor = value;
    } else {
        LOG_ALWAYS_FATAL("Error setting color property on FullPath: No valid property with id: %d",
                propertyId);
    }
}

void FullPath::setPropertyValue(int propertyId, float value) {
    Property property = static_cast<Property>(propertyId);
    switch (property) {
    case Property::StrokeWidth:
        setStrokeWidth(value);
        break;
    case Property::StrokeAlpha:
        setStrokeAlpha(value);
        break;
    case Property::FillAlpha:
        setFillAlpha(value);
        break;
    case Property::TrimPathStart:
        setTrimPathStart(value);
        break;
    case Property::TrimPathEnd:
        setTrimPathEnd(value);
        break;
    case Property::TrimPathOffset:
        setTrimPathOffset(value);
        break;
    default:
        LOG_ALWAYS_FATAL("Invalid property id: %d for animation", propertyId);
        break;
    }
}

void ClipPath::drawPath(SkCanvas* outCanvas, SkPath& renderPath,
        float strokeScale, const SkMatrix& matrix){
    outCanvas->clipPath(renderPath, SkRegion::kIntersect_Op);
}

Group::Group(const Group& group) : Node(group) {
    mProperties = group.mProperties;
}

void Group::draw(SkCanvas* outCanvas, const SkMatrix& currentMatrix, float scaleX,
        float scaleY) {
    // TODO: Try apply the matrix to the canvas instead of passing it down the tree

    // Calculate current group's matrix by preConcat the parent's and
    // and the current one on the top of the stack.
    // Basically the Mfinal = Mviewport * M0 * M1 * M2;
    // Mi the local matrix at level i of the group tree.
    SkMatrix stackedMatrix;
    getLocalMatrix(&stackedMatrix);
    stackedMatrix.postConcat(currentMatrix);

    // Save the current clip information, which is local to this group.
    outCanvas->save();
    // Draw the group tree in the same order as the XML file.
    for (auto& child : mChildren) {
        child->draw(outCanvas, stackedMatrix, scaleX, scaleY);
    }
    // Restore the previous clip information.
    outCanvas->restore();
}

void Group::dump() {
    ALOGD("Group %s has %zu children: ", mName.c_str(), mChildren.size());
    for (size_t i = 0; i < mChildren.size(); i++) {
        mChildren[i]->dump();
    }
}

void Group::updateLocalMatrix(float rotate, float pivotX, float pivotY,
        float scaleX, float scaleY, float translateX, float translateY) {
    setRotation(rotate);
    setPivotX(pivotX);
    setPivotY(pivotY);
    setScaleX(scaleX);
    setScaleY(scaleY);
    setTranslateX(translateX);
    setTranslateY(translateY);
}

void Group::getLocalMatrix(SkMatrix* outMatrix) {
    outMatrix->reset();
    // TODO: use rotate(mRotate, mPivotX, mPivotY) and scale with pivot point, instead of
    // translating to pivot for rotating and scaling, then translating back.
    outMatrix->postTranslate(-mProperties.pivotX, -mProperties.pivotY);
    outMatrix->postScale(mProperties.scaleX, mProperties.scaleY);
    outMatrix->postRotate(mProperties.rotate, 0, 0);
    outMatrix->postTranslate(mProperties.translateX + mProperties.pivotX,
            mProperties.translateY + mProperties.pivotY);
}

void Group::addChild(Node* child) {
    mChildren.emplace_back(child);
}

bool Group::getProperties(float* outProperties, int length) {
    int propertyCount = static_cast<int>(Property::Count);
    if (length != propertyCount) {
        LOG_ALWAYS_FATAL("Properties needs exactly %d bytes, a byte array of size %d is provided",
                propertyCount, length);
        return false;
    }
    Properties* out = reinterpret_cast<Properties*>(outProperties);
    *out = mProperties;
    return true;
}

// TODO: Consider animating the properties as float pointers
float Group::getPropertyValue(int propertyId) const {
    Property currentProperty = static_cast<Property>(propertyId);
    switch (currentProperty) {
    case Property::Rotate:
        return mProperties.rotate;
    case Property::PivotX:
        return mProperties.pivotX;
    case Property::PivotY:
        return mProperties.pivotY;
    case Property::ScaleX:
        return mProperties.scaleX;
    case Property::ScaleY:
        return mProperties.scaleY;
    case Property::TranslateX:
        return mProperties.translateX;
    case Property::TranslateY:
        return mProperties.translateY;
    default:
        LOG_ALWAYS_FATAL("Invalid property index: %d", propertyId);
        return 0;
    }
}

void Group::setPropertyValue(int propertyId, float value) {
    Property currentProperty = static_cast<Property>(propertyId);
    switch (currentProperty) {
    case Property::Rotate:
        mProperties.rotate = value;
        break;
    case Property::PivotX:
        mProperties.pivotX = value;
        break;
    case Property::PivotY:
        mProperties.pivotY = value;
        break;
    case Property::ScaleX:
        mProperties.scaleX = value;
        break;
    case Property::ScaleY:
        mProperties.scaleY = value;
        break;
    case Property::TranslateX:
        mProperties.translateX = value;
        break;
    case Property::TranslateY:
        mProperties.translateY = value;
        break;
    default:
        LOG_ALWAYS_FATAL("Invalid property index: %d", propertyId);
    }
}

bool Group::isValidProperty(int propertyId) {
    return propertyId >= 0 && propertyId < static_cast<int>(Property::Count);
}

void Tree::draw(Canvas* outCanvas, SkColorFilter* colorFilter,
        const SkRect& bounds, bool needsMirroring, bool canReuseCache) {
    // The imageView can scale the canvas in different ways, in order to
    // avoid blurry scaling, we have to draw into a bitmap with exact pixel
    // size first. This bitmap size is determined by the bounds and the
    // canvas scale.
    outCanvas->getMatrix(&mCanvasMatrix);
    mBounds = bounds;
    float canvasScaleX = 1.0f;
    float canvasScaleY = 1.0f;
    if (mCanvasMatrix.getSkewX() == 0 && mCanvasMatrix.getSkewY() == 0) {
        // Only use the scale value when there's no skew or rotation in the canvas matrix.
        // TODO: Add a cts test for drawing VD on a canvas with negative scaling factors.
        canvasScaleX = fabs(mCanvasMatrix.getScaleX());
        canvasScaleY = fabs(mCanvasMatrix.getScaleY());
    }
    int scaledWidth = (int) (mBounds.width() * canvasScaleX);
    int scaledHeight = (int) (mBounds.height() * canvasScaleY);
    scaledWidth = std::min(Tree::MAX_CACHED_BITMAP_SIZE, scaledWidth);
    scaledHeight = std::min(Tree::MAX_CACHED_BITMAP_SIZE, scaledHeight);

    if (scaledWidth <= 0 || scaledHeight <= 0) {
        return;
    }

    mPaint.setColorFilter(colorFilter);

    int saveCount = outCanvas->save(SaveFlags::MatrixClip);
    outCanvas->translate(mBounds.fLeft, mBounds.fTop);

    // Handle RTL mirroring.
    if (needsMirroring) {
        outCanvas->translate(mBounds.width(), 0);
        outCanvas->scale(-1.0f, 1.0f);
    }

    // At this point, canvas has been translated to the right position.
    // And we use this bound for the destination rect for the drawBitmap, so
    // we offset to (0, 0);
    mBounds.offsetTo(0, 0);
    createCachedBitmapIfNeeded(scaledWidth, scaledHeight);

    outCanvas->drawVectorDrawable(this);

    outCanvas->restoreToCount(saveCount);
}

SkPaint* Tree::getPaint() {
    SkPaint* paint;
    if (mRootAlpha == 1.0f && mPaint.getColorFilter() == NULL) {
        paint = NULL;
    } else {
        mPaint.setFilterQuality(kLow_SkFilterQuality);
        mPaint.setAlpha(mRootAlpha * 255);
        paint = &mPaint;
    }
    return paint;
}

const SkBitmap& Tree::getBitmapUpdateIfDirty() {
    mCachedBitmap.eraseColor(SK_ColorTRANSPARENT);
    SkCanvas outCanvas(mCachedBitmap);
    float scaleX = (float) mCachedBitmap.width() / mViewportWidth;
    float scaleY = (float) mCachedBitmap.height() / mViewportHeight;
    mRootNode->draw(&outCanvas, SkMatrix::I(), scaleX, scaleY);
    mCacheDirty = false;
    return mCachedBitmap;
}

void Tree::createCachedBitmapIfNeeded(int width, int height) {
    if (!canReuseBitmap(width, height)) {
        SkImageInfo info = SkImageInfo::Make(width, height,
                kN32_SkColorType, kPremul_SkAlphaType);
        mCachedBitmap.setInfo(info);
        // TODO: Count the bitmap cache against app's java heap
        mCachedBitmap.allocPixels(info);
        mCacheDirty = true;
    }
}

bool Tree::canReuseBitmap(int width, int height) {
    return width == mCachedBitmap.width() && height == mCachedBitmap.height();
}

}; // namespace VectorDrawable

}; // namespace uirenderer
}; // namespace android
