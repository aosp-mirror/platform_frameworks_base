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

#include <benchmark/Benchmark.h>

#include "BakedOpState.h"
#include "BakedOpDispatcher.h"
#include "BakedOpRenderer.h"
#include "LayerUpdateQueue.h"
#include "OpReorderer.h"
#include "RecordedOp.h"
#include "RecordingCanvas.h"
#include "tests/common/TestUtils.h"
#include "Vector.h"
#include "tests/microbench/MicroBench.h"

#include <vector>

using namespace android;
using namespace android::uirenderer;

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

BENCHMARK_NO_ARG(BM_OpReorderer_defer);
void BM_OpReorderer_defer::Run(int iters) {
    auto nodes = createTestNodeList();
    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        OpReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 200), 100, 200,
                nodes, sLightCenter);
        MicroBench::DoNotOptimize(&reorderer);
    }
    StopBenchmarkTiming();
}

BENCHMARK_NO_ARG(BM_OpReorderer_deferAndRender);
void BM_OpReorderer_deferAndRender::Run(int iters) {
    TestUtils::runOnRenderThread([this, iters](renderthread::RenderThread& thread) {
        auto nodes = createTestNodeList();
        BakedOpRenderer::LightInfo lightInfo = {50.0f, 128, 128 };

        RenderState& renderState = thread.renderState();
        Caches& caches = Caches::getInstance();

        StartBenchmarkTiming();
        for (int i = 0; i < iters; i++) {
            OpReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 200), 100, 200,
                    nodes, sLightCenter);

            BakedOpRenderer renderer(caches, renderState, true, lightInfo);
            reorderer.replayBakedOps<BakedOpDispatcher>(renderer);
            MicroBench::DoNotOptimize(&renderer);
        }
        StopBenchmarkTiming();
    });
}
