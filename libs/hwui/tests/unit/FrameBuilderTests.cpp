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

#include <BakedOpState.h>
#include <DeferredLayerUpdater.h>
#include <FrameBuilder.h>
#include <LayerUpdateQueue.h>
#include <RecordedOp.h>
#include <RecordingCanvas.h>
#include <tests/common/TestUtils.h>

#include <unordered_map>

namespace android {
namespace uirenderer {

const FrameBuilder::LightGeometry sLightGeometry = { {100, 100, 100}, 50};

/**
 * Virtual class implemented by each test to redirect static operation / state transitions to
 * virtual methods.
 *
 * Virtual dispatch allows for default behaviors to be specified (very common case in below tests),
 * and allows Renderer vs Dispatching behavior to be merged.
 *
 * onXXXOp methods fail by default - tests should override ops they expect
 * startRepaintLayer fails by default - tests should override if expected
 * startFrame/endFrame do nothing by default - tests should override to intercept
 */
class TestRendererBase {
public:
    virtual ~TestRendererBase() {}
    virtual OffscreenBuffer* startTemporaryLayer(uint32_t, uint32_t) {
        ADD_FAILURE() << "Temporary layers not expected in this test";
        return nullptr;
    }
    virtual void recycleTemporaryLayer(OffscreenBuffer*) {
        ADD_FAILURE() << "Temporary layers not expected in this test";
    }
    virtual void startRepaintLayer(OffscreenBuffer*, const Rect& repaintRect) {
        ADD_FAILURE() << "Layer repaint not expected in this test";
    }
    virtual void endLayer() {
        ADD_FAILURE() << "Layer updates not expected in this test";
    }
    virtual void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) {}
    virtual void endFrame(const Rect& repaintRect) {}

    // define virtual defaults for single draw methods
#define X(Type) \
    virtual void on##Type(const Type&, const BakedOpState&) { \
        ADD_FAILURE() << #Type " not expected in this test"; \
    }
    MAP_RENDERABLE_OPS(X)
#undef X

    // define virtual defaults for merged draw methods
#define X(Type) \
    virtual void onMerged##Type##s(const MergedBakedOpList& opList) { \
        ADD_FAILURE() << "Merged " #Type "s not expected in this test"; \
    }
    MAP_MERGEABLE_OPS(X)
#undef X

    int getIndex() { return mIndex; }

protected:
    int mIndex = 0;
};

/**
 * Dispatches all static methods to similar formed methods on renderer, which fail by default but
 * are overridden by subclasses per test.
 */
class TestDispatcher {
public:
    // define single op methods, which redirect to TestRendererBase
#define X(Type) \
    static void on##Type(TestRendererBase& renderer, const Type& op, const BakedOpState& state) { \
        renderer.on##Type(op, state); \
    }
    MAP_RENDERABLE_OPS(X);
#undef X

    // define merged op methods, which redirect to TestRendererBase
#define X(Type) \
    static void onMerged##Type##s(TestRendererBase& renderer, const MergedBakedOpList& opList) { \
        renderer.onMerged##Type##s(opList); \
    }
    MAP_MERGEABLE_OPS(X);
#undef X
};

class FailRenderer : public TestRendererBase {};

RENDERTHREAD_TEST(FrameBuilder, simple) {
    class SimpleTestRenderer : public TestRendererBase {
    public:
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(100u, width);
            EXPECT_EQ(200u, height);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
        }
        void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(3, mIndex++);
        }
    };

    auto node = TestUtils::createNode(0, 0, 100, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(25, 25);
        canvas.drawRect(0, 0, 100, 200, SkPaint());
        canvas.drawBitmap(bitmap, 10, 10, nullptr);
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(100, 200), 100, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SimpleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex()); // 2 ops + start + end
}

RENDERTHREAD_TEST(FrameBuilder, simpleStroke) {
    class SimpleStrokeTestRenderer : public TestRendererBase {
    public:
        void onPointsOp(const PointsOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            // even though initial bounds are empty...
            EXPECT_TRUE(op.unmappedBounds.isEmpty())
                    << "initial bounds should be empty, since they're unstroked";
            EXPECT_EQ(Rect(45, 45, 55, 55), state.computedState.clippedBounds)
                    << "final bounds should account for stroke";
        }
    };

    auto node = TestUtils::createNode(0, 0, 100, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint strokedPaint;
        strokedPaint.setStrokeWidth(10);
        canvas.drawPoint(50, 50, strokedPaint);
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(100, 200), 100, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SimpleStrokeTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, simpleRejection) {
    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.clipRect(200, 200, 400, 400, SkRegion::kIntersect_Op); // intersection should be empty
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    FailRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

RENDERTHREAD_TEST(FrameBuilder, simpleBatching) {
    const int LOOPS = 5;
    class SimpleBatchingTestRenderer : public TestRendererBase {
    public:
        void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
            EXPECT_TRUE(mIndex++ >= LOOPS) << "Bitmaps should be above all rects";
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_TRUE(mIndex++ < LOOPS) << "Rects should be below all bitmaps";
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(10, 10,
                kAlpha_8_SkColorType); // Disable merging by using alpha 8 bitmap

        // Alternate between drawing rects and bitmaps, with bitmaps overlapping rects.
        // Rects don't overlap bitmaps, so bitmaps should be brought to front as a group.
        canvas.save(SaveFlags::MatrixClip);
        for (int i = 0; i < LOOPS; i++) {
            canvas.translate(0, 10);
            canvas.drawRect(0, 0, 10, 10, SkPaint());
            canvas.drawBitmap(bitmap, 5, 0, nullptr);
        }
        canvas.restore();
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SimpleBatchingTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2 * LOOPS, renderer.getIndex())
            << "Expect number of ops = 2 * loop count";
}

RENDERTHREAD_TEST(FrameBuilder, deferRenderNode_translateClip) {
    class DeferRenderNodeTranslateClipTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(5, 10, 55, 60), state.computedState.clippedBounds);
            EXPECT_EQ(OpClipSideFlags::Right | OpClipSideFlags::Bottom,
                    state.computedState.clipSideFlags);
        }
    };

    auto node = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.drawRect(0, 0, 100, 100, SkPaint());
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 100, 100,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(5, 10, Rect(50, 50), // translate + clip node
            *TestUtils::getSyncedNode(node));

    DeferRenderNodeTranslateClipTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, deferRenderNodeScene) {
    class DeferRenderNodeSceneTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            const Rect& clippedBounds = state.computedState.clippedBounds;
            Matrix4 expected;
            switch (mIndex++) {
            case 0:
                // background - left side
                EXPECT_EQ(Rect(600, 100, 700, 500), clippedBounds);
                expected.loadTranslate(100, 100, 0);
                break;
            case 1:
                // background - top side
                EXPECT_EQ(Rect(100, 400, 600, 500), clippedBounds);
                expected.loadTranslate(100, 100, 0);
                break;
            case 2:
                // content
                EXPECT_EQ(Rect(100, 100, 700, 500), clippedBounds);
                expected.loadTranslate(-50, -50, 0);
                break;
            case 3:
                // overlay
                EXPECT_EQ(Rect(0, 0, 800, 200), clippedBounds);
                break;
            default:
                ADD_FAILURE() << "Too many rects observed";
            }
            EXPECT_EQ(expected, state.computedState.transform);
        }
    };

    std::vector<sp<RenderNode>> nodes;
    SkPaint transparentPaint;
    transparentPaint.setAlpha(128);

    // backdrop
    nodes.push_back(TestUtils::createNode(100, 100, 700, 500, // 600x400
            [&transparentPaint](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.drawRect(0, 0, 600, 400, transparentPaint);
    }));

    // content
    Rect contentDrawBounds(150, 150, 650, 450); // 500x300
    nodes.push_back(TestUtils::createNode(0, 0, 800, 600,
            [&transparentPaint](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.drawRect(0, 0, 800, 600, transparentPaint);
    }));

    // overlay
    nodes.push_back(TestUtils::createNode(0, 0, 800, 600,
            [&transparentPaint](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.drawRect(0, 0, 800, 200, transparentPaint);
    }));

    for (auto& node : nodes) {
        TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    }

    FrameBuilder frameBuilder(SkRect::MakeWH(800, 600), 800, 600,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNodeScene(nodes, contentDrawBounds);

    DeferRenderNodeSceneTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, empty_noFbo0) {
    class EmptyNoFbo0TestRenderer : public TestRendererBase {
    public:
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            ADD_FAILURE() << "Primary frame draw not expected in this test";
        }
        void endFrame(const Rect& repaintRect) override {
            ADD_FAILURE() << "Primary frame draw not expected in this test";
        }
    };

    // Use layer update constructor, so no work is enqueued for Fbo0
    LayerUpdateQueue emptyLayerUpdateQueue;
    FrameBuilder frameBuilder(emptyLayerUpdateQueue, sLightGeometry, Caches::getInstance());
    EmptyNoFbo0TestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

