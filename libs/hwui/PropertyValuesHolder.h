/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

#include "VectorDrawable.h"

#include <SkColor.h>

namespace android {
namespace uirenderer {

/**
 * PropertyValues holder contains data needed to change a property of a Vector Drawable object.
 * When a fraction in [0f, 1f] is provided, the holder will calculate an interpolated value based
 * on its start and end value, and set the new value on the VectorDrawble's corresponding property.
 */
class ANDROID_API PropertyValuesHolder {
public:
    virtual void setFraction(float fraction) = 0;
    void setPropertyDataSource(float* dataSource, int length) {
        mDataSource.insert(mDataSource.begin(), dataSource, dataSource + length);
    }
   float getValueFromData(float fraction);
   virtual ~PropertyValuesHolder() {}
protected:
   std::vector<float> mDataSource;
};

class ANDROID_API GroupPropertyValuesHolder : public PropertyValuesHolder {
public:
    GroupPropertyValuesHolder(VectorDrawable::Group* ptr, int propertyId, float startValue,
            float endValue)
            : mGroup(ptr)
            , mPropertyId(propertyId)
            , mStartValue(startValue)
            , mEndValue(endValue){
    }
    void setFraction(float fraction) override;
private:
    VectorDrawable::Group* mGroup;
    int mPropertyId;
    float mStartValue;
    float mEndValue;
};

class ANDROID_API FullPathColorPropertyValuesHolder : public PropertyValuesHolder {
public:
    FullPathColorPropertyValuesHolder(VectorDrawable::FullPath* ptr, int propertyId, int32_t startValue,
            int32_t endValue)
            : mFullPath(ptr)
            , mPropertyId(propertyId)
            , mStartValue(startValue)
            , mEndValue(endValue) {};
    void setFraction(float fraction) override;
    static SkColor interpolateColors(SkColor fromColor, SkColor toColor, float fraction);
private:
    VectorDrawable::FullPath* mFullPath;
    int mPropertyId;
    int32_t mStartValue;
    int32_t mEndValue;
};

class ANDROID_API FullPathPropertyValuesHolder : public PropertyValuesHolder {
public:
    FullPathPropertyValuesHolder(VectorDrawable::FullPath* ptr, int propertyId, float startValue,
            float endValue)
            : mFullPath(ptr)
            , mPropertyId(propertyId)
            , mStartValue(startValue)
            , mEndValue(endValue) {};
    void setFraction(float fraction) override;
private:
    VectorDrawable::FullPath* mFullPath;
    int mPropertyId;
    float mStartValue;
    float mEndValue;
};

class ANDROID_API PathDataPropertyValuesHolder : public PropertyValuesHolder {
public:
    PathDataPropertyValuesHolder(VectorDrawable::Path* ptr, PathData* startValue,
            PathData* endValue)
            : mPath(ptr)
            , mStartValue(*startValue)
            , mEndValue(*endValue) {};
    void setFraction(float fraction) override;
private:
    VectorDrawable::Path* mPath;
    PathData mPathData;
    PathData mStartValue;
    PathData mEndValue;
};

class ANDROID_API RootAlphaPropertyValuesHolder : public PropertyValuesHolder {
public:
    RootAlphaPropertyValuesHolder(VectorDrawable::Tree* tree, float startValue, float endValue)
            : mTree(tree)
            , mStartValue(startValue)
            , mEndValue(endValue) {}
    void setFraction(float fraction) override;
private:
    VectorDrawable::Tree* mTree;
    float mStartValue;
    float mEndValue;
};
}
}
