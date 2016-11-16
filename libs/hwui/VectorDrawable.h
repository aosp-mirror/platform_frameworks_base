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
#include "DisplayList.h"

#include <SkBitmap.h>
#include <SkColor.h>
#include <SkColorFilter.h>
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
#define VD_SET_PRIMITIVE_FIELD_WITH_FLAG(field, value, flag) (VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(field, (value)) ? ((flag) = true, true) : false)
#define VD_SET_PROP(field, value) ((value) != (field) ? ((field) = (value), true) : false)
#define VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(field, value) ({ bool retVal = VD_SET_PROP((mPrimitiveFields.field), (value));\
    onPropertyChanged(); retVal;})
#define UPDATE_SKPROP(field, value) ({bool retVal = ((field) != (value)); if ((field) != (value)) SkRefCnt_SafeAssign((field), (value)); retVal;})

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
 * VectorDrawables are drawn into bitmap caches first, then the caches are drawn to the given
 * canvas with root alpha applied. Two caches are maintained for VD, one in UI thread, the other in
 * Render Thread. A generation id is used to keep track of changes in the vector drawable tree.
 * Each cache has their own generation id to track whether they are up to date with the latest
 * change in the tree.
 *
 * Any property change to the vector drawable coming from UI thread (such as bulk setters to update
 * all the properties, and viewport change, etc.) are only modifying the staging properties. The
 * staging properties will then be marked dirty and will be pushed over to render thread properties
 * at sync point. If staging properties are not dirty at sync point, we sync backwards by updating
 * staging properties with render thread properties to reflect the latest animation value.
 *
 */

class PropertyChangedListener {
public:
    PropertyChangedListener(bool* dirty, bool* stagingDirty)
            : mDirty(dirty), mStagingDirty(stagingDirty) {}
    void onPropertyChanged() {
            *mDirty = true;
    }
    void onStagingPropertyChanged() {
            *mStagingDirty = true;
    }
private:
    bool* mDirty;
    bool* mStagingDirty;
};

class ANDROID_API Node {
public:
    class Properties {
    public:
        Properties(Node* node) : mNode(node) {}
        inline void onPropertyChanged() {
            mNode->onPropertyChanged(this);
        }
    private:
        Node* mNode;
    };
    Node(const Node& node) {
        mName = node.mName;
    }
    Node() {}
    virtual void draw(SkCanvas* outCanvas, const SkMatrix& currentMatrix,
            float scaleX, float scaleY, bool useStagingData) = 0;
    virtual void dump() = 0;
    void setName(const char* name) {
        mName = name;
    }
    virtual void setPropertyChangedListener(PropertyChangedListener* listener) {
        mPropertyChangedListener = listener;
    }
    virtual void onPropertyChanged(Properties* properties) = 0;
    virtual ~Node(){}
    virtual void syncProperties() = 0;
protected:
    std::string mName;
    PropertyChangedListener* mPropertyChangedListener = nullptr;
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

    class PathProperties : public Properties {
    public:
        PathProperties(Node* node) : Properties(node) {}
        void syncProperties(const PathProperties& prop) {
            mData = prop.mData;
            onPropertyChanged();
        }
        void setData(const Data& data) {
            // Updates the path data. Note that we don't generate a new Skia path right away
            // because there are cases where the animation is changing the path data, but the view
            // that hosts the VD has gone off screen, in which case we won't even draw. So we
            // postpone the Skia path generation to the draw time.
            if (data == mData) {
                return;
            }
            mData = data;
            onPropertyChanged();

        }
        const Data& getData() const {
            return mData;
        }
    private:
        Data mData;
    };

    Path(const Path& path);
    Path(const char* path, size_t strLength);
    Path() {}

