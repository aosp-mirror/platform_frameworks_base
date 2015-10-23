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

#include "Matrix.h"
#include "Rect.h"
#include "Vector.h"
#include "VertexBuffer.h"
#include "TessellationCache.h"
#include "microbench/MicroBench.h"

#include <SkPath.h>

#include <memory>

using namespace android;
using namespace android::uirenderer;

struct ShadowTestData {
    Matrix4 drawTransform;
    Rect localClip;
    Matrix4 casterTransformXY;
    Matrix4 casterTransformZ;
    Vector3 lightCenter;
    float lightRadius;
};

void createShadowTestData(ShadowTestData* out) {
    static float SAMPLE_DRAW_TRANSFORM[] = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1,
    };
    static float SAMPLE_CASTERXY[] = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            32, 32, 0, 1,
    };
    static float SAMPLE_CASTERZ[] = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            32, 32, 32, 1,
    };
    static Rect SAMPLE_CLIP(0, 0, 1536, 2048);
    static Vector3 SAMPLE_LIGHT_CENTER{768, -400, 1600};
    static float SAMPLE_LIGHT_RADIUS = 1600;

    out->drawTransform.load(SAMPLE_DRAW_TRANSFORM);
    out->localClip = SAMPLE_CLIP;
    out->casterTransformXY.load(SAMPLE_CASTERXY);
    out->casterTransformZ.load(SAMPLE_CASTERZ);
    out->lightCenter = SAMPLE_LIGHT_CENTER;
    out->lightRadius = SAMPLE_LIGHT_RADIUS;
}

static inline void tessellateShadows(ShadowTestData& testData, bool opaque,
        const SkPath& shape, VertexBuffer* ambient, VertexBuffer* spot) {
    tessellateShadows(&testData.drawTransform, &testData.localClip,
            opaque, &shape, &testData.casterTransformXY,
            &testData.casterTransformZ, testData.lightCenter,
            testData.lightRadius, *ambient, *spot);
}

BENCHMARK_NO_ARG(BM_TessellateShadows_roundrect_opaque);
void BM_TessellateShadows_roundrect_opaque::Run(int iters) {
    ShadowTestData shadowData;
    createShadowTestData(&shadowData);
    SkPath path;
    path.reset();
    path.addRoundRect(SkRect::MakeLTRB(0, 0, 100, 100), 5, 5);

    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        std::unique_ptr<VertexBuffer> ambient(new VertexBuffer);
        std::unique_ptr<VertexBuffer> spot(new VertexBuffer);
        tessellateShadows(shadowData, true, path, ambient.get(), spot.get());
        MicroBench::DoNotOptimize(ambient.get());
        MicroBench::DoNotOptimize(spot.get());
    }
    StopBenchmarkTiming();
}

BENCHMARK_NO_ARG(BM_TessellateShadows_roundrect_translucent);
void BM_TessellateShadows_roundrect_translucent::Run(int iters) {
    ShadowTestData shadowData;
    createShadowTestData(&shadowData);
    SkPath path;
    path.reset();
    path.addRoundRect(SkRect::MakeLTRB(0, 0, 100, 100), 5, 5);

    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        std::unique_ptr<VertexBuffer> ambient(new VertexBuffer);
        std::unique_ptr<VertexBuffer> spot(new VertexBuffer);
        tessellateShadows(shadowData, false, path, ambient.get(), spot.get());
        MicroBench::DoNotOptimize(ambient.get());
        MicroBench::DoNotOptimize(spot.get());
    }
    StopBenchmarkTiming();
}
