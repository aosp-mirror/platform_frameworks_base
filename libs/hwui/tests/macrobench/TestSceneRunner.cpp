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

#include "AnimationContext.h"
#include "RenderNode.h"
#include "tests/common/TestContext.h"
#include "tests/common/TestScene.h"
#include "tests/common/scenes/TestSceneBase.h"
#include "renderthread/RenderProxy.h"
#include "renderthread/RenderTask.h"

#include <cutils/log.h>
#include <gui/Surface.h>
#include <ui/PixelFormat.h>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::test;

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) override {
        return new AnimationContext(clock);
    }
};

template<class T>
class ModifiedMovingAverage {
public:
    ModifiedMovingAverage(int weight) : mWeight(weight) {}

    T add(T today) {
        if (!mHasValue) {
            mAverage = today;
        } else {
            mAverage = (((mWeight - 1) * mAverage) + today) / mWeight;
        }
        return mAverage;
    }

    T average() {
        return mAverage;
    }

private:
    bool mHasValue = false;
    int mWeight;
    T mAverage;
};

void run(const TestScene::Info& info, const TestScene::Options& opts) {
    // Switch to the real display
    gDisplay = getBuiltInDisplay();

    std::unique_ptr<TestScene> scene(info.createScene(opts));

    TestContext testContext;

    // create the native surface
    const int width = gDisplay.w;
    const int height = gDisplay.h;
    sp<Surface> surface = testContext.surface();

    sp<RenderNode> rootNode = TestUtils::createNode(0, 0, width, height,
            [&scene, width, height](RenderProperties& props, TestCanvas& canvas) {
        props.setClipToBounds(false);
        scene->createContent(width, height, canvas);
    });

    ContextFactory factory;
    std::unique_ptr<RenderProxy> proxy(new RenderProxy(false,
            rootNode.get(), &factory));
    proxy->loadSystemProperties();
    proxy->initialize(surface);
    float lightX = width / 2.0;
    proxy->setup(width, height, dp(800.0f), 255 * 0.075, 255 * 0.15);
    proxy->setLightCenter((Vector3){lightX, dp(-200.0f), dp(800.0f)});

    // Do a few cold runs then reset the stats so that the caches are all hot
    for (int i = 0; i < 5; i++) {
        testContext.waitForVsync();
        nsecs_t vsync = systemTime(CLOCK_MONOTONIC);
        UiFrameInfoBuilder(proxy->frameInfo()).setVsync(vsync, vsync);
        proxy->syncAndDrawFrame(nullptr);
    }

    proxy->resetProfileInfo();
    proxy->fence();

    ModifiedMovingAverage<double> avgMs(opts.reportFrametimeWeight);

    for (int i = 0; i < opts.count; i++) {
        testContext.waitForVsync();
        nsecs_t vsync = systemTime(CLOCK_MONOTONIC);
        {
            ATRACE_NAME("UI-Draw Frame");
            UiFrameInfoBuilder(proxy->frameInfo()).setVsync(vsync, vsync);
            scene->doFrame(i);
            proxy->syncAndDrawFrame(nullptr);
        }
        if (opts.reportFrametimeWeight) {
            proxy->fence();
            nsecs_t done = systemTime(CLOCK_MONOTONIC);
            avgMs.add((done - vsync) / 1000000.0);
            if (i % 10 == 9) {
                printf("Average frametime %.3fms\n", avgMs.average());
            }
        }
    }

    proxy->dumpProfileInfo(STDOUT_FILENO, DumpFlags::JankStats);
}
