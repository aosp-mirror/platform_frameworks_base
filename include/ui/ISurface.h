/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_ISURFACE_H
#define ANDROID_ISURFACE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/IInterface.h>
#include <utils/RefBase.h>
#include <ui/PixelFormat.h>

namespace android {

typedef int32_t    SurfaceID;

class IMemoryHeap;
class Overlay;

class ISurface : public IInterface
{
public: 
    DECLARE_META_INTERFACE(Surface);

    virtual status_t registerBuffers(int w, int h, int hstride, int vstride,
            PixelFormat format, const sp<IMemoryHeap>& heap) = 0;

    virtual void postBuffer(ssize_t offset) = 0; // one-way

    virtual void unregisterBuffers() = 0;
    
    virtual sp<Overlay> createOverlay(
            uint32_t w, uint32_t h, int32_t format) = 0;
};

// ----------------------------------------------------------------------------

class BnSurface : public BnInterface<ISurface>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_ISURFACE_H
