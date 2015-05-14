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

#include "TestContext.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) {
        return new AnimationContext(clock);
    }
};

static DisplayListRenderer* startRecording(RenderNode* node) {
    DisplayListRenderer* renderer = new DisplayListRenderer();
    renderer->setViewport(node->getWidth(), node->getHeight());
    renderer->prepare(false);
    return renderer;
}

static void endRecording(DisplayListRenderer* renderer, RenderNode* node) {
    renderer->finish();
    node->setStagingDisplayList(renderer->finishRecording());
    delete renderer;
}

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

int main(int argc, char* argv[]) {
    createTestEnvironment();

    // create the native surface
    const int width = gDisplay.w;
    const int height = gDisplay.h;
    sp<SurfaceControl> control = createWindow(width, height);
    sp<Surface> surface = control->getSurface();

    RenderNode* rootNode = new RenderNode();
    rootNode->incStrong(0);
    rootNode->mutateStagingProperties().setLeftTopRightBottom(0, 0, width, height);
    rootNode->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    rootNode->mutateStagingProperties().setClipToBounds(false);
    rootNode->setPropertyFieldsDirty(RenderNode::GENERIC);

    ContextFactory factory;
    RenderProxy* proxy = new RenderProxy(false, rootNode, &factory);
    proxy->loadSystemProperties();
    proxy->initialize(surface);
    float lightX = width / 2.0;
    proxy->setup(width, height, (Vector3){lightX, dp(-200.0f), dp(800.0f)},
            dp(800.0f), 255 * 0.075, 255 * 0.15);

    android::uirenderer::Rect DUMMY;

    std::vector< sp<RenderNode> > cards;

    DisplayListRenderer* renderer = startRecording(rootNode);
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
    endRecording(renderer, rootNode);

    for (int i = 0; i < 150; i++) {
        ATRACE_NAME("UI-Draw Frame");
        for (int ci = 0; ci < cards.size(); ci++) {
            cards[ci]->mutateStagingProperties().setTranslationX(i);
            cards[ci]->mutateStagingProperties().setTranslationY(i);
            cards[ci]->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        }
        nsecs_t frameTimeNs = systemTime(CLOCK_MONOTONIC);
        proxy->syncAndDrawFrame(frameTimeNs, 0, gDisplay.density);
        usleep(12000);
    }

    sleep(5);

    delete proxy;
    rootNode->decStrong(0);

    printf("Success!\n");
    return 0;
}
