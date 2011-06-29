/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <cutils/memory.h>

#include <utils/Log.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

#include <surfaceflinger/Surface.h>
#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

using namespace android;

int main(int argc, char** argv)
{
    // set up the thread-pool
    sp<ProcessState> proc(ProcessState::self());
    ProcessState::self()->startThreadPool();

    // create a client to surfaceflinger
    sp<SurfaceComposerClient> client = new SurfaceComposerClient();
    
    sp<SurfaceControl> surfaceControl = client->createSurface(
            getpid(), 0, 160, 240, PIXEL_FORMAT_RGB_565);
    SurfaceComposerClient::openGlobalTransaction();
    surfaceControl->setLayer(100000);
    SurfaceComposerClient::closeGlobalTransaction();

    // pretend it went cross-process
    Parcel parcel;
    SurfaceControl::writeSurfaceToParcel(surfaceControl, &parcel);
    parcel.setDataPosition(0);
    sp<Surface> surface = Surface::readFromParcel(parcel);
    ANativeWindow* window = surface.get();

    printf("window=%p\n", window);

    int err = native_window_set_buffer_count(window, 8);
    ANativeWindowBuffer* buffer;

    for (int i=0 ; i<8 ; i++) {
        window->dequeueBuffer(window, &buffer);
        printf("buffer %d: %p\n", i, buffer);
    }

    printf("test complete. CTRL+C to finish.\n");

    IPCThreadState::self()->joinThreadPool();
    return 0;
}
