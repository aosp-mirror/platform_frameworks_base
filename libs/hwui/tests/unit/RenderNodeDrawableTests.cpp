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

#include <VectorDrawable.h>
#include <gtest/gtest.h>

#include <SkClipStack.h>
#include <SkSurface_Base.h>
#include <string.h>
#include "AnimationContext.h"
#include "DamageAccumulator.h"
#include "FatalTestCanvas.h"
#include "IContextFactory.h"
#include "hwui/Paint.h"
#include "RecordingCanvas.h"
#include "SkiaCanvas.h"
#include "pipeline/skia/SkiaDisplayList.h"
#include "pipeline/skia/SkiaOpenGLPipeline.h"
#include "pipeline/skia/SkiaPipeline.h"
#include "pipeline/skia/SkiaRecordingCanvas.h"
#include "renderthread/CanvasContext.h"
#include "tests/common/TestUtils.h"
#include "utils/Color.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::skiapipeline;

TEST(RenderNodeDrawable, create) {
    auto rootNode =
            TestUtils::createNode(0, 0, 200, 400, [](RenderProperties& props, Canvas& canvas) {
                canvas.drawColor(Color::Red_500, SkBlendMode::kSrcOver);
            });

    DisplayListData skLiteDL;
    RecordingCanvas canvas;
    canvas.reset(&skLiteDL, SkIRect::MakeWH(1, 1));
    canvas.translate(100, 100);
    RenderNodeDrawable drawable(rootNode.get(), &canvas);

    ASSERT_EQ(drawable.getRenderNode(), rootNode.get());
    ASSERT_EQ(&drawable.getNodeProperties(), &rootNode->properties());
    ASSERT_EQ(drawable.getRecordedMatrix(), canvas.getTotalMatrix());
}

namespace {

static void drawOrderedRect(Canvas* canvas, uint8_t expectedDrawOrder) {
    Paint paint;
    // order put in blue channel, transparent so overlapped content doesn't get rejected
    paint.setColor(SkColorSetARGB(1, 0, 0, expectedDrawOrder));
    canvas->drawRect(0, 0, 100, 100, paint);
}

static void drawOrderedNode(Canvas* canvas, uint8_t expectedDrawOrder, float z) {
    auto node = TestUtils::createSkiaNode(
            0, 0, 100, 100,
            [expectedDrawOrder, z](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                drawOrderedRect(&canvas, expectedDrawOrder);
                props.setTranslationZ(z);
            });
    canvas->drawRenderNode(node.get());  // canvas takes reference/sole ownership
}

static void drawOrderedNode(
        Canvas* canvas, uint8_t expectedDrawOrder,
        std::function<void(RenderProperties& props, SkiaRecordingCanvas& canvas)> setup) {
    auto node = TestUtils::createSkiaNode(
            0, 0, 100, 100,
            [expectedDrawOrder, setup](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                drawOrderedRect(&canvas, expectedDrawOrder);
                if (setup) {
                    setup(props, canvas);
                }
            });
    canvas->drawRenderNode(node.get());  // canvas takes reference/sole ownership
}

class ZReorderCanvas : public SkCanvas {
public:
    ZReorderCanvas(int width, int height) : SkCanvas(width, height) {}
    void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
        int expectedOrder = SkColorGetB(paint.getColor());  // extract order from blue channel
        EXPECT_EQ(expectedOrder, mDrawCounter++) << "An op was drawn out of order";
    }
    int getIndex() { return mDrawCounter; }

protected:
    int mDrawCounter = 0;
};

}  // end anonymous namespace

TEST(RenderNodeDrawable, zReorder) {
    auto parent = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                               SkiaRecordingCanvas& canvas) {
        canvas.insertReorderBarrier(true);
        canvas.insertReorderBarrier(false);
        drawOrderedNode(&canvas, 0, 10.0f);  // in reorder=false at this point, so played inorder
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
        drawOrderedNode(&canvas, 9, -10.0f);  // in reorder=false at this point, so played inorder
        canvas.insertReorderBarrier(true);    // reorder a node ahead of drawrect op
        drawOrderedRect(&canvas, 11);
        drawOrderedNode(&canvas, 10, -1.0f);
        canvas.insertReorderBarrier(false);
        canvas.insertReorderBarrier(true);  // test with two empty reorder sections
        canvas.insertReorderBarrier(true);
        canvas.insertReorderBarrier(false);
        drawOrderedRect(&canvas, 12);
    });

    // create a canvas not backed by any device/pixels, but with dimensions to avoid quick rejection
    ZReorderCanvas canvas(100, 100);
    RenderNodeDrawable drawable(parent.get(), &canvas, false);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(13, canvas.getIndex());
}

TEST(RenderNodeDrawable, composeOnLayer) {
    auto surface = SkSurface::MakeRasterN32Premul(1, 1);
    SkCanvas& canvas = *surface->getCanvas();
    canvas.drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);

    auto rootNode = TestUtils::createSkiaNode(
            0, 0, 1, 1, [](RenderProperties& props, SkiaRecordingCanvas& recorder) {
                recorder.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
            });

    // attach a layer to the render node
    auto surfaceLayer = SkSurface::MakeRasterN32Premul(1, 1);
    auto canvas2 = surfaceLayer->getCanvas();
    canvas2->drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
    rootNode->setLayerSurface(surfaceLayer);

    RenderNodeDrawable drawable1(rootNode.get(), &canvas, false);
    canvas.drawDrawable(&drawable1);
    ASSERT_EQ(SK_ColorRED, TestUtils::getColor(surface, 0, 0));

    RenderNodeDrawable drawable2(rootNode.get(), &canvas, true);
    canvas.drawDrawable(&drawable2);
    ASSERT_EQ(SK_ColorWHITE, TestUtils::getColor(surface, 0, 0));

    RenderNodeDrawable drawable3(rootNode.get(), &canvas, false);
    canvas.drawDrawable(&drawable3);
    ASSERT_EQ(SK_ColorRED, TestUtils::getColor(surface, 0, 0));

    rootNode->setLayerSurface(sk_sp<SkSurface>());
}

