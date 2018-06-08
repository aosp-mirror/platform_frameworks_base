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

#include <GrRectanizer.h>
#include "pipeline/skia/VectorDrawableAtlas.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::skiapipeline;

RENDERTHREAD_SKIA_PIPELINE_TEST(VectorDrawableAtlas, addGetRemove) {
    VectorDrawableAtlas atlas(100 * 100);
    atlas.prepareForDraw(renderThread.getGrContext());
    // create 150 rects 10x10, which won't fit in the atlas (atlas can fit no more than 100 rects)
    const int MAX_RECTS = 150;
    AtlasEntry VDRects[MAX_RECTS];

    sk_sp<SkSurface> atlasSurface;

    // check we are able to allocate new rects
    // check that rects in the atlas do not intersect
    for (uint32_t i = 0; i < MAX_RECTS; i++) {
        VDRects[i] = atlas.requestNewEntry(10, 10, renderThread.getGrContext());
        if (0 == i) {
            atlasSurface = VDRects[0].surface;
        }
        ASSERT_TRUE(VDRects[i].key != INVALID_ATLAS_KEY);
        ASSERT_TRUE(VDRects[i].surface.get() != nullptr);
        ASSERT_TRUE(VDRects[i].rect.width() == 10 && VDRects[i].rect.height() == 10);

        // nothing in the atlas should intersect
        if (atlasSurface.get() == VDRects[i].surface.get()) {
            for (uint32_t j = 0; j < i; j++) {
                if (atlasSurface.get() == VDRects[j].surface.get()) {
                    ASSERT_FALSE(VDRects[i].rect.intersect(VDRects[j].rect));
                }
            }
        }
    }

    // first 1/3 rects should all be in the same surface
    for (uint32_t i = 1; i < MAX_RECTS / 3; i++) {
        ASSERT_NE(VDRects[i].key, VDRects[0].key);
        ASSERT_EQ(VDRects[i].surface.get(), atlasSurface.get());
    }

    // first rect is using atlas and last is a standalone surface
    ASSERT_NE(VDRects[0].surface.get(), VDRects[MAX_RECTS - 1].surface.get());

    // check getEntry returns the same surfaces that we had created
    for (uint32_t i = 0; i < MAX_RECTS; i++) {
        auto VDRect = atlas.getEntry(VDRects[i].key);
        ASSERT_TRUE(VDRect.key != INVALID_ATLAS_KEY);
        ASSERT_EQ(VDRects[i].key, VDRect.key);
        ASSERT_EQ(VDRects[i].surface.get(), VDRect.surface.get());
        ASSERT_EQ(VDRects[i].rect, VDRect.rect);
        atlas.releaseEntry(VDRect.key);
    }

    // check that any new rects will be allocated in the atlas, even that rectanizer is full.
    // rects in the atlas should not intersect.
    for (uint32_t i = 0; i < MAX_RECTS / 3; i++) {
        VDRects[i] = atlas.requestNewEntry(10, 10, renderThread.getGrContext());
        ASSERT_TRUE(VDRects[i].key != INVALID_ATLAS_KEY);
        ASSERT_EQ(VDRects[i].surface.get(), atlasSurface.get());
        ASSERT_TRUE(VDRects[i].rect.width() == 10 && VDRects[i].rect.height() == 10);
        for (uint32_t j = 0; j < i; j++) {
            ASSERT_FALSE(VDRects[i].rect.intersect(VDRects[j].rect));
        }
    }
}

RENDERTHREAD_SKIA_PIPELINE_TEST(VectorDrawableAtlas, disallowSharedSurface) {
    VectorDrawableAtlas atlas(100 * 100);
    // don't allow to use a shared surface
    atlas.setStorageMode(VectorDrawableAtlas::StorageMode::disallowSharedSurface);
    atlas.prepareForDraw(renderThread.getGrContext());
    // create 150 rects 10x10, which won't fit in the atlas (atlas can fit no more than 100 rects)
    const int MAX_RECTS = 150;
    AtlasEntry VDRects[MAX_RECTS];

    // check we are able to allocate new rects
    // check that rects in the atlas use unique surfaces
    for (uint32_t i = 0; i < MAX_RECTS; i++) {
        VDRects[i] = atlas.requestNewEntry(10, 10, renderThread.getGrContext());
        ASSERT_TRUE(VDRects[i].key != INVALID_ATLAS_KEY);
        ASSERT_TRUE(VDRects[i].surface.get() != nullptr);
        ASSERT_TRUE(VDRects[i].rect.width() == 10 && VDRects[i].rect.height() == 10);

        // nothing in the atlas should use the same surface
        for (uint32_t j = 0; j < i; j++) {
            ASSERT_NE(VDRects[i].surface.get(), VDRects[j].surface.get());
        }
    }
}

RENDERTHREAD_SKIA_PIPELINE_TEST(VectorDrawableAtlas, repack) {
    VectorDrawableAtlas atlas(100 * 100);
    ASSERT_FALSE(atlas.isFragmented());
    atlas.prepareForDraw(renderThread.getGrContext());
    ASSERT_FALSE(atlas.isFragmented());
    // create 150 rects 10x10, which won't fit in the atlas (atlas can fit no more than 100 rects)
    const int MAX_RECTS = 150;
    AtlasEntry VDRects[MAX_RECTS];

    sk_sp<SkSurface> atlasSurface;

    // fill the atlas with check we are able to allocate new rects
    for (uint32_t i = 0; i < MAX_RECTS; i++) {
        VDRects[i] = atlas.requestNewEntry(10, 10, renderThread.getGrContext());
        if (0 == i) {
            atlasSurface = VDRects[0].surface;
        }
        ASSERT_TRUE(VDRects[i].key != INVALID_ATLAS_KEY);
    }

    ASSERT_FALSE(atlas.isFragmented());

    // first 1/3 rects should all be in the same surface
    for (uint32_t i = 1; i < MAX_RECTS / 3; i++) {
        ASSERT_NE(VDRects[i].key, VDRects[0].key);
        ASSERT_EQ(VDRects[i].surface.get(), atlasSurface.get());
    }

    // release all entries
    for (uint32_t i = 0; i < MAX_RECTS; i++) {
        auto VDRect = atlas.getEntry(VDRects[i].key);
        ASSERT_TRUE(VDRect.key != INVALID_ATLAS_KEY);
        atlas.releaseEntry(VDRect.key);
    }

    ASSERT_FALSE(atlas.isFragmented());

    // allocate 4x4 rects, which will fragment the atlas badly, because each entry occupies a 10x10
    // area
    for (uint32_t i = 0; i < 4 * MAX_RECTS; i++) {
        AtlasEntry entry = atlas.requestNewEntry(4, 4, renderThread.getGrContext());
        ASSERT_TRUE(entry.key != INVALID_ATLAS_KEY);
    }

    ASSERT_TRUE(atlas.isFragmented());

    atlas.repackIfNeeded(renderThread.getGrContext());

    ASSERT_FALSE(atlas.isFragmented());
}