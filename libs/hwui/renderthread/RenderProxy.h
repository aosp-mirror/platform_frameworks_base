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
#include <android/native_window.h>
#include <cutils/compiler.h>
#include <android/surface_control.h>
#include <utils/Functor.h>

#include "../FrameMetricsObserver.h"
#include "../IContextFactory.h"
#include "ColorMode.h"
#include "DrawFrameTask.h"
#include "SwapBehavior.h"
#include "hwui/Bitmap.h"

namespace android {
class GraphicBuffer;
class Surface;

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
}

/*
 * RenderProxy is strictly single threaded. All methods must be invoked on the owning
 * thread. It is important to note that RenderProxy may be deleted while it has
 * tasks post()'d as a result. Therefore any RenderTask that is post()'d must not
 * reference RenderProxy or any of its fields. The exception here is that postAndWait()
 * references RenderProxy fields. This is safe as RenderProxy cannot
 * be deleted if it is blocked inside a call.
 */
class RenderProxy {
public:
    RenderProxy(bool opaque, RenderNode* rootNode, IContextFactory* contextFactory);
    virtual ~RenderProxy();

    // Won't take effect until next EGLSurface creation
    void setSwapBehavior(SwapBehavior swapBehavior);
    bool loadSystemProperties();
    void setName(const char* name);

    void setSurface(ANativeWindow* window, bool enableTimeout = true);
    void setSurfaceControl(ASurfaceControl* surfaceControl);
    void allocateBuffers();
    bool pause();
    void setStopped(bool stopped);
    void setLightAlpha(uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha);
    void setLightGeometry(const Vector3& lightCenter, float lightRadius);
    void setOpaque(bool opaque);
    void setColorMode(ColorMode mode);
    int64_t* frameInfo();
    int syncAndDrawFrame();
    void destroy();

    static void destroyFunctor(int functor);

    DeferredLayerUpdater* createTextureLayer();
    void buildLayer(RenderNode* node);
    bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap& bitmap);
    void pushLayerUpdate(DeferredLayerUpdater* layer);
    void cancelLayerUpdate(DeferredLayerUpdater* layer);
    void detachSurfaceTexture(DeferredLayerUpdater* layer);

    void destroyHardwareResources();
    static void trimMemory(int level);
    static void purgeCaches();
    static void overrideProperty(const char* name, const char* value);

    void fence();
    static int maxTextureSize();
    void stopDrawing();
    void notifyFramePending();

    void dumpProfileInfo(int fd, int dumpFlags);
    // Not exported, only used for testing
    void resetProfileInfo();
    uint32_t frameTimePercentile(int p);
    static void dumpGraphicsMemory(int fd, bool includeProfileData = true);
    static void getMemoryUsage(size_t* cpuUsage, size_t* gpuUsage);

    static void rotateProcessStatsBuffer();
    static void setProcessStatsBuffer(int fd);
    int getRenderThreadTid();

    void addRenderNode(RenderNode* node, bool placeFront);
    void removeRenderNode(RenderNode* node);
    void drawRenderNode(RenderNode* node);
    void setContentDrawBounds(int left, int top, int right, int bottom);
    void setPictureCapturedCallback(const std::function<void(sk_sp<SkPicture>&&)>& callback);
    void setASurfaceTransactionCallback(
            const std::function<bool(int64_t, int64_t, int64_t)>& callback);
    void setPrepareSurfaceControlForWebviewCallback(const std::function<void()>& callback);
    void setFrameCallback(std::function<void(int64_t)>&& callback);
    void setFrameCompleteCallback(std::function<void(int64_t)>&& callback);

    void addFrameMetricsObserver(FrameMetricsObserver* observer);
    void removeFrameMetricsObserver(FrameMetricsObserver* observer);
    void setForceDark(bool enable);

    static int copySurfaceInto(ANativeWindow* window, int left, int top, int right,
                                           int bottom, SkBitmap* bitmap);
    static void prepareToDraw(Bitmap& bitmap);

    static int copyHWBitmapInto(Bitmap* hwBitmap, SkBitmap* bitmap);
    static int copyImageInto(const sk_sp<SkImage>& image, SkBitmap* bitmap);

    static void disableVsync();

    static void preload();

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
