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

#include <cutils/log.h>
#include <gui/Surface.h>
#include <ui/PixelFormat.h>

#include <AnimationContext.h>
#include <DisplayListCanvas.h>
#include <RecordingCanvas.h>
#include <RenderNode.h>
#include <renderthread/RenderProxy.h>
#include <renderthread/RenderTask.h>

#include "Benchmark.h"
#include "TestContext.h"

#include "protos/hwui.pb.h"

#include <stdio.h>
#include <unistd.h>
#include <getopt.h>
#include <vector>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::test;

#if HWUI_NEW_OPS
typedef RecordingCanvas TestCanvas;
#else
typedef DisplayListCanvas TestCanvas;
#endif


class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) override {
        return new AnimationContext(clock);
    }
};

static TestCanvas* startRecording(RenderNode* node) {
    TestCanvas* renderer = new TestCanvas(
            node->stagingProperties().getWidth(), node->stagingProperties().getHeight());
    return renderer;
}

static void endRecording(TestCanvas* renderer, RenderNode* node) {
    node->setStagingDisplayList(renderer->finishRecording());
    delete renderer;
}

class TreeContentAnimation {
public:
    virtual ~TreeContentAnimation() {}
    int frameCount = 150;
    virtual int getFrameCount() { return frameCount; }
    virtual void setFrameCount(int fc) {
        if (fc > 0) {
            frameCount = fc;
        }
    }
    virtual void createContent(int width, int height, TestCanvas* renderer) = 0;
    virtual void doFrame(int frameNr) = 0;

    template <class T>
    static void run(const BenchmarkOptions& opts) {
        // Switch to the real display
        gDisplay = getBuiltInDisplay();

        T animation;
        animation.setFrameCount(opts.count);

        TestContext testContext;

        // create the native surface
        const int width = gDisplay.w;
        const int height = gDisplay.h;
        sp<Surface> surface = testContext.surface();

        RenderNode* rootNode = new RenderNode();
        rootNode->incStrong(nullptr);
        rootNode->mutateStagingProperties().setLeftTopRightBottom(0, 0, width, height);
        rootNode->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        rootNode->mutateStagingProperties().setClipToBounds(false);
        rootNode->setPropertyFieldsDirty(RenderNode::GENERIC);

        ContextFactory factory;
        std::unique_ptr<RenderProxy> proxy(new RenderProxy(false, rootNode, &factory));
        proxy->loadSystemProperties();
        proxy->initialize(surface);
        float lightX = width / 2.0;
        proxy->setup(width, height, dp(800.0f), 255 * 0.075, 255 * 0.15);
        proxy->setLightCenter((Vector3){lightX, dp(-200.0f), dp(800.0f)});

        android::uirenderer::Rect DUMMY;

        TestCanvas* renderer = startRecording(rootNode);
        animation.createContent(width, height, renderer);
        endRecording(renderer, rootNode);

        // Do a few cold runs then reset the stats so that the caches are all hot
        for (int i = 0; i < 3; i++) {
            testContext.waitForVsync();
            proxy->syncAndDrawFrame();
        }
        proxy->resetProfileInfo();

        for (int i = 0; i < animation.getFrameCount(); i++) {
            testContext.waitForVsync();

            ATRACE_NAME("UI-Draw Frame");
            nsecs_t vsync = systemTime(CLOCK_MONOTONIC);
            UiFrameInfoBuilder(proxy->frameInfo())
                    .setVsync(vsync, vsync);
            animation.doFrame(i);
            proxy->syncAndDrawFrame();
        }

        proxy->dumpProfileInfo(STDOUT_FILENO, 0);
        rootNode->decStrong(nullptr);
    }
};

class ShadowGridAnimation : public TreeContentAnimation {
public:
    std::vector< sp<RenderNode> > cards;
    void createContent(int width, int height, TestCanvas* renderer) override {
        renderer->drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        renderer->insertReorderBarrier(true);

        for (int x = dp(16); x < (width - dp(116)); x += dp(116)) {
            for (int y = dp(16); y < (height - dp(116)); y += dp(116)) {
                sp<RenderNode> card = createCard(x, y, dp(100), dp(100));
                renderer->drawRenderNode(card.get());
                cards.push_back(card);
            }
        }

        renderer->insertReorderBarrier(false);
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        for (size_t ci = 0; ci < cards.size(); ci++) {
            cards[ci]->mutateStagingProperties().setTranslationX(curFrame);
            cards[ci]->mutateStagingProperties().setTranslationY(curFrame);
            cards[ci]->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        }
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(x, y, x + width, y + height);
        node->mutateStagingProperties().setElevation(dp(16));
        node->mutateStagingProperties().mutableOutline().setRoundRect(0, 0, width, height, dp(10), 1);
        node->mutateStagingProperties().mutableOutline().setShouldClip(true);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y | RenderNode::Z);

