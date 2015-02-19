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

#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>

using namespace android;

DisplayInfo gDisplay;
sp<SurfaceComposerClient> gSession;

void createTestEnvironment() {
    gSession = new SurfaceComposerClient();
    sp<IBinder> dtoken(SurfaceComposerClient::getBuiltInDisplay(
                ISurfaceComposer::eDisplayIdMain));
    status_t status = SurfaceComposerClient::getDisplayInfo(dtoken, &gDisplay);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get display info\n");
}

sp<SurfaceControl> createWindow(int width, int height) {
    sp<SurfaceControl> control = gSession->createSurface(String8("HwuiTest"),
            width, height, PIXEL_FORMAT_RGBX_8888);

    SurfaceComposerClient::openGlobalTransaction();
    control->setLayer(0x7FFFFFF);
    control->show();
    SurfaceComposerClient::closeGlobalTransaction();

    return control;
}
