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

#include <DeferredLayerUpdater.h>
#include <RecordedOp.h>
#include <RecordingCanvas.h>
#include <hwui/Paint.h>
#include <minikin/Layout.h>
#include <tests/common/TestUtils.h>
#include <utils/Color.h>

#include <SkGradientShader.h>
#include <SkShader.h>

namespace android {
namespace uirenderer {

static void playbackOps(const DisplayList& displayList,
        std::function<void(const RecordedOp&)> opReceiver) {
    for (auto& chunk : displayList.getChunks()) {
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            RecordedOp* op = displayList.getOps()[opIndex];
            opReceiver(*op);
        }
    }
}

static void validateSingleOp(std::unique_ptr<DisplayList>& dl,
        std::function<void(const RecordedOp& op)> opValidator) {
    ASSERT_EQ(1u, dl->getOps().size()) << "Must be exactly one op";
    opValidator(*(dl->getOps()[0]));
}

TEST(RecordingCanvas, emptyPlayback) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.restore();
    });
    playbackOps(*dl, [](const RecordedOp& op) { ADD_FAILURE(); });
}

TEST(RecordingCanvas, clipRect) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 100, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.clipRect(0, 0, 100, 100, SkRegion::kIntersect_Op);
        canvas.drawRect(0, 0, 50, 50, SkPaint());
        canvas.drawRect(50, 50, 100, 100, SkPaint());
        canvas.restore();
    });

    ASSERT_EQ(2u, dl->getOps().size()) << "Must be exactly two ops";
    EXPECT_CLIP_RECT(Rect(100, 100), dl->getOps()[0]->localClip);
    EXPECT_CLIP_RECT(Rect(100, 100), dl->getOps()[1]->localClip);
    EXPECT_EQ(dl->getOps()[0]->localClip, dl->getOps()[1]->localClip)
            << "Clip should be serialized once";
}

TEST(RecordingCanvas, emptyClipRect) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.clipRect(0, 0, 100, 100, SkRegion::kIntersect_Op);
        canvas.clipRect(100, 100, 200, 200, SkRegion::kIntersect_Op);
        canvas.drawRect(0, 0, 50, 50, SkPaint()); // rejected at record time
        canvas.restore();
    });
    ASSERT_EQ(0u, dl->getOps().size()) << "Must be zero ops. Rect should be rejected.";
}

TEST(RecordingCanvas, emptyPaintRejection) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        SkPaint emptyPaint;
        emptyPaint.setColor(Color::Transparent);

        float points[] = {0, 0, 200, 200};
        canvas.drawPoints(points, 4, emptyPaint);
        canvas.drawLines(points, 4, emptyPaint);
        canvas.drawRect(0, 0, 200, 200, emptyPaint);
        canvas.drawRegion(SkRegion(SkIRect::MakeWH(200, 200)), emptyPaint);
        canvas.drawRoundRect(0, 0, 200, 200, 10, 10, emptyPaint);
        canvas.drawCircle(100, 100, 100, emptyPaint);
        canvas.drawOval(0, 0, 200, 200, emptyPaint);
        canvas.drawArc(0, 0, 200, 200, 0, 360, true, emptyPaint);
        SkPath path;
        path.addRect(0, 0, 200, 200);
        canvas.drawPath(path, emptyPaint);
    });
    EXPECT_EQ(0u, dl->getOps().size()) << "Op should be rejected";
}

TEST(RecordingCanvas, drawArc) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.drawArc(0, 0, 200, 200, 0, 180, true, SkPaint());
        canvas.drawArc(0, 0, 100, 100, 0, 360, true, SkPaint());
    });

    auto&& ops = dl->getOps();
    ASSERT_EQ(2u, ops.size()) << "Must be exactly two ops";
    EXPECT_EQ(RecordedOpId::ArcOp, ops[0]->opId);
    EXPECT_EQ(Rect(200, 200), ops[0]->unmappedBounds);

    EXPECT_EQ(RecordedOpId::OvalOp, ops[1]->opId)
            << "Circular arcs should be converted to ovals";
    EXPECT_EQ(Rect(100, 100), ops[1]->unmappedBounds);
}

