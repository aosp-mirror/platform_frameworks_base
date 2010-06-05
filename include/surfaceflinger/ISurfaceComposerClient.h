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

#ifndef ANDROID_SF_ISURFACE_COMPOSER_CLIENT_H
#define ANDROID_SF_ISURFACE_COMPOSER_CLIENT_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <binder/IInterface.h>

#include <ui/PixelFormat.h>

#include <surfaceflinger/ISurface.h>

namespace android {

// ----------------------------------------------------------------------------

class IMemoryHeap;

typedef int32_t    ClientID;
typedef int32_t    DisplayID;

// ----------------------------------------------------------------------------

class layer_state_t;

class ISurfaceComposerClient : public IInterface
{
public:
    DECLARE_META_INTERFACE(SurfaceComposerClient);

    struct surface_data_t {
        int32_t             token;
        int32_t             identity;
        uint32_t            width;
        uint32_t            height;
        uint32_t            format;
        status_t readFromParcel(const Parcel& parcel);
        status_t writeToParcel(Parcel* parcel) const;
    };

    virtual sp<IMemoryHeap> getControlBlock() const = 0;
    virtual ssize_t getTokenForSurface(const sp<ISurface>& sur) const = 0;

    /*
     * Requires ACCESS_SURFACE_FLINGER permission
     */
    virtual sp<ISurface> createSurface( surface_data_t* data,
                                        int pid,
                                        const String8& name,
                                        DisplayID display,
                                        uint32_t w,
                                        uint32_t h,
                                        PixelFormat format,
                                        uint32_t flags) = 0;

    /*
     * Requires ACCESS_SURFACE_FLINGER permission
     */
    virtual status_t    destroySurface(SurfaceID sid) = 0;

    /*
     * Requires ACCESS_SURFACE_FLINGER permission
     */
    virtual status_t    setState(int32_t count, const layer_state_t* states) = 0;
};

// ----------------------------------------------------------------------------

class BnSurfaceComposerClient : public BnInterface<ISurfaceComposerClient>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_SF_ISURFACE_COMPOSER_CLIENT_H