namespace {
static SkRect getRecorderClipBounds(const SkiaRecordingCanvas& recorder) {
    SkRect clipBounds;
    recorder.getClipBounds(&clipBounds);
    return clipBounds;
}

static SkMatrix getRecorderMatrix(const SkiaRecordingCanvas& recorder) {
    SkMatrix matrix;
    recorder.getMatrix(&matrix);
    return matrix;
}
}

TEST(RenderNodeDrawable, saveLayerClipAndMatrixRestore) {
    auto surface = SkSurface::MakeRasterN32Premul(400, 800);
    SkCanvas& canvas = *surface->getCanvas();
    canvas.drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorWHITE);

    auto rootNode = TestUtils::createSkiaNode(
            0, 0, 400, 800, [](RenderProperties& props, SkiaRecordingCanvas& recorder) {
                SkPaint layerPaint;
                ASSERT_EQ(SkRect::MakeLTRB(0, 0, 400, 800), getRecorderClipBounds(recorder));
                EXPECT_TRUE(getRecorderMatrix(recorder).isIdentity());

                // note we don't pass SaveFlags::MatrixClip, but matrix and clip will be saved
                recorder.saveLayer(0, 0, 400, 400, &layerPaint, SaveFlags::ClipToLayer);
                ASSERT_EQ(SkRect::MakeLTRB(0, 0, 400, 400), getRecorderClipBounds(recorder));
                EXPECT_TRUE(getRecorderMatrix(recorder).isIdentity());

                recorder.clipRect(50, 50, 350, 350, SkClipOp::kIntersect);
                ASSERT_EQ(SkRect::MakeLTRB(50, 50, 350, 350), getRecorderClipBounds(recorder));

                recorder.translate(300.0f, 400.0f);
                EXPECT_EQ(SkMatrix::MakeTrans(300.0f, 400.0f), getRecorderMatrix(recorder));

                recorder.restore();
                ASSERT_EQ(SkRect::MakeLTRB(0, 0, 400, 800), getRecorderClipBounds(recorder));
                EXPECT_TRUE(getRecorderMatrix(recorder).isIdentity());

                Paint paint;
                paint.setAntiAlias(true);
                paint.setColor(SK_ColorGREEN);
                recorder.drawRect(0.0f, 400.0f, 400.0f, 800.0f, paint);
            });

    RenderNodeDrawable drawable(rootNode.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    ASSERT_EQ(SK_ColorGREEN, TestUtils::getColor(surface, 200, 600));
}

