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
#include "pipeline/skia/GLFunctorDrawable.h"
#include "pipeline/skia/SkiaDisplayList.h"
#include "renderthread/CanvasContext.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::skiapipeline;

TEST(SkiaDisplayList, create) {
    SkiaDisplayList skiaDL;
    ASSERT_TRUE(skiaDL.isEmpty());
    ASSERT_FALSE(skiaDL.mProjectionReceiver);
}

TEST(SkiaDisplayList, reset) {
    std::unique_ptr<SkiaDisplayList> skiaDL;
    {
        SkiaRecordingCanvas canvas{nullptr, 1, 1};
        canvas.drawColor(0, SkBlendMode::kSrc);
        skiaDL.reset(canvas.finishRecording());
    }

    SkCanvas dummyCanvas;
    RenderNodeDrawable drawable(nullptr, &dummyCanvas);
    skiaDL->mChildNodes.emplace_back(nullptr, &dummyCanvas);
    GLFunctorDrawable functorDrawable(nullptr, nullptr, &dummyCanvas);
    skiaDL->mChildFunctors.push_back(&functorDrawable);
    skiaDL->mMutableImages.push_back(nullptr);
    skiaDL->mVectorDrawables.push_back(nullptr);
    skiaDL->mProjectionReceiver = &drawable;

    ASSERT_FALSE(skiaDL->mChildNodes.empty());
    ASSERT_FALSE(skiaDL->mChildFunctors.empty());
    ASSERT_FALSE(skiaDL->mMutableImages.empty());
    ASSERT_FALSE(skiaDL->mVectorDrawables.empty());
    ASSERT_FALSE(skiaDL->isEmpty());
    ASSERT_TRUE(skiaDL->mProjectionReceiver);

    skiaDL->reset();

    ASSERT_TRUE(skiaDL->mChildNodes.empty());
    ASSERT_TRUE(skiaDL->mChildFunctors.empty());
    ASSERT_TRUE(skiaDL->mMutableImages.empty());
    ASSERT_TRUE(skiaDL->mVectorDrawables.empty());
    ASSERT_TRUE(skiaDL->isEmpty());
    ASSERT_FALSE(skiaDL->mProjectionReceiver);
}

TEST(SkiaDisplayList, reuseDisplayList) {
    sp<RenderNode> renderNode = new RenderNode();
    std::unique_ptr<SkiaDisplayList> availableList;

    // no list has been attached so it should return a nullptr
    availableList = renderNode->detachAvailableList();
    ASSERT_EQ(availableList.get(), nullptr);

    // attach a displayList for reuse
    SkiaDisplayList skiaDL;
    ASSERT_TRUE(skiaDL.reuseDisplayList(renderNode.get(), nullptr));

    // detach the list that you just attempted to reuse
    availableList = renderNode->detachAvailableList();
    ASSERT_EQ(availableList.get(), &skiaDL);
    availableList.release();  // prevents an invalid free since our DL is stack allocated

    // after detaching there should return no available list
    availableList = renderNode->detachAvailableList();
    ASSERT_EQ(availableList.get(), nullptr);
}

TEST(SkiaDisplayList, syncContexts) {
    SkiaDisplayList skiaDL;

    SkCanvas dummyCanvas;
    TestUtils::MockFunctor functor;
    GLFunctorDrawable functorDrawable(&functor, nullptr, &dummyCanvas);
    skiaDL.mChildFunctors.push_back(&functorDrawable);

    int functor2 = WebViewFunctor_create(
            nullptr, TestUtils::createMockFunctor(RenderMode::OpenGL_ES), RenderMode::OpenGL_ES);
    auto& counts = TestUtils::countsForFunctor(functor2);
    skiaDL.mChildFunctors.push_back(
            skiaDL.allocateDrawable<GLFunctorDrawable>(functor2, &dummyCanvas));
    WebViewFunctor_release(functor2);

    SkRect bounds = SkRect::MakeWH(200, 200);
    VectorDrawableRoot vectorDrawable(new VectorDrawable::Group());
    vectorDrawable.mutateStagingProperties()->setBounds(bounds);
    skiaDL.mVectorDrawables.push_back(&vectorDrawable);

    // ensure that the functor and vectorDrawable are properly synced
    TestUtils::runOnRenderThread([&](auto&) {
        skiaDL.syncContents(WebViewSyncData{
                .applyForceDark = false,
        });
    });

    EXPECT_EQ(functor.getLastMode(), DrawGlInfo::kModeSync);
    EXPECT_EQ(counts.sync, 1);
    EXPECT_EQ(counts.destroyed, 0);
    EXPECT_EQ(vectorDrawable.mutateProperties()->getBounds(), bounds);

    skiaDL.reset();
    TestUtils::runOnRenderThread([](auto&) {
        // Fence
    });
    EXPECT_EQ(counts.destroyed, 1);
}

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) override {
        return new AnimationContext(clock);
    }
};

RENDERTHREAD_SKIA_PIPELINE_TEST(SkiaDisplayList, prepareListAndChildren) {
    auto rootNode = TestUtils::createNode(0, 0, 200, 400, nullptr);
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, rootNode.get(), &contextFactory));
    TreeInfo info(TreeInfo::MODE_FULL, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;

    SkiaDisplayList skiaDL;

    // prepare with a clean VD
    VectorDrawableRoot cleanVD(new VectorDrawable::Group());
    skiaDL.mVectorDrawables.push_back(&cleanVD);
    cleanVD.getBitmapUpdateIfDirty();  // this clears the dirty bit

    ASSERT_FALSE(cleanVD.isDirty());
    ASSERT_FALSE(cleanVD.getPropertyChangeWillBeConsumed());
    TestUtils::MockTreeObserver observer;
    ASSERT_FALSE(skiaDL.prepareListAndChildren(observer, info, false,
                                               [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
    ASSERT_TRUE(cleanVD.getPropertyChangeWillBeConsumed());

    // prepare again this time adding a dirty VD
    VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
    skiaDL.mVectorDrawables.push_back(&dirtyVD);

    ASSERT_TRUE(dirtyVD.isDirty());
    ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());
    ASSERT_TRUE(skiaDL.prepareListAndChildren(observer, info, false,
                                              [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
    ASSERT_TRUE(dirtyVD.getPropertyChangeWillBeConsumed());

    // prepare again this time adding a RenderNode and a callback
    sp<RenderNode> renderNode = new RenderNode();
    TreeInfo* infoPtr = &info;
    SkCanvas dummyCanvas;
    skiaDL.mChildNodes.emplace_back(renderNode.get(), &dummyCanvas);
    bool hasRun = false;
    ASSERT_TRUE(skiaDL.prepareListAndChildren(
            observer, info, false,
            [&hasRun, renderNode, infoPtr](RenderNode* n, TreeObserver& observer, TreeInfo& i,
                                           bool r) {
                hasRun = true;
                ASSERT_EQ(renderNode.get(), n);
                ASSERT_EQ(infoPtr, &i);
                ASSERT_FALSE(r);
            }));
    ASSERT_TRUE(hasRun);

    canvasContext->destroy();
}

TEST(SkiaDisplayList, updateChildren) {
    SkiaDisplayList skiaDL;

    sp<RenderNode> renderNode = new RenderNode();
    SkCanvas dummyCanvas;
    skiaDL.mChildNodes.emplace_back(renderNode.get(), &dummyCanvas);
    skiaDL.updateChildren([renderNode](RenderNode* n) { ASSERT_EQ(renderNode.get(), n); });
}
