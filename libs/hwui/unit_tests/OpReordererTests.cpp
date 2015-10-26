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

#include <gtest/gtest.h>

#include <BakedOpState.h>
#include <OpReorderer.h>
#include <RecordedOp.h>
#include <RecordingCanvas.h>
#include <unit_tests/TestUtils.h>

#include <unordered_map>

namespace android {
namespace uirenderer {


/**
 * Virtual class implemented by each test to redirect static operation / state transitions to
 * virtual methods.
 *
 * Virtual dispatch allows for default behaviors to be specified (very common case in below tests),
 * and allows Renderer vs Dispatching behavior to be merged.
 *
 * onXXXOp methods fail by default - tests should override ops they expect
 * startLayer fails by default - tests should override if expected
 * startFrame/endFrame do nothing by default - tests should override to intercept
 */
class TestRendererBase {
public:
    virtual ~TestRendererBase() {}
    virtual OffscreenBuffer* startLayer(uint32_t width, uint32_t height) { ADD_FAILURE(); return nullptr; }
    virtual void endLayer() { ADD_FAILURE(); }
    virtual void startFrame(uint32_t width, uint32_t height) {}
    virtual void endFrame() {}

    // define virtual defaults for direct
#define BASE_OP_METHOD(Type) \
    virtual void on##Type(const Type&, const BakedOpState&) { ADD_FAILURE(); }
    MAP_OPS(BASE_OP_METHOD)
    int getIndex() { return mIndex; }

protected:
    int mIndex = 0;
};

/**
 * Dispatches all static methods to similar formed methods on renderer, which fail by default but
 * are overriden by subclasses per test.
 */
class TestDispatcher {
public:
#define DISPATCHER_METHOD(Type) \
    static void on##Type(TestRendererBase& renderer, const Type& op, const BakedOpState& state) { \
        renderer.on##Type(op, state); \
    }
    MAP_OPS(DISPATCHER_METHOD);
};


class FailRenderer : public TestRendererBase {};

class SimpleTestRenderer : public TestRendererBase {
public:
    void startFrame(uint32_t width, uint32_t height) override {
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
    void endFrame() override {
        EXPECT_EQ(3, mIndex++);
    }
};
TEST(OpReorderer, simple) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(25, 25);
        canvas.drawRect(0, 0, 100, 200, SkPaint());
        canvas.drawBitmap(bitmap, 10, 10, nullptr);
    });
    OpReorderer reorderer(100, 200, *dl);

    SimpleTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex()); // 2 ops + start + end
}


TEST(OpReorderer, simpleRejection) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.clipRect(200, 200, 400, 400, SkRegion::kIntersect_Op); // intersection should be empty
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();
    });
    OpReorderer reorderer(200, 200, *dl);

    FailRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
}


static int SIMPLE_BATCHING_LOOPS = 5;
class SimpleBatchingTestRenderer : public TestRendererBase {
public:
    void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
        EXPECT_TRUE(mIndex++ >= SIMPLE_BATCHING_LOOPS);
    }
    void onRectOp(const RectOp& op, const BakedOpState& state) override {
        EXPECT_TRUE(mIndex++ < SIMPLE_BATCHING_LOOPS);
    }
};
TEST(OpReorderer, simpleBatching) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(10, 10);

        // Alternate between drawing rects and bitmaps, with bitmaps overlapping rects.
        // Rects don't overlap bitmaps, so bitmaps should be brought to front as a group.
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        for (int i = 0; i < SIMPLE_BATCHING_LOOPS; i++) {
            canvas.translate(0, 10);
            canvas.drawRect(0, 0, 10, 10, SkPaint());
            canvas.drawBitmap(bitmap, 5, 0, nullptr);
        }
        canvas.restore();
    });

    OpReorderer reorderer(200, 200, *dl);

    SimpleBatchingTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2 * SIMPLE_BATCHING_LOOPS, renderer.getIndex()); // 2 x loops ops, because no merging (TODO: force no merging)
}

class RenderNodeTestRenderer : public TestRendererBase {
public:
    void onRectOp(const RectOp& op, const BakedOpState& state) override {
        switch(mIndex++) {
        case 0:
            EXPECT_EQ(Rect(0, 0, 200, 200), state.computedState.clippedBounds);
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
TEST(OpReorderer, renderNode) {
    sp<RenderNode> child = TestUtils::createNode<RecordingCanvas>(10, 10, 110, 110, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });

    RenderNode* childPtr = child.get();
    sp<RenderNode> parent = TestUtils::createNode<RecordingCanvas>(0, 0, 200, 200, [childPtr](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorDKGRAY);
        canvas.drawRect(0, 0, 200, 200, paint);

        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(40, 40);
        canvas.drawRenderNode(childPtr);
        canvas.restore();
    });

    TestUtils::syncNodePropertiesAndDisplayList(child);
    TestUtils::syncNodePropertiesAndDisplayList(parent);

    std::vector< sp<RenderNode> > nodes;
    nodes.push_back(parent.get());

    OpReorderer reorderer(SkRect::MakeWH(200, 200), 200, 200, nodes);

    RenderNodeTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
}

class ClippedTestRenderer : public TestRendererBase {
public:
    void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
        EXPECT_EQ(0, mIndex++);
        EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clippedBounds);
        EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clipRect);
        EXPECT_TRUE(state.computedState.transform.isIdentity());
    }
};
TEST(OpReorderer, clipped) {
    sp<RenderNode> node = TestUtils::createNode<RecordingCanvas>(0, 0, 200, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(200, 200);
        canvas.drawBitmap(bitmap, 0, 0, nullptr);
    });
    TestUtils::syncNodePropertiesAndDisplayList(node);
    std::vector< sp<RenderNode> > nodes;
    nodes.push_back(node.get());

    OpReorderer reorderer(SkRect::MakeLTRB(10, 20, 30, 40), // clip to small area, should see in receiver
            200, 200, nodes);

    ClippedTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
}


