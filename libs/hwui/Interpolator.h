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
#include <memory>

#include <cutils/compiler.h>
#include <vector>

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

class AccelerateDecelerateInterpolator : public Interpolator {
public:
    virtual float interpolate(float input) override;
};

class AccelerateInterpolator : public Interpolator {
public:
    explicit AccelerateInterpolator(float factor) : mFactor(factor), mDoubleFactor(factor * 2) {}
    virtual float interpolate(float input) override;

private:
    const float mFactor;
    const float mDoubleFactor;
};

class AnticipateInterpolator : public Interpolator {
public:
    explicit AnticipateInterpolator(float tension) : mTension(tension) {}
    virtual float interpolate(float input) override;

private:
    const float mTension;
};

class AnticipateOvershootInterpolator : public Interpolator {
public:
    explicit AnticipateOvershootInterpolator(float tension) : mTension(tension) {}
    virtual float interpolate(float input) override;

private:
    const float mTension;
};

class BounceInterpolator : public Interpolator {
public:
    virtual float interpolate(float input) override;
};

class CycleInterpolator : public Interpolator {
public:
    explicit CycleInterpolator(float cycles) : mCycles(cycles) {}
    virtual float interpolate(float input) override;

private:
    const float mCycles;
};

class DecelerateInterpolator : public Interpolator {
public:
    explicit DecelerateInterpolator(float factor) : mFactor(factor) {}
    virtual float interpolate(float input) override;

private:
    const float mFactor;
};

class LinearInterpolator : public Interpolator {
public:
    virtual float interpolate(float input) override { return input; }
};

class OvershootInterpolator : public Interpolator {
public:
    explicit OvershootInterpolator(float tension) : mTension(tension) {}
    virtual float interpolate(float input) override;

private:
    const float mTension;
};

class PathInterpolator : public Interpolator {
public:
    explicit PathInterpolator(std::vector<float>&& x, std::vector<float>&& y) : mX(x), mY(y) {}
    virtual float interpolate(float input) override;

private:
    std::vector<float> mX;
    std::vector<float> mY;
};

class LUTInterpolator : public Interpolator {
public:
    LUTInterpolator(float* values, size_t size);
    ~LUTInterpolator();

    virtual float interpolate(float input) override;

private:
    std::unique_ptr<float[]> mValues;
    size_t mSize;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* INTERPOLATOR_H */
