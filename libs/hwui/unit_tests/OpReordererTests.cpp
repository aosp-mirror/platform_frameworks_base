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
 * Class that redirects static operation dispatch to virtual methods on a Client class.
 *
 * The client is recreated for every op (so data cannot be persisted between operations), but the
 * virtual dispatch allows for default behaviors to be specified without enumerating each operation
 * for every test.
 *
 * onXXXOp methods fail by default - tests should override ops they expect
 * startFrame/endFrame do nothing by default - tests should override to intercept
 */
template<class CustomClient, class Arg>
class TestReceiver {
public:
#define CLIENT_METHOD(Type) \
    virtual void on##Type(Arg&, const Type&, const BakedOpState&) { FAIL(); }
    class Client {
    public:
        virtual ~Client() {};
        MAP_OPS(CLIENT_METHOD)

        virtual void startFrame(Arg& info) {}
        virtual void endFrame(Arg& info) {}
    };

#define DISPATCHER_METHOD(Type) \
    static void on##Type(Arg& arg, const Type& op, const BakedOpState& state) { \
        CustomClient client; client.on##Type(arg, op, state); \
    }
    MAP_OPS(DISPATCHER_METHOD)

    static void startFrame(Arg& info) {
        CustomClient client;
        client.startFrame(info);
    }

    static void endFrame(Arg& info) {
        CustomClient client;
        client.endFrame(info);
    }
};

class Info {
public:
    int index = 0;
};

// Receiver class which will fail if it receives any ops
class FailReceiver : public TestReceiver<FailReceiver, Info>::Client {};

class SimpleReceiver : public TestReceiver<SimpleReceiver, Info>::Client {
public:
    void startFrame(Info& info) override {
        EXPECT_EQ(0, info.index++);
    }
    void onRectOp(Info& info, const RectOp& op, const BakedOpState& state) override {
        EXPECT_EQ(1, info.index++);
    }
    void onBitmapOp(Info& info, const BitmapOp& op, const BakedOpState& state) override {
        EXPECT_EQ(2, info.index++);
    }
    void endFrame(Info& info) override {
        EXPECT_EQ(3, info.index++);
    }
};
TEST(OpReorderer, simple) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(25, 25);
        canvas.drawRect(0, 0, 100, 200, SkPaint());
        canvas.drawBitmap(bitmap, 10, 10, nullptr);
    });
    OpReorderer reorderer;
    reorderer.defer(200, 200, *dl);

    Info info;
    reorderer.replayBakedOps<TestReceiver<SimpleReceiver, Info>>(info);
    EXPECT_EQ(4, info.index); // 2 ops + start + end
}


TEST(OpReorderer, simpleRejection) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.clipRect(200, 200, 400, 400, SkRegion::kIntersect_Op); // intersection should be empty
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();
    });
    OpReorderer reorderer;
    reorderer.defer(200, 200, *dl);

    Info info;
    reorderer.replayBakedOps<TestReceiver<FailReceiver, Info>>(info);
}