TEST(RecordingCanvas, drawLines) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setStrokeWidth(20); // doesn't affect recorded bounds - would be resolved at bake time
        float points[] = { 0, 0, 20, 10, 30, 40, 90 }; // NB: only 1 valid line
        canvas.drawLines(&points[0], 7, paint);
    });

    ASSERT_EQ(1u, dl->getOps().size()) << "Must be exactly one op";
    auto op = dl->getOps()[0];
    ASSERT_EQ(RecordedOpId::LinesOp, op->opId);
    EXPECT_EQ(4, ((LinesOp*)op)->floatCount)
            << "float count must be rounded down to closest multiple of 4";
    EXPECT_EQ(Rect(20, 10), op->unmappedBounds)
            << "unmapped bounds must be size of line, and not outset for stroke width";
}

TEST(RecordingCanvas, drawRect) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.drawRect(10, 20, 90, 180, SkPaint());
    });

    ASSERT_EQ(1u, dl->getOps().size()) << "Must be exactly one op";
    auto op = *(dl->getOps()[0]);
    ASSERT_EQ(RecordedOpId::RectOp, op.opId);
    EXPECT_EQ(nullptr, op.localClip);
    EXPECT_EQ(Rect(10, 20, 90, 180), op.unmappedBounds);
}

TEST(RecordingCanvas, drawRoundRect) {
    // Round case - stays rounded
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.drawRoundRect(0, 0, 100, 100, 10, 10, SkPaint());
    });
    ASSERT_EQ(1u, dl->getOps().size()) << "Must be exactly one op";
    ASSERT_EQ(RecordedOpId::RoundRectOp, dl->getOps()[0]->opId);

    // Non-rounded case - turned into drawRect
    dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.drawRoundRect(0, 0, 100, 100, 0, -1, SkPaint());
    });
    ASSERT_EQ(1u, dl->getOps().size()) << "Must be exactly one op";
    ASSERT_EQ(RecordedOpId::RectOp, dl->getOps()[0]->opId)
        << "Non-rounded rects should be converted";
}

TEST(RecordingCanvas, drawGlyphs) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        TestUtils::drawUtf8ToCanvas(&canvas, "test text", paint, 25, 25);
    });

    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        count++;
        ASSERT_EQ(RecordedOpId::TextOp, op.opId);
        EXPECT_EQ(nullptr, op.localClip);
        EXPECT_TRUE(op.localMatrix.isIdentity());
        EXPECT_TRUE(op.unmappedBounds.contains(25, 15, 50, 25))
                << "Op expected to be 25+ pixels wide, 10+ pixels tall";
    });
    ASSERT_EQ(1, count);
}

TEST(RecordingCanvas, drawGlyphs_strikeThruAndUnderline) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                paint.setUnderlineText(i != 0);
                paint.setStrikeThruText(j != 0);
                TestUtils::drawUtf8ToCanvas(&canvas, "test text", paint, 25, 25);
            }
        }
    });

    auto ops = dl->getOps();
    ASSERT_EQ(8u, ops.size());

    int index = 0;
    EXPECT_EQ(RecordedOpId::TextOp, ops[index++]->opId); // no underline or strikethrough

    EXPECT_EQ(RecordedOpId::TextOp, ops[index++]->opId);
    EXPECT_EQ(RecordedOpId::RectOp, ops[index++]->opId); // strikethrough only

    EXPECT_EQ(RecordedOpId::TextOp, ops[index++]->opId);
    EXPECT_EQ(RecordedOpId::RectOp, ops[index++]->opId); // underline only

    EXPECT_EQ(RecordedOpId::TextOp, ops[index++]->opId);
    EXPECT_EQ(RecordedOpId::RectOp, ops[index++]->opId); // underline
    EXPECT_EQ(RecordedOpId::RectOp, ops[index++]->opId); // strikethrough
}