    void dump() override;
    void draw(SkCanvas* outCanvas, const SkMatrix& groupStackedMatrix,
            float scaleX, float scaleY, bool useStagingData) override;
    static float getMatrixScale(const SkMatrix& groupStackedMatrix);
    virtual void syncProperties() override;
    virtual void onPropertyChanged(Properties* prop) override {
        if (prop == &mStagingProperties) {
            mStagingPropertiesDirty = true;
            if (mPropertyChangedListener) {
                mPropertyChangedListener->onStagingPropertyChanged();
            }
        } else if (prop == &mProperties){
            mSkPathDirty = true;
            if (mPropertyChangedListener) {
                mPropertyChangedListener->onPropertyChanged();
            }
        }
    }
    PathProperties* mutateStagingProperties() { return &mStagingProperties; }
    const PathProperties* stagingProperties() { return &mStagingProperties; }

    // This should only be called from animations on RT
    PathProperties* mutateProperties() { return &mProperties; }

protected:
    virtual const SkPath& getUpdatedPath();
    virtual void getStagingPath(SkPath* outPath);
    virtual void drawPath(SkCanvas *outCanvas, SkPath& renderPath,
            float strokeScale, const SkMatrix& matrix, bool useStagingData) = 0;

    // Internal data, render thread only.
    bool mSkPathDirty = true;
    SkPath mSkPath;

private:
    PathProperties mProperties = PathProperties(this);
    PathProperties mStagingProperties = PathProperties(this);
    bool mStagingPropertiesDirty = true;
};

