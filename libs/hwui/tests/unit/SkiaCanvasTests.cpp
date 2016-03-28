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

#include "tests/common/TestUtils.h"

#include <gtest/gtest.h>
#include <RecordingCanvas.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>

using namespace android;
using namespace android::uirenderer;

/**
 * Verify that we get the same culling bounds for text for (1) drawing glyphs
 * directly to a Canvas or (2) going through a SkPicture as an intermediate step.
 */
TEST(SkiaCanvasProxy, drawGlyphsViaPicture) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        // setup test variables
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        static const char* text = "testing text bounds";

        // draw text directly into Recording canvas
        TestUtils::drawUtf8ToCanvas(&canvas, text, paint, 25, 25);

        // record the same text draw into a SkPicture and replay it into a Recording canvas
        SkPictureRecorder recorder;
        SkCanvas* skCanvas = recorder.beginRecording(200, 200, NULL, 0);
        std::unique_ptr<Canvas> pictCanvas(Canvas::create_canvas(skCanvas));
        TestUtils::drawUtf8ToCanvas(pictCanvas.get(), text, paint, 25, 25);
        SkAutoTUnref<const SkPicture> picture(recorder.endRecording());

        canvas.asSkCanvas()->drawPicture(picture);
    });

    // verify that the text bounds and matrices match
    ASSERT_EQ(2U, dl->getOps().size());
    auto directOp = dl->getOps()[0];
    auto pictureOp = dl->getOps()[1];
    ASSERT_EQ(RecordedOpId::TextOp, directOp->opId);
    EXPECT_EQ(directOp->opId, pictureOp->opId);
    EXPECT_EQ(directOp->unmappedBounds, pictureOp->unmappedBounds);
    EXPECT_EQ(directOp->localMatrix, pictureOp->localMatrix);
}
