/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <hwui/Paint.h>
#include <minikin/Layout.h>
#include <string>
#include "TestSceneBase.h"

class SaveLayer2Animation;

static TestScene::Registrar _SaveLayer(TestScene::Info{
        "savelayer2",
        "Interleaving 20 drawText/drawRect ops with saveLayer"
        "Tests the clipped saveLayer performance and FBO switching overhead.",
        TestScene::simpleCreateScene<SaveLayer2Animation>});

class SaveLayer2Animation : public TestScene {
public:
    Paint mBluePaint;
    Paint mGreenPaint;

    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(SkColorSetARGB(255, 255, 0, 0), SkBlendMode::kSrcOver);
        SkIRect bounds = SkIRect::MakeWH(width, height);
        int regions = 20;
        int smallRectHeight = (bounds.height() / regions);
        int padding = smallRectHeight / 4;
        int top = bounds.fTop;

        mBluePaint.setColor(SkColorSetARGB(255, 0, 0, 255));
        mBluePaint.getSkFont().setSize(padding);
        mGreenPaint.setColor(SkColorSetARGB(255, 0, 255, 0));
        mGreenPaint.getSkFont().setSize(padding);

        // interleave drawText and drawRect with saveLayer ops
        for (int i = 0; i < regions; i++, top += smallRectHeight) {
            canvas.saveLayer(bounds.fLeft, top, bounds.fRight, top + padding, &mBluePaint,
                             SaveFlags::ClipToLayer | SaveFlags::MatrixClip);
            canvas.drawColor(SkColorSetARGB(255, 255, 255, 0), SkBlendMode::kSrcOver);
            std::string stri = std::to_string(i);
            std::string offscreen = "offscreen line " + stri;
            TestUtils::drawUtf8ToCanvas(&canvas, offscreen.c_str(), mBluePaint, bounds.fLeft,
                    top + padding);
            canvas.restore();

            canvas.drawRect(bounds.fLeft, top + padding, bounds.fRight,
                            top + smallRectHeight - padding, mBluePaint);
            std::string onscreen = "onscreen line " + stri;
            TestUtils::drawUtf8ToCanvas(&canvas, onscreen.c_str(), mGreenPaint, bounds.fLeft,
                    top + smallRectHeight - padding);
        }
    }
    void doFrame(int frameNr) override {}
};
