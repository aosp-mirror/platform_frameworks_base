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

static const int IDENT_DISPLAYEVENT = 1;

static android::DisplayInfo DUMMY_DISPLAY{
        1080,   // w
        1920,   // h
        320.0,  // xdpi
        320.0,  // ydpi
        60.0,   // fps
        2.0,    // density
        0,      // orientation
        false,  // secure?
        0,      // appVsyncOffset
        0,      // presentationDeadline
};

DisplayInfo getBuiltInDisplay() {
#if !HWUI_NULL_GPU
    DisplayInfo display;
    sp<IBinder> dtoken(SurfaceComposerClient::getBuiltInDisplay(ISurfaceComposer::eDisplayIdMain));
    status_t status = SurfaceComposerClient::getDisplayInfo(dtoken, &display);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get display info\n");
    return display;
#else
    return DUMMY_DISPLAY;
#endif
}

// Initialize to a dummy default
android::DisplayInfo gDisplay = DUMMY_DISPLAY;

TestContext::TestContext() {
    mLooper = new Looper(true);
    mSurfaceComposerClient = new SurfaceComposerClient();
    mLooper->addFd(mDisplayEventReceiver.getFd(), IDENT_DISPLAYEVENT, Looper::EVENT_INPUT, nullptr,
                   nullptr);
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
    mSurfaceControl = mSurfaceComposerClient->createSurface(String8("HwuiTest"), gDisplay.w,
                                                            gDisplay.h, PIXEL_FORMAT_RGBX_8888);

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
    mConsumer->setDefaultBufferSize(gDisplay.w, gDisplay.h);
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
