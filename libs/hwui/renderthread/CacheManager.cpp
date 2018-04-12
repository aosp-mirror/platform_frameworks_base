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

#include "CacheManager.h"

#include "Layer.h"
#include "Properties.h"
#include "RenderThread.h"
#include "pipeline/skia/ShaderCache.h"
#include "pipeline/skia/SkiaMemoryTracer.h"
#include "renderstate/RenderState.h"

#include <GrContextOptions.h>
#include <SkExecutor.h>
#include <SkGraphics.h>
#include <gui/Surface.h>
#include <math.h>
#include <set>

namespace android {
namespace uirenderer {
namespace renderthread {

// This multiplier was selected based on historical review of cache sizes relative
// to the screen resolution. This is meant to be a conservative default based on
// that analysis. The 4.0f is used because the default pixel format is assumed to
// be ARGB_8888.
#define SURFACE_SIZE_MULTIPLIER (12.0f * 4.0f)
#define BACKGROUND_RETENTION_PERCENTAGE (0.5f)

// for super large fonts we will draw them as paths so no need to keep linearly
// increasing the font cache size.
#define FONT_CACHE_MIN_MB (0.5f)
#define FONT_CACHE_MAX_MB (4.0f)

CacheManager::CacheManager(const DisplayInfo& display) : mMaxSurfaceArea(display.w * display.h) {
    mVectorDrawableAtlas = new skiapipeline::VectorDrawableAtlas(
            mMaxSurfaceArea / 2,
            skiapipeline::VectorDrawableAtlas::StorageMode::disallowSharedSurface);
    skiapipeline::ShaderCache::get().initShaderDiskCache();
}

void CacheManager::reset(sk_sp<GrContext> context) {
    if (context != mGrContext) {
        destroy();
    }

    if (context) {
        mGrContext = std::move(context);
        mGrContext->getResourceCacheLimits(&mMaxResources, nullptr);
        updateContextCacheSizes();
    }
}

void CacheManager::destroy() {
    // cleanup any caches here as the GrContext is about to go away...
    mGrContext.reset(nullptr);
    mVectorDrawableAtlas = new skiapipeline::VectorDrawableAtlas(
            mMaxSurfaceArea / 2,
            skiapipeline::VectorDrawableAtlas::StorageMode::disallowSharedSurface);
}

void CacheManager::updateContextCacheSizes() {
    mMaxResourceBytes = mMaxSurfaceArea * SURFACE_SIZE_MULTIPLIER;
    mBackgroundResourceBytes = mMaxResourceBytes * BACKGROUND_RETENTION_PERCENTAGE;

    mGrContext->setResourceCacheLimits(mMaxResources, mMaxResourceBytes);
}

class CacheManager::SkiaTaskProcessor : public TaskProcessor<bool>, public SkExecutor {
public:
    explicit SkiaTaskProcessor(TaskManager* taskManager) : TaskProcessor<bool>(taskManager) {}

    // This is really a Task<void> but that doesn't really work when Future<>
    // expects to be able to get/set a value
    struct SkiaTask : public Task<bool> {
        std::function<void()> func;
    };

    virtual void add(std::function<void(void)> func) override {
        sp<SkiaTask> task(new SkiaTask());
        task->func = func;
        TaskProcessor<bool>::add(task);
    }

