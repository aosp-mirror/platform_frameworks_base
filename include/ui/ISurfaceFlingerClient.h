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

#ifndef ANDROID_ISURFACE_FLINGER_CLIENT_H
#define ANDROID_ISURFACE_FLINGER_CLIENT_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/IInterface.h>
#include <utils/RefBase.h>

#include <ui/ISurface.h>

#include <ui/PixelFormat.h>
  
namespace android {

// ----------------------------------------------------------------------------

class Rect;
class Point;
class IMemory;
class ISurface;

typedef int32_t    ClientID;
typedef int32_t    DisplayID;

// ----------------------------------------------------------------------------

class layer_state_t;

class ISurfaceFlingerClient : public IInterface
{
public: 
    DECLARE_META_INTERFACE(SurfaceFlingerClient);

    struct surface_data_t {
        int32_t             token;
        int32_t             identity;
        sp<IMemoryHeap>     heap[2];
        status_t readFromParcel(const Parcel& parcel);
        status_t writeToParcel(Parcel* parcel) const;
    };
    
    virtual void getControlBlocks(sp<IMemory>* ctl) const = 0;

    virtual sp<ISurface> createSurface( surface_data_t* data,
                                        int pid, 
                                        DisplayID display,
                                        uint32_t w,
                                        uint32_t h,
                                        PixelFormat format,
                                        uint32_t flags) = 0;
                                    
    virtual status_t    destroySurface(SurfaceID sid) = 0;

    virtual status_t    setState(int32_t count, const layer_state_t* states) = 0;
};

// ----------------------------------------------------------------------------

class BnSurfaceFlingerClient : public BnInterface<ISurfaceFlingerClient>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_ISURFACE_FLINGER_CLIENT_H
