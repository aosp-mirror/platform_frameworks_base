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

#ifndef FRAMEWORKS_BASE_WEBVIEWFUNCTOR_H
#define FRAMEWORKS_BASE_WEBVIEWFUNCTOR_H

#include <cutils/compiler.h>
#include <private/hwui/DrawGlInfo.h>
#include <private/hwui/DrawVkInfo.h>

namespace android::uirenderer {

enum class RenderMode {
    OpenGL_ES,
    Vulkan,
};

// Static for the lifetime of the process
ANDROID_API RenderMode WebViewFunctor_queryPlatformRenderMode();

struct WebViewSyncData {
    bool applyForceDark;
};

struct WebViewFunctorCallbacks {
    // kModeSync, called on RenderThread
    void (*onSync)(int functor, void* data, const WebViewSyncData& syncData);

    // Called when either the context is destroyed _or_ when the functor's last reference goes
    // away. Will always be called with an active context and always on renderthread.
    void (*onContextDestroyed)(int functor, void* data);

    // Called when the last reference to the handle goes away and the handle is considered
    // irrevocably destroyed. Will always be proceeded by a call to onContextDestroyed if
    // this functor had ever been drawn.
    void (*onDestroyed)(int functor, void* data);

    union {
        struct {
            // Called on RenderThread. initialize is guaranteed to happen before this call
            void (*draw)(int functor, void* data, const DrawGlInfo& params);
        } gles;
        struct {
            // Called either the first time the functor is used or the first time it's used after
            // a call to onContextDestroyed.
            void (*initialize)(int functor, void* data, const VkFunctorInitParams& params);
            void (*draw)(int functor, void* data, const VkFunctorDrawParams& params);
            void (*postDraw)(int functor, void*);
        } vk;
    };
};

// Creates a new WebViewFunctor from the given prototype. The prototype is copied after
// this function returns. Caller retains full ownership of it.
// Returns -1 if the creation fails (such as an unsupported functorMode + platform mode combination)
ANDROID_API int WebViewFunctor_create(void* data, const WebViewFunctorCallbacks& prototype, RenderMode functorMode);

// May be called on any thread to signal that the functor should be destroyed.
// The functor will receive an onDestroyed when the last usage of it is released,
// and it should be considered alive & active until that point.
ANDROID_API void WebViewFunctor_release(int functor);

}  // namespace android::uirenderer

#endif  // FRAMEWORKS_BASE_WEBVIEWFUNCTOR_H
