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

#include "TestSceneBase.h"
#include "utils/Color.h"

#include <cstdio>

class ListViewAnimation;

static TestScene::Registrar _ListView(TestScene::Info{
    "listview",
    "A mock ListView of scrolling content. Doesn't re-bind/re-record views as they are recycled, so"
    "won't upload much content (either glyphs, or bitmaps).",
    TestScene::simpleCreateScene<ListViewAnimation>
});

class ListViewAnimation : public TestScene {
public:
    int cardHeight;
    int cardSpacing;
    int cardWidth;
    int cardLeft;
    sp<RenderNode> listView;
    std::vector< sp<RenderNode> > cards;
    void createContent(int width, int height, TestCanvas& canvas) override {
        srand(0);
        cardHeight = dp(60);
        cardSpacing = dp(16);
        cardWidth = std::min((height - cardSpacing * 2), (int)dp(300));
        cardLeft = (width - cardWidth) / 2;

        for (int y = 0; y < height + (cardHeight + cardSpacing - 1); y += (cardHeight + cardSpacing)) {
            cards.push_back(createCard(cards.size(), y));
        }
        listView = TestUtils::createNode(0, 0, width, height,
                [this](RenderProperties& props, TestCanvas& canvas) {
            for (size_t ci = 0; ci < cards.size(); ci++) {
                canvas.drawRenderNode(cards[ci].get());
            }
        });

        canvas.drawColor(Color::Grey_500, SkXfermode::kSrcOver_Mode);
        canvas.drawRenderNode(listView.get());
    }

    void doFrame(int frameNr) override {
        int scrollPx = dp(frameNr) * 3;
        int cardIndexOffset = scrollPx / (cardSpacing + cardHeight);
        int pxOffset = -(scrollPx % (cardSpacing + cardHeight));

        TestCanvas canvas(
                listView->stagingProperties().getWidth(),
                listView->stagingProperties().getHeight());
        for (size_t ci = 0; ci < cards.size(); ci++) {
            // update card position
            auto card = cards[(ci + cardIndexOffset) % cards.size()];
            int top = ((int)ci) * (cardSpacing + cardHeight) + pxOffset;
            card->mutateStagingProperties().setLeftTopRightBottom(
                    cardLeft, top, cardLeft + cardWidth, top + cardHeight);
            card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

            // draw it to parent DisplayList
            canvas.drawRenderNode(cards[ci].get());
        }
        listView->setStagingDisplayList(canvas.finishRecording(), nullptr);
    }
private:
    SkBitmap createRandomCharIcon() {
        int size = cardHeight - (dp(10) * 2);
        SkBitmap bitmap = TestUtils::createSkBitmap(size, size);
        SkCanvas canvas(bitmap);
        canvas.clear(0);

        SkPaint paint;
        paint.setAntiAlias(true);
        SkColor randomColor = BrightColors[rand() % BrightColorsCount];
        paint.setColor(randomColor);
        canvas.drawCircle(size / 2, size / 2, size / 2, paint);

        bool bgDark = SkColorGetR(randomColor) + SkColorGetG(randomColor) + SkColorGetB(randomColor)
                < 128 * 3;
        paint.setColor(bgDark ? Color::White : Color::Grey_700);
        paint.setTextAlign(SkPaint::kCenter_Align);
        paint.setTextSize(size / 2);
        char charToShow = 'A' + (rand() % 26);
        canvas.drawText(&charToShow, 1, size / 2, /*approximate centering*/ size * 0.7, paint);
        return bitmap;
    }

    static SkBitmap createBoxBitmap(bool filled) {
        int size = dp(20);
        int stroke = dp(2);
        SkBitmap bitmap = TestUtils::createSkBitmap(size, size);
        SkCanvas canvas(bitmap);
        canvas.clear(Color::Transparent);

        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor(filled ? Color::Yellow_500 : Color::Grey_700);
        paint.setStyle(filled ? SkPaint::kStrokeAndFill_Style : SkPaint::kStroke_Style);
        paint.setStrokeWidth(stroke);
        canvas.drawRect(SkRect::MakeLTRB(stroke, stroke, size - stroke, size - stroke), paint);
        return bitmap;
    }

    sp<RenderNode> createCard(int cardId, int top) {
        return TestUtils::createNode(cardLeft, top, cardLeft + cardWidth, top + cardHeight,
                [this, cardId](RenderProperties& props, TestCanvas& canvas) {
            static SkBitmap filledBox = createBoxBitmap(true);
            static SkBitmap strokedBox = createBoxBitmap(false);

            // TODO: switch to using round rect clipping, once merging correctly handles that
            SkPaint roundRectPaint;
            roundRectPaint.setAntiAlias(true);
            roundRectPaint.setColor(Color::White);
            canvas.drawRoundRect(0, 0, cardWidth, cardHeight, dp(6), dp(6), roundRectPaint);

            SkPaint textPaint;
            textPaint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
            textPaint.setColor(rand() % 2 ? Color::Black : Color::Grey_500);
            textPaint.setTextSize(dp(20));
            textPaint.setAntiAlias(true);
            char buf[256];
            snprintf(buf, sizeof(buf), "This card is #%d", cardId);
            TestUtils::drawUtf8ToCanvas(&canvas, buf, textPaint, cardHeight, dp(25));
            textPaint.setTextSize(dp(15));
            TestUtils::drawUtf8ToCanvas(&canvas, "This is some more text on the card", textPaint,
                    cardHeight, dp(45));

            canvas.drawBitmap(createRandomCharIcon(), dp(10), dp(10), nullptr);

            const SkBitmap& boxBitmap = rand() % 2 ? filledBox : strokedBox;
            canvas.drawBitmap(boxBitmap, cardWidth - dp(10) - boxBitmap.width(), dp(10), nullptr);
        });
    }
};
