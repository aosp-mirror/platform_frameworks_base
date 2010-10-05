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

#include <utils/Log.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

#include <binder/IMemory.h>
#include <surfaceflinger/ISurfaceComposer.h>

#include <SkImageEncoder.h>
#include <SkBitmap.h>

using namespace android;

int main(int argc, char** argv)
{
    if (argc != 2) {
        printf("usage: %s path\n", argv[0]);
        exit(0);
    }

    const String16 name("SurfaceFlinger");
    sp<ISurfaceComposer> composer;
    getService(name, &composer);

    sp<IMemoryHeap> heap;
    uint32_t w, h;
    PixelFormat f;
    status_t err = composer->captureScreen(0, &heap, &w, &h, &f, 0, 0);
    if (err != NO_ERROR) {
        fprintf(stderr, "screen capture failed: %s\n", strerror(-err));
        exit(0);
    }

    printf("screen capture success: w=%u, h=%u, pixels=%p\n",
            w, h, heap->getBase());

    printf("saving file as PNG in %s ...\n", argv[1]);

    SkBitmap b;
    b.setConfig(SkBitmap::kARGB_8888_Config, w, h);
    b.setPixels(heap->getBase());
    SkImageEncoder::EncodeFile(argv[1], b,
            SkImageEncoder::kPNG_Type, SkImageEncoder::kDefaultQuality);

    return 0;
}
