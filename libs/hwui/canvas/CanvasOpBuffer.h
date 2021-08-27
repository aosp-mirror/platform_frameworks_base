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

#pragma once

#include <SkMatrix.h>

#include "CanvasOpTypes.h"
#include "CanvasTransform.h"
#include "OpBuffer.h"
#include "TreeInfo.h"
#include "private/hwui/WebViewFunctor.h"

#include <functional>

namespace android::uirenderer {

class RenderNode;

template <CanvasOpType T>
struct CanvasOp;

template <CanvasOpType T>
class CanvasOpContainer {
private:
    BE_OPBUFFERS_FRIEND();

    OpBufferItemHeader<CanvasOpType> header;
    // TODO: Figure out some magic to make this not be here when it's identity (or not used)
    SkMatrix mTransform;
    CanvasOp<T> mImpl;

public:
    CanvasOpContainer(CanvasOp<T>&& impl, const SkMatrix& transform = SkMatrix::I())
            : mTransform(transform), mImpl(std::move(impl)) {}

    uint32_t size() const { return header.size; }
    CanvasOpType type() const { return header.type; }

    const SkMatrix& transform() const { return mTransform; }

    CanvasOp<T>* operator->() noexcept { return &mImpl; }
    const CanvasOp<T>* operator->() const noexcept { return &mImpl; }

    CanvasOp<T>& op() noexcept { return mImpl; }
    const CanvasOp<T>& op() const noexcept { return mImpl; }
};

extern template class OpBuffer<CanvasOpType, CanvasOpContainer>;
class CanvasOpBuffer final : private OpBuffer<CanvasOpType, CanvasOpContainer> {
private:
    using SUPER = OpBuffer<CanvasOpType, CanvasOpContainer>;

public:
    // Expose select superclass methods publicly
    using SUPER::for_each;
    using SUPER::size;
    using SUPER::resize;

    template <CanvasOpType T>
    void push(CanvasOp<T>&& op) {
        push_container(CanvasOpContainer<T>(std::move(op)));
    }

    template <CanvasOpType T>
    void push_container(CanvasOpContainer<T>&& op) {
        if constexpr (IsDrawOp(T)) {
            mHas.content = true;
        }
        if constexpr (T == CanvasOpType::DrawRenderNode) {
            mHas.children = true;
            // use staging property, since recording on UI thread
            if (op->renderNode->stagingProperties().isProjectionReceiver()) {
                mHas.projectionReceiver = true;
            }
        }
        SUPER::push_container(std::move(op));
    }

    void clear() {
        mHas = Contains{};
        SUPER::clear();
    }

    void updateChildren(std::function<void(RenderNode*)> updateFn);
    bool prepareListAndChildren(
            TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer,
            std::function<void(RenderNode*, TreeObserver&, TreeInfo&, bool)> childFn);
    void syncContents(const WebViewSyncData& data);
    void onRemovedFromTree();
    void applyColorTransform(ColorTransform transform);

    [[nodiscard]] bool isEmpty() const { return !mHas.content; }
    [[nodiscard]] bool hasText() const { return mHas.text; }
    [[nodiscard]] bool hasVectorDrawables() const { return mHas.vectorDrawable; }
    [[nodiscard]] bool containsProjectionReceiver() const { return mHas.projectionReceiver; }
    [[nodiscard]] bool hasFunctor() const { return mHas.functor; }

    [[nodiscard]] size_t getUsedSize() const {
        return size();
    }

    [[nodiscard]] size_t getAllocatedSize() const {
        return capacity();
    }

    void output(std::ostream& output, uint32_t level) const;

private:
    struct Contains {
        bool content : 1 = false;
        bool children : 1 = false;
        bool projectionReceiver : 1 = false;
        bool text : 1 = false;
        bool vectorDrawable : 1 = false;
        bool functor : 1 = false;
    };
    Contains mHas;
};

}  // namespace android::uirenderer
