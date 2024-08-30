/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef CACHEMANAGER_H
#define CACHEMANAGER_H

#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
#include <include/gpu/ganesh/GrDirectContext.h>
#endif
#include <SkSurface.h>
#include <utils/String8.h>

#include <vector>

#include "MemoryPolicy.h"
#include "utils/RingBuffer.h"
#include "utils/TimeUtils.h"

namespace android {

class Surface;

namespace uirenderer {

class RenderState;

namespace renderthread {

class RenderThread;
class CanvasContext;

class CacheManager {
public:
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    void configureContext(GrContextOptions* context, const void* identity, ssize_t size);
#endif
    void trimMemory(TrimLevel mode);
    void trimCaches(CacheTrimLevel mode);
    void trimStaleResources();
    void dumpMemoryUsage(String8& log, const RenderState* renderState = nullptr);
    void getMemoryUsage(size_t* cpuUsage, size_t* gpuUsage);

    size_t getCacheSize() const { return mMaxResourceBytes; }
    size_t getBackgroundCacheSize() const { return mBackgroundResourceBytes; }
    void onFrameCompleted();
    void notifyNextFrameSize(int width, int height);

    void onThreadIdle();

    void registerCanvasContext(CanvasContext* context);
    void unregisterCanvasContext(CanvasContext* context);
    void onContextStopped(CanvasContext* context);

    bool areAllContextsStopped();

private:
    friend class RenderThread;

    explicit CacheManager(RenderThread& thread);
    void setupCacheLimits();
    void checkUiHidden();
    void scheduleDestroyContext();
    void cancelDestroyContext();

#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    void reset(sk_sp<GrDirectContext> grContext);
#endif
    void destroy();

    RenderThread& mRenderThread;
    const MemoryPolicy& mMemoryPolicy;
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    sk_sp<GrDirectContext> mGrContext;
#endif

    size_t mMaxSurfaceArea = 0;

    size_t mMaxResourceBytes = 0;
    size_t mBackgroundResourceBytes = 0;

    size_t mMaxGpuFontAtlasBytes = 0;
    size_t mMaxCpuFontCacheBytes = 0;
    size_t mBackgroundCpuFontCacheBytes = 0;

    std::vector<CanvasContext*> mCanvasContexts;
    RingBuffer<uint64_t, 100> mFrameCompletions;

    nsecs_t mLastDeferredCleanup = 0;
    bool mIsDestructionPending = false;
    uint32_t mGenerationId = 0;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* CACHEMANAGER_H */
