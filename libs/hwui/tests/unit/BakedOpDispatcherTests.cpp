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

#include <BakedOpDispatcher.h>
#include <BakedOpRenderer.h>
#include <FrameBuilder.h>
#include <LayerUpdateQueue.h>
#include <RecordedOp.h>
#include <hwui/Paint.h>
#include <tests/common/TestUtils.h>
#include <utils/Color.h>

#include <SkBlurDrawLooper.h>
#include <SkDashPathEffect.h>
#include <SkPath.h>

using namespace android::uirenderer;

static BakedOpRenderer::LightInfo sLightInfo;
const FrameBuilder::LightGeometry sLightGeometry = {{100, 100, 100}, 50};

class ValidatingBakedOpRenderer : public BakedOpRenderer {
public:
    ValidatingBakedOpRenderer(RenderState& renderState,
                              std::function<void(const Glop& glop)> validator)
            : BakedOpRenderer(Caches::getInstance(), renderState, true, false, sLightInfo)
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

typedef void (*TestBakedOpReceiver)(BakedOpRenderer&, const BakedOpState&);

static void testUnmergedGlopDispatch(renderthread::RenderThread& renderThread, RecordedOp* op,
                                     std::function<void(const Glop& glop)> glopVerifier,
                                     int expectedGlopCount = 1) {
    // Create op, and wrap with basic state.
    LinearAllocator allocator;
    auto snapshot = TestUtils::makeSnapshot(Matrix4::identity(), Rect(100, 100));
    auto state = BakedOpState::tryConstruct(allocator, *snapshot, *op);
    ASSERT_NE(nullptr, state);

    int glopCount = 0;
    auto glopReceiver = [&glopVerifier, &glopCount, &expectedGlopCount](const Glop& glop) {
        ASSERT_LE(glopCount++, expectedGlopCount) << expectedGlopCount << "glop(s) expected";
        glopVerifier(glop);
    };
    ValidatingBakedOpRenderer renderer(renderThread.renderState(), glopReceiver);

// Dispatch based on op type created, similar to Frame/LayerBuilder dispatch behavior
#define X(Type)                                                                              \
    [](BakedOpRenderer& renderer, const BakedOpState& state) {                               \
        BakedOpDispatcher::on##Type(renderer, static_cast<const Type&>(*(state.op)), state); \
    },
    static TestBakedOpReceiver unmergedReceivers[] = BUILD_RENDERABLE_OP_LUT(X);
#undef X
    unmergedReceivers[op->opId](renderer, *state);
    ASSERT_EQ(expectedGlopCount, glopCount) << "Exactly " << expectedGlopCount
                                            << "Glop(s) expected";
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpDispatcher, pathTexture_positionOvalArc) {
    SkPaint strokePaint;
    strokePaint.setStyle(SkPaint::kStroke_Style);
    strokePaint.setStrokeWidth(4);

    float intervals[] = {1.0f, 1.0f};
    strokePaint.setPathEffect(SkDashPathEffect::Make(intervals, 2, 0));

    auto textureGlopVerifier = [](const Glop& glop) {
        // validate glop produced by renderPathTexture (so texture, unit quad)
        auto texture = glop.fill.texture.texture;
        ASSERT_NE(nullptr, texture);
        float expectedOffset = floor(4 * 1.5f + 0.5f);
        EXPECT_EQ(expectedOffset, reinterpret_cast<PathTexture*>(texture)->offset)
                << "Should see conservative offset from PathCache::computeBounds";
        Rect expectedBounds(10, 15, 20, 25);
        expectedBounds.outset(expectedOffset);

        Matrix4 expectedModelView;
        expectedModelView.loadTranslate(10 - expectedOffset, 15 - expectedOffset, 0);
        expectedModelView.scale(10 + 2 * expectedOffset, 10 + 2 * expectedOffset, 1);
        EXPECT_EQ(expectedModelView, glop.transform.modelView)
                << "X and Y offsets, and scale both applied to model view";
    };

    // Arc and Oval will render functionally the same glop, differing only in texture content
    ArcOp arcOp(Rect(10, 15, 20, 25), Matrix4::identity(), nullptr, &strokePaint, 0, 270, true);
    testUnmergedGlopDispatch(renderThread, &arcOp, textureGlopVerifier);

    OvalOp ovalOp(Rect(10, 15, 20, 25), Matrix4::identity(), nullptr, &strokePaint);
    testUnmergedGlopDispatch(renderThread, &ovalOp, textureGlopVerifier);
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpDispatcher, onLayerOp_bufferless) {
    SkPaint layerPaint;
    layerPaint.setAlpha(128);
    OffscreenBuffer* buffer = nullptr;  // no providing a buffer, should hit rect fallback case
    LayerOp op(Rect(10, 10), Matrix4::identity(), nullptr, &layerPaint, &buffer);
    testUnmergedGlopDispatch(renderThread, &op,
                             [](const Glop& glop) { ADD_FAILURE() << "Nothing should happen"; }, 0);
}

static int getGlopTransformFlags(renderthread::RenderThread& renderThread, RecordedOp* op) {
    int result = 0;
    testUnmergedGlopDispatch(renderThread, op, [&result](const Glop& glop) {
        result = glop.transform.transformFlags;
    });
    return result;
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpDispatcher, offsetFlags) {
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

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpDispatcher, renderTextWithShadow) {
    auto node = TestUtils::createNode<RecordingCanvas>(
            0, 0, 100, 100, [](RenderProperties& props, RecordingCanvas& canvas) {

                android::Paint shadowPaint;
                shadowPaint.setColor(SK_ColorRED);

                SkScalar sigma = Blur::convertRadiusToSigma(5);
                shadowPaint.setLooper(SkBlurDrawLooper::Make(SK_ColorWHITE, sigma, 3, 3));

                TestUtils::drawUtf8ToCanvas(&canvas, "A", shadowPaint, 25, 25);
                TestUtils::drawUtf8ToCanvas(&canvas, "B", shadowPaint, 50, 50);
            });

    int glopCount = 0;
    auto glopReceiver = [&glopCount](const Glop& glop) {
        if (glopCount < 2) {
            // two white shadows
            EXPECT_EQ(FloatColor({1, 1, 1, 1}), glop.fill.color);
        } else {
            // two text draws merged into one, drawn after both shadows
            EXPECT_EQ(FloatColor({1, 0, 0, 1}), glop.fill.color);
        }
        glopCount++;
    };

    ValidatingBakedOpRenderer renderer(renderThread.renderState(), glopReceiver);

    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 100, 100, sLightGeometry,
                              Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
    ASSERT_EQ(3, glopCount) << "Exactly three glops expected";
}

static void validateLayerDraw(renderthread::RenderThread& renderThread,
                              std::function<void(const Glop& glop)> validator) {
    auto node = TestUtils::createNode<RecordingCanvas>(
            0, 0, 100, 100, [](RenderProperties& props, RecordingCanvas& canvas) {
                props.mutateLayerProperties().setType(LayerType::RenderLayer);

                // provide different blend mode, so decoration draws contrast
                props.mutateLayerProperties().setXferMode(SkBlendMode::kSrc);
                canvas.drawColor(Color::Black, SkBlendMode::kSrcOver);
            });
    OffscreenBuffer** layerHandle = node->getLayerHandle();

    auto syncedNode = TestUtils::getSyncedNode(node);

    // create RenderNode's layer here in same way prepareTree would
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    *layerHandle = &layer;
    {
        LayerUpdateQueue layerUpdateQueue;  // Note: enqueue damage post-sync, so bounds are valid
        layerUpdateQueue.enqueueLayerWithDamage(node.get(), Rect(0, 0, 100, 100));

        ValidatingBakedOpRenderer renderer(renderThread.renderState(), validator);
        FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 100, 100, sLightGeometry,
                                  Caches::getInstance());
        frameBuilder.deferLayers(layerUpdateQueue);
        frameBuilder.deferRenderNode(*syncedNode);
        frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
    }

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

static FloatColor makeFloatColor(uint32_t color) {
    FloatColor c;
    c.set(color);
    return c;
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpDispatcher, layerUpdateProperties) {
    for (bool debugOverdraw : {false, true}) {
        for (bool debugLayersUpdates : {false, true}) {
            ScopedProperty<bool> ovdProp(Properties::debugOverdraw, debugOverdraw);
            ScopedProperty<bool> lupProp(Properties::debugLayersUpdates, debugLayersUpdates);

            int glopCount = 0;
            validateLayerDraw(renderThread, [&glopCount, &debugLayersUpdates](const Glop& glop) {
                if (glopCount == 0) {
                    // 0 - Black layer fill
                    EXPECT_TRUE(glop.fill.colorEnabled);
                    EXPECT_EQ(makeFloatColor(Color::Black), glop.fill.color);
                } else if (glopCount == 1) {
                    // 1 - Uncolored (textured) layer draw
                    EXPECT_FALSE(glop.fill.colorEnabled);
                } else if (glopCount == 2) {
                    // 2 - layer overlay, if present
                    EXPECT_TRUE(glop.fill.colorEnabled);
                    // blend srcover, different from that of layer
                    EXPECT_EQ(GLenum(GL_ONE), glop.blend.src);
                    EXPECT_EQ(GLenum(GL_ONE_MINUS_SRC_ALPHA), glop.blend.dst);
                    EXPECT_EQ(makeFloatColor(debugLayersUpdates ? 0x7f00ff00 : 0), glop.fill.color)
                            << "Should be transparent green if debugLayersUpdates";
                } else if (glopCount < 7) {
                    // 3 - 6 - overdraw indicator overlays, if present
                    EXPECT_TRUE(glop.fill.colorEnabled);
                    uint32_t expectedColor = Caches::getInstance().getOverdrawColor(glopCount - 2);
                    ASSERT_EQ(makeFloatColor(expectedColor), glop.fill.color);
                } else {
                    ADD_FAILURE() << "Too many glops observed";
                }
                glopCount++;
            });
            int expectedCount = 2;
            if (debugLayersUpdates || debugOverdraw) expectedCount++;
            if (debugOverdraw) expectedCount += 4;
            EXPECT_EQ(expectedCount, glopCount);
        }
    }
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(BakedOpDispatcher, pathTextureSnapping) {
    Rect bounds(10, 15, 20, 25);
    SkPaint paint;
    SkPath path;
    path.addRect(SkRect::MakeXYWH(1.5, 3.8, 100, 90));
    PathOp op(bounds, Matrix4::identity(), nullptr, &paint, &path);
    testUnmergedGlopDispatch(renderThread, &op, [](const Glop& glop) {
        auto texture = glop.fill.texture.texture;
        ASSERT_NE(nullptr, texture);
        EXPECT_EQ(1, reinterpret_cast<PathTexture*>(texture)->left);
        EXPECT_EQ(3, reinterpret_cast<PathTexture*>(texture)->top);
    });
}
