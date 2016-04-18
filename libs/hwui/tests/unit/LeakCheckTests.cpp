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

#include "BakedOpRenderer.h"
#include "BakedOpDispatcher.h"
#include "FrameBuilder.h"
#include "LayerUpdateQueue.h"
#include "RecordingCanvas.h"
#include "tests/common/TestUtils.h"

#include <gtest/gtest.h>

using namespace android;
using namespace android::uirenderer;

const FrameBuilder::LightGeometry sLightGeometery = { {100, 100, 100}, 50};
const BakedOpRenderer::LightInfo sLightInfo = { 128, 128 };

RENDERTHREAD_TEST(LeakCheck, saveLayer_overdrawRejection) {
    auto node = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(0, 0, 100, 100, 128, SaveFlags::ClipToLayer);
        canvas.drawRect(0, 0, 100, 100, SkPaint());
        canvas.restore();

        // opaque draw, rejects saveLayer beneath
        canvas.drawRect(0, 0, 100, 100, SkPaint());
    });
    RenderState& renderState = renderThread.renderState();
    Caches& caches = Caches::getInstance();

    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 100, 100,
            sLightGeometery, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));
    BakedOpRenderer renderer(caches, renderState, true, sLightInfo);
    frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
}

RENDERTHREAD_TEST(LeakCheck, saveLayerUnclipped_simple) {
    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, (SaveFlags::Flags)(0));
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.restore();
    });
    RenderState& renderState = renderThread.renderState();
    Caches& caches = Caches::getInstance();

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometery, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));
    BakedOpRenderer renderer(caches, renderState, true, sLightInfo);
    frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
}
