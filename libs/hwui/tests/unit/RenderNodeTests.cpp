/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <VectorDrawable.h>
#include <gtest/gtest.h>

#include "AnimationContext.h"
#include "DamageAccumulator.h"
#include "IContextFactory.h"
#include "RenderNode.h"
#include "TreeInfo.h"
#include "renderthread/CanvasContext.h"
#include "tests/common/TestUtils.h"
#include "utils/Color.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

class ContextFactory : public android::uirenderer::IContextFactory {
public:
    android::uirenderer::AnimationContext* createAnimationContext(
            android::uirenderer::renderthread::TimeLord& clock) override {
        return new android::uirenderer::AnimationContext(clock);
    }
};

TEST(RenderNode, hasParents) {
    auto child = TestUtils::createNode(0, 0, 200, 400, [](RenderProperties& props, Canvas& canvas) {
        canvas.drawColor(Color::Red_500, SkBlendMode::kSrcOver);
    });
    auto parent = TestUtils::createNode(0, 0, 200, 400,
                                        [&child](RenderProperties& props, Canvas& canvas) {
                                            canvas.drawRenderNode(child.get());
                                        });

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_TRUE(child->hasParents()) << "Child node has no parent";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";

    TestUtils::recordNode(*parent, [](Canvas& canvas) {
        canvas.drawColor(Color::Amber_500, SkBlendMode::kSrcOver);
    });

    EXPECT_TRUE(child->hasParents()) << "Child should still have a parent";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_FALSE(child->hasParents()) << "Child should be removed";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";
}

TEST(RenderNode, validity) {
    auto child = TestUtils::createNode(0, 0, 200, 400, [](RenderProperties& props, Canvas& canvas) {
        canvas.drawColor(Color::Red_500, SkBlendMode::kSrcOver);
    });
    auto parent = TestUtils::createNode(0, 0, 200, 400,
                                        [&child](RenderProperties& props, Canvas& canvas) {
                                            canvas.drawRenderNode(child.get());
                                        });

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent->isValid());
    EXPECT_TRUE(child->nothingToDraw());
    EXPECT_TRUE(parent->nothingToDraw());

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent->isValid());
    EXPECT_FALSE(child->nothingToDraw());
    EXPECT_FALSE(parent->nothingToDraw());

    TestUtils::recordNode(*parent, [](Canvas& canvas) {
        canvas.drawColor(Color::Amber_500, SkBlendMode::kSrcOver);
    });

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent->isValid());
    EXPECT_FALSE(child->nothingToDraw());
    EXPECT_FALSE(parent->nothingToDraw());

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_FALSE(child->isValid());
    EXPECT_TRUE(parent->isValid());
    EXPECT_TRUE(child->nothingToDraw());
    EXPECT_FALSE(parent->nothingToDraw());

    TestUtils::recordNode(*child, [](Canvas& canvas) {
        canvas.drawColor(Color::Amber_500, SkBlendMode::kSrcOver);
    });

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(child->nothingToDraw());

    TestUtils::recordNode(*parent,
                          [&child](Canvas& canvas) { canvas.drawRenderNode(child.get()); });

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent->isValid());
    EXPECT_FALSE(child->nothingToDraw());
    EXPECT_FALSE(parent->nothingToDraw());

    parent->destroyHardwareResources();

    EXPECT_FALSE(child->isValid());
    EXPECT_FALSE(parent->isValid());
    EXPECT_TRUE(child->nothingToDraw());
    EXPECT_TRUE(parent->nothingToDraw());
}

