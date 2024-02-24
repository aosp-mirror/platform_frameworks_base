/*
 * Copyright (C) 2024 The Android Open Source Project
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

namespace android::uirenderer {

WebViewFunctor::WebViewFunctor(void* data, const WebViewFunctorCallbacks& callbacks,
                               RenderMode functorMode)
        : mData(data) {}

WebViewFunctor::~WebViewFunctor() {}

void WebViewFunctor::sync(const WebViewSyncData& syncData) const {}

void WebViewFunctor::onRemovedFromTree() {}

bool WebViewFunctor::prepareRootSurfaceControl() {
    return true;
}

void WebViewFunctor::drawGl(const DrawGlInfo& drawInfo) {}

void WebViewFunctor::initVk(const VkFunctorInitParams& params) {}

void WebViewFunctor::drawVk(const VkFunctorDrawParams& params) {}

void WebViewFunctor::postDrawVk() {}

void WebViewFunctor::destroyContext() {}

void WebViewFunctor::removeOverlays() {}

ASurfaceControl* WebViewFunctor::getSurfaceControl() {
    return mSurfaceControl;
}

void WebViewFunctor::mergeTransaction(ASurfaceTransaction* transaction) {}

void WebViewFunctor::reparentSurfaceControl(ASurfaceControl* parent) {}

WebViewFunctorManager& WebViewFunctorManager::instance() {
    static WebViewFunctorManager sInstance;
    return sInstance;
}

int WebViewFunctorManager::createFunctor(void* data, const WebViewFunctorCallbacks& callbacks,
                                         RenderMode functorMode) {
    return 0;
}

void WebViewFunctorManager::releaseFunctor(int functor) {}

void WebViewFunctorManager::onContextDestroyed() {}

void WebViewFunctorManager::destroyFunctor(int functor) {}

sp<WebViewFunctor::Handle> WebViewFunctorManager::handleFor(int functor) {
    return nullptr;
}

}  // namespace android::uirenderer
