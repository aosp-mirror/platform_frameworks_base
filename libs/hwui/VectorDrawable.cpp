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

#include <math.h>
#include <string.h>
#include <utils/Log.h>

#include "PathParser.h"
#include "SkColorFilter.h"
#include "SkImageInfo.h"
#include "SkShader.h"
#include "hwui/Paint.h"

#ifdef __ANDROID__
#include "renderthread/RenderThread.h"
#endif

#include <gui/TraceUtils.h>
#include "utils/Macros.h"
#include "utils/VectorDrawableUtils.h"

namespace android {
namespace uirenderer {
namespace VectorDrawable {

const int Tree::MAX_CACHED_BITMAP_SIZE = 2048;

void Path::dump() {
    ALOGD("Path: %s has %zu points", mName.c_str(), mProperties.getData().points.size());
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

const SkPath& Path::getUpdatedPath(bool useStagingData, SkPath* tempStagingPath) {
    if (useStagingData) {
        tempStagingPath->reset();
        VectorDrawableUtils::verbsToPath(tempStagingPath, mStagingProperties.getData());
        return *tempStagingPath;
    } else {
        if (mSkPathDirty) {
            mSkPath.reset();
            VectorDrawableUtils::verbsToPath(&mSkPath, mProperties.getData());
            mSkPathDirty = false;
        }
        return mSkPath;
    }
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

const SkPath& FullPath::getUpdatedPath(bool useStagingData, SkPath* tempStagingPath) {
    if (!useStagingData && !mSkPathDirty && !mProperties.mTrimDirty) {
        return mTrimmedSkPath;
    }
    Path::getUpdatedPath(useStagingData, tempStagingPath);
    SkPath* outPath;
    if (useStagingData) {
        SkPath inPath = *tempStagingPath;
        applyTrim(tempStagingPath, inPath, mStagingProperties.getTrimPathStart(),
                  mStagingProperties.getTrimPathEnd(), mStagingProperties.getTrimPathOffset());
        outPath = tempStagingPath;
    } else {
        if (mProperties.getTrimPathStart() != 0.0f || mProperties.getTrimPathEnd() != 1.0f) {
            mProperties.mTrimDirty = false;
            applyTrim(&mTrimmedSkPath, mSkPath, mProperties.getTrimPathStart(),
                      mProperties.getTrimPathEnd(), mProperties.getTrimPathOffset());
            outPath = &mTrimmedSkPath;
        } else {
            outPath = &mSkPath;
        }
    }
    const FullPathProperties& properties = useStagingData ? mStagingProperties : mProperties;
    bool setFillPath = properties.getFillGradient() != nullptr ||
                       properties.getFillColor() != SK_ColorTRANSPARENT;
    if (setFillPath) {
        outPath->setFillType(static_cast<SkPathFillType>(properties.getFillType()));
    }
    return *outPath;
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

void FullPath::draw(SkCanvas* outCanvas, bool useStagingData) {
    const FullPathProperties& properties = useStagingData ? mStagingProperties : mProperties;
    SkPath tempStagingPath;
    const SkPath& renderPath = getUpdatedPath(useStagingData, &tempStagingPath);

    // Draw path's fill, if fill color or gradient is valid
    bool needsFill = false;
    SkPaint paint;
    if (properties.getFillGradient() != nullptr) {
        paint.setColor(applyAlpha(SK_ColorBLACK, properties.getFillAlpha()));
        paint.setShader(sk_sp<SkShader>(SkSafeRef(properties.getFillGradient())));
        needsFill = true;
    } else if (properties.getFillColor() != SK_ColorTRANSPARENT) {
        paint.setColor(applyAlpha(properties.getFillColor(), properties.getFillAlpha()));
        needsFill = true;
    }

    if (needsFill) {
        paint.setStyle(SkPaint::Style::kFill_Style);
        paint.setAntiAlias(mAntiAlias);
        outCanvas->drawPath(renderPath, paint);
    }

    // Draw path's stroke, if stroke color or Gradient is valid
    bool needsStroke = false;
    if (properties.getStrokeGradient() != nullptr) {
        paint.setColor(applyAlpha(SK_ColorBLACK, properties.getStrokeAlpha()));
        paint.setShader(sk_sp<SkShader>(SkSafeRef(properties.getStrokeGradient())));
        needsStroke = true;
    } else if (properties.getStrokeColor() != SK_ColorTRANSPARENT) {
        paint.setColor(applyAlpha(properties.getStrokeColor(), properties.getStrokeAlpha()));
        needsStroke = true;
    }
    if (needsStroke) {
        paint.setStyle(SkPaint::Style::kStroke_Style);
        paint.setAntiAlias(mAntiAlias);
        paint.setStrokeJoin(SkPaint::Join(properties.getStrokeLineJoin()));
        paint.setStrokeCap(SkPaint::Cap(properties.getStrokeLineCap()));
        paint.setStrokeMiter(properties.getStrokeMiterLimit());
        paint.setStrokeWidth(properties.getStrokeWidth());
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
        LOG_ALWAYS_FATAL(
                "Error setting color property on FullPath: No valid property"
                " with id: %d",
                propertyId);
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

void ClipPath::draw(SkCanvas* outCanvas, bool useStagingData) {
    SkPath tempStagingPath;
    outCanvas->clipPath(getUpdatedPath(useStagingData, &tempStagingPath), true);
}

Group::Group(const Group& group) : Node(group) {
    mStagingProperties.syncProperties(group.mStagingProperties);
}

void Group::draw(SkCanvas* outCanvas, bool useStagingData) {
    // Save the current clip and matrix information, which is local to this group.
    SkAutoCanvasRestore saver(outCanvas, true);
    // apply the current group's matrix to the canvas
    SkMatrix stackedMatrix;
    const GroupProperties& prop = useStagingData ? mStagingProperties : mProperties;
    getLocalMatrix(&stackedMatrix, prop);
    outCanvas->concat(stackedMatrix);
    // Draw the group tree in the same order as the XML file.
    for (auto& child : mChildren) {
        child->draw(outCanvas, useStagingData);
    }
    // Restore the previous clip and matrix information.
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

int Tree::draw(Canvas* outCanvas, SkColorFilter* colorFilter, const SkRect& bounds,
               bool needsMirroring, bool canReuseCache) {
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
    int scaledWidth = (int)(bounds.width() * canvasScaleX);
    int scaledHeight = (int)(bounds.height() * canvasScaleY);
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
    bool redrawNeeded = allocateBitmapIfNeeded(mStagingCache, mStagingProperties.getScaledWidth(),
                                               mStagingProperties.getScaledHeight());
    // draw bitmap cache
    if (redrawNeeded || mStagingCache.dirty) {
        updateBitmapCache(*mStagingCache.bitmap, true);
        mStagingCache.dirty = false;
    }

    Paint skp;
    getPaintFor(&skp, mStagingProperties);
    Paint paint;
    paint.setFilterBitmap(skp.isFilterBitmap());
    paint.setColorFilter(skp.refColorFilter());
    paint.setAlpha(skp.getAlpha());
    outCanvas->drawBitmap(*mStagingCache.bitmap, 0, 0, mStagingCache.bitmap->width(),
                          mStagingCache.bitmap->height(), mStagingProperties.getBounds().left(),
                          mStagingProperties.getBounds().top(),
                          mStagingProperties.getBounds().right(),
                          mStagingProperties.getBounds().bottom(), &paint);
}

void Tree::getPaintFor(Paint* outPaint, const TreeProperties& prop) const {
    // HWUI always draws VD with bilinear filtering.
    outPaint->setFilterBitmap(true);
    if (prop.getColorFilter() != nullptr) {
        outPaint->setColorFilter(sk_ref_sp(prop.getColorFilter()));
    }
    outPaint->setAlpha(prop.getRootAlpha() * 255);
}

Bitmap& Tree::getBitmapUpdateIfDirty() {
    bool redrawNeeded = allocateBitmapIfNeeded(mCache, mProperties.getScaledWidth(),
                                               mProperties.getScaledHeight());
    if (redrawNeeded || mCache.dirty) {
        updateBitmapCache(*mCache.bitmap, false);
        mCache.dirty = false;
    }
    return *mCache.bitmap;
}

void Tree::draw(SkCanvas* canvas, const SkRect& bounds, const SkPaint& inPaint) {
    if (canvas->quickReject(bounds)) {
        // The RenderNode is on screen, but the AVD is not.
        return;
    }

    // Update the paint for any animatable properties
    SkPaint paint = inPaint;
    paint.setAlpha(mProperties.getRootAlpha() * 255);

    sk_sp<SkImage> cachedBitmap = getBitmapUpdateIfDirty().makeImage();

    // HWUI always draws VD with bilinear filtering.
    auto sampling = SkSamplingOptions(SkFilterMode::kLinear);
    int scaledWidth = SkScalarCeilToInt(mProperties.getScaledWidth());
    int scaledHeight = SkScalarCeilToInt(mProperties.getScaledHeight());
    canvas->drawImageRect(cachedBitmap, SkRect::MakeWH(scaledWidth, scaledHeight), bounds,
                          sampling, &paint, SkCanvas::kFast_SrcRectConstraint);
}

void Tree::updateBitmapCache(Bitmap& bitmap, bool useStagingData) {
    SkBitmap outCache;
    bitmap.getSkBitmap(&outCache);
    int cacheWidth = outCache.width();
    int cacheHeight = outCache.height();
    ATRACE_FORMAT("VectorDrawable repaint %dx%d", cacheWidth, cacheHeight);
    outCache.eraseColor(SK_ColorTRANSPARENT);
    SkCanvas outCanvas(outCache);
    float viewportWidth =
            useStagingData ? mStagingProperties.getViewportWidth() : mProperties.getViewportWidth();
    float viewportHeight = useStagingData ? mStagingProperties.getViewportHeight()
                                          : mProperties.getViewportHeight();
    float scaleX = cacheWidth / viewportWidth;
    float scaleY = cacheHeight / viewportHeight;
    outCanvas.scale(scaleX, scaleY);
    mRootNode->draw(&outCanvas, useStagingData);
}

bool Tree::allocateBitmapIfNeeded(Cache& cache, int width, int height) {
    if (!canReuseBitmap(cache.bitmap.get(), width, height)) {
        SkImageInfo info = SkImageInfo::MakeN32(width, height, kPremul_SkAlphaType);
        cache.bitmap = Bitmap::allocateHeapBitmap(info);
        return true;
    }
    return false;
}

bool Tree::canReuseBitmap(Bitmap* bitmap, int width, int height) {
    return bitmap && width <= bitmap->width() && height <= bitmap->height();
}

void Tree::onPropertyChanged(TreeProperties* prop) {
    if (prop == &mStagingProperties) {
        mStagingCache.dirty = true;
    } else {
        mCache.dirty = true;
    }
}

class MinMaxAverage {
public:
    void add(float sample) {
        if (mCount == 0) {
            mMin = sample;
            mMax = sample;
        } else {
            mMin = std::min(mMin, sample);
            mMax = std::max(mMax, sample);
        }
        mTotal += sample;
        mCount++;
    }

    float average() { return mTotal / mCount; }

    float min() { return mMin; }

    float max() { return mMax; }

    float delta() { return mMax - mMin; }

private:
    float mMin = 0.0f;
    float mMax = 0.0f;
    float mTotal = 0.0f;
    int mCount = 0;
};

BitmapPalette Tree::computePalette() {
    // TODO Cache this and share the code with Bitmap.cpp

    ATRACE_CALL();

    // TODO: This calculation of converting to HSV & tracking min/max is probably overkill
    // Experiment with something simpler since we just want to figure out if it's "color-ful"
    // and then the average perceptual lightness.

    MinMaxAverage hue, saturation, value;
    int sampledCount = 0;

    // Sample a grid of 100 pixels to get an overall estimation of the colors in play
    mRootNode->forEachFillColor([&](SkColor color) {
        if (SkColorGetA(color) < 75) {
            return;
        }
        sampledCount++;
        float hsv[3];
        SkColorToHSV(color, hsv);
        hue.add(hsv[0]);
        saturation.add(hsv[1]);
        value.add(hsv[2]);
    });

    if (sampledCount == 0) {
        ALOGV("VectorDrawable is mostly translucent");
        return BitmapPalette::Unknown;
    }

    ALOGV("samples = %d, hue [min = %f, max = %f, avg = %f]; saturation [min = %f, max = %f, avg = "
          "%f]; value [min = %f, max = %f, avg = %f]",
          sampledCount, hue.min(), hue.max(), hue.average(), saturation.min(), saturation.max(),
          saturation.average(), value.min(), value.max(), value.average());

    if (hue.delta() <= 20 && saturation.delta() <= .1f) {
        if (value.average() >= .5f) {
            return BitmapPalette::Light;
        } else {
            return BitmapPalette::Dark;
        }
    }
    return BitmapPalette::Unknown;
}

}  // namespace VectorDrawable

}  // namespace uirenderer
}  // namespace android