class ANDROID_API FullPath: public Path {
public:
    class FullPathProperties : public Properties {
    public:
        struct PrimitiveFields {
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
        FullPathProperties(Node* mNode) : Properties(mNode), mTrimDirty(false) {}
        ~FullPathProperties() {
            SkSafeUnref(fillGradient);
            SkSafeUnref(strokeGradient);
        }
        void syncProperties(const FullPathProperties& prop) {
            mPrimitiveFields = prop.mPrimitiveFields;
            mTrimDirty = true;
            UPDATE_SKPROP(fillGradient, prop.fillGradient);
            UPDATE_SKPROP(strokeGradient, prop.strokeGradient);
            onPropertyChanged();
        }
        void setFillGradient(SkShader* gradient) {
            if(UPDATE_SKPROP(fillGradient, gradient)) {
                onPropertyChanged();
            }
        }
        void setStrokeGradient(SkShader* gradient) {
            if(UPDATE_SKPROP(strokeGradient, gradient)) {
                onPropertyChanged();
            }
        }
        SkShader* getFillGradient() const {
            return fillGradient;
        }
        SkShader* getStrokeGradient() const {
            return strokeGradient;
        }
        float getStrokeWidth() const{
            return mPrimitiveFields.strokeWidth;
        }
        void setStrokeWidth(float strokeWidth) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(strokeWidth, strokeWidth);
        }
        SkColor getStrokeColor() const{
            return mPrimitiveFields.strokeColor;
        }
        void setStrokeColor(SkColor strokeColor) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(strokeColor, strokeColor);
        }
        float getStrokeAlpha() const{
            return mPrimitiveFields.strokeAlpha;
        }
        void setStrokeAlpha(float strokeAlpha) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(strokeAlpha, strokeAlpha);
        }
        SkColor getFillColor() const {
            return mPrimitiveFields.fillColor;
        }
        void setFillColor(SkColor fillColor) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(fillColor, fillColor);
        }
        float getFillAlpha() const{
            return mPrimitiveFields.fillAlpha;
        }
        void setFillAlpha(float fillAlpha) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(fillAlpha, fillAlpha);
        }
        float getTrimPathStart() const{
            return mPrimitiveFields.trimPathStart;
        }
        void setTrimPathStart(float trimPathStart) {
            VD_SET_PRIMITIVE_FIELD_WITH_FLAG(trimPathStart, trimPathStart, mTrimDirty);
        }
        float getTrimPathEnd() const{
            return mPrimitiveFields.trimPathEnd;
        }
        void setTrimPathEnd(float trimPathEnd) {
            VD_SET_PRIMITIVE_FIELD_WITH_FLAG(trimPathEnd, trimPathEnd, mTrimDirty);
        }
        float getTrimPathOffset() const{
            return mPrimitiveFields.trimPathOffset;
        }
        void setTrimPathOffset(float trimPathOffset) {
            VD_SET_PRIMITIVE_FIELD_WITH_FLAG(trimPathOffset, trimPathOffset, mTrimDirty);
        }

        float getStrokeMiterLimit() const {
            return mPrimitiveFields.strokeMiterLimit;
        }
        float getStrokeLineCap() const {
            return mPrimitiveFields.strokeLineCap;
        }
        float getStrokeLineJoin() const {
            return mPrimitiveFields.strokeLineJoin;
        }
        float getFillType() const {
            return mPrimitiveFields.fillType;
        }
        bool copyProperties(int8_t* outProperties, int length) const;
        void updateProperties(float strokeWidth, SkColor strokeColor, float strokeAlpha,
                SkColor fillColor, float fillAlpha, float trimPathStart, float trimPathEnd,
                float trimPathOffset, float strokeMiterLimit, int strokeLineCap, int strokeLineJoin,
                int fillType) {
            mPrimitiveFields.strokeWidth = strokeWidth;
            mPrimitiveFields.strokeColor = strokeColor;
            mPrimitiveFields.strokeAlpha = strokeAlpha;
            mPrimitiveFields.fillColor = fillColor;
            mPrimitiveFields.fillAlpha = fillAlpha;
            mPrimitiveFields.trimPathStart = trimPathStart;
            mPrimitiveFields.trimPathEnd = trimPathEnd;
            mPrimitiveFields.trimPathOffset = trimPathOffset;
            mPrimitiveFields.strokeMiterLimit = strokeMiterLimit;
            mPrimitiveFields.strokeLineCap = strokeLineCap;
            mPrimitiveFields.strokeLineJoin = strokeLineJoin;
            mPrimitiveFields.fillType = fillType;
            mTrimDirty = true;
            onPropertyChanged();
        }
        // Set property values during animation
        void setColorPropertyValue(int propertyId, int32_t value);
        void setPropertyValue(int propertyId, float value);
        bool mTrimDirty;
    private:
        enum class Property {
            strokeWidth = 0,
            strokeColor,
            strokeAlpha,
            fillColor,
            fillAlpha,
            trimPathStart,
            trimPathEnd,
            trimPathOffset,
            strokeLineCap,
            strokeLineJoin,
            strokeMiterLimit,
            fillType,
            count,
        };
        PrimitiveFields mPrimitiveFields;
        SkShader* fillGradient = nullptr;
        SkShader* strokeGradient = nullptr;
    };

    // Called from UI thread
    FullPath(const FullPath& path); // for cloning
    FullPath(const char* path, size_t strLength) : Path(path, strLength) {}
    FullPath() : Path() {}
    void dump() override;
    FullPathProperties* mutateStagingProperties() { return &mStagingProperties; }
    const FullPathProperties* stagingProperties() { return &mStagingProperties; }

    // This should only be called from animations on RT
    FullPathProperties* mutateProperties() { return &mProperties; }

    virtual void syncProperties() override;
    virtual void onPropertyChanged(Properties* properties) override {
        Path::onPropertyChanged(properties);
        if (properties == &mStagingProperties) {
            mStagingPropertiesDirty = true;
            if (mPropertyChangedListener) {
                mPropertyChangedListener->onStagingPropertyChanged();
            }
        } else if (properties == &mProperties) {
            if (mPropertyChangedListener) {
                mPropertyChangedListener->onPropertyChanged();
            }
        }
    }

protected:
    const SkPath& getUpdatedPath() override;
    void getStagingPath(SkPath* outPath) override;
    void drawPath(SkCanvas* outCanvas, SkPath& renderPath,
            float strokeScale, const SkMatrix& matrix, bool useStagingData) override;
private:

    FullPathProperties mProperties = FullPathProperties(this);
    FullPathProperties mStagingProperties = FullPathProperties(this);
    bool mStagingPropertiesDirty = true;

    // Intermediate data for drawing, render thread only
    SkPath mTrimmedSkPath;

};

