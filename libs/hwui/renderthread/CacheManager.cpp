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

#include <GrContextOptions.h>
#include <GrTypes.h>
#include <SkExecutor.h>
#include <SkGraphics.h>
#include <math.h>
#include <utils/Trace.h>

#include <set>

#include "CanvasContext.h"
#include "DeviceInfo.h"
#include "Layer.h"
#include "Properties.h"
#include "RenderThread.h"
#include "VulkanManager.h"
#include "pipeline/skia/ATraceMemoryDump.h"
#include "pipeline/skia/ShaderCache.h"
#include "pipeline/skia/SkiaMemoryTracer.h"
#include "renderstate/RenderState.h"
#include "thread/CommonPool.h"

namespace android {
namespace uirenderer {
namespace renderthread {

CacheManager::CacheManager(RenderThread& thread)
        : mRenderThread(thread), mMemoryPolicy(loadMemoryPolicy()) {
    mMaxSurfaceArea = static_cast<size_t>((DeviceInfo::getWidth() * DeviceInfo::getHeight()) *
                                          mMemoryPolicy.initialMaxSurfaceAreaScale);
    setupCacheLimits();
}

static inline int countLeadingZeros(uint32_t mask) {
    // __builtin_clz(0) is undefined, so we have to detect that case.
    return mask ? __builtin_clz(mask) : 32;
}

// Return the smallest power-of-2 >= n.
static inline uint32_t nextPowerOfTwo(uint32_t n) {
    return n ? (1 << (32 - countLeadingZeros(n - 1))) : 1;
}

void CacheManager::setupCacheLimits() {
    mMaxResourceBytes = mMaxSurfaceArea * mMemoryPolicy.surfaceSizeMultiplier;
    mBackgroundResourceBytes = mMaxResourceBytes * mMemoryPolicy.backgroundRetentionPercent;
    // This sets the maximum size for a single texture atlas in the GPU font cache. If
    // necessary, the cache can allocate additional textures that are counted against the
    // total cache limits provided to Skia.
    mMaxGpuFontAtlasBytes = nextPowerOfTwo(mMaxSurfaceArea);
    // This sets the maximum size of the CPU font cache to be at least the same size as the
    // total number of GPU font caches (i.e. 4 separate GPU atlases).
    mMaxCpuFontCacheBytes = std::max(mMaxGpuFontAtlasBytes * 4, SkGraphics::GetFontCacheLimit());
    mBackgroundCpuFontCacheBytes = mMaxCpuFontCacheBytes * mMemoryPolicy.backgroundRetentionPercent;

    SkGraphics::SetFontCacheLimit(mMaxCpuFontCacheBytes);
    if (mGrContext) {
        mGrContext->setResourceCacheLimit(mMaxResourceBytes);
    }
}

void CacheManager::reset(sk_sp<GrDirectContext> context) {
    if (context != mGrContext) {
        destroy();
    }

    if (context) {
        mGrContext = std::move(context);
        mGrContext->setResourceCacheLimit(mMaxResourceBytes);
        mLastDeferredCleanup = systemTime(CLOCK_MONOTONIC);
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
}

static GrPurgeResourceOptions toSkiaEnum(bool scratchOnly) {
    return scratchOnly ? GrPurgeResourceOptions::kScratchResourcesOnly :
                         GrPurgeResourceOptions::kAllResources;
}

void CacheManager::trimMemory(TrimLevel mode) {
    if (!mGrContext) {
        return;
    }

    // flush and submit all work to the gpu and wait for it to finish
    mGrContext->flushAndSubmit(GrSyncCpu::kYes);

    if (mode >= TrimLevel::BACKGROUND) {
        mGrContext->freeGpuResources();
        SkGraphics::PurgeAllCaches();
        mRenderThread.destroyRenderingContext();
    } else if (mode == TrimLevel::UI_HIDDEN) {
        // Here we purge all the unlocked scratch resources and then toggle the resources cache
        // limits between the background and max amounts. This causes the unlocked resources
        // that have persistent data to be purged in LRU order.
        mGrContext->setResourceCacheLimit(mBackgroundResourceBytes);
        SkGraphics::SetFontCacheLimit(mBackgroundCpuFontCacheBytes);
        mGrContext->purgeUnlockedResources(toSkiaEnum(mMemoryPolicy.purgeScratchOnly));
        mGrContext->setResourceCacheLimit(mMaxResourceBytes);
        SkGraphics::SetFontCacheLimit(mMaxCpuFontCacheBytes);
    }
}

void CacheManager::trimCaches(CacheTrimLevel mode) {
    switch (mode) {
        case CacheTrimLevel::FONT_CACHE:
            SkGraphics::PurgeFontCache();
            break;
        case CacheTrimLevel::RESOURCE_CACHE:
            SkGraphics::PurgeResourceCache();
            break;
        case CacheTrimLevel::ALL_CACHES:
            SkGraphics::PurgeAllCaches();
            if (mGrContext) {
                mGrContext->purgeUnlockedResources(GrPurgeResourceOptions::kAllResources);
            }
            break;
        default:
            break;
    }
}

void CacheManager::trimStaleResources() {
    if (!mGrContext) {
        return;
    }
    mGrContext->flushAndSubmit();
    mGrContext->performDeferredCleanup(std::chrono::seconds(30),
                                       GrPurgeResourceOptions::kAllResources);
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
    log.appendFormat(R"(Memory policy:
  Max surface area: %zu
  Max resource usage: %.2fMB (x%.0f)
  Background retention: %.0f%% (altUiHidden = %s)
)",
                     mMaxSurfaceArea, mMaxResourceBytes / 1000000.f,
                     mMemoryPolicy.surfaceSizeMultiplier,
                     mMemoryPolicy.backgroundRetentionPercent * 100.0f,
                     mMemoryPolicy.useAlternativeUiHidden ? "true" : "false");
    if (Properties::isSystemOrPersistent) {
        log.appendFormat("  IsSystemOrPersistent\n");
    }
    log.appendFormat("  GPU Context timeout: %" PRIu64 "\n", ns2s(mMemoryPolicy.contextTimeout));
    size_t stoppedContexts = 0;
    for (auto context : mCanvasContexts) {
        if (context->isStopped()) stoppedContexts++;
    }
    log.appendFormat("Contexts: %zu (stopped = %zu)\n", mCanvasContexts.size(), stoppedContexts);

    auto vkInstance = VulkanManager::peekInstance();
    if (!mGrContext) {
        if (!vkInstance) {
            log.appendFormat("No GPU context.\n");
        } else {
            log.appendFormat("No GrContext; however %d remaining Vulkan refs",
                             vkInstance->getStrongCount() - 1);
        }
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
    cancelDestroyContext();
    mFrameCompletions.next() = systemTime(CLOCK_MONOTONIC);
    if (ATRACE_ENABLED()) {
        ATRACE_NAME("dumpingMemoryStatistics");
        static skiapipeline::ATraceMemoryDump tracer;
        tracer.startFrame();
        SkGraphics::DumpMemoryStatistics(&tracer);
        if (mGrContext && Properties::debugTraceGpuResourceCategories) {
            mGrContext->dumpMemoryStatistics(&tracer);
        }
        tracer.logTraces(Properties::debugTraceGpuResourceCategories, mGrContext.get());
    }
}

void CacheManager::onThreadIdle() {
    if (!mGrContext || mFrameCompletions.size() == 0) return;

    const nsecs_t now = systemTime(CLOCK_MONOTONIC);
    // Rate limiting
    if ((now - mLastDeferredCleanup) > 25_ms) {
        mLastDeferredCleanup = now;
        const nsecs_t frameCompleteNanos = mFrameCompletions[0];
        const nsecs_t frameDiffNanos = now - frameCompleteNanos;
        const nsecs_t cleanupMillis =
                ns2ms(std::clamp(frameDiffNanos, mMemoryPolicy.minimumResourceRetention,
                                 mMemoryPolicy.maximumResourceRetention));
        mGrContext->performDeferredCleanup(std::chrono::milliseconds(cleanupMillis),
                                           toSkiaEnum(mMemoryPolicy.purgeScratchOnly));
    }
}

void CacheManager::scheduleDestroyContext() {
    if (mMemoryPolicy.contextTimeout > 0) {
        mRenderThread.queue().postDelayed(mMemoryPolicy.contextTimeout,
                                          [this, genId = mGenerationId] {
                                              if (mGenerationId != genId) return;
                                              // GenID should have already stopped this, but just in
                                              // case
                                              if (!areAllContextsStopped()) return;
                                              mRenderThread.destroyRenderingContext();
                                          });
    }
}

void CacheManager::cancelDestroyContext() {
    if (mIsDestructionPending) {
        mIsDestructionPending = false;
        mGenerationId++;
    }
}

bool CacheManager::areAllContextsStopped() {
    for (auto context : mCanvasContexts) {
        if (!context->isStopped()) return false;
    }
    return true;
}

void CacheManager::checkUiHidden() {
    if (!mGrContext) return;

    if (mMemoryPolicy.useAlternativeUiHidden && areAllContextsStopped()) {
        trimMemory(TrimLevel::UI_HIDDEN);
    }
}

void CacheManager::registerCanvasContext(CanvasContext* context) {
    mCanvasContexts.push_back(context);
    cancelDestroyContext();
}

void CacheManager::unregisterCanvasContext(CanvasContext* context) {
    std::erase(mCanvasContexts, context);
    checkUiHidden();
    if (mCanvasContexts.empty()) {
        scheduleDestroyContext();
    }
}

void CacheManager::onContextStopped(CanvasContext* context) {
    checkUiHidden();
    if (mMemoryPolicy.releaseContextOnStoppedOnly && areAllContextsStopped()) {
        scheduleDestroyContext();
    }
}

void CacheManager::notifyNextFrameSize(int width, int height) {
    int frameArea = width * height;
    if (frameArea > mMaxSurfaceArea) {
        mMaxSurfaceArea = frameArea;
        setupCacheLimits();
    }
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
