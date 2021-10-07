/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <SkFont.h>
#include <cstdio>
#include "TestSceneBase.h"
#include "hwui/Paint.h"
#include "tests/common/TestUtils.h"

class StretchyListViewAnimation;
class StretchyListViewHolePunch;
class StretchyUniformListView;
class StretchyUniformListViewHolePunch;
class StretchyUniformLayerListView;
class StretchyUniformLayerListViewHolePunch;

static TestScene::Registrar _StretchyListViewAnimation(TestScene::Info{
        "stretchylistview",
        "A mock ListView of scrolling content that's stretching. Doesn't re-bind/re-record views "
        "as they are recycled, so won't upload much content (either glyphs, or bitmaps).",
        TestScene::simpleCreateScene<StretchyListViewAnimation>});

static TestScene::Registrar _StretchyListViewHolePunch(TestScene::Info{
        "stretchylistview_holepunch",
        "A mock ListView of scrolling content that's stretching. Includes a hole punch",
        TestScene::simpleCreateScene<StretchyListViewHolePunch>});

static TestScene::Registrar _StretchyUniformListView(TestScene::Info{
        "stretchylistview_uniform",
        "A mock ListView of scrolling content that's stretching using a uniform stretch effect.",
        TestScene::simpleCreateScene<StretchyUniformListView>});

static TestScene::Registrar _StretchyUniformListViewHolePunch(TestScene::Info{
        "stretchylistview_uniform_holepunch",
        "A mock ListView of scrolling content that's stretching using a uniform stretch effect. "
        "Includes a hole punch",
        TestScene::simpleCreateScene<StretchyUniformListViewHolePunch>});

static TestScene::Registrar _StretchyUniformLayerListView(TestScene::Info{
        "stretchylistview_uniform_layer",
        "A mock ListView of scrolling content that's stretching using a uniform stretch effect. "
        "Uses a layer",
        TestScene::simpleCreateScene<StretchyUniformLayerListView>});

static TestScene::Registrar _StretchyUniformLayerListViewHolePunch(TestScene::Info{
        "stretchylistview_uniform_layer_holepunch",
        "A mock ListView of scrolling content that's stretching using a uniform stretch effect. "
        "Uses a layer & includes a hole punch",
        TestScene::simpleCreateScene<StretchyUniformLayerListViewHolePunch>});

class StretchyListViewAnimation : public TestScene {
protected:
    virtual StretchEffectBehavior stretchBehavior() { return StretchEffectBehavior::Shader; }
    virtual bool haveHolePunch() { return false; }
    virtual bool forceLayer() { return false; }

private:
    int mItemHeight;
    int mItemSpacing;
    int mItemWidth;
    int mItemLeft;
    sp<RenderNode> mListView;
    std::vector<sp<RenderNode> > mListItems;

    sk_sp<Bitmap> createRandomCharIcon(int cardHeight) {
        SkBitmap skBitmap;
        int size = cardHeight - (dp(10) * 2);
        sk_sp<Bitmap> bitmap(TestUtils::createBitmap(size, size, &skBitmap));
        SkCanvas canvas(skBitmap);
        canvas.clear(0);

        SkPaint paint;
        paint.setAntiAlias(true);
        SkColor randomColor = BrightColors[rand() % BrightColorsCount];
        paint.setColor(randomColor);
        canvas.drawCircle(size / 2, size / 2, size / 2, paint);

        bool bgDark =
                SkColorGetR(randomColor) + SkColorGetG(randomColor) + SkColorGetB(randomColor) <
                128 * 3;
        paint.setColor(bgDark ? Color::White : Color::Grey_700);

        SkFont font;
        font.setSize(size / 2);
        char charToShow = 'A' + (rand() % 26);
        const SkPoint pos = {SkIntToScalar(size / 2),
                             /*approximate centering*/ SkFloatToScalar(size * 0.7f)};
        canvas.drawSimpleText(&charToShow, 1, SkTextEncoding::kUTF8, pos.fX, pos.fY, font, paint);
        return bitmap;
    }

    static sk_sp<Bitmap> createBoxBitmap(bool filled) {
        int size = dp(20);
        int stroke = dp(2);
        SkBitmap skBitmap;
        auto bitmap = TestUtils::createBitmap(size, size, &skBitmap);
        SkCanvas canvas(skBitmap);
        canvas.clear(Color::Transparent);

        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor(filled ? Color::Yellow_500 : Color::Grey_700);
        paint.setStyle(filled ? SkPaint::kStrokeAndFill_Style : SkPaint::kStroke_Style);
        paint.setStrokeWidth(stroke);
        canvas.drawRect(SkRect::MakeLTRB(stroke, stroke, size - stroke, size - stroke), paint);
        return bitmap;
    }

