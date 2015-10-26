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

#include <RecordedOp.h>
#include <RecordingCanvas.h>
#include <unit_tests/TestUtils.h>

namespace android {
namespace uirenderer {

static void playbackOps(const DisplayList& displayList,
        std::function<void(const RecordedOp&)> opReceiver) {
    for (const DisplayList::Chunk& chunk : displayList.getChunks()) {
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            RecordedOp* op = displayList.getOps()[opIndex];
            opReceiver(*op);
        }
    }
}

TEST(RecordingCanvas, emptyPlayback) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.restore();
    });
    playbackOps(*dl, [](const RecordedOp& op) { ADD_FAILURE(); });
}

TEST(RecordingCanvas, testSimpleRectRecord) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.drawRect(10, 20, 90, 180, SkPaint());
    });

    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        count++;
        ASSERT_EQ(RecordedOpId::RectOp, op.opId);
        ASSERT_EQ(Rect(0, 0, 100, 200), op.localClipRect);
        ASSERT_EQ(Rect(10, 20, 90, 180), op.unmappedBounds);
    });
    ASSERT_EQ(1, count); // only one observed
}

TEST(RecordingCanvas, backgroundAndImage) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap;
        bitmap.setInfo(SkImageInfo::MakeUnknown(25, 25));
        SkPaint paint;
        paint.setColor(SK_ColorBLUE);

        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        {
            // a background!
            canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
            canvas.drawRect(0, 0, 100, 200, paint);
            canvas.restore();
        }
        {
            // an image!
            canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
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
            EXPECT_EQ(Rect(0, 0, 100, 200), op.unmappedBounds);
            EXPECT_EQ(Rect(0, 0, 100, 200), op.localClipRect);

            Matrix4 expectedMatrix;
            expectedMatrix.loadIdentity();
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        } else {
            ASSERT_EQ(RecordedOpId::BitmapOp, op.opId);
            EXPECT_EQ(nullptr, op.paint);
            EXPECT_EQ(Rect(0, 0, 25, 25), op.unmappedBounds);
            EXPECT_EQ(Rect(0, 0, 100, 200), op.localClipRect);

            Matrix4 expectedMatrix;
            expectedMatrix.loadTranslate(25, 25, 0);
            expectedMatrix.scale(2, 2, 1);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
        count++;
    });
    ASSERT_EQ(2, count); // two draws observed
}

TEST(RecordingCanvas, saveLayerSimple) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 20, 190, 180, 128, SkCanvas::kARGB_ClipLayer_SaveFlag);
        canvas.drawRect(10, 20, 190, 180, SkPaint());
        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        Matrix4 expectedMatrix;
        switch(count++) {
        case 0:
            EXPECT_EQ(RecordedOpId::BeginLayerOp, op.opId);
            // TODO: add asserts
            break;
        case 1:
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);
            EXPECT_EQ(Rect(0, 0, 180, 160), op.localClipRect);
            EXPECT_EQ(Rect(10, 20, 190, 180), op.unmappedBounds);
            expectedMatrix.loadTranslate(-10, -20, 0);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
            break;
        case 2:
            EXPECT_EQ(RecordedOpId::EndLayerOp, op.opId);
            // TODO: add asserts
            break;
        default:
            ADD_FAILURE();
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayerViewportCrop) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        // shouldn't matter, since saveLayer will clip to its bounds
        canvas.clipRect(-1000, -1000, 1000, 1000, SkRegion::kReplace_Op);

        canvas.saveLayerAlpha(100, 100, 300, 300, 128, SkCanvas::kARGB_ClipLayer_SaveFlag);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 1) {
            Matrix4 expectedMatrix;
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);

            // recorded clip rect should be intersection of
            // viewport and saveLayer bounds, in layer space
            EXPECT_EQ(Rect(0, 0, 100, 100), op.localClipRect);
            EXPECT_EQ(Rect(0, 0, 400, 400), op.unmappedBounds);
            expectedMatrix.loadTranslate(-100, -100, 0);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayerRotateUnclipped) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(100, 100);
        canvas.rotate(45);
        canvas.translate(-50, -50);

        canvas.saveLayerAlpha(0, 0, 100, 100, 128, SkCanvas::kARGB_ClipLayer_SaveFlag);
        canvas.drawRect(0, 0, 100, 100, SkPaint());
        canvas.restore();

        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 1) {
            Matrix4 expectedMatrix;
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);

            // recorded rect doesn't see rotate, since recorded relative to saveLayer bounds
            EXPECT_EQ(Rect(0, 0, 100, 100), op.localClipRect);
            EXPECT_EQ(Rect(0, 0, 100, 100), op.unmappedBounds);
            expectedMatrix.loadIdentity();
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayerRotateClipped) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(100, 100);
        canvas.rotate(45);
        canvas.translate(-200, -200);

        // area of saveLayer will be clipped to parent viewport, so we ask for 400x400...
        canvas.saveLayerAlpha(0, 0, 400, 400, 128, SkCanvas::kARGB_ClipLayer_SaveFlag);
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
            EXPECT_RECT_APPROX_EQ(Rect(58.57864, 58.57864, 341.42136, 341.42136), op.localClipRect);
            EXPECT_EQ(Rect(0, 0, 400, 400), op.unmappedBounds);
            expectedMatrix.loadIdentity();
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
    });
    EXPECT_EQ(3, count);
}

} // namespace uirenderer
} // namespace android
