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

#include "CanvasState.h"
#include "DisplayList.h"
#include "hwui/Canvas.h"
#include "pipeline/skia/SkiaDisplayList.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;

void BM_DisplayList_alloc(benchmark::State& benchState) {
    while (benchState.KeepRunning()) {
        auto displayList = new skiapipeline::SkiaDisplayList();
        benchmark::DoNotOptimize(displayList);
        delete displayList;
    }
}
BENCHMARK(BM_DisplayList_alloc);

void BM_DisplayList_alloc_theoretical(benchmark::State& benchState) {
    while (benchState.KeepRunning()) {
        auto displayList = new char[sizeof(skiapipeline::SkiaDisplayList)];
        benchmark::DoNotOptimize(displayList);
        delete[] displayList;
    }
}
BENCHMARK(BM_DisplayList_alloc_theoretical);

void BM_DisplayListCanvas_record_empty(benchmark::State& benchState) {
    std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(100, 100));
    delete canvas->finishRecording();

    while (benchState.KeepRunning()) {
        canvas->resetRecording(100, 100);
        benchmark::DoNotOptimize(canvas.get());
        delete canvas->finishRecording();
    }
}
BENCHMARK(BM_DisplayListCanvas_record_empty);

void BM_DisplayListCanvas_record_saverestore(benchmark::State& benchState) {
    std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(100, 100));
    delete canvas->finishRecording();

    while (benchState.KeepRunning()) {
        canvas->resetRecording(100, 100);
        canvas->save(SaveFlags::MatrixClip);
        canvas->save(SaveFlags::MatrixClip);
        benchmark::DoNotOptimize(canvas.get());
        canvas->restore();
        canvas->restore();
        delete canvas->finishRecording();
    }
}
BENCHMARK(BM_DisplayListCanvas_record_saverestore);

void BM_DisplayListCanvas_record_translate(benchmark::State& benchState) {
    std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(100, 100));
    delete canvas->finishRecording();

    while (benchState.KeepRunning()) {
        canvas->resetRecording(100, 100);
        canvas->scale(10, 10);
        benchmark::DoNotOptimize(canvas.get());
        delete canvas->finishRecording();
    }
}
BENCHMARK(BM_DisplayListCanvas_record_translate);

/**
 * Simulate a simple view drawing a background, overlapped by an image.
 *
 * Note that the recording commands are intentionally not perfectly efficient, as the
 * View system frequently produces unneeded save/restores.
 */
void BM_DisplayListCanvas_record_simpleBitmapView(benchmark::State& benchState) {
    std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(100, 100));
    delete canvas->finishRecording();

    SkPaint rectPaint;
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
        delete canvas->finishRecording();
    }
}
BENCHMARK(BM_DisplayListCanvas_record_simpleBitmapView);

class NullClient : public CanvasStateClient {
    void onViewportInitialized() override {}
    void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {}
    GLuint getTargetFbo() const override { return 0; }
};

void BM_CanvasState_saverestore(benchmark::State& benchState) {
    NullClient client;
    CanvasState state(client);
    state.initializeSaveStack(100, 100, 0, 0, 100, 100, Vector3());

    while (benchState.KeepRunning()) {
        state.save(SaveFlags::MatrixClip);
        state.save(SaveFlags::MatrixClip);
        benchmark::DoNotOptimize(&state);
        state.restore();
        state.restore();
    }
}
BENCHMARK(BM_CanvasState_saverestore);

void BM_CanvasState_init(benchmark::State& benchState) {
    NullClient client;
    CanvasState state(client);
    state.initializeSaveStack(100, 100, 0, 0, 100, 100, Vector3());

    while (benchState.KeepRunning()) {
        state.initializeSaveStack(100, 100, 0, 0, 100, 100, Vector3());
        benchmark::DoNotOptimize(&state);
    }
}
BENCHMARK(BM_CanvasState_init);

void BM_CanvasState_translate(benchmark::State& benchState) {
    NullClient client;
    CanvasState state(client);
    state.initializeSaveStack(100, 100, 0, 0, 100, 100, Vector3());

    while (benchState.KeepRunning()) {
        state.translate(5, 5, 0);
        benchmark::DoNotOptimize(&state);
        state.translate(-5, -5, 0);
    }
}
BENCHMARK(BM_CanvasState_translate);

void BM_DisplayListCanvas_basicViewGroupDraw(benchmark::State& benchState) {
    sp<RenderNode> child = TestUtils::createNode(50, 50, 100, 100, [](auto& props, auto& canvas) {
        canvas.drawColor(0xFFFFFFFF, SkBlendMode::kSrcOver);
    });

    std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(100, 100));
    delete canvas->finishRecording();

    while (benchState.KeepRunning()) {
        canvas->resetRecording(200, 200);
        canvas->translate(0, 0);  // mScrollX, mScrollY

        // Clip to padding
        // Can expect ~25% of views to have clip to padding with a non-null padding
        int clipRestoreCount = canvas->save(SaveFlags::MatrixClip);
        canvas->clipRect(1, 1, 199, 199, SkClipOp::kIntersect);

        canvas->insertReorderBarrier(true);

        // Draw child loop
        for (int i = 0; i < benchState.range(0); i++) {
            canvas->drawRenderNode(child.get());
        }

        canvas->insertReorderBarrier(false);
        canvas->restoreToCount(clipRestoreCount);

        delete canvas->finishRecording();
    }
}
BENCHMARK(BM_DisplayListCanvas_basicViewGroupDraw)->Arg(1)->Arg(5)->Arg(10);
