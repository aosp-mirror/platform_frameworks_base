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

#include "LayerUpdateQueue.h"

#include "RenderNode.h"

namespace android {
namespace uirenderer {

void LayerUpdateQueue::clear() {
    mEntries.clear();
}

void LayerUpdateQueue::enqueueLayerWithDamage(RenderNode* renderNode, Rect damage) {
    damage.roundOut();
    damage.doIntersect(0, 0, renderNode->getWidth(), renderNode->getHeight());
    if (!damage.isEmpty()) {
        for (Entry& entry : mEntries) {
            if (CC_UNLIKELY(entry.renderNode == renderNode)) {
                entry.damage.unionWith(damage);
                return;
            }
        }
        mEntries.emplace_back(renderNode, damage);
    }
}

} // namespace uirenderer
} // namespace android
