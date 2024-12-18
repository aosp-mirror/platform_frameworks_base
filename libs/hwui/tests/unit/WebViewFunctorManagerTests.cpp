/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "WebViewFunctorManager.h"
#include "private/hwui/WebViewFunctor.h"
#include "renderthread/RenderProxy.h"
#include "tests/common/TestUtils.h"

#include <unordered_map>

using namespace android;
using namespace android::uirenderer;

#define ASSUME_GLES()                                                      \
    if (WebViewFunctor_queryPlatformRenderMode() != RenderMode::OpenGL_ES) \
    GTEST_SKIP() << "Not in GLES, skipping test"

TEST(WebViewFunctor, createDestroyGLES) {
    ASSUME_GLES();
    int functor = WebViewFunctor_create(
            nullptr, TestUtils::createMockFunctorCallbacks(RenderMode::OpenGL_ES),
            RenderMode::OpenGL_ES);
    ASSERT_NE(-1, functor);
    WebViewFunctor_release(functor);
    TestUtils::runOnRenderThreadUnmanaged([](renderthread::RenderThread&) {
        // Empty, don't care
    });
    auto counts = TestUtils::copyCountsForFunctor(functor);
    // We never initialized, so contextDestroyed == 0
    EXPECT_EQ(0, counts.contextDestroyed);
    EXPECT_EQ(1, counts.destroyed);
}

TEST(WebViewFunctor, createSyncHandleGLES) {
    ASSUME_GLES();
    int functor = WebViewFunctor_create(
            nullptr, TestUtils::createMockFunctorCallbacks(RenderMode::OpenGL_ES),
            RenderMode::OpenGL_ES);
    ASSERT_NE(-1, functor);
    auto handle = WebViewFunctorManager::instance().handleFor(functor);
    ASSERT_TRUE(handle);
    WebViewFunctor_release(functor);
    EXPECT_FALSE(WebViewFunctorManager::instance().handleFor(functor));
    TestUtils::runOnRenderThreadUnmanaged([](renderthread::RenderThread&) {
        // fence
    });
    auto counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(0, counts.sync);
    EXPECT_EQ(0, counts.contextDestroyed);
    EXPECT_EQ(0, counts.destroyed);

    TestUtils::runOnRenderThreadUnmanaged([&](auto&) {
        WebViewSyncData syncData;
        handle->sync(syncData);
    });

    counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(1, counts.sync);

    TestUtils::runOnRenderThreadUnmanaged([&](auto&) {
        WebViewSyncData syncData;
        handle->sync(syncData);
    });

    counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(2, counts.sync);

    handle.clear();

    TestUtils::runOnRenderThreadUnmanaged([](renderthread::RenderThread&) {
        // fence
    });

    counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(2, counts.sync);
    EXPECT_EQ(0, counts.contextDestroyed);
    EXPECT_EQ(1, counts.destroyed);
}

TEST(WebViewFunctor, createSyncDrawGLES) {
    ASSUME_GLES();
    int functor = WebViewFunctor_create(
            nullptr, TestUtils::createMockFunctorCallbacks(RenderMode::OpenGL_ES),
            RenderMode::OpenGL_ES);
    ASSERT_NE(-1, functor);
    auto handle = WebViewFunctorManager::instance().handleFor(functor);
    ASSERT_TRUE(handle);
    WebViewFunctor_release(functor);
    for (int i = 0; i < 5; i++) {
        TestUtils::runOnRenderThreadUnmanaged([&](auto&) {
            WebViewSyncData syncData;
            handle->sync(syncData);
            DrawGlInfo drawInfo;
            handle->drawGl(drawInfo);
            handle->drawGl(drawInfo);
        });
    }
    handle.clear();
    TestUtils::runOnRenderThreadUnmanaged([](renderthread::RenderThread&) {
        // fence
    });
    auto counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(5, counts.sync);
    EXPECT_EQ(10, counts.glesDraw);
    EXPECT_EQ(1, counts.contextDestroyed);
    EXPECT_EQ(1, counts.destroyed);
}

TEST(WebViewFunctor, contextDestroyedGLES) {
    ASSUME_GLES();
    int functor = WebViewFunctor_create(
            nullptr, TestUtils::createMockFunctorCallbacks(RenderMode::OpenGL_ES),
            RenderMode::OpenGL_ES);
    ASSERT_NE(-1, functor);
    auto handle = WebViewFunctorManager::instance().handleFor(functor);
    ASSERT_TRUE(handle);
    WebViewFunctor_release(functor);
    TestUtils::runOnRenderThreadUnmanaged([&](auto&) {
        WebViewSyncData syncData;
        handle->sync(syncData);
        DrawGlInfo drawInfo;
        handle->drawGl(drawInfo);
    });
    auto counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(1, counts.sync);
    EXPECT_EQ(1, counts.glesDraw);
    EXPECT_EQ(0, counts.contextDestroyed);
    EXPECT_EQ(0, counts.destroyed);
    TestUtils::runOnRenderThreadUnmanaged([](auto& rt) {
        rt.destroyRenderingContext();
    });
    counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(1, counts.sync);
    EXPECT_EQ(1, counts.glesDraw);
    EXPECT_EQ(1, counts.contextDestroyed);
    EXPECT_EQ(0, counts.destroyed);
    TestUtils::runOnRenderThreadUnmanaged([&](auto&) {
        WebViewSyncData syncData;
        handle->sync(syncData);
        DrawGlInfo drawInfo;
        handle->drawGl(drawInfo);
    });
    counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(2, counts.sync);
    EXPECT_EQ(2, counts.glesDraw);
    EXPECT_EQ(1, counts.contextDestroyed);
    EXPECT_EQ(0, counts.destroyed);
    handle.clear();
    TestUtils::runOnRenderThreadUnmanaged([](renderthread::RenderThread&) {
        // fence
    });
    counts = TestUtils::copyCountsForFunctor(functor);
    EXPECT_EQ(2, counts.sync);
    EXPECT_EQ(2, counts.glesDraw);
    EXPECT_EQ(2, counts.contextDestroyed);
    EXPECT_EQ(1, counts.destroyed);
}
