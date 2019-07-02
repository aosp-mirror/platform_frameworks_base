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

#ifndef ANDROID_HWUI_LAYER_UPDATE_QUEUE_H
#define ANDROID_HWUI_LAYER_UPDATE_QUEUE_H

#include <utils/StrongPointer.h>
#include "Rect.h"
#include "RenderNode.h"
#include "utils/Macros.h"

#include <unordered_map>
#include <vector>

namespace android {
namespace uirenderer {

class RenderNode;

class LayerUpdateQueue {
    PREVENT_COPY_AND_ASSIGN(LayerUpdateQueue);

public:
    struct Entry {
        Entry(RenderNode* renderNode, const Rect& damage)
                : renderNode(renderNode), damage(damage) {}
        sp<RenderNode> renderNode;
        Rect damage;
    };

    LayerUpdateQueue() {}
    void enqueueLayerWithDamage(RenderNode* renderNode, Rect dirty);
    void clear();
    const std::vector<Entry>& entries() const { return mEntries; }

private:
    std::vector<Entry> mEntries;
};

}  // namespace uirenderer
}  // namespace android

#endif  // ANDROID_HWUI_LAYER_UPDATE_QUEUE_H
