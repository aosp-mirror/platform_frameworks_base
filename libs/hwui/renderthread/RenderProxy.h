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

#ifndef RENDERPROXY_H_
#define RENDERPROXY_H_

#include "RenderTask.h"

#include <cutils/compiler.h>
#include <EGL/egl.h>
#include <SkBitmap.h>
#include <utils/Condition.h>
#include <utils/Functor.h>
#include <utils/Mutex.h>
#include <utils/Timers.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include "../Caches.h"
#include "../IContextFactory.h"
#include "CanvasContext.h"
#include "DrawFrameTask.h"

namespace android {
namespace uirenderer {

class DeferredLayerUpdater;
class RenderNode;
class DisplayListData;
class Layer;
class Rect;

namespace renderthread {

class ErrorChannel;
class RenderThread;
class RenderProxyBridge;

/*
 * RenderProxy is strictly single threaded. All methods must be invoked on the owning
 * thread. It is important to note that RenderProxy may be deleted while it has
 * tasks post()'d as a result. Therefore any RenderTask that is post()'d must not
 * reference RenderProxy or any of its fields. The exception here is that postAndWait()
 * references RenderProxy fields. This is safe as RenderProxy cannot
 * be deleted if it is blocked inside a call.
 */
class ANDROID_API RenderProxy {
public:
    ANDROID_API RenderProxy(bool translucent, RenderNode* rootNode, IContextFactory* contextFactory);
    ANDROID_API virtual ~RenderProxy();

    // Won't take effect until next EGLSurface creation
    ANDROID_API void setSwapBehavior(SwapBehavior swapBehavior);
    ANDROID_API bool loadSystemProperties();
    ANDROID_API void setName(const char* name);

    ANDROID_API void initialize(const sp<ANativeWindow>& window);
    ANDROID_API void updateSurface(const sp<ANativeWindow>& window);
    ANDROID_API bool pauseSurface(const sp<ANativeWindow>& window);
    ANDROID_API void setup(int width, int height, float lightRadius,
            uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha);
    ANDROID_API void setLightCenter(const Vector3& lightCenter);
    ANDROID_API void setOpaque(bool opaque);
    ANDROID_API int64_t* frameInfo();
    ANDROID_API int syncAndDrawFrame();
    ANDROID_API void destroy();

    ANDROID_API static void invokeFunctor(Functor* functor, bool waitForCompletion);

    ANDROID_API void runWithGlContext(RenderTask* task);

    ANDROID_API DeferredLayerUpdater* createTextureLayer();
    ANDROID_API void buildLayer(RenderNode* node);
    ANDROID_API bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap& bitmap);
    ANDROID_API void pushLayerUpdate(DeferredLayerUpdater* layer);
    ANDROID_API void cancelLayerUpdate(DeferredLayerUpdater* layer);
    ANDROID_API void detachSurfaceTexture(DeferredLayerUpdater* layer);

    ANDROID_API void destroyHardwareResources();
    ANDROID_API static void trimMemory(int level);
    ANDROID_API static void overrideProperty(const char* name, const char* value);

    ANDROID_API void fence();
    ANDROID_API void stopDrawing();
    ANDROID_API void notifyFramePending();

    ANDROID_API void dumpProfileInfo(int fd, int dumpFlags);
    // Not exported, only used for testing
    void resetProfileInfo();
    ANDROID_API static void dumpGraphicsMemory(int fd);

    ANDROID_API void setTextureAtlas(const sp<GraphicBuffer>& buffer, int64_t* map, size_t size);
    ANDROID_API void setProcessStatsBuffer(int fd);

private:
    RenderThread& mRenderThread;
    CanvasContext* mContext;

    DrawFrameTask mDrawFrameTask;

    Mutex mSyncMutex;
    Condition mSyncCondition;

    void destroyContext();

    void post(RenderTask* task);
    void* postAndWait(MethodInvokeRenderTask* task);

    static void* staticPostAndWait(MethodInvokeRenderTask* task);

    // Friend class to help with bridging
    friend class RenderProxyBridge;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERPROXY_H_ */
