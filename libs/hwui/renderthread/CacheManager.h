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
#include <GrContext.h>
#endif
#include <SkSurface.h>
#include <utils/String8.h>
#include <vector>

namespace android {

class Surface;

namespace uirenderer {

class RenderState;

namespace renderthread {

class IRenderPipeline;
class RenderThread;

class CacheManager {
public:
    enum class TrimMemoryMode { Complete, UiHidden };

#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    void configureContext(GrContextOptions* context, const void* identity, ssize_t size);
#endif
    void trimMemory(TrimMemoryMode mode);
    void trimStaleResources();
    void dumpMemoryUsage(String8& log, const RenderState* renderState = nullptr);

    size_t getCacheSize() const { return mMaxResourceBytes; }
    size_t getBackgroundCacheSize() const { return mBackgroundResourceBytes; }
    void onFrameCompleted();

private:
    friend class RenderThread;

    explicit CacheManager();

#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    void reset(sk_sp<GrContext> grContext);
#endif
    void destroy();

    const size_t mMaxSurfaceArea;
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
    sk_sp<GrContext> mGrContext;
#endif

    const size_t mMaxResourceBytes;
    const size_t mBackgroundResourceBytes;

    const size_t mMaxGpuFontAtlasBytes;
    const size_t mMaxCpuFontCacheBytes;
    const size_t mBackgroundCpuFontCacheBytes;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* CACHEMANAGER_H */
