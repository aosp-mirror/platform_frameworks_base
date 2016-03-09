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
#if HWUI_NEW_OPS
#include "RecordingCanvas.h"
#else
#include "DisplayListCanvas.h"
#endif
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;

#if HWUI_NEW_OPS
typedef RecordingCanvas TestCanvas;
#else
typedef DisplayListCanvas TestCanvas;
#endif

void BM_DisplayList_alloc(benchmark::State& benchState) {
    while (benchState.KeepRunning()) {
        auto displayList = new DisplayList();
        benchmark::DoNotOptimize(displayList);
        delete displayList;
    }
}
BENCHMARK(BM_DisplayList_alloc);

void BM_DisplayList_alloc_theoretical(benchmark::State& benchState) {
    while (benchState.KeepRunning()) {
        auto displayList = new char[sizeof(DisplayList)];
        benchmark::DoNotOptimize(displayList);
        delete[] displayList;
    }
}
BENCHMARK(BM_DisplayList_alloc_theoretical);

void BM_DisplayListCanvas_record_empty(benchmark::State& benchState) {
    TestCanvas canvas(100, 100);
    delete canvas.finishRecording();

    while (benchState.KeepRunning()) {
        canvas.resetRecording(100, 100);
        benchmark::DoNotOptimize(&canvas);
        delete canvas.finishRecording();
    }
}
BENCHMARK(BM_DisplayListCanvas_record_empty);

void BM_DisplayListCanvas_record_saverestore(benchmark::State& benchState) {
    TestCanvas canvas(100, 100);
    delete canvas.finishRecording();

    while (benchState.KeepRunning()) {
        canvas.resetRecording(100, 100);
        canvas.save(SaveFlags::MatrixClip);
        canvas.save(SaveFlags::MatrixClip);
        benchmark::DoNotOptimize(&canvas);
        canvas.restore();
        canvas.restore();
        delete canvas.finishRecording();
    }
}
BENCHMARK(BM_DisplayListCanvas_record_saverestore);

void BM_DisplayListCanvas_record_translate(benchmark::State& benchState) {
    TestCanvas canvas(100, 100);
    delete canvas.finishRecording();

    while (benchState.KeepRunning()) {
        canvas.resetRecording(100, 100);
        canvas.scale(10, 10);
        benchmark::DoNotOptimize(&canvas);
        delete canvas.finishRecording();
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
    TestCanvas canvas(100, 100);
    delete canvas.finishRecording();

    SkPaint rectPaint;
    SkBitmap iconBitmap = TestUtils::createSkBitmap(80, 80);

    while (benchState.KeepRunning()) {
        canvas.resetRecording(100, 100);
        {
            canvas.save(SaveFlags::MatrixClip);
            canvas.drawRect(0, 0, 100, 100, rectPaint);
            canvas.restore();
        }
        {
            canvas.save(SaveFlags::MatrixClip);
            canvas.translate(10, 10);
            canvas.drawBitmap(iconBitmap, 0, 0, nullptr);
            canvas.restore();
        }
        benchmark::DoNotOptimize(&canvas);
        delete canvas.finishRecording();
    }
}
BENCHMARK(BM_DisplayListCanvas_record_simpleBitmapView);

class NullClient: public CanvasStateClient {
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
