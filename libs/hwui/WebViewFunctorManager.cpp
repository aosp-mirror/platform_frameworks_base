/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "WebViewFunctorManager.h"

#include <private/hwui/WebViewFunctor.h>
#include "Properties.h"
#include "renderthread/RenderThread.h"

#include <log/log.h>
#include <utils/Trace.h>
#include <atomic>

namespace android::uirenderer {

RenderMode WebViewFunctor_queryPlatformRenderMode() {
    auto pipelineType = Properties::getRenderPipelineType();
    switch (pipelineType) {
        case RenderPipelineType::SkiaGL:
            return RenderMode::OpenGL_ES;
        case RenderPipelineType::SkiaVulkan:
            return RenderMode::Vulkan;
        default:
            LOG_ALWAYS_FATAL("Unknown render pipeline type: %d", (int)pipelineType);
    }
}

int WebViewFunctor_create(void* data, const WebViewFunctorCallbacks& prototype,
                          RenderMode functorMode) {
    if (functorMode != RenderMode::OpenGL_ES && functorMode != RenderMode::Vulkan) {
        ALOGW("Unknown rendermode %d", (int)functorMode);
        return -1;
    }
    if (functorMode == RenderMode::Vulkan &&
        WebViewFunctor_queryPlatformRenderMode() != RenderMode::Vulkan) {
        ALOGW("Unable to map from GLES platform to a vulkan functor");
        return -1;
    }
    return WebViewFunctorManager::instance().createFunctor(data, prototype, functorMode);
}

void WebViewFunctor_release(int functor) {
    WebViewFunctorManager::instance().releaseFunctor(functor);
}

static std::atomic_int sNextId{1};

WebViewFunctor::WebViewFunctor(void* data, const WebViewFunctorCallbacks& callbacks,
                               RenderMode functorMode)
        : mData(data) {
    mFunctor = sNextId++;
    mCallbacks = callbacks;
    mMode = functorMode;
}

WebViewFunctor::~WebViewFunctor() {
    destroyContext();

    ATRACE_NAME("WebViewFunctor::onDestroy");
    mCallbacks.onDestroyed(mFunctor, mData);
}

void WebViewFunctor::sync(const WebViewSyncData& syncData) const {
    ATRACE_NAME("WebViewFunctor::sync");
    mCallbacks.onSync(mFunctor, mData, syncData);
}

void WebViewFunctor::drawGl(const DrawGlInfo& drawInfo) {
    ATRACE_NAME("WebViewFunctor::drawGl");
    if (!mHasContext) {
        mHasContext = true;
    }
    mCallbacks.gles.draw(mFunctor, mData, drawInfo);
}

void WebViewFunctor::initVk(const VkFunctorInitParams& params) {
    ATRACE_NAME("WebViewFunctor::initVk");
    if (!mHasContext) {
        mHasContext = true;
    } else {
        return;
    }
    mCallbacks.vk.initialize(mFunctor, mData, params);
}

void WebViewFunctor::drawVk(const VkFunctorDrawParams& params) {
    ATRACE_NAME("WebViewFunctor::drawVk");
    mCallbacks.vk.draw(mFunctor, mData, params);
}

void WebViewFunctor::postDrawVk() {
    ATRACE_NAME("WebViewFunctor::postDrawVk");
    mCallbacks.vk.postDraw(mFunctor, mData);
}

void WebViewFunctor::destroyContext() {
    if (mHasContext) {
        mHasContext = false;
        ATRACE_NAME("WebViewFunctor::onContextDestroyed");
        mCallbacks.onContextDestroyed(mFunctor, mData);

        // grContext may be null in unit tests.
        auto* grContext = renderthread::RenderThread::getInstance().getGrContext();
        if (grContext) grContext->resetContext();
    }
}

WebViewFunctorManager& WebViewFunctorManager::instance() {
    static WebViewFunctorManager sInstance;
    return sInstance;
}

int WebViewFunctorManager::createFunctor(void* data, const WebViewFunctorCallbacks& callbacks,
                                         RenderMode functorMode) {
    auto object = std::make_unique<WebViewFunctor>(data, callbacks, functorMode);
    int id = object->id();
    auto handle = object->createHandle();
    {
        std::lock_guard _lock{mLock};
        mActiveFunctors.push_back(std::move(handle));
        mFunctors.push_back(std::move(object));
    }
    return id;
}

void WebViewFunctorManager::releaseFunctor(int functor) {
    sp<WebViewFunctor::Handle> toRelease;
    {
        std::lock_guard _lock{mLock};
        for (auto iter = mActiveFunctors.begin(); iter != mActiveFunctors.end(); iter++) {
            if ((*iter)->id() == functor) {
                toRelease = std::move(*iter);
                mActiveFunctors.erase(iter);
                break;
            }
        }
    }
}

void WebViewFunctorManager::onContextDestroyed() {
    // WARNING: SKETCHY
    // Because we know that we always remove from mFunctors on RenderThread, the same
    // thread that always invokes onContextDestroyed, we know that the functor pointers
    // will remain valid without the lock held.
    // However, we won't block new functors from being added in the meantime.
    mLock.lock();
    const size_t size = mFunctors.size();
    WebViewFunctor* toDestroyContext[size];
    for (size_t i = 0; i < size; i++) {
        toDestroyContext[i] = mFunctors[i].get();
    }
    mLock.unlock();
    for (size_t i = 0; i < size; i++) {
        toDestroyContext[i]->destroyContext();
    }
}

void WebViewFunctorManager::destroyFunctor(int functor) {
    std::unique_ptr<WebViewFunctor> toRelease;
    {
        std::lock_guard _lock{mLock};
        for (auto iter = mFunctors.begin(); iter != mFunctors.end(); iter++) {
            if ((*iter)->id() == functor) {
                toRelease = std::move(*iter);
                mFunctors.erase(iter);
                break;
            }
        }
    }
}

sp<WebViewFunctor::Handle> WebViewFunctorManager::handleFor(int functor) {
    std::lock_guard _lock{mLock};
    for (auto& iter : mActiveFunctors) {
        if (iter->id() == functor) {
            return iter;
        }
    }
    return nullptr;
}

}  // namespace android::uirenderer
