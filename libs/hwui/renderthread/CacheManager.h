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

#include "pipeline/skia/VectorDrawableAtlas.h"
#include "thread/TaskManager.h"
#include "thread/TaskProcessor.h"

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

    void configureContext(GrContextOptions* context, const void* identity, ssize_t size);
    void trimMemory(TrimMemoryMode mode);
    void trimStaleResources();
    void dumpMemoryUsage(String8& log, const RenderState* renderState = nullptr);

    sp<skiapipeline::VectorDrawableAtlas> acquireVectorDrawableAtlas();

    size_t getCacheSize() const { return mMaxResourceBytes; }
    size_t getBackgroundCacheSize() const { return mBackgroundResourceBytes; }

    TaskManager* getTaskManager() { return &mTaskManager; }

private:
    friend class RenderThread;

    explicit CacheManager(const DisplayInfo& display);

    void reset(sk_sp<GrContext> grContext);
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

    sp<skiapipeline::VectorDrawableAtlas> mVectorDrawableAtlas;

    class SkiaTaskProcessor;
    sp<SkiaTaskProcessor> mTaskProcessor;
    TaskManager mTaskManager;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* CACHEMANAGER_H */
