/*
 * Copyright (C) 2014 The Android Open Source Project
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
#ifndef RENDERSTATE_H
#define RENDERSTATE_H

#include <pthread.h>
#include <utils/RefBase.h>

#include <set>

#include "utils/Macros.h"

namespace android {
namespace uirenderer {

class Layer;

namespace renderthread {
class CacheManager;
class RenderThread;
}

class IGpuContextCallback {
public:
    virtual void onContextDestroyed() = 0;
protected:
    virtual ~IGpuContextCallback() {}
};

// wrapper of Caches for users to migrate to.
class RenderState {
    PREVENT_COPY_AND_ASSIGN(RenderState);
    friend class renderthread::RenderThread;
    friend class renderthread::CacheManager;

public:
    void registerContextCallback(IGpuContextCallback* cb) { mContextCallbacks.insert(cb); }
    void removeContextCallback(IGpuContextCallback* cb) { mContextCallbacks.erase(cb); }

    void registerLayer(Layer* layer) { mActiveLayers.insert(layer); }
    void unregisterLayer(Layer* layer) { mActiveLayers.erase(layer); }

    // TODO: This system is a little clunky feeling, this could use some
    // more thinking...
    void postDecStrong(VirtualLightRefBase* object);

    renderthread::RenderThread& getRenderThread() const { return mRenderThread; }

private:
    explicit RenderState(renderthread::RenderThread& thread);
    ~RenderState() {}

    // Context notifications are only to be triggered by renderthread::RenderThread
    void onContextDestroyed();

    std::set<IGpuContextCallback*> mContextCallbacks;
    std::set<Layer*> mActiveLayers;

    renderthread::RenderThread& mRenderThread;
    pthread_t mThreadId;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERSTATE_H */
