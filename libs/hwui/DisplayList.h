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
#include "canvas/CanvasOpBuffer.h"

#include <memory>
#include <variant>

namespace android {
namespace uirenderer {

namespace VectorDrawable {
class Tree;
};
typedef uirenderer::VectorDrawable::Tree VectorDrawableRoot;

class SkiaDisplayListWrapper {
public:
    // Constructs an empty (invalid) DisplayList
    explicit SkiaDisplayListWrapper() {}

    // Constructs a DisplayList from a SkiaDisplayList
    explicit SkiaDisplayListWrapper(std::unique_ptr<skiapipeline::SkiaDisplayList> impl)
        : mImpl(std::move(impl)) {}

    // Move support
    SkiaDisplayListWrapper(SkiaDisplayListWrapper&& other) : mImpl(std::move(other.mImpl)) {}
    SkiaDisplayListWrapper& operator=(SkiaDisplayListWrapper&& other) {
        mImpl = std::move(other.mImpl);
        return *this;
    }

    // No copy support
    SkiaDisplayListWrapper(const SkiaDisplayListWrapper& other) = delete;
    SkiaDisplayListWrapper& operator=(const SkiaDisplayListWrapper&) = delete;

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

    [[nodiscard]] bool hasHolePunches() const {
        return mImpl && mImpl->hasHolePunches();
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

    void onRemovedFromTree() {
        if (mImpl) {
            mImpl->onRemovedFromTree();
        }
    }

    [[nodiscard]] bool hasText() const {
        return mImpl && mImpl->hasText();
    }

    void applyColorTransform(ColorTransform transform) {
        if (mImpl) {
            mImpl->applyColorTransform(transform);
        }
    }

private:
    std::unique_ptr<skiapipeline::SkiaDisplayList> mImpl;
};


/**
 * Data structure that holds the list of commands used in display list stream
 */
//using DisplayList = skiapipeline::SkiaDisplayList;
class MultiDisplayList {
private:
    using SkiaDisplayList = skiapipeline::SkiaDisplayList;

    struct EmptyList {
        bool hasText() const { return false; }
        void updateChildren(std::function<void(RenderNode*)> updateFn) {}
        bool isEmpty() const { return true; }
        bool containsProjectionReceiver() const { return false; }
        bool hasVectorDrawables() const { return false; }
        size_t getUsedSize() const { return 0; }
        size_t getAllocatedSize() const { return 0; }
        void output(std::ostream& output, uint32_t level) const { }
        bool hasFunctor() const { return false; }
        bool prepareListAndChildren(
                TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer,
                std::function<void(RenderNode*, TreeObserver&, TreeInfo&, bool)> childFn) {
            return false;
        }
        void syncContents(const WebViewSyncData& data) { }
        void onRemovedFromTree() { }
        void applyColorTransform(ColorTransform transform) { }
    };

    std::variant<EmptyList, std::unique_ptr<SkiaDisplayList>, CanvasOpBuffer> mImpls;

    template <typename T>
    static constexpr T& get(T& t) { return t; }
    template <typename T>
    static constexpr const T& get(const T& t) { return t; }

    template <typename T>
    static constexpr T& get(std::unique_ptr<T>& t) { return *t; }
    template <typename T>
    static constexpr const T& get(const std::unique_ptr<T>& t) { return *t; }

    template <typename T>
    auto apply(T&& t) {
        return std::visit([&t](auto& it) -> auto {
            return t(get(it));
        }, mImpls);
    }

    template <typename T>
    auto apply(T&& t) const {
        return std::visit([&t](const auto& it) -> auto {
            return t(get(it));
        }, mImpls);
    }

public:
    // Constructs an empty (invalid) DisplayList
    explicit MultiDisplayList() {}

    // Constructs a DisplayList from a SkiaDisplayList
    explicit MultiDisplayList(std::unique_ptr<SkiaDisplayList> impl)
        : mImpls(std::move(impl)) {}

    explicit MultiDisplayList(CanvasOpBuffer&& opBuffer) : mImpls(std::move(opBuffer)) {}

    // Move support
    MultiDisplayList(MultiDisplayList&& other) : mImpls(std::move(other.mImpls)) {}
    MultiDisplayList& operator=(MultiDisplayList&& other) {
        mImpls = std::move(other.mImpls);
        return *this;
    }

    // No copy support
    MultiDisplayList(const MultiDisplayList& other) = delete;
    MultiDisplayList& operator=(const MultiDisplayList&) = delete;

    void updateChildren(std::function<void(RenderNode*)> updateFn) {
        apply([&](auto& it) { it.updateChildren(std::move(updateFn)); });
    }

    [[nodiscard]] explicit operator bool() const {
        return isValid();
    }

    // If true this DisplayList contains a backing content, even if that content is empty
    // If false, there this DisplayList is in an "empty" state
    [[nodiscard]] bool isValid() const {
        return mImpls.index() != 0;
    }

    [[nodiscard]] bool isEmpty() const {
        return apply([](const auto& it) -> auto { return it.isEmpty(); });
    }

    [[nodiscard]] bool hasContent() const {
        return !isEmpty();
    }

    [[nodiscard]] bool containsProjectionReceiver() const {
        return apply([](const auto& it) -> auto { return it.containsProjectionReceiver(); });
    }

    [[nodiscard]] SkiaDisplayList* asSkiaDl() {
        return std::get<1>(mImpls).get();
    }

    [[nodiscard]] const SkiaDisplayList* asSkiaDl() const {
        return std::get<1>(mImpls).get();
    }

    [[nodiscard]] bool hasVectorDrawables() const {
        return apply([](const auto& it) -> auto { return it.hasVectorDrawables(); });
    }

    void clear(RenderNode* owningNode = nullptr) {
        if (owningNode && mImpls.index() == 1) {
            auto& skiaDl = std::get<1>(mImpls);
            if (skiaDl->reuseDisplayList(owningNode)) {
                skiaDl.release();
            }
        }
        mImpls = EmptyList{};
    }

    [[nodiscard]] size_t getUsedSize() const {
        return apply([](const auto& it) -> auto { return it.getUsedSize(); });
    }

    [[nodiscard]] size_t getAllocatedSize() const {
        return apply([](const auto& it) -> auto { return it.getAllocatedSize(); });
    }

    void output(std::ostream& output, uint32_t level) const {
        apply([&](const auto& it) { it.output(output, level); });
    }

    [[nodiscard]] bool hasFunctor() const {
        return apply([](const auto& it) -> auto { return it.hasFunctor(); });
    }

    bool prepareListAndChildren(
            TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer,
            std::function<void(RenderNode*, TreeObserver&, TreeInfo&, bool)> childFn) {
        return apply([&](auto& it) -> auto {
            return it.prepareListAndChildren(observer, info, functorsNeedLayer, std::move(childFn));
        });
    }

    void syncContents(const WebViewSyncData& data) {
        apply([&](auto& it) { it.syncContents(data); });
    }

    void onRemovedFromTree() {
        apply([&](auto& it) { it.onRemovedFromTree(); });
    }

    [[nodiscard]] bool hasText() const {
        return apply([](const auto& it) -> auto { return it.hasText(); });
    }

    void applyColorTransform(ColorTransform transform) {
        apply([=](auto& it) { it.applyColorTransform(transform); });
    }

    [[nodiscard]] CanvasOpBuffer& asOpBuffer() {
        return std::get<CanvasOpBuffer>(mImpls);
    }
};

// For now stick to the original single-type container to avoid any regressions
using DisplayList = SkiaDisplayListWrapper;

}  // namespace uirenderer
}  // namespace android
