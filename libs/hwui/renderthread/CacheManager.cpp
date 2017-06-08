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
#include "RenderThread.h"
#include "renderstate/RenderState.h"

#include <gui/Surface.h>
#include <GrContextOptions.h>
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

CacheManager::CacheManager(const DisplayInfo& display)
        : mMaxSurfaceArea(display.w * display.h) {
    mVectorDrawableAtlas.reset(new VectorDrawableAtlas);
}

void CacheManager::reset(GrContext* context) {
    if (context != mGrContext.get()) {
        destroy();
    }

    if (context) {
        mGrContext = sk_ref_sp(context);
        mGrContext->getResourceCacheLimits(&mMaxResources, nullptr);
        updateContextCacheSizes();
    }
}

void CacheManager::destroy() {
    // cleanup any caches here as the GrContext is about to go away...
    mGrContext.reset(nullptr);
    mVectorDrawableAtlas.reset(new VectorDrawableAtlas);
}

void CacheManager::updateContextCacheSizes() {
    mMaxResourceBytes = mMaxSurfaceArea * SURFACE_SIZE_MULTIPLIER;
    mBackgroundResourceBytes = mMaxResourceBytes * BACKGROUND_RETENTION_PERCENTAGE;

    mGrContext->setResourceCacheLimits(mMaxResources, mMaxResourceBytes);
}

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
}

void CacheManager::trimMemory(TrimMemoryMode mode) {
    if (!mGrContext) {
        return;
    }

    mGrContext->flush();

    switch (mode) {
        case TrimMemoryMode::Complete:
            mVectorDrawableAtlas.reset(new VectorDrawableAtlas);
            mGrContext->freeGpuResources();
            break;
        case TrimMemoryMode::UiHidden:
            mGrContext->purgeUnlockedResources(mMaxResourceBytes - mBackgroundResourceBytes, true);
            break;
    }
}

void CacheManager::trimStaleResources() {
    if (!mGrContext) {
        return;
    }
    mGrContext->flush();
    mGrContext->purgeResourcesNotUsedInMs(std::chrono::seconds(30));
}

VectorDrawableAtlas* CacheManager::acquireVectorDrawableAtlas() {
    LOG_ALWAYS_FATAL_IF(mVectorDrawableAtlas.get() == nullptr);
    LOG_ALWAYS_FATAL_IF(mGrContext == nullptr);

    /**
     * TODO LIST:
     *    1) compute the atlas based on the surfaceArea surface
     *    2) identify a way to reuse cache entries
     *    3) add ability to repack the cache?
     *    4) define memory conditions where we clear the cache (e.g. surface->reset())
     */

    return mVectorDrawableAtlas.release();
}
void CacheManager::releaseVectorDrawableAtlas(VectorDrawableAtlas* atlas) {
    LOG_ALWAYS_FATAL_IF(mVectorDrawableAtlas.get() != nullptr);
    mVectorDrawableAtlas.reset(atlas);
    mVectorDrawableAtlas->isNewAtlas = false;
}

void CacheManager::dumpMemoryUsage(String8& log, const RenderState* renderState) {
    if (!mGrContext) {
        log.appendFormat("No valid cache instance.\n");
        return;
    }

    size_t bytesCached;
    mGrContext->getResourceCacheUsage(nullptr, &bytesCached);

    log.appendFormat("Caches:\n");
    log.appendFormat("                         Current / Maximum\n");
    log.appendFormat("  VectorDrawableAtlas  %6.2f kB / %6.2f kB (entries = %zu)\n",
            0.0f, 0.0f, (size_t)0);

    if (renderState) {
        if (renderState->mActiveLayers.size() > 0) {
            log.appendFormat("  Layer Info:\n");
        }

        size_t layerMemoryTotal = 0;
        for (std::set<Layer*>::iterator it = renderState->mActiveLayers.begin();
                it != renderState->mActiveLayers.end(); it++) {
            const Layer* layer = *it;
            const char* layerType = layer->getApi() == Layer::Api::OpenGL ? "GlLayer" : "VkLayer";
            log.appendFormat("    %s size %dx%d\n", layerType,
                    layer->getWidth(), layer->getHeight());
            layerMemoryTotal += layer->getWidth() * layer->getHeight() * 4;
        }
        log.appendFormat("  Layers Total         %6.2f kB (numLayers = %zu)\n",
                         layerMemoryTotal / 1024.0f, renderState->mActiveLayers.size());
    }


    log.appendFormat("Total memory usage:\n");
    log.appendFormat("  %zu bytes, %.2f MB (%.2f MB is purgeable)\n",
                     bytesCached, bytesCached / 1024.0f / 1024.0f,
                     mGrContext->getResourceCachePurgeableBytes() / 1024.0f / 1024.0f);


}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
