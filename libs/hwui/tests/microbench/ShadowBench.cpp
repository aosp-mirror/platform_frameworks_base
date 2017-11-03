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

#include "Matrix.h"
#include "Rect.h"
#include "TessellationCache.h"
#include "Vector.h"
#include "VertexBuffer.h"

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
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
    };
    static float SAMPLE_CASTERXY[] = {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 32, 32, 0, 1,
    };
    static float SAMPLE_CASTERZ[] = {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 32, 32, 32, 1,
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

static inline void tessellateShadows(ShadowTestData& testData, bool opaque, const SkPath& shape,
                                     VertexBuffer* ambient, VertexBuffer* spot) {
    tessellateShadows(&testData.drawTransform, &testData.localClip, opaque, &shape,
                      &testData.casterTransformXY, &testData.casterTransformZ, testData.lightCenter,
                      testData.lightRadius, *ambient, *spot);
}

void BM_TessellateShadows_roundrect_opaque(benchmark::State& state) {
    ShadowTestData shadowData;
    createShadowTestData(&shadowData);
    SkPath path;
    path.addRoundRect(SkRect::MakeWH(100, 100), 5, 5);

    while (state.KeepRunning()) {
        VertexBuffer ambient;
        VertexBuffer spot;
        tessellateShadows(shadowData, true, path, &ambient, &spot);
        benchmark::DoNotOptimize(&ambient);
        benchmark::DoNotOptimize(&spot);
    }
}
BENCHMARK(BM_TessellateShadows_roundrect_opaque);

void BM_TessellateShadows_roundrect_translucent(benchmark::State& state) {
    ShadowTestData shadowData;
    createShadowTestData(&shadowData);
    SkPath path;
    path.reset();
    path.addRoundRect(SkRect::MakeLTRB(0, 0, 100, 100), 5, 5);

    while (state.KeepRunning()) {
        std::unique_ptr<VertexBuffer> ambient(new VertexBuffer);
        std::unique_ptr<VertexBuffer> spot(new VertexBuffer);
        tessellateShadows(shadowData, false, path, ambient.get(), spot.get());
        benchmark::DoNotOptimize(ambient.get());
        benchmark::DoNotOptimize(spot.get());
    }
}
BENCHMARK(BM_TessellateShadows_roundrect_translucent);