RENDERTHREAD_TEST(FrameBuilder, empty_withFbo0) {
    class EmptyWithFbo0TestRenderer : public TestRendererBase {
    public:
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(1, mIndex++);
        }
    };
    auto node = TestUtils::createNode(10, 10, 110, 110,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        // no drawn content
    });

    // Draw, but pass node without draw content, so no work is done for primary frame
    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    EmptyWithFbo0TestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex()) << "No drawing content produced,"
            " but fbo0 update lifecycle should still be observed";
}

RENDERTHREAD_TEST(FrameBuilder, avoidOverdraw_rects) {
    class AvoidOverdrawRectsTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(mIndex++, 0) << "Should be one rect";
            EXPECT_EQ(Rect(10, 10, 190, 190), op.unmappedBounds)
                    << "Last rect should occlude others.";
        }
    };
    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.drawRect(10, 10, 190, 190, SkPaint());
    });

    // Damage (and therefore clip) is same as last draw, subset of renderable area.
    // This means last op occludes other contents, and they'll be rejected to avoid overdraw.
    FrameBuilder frameBuilder(SkRect::MakeLTRB(10, 10, 190, 190), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    EXPECT_EQ(3u, node->getDisplayList()->getOps().size())
            << "Recording must not have rejected ops, in order for this test to be valid";

    AvoidOverdrawRectsTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex()) << "Expect exactly one op";
}

RENDERTHREAD_TEST(FrameBuilder, avoidOverdraw_bitmaps) {
    static SkBitmap opaqueBitmap = TestUtils::createSkBitmap(50, 50,
            SkColorType::kRGB_565_SkColorType);
    static SkBitmap transpBitmap = TestUtils::createSkBitmap(50, 50,
            SkColorType::kAlpha_8_SkColorType);
    class AvoidOverdrawBitmapsTestRenderer : public TestRendererBase {
    public:
        void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
            switch(mIndex++) {
            case 0:
                EXPECT_EQ(opaqueBitmap.pixelRef(), op.bitmap->pixelRef());
                break;
            case 1:
                EXPECT_EQ(transpBitmap.pixelRef(), op.bitmap->pixelRef());
                break;
            default:
                ADD_FAILURE() << "Only two ops expected.";
            }
        }
    };

    auto node = TestUtils::createNode(0, 0, 50, 50,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.drawRect(0, 0, 50, 50, SkPaint());
        canvas.drawRect(0, 0, 50, 50, SkPaint());
        canvas.drawBitmap(transpBitmap, 0, 0, nullptr);

        // only the below draws should remain, since they're
        canvas.drawBitmap(opaqueBitmap, 0, 0, nullptr);
        canvas.drawBitmap(transpBitmap, 0, 0, nullptr);
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(50, 50), 50, 50,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    EXPECT_EQ(5u, node->getDisplayList()->getOps().size())
            << "Recording must not have rejected ops, in order for this test to be valid";

    AvoidOverdrawBitmapsTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex()) << "Expect exactly two ops";
}

RENDERTHREAD_TEST(FrameBuilder, clippedMerging) {
    class ClippedMergingTestRenderer : public TestRendererBase {
    public:
        void onMergedBitmapOps(const MergedBakedOpList& opList) override {
            EXPECT_EQ(0, mIndex);
            mIndex += opList.count;
            EXPECT_EQ(4u, opList.count);
            EXPECT_EQ(Rect(10, 10, 90, 90), opList.clip);
            EXPECT_EQ(OpClipSideFlags::Left | OpClipSideFlags::Top | OpClipSideFlags::Right,
                    opList.clipSideFlags);
        }
    };
    auto node = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& props, TestCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(20, 20);

        // left side clipped (to inset left half)
        canvas.clipRect(10, 0, 50, 100, SkRegion::kReplace_Op);
        canvas.drawBitmap(bitmap, 0, 40, nullptr);

        // top side clipped (to inset top half)
        canvas.clipRect(0, 10, 100, 50, SkRegion::kReplace_Op);
        canvas.drawBitmap(bitmap, 40, 0, nullptr);

        // right side clipped (to inset right half)
        canvas.clipRect(50, 0, 90, 100, SkRegion::kReplace_Op);
        canvas.drawBitmap(bitmap, 80, 40, nullptr);

        // bottom not clipped, just abutting (inset bottom half)
        canvas.clipRect(0, 50, 100, 90, SkRegion::kReplace_Op);
        canvas.drawBitmap(bitmap, 40, 70, nullptr);
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 100, 100,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    ClippedMergingTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, textMerging) {
    class TextMergingTestRenderer : public TestRendererBase {
    public:
        void onMergedTextOps(const MergedBakedOpList& opList) override {
            EXPECT_EQ(0, mIndex);
            mIndex += opList.count;
            EXPECT_EQ(2u, opList.count);
            EXPECT_EQ(OpClipSideFlags::Top, opList.clipSideFlags);
            EXPECT_EQ(OpClipSideFlags::Top, opList.states[0]->computedState.clipSideFlags);
            EXPECT_EQ(OpClipSideFlags::None, opList.states[1]->computedState.clipSideFlags);
        }
    };
    auto node = TestUtils::createNode(0, 0, 400, 400,
            [](RenderProperties& props, TestCanvas& canvas) {
        SkPaint paint;
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        paint.setAntiAlias(true);
        paint.setTextSize(50);
        TestUtils::drawUtf8ToCanvas(&canvas, "Test string1", paint, 100, 0); // will be top clipped
        TestUtils::drawUtf8ToCanvas(&canvas, "Test string1", paint, 100, 100); // not clipped
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(400, 400), 400, 400,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    TextMergingTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex()) << "Expect 2 ops";
}

RENDERTHREAD_TEST(FrameBuilder, textStrikethrough) {
    const int LOOPS = 5;
    class TextStrikethroughTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_TRUE(mIndex++ >= LOOPS) << "Strikethrough rects should be above all text";
        }
        void onMergedTextOps(const MergedBakedOpList& opList) override {
            EXPECT_EQ(0, mIndex);
            mIndex += opList.count;
            EXPECT_EQ(5u, opList.count);
        }
    };
    auto node = TestUtils::createNode(0, 0, 200, 2000,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint textPaint;
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(20);
        textPaint.setStrikeThruText(true);
        textPaint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        for (int i = 0; i < LOOPS; i++) {
            TestUtils::drawUtf8ToCanvas(&canvas, "test text", textPaint, 10, 100 * (i + 1));
        }
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 2000), 200, 2000,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    TextStrikethroughTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2 * LOOPS, renderer.getIndex())
            << "Expect number of ops = 2 * loop count";
}

static auto styles = {
        SkPaint::kFill_Style, SkPaint::kStroke_Style, SkPaint::kStrokeAndFill_Style };

