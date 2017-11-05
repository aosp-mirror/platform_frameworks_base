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
#include <GlopBuilder.h>
#include <tests/common/TestUtils.h>

using namespace android::uirenderer;

const BakedOpRenderer::LightInfo sLightInfo = {128, 128};

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpRenderer, startRepaintLayer_clear) {
    BakedOpRenderer renderer(Caches::getInstance(), renderThread.renderState(), true, false,
                             sLightInfo);
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 200u, 200u);

    layer.dirty(Rect(200, 200));
    {
        renderer.startRepaintLayer(&layer, Rect(200, 200));
        EXPECT_TRUE(layer.region.isEmpty()) << "Repaint full layer should clear region";
        renderer.endLayer();
    }

    layer.dirty(Rect(200, 200));
    {
        renderer.startRepaintLayer(&layer, Rect(100, 200));  // repainting left side
        EXPECT_TRUE(layer.region.isRect());
        // ALOGD("bounds %d %d %d %d", RECT_ARGS(layer.region.getBounds()));
        EXPECT_EQ(android::Rect(100, 0, 200, 200), layer.region.getBounds())
                << "Left side being repainted, so right side should be clear";
        renderer.endLayer();
    }

    // right side is now only dirty portion
    {
        renderer.startRepaintLayer(&layer, Rect(100, 0, 200, 200));  // repainting right side
        EXPECT_TRUE(layer.region.isEmpty())
                << "Now right side being repainted, so region should be entirely clear";
        renderer.endLayer();
    }
}

static void drawFirstOp(RenderState& renderState, int color, SkBlendMode mode) {
    BakedOpRenderer renderer(Caches::getInstance(), renderState, true, false, sLightInfo);

    renderer.startFrame(100, 100, Rect(100, 100));
    SkPaint paint;
    paint.setColor(color);
    paint.setBlendMode(mode);

    Rect dest(0, 0, 100, 100);
    Glop glop;
    GlopBuilder(renderState, Caches::getInstance(), &glop)
            .setRoundRectClipState(nullptr)
            .setMeshUnitQuad()
            .setFillPaint(paint, 1.0f)
            .setTransform(Matrix4::identity(), TransformFlags::None)
            .setModelViewMapUnitToRectSnap(dest)
            .build();
    renderer.renderGlop(nullptr, nullptr, glop);
    renderer.endFrame(Rect(100, 100));
}

static void verifyBlend(RenderState& renderState, GLenum expectedSrc, GLenum expectedDst) {
    EXPECT_TRUE(renderState.blend().getEnabled());
    GLenum src;
    GLenum dst;
    renderState.blend().getFactors(&src, &dst);
    EXPECT_EQ(expectedSrc, src);
    EXPECT_EQ(expectedDst, dst);
}

static void verifyBlendDisabled(RenderState& renderState) {
    EXPECT_FALSE(renderState.blend().getEnabled());
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpRenderer, firstDrawBlend_clear) {
    // initialize blend state to nonsense value
    renderThread.renderState().blend().setFactors(GL_ONE, GL_ONE);

    drawFirstOp(renderThread.renderState(), 0xfeff0000, SkBlendMode::kClear);
    verifyBlend(renderThread.renderState(), GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpRenderer, firstDrawBlend_srcover) {
    // initialize blend state to nonsense value
    renderThread.renderState().blend().setFactors(GL_ONE, GL_ONE);

    drawFirstOp(renderThread.renderState(), 0xfeff0000, SkBlendMode::kSrcOver);
    verifyBlendDisabled(renderThread.renderState());
}
