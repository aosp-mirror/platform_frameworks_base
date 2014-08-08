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

namespace android {
namespace uirenderer {

struct DirtyStack;
class RenderNode;
class Matrix4;

class IDamageAccumulator {
public:
    virtual void pushTransform(const RenderNode* transform) = 0;
    virtual void pushTransform(const Matrix4* transform) = 0;
    virtual void popTransform() = 0;
    virtual void dirty(float left, float top, float right, float bottom) = 0;
    virtual void peekAtDirty(SkRect* dest) = 0;
protected:
    virtual ~IDamageAccumulator() {}
};

class DamageAccumulator : public IDamageAccumulator {
    PREVENT_COPY_AND_ASSIGN(DamageAccumulator);
public:
    DamageAccumulator();
    // mAllocator will clean everything up for us, no need for a dtor

    // Push a transform node onto the stack. This should be called prior
    // to any dirty() calls. Subsequent calls to dirty()
    // will be affected by the transform when popTransform() is called.
    virtual void pushTransform(const RenderNode* transform);
    virtual void pushTransform(const Matrix4* transform);

    // Pops a transform node from the stack, propagating the dirty rect
    // up to the parent node. Returns the IDamageTransform that was just applied
    virtual void popTransform();

    virtual void dirty(float left, float top, float right, float bottom);

    // Returns the current dirty area, *NOT* transformed by pushed transforms
    virtual void peekAtDirty(SkRect* dest);

    void finish(SkRect* totalDirty);

private:
    void pushCommon();
    void applyMatrix4Transform(DirtyStack* frame);
    void applyRenderNodeTransform(DirtyStack* frame);

    LinearAllocator mAllocator;
    DirtyStack* mHead;
};

class NullDamageAccumulator : public IDamageAccumulator {
    PREVENT_COPY_AND_ASSIGN(NullDamageAccumulator);
public:
    virtual void pushTransform(const RenderNode* transform) { }
    virtual void pushTransform(const Matrix4* transform) { }
    virtual void popTransform() { }
    virtual void dirty(float left, float top, float right, float bottom) { }
    virtual void peekAtDirty(SkRect* dest) { dest->setEmpty(); }

    ANDROID_API static NullDamageAccumulator* instance();

private:
    NullDamageAccumulator() {}
    ~NullDamageAccumulator() {}

    static NullDamageAccumulator sInstance;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DAMAGEACCUMULATOR_H */
