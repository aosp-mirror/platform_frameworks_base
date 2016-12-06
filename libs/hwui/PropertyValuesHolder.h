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
    virtual ~PropertyValuesHolder() {}
};

template <typename T>
class Evaluator {
public:
    virtual void evaluate(T* out, const T& from, const T& to, float fraction) const {};
    virtual ~Evaluator() {}
};

class FloatEvaluator : public Evaluator<float> {
public:
    virtual void evaluate(float* out, const float& from, const float& to, float fraction)
            const override {
        *out = from * (1 - fraction) + to * fraction;
    }
};

class ANDROID_API ColorEvaluator : public Evaluator<SkColor> {
public:
    virtual void evaluate(SkColor* outColor, const SkColor& from, const SkColor& to,
            float fraction) const override;
};

class ANDROID_API PathEvaluator : public Evaluator<PathData> {
    virtual void evaluate(PathData* out, const PathData& from, const PathData& to, float fraction)
            const override;
};

template <typename T>
class ANDROID_API PropertyValuesHolderImpl : public PropertyValuesHolder {
public:
    PropertyValuesHolderImpl(const T& startValue, const T& endValue)
            : mStartValue(startValue)
            , mEndValue(endValue) {}
    void setPropertyDataSource(T* dataSource, int length) {
        mDataSource.insert(mDataSource.begin(), dataSource, dataSource + length);
    }
    // Calculate the animated value from the data source.
    const T getValueFromData(float fraction) const;
    // Convenient method to favor getting animated value from data source. If no data source is set
    // fall back to linear interpolation.
    const T calculateAnimatedValue(float fraction) const;
protected:
   std::unique_ptr<Evaluator<T>> mEvaluator = nullptr;
   // This contains uniformly sampled data throughout the animation duration. The first element
   // should be the start value and the last should be the end value of the animation. When the
   // data source is set, we'll favor data source over the linear interpolation of start/end value
   // for calculation of animated value.
   std::vector<T> mDataSource;
   T mStartValue;
   T mEndValue;
};

class ANDROID_API GroupPropertyValuesHolder : public PropertyValuesHolderImpl<float> {
public:
    GroupPropertyValuesHolder(VectorDrawable::Group* ptr, int propertyId, float startValue,
            float endValue)
            : PropertyValuesHolderImpl(startValue, endValue)
            , mGroup(ptr)
            , mPropertyId(propertyId) {
        mEvaluator.reset(new FloatEvaluator());
    }
    void setFraction(float fraction) override;
private:
    VectorDrawable::Group* mGroup;
    int mPropertyId;
};

class ANDROID_API FullPathColorPropertyValuesHolder : public PropertyValuesHolderImpl<SkColor> {
public:
    FullPathColorPropertyValuesHolder(VectorDrawable::FullPath* ptr, int propertyId,
            SkColor startValue, SkColor endValue)
            : PropertyValuesHolderImpl(startValue, endValue)
            , mFullPath(ptr)
            , mPropertyId(propertyId) {
        mEvaluator.reset(new ColorEvaluator());
    }
    void setFraction(float fraction) override;
    static SkColor interpolateColors(SkColor fromColor, SkColor toColor, float fraction);
private:
    VectorDrawable::FullPath* mFullPath;
    int mPropertyId;
};

class ANDROID_API FullPathPropertyValuesHolder : public PropertyValuesHolderImpl<float> {
public:
    FullPathPropertyValuesHolder(VectorDrawable::FullPath* ptr, int propertyId, float startValue,
            float endValue)
            : PropertyValuesHolderImpl(startValue, endValue)
            , mFullPath(ptr)
            , mPropertyId(propertyId) {
        mEvaluator.reset(new FloatEvaluator());
    };
    void setFraction(float fraction) override;
private:
    VectorDrawable::FullPath* mFullPath;
    int mPropertyId;
};

class ANDROID_API PathDataPropertyValuesHolder : public PropertyValuesHolderImpl<PathData> {
public:
    PathDataPropertyValuesHolder(VectorDrawable::Path* ptr, PathData* startValue,
            PathData* endValue)
            : PropertyValuesHolderImpl(*startValue, *endValue)
            , mPath(ptr) {
        mEvaluator.reset(new PathEvaluator());
    };
    void setFraction(float fraction) override;
private:
    VectorDrawable::Path* mPath;
    PathData mPathData;
};

class ANDROID_API RootAlphaPropertyValuesHolder : public PropertyValuesHolderImpl<float> {
public:
    RootAlphaPropertyValuesHolder(VectorDrawable::Tree* tree, float startValue, float endValue)
            : PropertyValuesHolderImpl(startValue, endValue)
            , mTree(tree) {
        mEvaluator.reset(new FloatEvaluator());
    }
    void setFraction(float fraction) override;
private:
    VectorDrawable::Tree* mTree;
};
}
}
