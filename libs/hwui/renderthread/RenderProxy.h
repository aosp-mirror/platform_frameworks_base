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

#include <SkBitmap.h>
#include <cutils/compiler.h>
#include <gui/Surface.h>
#include <utils/Functor.h>

#include "../FrameMetricsObserver.h"
#include "../IContextFactory.h"
#include "DrawFrameTask.h"
#include "SwapBehavior.h"
#include "hwui/Bitmap.h"

namespace android {
class GraphicBuffer;

namespace uirenderer {

class DeferredLayerUpdater;
class RenderNode;
class Rect;

namespace renderthread {

class CanvasContext;
class RenderThread;
class RenderProxyBridge;

namespace DumpFlags {
enum {
    FrameStats = 1 << 0,
    Reset = 1 << 1,
    JankStats = 1 << 2,
};
};

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
    ANDROID_API RenderProxy(bool opaque, RenderNode* rootNode, IContextFactory* contextFactory);
    ANDROID_API virtual ~RenderProxy();

    // Won't take effect until next EGLSurface creation
    ANDROID_API void setSwapBehavior(SwapBehavior swapBehavior);
    ANDROID_API bool loadSystemProperties();
    ANDROID_API void setName(const char* name);

    ANDROID_API void initialize(const sp<Surface>& surface);
    ANDROID_API void allocateBuffers(const sp<Surface>& surface);
    ANDROID_API void updateSurface(const sp<Surface>& surface);
    ANDROID_API bool pauseSurface(const sp<Surface>& surface);
    ANDROID_API void setStopped(bool stopped);
    ANDROID_API void setup(float lightRadius, uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha);
    ANDROID_API void setLightCenter(const Vector3& lightCenter);
    ANDROID_API void setOpaque(bool opaque);
    ANDROID_API void setWideGamut(bool wideGamut);
    ANDROID_API int64_t* frameInfo();
    ANDROID_API int syncAndDrawFrame();
    ANDROID_API void destroy();

    ANDROID_API static void invokeFunctor(Functor* functor, bool waitForCompletion);

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
    ANDROID_API static void staticFence();
    ANDROID_API void stopDrawing();
    ANDROID_API void notifyFramePending();

    ANDROID_API void dumpProfileInfo(int fd, int dumpFlags);
    // Not exported, only used for testing
    void resetProfileInfo();
    uint32_t frameTimePercentile(int p);
    ANDROID_API static void dumpGraphicsMemory(int fd);

    ANDROID_API static void rotateProcessStatsBuffer();
    ANDROID_API static void setProcessStatsBuffer(int fd);
    ANDROID_API int getRenderThreadTid();

    ANDROID_API void serializeDisplayListTree();

    ANDROID_API void addRenderNode(RenderNode* node, bool placeFront);
    ANDROID_API void removeRenderNode(RenderNode* node);
    ANDROID_API void drawRenderNode(RenderNode* node);
    ANDROID_API void setContentDrawBounds(int left, int top, int right, int bottom);
    ANDROID_API void setFrameCallback(std::function<void(int64_t)>&& callback);
    ANDROID_API void setFrameCompleteCallback(std::function<void(int64_t)>&& callback);

    ANDROID_API void addFrameMetricsObserver(FrameMetricsObserver* observer);
    ANDROID_API void removeFrameMetricsObserver(FrameMetricsObserver* observer);
    ANDROID_API long getDroppedFrameReportCount();

    ANDROID_API static int copySurfaceInto(sp<Surface>& surface, int left, int top, int right,
                                           int bottom, SkBitmap* bitmap);
    ANDROID_API static void prepareToDraw(Bitmap& bitmap);

    static sk_sp<Bitmap> allocateHardwareBitmap(SkBitmap& bitmap);

    static int copyGraphicBufferInto(GraphicBuffer* buffer, SkBitmap* bitmap);

    static void onBitmapDestroyed(uint32_t pixelRefId);

    ANDROID_API static void disableVsync();

    static void repackVectorDrawableAtlas();

    static void releaseVDAtlasEntries();

private:
    RenderThread& mRenderThread;
    CanvasContext* mContext;

    DrawFrameTask mDrawFrameTask;

    void destroyContext();

    // Friend class to help with bridging
    friend class RenderProxyBridge;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERPROXY_H_ */
