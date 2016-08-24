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

#include <LayerUpdateQueue.h>
#include <RenderNode.h>

#include <tests/common/TestUtils.h>

namespace android {
namespace uirenderer {

TEST(LayerUpdateQueue, construct) {
    LayerUpdateQueue queue;
    EXPECT_TRUE(queue.entries().empty());
}

// sync node properties, so properties() reflects correct width and height
static sp<RenderNode> createSyncedNode(uint32_t width, uint32_t height) {
    sp<RenderNode> node = TestUtils::createNode(0, 0, width, height, nullptr);
    TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    return node;
}

TEST(LayerUpdateQueue, enqueueSimple) {
    sp<RenderNode> a = createSyncedNode(100, 100);
    sp<RenderNode> b = createSyncedNode(200, 200);
    sp<RenderNode> c = createSyncedNode(200, 200);

    LayerUpdateQueue queue;
    queue.enqueueLayerWithDamage(a.get(), Rect(25, 25, 75, 75));
    queue.enqueueLayerWithDamage(b.get(), Rect(100, 100, 300, 300));
    queue.enqueueLayerWithDamage(c.get(), Rect(.5, .5, .5, .5));

    EXPECT_EQ(3u, queue.entries().size());

    EXPECT_EQ(a.get(), queue.entries()[0].renderNode);
    EXPECT_EQ(Rect(25, 25, 75, 75), queue.entries()[0].damage);
    EXPECT_EQ(b.get(), queue.entries()[1].renderNode);
    EXPECT_EQ(Rect(100, 100, 200, 200), queue.entries()[1].damage); // clipped to bounds
    EXPECT_EQ(c.get(), queue.entries()[2].renderNode);
    EXPECT_EQ(Rect(0, 0, 1, 1), queue.entries()[2].damage); // rounded out
}

TEST(LayerUpdateQueue, enqueueUnion) {
    sp<RenderNode> a = createSyncedNode(100, 100);

    LayerUpdateQueue queue;
    queue.enqueueLayerWithDamage(a.get(), Rect(10, 10, 20, 20));
    queue.enqueueLayerWithDamage(a.get(), Rect(30, 30, 40, 40));

    EXPECT_EQ(1u, queue.entries().size());

    EXPECT_EQ(a.get(), queue.entries()[0].renderNode);
    EXPECT_EQ(Rect(10, 10, 40, 40), queue.entries()[0].damage);
}

TEST(LayerUpdateQueue, clear) {
    sp<RenderNode> a = createSyncedNode(100, 100);

    LayerUpdateQueue queue;
    queue.enqueueLayerWithDamage(a.get(), Rect(100, 100));

    EXPECT_FALSE(queue.entries().empty());

    queue.clear();

    EXPECT_TRUE(queue.entries().empty());
}

};
};
