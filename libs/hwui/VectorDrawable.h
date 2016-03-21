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

#ifndef ANDROID_HWUI_VPATH_H
#define ANDROID_HWUI_VPATH_H

#include "hwui/Canvas.h"

#include <SkBitmap.h>
#include <SkColor.h>
#include <SkCanvas.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkPathMeasure.h>
#include <SkRect.h>
#include <SkShader.h>

#include <cutils/compiler.h>
#include <stddef.h>
#include <vector>
#include <string>

namespace android {
namespace uirenderer {

namespace VectorDrawable {
#define VD_SET_PROP_WITH_FLAG(field, value, flag) (VD_SET_PROP(field, value) ? (flag = true, true): false);
#define VD_SET_PROP(field, value) (value != field ? (field = value, true) : false)

/* A VectorDrawable is composed of a tree of nodes.
 * Each node can be a group node, or a path.
 * A group node can have groups or paths as children, but a path node has
 * no children.
 * One example can be:
 *                 Root Group
 *                /    |     \
 *           Group    Path    Group
 *          /     \             |
 *         Path   Path         Path
 *
 */
class ANDROID_API Node {
public:
    Node(const Node& node) {
        mName = node.mName;
    }
    Node() {}
    virtual void draw(SkCanvas* outCanvas, const SkMatrix& currentMatrix,
            float scaleX, float scaleY) = 0;
    virtual void dump() = 0;
    void setName(const char* name) {
        mName = name;
    }
    virtual ~Node(){}
protected:
    std::string mName;
};

class ANDROID_API Path : public Node {
public:
    struct ANDROID_API Data {
        std::vector<char> verbs;
        std::vector<size_t> verbSizes;
        std::vector<float> points;
        bool operator==(const Data& data) const {
            return verbs == data.verbs && verbSizes == data.verbSizes
                    && points == data.points;
        }
    };
    Path(const Data& nodes);
    Path(const Path& path);
    Path(const char* path, size_t strLength);
    Path() {}
    void dump() override;
    bool canMorph(const Data& path);
    bool canMorph(const Path& path);
    void draw(SkCanvas* outCanvas, const SkMatrix& groupStackedMatrix,
            float scaleX, float scaleY) override;
    void setPath(const char* path, size_t strLength);
    void setPathData(const Data& data);
    static float getMatrixScale(const SkMatrix& groupStackedMatrix);

protected:
    virtual const SkPath& getUpdatedPath();
    virtual void drawPath(SkCanvas *outCanvas, SkPath& renderPath,
            float strokeScale, const SkMatrix& matrix) = 0;
    Data mData;
    SkPath mSkPath;
    bool mSkPathDirty = true;
};

class ANDROID_API FullPath: public Path {
public:

struct Properties {
    float strokeWidth = 0;
    SkColor strokeColor = SK_ColorTRANSPARENT;
    float strokeAlpha = 1;
    SkColor fillColor = SK_ColorTRANSPARENT;
    float fillAlpha = 1;
    float trimPathStart = 0;
    float trimPathEnd = 1;
    float trimPathOffset = 0;
    int32_t strokeLineCap = SkPaint::Cap::kButt_Cap;
    int32_t strokeLineJoin = SkPaint::Join::kMiter_Join;
    float strokeMiterLimit = 4;
    int fillType = 0; /* non-zero or kWinding_FillType in Skia */
};

    FullPath(const FullPath& path); // for cloning
    FullPath(const char* path, size_t strLength) : Path(path, strLength) {}
    FullPath() : Path() {}
    FullPath(const Data& nodes) : Path(nodes) {}

    ~FullPath() {
        SkSafeUnref(mFillGradient);
        SkSafeUnref(mStrokeGradient);
    }

