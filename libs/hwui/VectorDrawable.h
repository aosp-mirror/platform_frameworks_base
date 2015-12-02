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

#include "Canvas.h"
#include <SkBitmap.h>
#include <SkColor.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkPathMeasure.h>
#include <SkRect.h>

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
    virtual void draw(Canvas* outCanvas, const SkMatrix& currentMatrix,
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
    void draw(Canvas* outCanvas, const SkMatrix& groupStackedMatrix,
            float scaleX, float scaleY) override;
    void setPath(const char* path, size_t strLength);
    void setPathData(const Data& data);
    static float getMatrixScale(const SkMatrix& groupStackedMatrix);

protected:
    virtual const SkPath& getUpdatedPath();
    virtual void drawPath(Canvas *outCanvas, const SkPath& renderPath,
            float strokeScale) = 0;
    Data mData;
    SkPath mSkPath;
    bool mSkPathDirty = true;
};

class ANDROID_API FullPath: public Path {
public:
    FullPath(const FullPath& path); // for cloning
    FullPath(const char* path, size_t strLength) : Path(path, strLength) {}
    FullPath() : Path() {}
    FullPath(const Data& nodes) : Path(nodes) {}

    void updateProperties(float strokeWidth, SkColor strokeColor,
            float strokeAlpha, SkColor fillColor, float fillAlpha,
            float trimPathStart, float trimPathEnd, float trimPathOffset,
            float strokeMiterLimit, int strokeLineCap, int strokeLineJoin);
    float getStrokeWidth() {
        return mStrokeWidth;
    }
    void setStrokeWidth(float strokeWidth) {
        mStrokeWidth = strokeWidth;
    }
    SkColor getStrokeColor() {
        return mStrokeColor;
    }
    void setStrokeColor(SkColor strokeColor) {
        mStrokeColor = strokeColor;
    }
    float getStrokeAlpha() {
        return mStrokeAlpha;
    }
    void setStrokeAlpha(float strokeAlpha) {
        mStrokeAlpha = strokeAlpha;
    }
    SkColor getFillColor() {
        return mFillColor;
    }
    void setFillColor(SkColor fillColor) {
        mFillColor = fillColor;
    }
    float getFillAlpha() {
        return mFillAlpha;
    }
    void setFillAlpha(float fillAlpha) {
        mFillAlpha = fillAlpha;
    }
    float getTrimPathStart() {
        return mTrimPathStart;
    }
    void setTrimPathStart(float trimPathStart) {
        VD_SET_PROP_WITH_FLAG(mTrimPathStart, trimPathStart, mTrimDirty);
    }
    float getTrimPathEnd() {
        return mTrimPathEnd;
    }
    void setTrimPathEnd(float trimPathEnd) {
        VD_SET_PROP_WITH_FLAG(mTrimPathEnd, trimPathEnd, mTrimDirty);
    }
    float getTrimPathOffset() {
        return mTrimPathOffset;
    }
    void setTrimPathOffset(float trimPathOffset) {
        VD_SET_PROP_WITH_FLAG(mTrimPathOffset, trimPathOffset, mTrimDirty);
    }
    bool getProperties(int8_t* outProperties, int length);

protected:
    const SkPath& getUpdatedPath() override;
    void drawPath(Canvas* outCanvas, const SkPath& renderPath,
            float strokeScale) override;

private:
    // Applies trimming to the specified path.
    void applyTrim();
    float mStrokeWidth = 0;
    SkColor mStrokeColor = SK_ColorTRANSPARENT;
    float mStrokeAlpha = 1;
    SkColor mFillColor = SK_ColorTRANSPARENT;
    float mFillAlpha = 1;
    float mTrimPathStart = 0;
    float mTrimPathEnd = 1;
    float mTrimPathOffset = 0;
    bool mTrimDirty = true;
    SkPaint::Cap mStrokeLineCap = SkPaint::Cap::kButt_Cap;
    SkPaint::Join mStrokeLineJoin = SkPaint::Join::kMiter_Join;
    float mStrokeMiterLimit = 4;
    SkPath mTrimmedSkPath;
    SkPaint mPaint;
};

class ANDROID_API ClipPath: public Path {
public:
    ClipPath(const ClipPath& path) : Path(path) {}
    ClipPath(const char* path, size_t strLength) : Path(path, strLength) {}
    ClipPath() : Path() {}
    ClipPath(const Data& nodes) : Path(nodes) {}

protected:
    void drawPath(Canvas* outCanvas, const SkPath& renderPath,
            float strokeScale) override;
};

class ANDROID_API Group: public Node {
public:
    Group(const Group& group);
    Group() {}
    float getRotation() {
        return mRotate;
    }
    void setRotation(float rotation) {
        mRotate = rotation;
    }
    float getPivotX() {
        return mPivotX;
    }
    void setPivotX(float pivotX) {
        mPivotX = pivotX;
    }
    float getPivotY() {
        return mPivotY;
    }
    void setPivotY(float pivotY) {
        mPivotY = pivotY;
    }
    float getScaleX() {
        return mScaleX;
    }
    void setScaleX(float scaleX) {
        mScaleX = scaleX;
    }
    float getScaleY() {
        return mScaleY;
    }
    void setScaleY(float scaleY) {
        mScaleY = scaleY;
    }
    float getTranslateX() {
        return mTranslateX;
    }
    void setTranslateX(float translateX) {
        mTranslateX = translateX;
    }
    float getTranslateY() {
        return mTranslateY;
    }
    void setTranslateY(float translateY) {
        mTranslateY = translateY;
    }
    virtual void draw(Canvas* outCanvas, const SkMatrix& currentMatrix,
            float scaleX, float scaleY) override;
    void updateLocalMatrix(float rotate, float pivotX, float pivotY,
            float scaleX, float scaleY, float translateX, float translateY);
    void getLocalMatrix(SkMatrix* outMatrix);
    void addChild(Node* child);
    void dump() override;
    bool getProperties(float* outProperties, int length);

private:
    enum class Property {
        Rotate_Property = 0,
        PivotX_Property,
        PivotY_Property,
        ScaleX_Property,
        ScaleY_Property,
        TranslateX_Property,
        TranslateY_Property,
        // Count of the properties, must be at the end.
        Count,
    };
    float mRotate = 0;
    float mPivotX = 0;
    float mPivotY = 0;
    float mScaleX = 1;
    float mScaleY = 1;
    float mTranslateX = 0;
    float mTranslateY = 0;
    std::vector<Node*> mChildren;
};

class ANDROID_API Tree {
public:
    Tree(Group* rootNode) : mRootNode(rootNode) {}
    void draw(Canvas* outCanvas, SkColorFilter* colorFilter,
            const SkRect& bounds, bool needsMirroring, bool canReuseCache);
    void drawCachedBitmapWithRootAlpha(Canvas* outCanvas, SkColorFilter* filter,
            const SkRect& originalBounds);

    void updateCachedBitmap(int width, int height);
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

    Group* mRootNode;
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
