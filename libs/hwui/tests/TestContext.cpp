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

#include "TestContext.h"

namespace android {
namespace uirenderer {
namespace test {

static const int IDENT_DISPLAYEVENT = 1;

static DisplayInfo getBuiltInDisplay() {
    DisplayInfo display;
    sp<IBinder> dtoken(SurfaceComposerClient::getBuiltInDisplay(
            ISurfaceComposer::eDisplayIdMain));
    status_t status = SurfaceComposerClient::getDisplayInfo(dtoken, &display);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get display info\n");
    return display;
}

android::DisplayInfo gDisplay = getBuiltInDisplay();

TestContext::TestContext() {
    mLooper = new Looper(true);
    mSurfaceComposerClient = new SurfaceComposerClient();
    mLooper->addFd(mDisplayEventReceiver.getFd(), IDENT_DISPLAYEVENT,
            Looper::EVENT_INPUT, nullptr, nullptr);
}

TestContext::~TestContext() {}

sp<Surface> TestContext::surface() {
    if (!mSurfaceControl.get()) {
        mSurfaceControl = mSurfaceComposerClient->createSurface(String8("HwuiTest"),
                gDisplay.w, gDisplay.h, PIXEL_FORMAT_RGBX_8888);

        SurfaceComposerClient::openGlobalTransaction();
        mSurfaceControl->setLayer(0x7FFFFFF);
        mSurfaceControl->show();
        SurfaceComposerClient::closeGlobalTransaction();
    }

    return mSurfaceControl->getSurface();
}

void TestContext::waitForVsync() {
#if HWUI_NULL_GPU
    return;
#endif

    // Request vsync
    mDisplayEventReceiver.requestNextVsync();

    // Wait
    mLooper->pollOnce(-1);

    // Drain it
    DisplayEventReceiver::Event buf[100];
    while (mDisplayEventReceiver.getEvents(buf, 100) > 0) { }
}

} // namespace test
} // namespace uirenderer
} // namespace android
