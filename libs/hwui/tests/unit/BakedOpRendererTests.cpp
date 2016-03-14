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

#include <gtest/gtest.h>

#include <BakedOpRenderer.h>
#include <tests/common/TestUtils.h>

using namespace android::uirenderer;

const BakedOpRenderer::LightInfo sLightInfo = { 128, 128 };

RENDERTHREAD_TEST(BakedOpRenderer, startRepaintLayer_clear) {
    BakedOpRenderer renderer(Caches::getInstance(), renderThread.renderState(), true, sLightInfo);
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 200u, 200u);

    layer.dirty(Rect(200, 200));
    {
        renderer.startRepaintLayer(&layer, Rect(200, 200));
        EXPECT_TRUE(layer.region.isEmpty()) << "Repaint full layer should clear region";
        renderer.endLayer();
    }

    layer.dirty(Rect(200, 200));
    {
        renderer.startRepaintLayer(&layer, Rect(100, 200)); // repainting left side
        EXPECT_TRUE(layer.region.isRect());
        //ALOGD("bounds %d %d %d %d", RECT_ARGS(layer.region.getBounds()));
        EXPECT_EQ(android::Rect(100, 0, 200, 200), layer.region.getBounds())
                << "Left side being repainted, so right side should be clear";
        renderer.endLayer();
    }

    // right side is now only dirty portion
    {
        renderer.startRepaintLayer(&layer, Rect(100, 0, 200, 200)); // repainting right side
        EXPECT_TRUE(layer.region.isEmpty())
                << "Now right side being repainted, so region should be entirely clear";
        renderer.endLayer();
    }
}
