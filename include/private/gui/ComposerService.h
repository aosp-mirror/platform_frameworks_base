/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_PRIVATE_GUI_COMPOSER_SERVICE_H
#define ANDROID_PRIVATE_GUI_COMPOSER_SERVICE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Singleton.h>
#include <utils/StrongPointer.h>


namespace android {

// ---------------------------------------------------------------------------

class IMemoryHeap;
class ISurfaceComposer;
class surface_flinger_cblk_t;

// ---------------------------------------------------------------------------

class ComposerService : public Singleton<ComposerService>
{
    // these are constants
    sp<ISurfaceComposer> mComposerService;
    sp<IMemoryHeap> mServerCblkMemory;
    surface_flinger_cblk_t volatile* mServerCblk;
    ComposerService();
    friend class Singleton<ComposerService>;
public:
    static sp<ISurfaceComposer> getComposerService();
    static surface_flinger_cblk_t const volatile * getControlBlock();
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_PRIVATE_GUI_COMPOSER_SERVICE_H
