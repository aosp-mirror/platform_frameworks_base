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

#include "SkBlendMode.h"
#include "TestSceneBase.h"
#include "tests/common/BitmapAllocationTestUtils.h"
#include "hwui/Paint.h"

class TvApp;
class TvAppNoRoundedCorner;
class TvAppColorFilter;
class TvAppNoRoundedCornerColorFilter;

static bool _TvApp(BitmapAllocationTestUtils::registerBitmapAllocationScene<TvApp>(
        "tvapp",
        "A dense grid of cards:"
        "with rounded corner, using overlay RenderNode for dimming."));

static bool _TvAppNoRoundedCorner(
        BitmapAllocationTestUtils::registerBitmapAllocationScene<TvAppNoRoundedCorner>(
                "tvapp_norc",
                "A dense grid of cards:"
                "no rounded corner, using overlay RenderNode for dimming"));

static bool _TvAppColorFilter(
        BitmapAllocationTestUtils::registerBitmapAllocationScene<TvAppColorFilter>(
                "tvapp_cf",
                "A dense grid of cards:"
                "with rounded corner, using ColorFilter for dimming"));

static bool _TvAppNoRoundedCornerColorFilter(
        BitmapAllocationTestUtils::registerBitmapAllocationScene<TvAppNoRoundedCornerColorFilter>(
                "tvapp_norc_cf",
                "A dense grid of cards:"
                "no rounded corner, using ColorFilter for dimming"));

class TvApp : public TestScene {
public:
    explicit TvApp(BitmapAllocationTestUtils::BitmapAllocator allocator)
            : TestScene(), mAllocator(allocator) {}

    sp<RenderNode> mBg;
    std::vector<sp<RenderNode>> mCards;
    std::vector<sp<RenderNode>> mInfoAreas;
    std::vector<sp<RenderNode>> mImages;
    std::vector<sp<RenderNode>> mOverlays;
    std::vector<sk_sp<Bitmap>> mCachedBitmaps;
    BitmapAllocationTestUtils::BitmapAllocator mAllocator;
    sk_sp<Bitmap> mSingleBitmap;
    int mSeed = 0;
    int mSeed2 = 0;

    void createContent(int width, int height, Canvas& canvas) override {
        mBg = createBitmapNode(canvas, 0xFF9C27B0, 0, 0, width, height);
        canvas.drawRenderNode(mBg.get());

        canvas.enableZ(true);
        mSingleBitmap = mAllocator(dp(160), dp(120), kRGBA_8888_SkColorType,
                                   [](SkBitmap& skBitmap) { skBitmap.eraseColor(0xFF0000FF); });

        for (int y = dp(18) - dp(178); y < height - dp(18); y += dp(178)) {
            bool isFirstCard = true;
            for (int x = dp(18); x < width - dp(18); x += dp(178)) {
                sp<RenderNode> card = createCard(x, y, dp(160), dp(160), isFirstCard);
                isFirstCard = false;
                canvas.drawRenderNode(card.get());
                mCards.push_back(card);
            }
        }
        canvas.enableZ(false);
    }

    void doFrame(int frameNr) override {
        size_t numCards = mCards.size();
        for (size_t ci = 0; ci < numCards; ci++) {
            updateCard(ci, frameNr);
        }
    }

private:
    sp<RenderNode> createBitmapNode(Canvas& canvas, SkColor color, int left, int top, int width,
                                    int height) {
        return TestUtils::createNode(
                left, top, left + width, top + height,
                [this, width, height, color](RenderProperties& props, Canvas& canvas) {
                    sk_sp<Bitmap> bitmap =
                            mAllocator(width, height, kRGBA_8888_SkColorType,
                                       [color](SkBitmap& skBitmap) { skBitmap.eraseColor(color); });
                    canvas.drawBitmap(*bitmap, 0, 0, nullptr);
                });
    }

    sp<RenderNode> createSharedBitmapNode(Canvas& canvas, int left, int top, int width, int height,
                                          sk_sp<Bitmap> bitmap) {
        return TestUtils::createNode(left, top, left + width, top + height,
                                     [bitmap](RenderProperties& props, Canvas& canvas) {
                                         canvas.drawBitmap(*bitmap, 0, 0, nullptr);
                                     });
    }

    sp<RenderNode> createInfoNode(Canvas& canvas, int left, int top, int width, int height,
                                  const char* text, const char* text2) {
        return TestUtils::createNode(left, top, left + width, top + height,
                                     [text, text2](RenderProperties& props, Canvas& canvas) {
                                         canvas.drawColor(0xFFFFEEEE, SkBlendMode::kSrcOver);

                                         Paint paint;
                                         paint.setAntiAlias(true);
                                         paint.getSkFont().setSize(24);

                                         paint.setColor(Color::Black);
                                         TestUtils::drawUtf8ToCanvas(&canvas, text, paint, 10, 30);
                                         paint.getSkFont().setSize(20);
                                         TestUtils::drawUtf8ToCanvas(&canvas, text2, paint, 10, 54);

                                     });
    }

    sp<RenderNode> createColorNode(Canvas& canvas, int left, int top, int width, int height,
                                   SkColor color) {
        return TestUtils::createNode(left, top, left + width, top + height,
                                     [color](RenderProperties& props, Canvas& canvas) {
                                         canvas.drawColor(color, SkBlendMode::kSrcOver);
                                     });
    }