class ANDROID_API ClipPath: public Path {
public:
    ClipPath(const ClipPath& path) : Path(path) {}
    ClipPath(const char* path, size_t strLength) : Path(path, strLength) {}
    ClipPath() : Path() {}

protected:
    void drawPath(SkCanvas* outCanvas, SkPath& renderPath,
            float strokeScale, const SkMatrix& matrix, bool useStagingData) override;
};

class ANDROID_API Group: public Node {
public:
    class GroupProperties : public Properties {
    public:
        GroupProperties(Node* mNode) : Properties(mNode) {}
        struct PrimitiveFields {
            float rotate = 0;
            float pivotX = 0;
            float pivotY = 0;
            float scaleX = 1;
            float scaleY = 1;
            float translateX = 0;
            float translateY = 0;
        } mPrimitiveFields;
        void syncProperties(const GroupProperties& prop) {
            mPrimitiveFields = prop.mPrimitiveFields;
            onPropertyChanged();
        }
        float getRotation() const {
            return mPrimitiveFields.rotate;
        }
        void setRotation(float rotation) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(rotate, rotation);
        }
        float getPivotX() const {
            return mPrimitiveFields.pivotX;
        }
        void setPivotX(float pivotX) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(pivotX, pivotX);
        }
        float getPivotY() const {
            return mPrimitiveFields.pivotY;
        }
        void setPivotY(float pivotY) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(pivotY, pivotY);
        }
        float getScaleX() const {
            return mPrimitiveFields.scaleX;
        }
        void setScaleX(float scaleX) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(scaleX, scaleX);
        }
        float getScaleY() const {
            return mPrimitiveFields.scaleY;
        }
        void setScaleY(float scaleY) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(scaleY, scaleY);
        }
        float getTranslateX() const {
            return mPrimitiveFields.translateX;
        }
        void setTranslateX(float translateX) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(translateX, translateX);
        }
        float getTranslateY() const {
            return mPrimitiveFields.translateY;
        }
        void setTranslateY(float translateY) {
            VD_SET_PRIMITIVE_FIELD_AND_NOTIFY(translateY, translateY);
        }
        void updateProperties(float rotate, float pivotX, float pivotY,
                float scaleX, float scaleY, float translateX, float translateY) {
            mPrimitiveFields.rotate = rotate;
            mPrimitiveFields.pivotX = pivotX;
            mPrimitiveFields.pivotY = pivotY;
            mPrimitiveFields.scaleX = scaleX;
            mPrimitiveFields.scaleY = scaleY;
            mPrimitiveFields.translateX = translateX;
            mPrimitiveFields.translateY = translateY;
            onPropertyChanged();
        }
        void setPropertyValue(int propertyId, float value);
        float getPropertyValue(int propertyId) const;
        bool copyProperties(float* outProperties, int length) const;
        static bool isValidProperty(int propertyId);
    private:
        enum class Property {
            rotate = 0,
            pivotX,
            pivotY,
            scaleX,
            scaleY,
            translateX,
            translateY,
            // Count of the properties, must be at the end.
            count,
        };
    };

    Group(const Group& group);
    Group() {}
    void addChild(Node* child);
    virtual void setPropertyChangedListener(PropertyChangedListener* listener) override {
        Node::setPropertyChangedListener(listener);
        for (auto& child : mChildren) {
             child->setPropertyChangedListener(listener);
        }
    }
    virtual void syncProperties() override;
    GroupProperties* mutateStagingProperties() { return &mStagingProperties; }
    const GroupProperties* stagingProperties() { return &mStagingProperties; }

    // This should only be called from animations on RT
    GroupProperties* mutateProperties() { return &mProperties; }

    // Methods below could be called from either UI thread or Render Thread.
    virtual void draw(SkCanvas* outCanvas, const SkMatrix& currentMatrix,
            float scaleX, float scaleY, bool useStagingData) override;
    void getLocalMatrix(SkMatrix* outMatrix, const GroupProperties& properties);
    void dump() override;
    static bool isValidProperty(int propertyId);

    virtual void onPropertyChanged(Properties* properties) override {
        if (properties == &mStagingProperties) {
            mStagingPropertiesDirty = true;
            if (mPropertyChangedListener) {
                mPropertyChangedListener->onStagingPropertyChanged();
            }
        } else {
            if (mPropertyChangedListener) {
                mPropertyChangedListener->onPropertyChanged();
            }
        }
    }

