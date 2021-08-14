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

#include "tests/common/TestContext.h"

#include <cutils/trace.h>

namespace android {
namespace uirenderer {
namespace test {

const ui::StaticDisplayInfo& getDisplayInfo() {
    static ui::StaticDisplayInfo info = [] {
        ui::StaticDisplayInfo info;
#if HWUI_NULL_GPU
        info.density = 2.f;
#else
        const sp<IBinder> token = SurfaceComposerClient::getInternalDisplayToken();
        LOG_ALWAYS_FATAL_IF(!token, "%s: No internal display", __FUNCTION__);

        const status_t status = SurfaceComposerClient::getStaticDisplayInfo(token, &info);
        LOG_ALWAYS_FATAL_IF(status, "%s: Failed to get display info", __FUNCTION__);
#endif
        return info;
    }();

    return info;
}

const ui::DisplayMode& getActiveDisplayMode() {
    static ui::DisplayMode config = [] {
        ui::DisplayMode config;
#if HWUI_NULL_GPU
        config.resolution = ui::Size(1080, 1920);
        config.xDpi = config.yDpi = 320.f;
        config.refreshRate = 60.f;
#else
        const sp<IBinder> token = SurfaceComposerClient::getInternalDisplayToken();
        LOG_ALWAYS_FATAL_IF(!token, "%s: No internal display", __FUNCTION__);

        const status_t status = SurfaceComposerClient::getActiveDisplayMode(token, &config);
        LOG_ALWAYS_FATAL_IF(status, "%s: Failed to get active display config", __FUNCTION__);
#endif
        return config;
    }();

    return config;
}

TestContext::TestContext() {
    mLooper = new Looper(true);
    mSurfaceComposerClient = new SurfaceComposerClient();

    constexpr int EVENT_ID = 1;
    mLooper->addFd(mDisplayEventReceiver.getFd(), EVENT_ID, Looper::EVENT_INPUT, nullptr, nullptr);
}

TestContext::~TestContext() {}

sp<Surface> TestContext::surface() {
    if (!mSurface.get()) {
        createSurface();
    }
    return mSurface;
}

void TestContext::createSurface() {
    if (mRenderOffscreen) {
        createOffscreenSurface();
    } else {
        createWindowSurface();
    }
}

void TestContext::createWindowSurface() {
    const ui::Size& resolution = getActiveDisplayResolution();
    mSurfaceControl =
            mSurfaceComposerClient->createSurface(String8("HwuiTest"), resolution.getWidth(),
                                                  resolution.getHeight(), PIXEL_FORMAT_RGBX_8888);

    SurfaceComposerClient::Transaction t;
    t.setLayer(mSurfaceControl, 0x7FFFFFF).show(mSurfaceControl).apply();
    mSurface = mSurfaceControl->getSurface();
}

void TestContext::createOffscreenSurface() {
    sp<IGraphicBufferProducer> producer;
    sp<IGraphicBufferConsumer> consumer;
    BufferQueue::createBufferQueue(&producer, &consumer);
    producer->setMaxDequeuedBufferCount(3);
    producer->setAsyncMode(true);
    mConsumer = new BufferItemConsumer(consumer, GRALLOC_USAGE_HW_COMPOSER, 4);
    const ui::Size& resolution = getActiveDisplayResolution();
    mConsumer->setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
    mSurface = new Surface(producer);
}

void TestContext::waitForVsync() {
    // Hacky fix for not getting sysprop change callbacks
    // We just poll the sysprop in vsync since it's when the UI thread is
    // "idle" and shouldn't burn too much time
    atrace_update_tags();

    if (mConsumer.get()) {
        BufferItem buffer;
        if (mConsumer->acquireBuffer(&buffer, 0, false) == OK) {
            // We assume the producer is internally ordered enough such that
            // it is unneccessary to set a release fence
            mConsumer->releaseBuffer(buffer);
        }
        // We running free, go go go!
        return;
    }
#if !HWUI_NULL_GPU
    // Request vsync
    mDisplayEventReceiver.requestNextVsync();

    // Wait
    mLooper->pollOnce(-1);

    // Drain it
    DisplayEventReceiver::Event buf[100];
    while (mDisplayEventReceiver.getEvents(buf, 100) > 0) {
    }
#endif
}

}  // namespace test
}  // namespace uirenderer
}  // namespace android