RENDERTHREAD_TEST(FrameBuilder, textStyle) {
    class TextStyleTestRenderer : public TestRendererBase {
    public:
        void onMergedTextOps(const MergedBakedOpList& opList) override {
            ASSERT_EQ(0, mIndex);
            ASSERT_EQ(3u, opList.count);
            mIndex += opList.count;

            int index = 0;
            for (auto style : styles) {
                auto state = opList.states[index++];
                ASSERT_EQ(style, state->op->paint->getStyle())
                        << "Remainder of validation relies upon stable merged order";
                ASSERT_EQ(0, state->computedState.clipSideFlags)
                        << "Clipped bounds validation requires unclipped ops";
            }

            Rect fill = opList.states[0]->computedState.clippedBounds;
            Rect stroke = opList.states[1]->computedState.clippedBounds;
            EXPECT_EQ(stroke, opList.states[2]->computedState.clippedBounds)
                    << "Stroke+Fill should be same as stroke";

            EXPECT_TRUE(stroke.contains(fill));
            EXPECT_FALSE(fill.contains(stroke));

            // outset by half the stroke width
            Rect outsetFill(fill);
            outsetFill.outset(5);
            EXPECT_EQ(stroke, outsetFill);
        }
    };
    auto node = TestUtils::createNode(0, 0, 400, 400,
            [](RenderProperties& props, TestCanvas& canvas) {
        SkPaint paint;
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        paint.setAntiAlias(true);
        paint.setTextSize(50);
        paint.setStrokeWidth(10);

        // draw 3 copies of the same text overlapping, each with a different style.
        // They'll get merged, but with
        for (auto style : styles) {
            paint.setStyle(style);
            TestUtils::drawUtf8ToCanvas(&canvas, "Test string1", paint, 100, 100);
        }
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(400, 400), 400, 400,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));
    TextStyleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(3, renderer.getIndex()) << "Expect 3 ops";
}

RENDERTHREAD_TEST(FrameBuilder, textureLayer_clipLocalMatrix) {
    class TextureLayerClipLocalMatrixTestRenderer : public TestRendererBase {
    public:
        void onTextureLayerOp(const TextureLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(50, 50, 150, 150), state.computedState.clipRect());
            EXPECT_EQ(Rect(50, 50, 105, 105), state.computedState.clippedBounds);

            Matrix4 expected;
            expected.loadTranslate(5, 5, 0);
            EXPECT_MATRIX_APPROX_EQ(expected, state.computedState.transform);
        }
    };

    auto layerUpdater = TestUtils::createTextureLayerUpdater(renderThread, 100, 100,
            SkMatrix::MakeTrans(5, 5));

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [&layerUpdater](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.clipRect(50, 50, 150, 150, SkRegion::kIntersect_Op);
        canvas.drawLayer(layerUpdater.get());
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    TextureLayerClipLocalMatrixTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, textureLayer_combineMatrices) {
    class TextureLayerCombineMatricesTestRenderer : public TestRendererBase {
    public:
        void onTextureLayerOp(const TextureLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);

            Matrix4 expected;
            expected.loadTranslate(35, 45, 0);
            EXPECT_MATRIX_APPROX_EQ(expected, state.computedState.transform);
        }
    };

    auto layerUpdater = TestUtils::createTextureLayerUpdater(renderThread, 100, 100,
            SkMatrix::MakeTrans(5, 5));

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [&layerUpdater](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.translate(30, 40);
        canvas.drawLayer(layerUpdater.get());
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    TextureLayerCombineMatricesTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, textureLayer_reject) {
    auto layerUpdater = TestUtils::createTextureLayerUpdater(renderThread, 100, 100,
            SkMatrix::MakeTrans(5, 5));
    layerUpdater->backingLayer()->setRenderTarget(GL_NONE); // Should be rejected

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [&layerUpdater](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.drawLayer(layerUpdater.get());
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    FailRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

RENDERTHREAD_TEST(FrameBuilder, functor_reject) {
    class FunctorTestRenderer : public TestRendererBase {
    public:
        void onFunctorOp(const FunctorOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
        }
    };
    Functor noopFunctor;

    // 1 million pixel tall view, scrolled down 80%
    auto scrolledFunctorView = TestUtils::createNode(0, 0, 400, 1000000,
            [&noopFunctor](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.translate(0, -800000);
        canvas.callDrawGLFunction(&noopFunctor, nullptr);
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(scrolledFunctorView));

    FunctorTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex()) << "Functor should not be rejected";
}

RENDERTHREAD_TEST(FrameBuilder, deferColorOp_unbounded) {
    class ColorTestRenderer : public TestRendererBase {
    public:
        void onColorOp(const ColorOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(200, 200), state.computedState.clippedBounds)
                    << "Color op should be expanded to bounds of surrounding";
        }
    };

    auto unclippedColorView = TestUtils::createNode(0, 0, 10, 10,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        props.setClipToBounds(false);
        canvas.drawColor(SK_ColorWHITE, SkXfermode::Mode::kSrcOver_Mode);
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(unclippedColorView));

    ColorTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex()) << "ColorOp should not be rejected";
}

