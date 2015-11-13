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
#include <renderthread/CanvasContext.h> // todo: remove
#include <unit_tests/TestUtils.h>

#include <unordered_map>

namespace android {
namespace uirenderer {

LayerUpdateQueue sEmptyLayerUpdateQueue;
Vector3 sLightCenter = {100, 100, 100};

static std::vector<sp<RenderNode>> createSyncedNodeList(sp<RenderNode>& node) {
    TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    std::vector<sp<RenderNode>> vec;
    vec.emplace_back(node);
    return vec;
}

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
        ADD_FAILURE() << "Layer creation not expected in this test";
        return nullptr;
    }
    virtual void startRepaintLayer(OffscreenBuffer*, const Rect& repaintRect) {
        ADD_FAILURE() << "Layer repaint not expected in this test";
    }
    virtual void endLayer() {
        ADD_FAILURE() << "Layer updates not expected in this test";
    }
    virtual void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) {}
    virtual void endFrame() {}

    // define virtual defaults for direct
#define BASE_OP_METHOD(Type) \
    virtual void on##Type(const Type&, const BakedOpState&) { \
        ADD_FAILURE() << #Type " not expected in this test"; \
    }
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

TEST(OpReorderer, simple) {
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
        void endFrame() override {
            EXPECT_EQ(3, mIndex++);
        }
    };

    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(25, 25);
        canvas.drawRect(0, 0, 100, 200, SkPaint());
        canvas.drawBitmap(bitmap, 10, 10, nullptr);
    });
    OpReorderer reorderer(100, 200, *dl, sLightCenter);

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
    OpReorderer reorderer(200, 200, *dl, sLightCenter);

    FailRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
}

TEST(OpReorderer, simpleBatching) {
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

    OpReorderer reorderer(200, 200, *dl, sLightCenter);

    SimpleBatchingTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2 * SIMPLE_BATCHING_LOOPS, renderer.getIndex()); // 2 x loops ops, because no merging (TODO: force no merging)
}

TEST(OpReorderer, renderNode) {
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

    OpReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            createSyncedNodeList(parent), sLightCenter);

    RenderNodeTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
}

TEST(OpReorderer, clipped) {
    class ClippedTestRenderer : public TestRendererBase {
    public:
        void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clipRect);
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
    };

    sp<RenderNode> node = TestUtils::createNode<RecordingCanvas>(0, 0, 200, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(200, 200);
        canvas.drawBitmap(bitmap, 0, 0, nullptr);
    });

    OpReorderer reorderer(sEmptyLayerUpdateQueue,
            SkRect::MakeLTRB(10, 20, 30, 40), // clip to small area, should see in receiver
            200, 200, createSyncedNodeList(node), sLightCenter);

    ClippedTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
}

TEST(OpReorderer, saveLayerSimple) {
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

    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, SkCanvas::kClipToLayer_SaveFlag);
        canvas.drawRect(10, 10, 190, 190, SkPaint());
        canvas.restore();
    });

    OpReorderer reorderer(200, 200, *dl, sLightCenter);

    SaveLayerSimpleTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

TEST(OpReorderer, saveLayerNested) {
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

    OpReorderer reorderer(800, 800, *dl, sLightCenter);

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
    OpReorderer reorderer(200, 200, *dl, sLightCenter);

    FailRenderer renderer;
    // should see no ops, even within the layer, since the layer should be rejected
    reorderer.replayBakedOps<TestDispatcher>(renderer);
}

RENDERTHREAD_TEST(OpReorderer, hwLayerSimple) {
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

            EXPECT_EQ(state.computedState.clipRect, Rect(25, 25, 75, 75))
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
        void endFrame() override {
            EXPECT_EQ(5, mIndex++);
        }
    };

    sp<RenderNode> node = TestUtils::createNode<RecordingCanvas>(10, 10, 110, 110, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    }, TestUtils::getHwLayerSetupCallback());
    OffscreenBuffer** layerHandle = node->getLayerHandle();

    // create RenderNode's layer here in same way prepareTree would
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    *layerHandle = &layer;

    auto syncedNodeList = createSyncedNodeList(node);

    // only enqueue partial damage
    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(node.get(), Rect(25, 25, 75, 75));

    OpReorderer reorderer(layerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            syncedNodeList, sLightCenter);
    HwLayerSimpleTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(6, renderer.getIndex());

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

