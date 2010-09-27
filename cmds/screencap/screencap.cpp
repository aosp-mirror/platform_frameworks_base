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

#include <unistd.h>
#include <fcntl.h>

#include <utils/Log.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

#include <binder/IMemory.h>
#include <surfaceflinger/ISurfaceComposer.h>

using namespace android;

int main(int argc, char** argv)
{
    const String16 name("SurfaceFlinger");
    sp<ISurfaceComposer> composer;
    if (getService(name, &composer) != NO_ERROR)
        return 0;

    sp<IMemoryHeap> heap;
    uint32_t w, h;
    PixelFormat f;
    status_t err = composer->captureScreen(0, &heap, &w, &h, &f);
    if (err != NO_ERROR)
        return 0;

    uint8_t* base = (uint8_t*)heap->getBase();
    int fd = dup(STDOUT_FILENO);
    write(fd, &w, 4);
    write(fd, &h, 4);
    write(fd, &f, 4);
    write(fd, base, w*h*4);
    close(fd);
    return 0;
}
