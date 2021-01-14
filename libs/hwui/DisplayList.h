/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "pipeline/skia/SkiaDisplayList.h"

#include <memory>

namespace android {
namespace uirenderer {

namespace VectorDrawable {
class Tree;
};
typedef uirenderer::VectorDrawable::Tree VectorDrawableRoot;

/**
 * Data structure that holds the list of commands used in display list stream
 */
//using DisplayList = skiapipeline::SkiaDisplayList;
class DisplayList {
public:
    // Constructs an empty (invalid) DisplayList
    explicit DisplayList() {}

    // Constructs a DisplayList from a SkiaDisplayList
    explicit DisplayList(std::unique_ptr<skiapipeline::SkiaDisplayList> impl)
        : mImpl(std::move(impl)) {}

    // Move support
    DisplayList(DisplayList&& other) : mImpl(std::move(other.mImpl)) {}
    DisplayList& operator=(DisplayList&& other) {
        mImpl = std::move(other.mImpl);
        return *this;
    }

    // No copy support
    DisplayList(const DisplayList& other) = delete;
    DisplayList& operator=(const DisplayList&) = delete;

    void updateChildren(std::function<void(RenderNode*)> updateFn) {
        mImpl->updateChildren(std::move(updateFn));
    }

    [[nodiscard]] explicit operator bool() const {
        return mImpl.get() != nullptr;
    }

    // If true this DisplayList contains a backing content, even if that content is empty
    // If false, there this DisplayList is in an "empty" state
    [[nodiscard]] bool isValid() const {
        return mImpl.get() != nullptr;
    }

    [[nodiscard]] bool isEmpty() const {
        return !hasContent();
    }

    [[nodiscard]] bool hasContent() const {
        return mImpl && !(mImpl->isEmpty());
    }

    [[nodiscard]] bool containsProjectionReceiver() const {
        return mImpl && mImpl->containsProjectionReceiver();
    }

    [[nodiscard]] skiapipeline::SkiaDisplayList* asSkiaDl() {
        return mImpl.get();
    }

    [[nodiscard]] const skiapipeline::SkiaDisplayList* asSkiaDl() const {
        return mImpl.get();
    }

    [[nodiscard]] bool hasVectorDrawables() const {
        return mImpl && mImpl->hasVectorDrawables();
    }

    void clear(RenderNode* owningNode = nullptr) {
        if (mImpl && owningNode && mImpl->reuseDisplayList(owningNode)) {
            // TODO: This is a bit sketchy to have a unique_ptr temporarily owned twice
            // Do something to cleanup reuseDisplayList passing itself to the RenderNode
            mImpl.release();
        } else {
            mImpl = nullptr;
        }
    }

    [[nodiscard]] size_t getUsedSize() const {
        return mImpl ? mImpl->getUsedSize() : 0;
    }

    [[nodiscard]] size_t getAllocatedSize() const {
        return mImpl ? mImpl->getAllocatedSize() : 0;
    }

    void output(std::ostream& output, uint32_t level) const {
        if (mImpl) {
            mImpl->output(output, level);
        }
    }

    [[nodiscard]] bool hasFunctor() const {
        return mImpl && mImpl->hasFunctor();
    }

    bool prepareListAndChildren(
            TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer,
            std::function<void(RenderNode*, TreeObserver&, TreeInfo&, bool)> childFn) {
        return mImpl && mImpl->prepareListAndChildren(
                observer, info, functorsNeedLayer, std::move(childFn));
    }

    void syncContents(const WebViewSyncData& data) {
        if (mImpl) {
            mImpl->syncContents(data);
        }
    }

    [[nodiscard]] bool hasText() const {
        return mImpl && mImpl->hasText();
    }

    void applyColorTransform(ColorTransform transform) {
        if (mImpl) {
            mImpl->mDisplayList.applyColorTransform(transform);
        }
    }

private:
    std::unique_ptr<skiapipeline::SkiaDisplayList> mImpl;
};

}  // namespace uirenderer
}  // namespace android
