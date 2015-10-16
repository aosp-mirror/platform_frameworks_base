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

#define UNSUPPORTED_OP(Info, Type) \
        static void on##Type(Info*, const Type&, const BakedOpState&) { FAIL(); }

class Info {
public:
    int index = 0;
};

class SimpleReceiver {
public:
    static void onBitmapOp(Info* info, const BitmapOp& op, const BakedOpState& state) {
        EXPECT_EQ(1, info->index++);
    }
    static void onRectOp(Info* info, const RectOp& op, const BakedOpState& state) {
        EXPECT_EQ(0, info->index++);
    }
    UNSUPPORTED_OP(Info, RenderNodeOp)
    UNSUPPORTED_OP(Info, SimpleRectsOp)
    static void startFrame(Info& info) {}
    static void endFrame(Info& info) {}
};
TEST(OpReorderer, simple) {
    auto dld = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(25, 25);
        canvas.drawRect(0, 0, 100, 200, SkPaint());
        canvas.drawBitmap(bitmap, 10, 10, nullptr);
    });

    OpReorderer reorderer;
    reorderer.defer(200, 200, dld->getChunks(), dld->getOps());

    Info info;
    reorderer.replayBakedOps<SimpleReceiver>(&info);
}


static int SIMPLE_BATCHING_LOOPS = 5;
class SimpleBatchingReceiver {
public:
    static void onBitmapOp(Info* info, const BitmapOp& op, const BakedOpState& state) {
        EXPECT_TRUE(info->index++ >= SIMPLE_BATCHING_LOOPS);
    }
    static void onRectOp(Info* info, const RectOp& op, const BakedOpState& state) {
        EXPECT_TRUE(info->index++ < SIMPLE_BATCHING_LOOPS);
    }
    UNSUPPORTED_OP(Info, RenderNodeOp)
    UNSUPPORTED_OP(Info, SimpleRectsOp)
    static void startFrame(Info& info) {}
    static void endFrame(Info& info) {}
};
TEST(OpReorderer, simpleBatching) {
    auto dld = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
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
    reorderer.defer(200, 200, dld->getChunks(), dld->getOps());

    Info info;
    reorderer.replayBakedOps<SimpleBatchingReceiver>(&info);
    EXPECT_EQ(2 * SIMPLE_BATCHING_LOOPS, info.index); // 2 x loops ops, because no merging (TODO: force no merging)
}

class RenderNodeReceiver {
public:
    UNSUPPORTED_OP(Info, BitmapOp)
    static void onRectOp(Info* info, const RectOp& op, const BakedOpState& state) {
        switch(info->index++) {
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
    UNSUPPORTED_OP(Info, RenderNodeOp)
    UNSUPPORTED_OP(Info, SimpleRectsOp)
    static void startFrame(Info& info) {}
    static void endFrame(Info& info) {}
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
    reorderer.replayBakedOps<RenderNodeReceiver>(&info);
}

class ClippedReceiver {
public:
    static void onBitmapOp(Info* info, const BitmapOp& op, const BakedOpState& state) {
        EXPECT_EQ(0, info->index++);
        EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clippedBounds);
        EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clipRect);
        EXPECT_TRUE(state.computedState.transform.isIdentity());
    }
    UNSUPPORTED_OP(Info, RectOp)
    UNSUPPORTED_OP(Info, RenderNodeOp)
    UNSUPPORTED_OP(Info, SimpleRectsOp)
    static void startFrame(Info& info) {}
    static void endFrame(Info& info) {}
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
    reorderer.replayBakedOps<ClippedReceiver>(&info);
}

}
}