    virtual bool useSingleBitmap() { return false; }

    virtual float roundedCornerRadius() { return dp(2); }

    // when true, use overlay RenderNode for dimming, otherwise apply a ColorFilter to dim image
    virtual bool useOverlay() { return true; }

    sp<RenderNode> createCard(int x, int y, int width, int height, bool selected) {
        return TestUtils::createNode(x, y, x + width, y + height, [width, height, selected, this](
                                                                          RenderProperties& props,
                                                                          Canvas& canvas) {
            if (selected) {
                props.setElevation(dp(16));
                props.setScaleX(1.2);
                props.setScaleY(1.2);
            }
            props.mutableOutline().setRoundRect(0, 0, width, height, roundedCornerRadius(), 1);
            props.mutableOutline().setShouldClip(true);

            sk_sp<Bitmap> bitmap =
                    useSingleBitmap() ? mSingleBitmap
                                      : mAllocator(width, dp(120), kRGBA_8888_SkColorType,
                                                   [this](SkBitmap& skBitmap) {
                                                       skBitmap.eraseColor(0xFF000000 |
                                                                           ((mSeed << 3) & 0xFF));
                                                   });
            sp<RenderNode> cardImage = createSharedBitmapNode(canvas, 0, 0, width, dp(120), bitmap);
            canvas.drawRenderNode(cardImage.get());
            mCachedBitmaps.push_back(bitmap);
            mImages.push_back(cardImage);

            char buffer[128];
            sprintf(buffer, "Video %d-%d", mSeed, mSeed + 1);
            mSeed++;
            char buffer2[128];
            sprintf(buffer2, "Studio %d", mSeed2++);
            sp<RenderNode> infoArea =
                    createInfoNode(canvas, 0, dp(120), width, height, buffer, buffer2);
            canvas.drawRenderNode(infoArea.get());
            mInfoAreas.push_back(infoArea);

            if (useOverlay()) {
                sp<RenderNode> overlayColor =
                        createColorNode(canvas, 0, 0, width, height, 0x00000000);
                canvas.drawRenderNode(overlayColor.get());
                mOverlays.push_back(overlayColor);
            }
        });
    }

    void updateCard(int ci, int curFrame) {
        // updating card's translation Y
        sp<RenderNode> card = mCards[ci];
        card->setPropertyFieldsDirty(RenderNode::Y);
        card->mutateStagingProperties().setTranslationY(curFrame % 150);

        // re-recording card's canvas, not necessary but to add some burden to CPU
        std::unique_ptr<Canvas> cardcanvas(Canvas::create_recording_canvas(
                card->stagingProperties().getWidth(), card->stagingProperties().getHeight(),
                card.get()));
        sp<RenderNode> image = mImages[ci];
        sp<RenderNode> infoArea = mInfoAreas[ci];
        cardcanvas->drawRenderNode(infoArea.get());

        if (useOverlay()) {
            cardcanvas->drawRenderNode(image.get());
            // re-recording card overlay's canvas, animating overlay color alpha
            sp<RenderNode> overlay = mOverlays[ci];
            std::unique_ptr<Canvas> canvas(
                    Canvas::create_recording_canvas(overlay->stagingProperties().getWidth(),
                                                    overlay->stagingProperties().getHeight(),
                                                    overlay.get()));
            canvas->drawColor((curFrame % 150) << 24, SkBlendMode::kSrcOver);
            canvas->finishRecording(overlay.get());
            cardcanvas->drawRenderNode(overlay.get());
        } else {
            // re-recording image node's canvas, animating ColorFilter
            std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(
                    image->stagingProperties().getWidth(), image->stagingProperties().getHeight(),
                    image.get()));
            Paint paint;
            sk_sp<SkColorFilter> filter(
                    SkColorFilters::Blend((curFrame % 150) << 24, SkBlendMode::kSrcATop));
            paint.setColorFilter(filter);
            sk_sp<Bitmap> bitmap = mCachedBitmaps[ci];
            canvas->drawBitmap(*bitmap, 0, 0, &paint);
            canvas->finishRecording(image.get());
            cardcanvas->drawRenderNode(image.get());
        }

        cardcanvas->finishRecording(card.get());
    }
};

class TvAppNoRoundedCorner : public TvApp {
public:
    explicit TvAppNoRoundedCorner(BitmapAllocationTestUtils::BitmapAllocator allocator) : TvApp(allocator) {}

private:
    virtual float roundedCornerRadius() override { return dp(0); }
};

class TvAppColorFilter : public TvApp {
public:
    explicit TvAppColorFilter(BitmapAllocationTestUtils::BitmapAllocator allocator) : TvApp(allocator) {}

private:
    virtual bool useOverlay() override { return false; }
};

class TvAppNoRoundedCornerColorFilter : public TvApp {
public:
    explicit TvAppNoRoundedCornerColorFilter(BitmapAllocationTestUtils::BitmapAllocator allocator)
            : TvApp(allocator) {}

private:
    virtual float roundedCornerRadius() override { return dp(0); }

    virtual bool useOverlay() override { return false; }
};