namespace {
class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) override {
        return new AnimationContext(clock);
    }
};
}  // end anonymous namespace

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorder) {
    static const int SCROLL_X = 5;
    static const int SCROLL_Y = 10;
    class ProjectionTestCanvas : public SkCanvas {
    public:
        ProjectionTestCanvas(int width, int height) : SkCanvas(width, height) {}
        void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
            const int index = mDrawCounter++;
            SkMatrix expectedMatrix;
            ;
            switch (index) {
                case 0:  // this is node "B"
                    EXPECT_EQ(SkRect::MakeWH(100, 100), rect);
                    EXPECT_EQ(SK_ColorWHITE, paint.getColor());
                    expectedMatrix.reset();
                    EXPECT_EQ(SkRect::MakeLTRB(0, 0, 100, 100), TestUtils::getClipBounds(this));
                    break;
                case 1:  // this is node "P"
                    EXPECT_EQ(SkRect::MakeLTRB(-10, -10, 60, 60), rect);
                    EXPECT_EQ(SK_ColorDKGRAY, paint.getColor());
                    expectedMatrix.setTranslate(50 - SCROLL_X, 50 - SCROLL_Y);
                    EXPECT_EQ(SkRect::MakeLTRB(-35, -30, 45, 50),
                              TestUtils::getLocalClipBounds(this));
                    break;
                case 2:  // this is node "C"
                    EXPECT_EQ(SkRect::MakeWH(100, 50), rect);
                    EXPECT_EQ(SK_ColorBLUE, paint.getColor());
                    expectedMatrix.setTranslate(-SCROLL_X, 50 - SCROLL_Y);
                    EXPECT_EQ(SkRect::MakeLTRB(0, 40, 95, 90), TestUtils::getClipBounds(this));
                    break;
                default:
                    ADD_FAILURE();
            }
            EXPECT_EQ(expectedMatrix, getTotalMatrix());
        }

        int getIndex() { return mDrawCounter; }

    protected:
        int mDrawCounter = 0;
    };

    /**
     * Construct a tree of nodes, where the root (A) has a receiver background (B), and a child (C)
     * with a projecting child (P) of its own. P would normally draw between B and C's "background"
     * draw, but because it is projected backwards, it's drawn in between B and C.
     *
     * The parent is scrolled by SCROLL_X/SCROLL_Y, but this does not affect the background
     * (which isn't affected by scroll).
     */
    auto receiverBackground = TestUtils::createSkiaNode(
            0, 0, 100, 100,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                properties.setProjectionReceiver(true);
                // scroll doesn't apply to background, so undone via translationX/Y
                // NOTE: translationX/Y only! no other transform properties may be set for a proj
                // receiver!
                properties.setTranslationX(SCROLL_X);
                properties.setTranslationY(SCROLL_Y);

                Paint paint;
                paint.setColor(SK_ColorWHITE);
                canvas.drawRect(0, 0, 100, 100, paint);
            },
            "B");

    auto projectingRipple = TestUtils::createSkiaNode(
            50, 0, 100, 50,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                properties.setProjectBackwards(true);
                properties.setClipToBounds(false);
                Paint paint;
                paint.setColor(SK_ColorDKGRAY);
                canvas.drawRect(-10, -10, 60, 60, paint);
            },
            "P");
    auto child = TestUtils::createSkiaNode(
            0, 50, 100, 100,
            [&projectingRipple](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                Paint paint;
                paint.setColor(SK_ColorBLUE);
                canvas.drawRect(0, 0, 100, 50, paint);
                canvas.drawRenderNode(projectingRipple.get());
            },
            "C");
    auto parent = TestUtils::createSkiaNode(
            0, 0, 100, 100,
            [&receiverBackground, &child](RenderProperties& properties,
                                          SkiaRecordingCanvas& canvas) {
                // Set a rect outline for the projecting ripple to be masked against.
                properties.mutableOutline().setRoundRect(10, 10, 90, 90, 5, 1.0f);

                canvas.save(SaveFlags::MatrixClip);
                canvas.translate(-SCROLL_X,
                                 -SCROLL_Y);  // Apply scroll (note: bg undoes this internally)
                canvas.drawRenderNode(receiverBackground.get());
                canvas.drawRenderNode(child.get());
                canvas.restore();
            },
            "A");
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, parent.get(), &contextFactory));
    TreeInfo info(TreeInfo::MODE_RT_ONLY, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;
    parent->prepareTree(info);

    // parent(A)             -> (receiverBackground, child)
    // child(C)              -> (rect[0, 0, 100, 50], projectingRipple)
    // projectingRipple(P)   -> (rect[-10, -10, 60, 60]) -> projects backwards
    // receiverBackground(B) -> (rect[0, 0, 100, 100]) -> projection receiver

    // create a canvas not backed by any device/pixels, but with dimensions to avoid quick rejection
    ProjectionTestCanvas canvas(100, 100);
    RenderNodeDrawable drawable(parent.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(3, canvas.getIndex());
}

RENDERTHREAD_SKIA_PIPELINE_TEST(RenderNodeDrawable, emptyReceiver) {
    class ProjectionTestCanvas : public SkCanvas {
    public:
        ProjectionTestCanvas(int width, int height) : SkCanvas(width, height) {}
        void onDrawRect(const SkRect& rect, const SkPaint& paint) override { mDrawCounter++; }

        int getDrawCounter() { return mDrawCounter; }

    private:
        int mDrawCounter = 0;
    };

    auto receiverBackground = TestUtils::createSkiaNode(
            0, 0, 100, 100,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                properties.setProjectionReceiver(true);
            },
            "B");  // a receiver with an empty display list

    auto projectingRipple = TestUtils::createSkiaNode(
            0, 0, 100, 100,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                properties.setProjectBackwards(true);
                properties.setClipToBounds(false);
                Paint paint;
                canvas.drawRect(0, 0, 100, 100, paint);
            },
            "P");
    auto child = TestUtils::createSkiaNode(
            0, 0, 100, 100,
            [&projectingRipple](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                Paint paint;
                canvas.drawRect(0, 0, 100, 100, paint);
                canvas.drawRenderNode(projectingRipple.get());
            },
            "C");
    auto parent =
            TestUtils::createSkiaNode(0, 0, 100, 100,
                                      [&receiverBackground, &child](RenderProperties& properties,
                                                                    SkiaRecordingCanvas& canvas) {
                                          canvas.drawRenderNode(receiverBackground.get());
                                          canvas.drawRenderNode(child.get());
                                      },
                                      "A");
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, parent.get(), &contextFactory));
    TreeInfo info(TreeInfo::MODE_RT_ONLY, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;
    parent->prepareTree(info);

    // parent(A)             -> (receiverBackground, child)
    // child(C)              -> (rect[0, 0, 100, 100], projectingRipple)
    // projectingRipple(P)   -> (rect[0, 0, 100, 100]) -> projects backwards
    // receiverBackground(B) -> (empty) -> projection receiver

    // create a canvas not backed by any device/pixels, but with dimensions to avoid quick rejection
    ProjectionTestCanvas canvas(100, 100);
    RenderNodeDrawable drawable(parent.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(2, canvas.getDrawCounter());
}

RENDERTHREAD_SKIA_PIPELINE_TEST(RenderNodeDrawable, projectionHwLayer) {
    /* R is backward projected on B and C is a layer.
                A
               / \
              B   C
                  |
                  R
    */
    static const int SCROLL_X = 5;
    static const int SCROLL_Y = 10;
    static const int CANVAS_WIDTH = 400;
    static const int CANVAS_HEIGHT = 400;
    static const int LAYER_WIDTH = 200;
    static const int LAYER_HEIGHT = 200;
    class ProjectionTestCanvas : public SkCanvas {
    public:
        ProjectionTestCanvas(int* drawCounter)
                : SkCanvas(CANVAS_WIDTH, CANVAS_HEIGHT), mDrawCounter(drawCounter) {}
        void onDrawArc(const SkRect&, SkScalar startAngle, SkScalar sweepAngle, bool useCenter,
                       const SkPaint&) override {
            EXPECT_EQ(0, (*mDrawCounter)++);  // part of painting the layer
            EXPECT_EQ(SkRect::MakeLTRB(0, 0, LAYER_WIDTH, LAYER_HEIGHT),
                      TestUtils::getClipBounds(this));
        }
        void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
            EXPECT_EQ(1, (*mDrawCounter)++);
            EXPECT_EQ(SkRect::MakeLTRB(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT),
                      TestUtils::getClipBounds(this));
        }
        void onDrawOval(const SkRect&, const SkPaint&) override {
            EXPECT_EQ(2, (*mDrawCounter)++);
            SkMatrix expectedMatrix;
            expectedMatrix.setTranslate(100 - SCROLL_X, 100 - SCROLL_Y);
            EXPECT_EQ(expectedMatrix, getTotalMatrix());
            EXPECT_EQ(SkRect::MakeLTRB(-85, -80, 295, 300), TestUtils::getLocalClipBounds(this));
        }
        int* mDrawCounter;
    };

    class ProjectionLayer : public SkSurface_Base {
    public:
        ProjectionLayer(int* drawCounter)
                : SkSurface_Base(SkImageInfo::MakeN32Premul(LAYER_WIDTH, LAYER_HEIGHT), nullptr)
                , mDrawCounter(drawCounter) {}
        virtual sk_sp<SkImage> onNewImageSnapshot(const SkIRect* bounds) override {
            EXPECT_EQ(3, (*mDrawCounter)++);
            EXPECT_EQ(SkRect::MakeLTRB(100 - SCROLL_X, 100 - SCROLL_Y, 300 - SCROLL_X,
                                       300 - SCROLL_Y),
                      TestUtils::getClipBounds(this->getCanvas()));
            return nullptr;
        }
        SkCanvas* onNewCanvas() override { return new ProjectionTestCanvas(mDrawCounter); }
        sk_sp<SkSurface> onNewSurface(const SkImageInfo&) override { return nullptr; }
        void onCopyOnWrite(ContentChangeMode) override {}
        int* mDrawCounter;
        void onWritePixels(const SkPixmap&, int x, int y) {}
    };

    auto receiverBackground = TestUtils::createSkiaNode(
            0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                properties.setProjectionReceiver(true);
                // scroll doesn't apply to background, so undone via translationX/Y
                // NOTE: translationX/Y only! no other transform properties may be set for a proj
                // receiver!
                properties.setTranslationX(SCROLL_X);
                properties.setTranslationY(SCROLL_Y);

                canvas.drawRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT, Paint());
            },
            "B");  // B
    auto projectingRipple = TestUtils::createSkiaNode(
            0, 0, LAYER_WIDTH, LAYER_HEIGHT,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                properties.setProjectBackwards(true);
                properties.setClipToBounds(false);
                canvas.drawOval(100, 100, 300, 300, Paint());  // drawn mostly out of layer bounds
            },
            "R");  // R
    auto child = TestUtils::createSkiaNode(
            100, 100, 300, 300,
            [&projectingRipple](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                canvas.drawRenderNode(projectingRipple.get());
                canvas.drawArc(0, 0, LAYER_WIDTH, LAYER_HEIGHT, 0.0f, 280.0f, true, Paint());
            },
            "C");  // C
    auto parent = TestUtils::createSkiaNode(
            0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
            [&receiverBackground, &child](RenderProperties& properties,
                                          SkiaRecordingCanvas& canvas) {
                // Set a rect outline for the projecting ripple to be masked against.
                properties.mutableOutline().setRoundRect(10, 10, 390, 390, 0, 1.0f);
                canvas.translate(-SCROLL_X,
                                 -SCROLL_Y);  // Apply scroll (note: bg undoes this internally)
                canvas.drawRenderNode(receiverBackground.get());
                canvas.drawRenderNode(child.get());
            },
            "A");  // A

    // prepareTree is required to find, which receivers have backward projected nodes
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, parent.get(), &contextFactory));
    TreeInfo info(TreeInfo::MODE_RT_ONLY, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;
    parent->prepareTree(info);

    int drawCounter = 0;
    // set a layer after prepareTree to avoid layer logic there
    child->animatorProperties().mutateLayerProperties().setType(LayerType::RenderLayer);
    sk_sp<SkSurface> surfaceLayer1(new ProjectionLayer(&drawCounter));
    child->setLayerSurface(surfaceLayer1);
    Matrix4 windowTransform;
    windowTransform.loadTranslate(100, 100, 0);
    child->getSkiaLayer()->inverseTransformInWindow.loadInverse(windowTransform);

    LayerUpdateQueue layerUpdateQueue;
    layerUpdateQueue.enqueueLayerWithDamage(child.get(),
                                            android::uirenderer::Rect(LAYER_WIDTH, LAYER_HEIGHT));
    auto pipeline = std::make_unique<SkiaOpenGLPipeline>(renderThread);
    pipeline->renderLayersImpl(layerUpdateQueue, true);
    EXPECT_EQ(1, drawCounter);  // assert index 0 is drawn on the layer

    RenderNodeDrawable drawable(parent.get(), surfaceLayer1->getCanvas(), true);
    surfaceLayer1->getCanvas()->drawDrawable(&drawable);
    EXPECT_EQ(4, drawCounter);

    // clean up layer pointer, so we can safely destruct RenderNode
    child->setLayerSurface(nullptr);
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionChildScroll) {
    /* R is backward projected on B.
                A
               / \
              B   C
                  |
                  R
    */
    static const int SCROLL_X = 500000;
    static const int SCROLL_Y = 0;
    static const int CANVAS_WIDTH = 400;
    static const int CANVAS_HEIGHT = 400;
    class ProjectionChildScrollTestCanvas : public SkCanvas {
    public:
        ProjectionChildScrollTestCanvas() : SkCanvas(CANVAS_WIDTH, CANVAS_HEIGHT) {}
        void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
            EXPECT_EQ(0, mDrawCounter++);
            EXPECT_TRUE(getTotalMatrix().isIdentity());
        }
        void onDrawOval(const SkRect&, const SkPaint&) override {
            EXPECT_EQ(1, mDrawCounter++);
            EXPECT_EQ(SkRect::MakeWH(CANVAS_WIDTH, CANVAS_HEIGHT), TestUtils::getClipBounds(this));
            EXPECT_TRUE(getTotalMatrix().isIdentity());
        }
        int mDrawCounter = 0;
    };

    auto receiverBackground = TestUtils::createSkiaNode(
            0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                properties.setProjectionReceiver(true);
                canvas.drawRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT, Paint());
            },
            "B");  // B
    auto projectingRipple = TestUtils::createSkiaNode(
            0, 0, 200, 200,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                // scroll doesn't apply to background, so undone via translationX/Y
                // NOTE: translationX/Y only! no other transform properties may be set for a proj
                // receiver!
                properties.setTranslationX(SCROLL_X);
                properties.setTranslationY(SCROLL_Y);
                properties.setProjectBackwards(true);
                properties.setClipToBounds(false);
                canvas.drawOval(0, 0, 200, 200, Paint());
            },
            "R");  // R
    auto child = TestUtils::createSkiaNode(
            0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
            [&projectingRipple](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                // Record time clip will be ignored by projectee
                canvas.clipRect(100, 100, 300, 300, SkClipOp::kIntersect);

                canvas.translate(-SCROLL_X,
                                 -SCROLL_Y);  // Apply scroll (note: bg undoes this internally)
                canvas.drawRenderNode(projectingRipple.get());
            },
            "C");  // C
    auto parent =
            TestUtils::createSkiaNode(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
                                      [&receiverBackground, &child](RenderProperties& properties,
                                                                    SkiaRecordingCanvas& canvas) {
                                          canvas.drawRenderNode(receiverBackground.get());
                                          canvas.drawRenderNode(child.get());
                                      },
                                      "A");  // A

    // prepareTree is required to find, which receivers have backward projected nodes
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, parent.get(), &contextFactory));
    TreeInfo info(TreeInfo::MODE_RT_ONLY, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;
    parent->prepareTree(info);

    std::unique_ptr<ProjectionChildScrollTestCanvas> canvas(new ProjectionChildScrollTestCanvas());
    RenderNodeDrawable drawable(parent.get(), canvas.get(), true);
    canvas->drawDrawable(&drawable);
    EXPECT_EQ(2, canvas->mDrawCounter);
}