TEST(FrameBuilder, renderNode) {
    class RenderNodeTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            switch(mIndex++) {
            case 0:
                EXPECT_EQ(Rect(200, 200), state.computedState.clippedBounds);
                EXPECT_EQ(SK_ColorDKGRAY, op.paint->getColor());
                break;
            case 1:
                EXPECT_EQ(Rect(50, 50, 150, 150), state.computedState.clippedBounds);
                EXPECT_EQ(SK_ColorWHITE, op.paint->getColor());
                break;
            default:
                ADD_FAILURE();
            }
        }
    };

    auto child = TestUtils::createNode(10, 10, 110, 110,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });

    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [&child](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorDKGRAY);
        canvas.drawRect(0, 0, 200, 200, paint);

        canvas.save(SaveFlags::MatrixClip);
        canvas.translate(40, 40);
        canvas.drawRenderNode(child.get());
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(parent));

    RenderNodeTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, clipped) {
    class ClippedTestRenderer : public TestRendererBase {
    public:
        void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clipRect());
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(200, 200);
        canvas.drawBitmap(bitmap, 0, 0, nullptr);
    });

    // clip to small area, should see in receiver
    FrameBuilder frameBuilder(SkRect::MakeLTRB(10, 20, 30, 40), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    ClippedTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

RENDERTHREAD_TEST(FrameBuilder, saveLayer_simple) {
    class SaveLayerSimpleTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(180u, width);
            EXPECT_EQ(180u, height);
            return nullptr;
        }
        void endLayer() override {
            EXPECT_EQ(2, mIndex++);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), op.unmappedBounds);
            EXPECT_EQ(Rect(180, 180), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(180, 180), state.computedState.clipRect());

            Matrix4 expectedTransform;
            expectedTransform.loadTranslate(-10, -10, 0);
            EXPECT_MATRIX_APPROX_EQ(expectedTransform, state.computedState.transform);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(200, 200), state.computedState.clipRect());
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
        void recycleTemporaryLayer(OffscreenBuffer* offscreenBuffer) override {
            EXPECT_EQ(4, mIndex++);
            EXPECT_EQ(nullptr, offscreenBuffer);
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, SaveFlags::ClipToLayer);
        canvas.drawRect(10, 10, 190, 190, SkPaint());
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SaveLayerSimpleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(5, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, saveLayer_nested) {
    /* saveLayer1 { rect1, saveLayer2 { rect2 } } will play back as:
     * - startTemporaryLayer2, rect2 endLayer2
     * - startTemporaryLayer1, rect1, drawLayer2, endLayer1
     * - startFrame, layerOp1, endFrame
     */
    class SaveLayerNestedTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            const int index = mIndex++;
            if (index == 0) {
                EXPECT_EQ(400u, width);
                EXPECT_EQ(400u, height);
                return (OffscreenBuffer*) 0x400;
            } else if (index == 3) {
                EXPECT_EQ(800u, width);
                EXPECT_EQ(800u, height);
                return (OffscreenBuffer*) 0x800;
            } else { ADD_FAILURE(); }
            return (OffscreenBuffer*) nullptr;
        }
        void endLayer() override {
            int index = mIndex++;
            EXPECT_TRUE(index == 2 || index == 6);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(7, mIndex++);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(9, mIndex++);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            const int index = mIndex++;
            if (index == 1) {
                EXPECT_EQ(Rect(400, 400), op.unmappedBounds); // inner rect
            } else if (index == 4) {
                EXPECT_EQ(Rect(800, 800), op.unmappedBounds); // outer rect
            } else { ADD_FAILURE(); }
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            const int index = mIndex++;
            if (index == 5) {
                EXPECT_EQ((OffscreenBuffer*)0x400, *op.layerHandle);
                EXPECT_EQ(Rect(400, 400), op.unmappedBounds); // inner layer
            } else if (index == 8) {
                EXPECT_EQ((OffscreenBuffer*)0x800, *op.layerHandle);
                EXPECT_EQ(Rect(800, 800), op.unmappedBounds); // outer layer
            } else { ADD_FAILURE(); }
        }
        void recycleTemporaryLayer(OffscreenBuffer* offscreenBuffer) override {
            const int index = mIndex++;
            // order isn't important, but we need to see both
            if (index == 10) {
                EXPECT_EQ((OffscreenBuffer*)0x400, offscreenBuffer);
            } else if (index == 11) {
                EXPECT_EQ((OffscreenBuffer*)0x800, offscreenBuffer);
            } else { ADD_FAILURE(); }
        }
    };

    auto node = TestUtils::createNode(0, 0, 800, 800,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(0, 0, 800, 800, 128, SaveFlags::ClipToLayer);
        {
            canvas.drawRect(0, 0, 800, 800, SkPaint());
            canvas.saveLayerAlpha(0, 0, 400, 400, 128, SaveFlags::ClipToLayer);
            {
                canvas.drawRect(0, 0, 400, 400, SkPaint());
            }
            canvas.restore();
        }
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(800, 800), 800, 800,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SaveLayerNestedTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(12, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, saveLayer_contentRejection) {
        auto node = TestUtils::createNode(0, 0, 200, 200,
                [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.clipRect(200, 200, 400, 400, SkRegion::kIntersect_Op);
        canvas.saveLayerAlpha(200, 200, 400, 400, 128, SaveFlags::ClipToLayer);

        // draw within save layer may still be recorded, but shouldn't be drawn
        canvas.drawRect(200, 200, 400, 400, SkPaint());

        canvas.restore();
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    FailRenderer renderer;
    // should see no ops, even within the layer, since the layer should be rejected
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

RENDERTHREAD_TEST(FrameBuilder, saveLayerUnclipped_simple) {
    class SaveLayerUnclippedSimpleTestRenderer : public TestRendererBase {
    public:
        void onCopyToLayerOp(const CopyToLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds);
            EXPECT_CLIP_RECT(Rect(200, 200), state.computedState.clipState);
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
        void onSimpleRectsOp(const SimpleRectsOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            ASSERT_NE(nullptr, op.paint);
            ASSERT_EQ(SkXfermode::kClear_Mode, PaintUtils::getXfermodeDirect(op.paint));
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
            EXPECT_EQ(Rect(200, 200), op.unmappedBounds);
            EXPECT_EQ(Rect(200, 200), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(200, 200), state.computedState.clipRect());
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
        void onCopyFromLayerOp(const CopyFromLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds);
            EXPECT_CLIP_RECT(Rect(200, 200), state.computedState.clipState);
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, (SaveFlags::Flags)(0));
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SaveLayerUnclippedSimpleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, saveLayerUnclipped_round) {
    class SaveLayerUnclippedRoundTestRenderer : public TestRendererBase {
    public:
        void onCopyToLayerOp(const CopyToLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds)
                    << "Bounds rect should round out";
        }
        void onSimpleRectsOp(const SimpleRectsOp& op, const BakedOpState& state) override {}
        void onRectOp(const RectOp& op, const BakedOpState& state) override {}
        void onCopyFromLayerOp(const CopyFromLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds)
                    << "Bounds rect should round out";
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10.95f, 10.5f, 189.75f, 189.25f, // values should all round out
                128, (SaveFlags::Flags)(0));
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SaveLayerUnclippedRoundTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, saveLayerUnclipped_mergedClears) {
    class SaveLayerUnclippedMergedClearsTestRenderer : public TestRendererBase {
    public:
        void onCopyToLayerOp(const CopyToLayerOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_GT(4, index);
            EXPECT_EQ(5, op.unmappedBounds.getWidth());
            EXPECT_EQ(5, op.unmappedBounds.getHeight());
            if (index == 0) {
                EXPECT_EQ(Rect(10, 10), state.computedState.clippedBounds);
            } else if (index == 1) {
                EXPECT_EQ(Rect(190, 0, 200, 10), state.computedState.clippedBounds);
            } else if (index == 2) {
                EXPECT_EQ(Rect(0, 190, 10, 200), state.computedState.clippedBounds);
            } else if (index == 3) {
                EXPECT_EQ(Rect(190, 190, 200, 200), state.computedState.clippedBounds);
            }
        }
        void onSimpleRectsOp(const SimpleRectsOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
            ASSERT_EQ(op.vertexCount, 16u);
            for (size_t i = 0; i < op.vertexCount; i++) {
                auto v = op.vertices[i];
                EXPECT_TRUE(v.x == 0 || v.x == 10 || v.x == 190 || v.x == 200);
                EXPECT_TRUE(v.y == 0 || v.y == 10 || v.y == 190 || v.y == 200);
            }
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(5, mIndex++);
        }
        void onCopyFromLayerOp(const CopyFromLayerOp& op, const BakedOpState& state) override {
            EXPECT_LT(5, mIndex++);
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {

        int restoreTo = canvas.save(SaveFlags::MatrixClip);
        canvas.scale(2, 2);
        canvas.saveLayerAlpha(0, 0, 5, 5, 128, SaveFlags::MatrixClip);
        canvas.saveLayerAlpha(95, 0, 100, 5, 128, SaveFlags::MatrixClip);
        canvas.saveLayerAlpha(0, 95, 5, 100, 128, SaveFlags::MatrixClip);
        canvas.saveLayerAlpha(95, 95, 100, 100, 128, SaveFlags::MatrixClip);
        canvas.drawRect(0, 0, 100, 100, SkPaint());
        canvas.restoreToCount(restoreTo);
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SaveLayerUnclippedMergedClearsTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(10, renderer.getIndex())
            << "Expect 4 copyTos, 4 copyFroms, 1 clear SimpleRects, and 1 rect.";
}

RENDERTHREAD_TEST(FrameBuilder, saveLayerUnclipped_clearClip) {
    class SaveLayerUnclippedClearClipTestRenderer : public TestRendererBase {
    public:
        void onCopyToLayerOp(const CopyToLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
        }
        void onSimpleRectsOp(const SimpleRectsOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            ASSERT_NE(nullptr, op.paint);
            EXPECT_EQ(SkXfermode::kClear_Mode, PaintUtils::getXfermodeDirect(op.paint));
            EXPECT_EQ(Rect(50, 50, 150, 150), state.computedState.clippedBounds)
                    << "Expect dirty rect as clip";
            ASSERT_NE(nullptr, state.computedState.clipState);
            EXPECT_EQ(Rect(50, 50, 150, 150), state.computedState.clipState->rect);
            EXPECT_EQ(ClipMode::Rectangle, state.computedState.clipState->mode);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
        }
        void onCopyFromLayerOp(const CopyFromLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        // save smaller than clip, so we get unclipped behavior
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, (SaveFlags::Flags)(0));
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.restore();
    });

    // draw with partial screen dirty, and assert we see that rect later
    FrameBuilder frameBuilder(SkRect::MakeLTRB(50, 50, 150, 150), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SaveLayerUnclippedClearClipTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, saveLayerUnclipped_reject) {
    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        // unclipped savelayer + rect both in area that won't intersect with dirty
        canvas.saveLayerAlpha(100, 100, 200, 200, 128, (SaveFlags::Flags)(0));
        canvas.drawRect(100, 100, 200, 200, SkPaint());
        canvas.restore();
    });

    // draw with partial screen dirty that doesn't intersect with savelayer
    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    FailRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

/* saveLayerUnclipped { saveLayer { saveLayerUnclipped { rect } } } will play back as:
 * - startTemporaryLayer, onCopyToLayer, onSimpleRects, onRect, onCopyFromLayer, endLayer
 * - startFrame, onCopyToLayer, onSimpleRects, drawLayer, onCopyFromLayer, endframe
 */
RENDERTHREAD_TEST(FrameBuilder, saveLayerUnclipped_complex) {
    class SaveLayerUnclippedComplexTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) {
            EXPECT_EQ(0, mIndex++); // savelayer first
            return (OffscreenBuffer*)0xabcd;
        }
        void onCopyToLayerOp(const CopyToLayerOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 1 || index == 7);
        }
        void onSimpleRectsOp(const SimpleRectsOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 2 || index == 8);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
            Matrix4 expected;
            expected.loadTranslate(-100, -100, 0);
            EXPECT_EQ(Rect(100, 100, 200, 200), state.computedState.clippedBounds);
            EXPECT_MATRIX_APPROX_EQ(expected, state.computedState.transform);
        }
        void onCopyFromLayerOp(const CopyFromLayerOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 4 || index == 10);
        }
        void endLayer() override {
            EXPECT_EQ(5, mIndex++);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(6, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(9, mIndex++);
            EXPECT_EQ((OffscreenBuffer*)0xabcd, *op.layerHandle);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(11, mIndex++);
        }
        void recycleTemporaryLayer(OffscreenBuffer* offscreenBuffer) override {
            EXPECT_EQ(12, mIndex++);
            EXPECT_EQ((OffscreenBuffer*)0xabcd, offscreenBuffer);
        }
    };

    auto node = TestUtils::createNode(0, 0, 600, 600, // 500x500 triggers clipping
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(0, 0, 500, 500, 128, (SaveFlags::Flags)0); // unclipped
        canvas.saveLayerAlpha(100, 100, 400, 400, 128, SaveFlags::ClipToLayer); // clipped
        canvas.saveLayerAlpha(200, 200, 300, 300, 128, (SaveFlags::Flags)0); // unclipped
        canvas.drawRect(200, 200, 300, 300, SkPaint());
        canvas.restore();
        canvas.restore();
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(600, 600), 600, 600,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    SaveLayerUnclippedComplexTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(13, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, hwLayer_simple) {
    class HwLayerSimpleTestRenderer : public TestRendererBase {
    public:
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(100u, offscreenBuffer->viewportWidth);
            EXPECT_EQ(100u, offscreenBuffer->viewportHeight);
            EXPECT_EQ(Rect(25, 25, 75, 75), repaintRect);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);

            EXPECT_TRUE(state.computedState.transform.isIdentity())
                    << "Transform should be reset within layer";

            EXPECT_EQ(Rect(25, 25, 75, 75), state.computedState.clipRect())
                    << "Damage rect should be used to clip layer content";
        }
        void endLayer() override {
            EXPECT_EQ(2, mIndex++);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(3, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(5, mIndex++);
        }
    };

    auto node = TestUtils::createNode(10, 10, 110, 110,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
    OffscreenBuffer** layerHandle = node->getLayerHandle();

    // create RenderNode's layer here in same way prepareTree would
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    *layerHandle = &layer;

    auto syncedNode = TestUtils::getSyncedNode(node);

    // only enqueue partial damage
    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(node.get(), Rect(25, 25, 75, 75));

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferLayers(layerUpdateQueue);
    frameBuilder.deferRenderNode(*syncedNode);

    HwLayerSimpleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(6, renderer.getIndex());

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

RENDERTHREAD_TEST(FrameBuilder, hwLayer_complex) {
    /* parentLayer { greyRect, saveLayer { childLayer { whiteRect } } } will play back as:
     * - startRepaintLayer(child), rect(grey), endLayer
     * - startTemporaryLayer, drawLayer(child), endLayer
     * - startRepaintLayer(parent), rect(white), drawLayer(saveLayer), endLayer
     * - startFrame, drawLayer(parent), endLayerb
     */
    class HwLayerComplexTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) {
            EXPECT_EQ(3, mIndex++); // savelayer first
            return (OffscreenBuffer*)0xabcd;
        }
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            int index = mIndex++;
            if (index == 0) {
                // starting inner layer
                EXPECT_EQ(100u, offscreenBuffer->viewportWidth);
                EXPECT_EQ(100u, offscreenBuffer->viewportHeight);
            } else if (index == 6) {
                // starting outer layer
                EXPECT_EQ(200u, offscreenBuffer->viewportWidth);
                EXPECT_EQ(200u, offscreenBuffer->viewportHeight);
            } else { ADD_FAILURE(); }
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            if (index == 1) {
                // inner layer's rect (white)
                EXPECT_EQ(SK_ColorWHITE, op.paint->getColor());
            } else if (index == 7) {
                // outer layer's rect (grey)
                EXPECT_EQ(SK_ColorDKGRAY, op.paint->getColor());
            } else { ADD_FAILURE(); }
        }
        void endLayer() override {
            int index = mIndex++;
            EXPECT_TRUE(index == 2 || index == 5 || index == 9);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(10, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            OffscreenBuffer* layer = *op.layerHandle;
            int index = mIndex++;
            if (index == 4) {
                EXPECT_EQ(100u, layer->viewportWidth);
                EXPECT_EQ(100u, layer->viewportHeight);
            } else if (index == 8) {
                EXPECT_EQ((OffscreenBuffer*)0xabcd, *op.layerHandle);
            } else if (index == 11) {
                EXPECT_EQ(200u, layer->viewportWidth);
                EXPECT_EQ(200u, layer->viewportHeight);
            } else { ADD_FAILURE(); }
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(12, mIndex++);
        }
        void recycleTemporaryLayer(OffscreenBuffer* offscreenBuffer) override {
            EXPECT_EQ(13, mIndex++);
        }
    };

    auto child = TestUtils::createNode(50, 50, 150, 150,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
    OffscreenBuffer childLayer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    *(child->getLayerHandle()) = &childLayer;

    RenderNode* childPtr = child.get();
    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [childPtr](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        SkPaint paint;
        paint.setColor(SK_ColorDKGRAY);
        canvas.drawRect(0, 0, 200, 200, paint);

        canvas.saveLayerAlpha(50, 50, 150, 150, 128, SaveFlags::ClipToLayer);
        canvas.drawRenderNode(childPtr);
        canvas.restore();
    });
    OffscreenBuffer parentLayer(renderThread.renderState(), Caches::getInstance(), 200, 200);
    *(parent->getLayerHandle()) = &parentLayer;

    auto syncedNode = TestUtils::getSyncedNode(parent);

    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(child.get(), Rect(100, 100));
    layerUpdateQueue.enqueueLayerWithDamage(parent.get(), Rect(200, 200));

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferLayers(layerUpdateQueue);
    frameBuilder.deferRenderNode(*syncedNode);

    HwLayerComplexTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(14, renderer.getIndex());

    // clean up layer pointers, so we can safely destruct RenderNodes
    *(child->getLayerHandle()) = nullptr;
    *(parent->getLayerHandle()) = nullptr;
}


RENDERTHREAD_TEST(FrameBuilder, buildLayer) {
    class BuildLayerTestRenderer : public TestRendererBase {
    public:
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(100u, offscreenBuffer->viewportWidth);
            EXPECT_EQ(100u, offscreenBuffer->viewportHeight);
            EXPECT_EQ(Rect(25, 25, 75, 75), repaintRect);
        }
        void onColorOp(const ColorOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);

            EXPECT_TRUE(state.computedState.transform.isIdentity())
                    << "Transform should be reset within layer";

            EXPECT_EQ(Rect(25, 25, 75, 75), state.computedState.clipRect())
                    << "Damage rect should be used to clip layer content";
        }
        void endLayer() override {
            EXPECT_EQ(2, mIndex++);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            ADD_FAILURE() << "Primary frame draw not expected in this test";
        }
        void endFrame(const Rect& repaintRect) override {
            ADD_FAILURE() << "Primary frame draw not expected in this test";
        }
    };

    auto node = TestUtils::createNode(10, 10, 110, 110,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        canvas.drawColor(SK_ColorWHITE, SkXfermode::Mode::kSrcOver_Mode);
    });
    OffscreenBuffer** layerHandle = node->getLayerHandle();

    // create RenderNode's layer here in same way prepareTree would
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    *layerHandle = &layer;

    TestUtils::syncHierarchyPropertiesAndDisplayList(node);

    // only enqueue partial damage
    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(node.get(), Rect(25, 25, 75, 75));

    // Draw, but pass empty node list, so no work is done for primary frame
    FrameBuilder frameBuilder(layerUpdateQueue, sLightGeometry, Caches::getInstance());
    BuildLayerTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(3, renderer.getIndex());

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

static void drawOrderedRect(RecordingCanvas* canvas, uint8_t expectedDrawOrder) {
    SkPaint paint;
    // order put in blue channel, transparent so overlapped content doesn't get rejected
    paint.setColor(SkColorSetARGB(1, 0, 0, expectedDrawOrder));
    canvas->drawRect(0, 0, 100, 100, paint);
}
static void drawOrderedNode(RecordingCanvas* canvas, uint8_t expectedDrawOrder, float z) {
    auto node = TestUtils::createNode(0, 0, 100, 100,
            [expectedDrawOrder](RenderProperties& props, RecordingCanvas& canvas) {
        drawOrderedRect(&canvas, expectedDrawOrder);
    });
    node->mutateStagingProperties().setTranslationZ(z);
    node->setPropertyFieldsDirty(RenderNode::TRANSLATION_Z);
    canvas->drawRenderNode(node.get()); // canvas takes reference/sole ownership
}
RENDERTHREAD_TEST(FrameBuilder, zReorder) {
    class ZReorderTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            int expectedOrder = SkColorGetB(op.paint->getColor()); // extract order from blue channel
            EXPECT_EQ(expectedOrder, mIndex++) << "An op was drawn out of order";
        }
    };

    auto parent = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, 10.0f); // in reorder=false at this point, so played inorder
        drawOrderedRect(&canvas, 1);
        canvas.insertReorderBarrier(true);
        drawOrderedNode(&canvas, 6, 2.0f);
        drawOrderedRect(&canvas, 3);
        drawOrderedNode(&canvas, 4, 0.0f);
        drawOrderedRect(&canvas, 5);
        drawOrderedNode(&canvas, 2, -2.0f);
        drawOrderedNode(&canvas, 7, 2.0f);
        canvas.insertReorderBarrier(false);
        drawOrderedRect(&canvas, 8);
        drawOrderedNode(&canvas, 9, -10.0f); // in reorder=false at this point, so played inorder
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 100, 100,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(parent));

    ZReorderTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(10, renderer.getIndex());
};