private:
    GroupProperties mProperties = GroupProperties(this);
    GroupProperties mStagingProperties = GroupProperties(this);
    bool mStagingPropertiesDirty = true;
    std::vector< std::unique_ptr<Node> > mChildren;
};

class ANDROID_API Tree : public VirtualLightRefBase {
public:
    Tree(Group* rootNode) : mRootNode(rootNode) {
        mRootNode->setPropertyChangedListener(&mPropertyChangedListener);
    }

    // Copy properties from the tree and use the give node as the root node
    Tree(const Tree* copy, Group* rootNode) : Tree(rootNode) {
        mStagingProperties.syncAnimatableProperties(*copy->stagingProperties());
        mStagingProperties.syncNonAnimatableProperties(*copy->stagingProperties());
    }
    // Draws the VD onto a bitmap cache, then the bitmap cache will be rendered onto the input
    // canvas. Returns the number of pixels needed for the bitmap cache.
    int draw(Canvas* outCanvas, SkColorFilter* colorFilter,
            const SkRect& bounds, bool needsMirroring, bool canReuseCache);
    void drawStaging(Canvas* canvas);

    const SkBitmap& getBitmapUpdateIfDirty();
    void setAllowCaching(bool allowCaching) {
        mAllowCaching = allowCaching;
    }
    SkPaint* getPaint();
    void syncProperties() {
        if (mStagingProperties.mNonAnimatablePropertiesDirty) {
            mProperties.syncNonAnimatableProperties(mStagingProperties);
            mStagingProperties.mNonAnimatablePropertiesDirty = false;
        }

        if (mStagingProperties.mAnimatablePropertiesDirty) {
            mProperties.syncAnimatableProperties(mStagingProperties);
        } else {
            mStagingProperties.syncAnimatableProperties(mProperties);
        }
        mStagingProperties.mAnimatablePropertiesDirty = false;
        mRootNode->syncProperties();
    }

    class TreeProperties {
    public:
        TreeProperties(Tree* tree) : mTree(tree) {}
        // Properties that can only be modified by UI thread, therefore sync should
        // only go from UI to RT
        struct NonAnimatableProperties {
            float viewportWidth = 0;
            float viewportHeight = 0;
            SkRect bounds;
            int scaledWidth = 0;
            int scaledHeight = 0;
            SkColorFilter* colorFilter = nullptr;
            ~NonAnimatableProperties() {
                SkSafeUnref(colorFilter);
            }
        } mNonAnimatableProperties;
        bool mNonAnimatablePropertiesDirty = true;

        float mRootAlpha = 1.0f;
        bool mAnimatablePropertiesDirty = true;

        void syncNonAnimatableProperties(const TreeProperties& prop) {
            // Copy over the data that can only be changed in UI thread
            if (mNonAnimatableProperties.colorFilter != prop.mNonAnimatableProperties.colorFilter) {
                SkRefCnt_SafeAssign(mNonAnimatableProperties.colorFilter,
                        prop.mNonAnimatableProperties.colorFilter);
            }
            mNonAnimatableProperties = prop.mNonAnimatableProperties;
        }

        void setViewportSize(float width, float height) {
            if (mNonAnimatableProperties.viewportWidth != width
                    || mNonAnimatableProperties.viewportHeight != height) {
                mNonAnimatablePropertiesDirty = true;
                mNonAnimatableProperties.viewportWidth = width;
                mNonAnimatableProperties.viewportHeight = height;
                mTree->onPropertyChanged(this);
            }
        }
        void setBounds(const SkRect& bounds) {
            if (mNonAnimatableProperties.bounds != bounds) {
                mNonAnimatableProperties.bounds = bounds;
                mNonAnimatablePropertiesDirty = true;
                mTree->onPropertyChanged(this);
            }
        }