namespace {
static int drawNode(RenderThread& renderThread, const sp<RenderNode>& renderNode) {
    ContextFactory contextFactory;
    std::unique_ptr<CanvasContext> canvasContext(
            CanvasContext::create(renderThread, false, renderNode.get(), &contextFactory));
    TreeInfo info(TreeInfo::MODE_RT_ONLY, *canvasContext.get());
    DamageAccumulator damageAccumulator;
    info.damageAccumulator = &damageAccumulator;
    renderNode->prepareTree(info);

    // create a canvas not backed by any device/pixels, but with dimensions to avoid quick rejection
    ZReorderCanvas canvas(100, 100);
    RenderNodeDrawable drawable(renderNode.get(), &canvas, false);
    canvas.drawDrawable(&drawable);
    return canvas.getIndex();
}
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderProjectedInMiddle) {
    /* R is backward projected on B
                A
               / \
              B   C
                  |
                  R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            props.setProjectionReceiver(true);
        });  // nodeB
        drawOrderedNode(&canvas, 2, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            drawOrderedNode(&canvas, 1, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                props.setProjectBackwards(true);
                props.setClipToBounds(false);
            });  // nodeR
        });      // nodeC
    });          // nodeA
    EXPECT_EQ(3, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderProjectLast) {
    /* R is backward projected on E
                  A
                / | \
               /  |  \
              B   C   E
                  |
                  R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, nullptr);  // nodeB
        drawOrderedNode(&canvas, 1, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            drawOrderedNode(&canvas, 3, [](RenderProperties& props,
                                           SkiaRecordingCanvas& canvas) {  // drawn as 2
                props.setProjectBackwards(true);
                props.setClipToBounds(false);
            });  // nodeR
        });      // nodeC
        drawOrderedNode(&canvas, 2,
                        [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // drawn as 3
                            props.setProjectionReceiver(true);
                        });  // nodeE
    });                      // nodeA
    EXPECT_EQ(4, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderNoReceivable) {
    /* R is backward projected without receiver
                A
               / \
              B   C
                  |
                  R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, nullptr);  // nodeB
        drawOrderedNode(&canvas, 1, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            drawOrderedNode(&canvas, 255, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                // not having a projection receiver is an undefined behavior
                props.setProjectBackwards(true);
                props.setClipToBounds(false);
            });  // nodeR
        });      // nodeC
    });          // nodeA
    EXPECT_EQ(2, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderParentReceivable) {
    /* R is backward projected on C
                A
               / \
              B   C
                  |
                  R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, nullptr);  // nodeB
        drawOrderedNode(&canvas, 1, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            props.setProjectionReceiver(true);
            drawOrderedNode(&canvas, 2, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                props.setProjectBackwards(true);
                props.setClipToBounds(false);
            });  // nodeR
        });      // nodeC
    });          // nodeA
    EXPECT_EQ(3, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderSameNodeReceivable) {
    /* R is backward projected on R
                A
               / \
              B   C
                  |
                  R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, nullptr);  // nodeB
        drawOrderedNode(&canvas, 1, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            drawOrderedNode(&canvas, 255, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                // having a node that is projected on itself is an undefined/unexpected behavior
                props.setProjectionReceiver(true);
                props.setProjectBackwards(true);
                props.setClipToBounds(false);
            });  // nodeR
        });      // nodeC
    });          // nodeA
    EXPECT_EQ(2, drawNode(renderThread, nodeA));
}

// Note: the outcome for this test is different in HWUI
RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderProjectedSibling) {
    /* R is set to project on B, but R is not drawn because projecting on a sibling is not allowed.
                A
               /|\
              / | \
             B  C  R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            props.setProjectionReceiver(true);
        });  // nodeB
        drawOrderedNode(&canvas, 1,
                        [](RenderProperties& props, SkiaRecordingCanvas& canvas) {});  // nodeC
        drawOrderedNode(&canvas, 255, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            props.setProjectBackwards(true);
            props.setClipToBounds(false);
        });  // nodeR
    });      // nodeA
    EXPECT_EQ(2, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderProjectedSibling2) {
    /* R is set to project on B, but R is not drawn because projecting on a sibling is not allowed.
                A
                |
                G
               /|\
              / | \
             B  C  R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            drawOrderedNode(&canvas, 1, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                props.setProjectionReceiver(true);
            });  // nodeB
            drawOrderedNode(&canvas, 2,
                            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {});  // nodeC
            drawOrderedNode(&canvas, 255, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                props.setProjectBackwards(true);
                props.setClipToBounds(false);
            });  // nodeR
        });      // nodeG
    });          // nodeA
    EXPECT_EQ(3, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderGrandparentReceivable) {
    /* R is backward projected on B
                A
                |
                B
                |
                C
                |
                R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            props.setProjectionReceiver(true);
            drawOrderedNode(&canvas, 1, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                drawOrderedNode(&canvas, 2,
                                [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                                    props.setProjectBackwards(true);
                                    props.setClipToBounds(false);
                                });  // nodeR
            });                      // nodeC
        });                          // nodeB
    });                              // nodeA
    EXPECT_EQ(3, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderTwoReceivables) {
    /* B and G are receivables, R is backward projected
                A
               / \
              B   C
                 / \
                G   R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // B
            props.setProjectionReceiver(true);
        });  // nodeB
        drawOrderedNode(&canvas, 2, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // C
            drawOrderedNode(&canvas, 3,
                            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // G
                                props.setProjectionReceiver(true);
                            });  // nodeG
            drawOrderedNode(&canvas, 1,
                            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // R
                                props.setProjectBackwards(true);
                                props.setClipToBounds(false);
                            });  // nodeR
        });                      // nodeC
    });                          // nodeA
    EXPECT_EQ(4, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderTwoReceivablesLikelyScenario) {
    /* B and G are receivables, G is backward projected
                A
               / \
              B   C
                 / \
                G   R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // B
            props.setProjectionReceiver(true);
        });  // nodeB
        drawOrderedNode(&canvas, 2, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // C
            drawOrderedNode(&canvas, 1,
                            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // G
                                props.setProjectionReceiver(true);
                                props.setProjectBackwards(true);
                                props.setClipToBounds(false);
                            });  // nodeG
            drawOrderedNode(&canvas, 3,
                            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // R
                            });                                                         // nodeR
        });                                                                             // nodeC
    });                                                                                 // nodeA
    EXPECT_EQ(4, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, projectionReorderTwoReceivablesDeeper) {
    /* B and G are receivables, R is backward projected
                A
               / \
              B   C
                 / \
                G   D
                    |
                    R
    */
    auto nodeA = TestUtils::createSkiaNode(0, 0, 100, 100, [](RenderProperties& props,
                                                              SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // B
            props.setProjectionReceiver(true);
        });  // nodeB
        drawOrderedNode(&canvas, 1, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // C
            drawOrderedNode(&canvas, 2,
                            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // G
                                props.setProjectionReceiver(true);
                            });  // nodeG
            drawOrderedNode(&canvas, 4,
                            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {  // D
                                drawOrderedNode(&canvas, 3, [](RenderProperties& props,
                                                               SkiaRecordingCanvas& canvas) {  // R
                                    props.setProjectBackwards(true);
                                    props.setClipToBounds(false);
                                });  // nodeR
                            });      // nodeD
        });                          // nodeC
    });                              // nodeA
    EXPECT_EQ(5, drawNode(renderThread, nodeA));
}