        TestCanvas* renderer = startRecording(node.get());
        renderer->drawColor(0xFFEEEEEE, SkXfermode::kSrcOver_Mode);
        endRecording(renderer, node.get());
        return node;
    }
};
static Benchmark _ShadowGrid(BenchmarkInfo{
    "shadowgrid",
    "A grid of rounded rects that cast a shadow. Simplified scenario of an "
    "Android TV-style launcher interface. High CPU/GPU load.",
    TreeContentAnimation::run<ShadowGridAnimation>
});

class ShadowGrid2Animation : public TreeContentAnimation {
public:
    std::vector< sp<RenderNode> > cards;
    void createContent(int width, int height, TestCanvas* renderer) override {
        renderer->drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        renderer->insertReorderBarrier(true);

        for (int x = dp(8); x < (width - dp(58)); x += dp(58)) {
            for (int y = dp(8); y < (height - dp(58)); y += dp(58)) {
                sp<RenderNode> card = createCard(x, y, dp(50), dp(50));
                renderer->drawRenderNode(card.get());
                cards.push_back(card);
            }
        }

        renderer->insertReorderBarrier(false);
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        for (size_t ci = 0; ci < cards.size(); ci++) {
            cards[ci]->mutateStagingProperties().setTranslationX(curFrame);
            cards[ci]->mutateStagingProperties().setTranslationY(curFrame);
            cards[ci]->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        }
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(x, y, x + width, y + height);
        node->mutateStagingProperties().setElevation(dp(16));
        node->mutateStagingProperties().mutableOutline().setRoundRect(0, 0, width, height, dp(6), 1);
        node->mutateStagingProperties().mutableOutline().setShouldClip(true);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y | RenderNode::Z);

        TestCanvas* renderer = startRecording(node.get());
        renderer->drawColor(0xFFEEEEEE, SkXfermode::kSrcOver_Mode);
        endRecording(renderer, node.get());
        return node;
    }
};
static Benchmark _ShadowGrid2(BenchmarkInfo{
    "shadowgrid2",
    "A dense grid of rounded rects that cast a shadow. This is a higher CPU load "
    "variant of shadowgrid. Very high CPU load, high GPU load.",
    TreeContentAnimation::run<ShadowGrid2Animation>
});

class RectGridAnimation : public TreeContentAnimation {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, TestCanvas* renderer) override {
        renderer->drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        renderer->insertReorderBarrier(true);

        card = createCard(40, 40, 200, 200);
        renderer->drawRenderNode(card.get());

        renderer->insertReorderBarrier(false);
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        card->mutateStagingProperties().setTranslationX(curFrame);
        card->mutateStagingProperties().setTranslationY(curFrame);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(x, y, x + width, y + height);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        TestCanvas* renderer = startRecording(node.get());
        renderer->drawColor(0xFFFF00FF, SkXfermode::kSrcOver_Mode);

        SkRegion region;
        for (int xOffset = 0; xOffset < width; xOffset+=2) {
            for (int yOffset = 0; yOffset < height; yOffset+=2) {
                region.op(xOffset, yOffset, xOffset + 1, yOffset + 1, SkRegion::kUnion_Op);
            }
        }

        SkPaint paint;
        paint.setColor(0xff00ffff);
        renderer->drawRegion(region, paint);

        endRecording(renderer, node.get());
        return node;
    }
};
static Benchmark _RectGrid(BenchmarkInfo{
    "rectgrid",
    "A dense grid of 1x1 rects that should visually look like a single rect. "
    "Low CPU/GPU load.",
    TreeContentAnimation::run<RectGridAnimation>
});

class OvalAnimation : public TreeContentAnimation {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, TestCanvas* renderer) override {
        renderer->drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        renderer->insertReorderBarrier(true);

