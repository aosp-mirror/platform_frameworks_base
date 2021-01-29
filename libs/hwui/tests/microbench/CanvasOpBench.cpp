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
#include "hwui/Paint.h"
#include "canvas/CanvasOpBuffer.h"
#include "canvas/CanvasFrontend.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;

void BM_CanvasOpBuffer_alloc(benchmark::State& benchState) {
    while (benchState.KeepRunning()) {
        auto displayList = new CanvasOpBuffer();
        benchmark::DoNotOptimize(displayList);
        delete displayList;
    }
}
BENCHMARK(BM_CanvasOpBuffer_alloc);

void BM_CanvasOpBuffer_record_saverestore(benchmark::State& benchState) {
    CanvasFrontend<CanvasOpBuffer> canvas(100, 100);
    while (benchState.KeepRunning()) {
        canvas.reset(100, 100);
        canvas.save(SaveFlags::MatrixClip);
        canvas.save(SaveFlags::MatrixClip);
        benchmark::DoNotOptimize(&canvas);
        canvas.restore();
        canvas.restore();
        canvas.finish();
    }
}
BENCHMARK(BM_CanvasOpBuffer_record_saverestore);

void BM_CanvasOpBuffer_record_saverestoreWithReuse(benchmark::State& benchState) {
    CanvasFrontend<CanvasOpBuffer> canvas(100, 100);

    while (benchState.KeepRunning()) {
        canvas.reset(100, 100);
        canvas.save(SaveFlags::MatrixClip);
        canvas.save(SaveFlags::MatrixClip);
        benchmark::DoNotOptimize(&canvas);
        canvas.restore();
        canvas.restore();
    }
}
BENCHMARK(BM_CanvasOpBuffer_record_saverestoreWithReuse);

void BM_CanvasOpBuffer_record_simpleBitmapView(benchmark::State& benchState) {
    CanvasFrontend<CanvasOpBuffer> canvas(100, 100);

    Paint rectPaint;
    sk_sp<Bitmap> iconBitmap(TestUtils::createBitmap(80, 80));

    while (benchState.KeepRunning()) {
        canvas.reset(100, 100);
        {
            canvas.save(SaveFlags::MatrixClip);
            canvas.draw(CanvasOp<CanvasOpType::DrawRect> {
                    .rect = SkRect::MakeWH(100, 100),
                    .paint = rectPaint,
            });
            canvas.restore();
        }
        {
            canvas.save(SaveFlags::MatrixClip);
            canvas.translate(10, 10);
            canvas.draw(CanvasOp<CanvasOpType::DrawImage> {
                    iconBitmap,
                    0,
                    0,
                    SkFilterMode::kNearest,
                    SkPaint{}
            });
            canvas.restore();
        }
        benchmark::DoNotOptimize(&canvas);
        canvas.finish();
    }
}
BENCHMARK(BM_CanvasOpBuffer_record_simpleBitmapView);