static int SIMPLE_BATCHING_LOOPS = 5;
class SimpleBatchingReceiver : public TestReceiver<SimpleBatchingReceiver, Info>::Client {
public:
    void onBitmapOp(Info& info, const BitmapOp& op, const BakedOpState& state) override {
        EXPECT_TRUE(info.index++ >= SIMPLE_BATCHING_LOOPS);
    }
    void onRectOp(Info& info, const RectOp& op, const BakedOpState& state) override {
        EXPECT_TRUE(info.index++ < SIMPLE_BATCHING_LOOPS);
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

    OpReorderer reorderer;
    reorderer.defer(200, 200, *dl);

    Info info;
    reorderer.replayBakedOps<TestReceiver<SimpleBatchingReceiver, Info>>(info);
    EXPECT_EQ(2 * SIMPLE_BATCHING_LOOPS, info.index); // 2 x loops ops, because no merging (TODO: force no merging)
}

class RenderNodeReceiver : public TestReceiver<RenderNodeReceiver, Info>::Client {
public:
    void onRectOp(Info& info, const RectOp& op, const BakedOpState& state) override {
        switch(info.index++) {
        case 0:
            EXPECT_EQ(Rect(0, 0, 200, 200), state.computedState.clippedBounds);
            EXPECT_EQ(SK_ColorDKGRAY, op.paint->getColor());
            break;
        case 1:
            EXPECT_EQ(Rect(50, 50, 150, 150), state.computedState.clippedBounds);
            EXPECT_EQ(SK_ColorWHITE, op.paint->getColor());
            break;
        default:
            FAIL();
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

    OpReorderer reorderer;
    reorderer.defer(SkRect::MakeWH(200, 200), 200, 200, nodes);

    Info info;
    reorderer.replayBakedOps<TestReceiver<RenderNodeReceiver, Info>>(info);
}

class ClippedReceiver : public TestReceiver<ClippedReceiver, Info>::Client {
public:
    void onBitmapOp(Info& info, const BitmapOp& op, const BakedOpState& state) override {
        EXPECT_EQ(0, info.index++);
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

    OpReorderer reorderer;
    reorderer.defer(SkRect::MakeLTRB(10, 20, 30, 40), // clip to small area, should see in receiver
            200, 200, nodes);

    Info info;
    reorderer.replayBakedOps<TestReceiver<ClippedReceiver, Info>>(info);
}


class SaveLayerSimpleReceiver : public TestReceiver<SaveLayerSimpleReceiver, Info>::Client {
public:
    void onRectOp(Info& info, const RectOp& op, const BakedOpState& state) override {
        EXPECT_EQ(0, info.index++);
        EXPECT_EQ(Rect(10, 10, 190, 190), op.unmappedBounds);
        EXPECT_EQ(Rect(0, 0, 180, 180), state.computedState.clippedBounds);
        EXPECT_EQ(Rect(0, 0, 180, 180), state.computedState.clipRect);

        Matrix4 expectedTransform;
        expectedTransform.loadTranslate(-10, -10, 0);
        EXPECT_MATRIX_APPROX_EQ(expectedTransform, state.computedState.transform);
    }
    void onLayerOp(Info& info, const LayerOp& op, const BakedOpState& state) override {
        EXPECT_EQ(1, info.index++);
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

    OpReorderer reorderer;
    reorderer.defer(200, 200, *dl);

    Info info;
    reorderer.replayBakedOps<TestReceiver<SaveLayerSimpleReceiver, Info>>(info);
    EXPECT_EQ(2, info.index);
}


// saveLayer1 {rect1, saveLayer2 { rect2 } } will play back as rect2, rect1, layerOp2, layerOp1
class SaveLayerNestedReceiver : public TestReceiver<SaveLayerNestedReceiver, Info>::Client {
public:
    void onRectOp(Info& info, const RectOp& op, const BakedOpState& state) override {
        const int index = info.index++;
        if (index == 0) {
            EXPECT_EQ(Rect(0, 0, 400, 400), op.unmappedBounds); // inner rect
        } else if (index == 1) {
            EXPECT_EQ(Rect(0, 0, 800, 800), op.unmappedBounds); // outer rect
        } else { FAIL(); }
    }
    void onLayerOp(Info& info, const LayerOp& op, const BakedOpState& state) override {
        const int index = info.index++;
        if (index == 2) {
            EXPECT_EQ(Rect(0, 0, 400, 400), op.unmappedBounds); // inner layer
        } else if (index == 3) {
            EXPECT_EQ(Rect(0, 0, 800, 800), op.unmappedBounds); // outer layer
        } else { FAIL(); }
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

    OpReorderer reorderer;
    reorderer.defer(800, 800, *dl);

    Info info;
    reorderer.replayBakedOps<TestReceiver<SaveLayerNestedReceiver, Info>>(info);
    EXPECT_EQ(4, info.index);
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
    OpReorderer reorderer;
    reorderer.defer(200, 200, *dl);
    Info info;

    // should see no ops, even within the layer, since the layer should be rejected
    reorderer.replayBakedOps<TestReceiver<FailReceiver, Info>>(info);
}

} // namespace uirenderer
} // namespace android