        card = createCard(40, 40, 400, 400);
        renderer->drawRenderNode(card.get());

        renderer->insertReorderBarrier(false);
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        card->mutateStagingProperties().setTranslationX(curFrame);
        card->mutateStagingProperties().setTranslationY(curFrame);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(x, y, x + width, y + height);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        TestCanvas* renderer = startRecording(node.get());

        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor(0xFF000000);
        renderer->drawOval(0, 0, width, height, paint);

        endRecording(renderer, node.get());
        return node;
    }
};
static Benchmark _Oval(BenchmarkInfo{
    "oval",
    "Draws 1 oval.",
    TreeContentAnimation::run<OvalAnimation>
});

class PartialDamageTest : public TreeContentAnimation {
public:
    std::vector< sp<RenderNode> > cards;
    void createContent(int width, int height, TestCanvas* renderer) override {
        static SkColor COLORS[] = {
                0xFFF44336,
                0xFF9C27B0,
                0xFF2196F3,
                0xFF4CAF50,
        };

        renderer->drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);

        for (int x = dp(16); x < (width - dp(116)); x += dp(116)) {
            for (int y = dp(16); y < (height - dp(116)); y += dp(116)) {
                sp<RenderNode> card = createCard(x, y, dp(100), dp(100),
                        COLORS[static_cast<int>((y / dp(116))) % 4]);
                renderer->drawRenderNode(card.get());
                cards.push_back(card);
            }
        }
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        cards[0]->mutateStagingProperties().setTranslationX(curFrame);
        cards[0]->mutateStagingProperties().setTranslationY(curFrame);
        cards[0]->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        TestCanvas* renderer = startRecording(cards[0].get());
        renderer->drawColor(interpolateColor(curFrame / 150.0f, 0xFFF44336, 0xFFF8BBD0),
                SkXfermode::kSrcOver_Mode);
        endRecording(renderer, cards[0].get());
    }

    static SkColor interpolateColor(float fraction, SkColor start, SkColor end) {
        int startA = (start >> 24) & 0xff;
        int startR = (start >> 16) & 0xff;
        int startG = (start >> 8) & 0xff;
        int startB = start & 0xff;

        int endA = (end >> 24) & 0xff;
        int endR = (end >> 16) & 0xff;
        int endG = (end >> 8) & 0xff;
        int endB = end & 0xff;

        return (int)((startA + (int)(fraction * (endA - startA))) << 24) |
                (int)((startR + (int)(fraction * (endR - startR))) << 16) |
                (int)((startG + (int)(fraction * (endG - startG))) << 8) |
                (int)((startB + (int)(fraction * (endB - startB))));
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height, SkColor color) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(x, y, x + width, y + height);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        TestCanvas* renderer = startRecording(node.get());
        renderer->drawColor(color, SkXfermode::kSrcOver_Mode);
        endRecording(renderer, node.get());
        return node;
    }
};
static Benchmark _PartialDamage(BenchmarkInfo{
    "partialdamage",
    "Tests the partial invalidation path. Draws a grid of rects and animates 1 "
    "of them, should be low CPU & GPU load if EGL_EXT_buffer_age or "
    "EGL_KHR_partial_update is supported by the device & are enabled in hwui.",
    TreeContentAnimation::run<PartialDamageTest>
});


class SimpleRectGridAnimation : public TreeContentAnimation {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, TestCanvas* renderer) override {
        SkPaint paint;
        paint.setColor(0xFF00FFFF);
        renderer->drawRect(0, 0, width, height, paint);

        card = createCard(40, 40, 200, 200);
        renderer->drawRenderNode(card.get());
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        card->mutateStagingProperties().setTranslationX(curFrame);
        card->mutateStagingProperties().setTranslationY(curFrame);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(x, y, x + width, y + height);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        TestCanvas* renderer = startRecording(node.get());
        SkPaint paint;
        paint.setColor(0xFFFF00FF);
        renderer->drawRect(0, 0, width, height, paint);

        endRecording(renderer, node.get());
        return node;
    }
};
static Benchmark _SimpleRectGrid(BenchmarkInfo{
    "simplerectgrid",
    "A simple collection of rects. "
    "Low CPU/GPU load.",
    TreeContentAnimation::run<SimpleRectGridAnimation>
});