        void setScaledSize(int width, int height) {
            // If the requested size is bigger than what the bitmap was, then
            // we increase the bitmap size to match. The width and height
            // are bound by MAX_CACHED_BITMAP_SIZE.
            if (mNonAnimatableProperties.scaledWidth < width
                    || mNonAnimatableProperties.scaledHeight < height) {
                mNonAnimatableProperties.scaledWidth = std::max(width,
                        mNonAnimatableProperties.scaledWidth);
                mNonAnimatableProperties.scaledHeight = std::max(height,
                        mNonAnimatableProperties.scaledHeight);
                mNonAnimatablePropertiesDirty = true;
                mTree->onPropertyChanged(this);
            }
        }
        void setColorFilter(SkColorFilter* filter) {
            if (UPDATE_SKPROP(mNonAnimatableProperties.colorFilter, filter)) {
                mNonAnimatablePropertiesDirty = true;
                mTree->onPropertyChanged(this);
            }
        }
        SkColorFilter* getColorFilter() const{
            return mNonAnimatableProperties.colorFilter;
        }

        float getViewportWidth() const {
            return mNonAnimatableProperties.viewportWidth;
        }
        float getViewportHeight() const {
            return mNonAnimatableProperties.viewportHeight;
        }
        float getScaledWidth() const {
            return mNonAnimatableProperties.scaledWidth;
        }
        float getScaledHeight() const {
            return mNonAnimatableProperties.scaledHeight;
        }
        void syncAnimatableProperties(const TreeProperties& prop) {
            mRootAlpha = prop.mRootAlpha;
        }
        bool setRootAlpha(float rootAlpha) {
            if (rootAlpha != mRootAlpha) {
                mAnimatablePropertiesDirty = true;
                mRootAlpha = rootAlpha;
                mTree->onPropertyChanged(this);
                return true;
            }
            return false;
        }
        float getRootAlpha() const { return mRootAlpha;}
        const SkRect& getBounds() const {
            return mNonAnimatableProperties.bounds;
        }
        Tree* mTree;
    };
    void onPropertyChanged(TreeProperties* prop);
    TreeProperties* mutateStagingProperties() { return &mStagingProperties; }
    const TreeProperties* stagingProperties() const { return &mStagingProperties; }

    // This should only be called from animations on RT
    TreeProperties* mutateProperties() { return &mProperties; }

    // This should always be called from RT.
    void markDirty() { mCache.dirty = true; }
    bool isDirty() const { return mCache.dirty; }
    bool getPropertyChangeWillBeConsumed() const { return mWillBeConsumed; }
    void setPropertyChangeWillBeConsumed(bool willBeConsumed) { mWillBeConsumed = willBeConsumed; }

private:

    SkPaint* updatePaint(SkPaint* outPaint, TreeProperties* prop);
    bool allocateBitmapIfNeeded(SkBitmap* outCache, int width, int height);
    bool canReuseBitmap(const SkBitmap&, int width, int height);
    void updateBitmapCache(SkBitmap* outCache, bool useStagingData);
    // Cap the bitmap size, such that it won't hurt the performance too much
    // and it won't crash due to a very large scale.
    // The drawable will look blurry above this size.
    const static int MAX_CACHED_BITMAP_SIZE;

    bool mAllowCaching = true;
    std::unique_ptr<Group> mRootNode;

    TreeProperties mProperties = TreeProperties(this);
    TreeProperties mStagingProperties = TreeProperties(this);

    SkPaint mPaint;
    struct Cache {
        SkBitmap bitmap;
        bool dirty = true;
    };

    Cache mStagingCache;
    Cache mCache;

    PropertyChangedListener mPropertyChangedListener
            = PropertyChangedListener(&mCache.dirty, &mStagingCache.dirty);

    mutable bool mWillBeConsumed = false;
};

} // namespace VectorDrawable

typedef VectorDrawable::Path::Data PathData;
} // namespace uirenderer
} // namespace android

#endif // ANDROID_HWUI_VPATH_H
