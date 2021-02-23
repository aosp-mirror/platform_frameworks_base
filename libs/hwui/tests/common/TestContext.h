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

#ifndef TESTCONTEXT_H
#define TESTCONTEXT_H

#include <gui/BufferItemConsumer.h>
#include <gui/DisplayEventReceiver.h>
#include <gui/ISurfaceComposer.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include <ui/DisplayMode.h>
#include <ui/StaticDisplayInfo.h>
#include <utils/Looper.h>

#include <atomic>
#include <thread>

#define dp(x) ((x) * android::uirenderer::test::getDisplayInfo().density)

namespace android {
namespace uirenderer {
namespace test {

const ui::StaticDisplayInfo& getDisplayInfo();
const ui::DisplayMode& getActiveDisplayMode();

inline const ui::Size& getActiveDisplayResolution() {
    return getActiveDisplayMode().resolution;
}

class TestContext {
public:
    TestContext();
    ~TestContext();

    // Must be called before surface();
    void setRenderOffscreen(bool renderOffscreen) {
        LOG_ALWAYS_FATAL_IF(mSurface.get(), "Must be called before surface is created");
        mRenderOffscreen = renderOffscreen;
    }

    sp<Surface> surface();

    void waitForVsync();

private:
    void createSurface();
    void createWindowSurface();
    void createOffscreenSurface();

    sp<SurfaceComposerClient> mSurfaceComposerClient;
    sp<SurfaceControl> mSurfaceControl;
    sp<BufferItemConsumer> mConsumer;
    DisplayEventReceiver mDisplayEventReceiver;
    sp<Looper> mLooper;
    sp<Surface> mSurface;
    bool mRenderOffscreen;
};

}  // namespace test
}  // namespace uirenderer
}  // namespace android

#endif