RENDERTHREAD_TEST(FrameBuilder, projectionReorder) {
    static const int scrollX = 5;
    static const int scrollY = 10;
    class ProjectionReorderTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            const int index = mIndex++;

            Matrix4 expectedMatrix;
            switch (index) {
            case 0:
                EXPECT_EQ(Rect(100, 100), op.unmappedBounds);
                EXPECT_EQ(SK_ColorWHITE, op.paint->getColor());
                expectedMatrix.loadIdentity();
                EXPECT_EQ(nullptr, state.computedState.localProjectionPathMask);
                break;
            case 1:
                EXPECT_EQ(Rect(-10, -10, 60, 60), op.unmappedBounds);
                EXPECT_EQ(SK_ColorDKGRAY, op.paint->getColor());
                expectedMatrix.loadTranslate(50 - scrollX, 50 - scrollY, 0);
                ASSERT_NE(nullptr, state.computedState.localProjectionPathMask);
                EXPECT_EQ(Rect(-35, -30, 45, 50),
                        Rect(state.computedState.localProjectionPathMask->getBounds()));
                break;
            case 2:
                EXPECT_EQ(Rect(100, 50), op.unmappedBounds);
                EXPECT_EQ(SK_ColorBLUE, op.paint->getColor());
                expectedMatrix.loadTranslate(-scrollX, 50 - scrollY, 0);
                EXPECT_EQ(nullptr, state.computedState.localProjectionPathMask);
                break;
            default:
                ADD_FAILURE();
            }
            EXPECT_EQ(expectedMatrix, state.computedState.transform);
        }
    };

    /**
     * Construct a tree of nodes, where the root (A) has a receiver background (B), and a child (C)
     * with a projecting child (P) of its own. P would normally draw between B and C's "background"
     * draw, but because it is projected backwards, it's drawn in between B and C.
     *
     * The parent is scrolled by scrollX/scrollY, but this does not affect the background
     * (which isn't affected by scroll).
     */
    auto receiverBackground = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setProjectionReceiver(true);
        // scroll doesn't apply to background, so undone via translationX/Y
        // NOTE: translationX/Y only! no other transform properties may be set for a proj receiver!
        properties.setTranslationX(scrollX);
        properties.setTranslationY(scrollY);

        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
    auto projectingRipple = TestUtils::createNode(50, 0, 100, 50,
            [](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setProjectBackwards(true);
        properties.setClipToBounds(false);
        SkPaint paint;
        paint.setColor(SK_ColorDKGRAY);
        canvas.drawRect(-10, -10, 60, 60, paint);
    });
    auto child = TestUtils::createNode(0, 50, 100, 100,
            [&projectingRipple](RenderProperties& properties, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorBLUE);
        canvas.drawRect(0, 0, 100, 50, paint);
        canvas.drawRenderNode(projectingRipple.get());
    });
    auto parent = TestUtils::createNode(0, 0, 100, 100,
            [&receiverBackground, &child](RenderProperties& properties, RecordingCanvas& canvas) {
        // Set a rect outline for the projecting ripple to be masked against.
        properties.mutableOutline().setRoundRect(10, 10, 90, 90, 5, 1.0f);

        canvas.save(SaveFlags::MatrixClip);
        canvas.translate(-scrollX, -scrollY); // Apply scroll (note: bg undoes this internally)
        canvas.drawRenderNode(receiverBackground.get());
        canvas.drawRenderNode(child.get());
        canvas.restore();
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 100, 100,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(parent));

    ProjectionReorderTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(3, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, projectionHwLayer) {
    static const int scrollX = 5;
    static const int scrollY = 10;
    class ProjectionHwLayerTestRenderer : public TestRendererBase {
    public:
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
        }
        void onArcOp(const ArcOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            ASSERT_EQ(nullptr, state.computedState.localProjectionPathMask);
        }
        void endLayer() override {
            EXPECT_EQ(2, mIndex++);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
            ASSERT_EQ(nullptr, state.computedState.localProjectionPathMask);
        }
        void onOvalOp(const OvalOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
            ASSERT_NE(nullptr, state.computedState.localProjectionPathMask);
            Matrix4 expected;
            expected.loadTranslate(100 - scrollX, 100 - scrollY, 0);
            EXPECT_EQ(expected, state.computedState.transform);
            EXPECT_EQ(Rect(-85, -80, 295, 300),
                    Rect(state.computedState.localProjectionPathMask->getBounds()));
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(5, mIndex++);
            ASSERT_EQ(nullptr, state.computedState.localProjectionPathMask);
        }
    };
    auto receiverBackground = TestUtils::createNode(0, 0, 400, 400,
            [](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setProjectionReceiver(true);
        // scroll doesn't apply to background, so undone via translationX/Y
        // NOTE: translationX/Y only! no other transform properties may be set for a proj receiver!
        properties.setTranslationX(scrollX);
        properties.setTranslationY(scrollY);

        canvas.drawRect(0, 0, 400, 400, SkPaint());
    });
    auto projectingRipple = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setProjectBackwards(true);
        properties.setClipToBounds(false);
        canvas.drawOval(100, 100, 300, 300, SkPaint()); // drawn mostly out of layer bounds
    });
    auto child = TestUtils::createNode(100, 100, 300, 300,
            [&projectingRipple](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.mutateLayerProperties().setType(LayerType::RenderLayer);
        canvas.drawRenderNode(projectingRipple.get());
        canvas.drawArc(0, 0, 200, 200, 0.0f, 280.0f, true, SkPaint());
    });
    auto parent = TestUtils::createNode(0, 0, 400, 400,
            [&receiverBackground, &child](RenderProperties& properties, RecordingCanvas& canvas) {
        // Set a rect outline for the projecting ripple to be masked against.
        properties.mutableOutline().setRoundRect(10, 10, 390, 390, 0, 1.0f);
        canvas.translate(-scrollX, -scrollY); // Apply scroll (note: bg undoes this internally)
        canvas.drawRenderNode(receiverBackground.get());
        canvas.drawRenderNode(child.get());
    });

    OffscreenBuffer** layerHandle = child->getLayerHandle();

    // create RenderNode's layer here in same way prepareTree would, setting windowTransform
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 200, 200);
    Matrix4 windowTransform;
    windowTransform.loadTranslate(100, 100, 0); // total transform of layer's origin
    layer.setWindowTransform(windowTransform);
    *layerHandle = &layer;

    auto syncedNode = TestUtils::getSyncedNode(parent);

    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(child.get(), Rect(200, 200));

    FrameBuilder frameBuilder(SkRect::MakeWH(400, 400), 400, 400,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferLayers(layerUpdateQueue);
    frameBuilder.deferRenderNode(*syncedNode);

    ProjectionHwLayerTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(6, renderer.getIndex());

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

RENDERTHREAD_TEST(FrameBuilder, projectionChildScroll) {
    static const int scrollX = 500000;
    static const int scrollY = 0;
    class ProjectionChildScrollTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
        void onOvalOp(const OvalOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            ASSERT_NE(nullptr, state.computedState.clipState);
            ASSERT_EQ(ClipMode::Rectangle, state.computedState.clipState->mode);
            ASSERT_EQ(Rect(400, 400), state.computedState.clipState->rect);
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
    };
    auto receiverBackground = TestUtils::createNode(0, 0, 400, 400,
            [](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setProjectionReceiver(true);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
    });
    auto projectingRipple = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& properties, RecordingCanvas& canvas) {
        // scroll doesn't apply to background, so undone via translationX/Y
        // NOTE: translationX/Y only! no other transform properties may be set for a proj receiver!
        properties.setTranslationX(scrollX);
        properties.setTranslationY(scrollY);
        properties.setProjectBackwards(true);
        properties.setClipToBounds(false);
        canvas.drawOval(0, 0, 200, 200, SkPaint());
    });
    auto child = TestUtils::createNode(0, 0, 400, 400,
            [&projectingRipple](RenderProperties& properties, RecordingCanvas& canvas) {
        // Record time clip will be ignored by projectee
        canvas.clipRect(100, 100, 300, 300, SkRegion::kIntersect_Op);

        canvas.translate(-scrollX, -scrollY); // Apply scroll (note: bg undoes this internally)
        canvas.drawRenderNode(projectingRipple.get());
    });
    auto parent = TestUtils::createNode(0, 0, 400, 400,
            [&receiverBackground, &child](RenderProperties& properties, RecordingCanvas& canvas) {
        canvas.drawRenderNode(receiverBackground.get());
        canvas.drawRenderNode(child.get());
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(400, 400), 400, 400,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(parent));

    ProjectionChildScrollTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex());
}

