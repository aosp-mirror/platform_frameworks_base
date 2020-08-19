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

#include "TestSceneBase.h"
#include "tests/common/TestListViewSceneBase.h"
#include "hwui/Paint.h"
#include "SkColor.h"
#include <shader/LinearGradientShader.h>

class ListOfFadedTextAnimation;

static TestScene::Registrar _ListOfFadedTextAnimation(TestScene::Info{
        "fadingedges",
        "A mock ListView of scrolling text with faded edge. Doesn't re-bind/re-record views"
        "as they are recycled, so won't upload much content (either glyphs, or bitmaps).",
        TestScene::simpleCreateScene<ListOfFadedTextAnimation>});

class ListOfFadedTextAnimation : public TestListViewSceneBase {
    void createListItem(RenderProperties& props, Canvas& canvas, int id, int itemWidth,
                        int itemHeight) override {
        canvas.drawColor(Color::White, SkBlendMode::kSrcOver);
        int length = dp(100);
        canvas.saveLayer(0, 0, length, itemHeight, nullptr, SaveFlags::HasAlphaLayer);
        Paint textPaint;
        textPaint.getSkFont().setSize(dp(20));
        textPaint.setAntiAlias(true);
        TestUtils::drawUtf8ToCanvas(&canvas, "not that long long text", textPaint, dp(10), dp(30));

        SkPoint pts[2];
        pts[0].set(0, 0);
        pts[1].set(0, 1);

        SkMatrix matrix;
        matrix.setScale(1, length);
        matrix.postRotate(-90);

        std::vector<SkColor4f> vColors(2);
        vColors[0] = SkColors::kBlack;
        vColors[1] = SkColors::kTransparent;

        sk_sp<LinearGradientShader> linearGradientShader = sk_make_sp<LinearGradientShader>(
                    pts,
                    vColors,
                    SkColorSpace::MakeSRGB(),
                    nullptr,
                    SkTileMode::kClamp,
                    0,
                    &matrix
                );

        Paint fadingPaint;
        fadingPaint.setShader(linearGradientShader);
        fadingPaint.setBlendMode(SkBlendMode::kDstOut);
        canvas.drawRect(0, 0, length, itemHeight, fadingPaint);
        canvas.restore();
    }
};
