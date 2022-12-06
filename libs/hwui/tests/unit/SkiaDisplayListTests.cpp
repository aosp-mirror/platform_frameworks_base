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
#include "tests/common/TestContext.h"
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
        skiaDL = canvas.finishRecording();
    }

    SkCanvas dummyCanvas;
    RenderNodeDrawable drawable(nullptr, &dummyCanvas);
    skiaDL->mChildNodes.emplace_back(nullptr, &dummyCanvas);
    int functor1 = WebViewFunctor_create(
            nullptr, TestUtils::createMockFunctor(RenderMode::OpenGL_ES), RenderMode::OpenGL_ES);
    GLFunctorDrawable functorDrawable{functor1, &dummyCanvas};
    WebViewFunctor_release(functor1);
    skiaDL->mChildFunctors.push_back(&functorDrawable);
    skiaDL->mMutableImages.push_back(nullptr);
    skiaDL->appendVD(nullptr);
    skiaDL->mProjectionReceiver = &drawable;

    ASSERT_FALSE(skiaDL->mChildNodes.empty());
    ASSERT_FALSE(skiaDL->mChildFunctors.empty());
    ASSERT_FALSE(skiaDL->mMutableImages.empty());
    ASSERT_TRUE(skiaDL->hasVectorDrawables());
    ASSERT_FALSE(skiaDL->isEmpty());
    ASSERT_TRUE(skiaDL->mProjectionReceiver);

    skiaDL->reset();

    ASSERT_TRUE(skiaDL->mChildNodes.empty());
    ASSERT_TRUE(skiaDL->mChildFunctors.empty());
    ASSERT_TRUE(skiaDL->mMutableImages.empty());
    ASSERT_FALSE(skiaDL->hasVectorDrawables());
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
    ASSERT_TRUE(skiaDL.reuseDisplayList(renderNode.get()));

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

    int functor1 = WebViewFunctor_create(
            nullptr, TestUtils::createMockFunctor(RenderMode::OpenGL_ES), RenderMode::OpenGL_ES);
    auto& counts = TestUtils::countsForFunctor(functor1);
    skiaDL.mChildFunctors.push_back(
            skiaDL.allocateDrawable<GLFunctorDrawable>(functor1, &dummyCanvas));
    WebViewFunctor_release(functor1);

    SkRect bounds = SkRect::MakeWH(200, 200);
    VectorDrawableRoot vectorDrawable(new VectorDrawable::Group());
    vectorDrawable.mutateStagingProperties()->setBounds(bounds);
    skiaDL.appendVD(&vectorDrawable);

    // ensure that the functor and vectorDrawable are properly synced
    TestUtils::runOnRenderThread([&](auto&) {
        skiaDL.syncContents(WebViewSyncData{
                .applyForceDark = false,
        });
    });

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

    // The VectorDrawableRoot needs to have bounds on screen (and therefore not
    // empty) in order to have PropertyChangeWillBeConsumed set.
    const auto bounds = SkRect::MakeIWH(100, 100);

    // prepare with a clean VD
    VectorDrawableRoot cleanVD(new VectorDrawable::Group());
    cleanVD.mutateProperties()->setBounds(bounds);
    skiaDL.appendVD(&cleanVD);
    cleanVD.getBitmapUpdateIfDirty();  // this clears the dirty bit

    ASSERT_FALSE(cleanVD.isDirty());
    ASSERT_FALSE(cleanVD.getPropertyChangeWillBeConsumed());
    TestUtils::MockTreeObserver observer;
    ASSERT_FALSE(skiaDL.prepareListAndChildren(observer, info, false,
                                               [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
    ASSERT_FALSE(cleanVD.getPropertyChangeWillBeConsumed());

    // prepare again this time adding a dirty VD
    VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
    dirtyVD.mutateProperties()->setBounds(bounds);
    skiaDL.appendVD(&dirtyVD);

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

RENDERTHREAD_SKIA_PIPELINE_TEST(SkiaDisplayList, prepareListAndChildren_vdOffscreen) {
    auto rootNode = TestUtils::createNode(0, 0, 200, 400, nullptr);
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, rootNode.get(), &contextFactory));

    // Set up a Surface so that we can position the VectorDrawable offscreen.
    test::TestContext testContext;
    testContext.setRenderOffscreen(true);
    auto surface = testContext.surface();
    int width = ANativeWindow_getWidth(surface.get());
    int height = ANativeWindow_getHeight(surface.get());
    canvasContext->setSurface(surface.get());

    TreeInfo info(TreeInfo::MODE_FULL, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;

    // The VectorDrawableRoot needs to have bounds on screen (and therefore not
    // empty) in order to have PropertyChangeWillBeConsumed set.
    const auto bounds = SkRect::MakeIWH(100, 100);

    for (const SkRect b : {bounds.makeOffset(width, 0),
                           bounds.makeOffset(0, height),
                           bounds.makeOffset(-bounds.width(), 0),
                           bounds.makeOffset(0, -bounds.height())}) {
        SkiaDisplayList skiaDL;
        VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
        dirtyVD.mutateProperties()->setBounds(b);
        skiaDL.appendVD(&dirtyVD);

        ASSERT_TRUE(dirtyVD.isDirty());
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());

        TestUtils::MockTreeObserver observer;
        ASSERT_FALSE(skiaDL.prepareListAndChildren(
                observer, info, false, [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());
    }

    // The DamageAccumulator's transform can also result in the
    // VectorDrawableRoot being offscreen.
    for (const SkISize translate : { SkISize{width, 0},
                                     SkISize{0, height},
                                     SkISize{-width, 0},
                                     SkISize{0, -height}}) {
        Matrix4 mat4;
        mat4.translate(translate.fWidth, translate.fHeight);
        damageAccumulator.pushTransform(&mat4);

        SkiaDisplayList skiaDL;
        VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
        dirtyVD.mutateProperties()->setBounds(bounds);
        skiaDL.appendVD(&dirtyVD);

        ASSERT_TRUE(dirtyVD.isDirty());
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());

        TestUtils::MockTreeObserver observer;
        ASSERT_FALSE(skiaDL.prepareListAndChildren(
                observer, info, false, [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());
        damageAccumulator.popTransform();
    }

    // Another way to be offscreen: a matrix from the draw call.
    for (const SkMatrix translate : { SkMatrix::Translate(width, 0),
                                      SkMatrix::Translate(0, height),
                                      SkMatrix::Translate(-width, 0),
                                      SkMatrix::Translate(0, -height)}) {
        SkiaDisplayList skiaDL;
        VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
        dirtyVD.mutateProperties()->setBounds(bounds);
        skiaDL.appendVD(&dirtyVD, translate);

        ASSERT_TRUE(dirtyVD.isDirty());
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());

        TestUtils::MockTreeObserver observer;
        ASSERT_FALSE(skiaDL.prepareListAndChildren(
                observer, info, false, [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());
    }

    // Verify that the matrices are combined in the right order.
    {
        // Rotate and then translate, so the VD is offscreen.
        Matrix4 mat4;
        mat4.loadRotate(180);
        damageAccumulator.pushTransform(&mat4);

        SkiaDisplayList skiaDL;
        VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
        dirtyVD.mutateProperties()->setBounds(bounds);
        SkMatrix translate = SkMatrix::Translate(50, 50);
        skiaDL.appendVD(&dirtyVD, translate);

        ASSERT_TRUE(dirtyVD.isDirty());
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());

        TestUtils::MockTreeObserver observer;
        ASSERT_FALSE(skiaDL.prepareListAndChildren(
                observer, info, false, [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());
        damageAccumulator.popTransform();
    }
    {
        // Switch the order of rotate and translate, so it is on screen.
        Matrix4 mat4;
        mat4.translate(50, 50);
        damageAccumulator.pushTransform(&mat4);

        SkiaDisplayList skiaDL;
        VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
        dirtyVD.mutateProperties()->setBounds(bounds);
        SkMatrix rotate;
        rotate.setRotate(180);
        skiaDL.appendVD(&dirtyVD, rotate);

        ASSERT_TRUE(dirtyVD.isDirty());
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());

        TestUtils::MockTreeObserver observer;
        ASSERT_TRUE(skiaDL.prepareListAndChildren(
                observer, info, false, [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
        ASSERT_TRUE(dirtyVD.getPropertyChangeWillBeConsumed());
        damageAccumulator.popTransform();
    }
    {
        // An AVD that is larger than the screen.
        SkiaDisplayList skiaDL;
        VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
        dirtyVD.mutateProperties()->setBounds(SkRect::MakeLTRB(-1, -1, width + 1, height + 1));
        skiaDL.appendVD(&dirtyVD);

        ASSERT_TRUE(dirtyVD.isDirty());
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());

        TestUtils::MockTreeObserver observer;
        ASSERT_TRUE(skiaDL.prepareListAndChildren(
                observer, info, false, [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
        ASSERT_TRUE(dirtyVD.getPropertyChangeWillBeConsumed());
    }
    {
        // An AVD whose bounds are not a rectangle after applying a matrix.
        SkiaDisplayList skiaDL;
        VectorDrawableRoot dirtyVD(new VectorDrawable::Group());
        dirtyVD.mutateProperties()->setBounds(bounds);
        SkMatrix mat;
        mat.setRotate(45, 50, 50);
        skiaDL.appendVD(&dirtyVD, mat);

        ASSERT_TRUE(dirtyVD.isDirty());
        ASSERT_FALSE(dirtyVD.getPropertyChangeWillBeConsumed());

        TestUtils::MockTreeObserver observer;
        ASSERT_TRUE(skiaDL.prepareListAndChildren(
                observer, info, false, [](RenderNode*, TreeObserver&, TreeInfo&, bool) {}));
        ASSERT_TRUE(dirtyVD.getPropertyChangeWillBeConsumed());
    }
}

TEST(SkiaDisplayList, updateChildren) {
    SkiaDisplayList skiaDL;

    sp<RenderNode> renderNode = new RenderNode();
    SkCanvas dummyCanvas;
    skiaDL.mChildNodes.emplace_back(renderNode.get(), &dummyCanvas);
    skiaDL.updateChildren([renderNode](RenderNode* n) { ASSERT_EQ(renderNode.get(), n); });
}
