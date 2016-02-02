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
#ifndef DAMAGEACCUMULATOR_H
#define DAMAGEACCUMULATOR_H

#include <cutils/compiler.h>
#include <utils/LinearAllocator.h>

#include <SkMatrix.h>
#include <SkRect.h>

#include "utils/Macros.h"

// Smaller than INT_MIN/INT_MAX because we offset these values
// and thus don't want to be adding offsets to INT_MAX, that's bad
#define DIRTY_MIN (-0x7ffffff-1)
#define DIRTY_MAX (0x7ffffff)

namespace android {
namespace uirenderer {

struct DirtyStack;
class RenderNode;
class Matrix4;

class DamageAccumulator {
    PREVENT_COPY_AND_ASSIGN(DamageAccumulator);
public:
    DamageAccumulator();
    // mAllocator will clean everything up for us, no need for a dtor

    // Push a transform node onto the stack. This should be called prior
    // to any dirty() calls. Subsequent calls to dirty()
    // will be affected by the transform when popTransform() is called.
    void pushTransform(const RenderNode* transform);
    void pushTransform(const Matrix4* transform);

    // Pops a transform node from the stack, propagating the dirty rect
    // up to the parent node. Returns the IDamageTransform that was just applied
    void popTransform();

    void dirty(float left, float top, float right, float bottom);

    // Returns the current dirty area, *NOT* transformed by pushed transforms
    void peekAtDirty(SkRect* dest) const;

    ANDROID_API void computeCurrentTransform(Matrix4* outMatrix) const;

    void finish(SkRect* totalDirty);

private:
    void pushCommon();
    void applyMatrix4Transform(DirtyStack* frame);
    void applyRenderNodeTransform(DirtyStack* frame);

    LinearAllocator mAllocator;
    DirtyStack* mHead;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DAMAGEACCUMULATOR_H */