TEST(RecordingCanvas, drawGlyphs_forceAlignLeft) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        paint.setTextAlign(SkPaint::kLeft_Align);
        TestUtils::drawUtf8ToCanvas(&canvas, "test text", paint, 25, 25);
        paint.setTextAlign(SkPaint::kCenter_Align);
        TestUtils::drawUtf8ToCanvas(&canvas, "test text", paint, 25, 25);
        paint.setTextAlign(SkPaint::kRight_Align);
        TestUtils::drawUtf8ToCanvas(&canvas, "test text", paint, 25, 25);
    });

    int count = 0;
    float lastX = FLT_MAX;
    playbackOps(*dl, [&count, &lastX](const RecordedOp& op) {
        count++;
        ASSERT_EQ(RecordedOpId::TextOp, op.opId);
        EXPECT_EQ(SkPaint::kLeft_Align, op.paint->getTextAlign())
                << "recorded drawText commands must force kLeft_Align on their paint";

        // verify TestUtils alignment offsetting (TODO: move asserts to Canvas base class)
        EXPECT_GT(lastX, ((const TextOp&)op).x)
                << "x coordinate should reduce across each of the draw commands, from alignment";
        lastX = ((const TextOp&)op).x;
    });
    ASSERT_EQ(3, count);
}

TEST(RecordingCanvas, drawColor) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.drawColor(Color::Black, SkXfermode::kSrcOver_Mode);
    });

    ASSERT_EQ(1u, dl->getOps().size()) << "Must be exactly one op";
    auto op = *(dl->getOps()[0]);
    EXPECT_EQ(RecordedOpId::ColorOp, op.opId);
    EXPECT_EQ(nullptr, op.localClip);
    EXPECT_TRUE(op.unmappedBounds.isEmpty()) << "Expect undefined recorded bounds";
}

TEST(RecordingCanvas, backgroundAndImage) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap;
        bitmap.setInfo(SkImageInfo::MakeUnknown(25, 25));
        SkPaint paint;
        paint.setColor(SK_ColorBLUE);

        canvas.save(SaveFlags::MatrixClip);
        {
            // a background!
            canvas.save(SaveFlags::MatrixClip);
            canvas.drawRect(0, 0, 100, 200, paint);
            canvas.restore();
        }
        {
            // an image!
            canvas.save(SaveFlags::MatrixClip);
            canvas.translate(25, 25);
            canvas.scale(2, 2);
            canvas.drawBitmap(bitmap, 0, 0, nullptr);
            canvas.restore();
        }
        canvas.restore();
    });

    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count == 0) {
            ASSERT_EQ(RecordedOpId::RectOp, op.opId);
            ASSERT_NE(nullptr, op.paint);
            EXPECT_EQ(SK_ColorBLUE, op.paint->getColor());
            EXPECT_EQ(Rect(100, 200), op.unmappedBounds);
            EXPECT_EQ(nullptr, op.localClip);

            Matrix4 expectedMatrix;
            expectedMatrix.loadIdentity();
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        } else {
            ASSERT_EQ(RecordedOpId::BitmapOp, op.opId);
            EXPECT_EQ(nullptr, op.paint);
            EXPECT_EQ(Rect(25, 25), op.unmappedBounds);
            EXPECT_EQ(nullptr, op.localClip);

            Matrix4 expectedMatrix;
            expectedMatrix.loadTranslate(25, 25, 0);
            expectedMatrix.scale(2, 2, 1);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
        count++;
    });
    ASSERT_EQ(2, count);
}

RENDERTHREAD_TEST(RecordingCanvas, textureLayer) {
    auto layerUpdater = TestUtils::createTextureLayerUpdater(renderThread, 100, 100,
            SkMatrix::MakeTrans(5, 5));

    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200,
            [&layerUpdater](RecordingCanvas& canvas) {
        canvas.drawLayer(layerUpdater.get());
    });

    validateSingleOp(dl, [] (const RecordedOp& op) {
        ASSERT_EQ(RecordedOpId::TextureLayerOp, op.opId);
        ASSERT_TRUE(op.localMatrix.isIdentity()) << "Op must not apply matrix at record time.";
    });
}