TEST(RenderNode, multiTreeValidity) {
    auto child = TestUtils::createNode(0, 0, 200, 400, [](RenderProperties& props, Canvas& canvas) {
        canvas.drawColor(Color::Red_500, SkBlendMode::kSrcOver);
    });
    auto parent1 = TestUtils::createNode(0, 0, 200, 400,
                                         [&child](RenderProperties& props, Canvas& canvas) {
                                             canvas.drawRenderNode(child.get());
                                         });
    auto parent2 = TestUtils::createNode(0, 0, 200, 400,
                                         [&child](RenderProperties& props, Canvas& canvas) {
                                             canvas.drawRenderNode(child.get());
                                         });

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent1->isValid());
    EXPECT_TRUE(parent2->isValid());
    EXPECT_TRUE(child->nothingToDraw());
    EXPECT_TRUE(parent1->nothingToDraw());
    EXPECT_TRUE(parent2->nothingToDraw());

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent1);

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent1->isValid());
    EXPECT_TRUE(parent2->isValid());
    EXPECT_FALSE(child->nothingToDraw());
    EXPECT_FALSE(parent1->nothingToDraw());
    EXPECT_TRUE(parent2->nothingToDraw());

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent2);

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent1->isValid());
    EXPECT_TRUE(parent2->isValid());
    EXPECT_FALSE(child->nothingToDraw());
    EXPECT_FALSE(parent1->nothingToDraw());
    EXPECT_FALSE(parent2->nothingToDraw());

    TestUtils::recordNode(*parent1, [](Canvas& canvas) {
        canvas.drawColor(Color::Amber_500, SkBlendMode::kSrcOver);
    });

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent1);

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent1->isValid());
    EXPECT_TRUE(parent2->isValid());
    EXPECT_FALSE(child->nothingToDraw());
    EXPECT_FALSE(parent1->nothingToDraw());
    EXPECT_FALSE(parent2->nothingToDraw());

    TestUtils::recordNode(*parent2, [](Canvas& canvas) {
        canvas.drawColor(Color::Amber_500, SkBlendMode::kSrcOver);
    });

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent2);

    EXPECT_FALSE(child->isValid());
    EXPECT_TRUE(parent1->isValid());
    EXPECT_TRUE(parent2->isValid());
    EXPECT_TRUE(child->nothingToDraw());
    EXPECT_FALSE(parent1->nothingToDraw());
    EXPECT_FALSE(parent2->nothingToDraw());

    TestUtils::recordNode(*child, [](Canvas& canvas) {
        canvas.drawColor(Color::Red_500, SkBlendMode::kSrcOver);
    });
    TestUtils::syncHierarchyPropertiesAndDisplayList(child);

    TestUtils::recordNode(*parent1,
                          [&child](Canvas& canvas) { canvas.drawRenderNode(child.get()); });
    TestUtils::syncHierarchyPropertiesAndDisplayList(parent1);

    TestUtils::recordNode(*parent2,
                          [&child](Canvas& canvas) { canvas.drawRenderNode(child.get()); });
    TestUtils::syncHierarchyPropertiesAndDisplayList(parent2);

    EXPECT_TRUE(child->isValid());
    EXPECT_TRUE(parent1->isValid());
    EXPECT_TRUE(parent2->isValid());
    EXPECT_FALSE(child->nothingToDraw());
    EXPECT_FALSE(parent1->nothingToDraw());
    EXPECT_FALSE(parent2->nothingToDraw());

    parent1->destroyHardwareResources();

    EXPECT_TRUE(child->isValid());
    EXPECT_FALSE(parent1->isValid());
    EXPECT_TRUE(parent2->isValid());
    EXPECT_FALSE(child->nothingToDraw());
    EXPECT_TRUE(parent1->nothingToDraw());
    EXPECT_FALSE(parent2->nothingToDraw());

    parent2->destroyHardwareResources();

    EXPECT_FALSE(child->isValid());
    EXPECT_FALSE(parent1->isValid());
    EXPECT_FALSE(parent2->isValid());
    EXPECT_TRUE(child->nothingToDraw());
    EXPECT_TRUE(parent1->nothingToDraw());
    EXPECT_TRUE(parent2->nothingToDraw());
}