class SaveLayerSimpleTestRenderer : public TestRendererBase {
public:
    OffscreenBuffer* startLayer(uint32_t width, uint32_t height) override {
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
        EXPECT_EQ(Rect(0, 0, 180, 180), state.computedState.clippedBounds);
        EXPECT_EQ(Rect(0, 0, 180, 180), state.computedState.clipRect);

        Matrix4 expectedTransform;
        expectedTransform.loadTranslate(-10, -10, 0);
        EXPECT_MATRIX_APPROX_EQ(expectedTransform, state.computedState.transform);
    }
    void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
        EXPECT_EQ(3, mIndex++);
        EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds);
        EXPECT_EQ(Rect(0, 0, 200, 200), state.computedState.clipRect);
        EXPECT_TRUE(state.computedState.transform.isIdentity());
    }
};
TEST(OpReorderer, saveLayerSimple) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, SkCanvas::kClipToLayer_SaveFlag);
        canvas.drawRect(10, 10, 190, 190, SkPaint());
        canvas.restore();
    });

    OpReorderer reorderer(200, 200, *dl);

    SaveLayerSimpleTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}


/* saveLayer1 {rect1, saveLayer2 { rect2 } } will play back as:
 * - startLayer2, rect2 endLayer2
 * - startLayer1, rect1, drawLayer2, endLayer1
 * - startFrame, layerOp1, endFrame
 */
class SaveLayerNestedTestRenderer : public TestRendererBase {
public:
    OffscreenBuffer* startLayer(uint32_t width, uint32_t height) override {
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
    void startFrame(uint32_t width, uint32_t height) override {
        EXPECT_EQ(7, mIndex++);
    }
    void endFrame() override {
        EXPECT_EQ(9, mIndex++);
    }
    void onRectOp(const RectOp& op, const BakedOpState& state) override {
        const int index = mIndex++;
        if (index == 1) {
            EXPECT_EQ(Rect(0, 0, 400, 400), op.unmappedBounds); // inner rect
        } else if (index == 4) {
            EXPECT_EQ(Rect(0, 0, 800, 800), op.unmappedBounds); // outer rect
        } else { ADD_FAILURE(); }
    }
    void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
        const int index = mIndex++;
        if (index == 5) {
            EXPECT_EQ((OffscreenBuffer*)0x400, *op.layerHandle);
            EXPECT_EQ(Rect(0, 0, 400, 400), op.unmappedBounds); // inner layer
        } else if (index == 8) {
            EXPECT_EQ((OffscreenBuffer*)0x800, *op.layerHandle);
            EXPECT_EQ(Rect(0, 0, 800, 800), op.unmappedBounds); // outer layer
        } else { ADD_FAILURE(); }
    }
};
TEST(OpReorderer, saveLayerNested) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(800, 800, [](RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(0, 0, 800, 800, 128, SkCanvas::kClipToLayer_SaveFlag);
        {
            canvas.drawRect(0, 0, 800, 800, SkPaint());
            canvas.saveLayerAlpha(0, 0, 400, 400, 128, SkCanvas::kClipToLayer_SaveFlag);
            {
                canvas.drawRect(0, 0, 400, 400, SkPaint());
            }
            canvas.restore();
        }
        canvas.restore();
    });

    OpReorderer reorderer(800, 800, *dl);

    SaveLayerNestedTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(10, renderer.getIndex());
}

TEST(OpReorderer, saveLayerContentRejection) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.clipRect(200, 200, 400, 400, SkRegion::kIntersect_Op);
        canvas.saveLayerAlpha(200, 200, 400, 400, 128, SkCanvas::kClipToLayer_SaveFlag);

        // draw within save layer may still be recorded, but shouldn't be drawn
        canvas.drawRect(200, 200, 400, 400, SkPaint());

        canvas.restore();
        canvas.restore();
    });
    OpReorderer reorderer(200, 200, *dl);

    FailRenderer renderer;
    // should see no ops, even within the layer, since the layer should be rejected
    reorderer.replayBakedOps<TestDispatcher>(renderer);
}

} // namespace uirenderer
} // namespace android
