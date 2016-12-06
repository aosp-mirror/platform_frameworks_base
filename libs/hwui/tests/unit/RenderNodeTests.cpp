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
    android::uirenderer::AnimationContext* createAnimationContext
        (android::uirenderer::renderthread::TimeLord& clock) override {
        return new android::uirenderer::AnimationContext(clock);
    }
};

TEST(RenderNode, hasParents) {
    auto child = TestUtils::createNode(0, 0, 200, 400,
            [](RenderProperties& props, TestCanvas& canvas) {
        canvas.drawColor(Color::Red_500, SkXfermode::kSrcOver_Mode);
    });
    auto parent = TestUtils::createNode(0, 0, 200, 400,
            [&child](RenderProperties& props, TestCanvas& canvas) {
        canvas.drawRenderNode(child.get());
    });

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_TRUE(child->hasParents()) << "Child node has no parent";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";

    TestUtils::recordNode(*parent, [](TestCanvas& canvas) {
        canvas.drawColor(Color::Amber_500, SkXfermode::kSrcOver_Mode);
    });

    EXPECT_TRUE(child->hasParents()) << "Child should still have a parent";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_FALSE(child->hasParents()) << "Child should be removed";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";
}

TEST(RenderNode, releasedCallback) {
    class DecRefOnReleased : public GlFunctorLifecycleListener {
    public:
        DecRefOnReleased(int* refcnt) : mRefCnt(refcnt) {}
        void onGlFunctorReleased(Functor* functor) override {
            *mRefCnt -= 1;
        }
    private:
        int* mRefCnt;
    };

    int refcnt = 0;
    sp<DecRefOnReleased> listener(new DecRefOnReleased(&refcnt));
    Functor noopFunctor;

    auto node = TestUtils::createNode(0, 0, 200, 400,
            [&](RenderProperties& props, TestCanvas& canvas) {
        refcnt++;
        canvas.callDrawGLFunction(&noopFunctor, listener.get());
    });
    TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    EXPECT_EQ(1, refcnt);

    TestUtils::recordNode(*node, [&](TestCanvas& canvas) {
        refcnt++;
        canvas.callDrawGLFunction(&noopFunctor, listener.get());
    });
    EXPECT_EQ(2, refcnt);

    TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    EXPECT_EQ(1, refcnt);

    TestUtils::recordNode(*node, [](TestCanvas& canvas) {});
    EXPECT_EQ(1, refcnt);
    TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    EXPECT_EQ(0, refcnt);
}

RENDERTHREAD_TEST(RenderNode, prepareTree_nullableDisplayList) {
    ContextFactory contextFactory;
    CanvasContext canvasContext(renderThread, false, nullptr, &contextFactory);
    TreeInfo info(TreeInfo::MODE_RT_ONLY, canvasContext);
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;
    info.observer = nullptr;

    {
        auto nonNullDLNode = TestUtils::createNode(0, 0, 200, 400,
                [](RenderProperties& props, TestCanvas& canvas) {
            canvas.drawColor(Color::Red_500, SkXfermode::kSrcOver_Mode);
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

    canvasContext.destroy(nullptr);
}