// creates a 100x100 shadow casting node with provided translationZ
static sp<RenderNode> createWhiteRectShadowCaster(float translationZ) {
    return TestUtils::createNode(0, 0, 100, 100,
            [translationZ](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setTranslationZ(translationZ);
        properties.mutableOutline().setRoundRect(0, 0, 100, 100, 0.0f, 1.0f);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
}

RENDERTHREAD_TEST(FrameBuilder, shadow) {
    class ShadowTestRenderer : public TestRendererBase {
    public:
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_FLOAT_EQ(1.0f, op.casterAlpha);
            EXPECT_TRUE(op.shadowTask->casterPerimeter.isRect(nullptr));
            EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), op.shadowTask->transformXY);

            Matrix4 expectedZ;
            expectedZ.loadTranslate(0, 0, 5);
            EXPECT_MATRIX_APPROX_EQ(expectedZ, op.shadowTask->transformZ);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
        }
    };

    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(parent));

    ShadowTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, shadowSaveLayer) {
    class ShadowSaveLayerTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            EXPECT_EQ(0, mIndex++);
            return nullptr;
        }
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_FLOAT_EQ(50, op.shadowTask->lightCenter.x);
            EXPECT_FLOAT_EQ(40, op.shadowTask->lightCenter.y);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
        }
        void endLayer() override {
            EXPECT_EQ(3, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
        }
        void recycleTemporaryLayer(OffscreenBuffer* offscreenBuffer) override {
            EXPECT_EQ(5, mIndex++);
        }
    };

    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        // save/restore outside of reorderBarrier, so they don't get moved out of place
        canvas.translate(20, 10);
        int count = canvas.saveLayerAlpha(30, 50, 130, 150, 128, SaveFlags::ClipToLayer);
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.insertReorderBarrier(false);
        canvas.restoreToCount(count);
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            (FrameBuilder::LightGeometry) {{ 100, 100, 100 }, 50}, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(parent));

    ShadowSaveLayerTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(6, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, shadowHwLayer) {
    class ShadowHwLayerTestRenderer : public TestRendererBase {
    public:
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
        }
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_FLOAT_EQ(50, op.shadowTask->lightCenter.x);
            EXPECT_FLOAT_EQ(40, op.shadowTask->lightCenter.y);
            EXPECT_FLOAT_EQ(30, op.shadowTask->lightRadius);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
        }
        void endLayer() override {
            EXPECT_EQ(3, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
        }
    };

    auto parent = TestUtils::createNode(50, 60, 150, 160,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        canvas.insertReorderBarrier(true);
        canvas.save(SaveFlags::MatrixClip);
        canvas.translate(20, 10);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.restore();
    });
    OffscreenBuffer** layerHandle = parent->getLayerHandle();

    // create RenderNode's layer here in same way prepareTree would, setting windowTransform
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    Matrix4 windowTransform;
    windowTransform.loadTranslate(50, 60, 0); // total transform of layer's origin
    layer.setWindowTransform(windowTransform);
    *layerHandle = &layer;

    auto syncedNode = TestUtils::getSyncedNode(parent);
    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(parent.get(), Rect(100, 100));

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            (FrameBuilder::LightGeometry) {{ 100, 100, 100 }, 30}, Caches::getInstance());
    frameBuilder.deferLayers(layerUpdateQueue);
    frameBuilder.deferRenderNode(*syncedNode);

    ShadowHwLayerTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(5, renderer.getIndex());

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