RENDERTHREAD_TEST(RenderNodeDrawable, simple) {
    static const int CANVAS_WIDTH = 100;
    static const int CANVAS_HEIGHT = 200;
    class SimpleTestCanvas : public TestCanvasBase {
    public:
        SimpleTestCanvas() : TestCanvasBase(CANVAS_WIDTH, CANVAS_HEIGHT) {}
        void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
            EXPECT_EQ(0, mDrawCounter++);
        }
        void onDrawImage(const SkImage*, SkScalar dx, SkScalar dy, const SkPaint*) override {
            EXPECT_EQ(1, mDrawCounter++);
        }
    };

    auto node = TestUtils::createSkiaNode(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
                                          [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                                              sk_sp<Bitmap> bitmap(TestUtils::createBitmap(25, 25));
                                              canvas.drawRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
                                                              Paint());
                                              canvas.drawBitmap(*bitmap, 10, 10, nullptr);
                                          });

    SimpleTestCanvas canvas;
    RenderNodeDrawable drawable(node.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(2, canvas.mDrawCounter);
}

RENDERTHREAD_TEST(RenderNodeDrawable, colorOp_unbounded) {
    static const int CANVAS_WIDTH = 200;
    static const int CANVAS_HEIGHT = 200;
    class ColorTestCanvas : public TestCanvasBase {
    public:
        ColorTestCanvas() : TestCanvasBase(CANVAS_WIDTH, CANVAS_HEIGHT) {}
        void onDrawPaint(const SkPaint&) {
            switch (mDrawCounter++) {
                case 0:
                    EXPECT_EQ(SkRect::MakeWH(CANVAS_WIDTH, CANVAS_HEIGHT),
                              TestUtils::getClipBounds(this));
                    break;
                case 1:
                    EXPECT_EQ(SkRect::MakeWH(10, 10), TestUtils::getClipBounds(this));
                    break;
                default:
                    ADD_FAILURE();
            }
        }
    };

    auto unclippedColorView = TestUtils::createSkiaNode(
            0, 0, 10, 10, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                props.setClipToBounds(false);
                canvas.drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
            });

    auto clippedColorView = TestUtils::createSkiaNode(
            0, 0, 10, 10, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                canvas.drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
            });

    ColorTestCanvas canvas;
    RenderNodeDrawable drawable(unclippedColorView.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(1, canvas.mDrawCounter);
    RenderNodeDrawable drawable2(clippedColorView.get(), &canvas, true);
    canvas.drawDrawable(&drawable2);
    EXPECT_EQ(2, canvas.mDrawCounter);
}

