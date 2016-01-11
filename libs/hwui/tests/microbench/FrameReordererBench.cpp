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

#include <benchmark/Benchmark.h>

#include "BakedOpState.h"
#include "BakedOpDispatcher.h"
#include "BakedOpRenderer.h"
#include "FrameReorderer.h"
#include "LayerUpdateQueue.h"
#include "RecordedOp.h"
#include "RecordingCanvas.h"
#include "tests/common/TestContext.h"
#include "tests/common/TestScene.h"
#include "tests/common/TestUtils.h"
#include "Vector.h"
#include "tests/microbench/MicroBench.h"

#include <vector>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::test;

const LayerUpdateQueue sEmptyLayerUpdateQueue;
const Vector3 sLightCenter = {100, 100, 100};

static std::vector<sp<RenderNode>> createTestNodeList() {
    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(10, 10);
        SkPaint paint;

        // Alternate between drawing rects and bitmaps, with bitmaps overlapping rects.
        // Rects don't overlap bitmaps, so bitmaps should be brought to front as a group.
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        for (int i = 0; i < 30; i++) {
            canvas.translate(0, 10);
            canvas.drawRect(0, 0, 10, 10, paint);
            canvas.drawBitmap(bitmap, 5, 0, nullptr);
        }
        canvas.restore();
    });
    TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    std::vector<sp<RenderNode>> vec;
    vec.emplace_back(node);
    return vec;
}

BENCHMARK_NO_ARG(BM_FrameBuilder_defer);
void BM_FrameBuilder_defer::Run(int iters) {
    auto nodes = createTestNodeList();
    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        FrameReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 200), 100, 200,
                nodes, sLightCenter);
        MicroBench::DoNotOptimize(&reorderer);
    }
    StopBenchmarkTiming();
}

BENCHMARK_NO_ARG(BM_FrameBuilder_deferAndRender);
void BM_FrameBuilder_deferAndRender::Run(int iters) {
    TestUtils::runOnRenderThread([this, iters](RenderThread& thread) {
        auto nodes = createTestNodeList();
        BakedOpRenderer::LightInfo lightInfo = {50.0f, 128, 128 };

        RenderState& renderState = thread.renderState();
        Caches& caches = Caches::getInstance();

        StartBenchmarkTiming();
        for (int i = 0; i < iters; i++) {
            FrameReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 200), 100, 200,
                    nodes, sLightCenter);

            BakedOpRenderer renderer(caches, renderState, true, lightInfo);
            reorderer.replayBakedOps<BakedOpDispatcher>(renderer);
            MicroBench::DoNotOptimize(&renderer);
        }
        StopBenchmarkTiming();
    });
}

static std::vector<sp<RenderNode>> getSyncedSceneNodes(const char* sceneName) {
    gDisplay = getBuiltInDisplay(); // switch to real display if present

    TestContext testContext;
    TestScene::Options opts;
    std::unique_ptr<TestScene> scene(TestScene::testMap()[sceneName].createScene(opts));

    sp<RenderNode> rootNode = TestUtils::createNode(0, 0, gDisplay.w, gDisplay.h,
                [&scene](RenderProperties& props, TestCanvas& canvas) {
            scene->createContent(gDisplay.w, gDisplay.h, canvas);
    });

    TestUtils::syncHierarchyPropertiesAndDisplayList(rootNode);
    std::vector<sp<RenderNode>> nodes;
    nodes.emplace_back(rootNode);
    return nodes;
}

static void benchDeferScene(testing::Benchmark& benchmark, int iters, const char* sceneName) {
    auto nodes = getSyncedSceneNodes(sceneName);
    benchmark.StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        FrameReorderer reorderer(sEmptyLayerUpdateQueue,
                SkRect::MakeWH(gDisplay.w, gDisplay.h), gDisplay.w, gDisplay.h,
                nodes, sLightCenter);
        MicroBench::DoNotOptimize(&reorderer);
    }
    benchmark.StopBenchmarkTiming();
}

static void benchDeferAndRenderScene(testing::Benchmark& benchmark,
        int iters, const char* sceneName) {
    TestUtils::runOnRenderThread([&benchmark, iters, sceneName](RenderThread& thread) {
        auto nodes = getSyncedSceneNodes(sceneName);
        BakedOpRenderer::LightInfo lightInfo = {50.0f, 128, 128 }; // TODO!

        RenderState& renderState = thread.renderState();
        Caches& caches = Caches::getInstance();

        benchmark.StartBenchmarkTiming();
        for (int i = 0; i < iters; i++) {
            FrameReorderer reorderer(sEmptyLayerUpdateQueue,
                    SkRect::MakeWH(gDisplay.w, gDisplay.h), gDisplay.w, gDisplay.h,
                    nodes, sLightCenter);

            BakedOpRenderer renderer(caches, renderState, true, lightInfo);
            reorderer.replayBakedOps<BakedOpDispatcher>(renderer);
            MicroBench::DoNotOptimize(&renderer);
        }
        benchmark.StopBenchmarkTiming();
    });
}

BENCHMARK_NO_ARG(BM_FrameBuilder_listview_defer);
void BM_FrameBuilder_listview_defer::Run(int iters) {
    benchDeferScene(*this, iters, "listview");
}

BENCHMARK_NO_ARG(BM_FrameBuilder_listview_deferAndRender);
void BM_FrameBuilder_listview_deferAndRender::Run(int iters) {
    benchDeferAndRenderScene(*this, iters, "listview");
}