RENDERTHREAD_TEST(FrameBuilder, shadowLayering) {
    class ShadowLayeringTestRenderer : public TestRendererBase {
    public:
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 0 || index == 1);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 2 || index == 3);
        }
    };
    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0001f).get());
    });
    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
            (FrameBuilder::LightGeometry) {{ 100, 100, 100 }, 50}, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(parent));

    ShadowLayeringTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, shadowClipping) {
    class ShadowClippingTestRenderer : public TestRendererBase {
    public:
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(25, 25, 75, 75), state.computedState.clipState->rect)
                    << "Shadow must respect pre-barrier canvas clip value.";
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
        }
    };
    auto parent = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        // Apply a clip before the reorder barrier/shadow casting child is drawn.
        // This clip must be applied to the shadow cast by the child.
        canvas.clipRect(25, 25, 75, 75, SkRegion::kIntersect_Op);
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 100, 100,
            (FrameBuilder::LightGeometry) {{ 100, 100, 100 }, 50}, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(parent));

    ShadowClippingTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex());
}

static void testProperty(std::function<void(RenderProperties&)> propSetupCallback,
        std::function<void(const RectOp&, const BakedOpState&)> opValidateCallback) {
    class PropertyTestRenderer : public TestRendererBase {
    public:
        PropertyTestRenderer(std::function<void(const RectOp&, const BakedOpState&)> callback)
                : mCallback(callback) {}
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(mIndex++, 0);
            mCallback(op, state);
        }
        std::function<void(const RectOp&, const BakedOpState&)> mCallback;
    };

    auto node = TestUtils::createNode(0, 0, 100, 100,
            [propSetupCallback](RenderProperties& props, RecordingCanvas& canvas) {
        propSetupCallback(props);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });

    FrameBuilder frameBuilder(SkRect::MakeWH(100, 100), 200, 200,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    PropertyTestRenderer renderer(opValidateCallback);
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex()) << "Should have seen one op";
}

RENDERTHREAD_TEST(FrameBuilder, renderPropOverlappingRenderingAlpha) {
    testProperty([](RenderProperties& properties) {
        properties.setAlpha(0.5f);
        properties.setHasOverlappingRendering(false);
    }, [](const RectOp& op, const BakedOpState& state) {
        EXPECT_EQ(0.5f, state.alpha) << "Alpha should be applied directly to op";
    });
}

RENDERTHREAD_TEST(FrameBuilder, renderPropClipping) {
    testProperty([](RenderProperties& properties) {
        properties.setClipToBounds(true);
        properties.setClipBounds(Rect(10, 20, 300, 400));
    }, [](const RectOp& op, const BakedOpState& state) {
        EXPECT_EQ(Rect(10, 20, 100, 100), state.computedState.clippedBounds)
                << "Clip rect should be intersection of node bounds and clip bounds";
    });
}

RENDERTHREAD_TEST(FrameBuilder, renderPropRevealClip) {
    testProperty([](RenderProperties& properties) {
        properties.mutableRevealClip().set(true, 50, 50, 25);
    }, [](const RectOp& op, const BakedOpState& state) {
        ASSERT_NE(nullptr, state.roundRectClipState);
        EXPECT_TRUE(state.roundRectClipState->highPriority);
        EXPECT_EQ(25, state.roundRectClipState->radius);
        EXPECT_EQ(Rect(50, 50, 50, 50), state.roundRectClipState->innerRect);
    });
}

