/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include <Rect.h>
#include <renderstate/OffscreenBufferPool.h>

#include <tests/common/TestUtils.h>

using namespace android::uirenderer;

TEST(OffscreenBuffer, computeIdealDimension) {
    EXPECT_EQ(64u, OffscreenBuffer::computeIdealDimension(1));
    EXPECT_EQ(64u, OffscreenBuffer::computeIdealDimension(31));
    EXPECT_EQ(64u, OffscreenBuffer::computeIdealDimension(33));
    EXPECT_EQ(64u, OffscreenBuffer::computeIdealDimension(64));
    EXPECT_EQ(1024u, OffscreenBuffer::computeIdealDimension(1000));
}

RENDERTHREAD_TEST(OffscreenBuffer, construct) {
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 49u, 149u);
    EXPECT_EQ(49u, layer.viewportWidth);
    EXPECT_EQ(149u, layer.viewportHeight);

    EXPECT_EQ(64u, layer.texture.width());
    EXPECT_EQ(192u, layer.texture.height());

    EXPECT_EQ(64u * 192u * 4u, layer.getSizeInBytes());
}

RENDERTHREAD_TEST(OffscreenBuffer, getTextureCoordinates) {
    OffscreenBuffer layerAligned(renderThread.renderState(), Caches::getInstance(), 256u, 256u);
    EXPECT_EQ(Rect(0, 1, 1, 0),
            layerAligned.getTextureCoordinates());

    OffscreenBuffer layerUnaligned(renderThread.renderState(), Caches::getInstance(), 200u, 225u);
    EXPECT_EQ(Rect(0, 225.0f / 256.0f, 200.0f / 256.0f, 0),
            layerUnaligned.getTextureCoordinates());
}

RENDERTHREAD_TEST(OffscreenBuffer, dirty) {
    OffscreenBuffer buffer(renderThread.renderState(), Caches::getInstance(), 256u, 256u);
    buffer.dirty(Rect(-100, -100, 100, 100));
    EXPECT_EQ(android::Rect(100, 100), buffer.region.getBounds());
}

RENDERTHREAD_TEST(OffscreenBufferPool, construct) {
    OffscreenBufferPool pool;
    EXPECT_EQ(0u, pool.getCount()) << "pool must be created empty";
    EXPECT_EQ(0u, pool.getSize()) << "pool must be created empty";
    EXPECT_EQ((uint32_t) Properties::layerPoolSize, pool.getMaxSize())
            << "pool must read size from Properties";
}

RENDERTHREAD_TEST(OffscreenBufferPool, getPutClear) {
    OffscreenBufferPool pool;

    auto layer = pool.get(renderThread.renderState(), 100u, 200u);
    EXPECT_EQ(100u, layer->viewportWidth);
    EXPECT_EQ(200u, layer->viewportHeight);

    ASSERT_LT(layer->getSizeInBytes(), pool.getMaxSize());

    pool.putOrDelete(layer);
    ASSERT_EQ(layer->getSizeInBytes(), pool.getSize());

    auto layer2 = pool.get(renderThread.renderState(), 102u, 202u);
    EXPECT_EQ(layer, layer2) << "layer should be recycled";
    ASSERT_EQ(0u, pool.getSize()) << "pool should have been emptied by removing only layer";

    pool.putOrDelete(layer);
    EXPECT_EQ(1u, pool.getCount());
    pool.clear();
    EXPECT_EQ(0u, pool.getSize());
    EXPECT_EQ(0u, pool.getCount());
}

RENDERTHREAD_TEST(OffscreenBufferPool, resize) {
    OffscreenBufferPool pool;

    auto layer = pool.get(renderThread.renderState(), 64u, 64u);
    layer->dirty(Rect(64, 64));

    // resize in place
    ASSERT_EQ(layer, pool.resize(layer, 60u, 55u));
    EXPECT_TRUE(layer->region.isEmpty()) << "In place resize should clear usage region";
    EXPECT_EQ(60u, layer->viewportWidth);
    EXPECT_EQ(55u, layer->viewportHeight);
    EXPECT_EQ(64u, layer->texture.width());
    EXPECT_EQ(64u, layer->texture.height());

    // resized to use different object in pool
    auto layer2 = pool.get(renderThread.renderState(), 128u, 128u);
    layer2->dirty(Rect(128, 128));
    EXPECT_FALSE(layer2->region.isEmpty());
    pool.putOrDelete(layer2);
    ASSERT_EQ(1u, pool.getCount());

    ASSERT_EQ(layer2, pool.resize(layer, 120u, 125u));
    EXPECT_TRUE(layer2->region.isEmpty()) << "Swap resize should clear usage region";
    EXPECT_EQ(120u, layer2->viewportWidth);
    EXPECT_EQ(125u, layer2->viewportHeight);
    EXPECT_EQ(128u, layer2->texture.width());
    EXPECT_EQ(128u, layer2->texture.height());

    // original allocation now only thing in pool
    EXPECT_EQ(1u, pool.getCount());
    EXPECT_EQ(layer->getSizeInBytes(), pool.getSize());

    pool.putOrDelete(layer2);
}

RENDERTHREAD_TEST(OffscreenBufferPool, putAndDestroy) {
    OffscreenBufferPool pool;
    // layer too big to return to the pool
    // Note: this relies on the fact that the pool won't reject based on max texture size
    auto hugeLayer = pool.get(renderThread.renderState(), pool.getMaxSize() / 64, 64);
    EXPECT_GT(hugeLayer->getSizeInBytes(), pool.getMaxSize());
    pool.putOrDelete(hugeLayer);
    EXPECT_EQ(0u, pool.getCount()); // failed to put (so was destroyed instead)
}

RENDERTHREAD_TEST(OffscreenBufferPool, clear) {
    EXPECT_EQ(0, GpuMemoryTracker::getInstanceCount(GpuObjectType::OffscreenBuffer));
    OffscreenBufferPool pool;

    // Create many buffers, with several at each size
    std::vector<OffscreenBuffer*> buffers;
    for (int size = 32; size <= 128; size += 32) {
        for (int i = 0; i < 10; i++) {
            buffers.push_back(pool.get(renderThread.renderState(), size, size));
        }
    }
    EXPECT_EQ(0u, pool.getCount()) << "Expect nothing inside";
    for (auto& buffer : buffers) pool.putOrDelete(buffer);
    EXPECT_EQ(40u, pool.getCount()) << "Expect all items added";
    EXPECT_EQ(40, GpuMemoryTracker::getInstanceCount(GpuObjectType::OffscreenBuffer));
    pool.clear();
    EXPECT_EQ(0u, pool.getCount()) << "Expect all items cleared";

    EXPECT_EQ(0, GpuMemoryTracker::getInstanceCount(GpuObjectType::OffscreenBuffer));
}