TEST(RenderNodeDrawable, renderNode) {
    static const int CANVAS_WIDTH = 200;
    static const int CANVAS_HEIGHT = 200;
    class RenderNodeTestCanvas : public TestCanvasBase {
    public:
        RenderNodeTestCanvas() : TestCanvasBase(CANVAS_WIDTH, CANVAS_HEIGHT) {}
        void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
            switch (mDrawCounter++) {
                case 0:
                    EXPECT_EQ(SkRect::MakeWH(CANVAS_WIDTH, CANVAS_HEIGHT),
                              TestUtils::getClipBounds(this));
                    EXPECT_EQ(SK_ColorDKGRAY, paint.getColor());
                    break;
                case 1:
                    EXPECT_EQ(SkRect::MakeLTRB(50, 50, 150, 150), TestUtils::getClipBounds(this));
                    EXPECT_EQ(SK_ColorWHITE, paint.getColor());
                    break;
                default:
                    ADD_FAILURE();
            }
        }
    };

    auto child = TestUtils::createSkiaNode(
            10, 10, 110, 110, [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                Paint paint;
                paint.setColor(SK_ColorWHITE);
                canvas.drawRect(0, 0, 100, 100, paint);
            });

    auto parent = TestUtils::createSkiaNode(
            0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
            [&child](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                Paint paint;
                paint.setColor(SK_ColorDKGRAY);
                canvas.drawRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT, paint);

                canvas.save(SaveFlags::MatrixClip);
                canvas.translate(40, 40);
                canvas.drawRenderNode(child.get());
                canvas.restore();
            });

    RenderNodeTestCanvas canvas;
    RenderNodeDrawable drawable(parent.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(2, canvas.mDrawCounter);
}