    void updateProperties(float strokeWidth, SkColor strokeColor,
            float strokeAlpha, SkColor fillColor, float fillAlpha,
            float trimPathStart, float trimPathEnd, float trimPathOffset,
            float strokeMiterLimit, int strokeLineCap, int strokeLineJoin, int fillType);
    // TODO: Cleanup: Remove the setter and getters below, and their counterparts in java and JNI
    float getStrokeWidth() {
        return mProperties.strokeWidth;
    }
    void setStrokeWidth(float strokeWidth) {
        mProperties.strokeWidth = strokeWidth;
    }
    SkColor getStrokeColor() {
        return mProperties.strokeColor;
    }
    void setStrokeColor(SkColor strokeColor) {
        mProperties.strokeColor = strokeColor;
    }
    float getStrokeAlpha() {
        return mProperties.strokeAlpha;
    }
    void setStrokeAlpha(float strokeAlpha) {
        mProperties.strokeAlpha = strokeAlpha;
    }
    SkColor getFillColor() {
        return mProperties.fillColor;
    }
    void setFillColor(SkColor fillColor) {
        mProperties.fillColor = fillColor;
    }
    float getFillAlpha() {
        return mProperties.fillAlpha;
    }
    void setFillAlpha(float fillAlpha) {
        mProperties.fillAlpha = fillAlpha;
    }
    float getTrimPathStart() {
        return mProperties.trimPathStart;
    }
    void setTrimPathStart(float trimPathStart) {
        VD_SET_PROP_WITH_FLAG(mProperties.trimPathStart, trimPathStart, mTrimDirty);
    }
    float getTrimPathEnd() {
        return mProperties.trimPathEnd;
    }
    void setTrimPathEnd(float trimPathEnd) {
        VD_SET_PROP_WITH_FLAG(mProperties.trimPathEnd, trimPathEnd, mTrimDirty);
    }
    float getTrimPathOffset() {
        return mProperties.trimPathOffset;
    }
    void setTrimPathOffset(float trimPathOffset) {
        VD_SET_PROP_WITH_FLAG(mProperties.trimPathOffset, trimPathOffset, mTrimDirty);
    }
    bool getProperties(int8_t* outProperties, int length);
    void setColorPropertyValue(int propertyId, int32_t value);
    void setPropertyValue(int propertyId, float value);

