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

#include <benchmark/benchmark.h>

#include "BakedOpState.h"
#include "BakedOpDispatcher.h"
#include "BakedOpRenderer.h"
#include "FrameBuilder.h"
#include "LayerUpdateQueue.h"
#include "RecordedOp.h"
#include "RecordingCanvas.h"
#include "tests/common/TestContext.h"
#include "tests/common/TestScene.h"
#include "tests/common/TestUtils.h"
#include "Vector.h"

#include <vector>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::test;

const FrameBuilder::LightGeometry sLightGeometry = { {100, 100, 100}, 50};
const BakedOpRenderer::LightInfo sLightInfo = { 128, 128 };

static sp<RenderNode> createTestNode() {
    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(10, 10);
        SkPaint paint;

        // Alternate between drawing rects and bitmaps, with bitmaps overlapping rects.
        // Rects don't overlap bitmaps, so bitmaps should be brought to front as a group.
        canvas.save(SaveFlags::MatrixClip);
        for (int i = 0; i < 30; i++) {
            canvas.translate(0, 10);
            canvas.drawRect(0, 0, 10, 10, paint);
            canvas.drawBitmap(bitmap, 5, 0, nullptr);
        }
        canvas.restore();
    });
    TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    return node;
}

void BM_FrameBuilder_defer(benchmark::State& state) {
    TestUtils::runOnRenderThread([&state](RenderThread& thread) {
        auto node = createTestNode();
        while (state.KeepRunning()) {
            FrameBuilder frameBuilder(SkRect::MakeWH(100, 200), 100, 200,
                    sLightGeometry, Caches::getInstance());
            frameBuilder.deferRenderNode(*node);
            benchmark::DoNotOptimize(&frameBuilder);
        }
    });
}
BENCHMARK(BM_FrameBuilder_defer);

void BM_FrameBuilder_deferAndRender(benchmark::State& state) {
    TestUtils::runOnRenderThread([&state](RenderThread& thread) {
        auto node = createTestNode();

        RenderState& renderState = thread.renderState();
        Caches& caches = Caches::getInstance();

        while (state.KeepRunning()) {
            FrameBuilder frameBuilder(SkRect::MakeWH(100, 200), 100, 200,
                    sLightGeometry, caches);
            frameBuilder.deferRenderNode(*node);

            BakedOpRenderer renderer(caches, renderState, true, sLightInfo);
            frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
            benchmark::DoNotOptimize(&renderer);
        }
    });
}
BENCHMARK(BM_FrameBuilder_deferAndRender);

static sp<RenderNode> getSyncedSceneNode(const char* sceneName) {
    gDisplay = getBuiltInDisplay(); // switch to real display if present

    TestContext testContext;
    TestScene::Options opts;
    std::unique_ptr<TestScene> scene(TestScene::testMap()[sceneName].createScene(opts));

    sp<RenderNode> rootNode = TestUtils::createNode(0, 0, gDisplay.w, gDisplay.h,
                [&scene](RenderProperties& props, TestCanvas& canvas) {
            scene->createContent(gDisplay.w, gDisplay.h, canvas);
    });

    TestUtils::syncHierarchyPropertiesAndDisplayList(rootNode);
    return rootNode;
}

static auto SCENES = {
        "listview",
};

void BM_FrameBuilder_defer_scene(benchmark::State& state) {
    TestUtils::runOnRenderThread([&state](RenderThread& thread) {
        const char* sceneName = *(SCENES.begin() + state.range_x());
        state.SetLabel(sceneName);
        auto node = getSyncedSceneNode(sceneName);
        while (state.KeepRunning()) {
            FrameBuilder frameBuilder(SkRect::MakeWH(gDisplay.w, gDisplay.h),
                    gDisplay.w, gDisplay.h,
                    sLightGeometry, Caches::getInstance());
            frameBuilder.deferRenderNode(*node);
            benchmark::DoNotOptimize(&frameBuilder);
        }
    });
}
BENCHMARK(BM_FrameBuilder_defer_scene)->DenseRange(0, SCENES.size() - 1);

void BM_FrameBuilder_deferAndRender_scene(benchmark::State& state) {
    TestUtils::runOnRenderThread([&state](RenderThread& thread) {
        const char* sceneName = *(SCENES.begin() + state.range_x());
        state.SetLabel(sceneName);
        auto node = getSyncedSceneNode(sceneName);

        RenderState& renderState = thread.renderState();
        Caches& caches = Caches::getInstance();

        while (state.KeepRunning()) {
            FrameBuilder frameBuilder(SkRect::MakeWH(gDisplay.w, gDisplay.h),
                    gDisplay.w, gDisplay.h,
                    sLightGeometry, Caches::getInstance());
            frameBuilder.deferRenderNode(*node);

            BakedOpRenderer renderer(caches, renderState, true, sLightInfo);
            frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
            benchmark::DoNotOptimize(&renderer);
        }
    });
}
BENCHMARK(BM_FrameBuilder_deferAndRender_scene)->DenseRange(0, SCENES.size() - 1);
