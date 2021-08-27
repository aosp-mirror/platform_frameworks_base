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

#include "DeviceInfo.h"
#include "Layer.h"
#include "Properties.h"
#include "RenderThread.h"
#include "pipeline/skia/ATraceMemoryDump.h"
#include "pipeline/skia/ShaderCache.h"
#include "pipeline/skia/SkiaMemoryTracer.h"
#include "renderstate/RenderState.h"
#include "thread/CommonPool.h"
#include <utils/Trace.h>

#include <GrContextOptions.h>
#include <SkExecutor.h>
#include <SkGraphics.h>
#include <SkMathPriv.h>
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

CacheManager::CacheManager()
        : mMaxSurfaceArea(DeviceInfo::getWidth() * DeviceInfo::getHeight())
        , mMaxResourceBytes(mMaxSurfaceArea * SURFACE_SIZE_MULTIPLIER)
        , mBackgroundResourceBytes(mMaxResourceBytes * BACKGROUND_RETENTION_PERCENTAGE)
        // This sets the maximum size for a single texture atlas in the GPU font cache. If
        // necessary, the cache can allocate additional textures that are counted against the
        // total cache limits provided to Skia.
        , mMaxGpuFontAtlasBytes(GrNextSizePow2(mMaxSurfaceArea))
        // This sets the maximum size of the CPU font cache to be at least the same size as the
        // total number of GPU font caches (i.e. 4 separate GPU atlases).
        , mMaxCpuFontCacheBytes(
                  std::max(mMaxGpuFontAtlasBytes * 4, SkGraphics::GetFontCacheLimit()))
        , mBackgroundCpuFontCacheBytes(mMaxCpuFontCacheBytes * BACKGROUND_RETENTION_PERCENTAGE) {
    SkGraphics::SetFontCacheLimit(mMaxCpuFontCacheBytes);
}

void CacheManager::reset(sk_sp<GrDirectContext> context) {
    if (context != mGrContext) {
        destroy();
    }

    if (context) {
        mGrContext = std::move(context);
        mGrContext->setResourceCacheLimit(mMaxResourceBytes);
    }
}

void CacheManager::destroy() {
    // cleanup any caches here as the GrContext is about to go away...
    mGrContext.reset(nullptr);
}

class CommonPoolExecutor : public SkExecutor {
public:
    virtual void add(std::function<void(void)> func) override { CommonPool::post(std::move(func)); }
};

static CommonPoolExecutor sDefaultExecutor;

void CacheManager::configureContext(GrContextOptions* contextOptions, const void* identity,
                                    ssize_t size) {
    contextOptions->fAllowPathMaskCaching = true;
    contextOptions->fGlyphCacheTextureMaximumBytes = mMaxGpuFontAtlasBytes;
    contextOptions->fExecutor = &sDefaultExecutor;

    auto& cache = skiapipeline::ShaderCache::get();
    cache.initShaderDiskCache(identity, size);
    contextOptions->fPersistentCache = &cache;
    contextOptions->fGpuPathRenderers &= ~GpuPathRenderers::kCoverageCounting;
}

void CacheManager::trimMemory(TrimMemoryMode mode) {
    if (!mGrContext) {
        return;
    }

    // flush and submit all work to the gpu and wait for it to finish
    mGrContext->flushAndSubmit(/*syncCpu=*/true);

    switch (mode) {
        case TrimMemoryMode::Complete:
            mGrContext->freeGpuResources();
            SkGraphics::PurgeAllCaches();
            break;
        case TrimMemoryMode::UiHidden:
            // Here we purge all the unlocked scratch resources and then toggle the resources cache
            // limits between the background and max amounts. This causes the unlocked resources
            // that have persistent data to be purged in LRU order.
            mGrContext->purgeUnlockedResources(true);
            mGrContext->setResourceCacheLimit(mBackgroundResourceBytes);
            mGrContext->setResourceCacheLimit(mMaxResourceBytes);
            SkGraphics::SetFontCacheLimit(mBackgroundCpuFontCacheBytes);
            SkGraphics::SetFontCacheLimit(mMaxCpuFontCacheBytes);
            break;
    }
}

void CacheManager::trimStaleResources() {
    if (!mGrContext) {
        return;
    }
    mGrContext->flushAndSubmit();
    mGrContext->purgeResourcesNotUsedInMs(std::chrono::seconds(30));
}

void CacheManager::getMemoryUsage(size_t* cpuUsage, size_t* gpuUsage) {
    *cpuUsage = 0;
    *gpuUsage = 0;
    if (!mGrContext) {
        return;
    }

    skiapipeline::SkiaMemoryTracer cpuTracer("category", true);
    SkGraphics::DumpMemoryStatistics(&cpuTracer);
    *cpuUsage += cpuTracer.total();

    skiapipeline::SkiaMemoryTracer gpuTracer("category", true);
    mGrContext->dumpMemoryStatistics(&gpuTracer);
    *gpuUsage += gpuTracer.total();
}

void CacheManager::dumpMemoryUsage(String8& log, const RenderState* renderState) {
    if (!mGrContext) {
        log.appendFormat("No valid cache instance.\n");
        return;
    }

    std::vector<skiapipeline::ResourcePair> cpuResourceMap = {
            {"skia/sk_resource_cache/bitmap_", "Bitmaps"},
            {"skia/sk_resource_cache/rrect-blur_", "Masks"},
            {"skia/sk_resource_cache/rects-blur_", "Masks"},
            {"skia/sk_resource_cache/tessellated", "Shadows"},
            {"skia/sk_glyph_cache", "Glyph Cache"},
    };
    skiapipeline::SkiaMemoryTracer cpuTracer(cpuResourceMap, false);
    SkGraphics::DumpMemoryStatistics(&cpuTracer);
    if (cpuTracer.hasOutput()) {
        log.appendFormat("CPU Caches:\n");
        cpuTracer.logOutput(log);
        log.appendFormat("  Glyph Count: %d \n", SkGraphics::GetFontCacheCountUsed());
        log.appendFormat("Total CPU memory usage:\n");
        cpuTracer.logTotals(log);
    }

    skiapipeline::SkiaMemoryTracer gpuTracer("category", true);
    mGrContext->dumpMemoryStatistics(&gpuTracer);
    if (gpuTracer.hasOutput()) {
        log.appendFormat("GPU Caches:\n");
        gpuTracer.logOutput(log);
    }

    if (renderState && renderState->mActiveLayers.size() > 0) {
        log.appendFormat("Layer Info:\n");

        const char* layerType = Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL
                                        ? "GlLayer"
                                        : "VkLayer";
        size_t layerMemoryTotal = 0;
        for (std::set<Layer*>::iterator it = renderState->mActiveLayers.begin();
             it != renderState->mActiveLayers.end(); it++) {
            const Layer* layer = *it;
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

void CacheManager::onFrameCompleted() {
    if (ATRACE_ENABLED()) {
        static skiapipeline::ATraceMemoryDump tracer;
        tracer.startFrame();
        SkGraphics::DumpMemoryStatistics(&tracer);
        if (mGrContext) {
            mGrContext->dumpMemoryStatistics(&tracer);
        }
        tracer.logTraces();
    }
}

void CacheManager::performDeferredCleanup(nsecs_t cleanupOlderThanMillis) {
    if (mGrContext) {
        mGrContext->performDeferredCleanup(
            std::chrono::milliseconds(cleanupOlderThanMillis),
            /* scratchResourcesOnly */true);
    }
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