    virtual void onProcess(const sp<Task<bool> >& task) override {
        SkiaTask* t = static_cast<SkiaTask*>(task.get());
        t->func();
        task->setResult(true);
    }
};

void CacheManager::configureContext(GrContextOptions* contextOptions) {
    contextOptions->fAllowPathMaskCaching = true;

    float screenMP = mMaxSurfaceArea / 1024.0f / 1024.0f;
    float fontCacheMB = 0;
    float decimalVal = std::modf(screenMP, &fontCacheMB);

    // This is a basic heuristic to size the cache to a multiple of 512 KB
    if (decimalVal > 0.8f) {
        fontCacheMB += 1.0f;
    } else if (decimalVal > 0.5f) {
        fontCacheMB += 0.5f;
    }

    // set limits on min/max size of the cache
    fontCacheMB = std::max(FONT_CACHE_MIN_MB, std::min(FONT_CACHE_MAX_MB, fontCacheMB));

    // We must currently set the size of the text cache based on the size of the
    // display even though we like to  be dynamicallysizing it to the size of the window.
    // Skia's implementation doesn't provide a mechanism to resize the font cache due to
    // the potential cost of recreating the glyphs.
    contextOptions->fGlyphCacheTextureMaximumBytes = fontCacheMB * 1024 * 1024;

    if (mTaskManager.canRunTasks()) {
        if (!mTaskProcessor.get()) {
            mTaskProcessor = new SkiaTaskProcessor(&mTaskManager);
        }
        contextOptions->fExecutor = mTaskProcessor.get();
    }

    contextOptions->fPersistentCache = &skiapipeline::ShaderCache::get();
}

void CacheManager::trimMemory(TrimMemoryMode mode) {
    if (!mGrContext) {
        return;
    }

    mGrContext->flush();

    switch (mode) {
        case TrimMemoryMode::Complete:
            mVectorDrawableAtlas = new skiapipeline::VectorDrawableAtlas(mMaxSurfaceArea / 2);
            mGrContext->freeGpuResources();
            break;
        case TrimMemoryMode::UiHidden:
            // Here we purge all the unlocked scratch resources and then toggle the resources cache
            // limits between the background and max amounts. This causes the unlocked resources
            // that have persistent data to be purged in LRU order.
            mGrContext->purgeUnlockedResources(true);
            mGrContext->setResourceCacheLimits(mMaxResources, mBackgroundResourceBytes);
            mGrContext->setResourceCacheLimits(mMaxResources, mMaxResourceBytes);
            break;
    }
}

void CacheManager::trimStaleResources() {
    if (!mGrContext) {
        return;
    }
    mGrContext->flush();
    // Here we purge all the unlocked scratch resources (leaving those resources w/ persistent data)
    // and then purge those w/ persistent data based on age.
    mGrContext->purgeUnlockedResources(true);
    mGrContext->purgeResourcesNotUsedInMs(std::chrono::seconds(10));
}

sp<skiapipeline::VectorDrawableAtlas> CacheManager::acquireVectorDrawableAtlas() {
    LOG_ALWAYS_FATAL_IF(mVectorDrawableAtlas.get() == nullptr);
    LOG_ALWAYS_FATAL_IF(mGrContext == nullptr);

    /**
     * TODO: define memory conditions where we clear the cache (e.g. surface->reset())
     */
    return mVectorDrawableAtlas;
}

void CacheManager::dumpMemoryUsage(String8& log, const RenderState* renderState) {
    if (!mGrContext) {
        log.appendFormat("No valid cache instance.\n");
        return;
    }

    log.appendFormat("Font Cache (CPU):\n");
    log.appendFormat("  Size: %.2f kB \n", SkGraphics::GetFontCacheUsed() / 1024.0f);
    log.appendFormat("  Glyph Count: %d \n", SkGraphics::GetFontCacheCountUsed());

    log.appendFormat("CPU Caches:\n");
    std::vector<skiapipeline::ResourcePair> cpuResourceMap = {
            {"skia/sk_resource_cache/bitmap_", "Bitmaps"},
            {"skia/sk_resource_cache/rrect-blur_", "Masks"},
            {"skia/sk_resource_cache/rects-blur_", "Masks"},
            {"skia/sk_resource_cache/tessellated", "Shadows"},
    };
    skiapipeline::SkiaMemoryTracer cpuTracer(cpuResourceMap, false);
    SkGraphics::DumpMemoryStatistics(&cpuTracer);
    cpuTracer.logOutput(log);

    log.appendFormat("GPU Caches:\n");
    skiapipeline::SkiaMemoryTracer gpuTracer("category", true);
    mGrContext->dumpMemoryStatistics(&gpuTracer);
    gpuTracer.logOutput(log);

    log.appendFormat("Other Caches:\n");
    log.appendFormat("                         Current / Maximum\n");
    log.appendFormat("  VectorDrawableAtlas  %6.2f kB / %6.2f KB (entries = %zu)\n", 0.0f, 0.0f,
                     (size_t)0);

    if (renderState) {
        if (renderState->mActiveLayers.size() > 0) {
            log.appendFormat("  Layer Info:\n");
        }

        size_t layerMemoryTotal = 0;
        for (std::set<Layer*>::iterator it = renderState->mActiveLayers.begin();
             it != renderState->mActiveLayers.end(); it++) {
            const Layer* layer = *it;
            const char* layerType = layer->getApi() == Layer::Api::OpenGL ? "GlLayer" : "VkLayer";
            log.appendFormat("    %s size %dx%d\n", layerType, layer->getWidth(),
                             layer->getHeight());
            layerMemoryTotal += layer->getWidth() * layer->getHeight() * 4;
        }
        log.appendFormat("  Layers Total         %6.2f KB (numLayers = %zu)\n",
                         layerMemoryTotal / 1024.0f, renderState->mActiveLayers.size());
    }

    log.appendFormat("Total GPU memory usage:\n");
    gpuTracer.logTotals(log);
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
