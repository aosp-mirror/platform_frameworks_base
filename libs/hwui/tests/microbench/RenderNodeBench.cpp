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

#include "hwui/Canvas.h"
#include "RenderNode.h"

using namespace android;
using namespace android::uirenderer;

void BM_RenderNode_create(benchmark::State& state) {
    while (state.KeepRunning()) {
        auto node = new RenderNode();
        node->incStrong(0);
        benchmark::DoNotOptimize(node);
        node->decStrong(0);
    }
}
BENCHMARK(BM_RenderNode_create);

void BM_RenderNode_recordSimple(benchmark::State& state) {
    sp<RenderNode> node = new RenderNode();
    std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(100, 100));
    canvas->finishRecording(node.get());

    while (state.KeepRunning()) {
        canvas->resetRecording(100, 100, node.get());
        canvas->drawColor(0x00000000, SkBlendMode::kSrcOver);
        canvas->finishRecording(node.get());
    }
}
BENCHMARK(BM_RenderNode_recordSimple);
