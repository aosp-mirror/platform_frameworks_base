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

#include <benchmark/benchmark.h>

#include "DisplayList.h"
#include "hwui/Canvas.h"
#include "hwui/Paint.h"
#include "pipeline/skia/SkiaDisplayList.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::skiapipeline;

void BM_SkiaDisplayList_alloc(benchmark::State& benchState) {
    while (benchState.KeepRunning()) {
        auto displayList = new skiapipeline::SkiaDisplayList();
        benchmark::DoNotOptimize(displayList);
        delete displayList;
    }
}
BENCHMARK(BM_SkiaDisplayList_alloc);

void BM_SkiaDisplayList_alloc_theoretical(benchmark::State& benchState) {
    while (benchState.KeepRunning()) {
        auto displayList = new char[sizeof(skiapipeline::SkiaDisplayList)];
        benchmark::DoNotOptimize(displayList);
        delete[] displayList;
    }
}
BENCHMARK(BM_SkiaDisplayList_alloc_theoretical);

void BM_SkiaDisplayListCanvas_record_empty(benchmark::State& benchState) {
    auto canvas = std::make_unique<SkiaRecordingCanvas>(nullptr, 100, 100);
    static_cast<void>(canvas->finishRecording());

    while (benchState.KeepRunning()) {
        canvas->resetRecording(100, 100);
        benchmark::DoNotOptimize(canvas.get());
        static_cast<void>(canvas->finishRecording());
    }
}
BENCHMARK(BM_SkiaDisplayListCanvas_record_empty);

void BM_SkiaDisplayListCanvas_record_saverestore(benchmark::State& benchState) {
    auto canvas = std::make_unique<SkiaRecordingCanvas>(nullptr, 100, 100);
    static_cast<void>(canvas->finishRecording());

    while (benchState.KeepRunning()) {
        canvas->resetRecording(100, 100);
        canvas->save(SaveFlags::MatrixClip);
        canvas->save(SaveFlags::MatrixClip);
        benchmark::DoNotOptimize(canvas.get());
        canvas->restore();
        canvas->restore();
        static_cast<void>(canvas->finishRecording());
    }
}
BENCHMARK(BM_SkiaDisplayListCanvas_record_saverestore);

void BM_SkiaDisplayListCanvas_record_translate(benchmark::State& benchState) {
    auto canvas = std::make_unique<SkiaRecordingCanvas>(nullptr, 100, 100);
    static_cast<void>(canvas->finishRecording());

    while (benchState.KeepRunning()) {
        canvas->resetRecording(100, 100);
        canvas->scale(10, 10);
        benchmark::DoNotOptimize(canvas.get());
        static_cast<void>(canvas->finishRecording());
    }
}
BENCHMARK(BM_SkiaDisplayListCanvas_record_translate);

/**
 * Simulate a simple view drawing a background, overlapped by an image.
 *
 * Note that the recording commands are intentionally not perfectly efficient, as the
 * View system frequently produces unneeded save/restores.
 */
void BM_SkiaDisplayListCanvas_record_simpleBitmapView(benchmark::State& benchState) {
    auto canvas = std::make_unique<SkiaRecordingCanvas>(nullptr, 100, 100);
    static_cast<void>(canvas->finishRecording());

    Paint rectPaint;
    sk_sp<Bitmap> iconBitmap(TestUtils::createBitmap(80, 80));

    while (benchState.KeepRunning()) {
        canvas->resetRecording(100, 100);
        {
            canvas->save(SaveFlags::MatrixClip);
            canvas->drawRect(0, 0, 100, 100, rectPaint);
            canvas->restore();
        }
        {
            canvas->save(SaveFlags::MatrixClip);
            canvas->translate(10, 10);
            canvas->drawBitmap(*iconBitmap, 0, 0, nullptr);
            canvas->restore();
        }
        benchmark::DoNotOptimize(canvas.get());
        static_cast<void>(canvas->finishRecording());
    }
}
BENCHMARK(BM_SkiaDisplayListCanvas_record_simpleBitmapView);

void BM_SkiaDisplayListCanvas_basicViewGroupDraw(benchmark::State& benchState) {
    sp<RenderNode> child = TestUtils::createNode(50, 50, 100, 100, [](auto& props, auto& canvas) {
        canvas.drawColor(0xFFFFFFFF, SkBlendMode::kSrcOver);
    });

    auto canvas = std::make_unique<SkiaRecordingCanvas>(nullptr, 100, 100);
    static_cast<void>(canvas->finishRecording());

    while (benchState.KeepRunning()) {
        canvas->resetRecording(200, 200);
        canvas->translate(0, 0);  // mScrollX, mScrollY

        // Clip to padding
        // Can expect ~25% of views to have clip to padding with a non-null padding
        int clipRestoreCount = canvas->save(SaveFlags::MatrixClip);
        canvas->clipRect(1, 1, 199, 199, SkClipOp::kIntersect);

        canvas->enableZ(true);

        // Draw child loop
        for (int i = 0; i < benchState.range(0); i++) {
            canvas->drawRenderNode(child.get());
        }

        canvas->enableZ(false);
        canvas->restoreToCount(clipRestoreCount);

        static_cast<void>(canvas->finishRecording());
    }
}
BENCHMARK(BM_SkiaDisplayListCanvas_basicViewGroupDraw)->Arg(1)->Arg(5)->Arg(10);