// Verify that layers are composed with kLow_SkFilterQuality filter quality.
RENDERTHREAD_SKIA_PIPELINE_TEST(RenderNodeDrawable, layerComposeQuality) {
    static const int CANVAS_WIDTH = 1;
    static const int CANVAS_HEIGHT = 1;
    static const int LAYER_WIDTH = 1;
    static const int LAYER_HEIGHT = 1;
    class FrameTestCanvas : public TestCanvasBase {
    public:
        FrameTestCanvas() : TestCanvasBase(CANVAS_WIDTH, CANVAS_HEIGHT) {}
        void onDrawImageRect(const SkImage* image, const SkRect* src, const SkRect& dst,
                             const SkPaint* paint, SrcRectConstraint constraint) override {
            mDrawCounter++;
            EXPECT_EQ(kLow_SkFilterQuality, paint->getFilterQuality());
        }
    };

    auto layerNode = TestUtils::createSkiaNode(
            0, 0, LAYER_WIDTH, LAYER_HEIGHT,
            [](RenderProperties& properties, SkiaRecordingCanvas& canvas) {
                canvas.drawPaint(Paint());
            });

    layerNode->animatorProperties().mutateLayerProperties().setType(LayerType::RenderLayer);
    layerNode->setLayerSurface(SkSurface::MakeRasterN32Premul(LAYER_WIDTH, LAYER_HEIGHT));

    FrameTestCanvas canvas;
    RenderNodeDrawable drawable(layerNode.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(1, canvas.mDrawCounter);  // make sure the layer was composed

    // clean up layer pointer, so we can safely destruct RenderNode
    layerNode->setLayerSurface(nullptr);
}

TEST(ReorderBarrierDrawable, testShadowMatrix) {
    static const int CANVAS_WIDTH = 100;
    static const int CANVAS_HEIGHT = 100;
    static const float TRANSLATE_X = 11.0f;
    static const float TRANSLATE_Y = 22.0f;
    static const float CASTER_X = 40.0f;
    static const float CASTER_Y = 40.0f;
    static const float CASTER_WIDTH = 20.0f;
    static const float CASTER_HEIGHT = 20.0f;

    class ShadowTestCanvas : public SkCanvas {
    public:
        ShadowTestCanvas(int width, int height) : SkCanvas(width, height) {}
        int getDrawCounter() { return mDrawCounter; }

        virtual void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override {
            // Do not expect this to be called. See RecordingCanvas.cpp DrawDrawable for context.
            EXPECT_TRUE(false);
        }

        virtual void didTranslate(SkScalar dx, SkScalar dy) override {
            mDrawCounter++;
            EXPECT_EQ(dx, TRANSLATE_X);
            EXPECT_EQ(dy, TRANSLATE_Y);
        }

        virtual void didSetMatrix(const SkMatrix& matrix) override {
            mDrawCounter++;
            // First invocation is EndReorderBarrierDrawable::drawShadow to apply shadow matrix.
            // Second invocation is preparing the matrix for an elevated RenderNodeDrawable.
            EXPECT_TRUE(matrix.isIdentity());
            EXPECT_TRUE(getTotalMatrix().isIdentity());
        }

        virtual void didConcat(const SkMatrix& matrix) override {
            mDrawCounter++;
            if (mFirstDidConcat) {
                // First invocation is EndReorderBarrierDrawable::drawShadow to apply shadow matrix.
                mFirstDidConcat = false;
                EXPECT_EQ(SkMatrix::MakeTrans(CASTER_X + TRANSLATE_X, CASTER_Y + TRANSLATE_Y),
                          matrix);
                EXPECT_EQ(SkMatrix::MakeTrans(CASTER_X + TRANSLATE_X, CASTER_Y + TRANSLATE_Y),
                          getTotalMatrix());
            } else {
                // Second invocation is preparing the matrix for an elevated RenderNodeDrawable.
                EXPECT_EQ(SkMatrix::MakeTrans(TRANSLATE_X, TRANSLATE_Y), matrix);
                EXPECT_EQ(SkMatrix::MakeTrans(TRANSLATE_X, TRANSLATE_Y), getTotalMatrix());
            }
        }

    protected:
        int mDrawCounter = 0;

    private:
        bool mFirstDidConcat = true;
    };

    auto parent = TestUtils::createSkiaNode(
            0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                canvas.translate(TRANSLATE_X, TRANSLATE_Y);
                canvas.insertReorderBarrier(true);

                auto node = TestUtils::createSkiaNode(
                        CASTER_X, CASTER_Y, CASTER_X + CASTER_WIDTH, CASTER_Y + CASTER_HEIGHT,
                        [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                            props.setElevation(42);
                            props.mutableOutline().setRoundRect(0, 0, 20, 20, 5, 1);
                            props.mutableOutline().setShouldClip(true);
                        });
                canvas.drawRenderNode(node.get());
                canvas.insertReorderBarrier(false);
            });

    // create a canvas not backed by any device/pixels, but with dimensions to avoid quick rejection
    ShadowTestCanvas canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
    RenderNodeDrawable drawable(parent.get(), &canvas, false);
    drawable.draw(&canvas);
    EXPECT_EQ(5, canvas.getDrawCounter());
}

