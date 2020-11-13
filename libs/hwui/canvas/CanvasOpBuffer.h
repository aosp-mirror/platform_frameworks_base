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
#include "OpBuffer.h"

namespace android::uirenderer {

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
class CanvasOpBuffer final : public OpBuffer<CanvasOpType, CanvasOpContainer> {
public:
    template <CanvasOpType T>
    void push(CanvasOp<T>&& op) {
        push_container(CanvasOpContainer<T>(std::move(op)));
    }
};

}  // namespace android::uirenderer