TEST(RecordingCanvas, saveLayer_simple) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 20, 190, 180, 128, SaveFlags::ClipToLayer);
        canvas.drawRect(10, 20, 190, 180, SkPaint());
        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        Matrix4 expectedMatrix;
        switch(count++) {
        case 0:
            EXPECT_EQ(RecordedOpId::BeginLayerOp, op.opId);
            EXPECT_EQ(Rect(10, 20, 190, 180), op.unmappedBounds);
            EXPECT_EQ(nullptr, op.localClip);
            EXPECT_TRUE(op.localMatrix.isIdentity());
            break;
        case 1:
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);
            EXPECT_CLIP_RECT(Rect(180, 160), op.localClip);
            EXPECT_EQ(Rect(10, 20, 190, 180), op.unmappedBounds);
            expectedMatrix.loadTranslate(-10, -20, 0);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
            break;
        case 2:
            EXPECT_EQ(RecordedOpId::EndLayerOp, op.opId);
            // Don't bother asserting recording state data - it's not used
            break;
        default:
            ADD_FAILURE();
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_rounding) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 100, [](RecordingCanvas& canvas) {
            canvas.saveLayerAlpha(10.25f, 10.75f, 89.25f, 89.75f, 128, SaveFlags::ClipToLayer);
            canvas.drawRect(20, 20, 80, 80, SkPaint());
            canvas.restore();
        });
        int count = 0;
        playbackOps(*dl, [&count](const RecordedOp& op) {
            Matrix4 expectedMatrix;
            switch(count++) {
            case 0:
                EXPECT_EQ(RecordedOpId::BeginLayerOp, op.opId);
                EXPECT_EQ(Rect(10, 10, 90, 90), op.unmappedBounds) << "Expect bounds rounded out";
                break;
            case 1:
                EXPECT_EQ(RecordedOpId::RectOp, op.opId);
                expectedMatrix.loadTranslate(-10, -10, 0);
                EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix) << "Expect rounded offset";
                break;
            case 2:
                EXPECT_EQ(RecordedOpId::EndLayerOp, op.opId);
                // Don't bother asserting recording state data - it's not used
                break;
            default:
                ADD_FAILURE();
            }
        });
        EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_missingRestore) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(0, 0, 200, 200, 128, SaveFlags::ClipToLayer);
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        // Note: restore omitted, shouldn't result in unmatched save
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 2) {
            EXPECT_EQ(RecordedOpId::EndLayerOp, op.opId);
        }
    });
    EXPECT_EQ(3, count) << "Missing a restore shouldn't result in an unmatched saveLayer";
}