RENDERTHREAD_TEST(OpReorderer, hwLayerComplex) {
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
        void endFrame() override {
            EXPECT_EQ(12, mIndex++);
        }
    };

    auto child = TestUtils::createNode<RecordingCanvas>(50, 50, 150, 150,
            [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    }, TestUtils::getHwLayerSetupCallback());
    OffscreenBuffer childLayer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    *(child->getLayerHandle()) = &childLayer;

    RenderNode* childPtr = child.get();
    auto parent = TestUtils::createNode<RecordingCanvas>(0, 0, 200, 200,
            [childPtr](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorDKGRAY);
        canvas.drawRect(0, 0, 200, 200, paint);

        canvas.saveLayerAlpha(50, 50, 150, 150, 128, SkCanvas::kClipToLayer_SaveFlag);
        canvas.drawRenderNode(childPtr);
        canvas.restore();
    }, TestUtils::getHwLayerSetupCallback());
    OffscreenBuffer parentLayer(renderThread.renderState(), Caches::getInstance(), 200, 200);
    *(parent->getLayerHandle()) = &parentLayer;

    auto syncedList = createSyncedNodeList(parent);

    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(child.get(), Rect(100, 100));
    layerUpdateQueue.enqueueLayerWithDamage(parent.get(), Rect(200, 200));

    OpReorderer reorderer(layerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            syncedList, sLightCenter);

    HwLayerComplexTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(13, renderer.getIndex());

    // clean up layer pointers, so we can safely destruct RenderNodes
    *(child->getLayerHandle()) = nullptr;
    *(parent->getLayerHandle()) = nullptr;
}

static void drawOrderedRect(RecordingCanvas* canvas, uint8_t expectedDrawOrder) {
    SkPaint paint;
    paint.setColor(SkColorSetARGB(256, 0, 0, expectedDrawOrder)); // order put in blue channel
    canvas->drawRect(0, 0, 100, 100, paint);
}
static void drawOrderedNode(RecordingCanvas* canvas, uint8_t expectedDrawOrder, float z) {
    auto node = TestUtils::createNode<RecordingCanvas>(0, 0, 100, 100,
            [expectedDrawOrder](RecordingCanvas& canvas) {
        drawOrderedRect(&canvas, expectedDrawOrder);
    });
    node->mutateStagingProperties().setTranslationZ(z);
    node->setPropertyFieldsDirty(RenderNode::TRANSLATION_Z);
    canvas->drawRenderNode(node.get()); // canvas takes reference/sole ownership
}
TEST(OpReorderer, zReorder) {
    class ZReorderTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            int expectedOrder = SkColorGetB(op.paint->getColor()); // extract order from blue channel
            EXPECT_EQ(expectedOrder, mIndex++) << "An op was drawn out of order";
        }
    };

    auto parent = TestUtils::createNode<RecordingCanvas>(0, 0, 100, 100,
            [](RecordingCanvas& canvas) {
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
    OpReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 100), 100, 100,
            createSyncedNodeList(parent), sLightCenter);

    ZReorderTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(10, renderer.getIndex());
};

// creates a 100x100 shadow casting node with provided translationZ
static sp<RenderNode> createWhiteRectShadowCaster(float translationZ) {
    return TestUtils::createNode<RecordingCanvas>(0, 0, 100, 100,
            [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    }, [translationZ] (RenderProperties& properties) {
        properties.setTranslationZ(translationZ);
        properties.mutableOutline().setRoundRect(0, 0, 100, 100, 0.0f, 1.0f);
        return RenderNode::GENERIC | RenderNode::TRANSLATION_Z;
    });
}

TEST(OpReorderer, shadow) {
    class ShadowTestRenderer : public TestRendererBase {
    public:
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_FLOAT_EQ(1.0f, op.casterAlpha);
            EXPECT_TRUE(op.casterPath->isRect(nullptr));
            EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), op.shadowMatrixXY);

            Matrix4 expectedZ;
            expectedZ.loadTranslate(0, 0, 5);
            EXPECT_MATRIX_APPROX_EQ(expectedZ, op.shadowMatrixZ);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
        }
    };

    sp<RenderNode> parent = TestUtils::createNode<RecordingCanvas>(0, 0, 200, 200,
            [] (RecordingCanvas& canvas) {
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
    });

    OpReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            createSyncedNodeList(parent), sLightCenter);

    ShadowTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex());
}

TEST(OpReorderer, shadowSaveLayer) {
    class ShadowSaveLayerTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            EXPECT_EQ(0, mIndex++);
            return nullptr;
        }
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_FLOAT_EQ(50, op.lightCenter.x);
            EXPECT_FLOAT_EQ(40, op.lightCenter.y);
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

    sp<RenderNode> parent = TestUtils::createNode<RecordingCanvas>(0, 0, 200, 200,
            [] (RecordingCanvas& canvas) {
        // save/restore outside of reorderBarrier, so they don't get moved out of place
        canvas.translate(20, 10);
        int count = canvas.saveLayerAlpha(30, 50, 130, 150, 128, SkCanvas::kClipToLayer_SaveFlag);
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.insertReorderBarrier(false);
        canvas.restoreToCount(count);
    });

    OpReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            createSyncedNodeList(parent), (Vector3) { 100, 100, 100 });

    ShadowSaveLayerTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(5, renderer.getIndex());
}