RENDERTHREAD_TEST(FrameBuilder, renderPropOutlineClip) {
    testProperty([](RenderProperties& properties) {
        properties.mutableOutline().setShouldClip(true);
        properties.mutableOutline().setRoundRect(10, 20, 30, 40, 5.0f, 0.5f);
    }, [](const RectOp& op, const BakedOpState& state) {
        ASSERT_NE(nullptr, state.roundRectClipState);
        EXPECT_FALSE(state.roundRectClipState->highPriority);
        EXPECT_EQ(5, state.roundRectClipState->radius);
        EXPECT_EQ(Rect(15, 25, 25, 35), state.roundRectClipState->innerRect);
    });
}

RENDERTHREAD_TEST(FrameBuilder, renderPropTransform) {
    testProperty([](RenderProperties& properties) {
        properties.setLeftTopRightBottom(10, 10, 110, 110);

        SkMatrix staticMatrix = SkMatrix::MakeScale(1.2f, 1.2f);
        properties.setStaticMatrix(&staticMatrix);

        // ignored, since static overrides animation
        SkMatrix animationMatrix = SkMatrix::MakeTrans(15, 15);
        properties.setAnimationMatrix(&animationMatrix);

        properties.setTranslationX(10);
        properties.setTranslationY(20);
        properties.setScaleX(0.5f);
        properties.setScaleY(0.7f);
    }, [](const RectOp& op, const BakedOpState& state) {
        Matrix4 matrix;
        matrix.loadTranslate(10, 10, 0); // left, top
        matrix.scale(1.2f, 1.2f, 1); // static matrix
        // ignore animation matrix, since static overrides it

        // translation xy
        matrix.translate(10, 20);

        // scale xy (from default pivot - center)
        matrix.translate(50, 50);
        matrix.scale(0.5f, 0.7f, 1);
        matrix.translate(-50, -50);
        EXPECT_MATRIX_APPROX_EQ(matrix, state.computedState.transform)
                << "Op draw matrix must match expected combination of transformation properties";
    });
}

struct SaveLayerAlphaData {
    uint32_t layerWidth = 0;
    uint32_t layerHeight = 0;
    Rect rectClippedBounds;
    Matrix4 rectMatrix;
    Matrix4 drawLayerMatrix;
};
/**
 * Constructs a view to hit the temporary layer alpha property implementation:
 *     a) 0 < alpha < 1
 *     b) too big for layer (larger than maxTextureSize)
 *     c) overlapping rendering content
 * returning observed data about layer size and content clip/transform.
 *
 * Used to validate clipping behavior of temporary layer, where requested layer size is reduced
 * (for efficiency, and to fit in layer size constraints) based on parent clip.
 */
void testSaveLayerAlphaClip(SaveLayerAlphaData* outObservedData,
        std::function<void(RenderProperties&)> propSetupCallback) {
    class SaveLayerAlphaClipTestRenderer : public TestRendererBase {
    public:
        SaveLayerAlphaClipTestRenderer(SaveLayerAlphaData* outData)
                : mOutData(outData) {}

        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            EXPECT_EQ(0, mIndex++);
            mOutData->layerWidth = width;
            mOutData->layerHeight = height;
            return nullptr;
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);

            mOutData->rectClippedBounds = state.computedState.clippedBounds;
            mOutData->rectMatrix = state.computedState.transform;
        }
        void endLayer() override {
            EXPECT_EQ(2, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
            mOutData->drawLayerMatrix = state.computedState.transform;
        }
        void recycleTemporaryLayer(OffscreenBuffer* offscreenBuffer) override {
            EXPECT_EQ(4, mIndex++);
        }
    private:
        SaveLayerAlphaData* mOutData;
    };

    ASSERT_GT(10000, DeviceInfo::get()->maxTextureSize())
            << "Node must be bigger than max texture size to exercise saveLayer codepath";
    auto node = TestUtils::createNode(0, 0, 10000, 10000,
            [&propSetupCallback](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setHasOverlappingRendering(true);
        properties.setAlpha(0.5f); // force saveLayer, since too big for HW layer
        // apply other properties
        propSetupCallback(properties);

        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 10000, 10000, paint);
    });
    auto syncedNode = TestUtils::getSyncedNode(node); // sync before querying height

    FrameBuilder frameBuilder(SkRect::MakeWH(200, 200), 200, 200,
                sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*syncedNode);

    SaveLayerAlphaClipTestRenderer renderer(outObservedData);
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);

    // assert, since output won't be valid if we haven't seen a save layer triggered
    ASSERT_EQ(5, renderer.getIndex()) << "Test must trigger saveLayer alpha behavior.";
}

RENDERTHREAD_TEST(FrameBuilder, renderPropSaveLayerAlphaClipBig) {
    SaveLayerAlphaData observedData;
    testSaveLayerAlphaClip(&observedData, [](RenderProperties& properties) {
        properties.setTranslationX(10); // offset rendering content
        properties.setTranslationY(-2000); // offset rendering content
    });
    EXPECT_EQ(190u, observedData.layerWidth);
    EXPECT_EQ(200u, observedData.layerHeight);
    EXPECT_EQ(Rect(190, 200), observedData.rectClippedBounds)
            << "expect content to be clipped to screen area";
    Matrix4 expected;
    expected.loadTranslate(0, -2000, 0);
    EXPECT_MATRIX_APPROX_EQ(expected, observedData.rectMatrix)
            << "expect content to be translated as part of being clipped";
    expected.loadTranslate(10, 0, 0);
    EXPECT_MATRIX_APPROX_EQ(expected, observedData.drawLayerMatrix)
                << "expect drawLayer to be translated as part of being clipped";
}

RENDERTHREAD_TEST(FrameBuilder, renderPropSaveLayerAlphaRotate) {
    SaveLayerAlphaData observedData;
    testSaveLayerAlphaClip(&observedData, [](RenderProperties& properties) {
        // Translate and rotate the view so that the only visible part is the top left corner of
        // the view. It will form an isosceles right triangle with a long side length of 200 at the
        // bottom of the viewport.
        properties.setTranslationX(100);
        properties.setTranslationY(100);
        properties.setPivotX(0);
        properties.setPivotY(0);
        properties.setRotation(45);
    });
    // ceil(sqrt(2) / 2 * 200) = 142
    EXPECT_EQ(142u, observedData.layerWidth);
    EXPECT_EQ(142u, observedData.layerHeight);
    EXPECT_EQ(Rect(142, 142), observedData.rectClippedBounds);
    EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), observedData.rectMatrix);
}

RENDERTHREAD_TEST(FrameBuilder, renderPropSaveLayerAlphaScale) {
    SaveLayerAlphaData observedData;
    testSaveLayerAlphaClip(&observedData, [](RenderProperties& properties) {
        properties.setPivotX(0);
        properties.setPivotY(0);
        properties.setScaleX(2);
        properties.setScaleY(0.5f);
    });
    EXPECT_EQ(100u, observedData.layerWidth);
    EXPECT_EQ(400u, observedData.layerHeight);
    EXPECT_EQ(Rect(100, 400), observedData.rectClippedBounds);
    EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), observedData.rectMatrix);
}

RENDERTHREAD_TEST(FrameBuilder, clip_replace) {
    class ClipReplaceTestRenderer : public TestRendererBase {
    public:
        void onColorOp(const ColorOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_TRUE(op.localClip->intersectWithRoot);
            EXPECT_EQ(Rect(20, 10, 30, 40), state.computedState.clipState->rect)
                    << "Expect resolved clip to be intersection of viewport clip and clip op";
        }
    };
    auto node = TestUtils::createNode(20, 20, 30, 30,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.clipRect(0, -20, 10, 30, SkRegion::kReplace_Op);
        canvas.drawColor(SK_ColorWHITE, SkXfermode::Mode::kSrcOver_Mode);
    });

    FrameBuilder frameBuilder(SkRect::MakeLTRB(10, 10, 40, 40), 50, 50,
            sLightGeometry, Caches::getInstance());
    frameBuilder.deferRenderNode(*TestUtils::getSyncedNode(node));

    ClipReplaceTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex());
}

} // namespace uirenderer
} // namespace android