    void setFillGradient(SkShader* fillGradient) {
        SkRefCnt_SafeAssign(mFillGradient, fillGradient);
    };
    void setStrokeGradient(SkShader* strokeGradient) {
        SkRefCnt_SafeAssign(mStrokeGradient, strokeGradient);
    };


protected:
    const SkPath& getUpdatedPath() override;
    void drawPath(SkCanvas* outCanvas, SkPath& renderPath,
            float strokeScale, const SkMatrix& matrix) override;

private:
    enum class Property {
        StrokeWidth = 0,
        StrokeColor,
        StrokeAlpha,
        FillColor,
        FillAlpha,
        TrimPathStart,
        TrimPathEnd,
        TrimPathOffset,
        StrokeLineCap,
        StrokeLineJoin,
        StrokeMiterLimit,
        FillType,
        Count,
    };
    // Applies trimming to the specified path.
    void applyTrim();
    Properties mProperties;
    bool mTrimDirty = true;
    SkPath mTrimmedSkPath;
    SkPaint mPaint;
    SkShader* mStrokeGradient = nullptr;
    SkShader* mFillGradient = nullptr;
};

class ANDROID_API ClipPath: public Path {
public:
    ClipPath(const ClipPath& path) : Path(path) {}
    ClipPath(const char* path, size_t strLength) : Path(path, strLength) {}
    ClipPath() : Path() {}
    ClipPath(const Data& nodes) : Path(nodes) {}

protected:
    void drawPath(SkCanvas* outCanvas, SkPath& renderPath,
            float strokeScale, const SkMatrix& matrix) override;
};

class ANDROID_API Group: public Node {
public:
    struct Properties {
        float rotate = 0;
        float pivotX = 0;
        float pivotY = 0;
        float scaleX = 1;
        float scaleY = 1;
        float translateX = 0;
        float translateY = 0;
    };
    Group(const Group& group);
    Group() {}
    float getRotation() {
        return mProperties.rotate;
    }
    void setRotation(float rotation) {
        mProperties.rotate = rotation;
    }
    float getPivotX() {
        return mProperties.pivotX;
    }
    void setPivotX(float pivotX) {
        mProperties.pivotX = pivotX;
    }
    float getPivotY() {
        return mProperties.pivotY;
    }
    void setPivotY(float pivotY) {
        mProperties.pivotY = pivotY;
    }
    float getScaleX() {
        return mProperties.scaleX;
    }
    void setScaleX(float scaleX) {
        mProperties.scaleX = scaleX;
    }
    float getScaleY() {
        return mProperties.scaleY;
    }
    void setScaleY(float scaleY) {
        mProperties.scaleY = scaleY;
    }
    float getTranslateX() {
        return mProperties.translateX;
    }
    void setTranslateX(float translateX) {
        mProperties.translateX = translateX;
    }
    float getTranslateY() {
        return mProperties.translateY;
    }
    void setTranslateY(float translateY) {
        mProperties.translateY = translateY;
    }
    virtual void draw(SkCanvas* outCanvas, const SkMatrix& currentMatrix,
            float scaleX, float scaleY) override;
    void updateLocalMatrix(float rotate, float pivotX, float pivotY,
            float scaleX, float scaleY, float translateX, float translateY);
    void getLocalMatrix(SkMatrix* outMatrix);
    void addChild(Node* child);
    void dump() override;
    bool getProperties(float* outProperties, int length);
    float getPropertyValue(int propertyId) const;
    void setPropertyValue(int propertyId, float value);
    static bool isValidProperty(int propertyId);

private:
    enum class Property {
        Rotate = 0,
        PivotX,
        PivotY,
        ScaleX,
        ScaleY,
        TranslateX,
        TranslateY,
        // Count of the properties, must be at the end.
        Count,
    };
    std::vector< std::unique_ptr<Node> > mChildren;
    Properties mProperties;
};

class ANDROID_API Tree : public VirtualLightRefBase {
public:
    Tree(Group* rootNode) : mRootNode(rootNode) {}
    void draw(Canvas* outCanvas, SkColorFilter* colorFilter,
            const SkRect& bounds, bool needsMirroring, bool canReuseCache);

    const SkBitmap& getBitmapUpdateIfDirty();
    void createCachedBitmapIfNeeded(int width, int height);
    bool canReuseBitmap(int width, int height);
    void setAllowCaching(bool allowCaching) {
        mAllowCaching = allowCaching;
    }
    bool setRootAlpha(float rootAlpha) {
        return VD_SET_PROP(mRootAlpha, rootAlpha);
    }

    float getRootAlpha() {
        return mRootAlpha;
    }
    void setViewportSize(float viewportWidth, float viewportHeight) {
        mViewportWidth = viewportWidth;
        mViewportHeight = viewportHeight;
    }
    SkPaint* getPaint();
    const SkRect& getBounds() const {
        return mBounds;
    }

private:
    // Cap the bitmap size, such that it won't hurt the performance too much
    // and it won't crash due to a very large scale.
    // The drawable will look blurry above this size.
    const static int MAX_CACHED_BITMAP_SIZE;

    bool mCacheDirty = true;
    bool mAllowCaching = true;
    float mViewportWidth = 0;
    float mViewportHeight = 0;
    float mRootAlpha = 1.0f;

    std::unique_ptr<Group> mRootNode;
    SkRect mBounds;
    SkMatrix mCanvasMatrix;
    SkPaint mPaint;
    SkPathMeasure mPathMeasure;
    SkBitmap mCachedBitmap;

};

} // namespace VectorDrawable

typedef VectorDrawable::Path::Data PathData;
} // namespace uirenderer
} // namespace android

#endif // ANDROID_HWUI_VPATH_H
