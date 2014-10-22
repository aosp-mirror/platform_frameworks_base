/*
 * Copyright (C) 2014 The Android Open Source Project
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
#ifndef INTERPOLATOR_H
#define INTERPOLATOR_H

#include <stddef.h>

#include <cutils/compiler.h>

namespace android {
namespace uirenderer {

class Interpolator {
public:
    virtual ~Interpolator() {}

    virtual float interpolate(float input) = 0;

    static Interpolator* createDefaultInterpolator();

protected:
    Interpolator() {}
};

class ANDROID_API AccelerateDecelerateInterpolator : public Interpolator {
public:
    virtual float interpolate(float input);
};

class ANDROID_API AccelerateInterpolator : public Interpolator {
public:
    AccelerateInterpolator(float factor) : mFactor(factor), mDoubleFactor(factor*2) {}
    virtual float interpolate(float input);
private:
    const float mFactor;
    const float mDoubleFactor;
};

class ANDROID_API AnticipateInterpolator : public Interpolator {
public:
    AnticipateInterpolator(float tension) : mTension(tension) {}
    virtual float interpolate(float input);
private:
    const float mTension;
};

class ANDROID_API AnticipateOvershootInterpolator : public Interpolator {
public:
    AnticipateOvershootInterpolator(float tension) : mTension(tension) {}
    virtual float interpolate(float input);
private:
    const float mTension;
};

class ANDROID_API BounceInterpolator : public Interpolator {
public:
    virtual float interpolate(float input);
};

class ANDROID_API CycleInterpolator : public Interpolator {
public:
    CycleInterpolator(float cycles) : mCycles(cycles) {}
    virtual float interpolate(float input);
private:
    const float mCycles;
};

class ANDROID_API DecelerateInterpolator : public Interpolator {
public:
    DecelerateInterpolator(float factor) : mFactor(factor) {}
    virtual float interpolate(float input);
private:
    const float mFactor;
};

class ANDROID_API LinearInterpolator : public Interpolator {
public:
    virtual float interpolate(float input) { return input; }
};

class ANDROID_API OvershootInterpolator : public Interpolator {
public:
    OvershootInterpolator(float tension) : mTension(tension) {}
    virtual float interpolate(float input);
private:
    const float mTension;
};

class ANDROID_API LUTInterpolator : public Interpolator {
public:
    LUTInterpolator(float* values, size_t size);
    ~LUTInterpolator();

    virtual float interpolate(float input);

private:
    float* mValues;
    size_t mSize;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* INTERPOLATOR_H */
