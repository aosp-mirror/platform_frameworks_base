/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "renderthread/CacheManager.h"

namespace android {
namespace uirenderer {
namespace renderthread {

CacheManager::CacheManager(RenderThread& thread)
        : mRenderThread(thread), mMemoryPolicy(loadMemoryPolicy()) {}

void CacheManager::setupCacheLimits() {}

void CacheManager::destroy() {}

void CacheManager::trimMemory(TrimLevel mode) {}

void CacheManager::trimCaches(CacheTrimLevel mode) {}

void CacheManager::trimStaleResources() {}

void CacheManager::getMemoryUsage(size_t* cpuUsage, size_t* gpuUsage) {}

void CacheManager::dumpMemoryUsage(String8& log, const RenderState* renderState) {}

void CacheManager::onFrameCompleted() {}

void CacheManager::onThreadIdle() {}

void CacheManager::scheduleDestroyContext() {}

void CacheManager::cancelDestroyContext() {}

bool CacheManager::areAllContextsStopped() {
    return false;
}

void CacheManager::checkUiHidden() {}

void CacheManager::registerCanvasContext(CanvasContext* context) {}

void CacheManager::unregisterCanvasContext(CanvasContext* context) {}

void CacheManager::onContextStopped(CanvasContext* context) {}

void CacheManager::notifyNextFrameSize(int width, int height) {}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