RENDERTHREAD_TEST(OpReorderer, shadowHwLayer) {
    class ShadowHwLayerTestRenderer : public TestRendererBase {
    public:
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
        }
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_FLOAT_EQ(50, op.lightCenter.x);
            EXPECT_FLOAT_EQ(40, op.lightCenter.y);
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

    sp<RenderNode> parent = TestUtils::createNode<RecordingCanvas>(50, 60, 150, 160,
            [] (RecordingCanvas& canvas) {
        canvas.insertReorderBarrier(true);
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(20, 10);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.restore();
    }, TestUtils::getHwLayerSetupCallback());
    OffscreenBuffer** layerHandle = parent->getLayerHandle();

    // create RenderNode's layer here in same way prepareTree would, setting windowTransform
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    Matrix4 windowTransform;
    windowTransform.loadTranslate(50, 60, 0); // total transform of layer's origin
    layer.setWindowTransform(windowTransform);
    *layerHandle = &layer;

    auto syncedList = createSyncedNodeList(parent);
    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(parent.get(), Rect(100, 100));
    OpReorderer reorderer(layerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            syncedList, (Vector3) { 100, 100, 100 });

    ShadowHwLayerTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(5, renderer.getIndex());

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

TEST(OpReorderer, shadowLayering) {
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
    sp<RenderNode> parent = TestUtils::createNode<RecordingCanvas>(0, 0, 200, 200,
            [] (RecordingCanvas& canvas) {
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0001f).get());
    });

    OpReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            createSyncedNodeList(parent), sLightCenter);

    ShadowLayeringTestRenderer renderer;
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}


static void testProperty(TestUtils::PropSetupCallback propSetupCallback,
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

    auto node = TestUtils::createNode<RecordingCanvas>(0, 0, 100, 100, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    }, propSetupCallback);

    OpReorderer reorderer(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 100), 200, 200,
            createSyncedNodeList(node), sLightCenter);

    PropertyTestRenderer renderer(opValidateCallback);
    reorderer.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex()) << "Should have seen one op";
}

TEST(OpReorderer, renderPropOverlappingRenderingAlpha) {
    testProperty([](RenderProperties& properties) {
        properties.setAlpha(0.5f);
        properties.setHasOverlappingRendering(false);
        return RenderNode::ALPHA | RenderNode::GENERIC;
    }, [](const RectOp& op, const BakedOpState& state) {
        EXPECT_EQ(0.5f, state.alpha) << "Alpha should be applied directly to op";
    });
}

TEST(OpReorderer, renderPropClipping) {
    testProperty([](RenderProperties& properties) {
        properties.setClipToBounds(true);
        properties.setClipBounds(Rect(10, 20, 300, 400));
        return RenderNode::GENERIC;
    }, [](const RectOp& op, const BakedOpState& state) {
        EXPECT_EQ(Rect(10, 20, 100, 100), state.computedState.clippedBounds)
                << "Clip rect should be intersection of node bounds and clip bounds";
    });
}

TEST(OpReorderer, renderPropRevealClip) {
    testProperty([](RenderProperties& properties) {
        properties.mutableRevealClip().set(true, 50, 50, 25);
        return RenderNode::GENERIC;
    }, [](const RectOp& op, const BakedOpState& state) {
        ASSERT_NE(nullptr, state.roundRectClipState);
        EXPECT_TRUE(state.roundRectClipState->highPriority);
        EXPECT_EQ(25, state.roundRectClipState->radius);
        EXPECT_EQ(Rect(50, 50, 50, 50), state.roundRectClipState->innerRect);
    });
}

TEST(OpReorderer, renderPropOutlineClip) {
    testProperty([](RenderProperties& properties) {
        properties.mutableOutline().setShouldClip(true);
        properties.mutableOutline().setRoundRect(10, 20, 30, 40, 5.0f, 0.5f);
        return RenderNode::GENERIC;
    }, [](const RectOp& op, const BakedOpState& state) {
        ASSERT_NE(nullptr, state.roundRectClipState);
        EXPECT_FALSE(state.roundRectClipState->highPriority);
        EXPECT_EQ(5, state.roundRectClipState->radius);
        EXPECT_EQ(Rect(15, 25, 25, 35), state.roundRectClipState->innerRect);
    });
}

TEST(OpReorderer, renderPropTransform) {
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
        return RenderNode::GENERIC
                | RenderNode::TRANSLATION_X | RenderNode::TRANSLATION_Y
                | RenderNode::SCALE_X | RenderNode::SCALE_Y;
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

} // namespace uirenderer
} // namespace android
