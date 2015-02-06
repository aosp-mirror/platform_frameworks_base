/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <stdio.h>

#include <cutils/log.h>
#include <gui/Surface.h>
#include <ui/PixelFormat.h>

#include <AnimationContext.h>
#include <DisplayListRenderer.h>
#include <RenderNode.h>
#include <renderthread/RenderProxy.h>
#include <renderthread/RenderTask.h>

#include "TestContext.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::test;

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) override {
        return new AnimationContext(clock);
    }
};

static DisplayListRenderer* startRecording(RenderNode* node) {
    DisplayListRenderer* renderer = new DisplayListRenderer();
    renderer->setViewport(node->stagingProperties().getWidth(),
            node->stagingProperties().getHeight());
    renderer->prepare();
    return renderer;
}

static void endRecording(DisplayListRenderer* renderer, RenderNode* node) {
    renderer->finish();
    node->setStagingDisplayList(renderer->finishRecording());
    delete renderer;
}

class TreeContentAnimation {
public:
    virtual ~TreeContentAnimation() {}
    virtual int getFrameCount() { return 150; }
    virtual void createContent(int width, int height, DisplayListRenderer* renderer) = 0;
    virtual void doFrame(int frameNr) = 0;

    template <class T>
    static void run() {
        T animation;

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
        proxy->setup(width, height, (Vector3){lightX, dp(-200.0f), dp(800.0f)},
                dp(800.0f), 255 * 0.075, 255 * 0.15);

        android::uirenderer::Rect DUMMY;

        std::vector< sp<RenderNode> > cards;

        DisplayListRenderer* renderer = startRecording(rootNode);
        animation.createContent(width, height, renderer);
        endRecording(renderer, rootNode);

        for (int i = 0; i < 150; i++) {
            testContext.waitForVsync();

            ATRACE_NAME("UI-Draw Frame");
            animation.doFrame(i);
            nsecs_t frameTimeNs = systemTime(CLOCK_MONOTONIC);
            proxy->syncAndDrawFrame(frameTimeNs, 0, gDisplay.density);
        }

        sleep(5);

        rootNode->decStrong(nullptr);
    }
};

class ShadowGridAnimation : public TreeContentAnimation {
public:
    std::vector< sp<RenderNode> > cards;
    void createContent(int width, int height, DisplayListRenderer* renderer) override {
        android::uirenderer::Rect DUMMY;

        renderer->drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        renderer->insertReorderBarrier(true);

        for (int x = dp(16); x < (width - dp(116)); x += dp(116)) {
            for (int y = dp(16); y < (height - dp(116)); y += dp(116)) {
                sp<RenderNode> card = createCard(x, y, dp(100), dp(100));
                renderer->drawRenderNode(card.get(), DUMMY, 0);
                cards.push_back(card);
            }
        }

        renderer->insertReorderBarrier(false);
    }
    void doFrame(int frameNr) override {
        for (size_t ci = 0; ci < cards.size(); ci++) {
            cards[ci]->mutateStagingProperties().setTranslationX(frameNr);
            cards[ci]->mutateStagingProperties().setTranslationY(frameNr);
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

        DisplayListRenderer* renderer = startRecording(node.get());
        renderer->drawColor(0xFFEEEEEE, SkXfermode::kSrcOver_Mode);
        endRecording(renderer, node.get());
        return node;
    }
};

class RectGridAnimation : public TreeContentAnimation {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, DisplayListRenderer* renderer) override {
        android::uirenderer::Rect DUMMY;

        renderer->drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        renderer->insertReorderBarrier(true);

        card = createCard(40, 40, 200, 200);
        renderer->drawRenderNode(card.get(), DUMMY, 0);

        renderer->insertReorderBarrier(false);
    }
    void doFrame(int frameNr) override {
        card->mutateStagingProperties().setTranslationX(frameNr);
        card->mutateStagingProperties().setTranslationY(frameNr);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(x, y, x + width, y + height);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        DisplayListRenderer* renderer = startRecording(node.get());
        renderer->drawColor(0xFFFF00FF, SkXfermode::kSrcOver_Mode);

        float rects[width * height];
        int index = 0;
        for (int xOffset = 0; xOffset < width; xOffset+=2) {
            for (int yOffset = 0; yOffset < height; yOffset+=2) {
                rects[index++] = xOffset;
                rects[index++] = yOffset;
                rects[index++] = xOffset + 1;
                rects[index++] = yOffset + 1;
            }
        }
        int count = width * height;

        SkPaint paint;
        paint.setColor(0xff00ffff);
        renderer->drawRects(rects, count, &paint);

        endRecording(renderer, node.get());
        return node;
    }
};

class OvalAnimation : public TreeContentAnimation {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, DisplayListRenderer* renderer) override {
        android::uirenderer::Rect DUMMY;

        renderer->drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        renderer->insertReorderBarrier(true);

        card = createCard(40, 40, 200, 200);
        renderer->drawRenderNode(card.get(), DUMMY, 0);

        renderer->insertReorderBarrier(false);
    }

    void doFrame(int frameNr) override {
        card->mutateStagingProperties().setTranslationX(frameNr);
        card->mutateStagingProperties().setTranslationY(frameNr);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(x, y, x + width, y + height);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        DisplayListRenderer* renderer = startRecording(node.get());
        renderer->drawColor(0xFFFF00FF, SkXfermode::kSrcOver_Mode);

        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor(0xFF00FFFF);
        renderer->drawOval(0, 0, width, height, paint);

        endRecording(renderer, node.get());
        return node;
    }
};

struct cstr_cmp {
    bool operator()(const char *a, const char *b) const {
        return std::strcmp(a, b) < 0;
    }
};

typedef void (*testProc)();

std::map<const char*, testProc, cstr_cmp> gTestMap {
    {"shadowgrid", TreeContentAnimation::run<ShadowGridAnimation>},
    {"rectgrid", TreeContentAnimation::run<RectGridAnimation> },
    {"oval", TreeContentAnimation::run<OvalAnimation> },
};

int main(int argc, char* argv[]) {
    const char* testName = argc > 1 ? argv[1] : "shadowgrid";
    testProc proc = gTestMap[testName];
    if(!proc) {
        printf("Error: couldn't find test %s\n", testName);
        return 1;
    }
    proc();
    printf("Success!\n");
    return 0;
}
