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

#include <gtest/gtest.h>

#include "renderthread/CacheManager.h"
#include "renderthread/EglManager.h"
#include "tests/common/TestUtils.h"

#include <SkImagePriv.h>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

static size_t getCacheUsage(GrDirectContext* grContext) {
    size_t cacheUsage;
    grContext->getResourceCacheUsage(nullptr, &cacheUsage);
    return cacheUsage;
}

// TOOD(258700630): fix this test and re-enable
RENDERTHREAD_SKIA_PIPELINE_TEST(CacheManager, DISABLED_trimMemory) {
    int32_t width = DeviceInfo::get()->getWidth();
    int32_t height = DeviceInfo::get()->getHeight();
    GrDirectContext* grContext = renderThread.getGrContext();
    ASSERT_TRUE(grContext != nullptr);

    // create pairs of offscreen render targets and images until we exceed the
    // backgroundCacheSizeLimit
    std::vector<sk_sp<SkSurface>> surfaces;

    while (getCacheUsage(grContext) <= renderThread.cacheManager().getBackgroundCacheSize()) {
        SkImageInfo info = SkImageInfo::MakeA8(width, height);
        sk_sp<SkSurface> surface = SkSurface::MakeRenderTarget(grContext, SkBudgeted::kYes, info);
        surface->getCanvas()->drawColor(SK_AlphaTRANSPARENT);

        grContext->flushAndSubmit();

        surfaces.push_back(surface);
    }

    // create an image and pin it so that we have something with a unique key in the cache
    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(SkImageInfo::MakeA8(width, height));
    sk_sp<SkImage> image = bitmap->makeImage();
    ASSERT_TRUE(SkImage_pinAsTexture(image.get(), grContext));

    // attempt to trim all memory while we still hold strong refs
    renderThread.cacheManager().trimMemory(CacheManager::TrimMemoryMode::Complete);
    ASSERT_TRUE(0 == grContext->getResourceCachePurgeableBytes());

    // free the surfaces
    for (size_t i = 0; i < surfaces.size(); i++) {
        ASSERT_TRUE(surfaces[i]->unique());
        surfaces[i].reset();
    }

    // unpin the image which should add a unique purgeable key to the cache
    SkImage_unpinAsTexture(image.get(), grContext);

    // verify that we have enough purgeable bytes
    const size_t purgeableBytes = grContext->getResourceCachePurgeableBytes();
    ASSERT_TRUE(renderThread.cacheManager().getBackgroundCacheSize() < purgeableBytes);

    // UI hidden and make sure only some got purged (unique should remain)
    renderThread.cacheManager().trimMemory(CacheManager::TrimMemoryMode::UiHidden);
    ASSERT_TRUE(0 < grContext->getResourceCachePurgeableBytes());
    ASSERT_TRUE(renderThread.cacheManager().getBackgroundCacheSize() > getCacheUsage(grContext));

    // complete and make sure all get purged
    renderThread.cacheManager().trimMemory(CacheManager::TrimMemoryMode::Complete);
    ASSERT_TRUE(0 == grContext->getResourceCachePurgeableBytes());
}