TEST(RecordingCanvas, saveLayer_simpleUnclipped) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 20, 190, 180, 128, (SaveFlags::Flags)0); // unclipped
        canvas.drawRect(10, 20, 190, 180, SkPaint());
        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        switch(count++) {
        case 0:
            EXPECT_EQ(RecordedOpId::BeginUnclippedLayerOp, op.opId);
            EXPECT_EQ(Rect(10, 20, 190, 180), op.unmappedBounds);
            EXPECT_EQ(nullptr, op.localClip);
            EXPECT_TRUE(op.localMatrix.isIdentity());
            break;
        case 1:
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);
            EXPECT_EQ(nullptr, op.localClip);
            EXPECT_EQ(Rect(10, 20, 190, 180), op.unmappedBounds);
            EXPECT_TRUE(op.localMatrix.isIdentity());
            break;
        case 2:
            EXPECT_EQ(RecordedOpId::EndUnclippedLayerOp, op.opId);
            // Don't bother asserting recording state data - it's not used
            break;
        default:
            ADD_FAILURE();
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_addClipFlag) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.clipRect(10, 20, 190, 180, SkRegion::kIntersect_Op);
        canvas.saveLayerAlpha(10, 20, 190, 180, 128, (SaveFlags::Flags)0); // unclipped
        canvas.drawRect(10, 20, 190, 180, SkPaint());
        canvas.restore();
        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 0) {
            EXPECT_EQ(RecordedOpId::BeginLayerOp, op.opId)
                    << "Clip + unclipped saveLayer should result in a clipped layer";
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_viewportCrop) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        // shouldn't matter, since saveLayer will clip to its bounds
        canvas.clipRect(-1000, -1000, 1000, 1000, SkRegion::kReplace_Op);

        canvas.saveLayerAlpha(100, 100, 300, 300, 128, SaveFlags::ClipToLayer);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 1) {
            Matrix4 expectedMatrix;
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);
            EXPECT_CLIP_RECT(Rect(100, 100), op.localClip) // Recorded clip rect should be
            // intersection of viewport and saveLayer bounds, in layer space;
            EXPECT_EQ(Rect(400, 400), op.unmappedBounds);
            expectedMatrix.loadTranslate(-100, -100, 0);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_rotateUnclipped) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.translate(100, 100);
        canvas.rotate(45);
        canvas.translate(-50, -50);

        canvas.saveLayerAlpha(0, 0, 100, 100, 128, SaveFlags::ClipToLayer);
        canvas.drawRect(0, 0, 100, 100, SkPaint());
        canvas.restore();

        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 1) {
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);
            EXPECT_CLIP_RECT(Rect(100, 100), op.localClip);
            EXPECT_EQ(Rect(100, 100), op.unmappedBounds);
            EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), op.localMatrix)
                    << "Recorded op shouldn't see any canvas transform before the saveLayer";
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_rotateClipped) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.translate(100, 100);
        canvas.rotate(45);
        canvas.translate(-200, -200);

        // area of saveLayer will be clipped to parent viewport, so we ask for 400x400...
        canvas.saveLayerAlpha(0, 0, 400, 400, 128, SaveFlags::ClipToLayer);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();

        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 1) {
            Matrix4 expectedMatrix;
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);

            // ...and get about 58.6, 58.6, 341.4 341.4, because the bounds are clipped by
            // the parent 200x200 viewport, but prior to rotation
            ASSERT_NE(nullptr, op.localClip);
            ASSERT_EQ(ClipMode::Rectangle, op.localClip->mode);
            // NOTE: this check relies on saveLayer altering the clip post-viewport init. This
            // causes the clip to be recorded by contained draw commands, though it's not necessary
            // since the same clip will be computed at draw time. If such a change is made, this
            // check could be done at record time by querying the clip, or the clip could be altered
            // slightly so that it is serialized.
            EXPECT_EQ(Rect(59, 59, 341, 341), op.localClip->rect);
            EXPECT_EQ(Rect(400, 400), op.unmappedBounds);
            expectedMatrix.loadIdentity();
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_rejectBegin) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.translate(0, -20); // avoid identity case
        // empty clip rect should force layer + contents to be rejected
        canvas.clipRect(0, -20, 200, -20, SkRegion::kIntersect_Op);
        canvas.saveLayerAlpha(0, 0, 200, 200, 128, SaveFlags::ClipToLayer);
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.restore();
        canvas.restore();
    });

    ASSERT_EQ(0u, dl->getOps().size()) << "Begin/Rect/End should all be rejected.";
}

TEST(RecordingCanvas, drawRenderNode_rejection) {
    auto child = TestUtils::createNode(50, 50, 150, 150,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });

    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [&child](RecordingCanvas& canvas) {
        canvas.clipRect(0, 0, 0, 0, SkRegion::kIntersect_Op); // empty clip, reject node
        canvas.drawRenderNode(child.get()); // shouldn't crash when rejecting node...
    });
    ASSERT_TRUE(dl->isEmpty());
}

TEST(RecordingCanvas, drawRenderNode_projection) {
    sp<RenderNode> background = TestUtils::createNode(50, 50, 150, 150,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
    {
        background->mutateStagingProperties().setProjectionReceiver(false);

        // NO RECEIVER PRESENT
        auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200,
                    [&background](RecordingCanvas& canvas) {
            canvas.drawRect(0, 0, 100, 100, SkPaint());
            canvas.drawRenderNode(background.get());
            canvas.drawRect(0, 0, 100, 100, SkPaint());
        });
        EXPECT_EQ(-1, dl->projectionReceiveIndex)
                << "no projection receiver should have been observed";
    }
    {
        background->mutateStagingProperties().setProjectionReceiver(true);

        // RECEIVER PRESENT
        auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200,
                    [&background](RecordingCanvas& canvas) {
            canvas.drawRect(0, 0, 100, 100, SkPaint());
            canvas.drawRenderNode(background.get());
            canvas.drawRect(0, 0, 100, 100, SkPaint());
        });

        ASSERT_EQ(3u, dl->getOps().size()) << "Must be three ops";
        auto op = dl->getOps()[1];
        EXPECT_EQ(RecordedOpId::RenderNodeOp, op->opId);
        EXPECT_EQ(1, dl->projectionReceiveIndex)
                << "correct projection receiver not identified";

        // verify the behavior works even though projection receiver hasn't been sync'd yet
        EXPECT_TRUE(background->stagingProperties().isProjectionReceiver());
        EXPECT_FALSE(background->properties().isProjectionReceiver());
    }
}

