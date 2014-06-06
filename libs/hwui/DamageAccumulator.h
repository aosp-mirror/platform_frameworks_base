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

#include <utils/LinearAllocator.h>

#include <SkMatrix.h>
#include <SkRect.h>

#include "utils/Macros.h"

namespace android {
namespace uirenderer {

struct DirtyStack;
class RenderNode;

class DamageAccumulator {
    PREVENT_COPY_AND_ASSIGN(DamageAccumulator);
public:
    DamageAccumulator();
    // mAllocator will clean everything up for us, no need for a dtor

    // Push a transform node onto the stack. This should be called prior
    // to any dirty() calls. Subsequent calls to dirty()
    // will be affected by the node's transform when popNode() is called.
    void pushNode(const RenderNode* node);
    // Pops a transform node from the stack, propagating the dirty rect
    // up to the parent node.
    void popNode();
    void dirty(float left, float top, float right, float bottom);

    void finish(SkRect* totalDirty);

private:
    LinearAllocator mAllocator;
    DirtyStack* mHead;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DAMAGEACCUMULATOR_H */
