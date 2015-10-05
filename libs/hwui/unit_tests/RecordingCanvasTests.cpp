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

static void playbackOps(const std::vector<DisplayListData::Chunk>& chunks,
        const std::vector<RecordedOp*>& ops, std::function<void(const RecordedOp&)> opReciever) {
    for (const DisplayListData::Chunk& chunk : chunks) {
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            opReciever(*ops[opIndex]);
        }
    }
}

TEST(RecordingCanvas, emptyPlayback) {
    auto dld = TestUtils::createDLD<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.restore();
    });
    playbackOps(dld->getChunks(), dld->getOps(), [](const RecordedOp& op) { FAIL(); });
}

TEST(RecordingCanvas, testSimpleRectRecord) {
    auto dld = TestUtils::createDLD<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.drawRect(10, 20, 90, 180, SkPaint());
    });

    int count = 0;
    playbackOps(dld->getChunks(), dld->getOps(), [&count](const RecordedOp& op) {
        count++;
        ASSERT_EQ(RecordedOpId::RectOp, op.opId);
        ASSERT_EQ(Rect(0, 0, 100, 200), op.localClipRect);
        ASSERT_EQ(Rect(10, 20, 90, 180), op.unmappedBounds);
    });
    ASSERT_EQ(1, count); // only one observed
}

TEST(RecordingCanvas, backgroundAndImage) {
    auto dld = TestUtils::createDLD<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
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
    playbackOps(dld->getChunks(), dld->getOps(), [&count](const RecordedOp& op) {
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

}
}
