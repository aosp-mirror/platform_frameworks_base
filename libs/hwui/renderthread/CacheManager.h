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

#include <GrContext.h>
#include <SkSurface.h>
#include <ui/DisplayInfo.h>
#include <utils/String8.h>
#include <vector>

namespace android {

class Surface;

namespace uirenderer {

class RenderState;

namespace renderthread {

class IRenderPipeline;
class RenderThread;

struct VectorDrawableAtlas {
    sk_sp<SkSurface> surface;
    bool isNewAtlas = true;
};

class CacheManager {
public:
    enum class TrimMemoryMode {
        Complete,
        UiHidden
    };

    void configureContext(GrContextOptions* context);
    void trimMemory(TrimMemoryMode mode);
    void trimStaleResources();
    void dumpMemoryUsage(String8& log, const RenderState* renderState = nullptr);

    VectorDrawableAtlas* acquireVectorDrawableAtlas();
    void releaseVectorDrawableAtlas(VectorDrawableAtlas*);

    size_t getCacheSize() const { return mMaxResourceBytes; }
    size_t getBackgroundCacheSize() const { return mBackgroundResourceBytes; }

private:
    friend class RenderThread;

    CacheManager(const DisplayInfo& display);


    void reset(GrContext* grContext);
    void destroy();
    void updateContextCacheSizes();

    const size_t mMaxSurfaceArea;
    sk_sp<GrContext> mGrContext;

    int mMaxResources = 0;
    size_t mMaxResourceBytes = 0;
    size_t mBackgroundResourceBytes = 0;

    struct PipelineProps {
        const void* pipelineKey = nullptr;
        size_t surfaceArea = 0;
    };

    std::unique_ptr<VectorDrawableAtlas> mVectorDrawableAtlas;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* CACHEMANAGER_H */