TEST(RecordingCanvas, firstClipWillReplace) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        // since no explicit clip set on canvas, this should be the one observed on op:
        canvas.clipRect(-100, -100, 300, 300, SkRegion::kIntersect_Op);

        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);

        canvas.restore();
    });
    ASSERT_EQ(1u, dl->getOps().size()) << "Must have one op";
    // first clip must be preserved, even if it extends beyond canvas bounds
    EXPECT_CLIP_RECT(Rect(-100, -100, 300, 300), dl->getOps()[0]->localClip);
}

TEST(RecordingCanvas, replaceClipIntersectWithRoot) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 100, [](RecordingCanvas& canvas) {
        canvas.save(SaveFlags::MatrixClip);
        canvas.clipRect(-10, -10, 110, 110, SkRegion::kReplace_Op);
        canvas.drawColor(SK_ColorWHITE, SkXfermode::Mode::kSrcOver_Mode);
        canvas.restore();
    });
    ASSERT_EQ(1u, dl->getOps().size()) << "Must have one op";
    // first clip must be preserved, even if it extends beyond canvas bounds
    EXPECT_CLIP_RECT(Rect(-10, -10, 110, 110), dl->getOps()[0]->localClip);
    EXPECT_TRUE(dl->getOps()[0]->localClip->intersectWithRoot);
}

TEST(RecordingCanvas, insertReorderBarrier) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.insertReorderBarrier(true);
        canvas.insertReorderBarrier(false);
        canvas.insertReorderBarrier(false);
        canvas.insertReorderBarrier(true);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.insertReorderBarrier(false);
    });

    auto chunks = dl->getChunks();
    EXPECT_EQ(0u, chunks[0].beginOpIndex);
    EXPECT_EQ(1u, chunks[0].endOpIndex);
    EXPECT_FALSE(chunks[0].reorderChildren);

    EXPECT_EQ(1u, chunks[1].beginOpIndex);
    EXPECT_EQ(2u, chunks[1].endOpIndex);
    EXPECT_TRUE(chunks[1].reorderChildren);
}

TEST(RecordingCanvas, insertReorderBarrier_clip) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        // first chunk: no recorded clip
        canvas.insertReorderBarrier(true);
        canvas.drawRect(0, 0, 400, 400, SkPaint());

        // second chunk: no recorded clip, since inorder region
        canvas.clipRect(0, 0, 200, 200, SkRegion::kIntersect_Op);
        canvas.insertReorderBarrier(false);
        canvas.drawRect(0, 0, 400, 400, SkPaint());

        // third chunk: recorded clip
        canvas.insertReorderBarrier(true);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
    });

    auto chunks = dl->getChunks();
    ASSERT_EQ(3u, chunks.size());

    EXPECT_TRUE(chunks[0].reorderChildren);
    EXPECT_EQ(nullptr, chunks[0].reorderClip);

    EXPECT_FALSE(chunks[1].reorderChildren);
    EXPECT_EQ(nullptr, chunks[1].reorderClip);

    EXPECT_TRUE(chunks[2].reorderChildren);
    ASSERT_NE(nullptr, chunks[2].reorderClip);
    EXPECT_EQ(Rect(200, 200), chunks[2].reorderClip->rect);
}

TEST(RecordingCanvas, refPaint) {
    SkPaint paint;

    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [&paint](RecordingCanvas& canvas) {
        paint.setColor(SK_ColorBLUE);
        // first two should use same paint
        canvas.drawRect(0, 0, 200, 10, paint);
        SkPaint paintCopy(paint);
        canvas.drawRect(0, 10, 200, 20, paintCopy);

        // only here do we use different paint ptr
        paint.setColor(SK_ColorRED);
        canvas.drawRect(0, 20, 200, 30, paint);
    });
    auto ops = dl->getOps();
    ASSERT_EQ(3u, ops.size());

    // first two are the same
    EXPECT_NE(nullptr, ops[0]->paint);
    EXPECT_NE(&paint, ops[0]->paint);
    EXPECT_EQ(ops[0]->paint, ops[1]->paint);

    // last is different, but still copied / non-null
    EXPECT_NE(nullptr, ops[2]->paint);
    EXPECT_NE(ops[0]->paint, ops[2]->paint);
    EXPECT_NE(&paint, ops[2]->paint);
}

