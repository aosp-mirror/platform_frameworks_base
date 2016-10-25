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
#include "IContextFactory.h"
#include "renderthread/CanvasContext.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) override {
        return new AnimationContext(clock);
    }
};

RENDERTHREAD_TEST(CanvasContext, create) {
    auto rootNode = TestUtils::createNode(0, 0, 200, 400, nullptr);
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(CanvasContext::create(
            renderThread, false, rootNode.get(), &contextFactory));

    ASSERT_FALSE(canvasContext->hasSurface());

    canvasContext->destroy(nullptr);
}

class TestFunctor : public Functor {
public:
    bool didProcess = false;

    virtual status_t operator ()(int what, void* data) {
        if (what == DrawGlInfo::kModeProcess) { didProcess = true; }
        return DrawGlInfo::kStatusDone;
    }
};

RENDERTHREAD_TEST(CanvasContext, invokeFunctor) {
    TestFunctor functor;
    ASSERT_FALSE(functor.didProcess);
    CanvasContext::invokeFunctor(renderThread, &functor);
    ASSERT_TRUE(functor.didProcess);
}
