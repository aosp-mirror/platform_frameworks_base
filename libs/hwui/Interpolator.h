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
    AccelerateDecelerateInterpolator() {}
    virtual ~AccelerateDecelerateInterpolator() {}

    virtual float interpolate(float input);
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
