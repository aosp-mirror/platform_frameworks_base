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
#include "BakedOpRenderer.h"
#include "OpReorderer.h"
#include "RecordedOp.h"
#include "RecordingCanvas.h"
#include "unit_tests/TestUtils.h"
#include "microbench/MicroBench.h"

#include <vector>

using namespace android;
using namespace android::uirenderer;

auto sReorderingDisplayList = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
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

BENCHMARK_NO_ARG(BM_OpReorderer_defer);
void BM_OpReorderer_defer::Run(int iters) {
    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        OpReorderer reorderer;
        reorderer.defer(200, 200, *sReorderingDisplayList);
        MicroBench::DoNotOptimize(&reorderer);
    }
    StopBenchmarkTiming();
}

BENCHMARK_NO_ARG(BM_OpReorderer_deferAndRender);
void BM_OpReorderer_deferAndRender::Run(int iters) {
    TestUtils::runOnRenderThread([this, iters](RenderState& renderState, Caches& caches) {
        StartBenchmarkTiming();
        for (int i = 0; i < iters; i++) {
            OpReorderer reorderer;
            reorderer.defer(200, 200, *sReorderingDisplayList);
            MicroBench::DoNotOptimize(&reorderer);

            BakedOpRenderer::Info info(caches, renderState, 200, 200, true);
            reorderer.replayBakedOps<BakedOpRenderer>(info);
        }
        StopBenchmarkTiming();
    });
}