    void createListItem(RenderProperties& props, Canvas& canvas, int cardId, int itemWidth,
                        int itemHeight) {
        static sk_sp<Bitmap> filledBox(createBoxBitmap(true));
        static sk_sp<Bitmap> strokedBox(createBoxBitmap(false));
        const bool addHolePunch = cardId == 2 && haveHolePunch();
        // TODO: switch to using round rect clipping, once merging correctly handles that
        Paint roundRectPaint;
        roundRectPaint.setAntiAlias(true);
        roundRectPaint.setColor(Color::White);
        if (addHolePunch) {
            // Punch a hole but then cover it up, we don't want to actually see it
            canvas.punchHole(SkRRect::MakeRect(SkRect::MakeWH(itemWidth, itemHeight)));
        }
        canvas.drawRoundRect(0, 0, itemWidth, itemHeight, dp(6), dp(6), roundRectPaint);

        Paint textPaint;
        textPaint.setColor(rand() % 2 ? Color::Black : Color::Grey_500);
        textPaint.getSkFont().setSize(dp(20));
        textPaint.setAntiAlias(true);
        char buf[256];
        snprintf(buf, sizeof(buf), "This card is #%d", cardId);
        TestUtils::drawUtf8ToCanvas(&canvas, buf, textPaint, itemHeight, dp(25));
        textPaint.getSkFont().setSize(dp(15));
        if (addHolePunch) {
            TestUtils::drawUtf8ToCanvas(&canvas, "I have a hole punch", textPaint, itemHeight,
                                        dp(45));
        } else {
            TestUtils::drawUtf8ToCanvas(&canvas, "This is some more text on the card", textPaint,
                                        itemHeight, dp(45));
        }

        auto randomIcon = createRandomCharIcon(itemHeight);
        canvas.drawBitmap(*randomIcon, dp(10), dp(10), nullptr);

        auto box = rand() % 2 ? filledBox : strokedBox;
        canvas.drawBitmap(*box, itemWidth - dp(10) - box->width(), dp(10), nullptr);
    }

    void createContent(int width, int height, Canvas& canvas) override {
        srand(0);
        mItemHeight = dp(60);
        mItemSpacing = dp(16);
        mItemWidth = std::min((height - mItemSpacing * 2), (int)dp(300));
        mItemLeft = (width - mItemWidth) / 2;
        int heightWithSpacing = mItemHeight + mItemSpacing;
        for (int y = 0; y < height + (heightWithSpacing - 1); y += heightWithSpacing) {
            int id = mListItems.size();
            auto node = TestUtils::createNode(mItemLeft, y, mItemLeft + mItemWidth, y + mItemHeight,
                                              [this, id](RenderProperties& props, Canvas& canvas) {
                                                  createListItem(props, canvas, id, mItemWidth,
                                                                 mItemHeight);
                                              });
            mListItems.push_back(node);
        }
        mListView = TestUtils::createNode(0, 0, width, height,
                                          [this](RenderProperties& props, Canvas& canvas) {
                                              for (size_t ci = 0; ci < mListItems.size(); ci++) {
                                                  canvas.drawRenderNode(mListItems[ci].get());
                                              }
                                          });

        canvas.drawColor(Color::Grey_500, SkBlendMode::kSrcOver);
        canvas.drawRenderNode(mListView.get());
    }

    void doFrame(int frameNr) override {
        if (frameNr == 0) {
            Properties::setStretchEffectBehavior(stretchBehavior());
            if (forceLayer()) {
                mListView->mutateStagingProperties().mutateLayerProperties().setType(
                        LayerType::RenderLayer);
            }
        }
        auto& props = mListView->mutateStagingProperties();
        auto& stretch = props.mutateLayerProperties().mutableStretchEffect();
        stretch.setEmpty();
        frameNr = frameNr % 150;
        // Animate from 0f to .1f
        const float sY = (frameNr > 75 ? 150 - frameNr : frameNr) / 1500.f;
        stretch.mergeWith({{.fX = 0, .fY = sY},
                           static_cast<float>(props.getWidth()),
                           static_cast<float>(props.getHeight())});
        mListView->setPropertyFieldsDirty(RenderNode::GENERIC);
    }
};

class StretchyListViewHolePunch : public StretchyListViewAnimation {
    bool haveHolePunch() override { return true; }
};

class StretchyUniformListView : public StretchyListViewAnimation {
    StretchEffectBehavior stretchBehavior() override { return StretchEffectBehavior::UniformScale; }
};

class StretchyUniformListViewHolePunch : public StretchyListViewAnimation {
    StretchEffectBehavior stretchBehavior() override { return StretchEffectBehavior::UniformScale; }
    bool haveHolePunch() override { return true; }
};

class StretchyUniformLayerListView : public StretchyListViewAnimation {
    StretchEffectBehavior stretchBehavior() override { return StretchEffectBehavior::UniformScale; }
    bool forceLayer() override { return true; }
};

class StretchyUniformLayerListViewHolePunch : public StretchyListViewAnimation {
    StretchEffectBehavior stretchBehavior() override { return StretchEffectBehavior::UniformScale; }
    bool haveHolePunch() override { return true; }
    bool forceLayer() override { return true; }
};