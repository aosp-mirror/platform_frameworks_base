/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "CanvasOpBuffer.h"

#include "CanvasOps.h"

namespace android::uirenderer {

template class OpBuffer<CanvasOpType, CanvasOpContainer>;

void CanvasOpBuffer::updateChildren(std::function<void(RenderNode*)> updateFn) {
    // TODO: Do we need a fast-path for finding children?
    if (mHas.children) {
        for (auto& iter : filter<CanvasOpType::DrawRenderNode>()) {
            updateFn(iter->renderNode.get());
        }
    }
}

void CanvasOpBuffer::output(std::ostream& output, uint32_t level) const {
    LOG_ALWAYS_FATAL("TODO");
}

bool CanvasOpBuffer::prepareListAndChildren(
            TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer,
            std::function<void(RenderNode*, TreeObserver&, TreeInfo&, bool)> childFn) {
    LOG_ALWAYS_FATAL("TODO");
    return false;
}

void CanvasOpBuffer::syncContents(const WebViewSyncData& data) {
    LOG_ALWAYS_FATAL("TODO");
}

void CanvasOpBuffer::onRemovedFromTree() {
    LOG_ALWAYS_FATAL("TODO");
}

void CanvasOpBuffer::applyColorTransform(ColorTransform transform) {
    LOG_ALWAYS_FATAL("TODO");
}

}  // namespace android::uirenderer