// Draw a vector drawable twice but with different bounds and verify correct bounds are used.
RENDERTHREAD_SKIA_PIPELINE_TEST(SkiaRecordingCanvas, drawVectorDrawable) {
    static const int CANVAS_WIDTH = 100;
    static const int CANVAS_HEIGHT = 200;
    class VectorDrawableTestCanvas : public TestCanvasBase {
    public:
        VectorDrawableTestCanvas() : TestCanvasBase(CANVAS_WIDTH, CANVAS_HEIGHT) {}
        void onDrawBitmapRect(const SkBitmap& bitmap, const SkRect* src, const SkRect& dst,
                              const SkPaint* paint, SrcRectConstraint constraint) override {
            const int index = mDrawCounter++;
            switch (index) {
                case 0:
                    EXPECT_EQ(dst, SkRect::MakeWH(CANVAS_WIDTH, CANVAS_HEIGHT));
                    break;
                case 1:
                    EXPECT_EQ(dst, SkRect::MakeWH(CANVAS_WIDTH / 2, CANVAS_HEIGHT));
                    break;
                default:
                    ADD_FAILURE();
            }
        }
        void onDrawImageRect(const SkImage*, const SkRect* src, const SkRect& dst,
                              const SkPaint* paint, SrcRectConstraint constraint) override {
            const int index = mDrawCounter++;
            switch (index) {
                case 0:
                    EXPECT_EQ(dst, SkRect::MakeWH(CANVAS_WIDTH, CANVAS_HEIGHT));
                    break;
                case 1:
                    EXPECT_EQ(dst, SkRect::MakeWH(CANVAS_WIDTH / 2, CANVAS_HEIGHT));
                    break;
                default:
                    ADD_FAILURE();
            }
        }
    };

    VectorDrawable::Group* group = new VectorDrawable::Group();
    sp<VectorDrawableRoot> vectorDrawable(new VectorDrawableRoot(group));
    vectorDrawable->mutateStagingProperties()->setScaledSize(CANVAS_WIDTH / 10, CANVAS_HEIGHT / 10);

    auto node =
            TestUtils::createSkiaNode(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
                                      [&](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                                          vectorDrawable->mutateStagingProperties()->setBounds(
                                                  SkRect::MakeWH(CANVAS_WIDTH, CANVAS_HEIGHT));
                                          canvas.drawVectorDrawable(vectorDrawable.get());
                                          vectorDrawable->mutateStagingProperties()->setBounds(
                                                  SkRect::MakeWH(CANVAS_WIDTH / 2, CANVAS_HEIGHT));
                                          canvas.drawVectorDrawable(vectorDrawable.get());
                                      });

    VectorDrawableTestCanvas canvas;
    RenderNodeDrawable drawable(node.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(2, canvas.mDrawCounter);
}