TEST(RecordingCanvas, refBitmap) {
    SkBitmap bitmap = TestUtils::createSkBitmap(100, 100);
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 100, [&bitmap](RecordingCanvas& canvas) {
        canvas.drawBitmap(bitmap, 0, 0, nullptr);
    });
    auto& bitmaps = dl->getBitmapResources();
    EXPECT_EQ(1u, bitmaps.size());
}

TEST(RecordingCanvas, refBitmapInShader_bitmapShader) {
    SkBitmap bitmap = TestUtils::createSkBitmap(100, 100);
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 100, [&bitmap](RecordingCanvas& canvas) {
        SkPaint paint;
        SkAutoTUnref<SkShader> shader(SkShader::CreateBitmapShader(bitmap,
                SkShader::TileMode::kClamp_TileMode,
                SkShader::TileMode::kClamp_TileMode));
        paint.setShader(shader);
        canvas.drawRoundRect(0, 0, 100, 100, 20.0f, 20.0f, paint);
    });
    auto& bitmaps = dl->getBitmapResources();
    EXPECT_EQ(1u, bitmaps.size());
}

TEST(RecordingCanvas, refBitmapInShader_composeShader) {
    SkBitmap bitmap = TestUtils::createSkBitmap(100, 100);
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 100, [&bitmap](RecordingCanvas& canvas) {
        SkPaint paint;
        SkAutoTUnref<SkShader> shader1(SkShader::CreateBitmapShader(bitmap,
                SkShader::TileMode::kClamp_TileMode,
                SkShader::TileMode::kClamp_TileMode));

        SkPoint center;
        center.set(50, 50);
        SkColor colors[2];
        colors[0] = Color::Black;
        colors[1] = Color::White;
        SkAutoTUnref<SkShader> shader2(SkGradientShader::CreateRadial(center, 50, colors, nullptr, 2,
                SkShader::TileMode::kRepeat_TileMode));

        SkAutoTUnref<SkShader> composeShader(SkShader::CreateComposeShader(shader1, shader2,
                SkXfermode::Mode::kMultiply_Mode));
        paint.setShader(composeShader);
        canvas.drawRoundRect(0, 0, 100, 100, 20.0f, 20.0f, paint);
    });
    auto& bitmaps = dl->getBitmapResources();
    EXPECT_EQ(1u, bitmaps.size());
}

TEST(RecordingCanvas, drawText) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        Paint paint;
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        std::unique_ptr<uint16_t[]> dst = TestUtils::asciiToUtf16("HELLO");
        canvas.drawText(dst.get(), 0, 5, 5, 25, 25, kBidi_Force_LTR, paint, NULL);
    });

    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        count++;
        ASSERT_EQ(RecordedOpId::TextOp, op.opId);
        EXPECT_EQ(nullptr, op.localClip);
        EXPECT_TRUE(op.localMatrix.isIdentity());
        EXPECT_TRUE(op.unmappedBounds.getHeight() >= 10);
        EXPECT_TRUE(op.unmappedBounds.getWidth() >= 25);
    });
    ASSERT_EQ(1, count);
}

TEST(RecordingCanvas, drawTextInHighContrast) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.setHighContrastText(true);
        Paint paint;
        paint.setColor(SK_ColorWHITE);
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        std::unique_ptr<uint16_t[]> dst = TestUtils::asciiToUtf16("HELLO");
        canvas.drawText(dst.get(), 0, 5, 5, 25, 25, kBidi_Force_LTR, paint, NULL);
    });

    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        ASSERT_EQ(RecordedOpId::TextOp, op.opId);
        if (count++ == 0) {
            EXPECT_EQ(SK_ColorBLACK, op.paint->getColor());
            EXPECT_EQ(SkPaint::kStrokeAndFill_Style, op.paint->getStyle());
        } else {
            EXPECT_EQ(SK_ColorWHITE, op.paint->getColor());
            EXPECT_EQ(SkPaint::kFill_Style, op.paint->getStyle());
        }

    });
    ASSERT_EQ(2, count);
}

} // namespace uirenderer
} // namespace android