TEST(RenderNode, releasedCallback) {
    int functor = WebViewFunctor_create(
            nullptr, TestUtils::createMockFunctor(RenderMode::OpenGL_ES), RenderMode::OpenGL_ES);

    auto node = TestUtils::createNode(0, 0, 200, 400, [&](RenderProperties& props, Canvas& canvas) {
        canvas.drawWebViewFunctor(functor);
    });
    TestUtils::runOnRenderThreadUnmanaged([&] (RenderThread&) {
        TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    });
    auto& counts = TestUtils::countsForFunctor(functor);
    EXPECT_EQ(1, counts.sync);
    EXPECT_EQ(0, counts.destroyed);

    TestUtils::recordNode(*node, [&](Canvas& canvas) {
        canvas.drawWebViewFunctor(functor);
    });
    EXPECT_EQ(1, counts.sync);
    EXPECT_EQ(0, counts.destroyed);

    TestUtils::runOnRenderThreadUnmanaged([&] (RenderThread&) {
        TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    });
    EXPECT_EQ(2, counts.sync);
    EXPECT_EQ(0, counts.destroyed);

    WebViewFunctor_release(functor);
    EXPECT_EQ(2, counts.sync);
    EXPECT_EQ(0, counts.destroyed);

    TestUtils::recordNode(*node, [](Canvas& canvas) {});
    TestUtils::runOnRenderThreadUnmanaged([&] (RenderThread&) {
        TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    });
    // Fence on any remaining post'd work
    TestUtils::runOnRenderThreadUnmanaged([] (RenderThread&) {});
    EXPECT_EQ(2, counts.sync);
    EXPECT_EQ(1, counts.destroyed);
}

RENDERTHREAD_TEST(RenderNode, prepareTree_nullableDisplayList) {
    auto rootNode = TestUtils::createNode(0, 0, 200, 400, nullptr);
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, rootNode.get(), &contextFactory));
    TreeInfo info(TreeInfo::MODE_RT_ONLY, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;

    {
        auto nonNullDLNode =
                TestUtils::createNode(0, 0, 200, 400, [](RenderProperties& props, Canvas& canvas) {
                    canvas.drawColor(Color::Red_500, SkBlendMode::kSrcOver);
                });
        TestUtils::syncHierarchyPropertiesAndDisplayList(nonNullDLNode);
        EXPECT_TRUE(nonNullDLNode->getDisplayList());
        nonNullDLNode->prepareTree(info);
    }

    {
        auto nullDLNode = TestUtils::createNode(0, 0, 200, 400, nullptr);
        TestUtils::syncHierarchyPropertiesAndDisplayList(nullDLNode);
        EXPECT_FALSE(nullDLNode->getDisplayList());
        nullDLNode->prepareTree(info);
    }

    canvasContext->destroy();
}

// TODO: Is this supposed to work in SkiaGL/SkiaVK?
RENDERTHREAD_TEST(DISABLED_RenderNode, prepareTree_HwLayer_AVD_enqueueDamage) {
    VectorDrawable::Group* group = new VectorDrawable::Group();
    sp<VectorDrawableRoot> vectorDrawable(new VectorDrawableRoot(group));

    auto rootNode =
            TestUtils::createNode(0, 0, 200, 400, [&](RenderProperties& props, Canvas& canvas) {
                canvas.drawVectorDrawable(vectorDrawable.get());
            });
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, rootNode.get(), &contextFactory));
    canvasContext->setSurface(nullptr);
    TreeInfo info(TreeInfo::MODE_RT_ONLY, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    LayerUpdateQueue layerUpdateQueue;
    info.damageAccumulator = &damageAccumulator;
    info.layerUpdateQueue = &layerUpdateQueue;

    // Put node on HW layer
    rootNode->mutateStagingProperties().mutateLayerProperties().setType(LayerType::RenderLayer);

    TestUtils::syncHierarchyPropertiesAndDisplayList(rootNode);
    rootNode->prepareTree(info);

    // Check that the VD is in the dislay list, and the layer update queue contains the correct
    // damage rect.
    EXPECT_TRUE(rootNode->getDisplayList().hasVectorDrawables());
    ASSERT_FALSE(info.layerUpdateQueue->entries().empty());
    EXPECT_EQ(rootNode.get(), info.layerUpdateQueue->entries().at(0).renderNode.get());
    EXPECT_EQ(uirenderer::Rect(0, 0, 200, 400), info.layerUpdateQueue->entries().at(0).damage);
    canvasContext->destroy();
}
