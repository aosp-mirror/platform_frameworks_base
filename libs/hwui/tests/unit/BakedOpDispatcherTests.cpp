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

#include <RecordedOp.h>
#include <BakedOpDispatcher.h>
#include <BakedOpRenderer.h>
#include <tests/common/TestUtils.h>

using namespace android::uirenderer;

static BakedOpRenderer::LightInfo sLightInfo;
static Rect sBaseClip(100, 100);

class ValidatingBakedOpRenderer : public BakedOpRenderer {
public:
    ValidatingBakedOpRenderer(RenderState& renderState, std::function<void(const Glop& glop)> validator)
            : BakedOpRenderer(Caches::getInstance(), renderState, true, sLightInfo)
            , mValidator(validator) {
        mGlopReceiver = ValidatingGlopReceiver;
    }
private:
    static void ValidatingGlopReceiver(BakedOpRenderer& renderer, const Rect* dirtyBounds,
            const ClipBase* clip, const Glop& glop) {

        auto vbor = reinterpret_cast<ValidatingBakedOpRenderer*>(&renderer);
        vbor->mValidator(glop);
    }
    std::function<void(const Glop& glop)> mValidator;
};

typedef void (*BakedOpReceiver)(BakedOpRenderer&, const BakedOpState&);

static void testUnmergedGlopDispatch(renderthread::RenderThread& renderThread, RecordedOp* op,
        std::function<void(const Glop& glop)> glopVerifier) {
    // Create op, and wrap with basic state.
    LinearAllocator allocator;
    auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), sBaseClip);
    auto state = BakedOpState::tryConstruct(allocator, *snapshot, *op);
    ASSERT_NE(nullptr, state);

    int glopCount = 0;
    auto glopReceiver = [&glopVerifier, &glopCount] (const Glop& glop) {
        ASSERT_EQ(glopCount++, 0) << "Only one Glop expected";
        glopVerifier(glop);
    };
    ValidatingBakedOpRenderer renderer(renderThread.renderState(), glopReceiver);

    // Dispatch based on op type created, similar to Frame/LayerBuilder dispatch behavior
#define X(Type) \
        [](BakedOpRenderer& renderer, const BakedOpState& state) { \
            BakedOpDispatcher::on##Type(renderer, static_cast<const Type&>(*(state.op)), state); \
        },
    static BakedOpReceiver unmergedReceivers[] = BUILD_RENDERABLE_OP_LUT(X);
#undef X
    unmergedReceivers[op->opId](renderer, *state);
    ASSERT_EQ(1, glopCount) << "Exactly one Glop expected";
}

RENDERTHREAD_TEST(BakedOpDispatcher, onArc_position) {
    SkPaint strokePaint;
    strokePaint.setStyle(SkPaint::kStroke_Style);
    strokePaint.setStrokeWidth(4);
    ArcOp op(Rect(10, 15, 20, 25), Matrix4::identity(), nullptr, &strokePaint, 0, 270, true);
    testUnmergedGlopDispatch(renderThread, &op, [] (const Glop& glop) {
        // validate glop produced by renderPathTexture (so texture, unit quad)
        auto texture = glop.fill.texture.texture;
        ASSERT_NE(nullptr, texture);
        float expectedOffset = floor(4 * 1.5f + 0.5f);
        EXPECT_EQ(expectedOffset, reinterpret_cast<PathTexture*>(texture)->offset)
                << "Should see conservative offset from PathCache::computeBounds";
        Rect expectedBounds(10, 15, 20, 25);
        expectedBounds.outset(expectedOffset);
#if !HWUI_NEW_OPS
        EXPECT_EQ(expectedBounds, glop.bounds) << "bounds outset by stroke 'offset'";
#endif
        Matrix4 expectedModelView;
        expectedModelView.loadTranslate(10 - expectedOffset, 15 - expectedOffset, 0);
        expectedModelView.scale(10 + 2 * expectedOffset, 10 + 2 * expectedOffset, 1);
        EXPECT_EQ(expectedModelView, glop.transform.modelView)
                << "X and Y offsets, and scale both applied to model view";
    });
}

RENDERTHREAD_TEST(BakedOpDispatcher, onLayerOp_bufferless) {
    SkPaint layerPaint;
    layerPaint.setAlpha(128);
    OffscreenBuffer* buffer = nullptr; // no providing a buffer, should hit rect fallback case
    LayerOp op(Rect(10, 10), Matrix4::identity(), nullptr, &layerPaint, &buffer);
    testUnmergedGlopDispatch(renderThread, &op, [&renderThread] (const Glop& glop) {
        // rect glop is dispatched with paint props applied
        EXPECT_EQ(renderThread.renderState().meshState().getUnitQuadVBO(),
                glop.mesh.vertices.bufferObject) << "Unit quad should be drawn";
        EXPECT_EQ(nullptr, glop.fill.texture.texture) << "Should be no texture when layer is null";
        EXPECT_FLOAT_EQ(128 / 255.0f, glop.fill.color.a) << "Rect quad should use op alpha";
    });
}

static int getGlopTransformFlags(renderthread::RenderThread& renderThread, RecordedOp* op) {
    int result = 0;
    testUnmergedGlopDispatch(renderThread, op, [&result] (const Glop& glop) {
        result = glop.transform.transformFlags;
    });
    return result;
}

RENDERTHREAD_TEST(BakedOpDispatcher, offsetFlags) {
    Rect bounds(10, 15, 20, 25);
    SkPaint paint;
    SkPaint aaPaint;
    aaPaint.setAntiAlias(true);

    RoundRectOp roundRectOp(bounds, Matrix4::identity(), nullptr, &paint, 0, 270);
    EXPECT_EQ(TransformFlags::None, getGlopTransformFlags(renderThread, &roundRectOp))
            << "Expect no offset for round rect op.";

    const float points[4] = {0.5, 0.5, 1.0, 1.0};
    PointsOp antiAliasedPointsOp(bounds, Matrix4::identity(), nullptr, &aaPaint, points, 4);
    EXPECT_EQ(TransformFlags::None, getGlopTransformFlags(renderThread, &antiAliasedPointsOp))
                << "Expect no offset for AA points.";
    PointsOp pointsOp(bounds, Matrix4::identity(), nullptr, &paint, points, 4);
    EXPECT_EQ(TransformFlags::OffsetByFudgeFactor, getGlopTransformFlags(renderThread, &pointsOp))
            << "Expect an offset for non-AA points.";

    LinesOp antiAliasedLinesOp(bounds, Matrix4::identity(), nullptr, &aaPaint, points, 4);
    EXPECT_EQ(TransformFlags::None, getGlopTransformFlags(renderThread, &antiAliasedLinesOp))
            << "Expect no offset for AA lines.";
    LinesOp linesOp(bounds, Matrix4::identity(), nullptr, &paint, points, 4);
    EXPECT_EQ(TransformFlags::OffsetByFudgeFactor, getGlopTransformFlags(renderThread, &linesOp))
            << "Expect an offset for non-AA lines.";
}