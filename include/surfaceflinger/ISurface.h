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

#ifndef ANDROID_SF_ISURFACE_H
#define ANDROID_SF_ISURFACE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <binder/IInterface.h>

#include <ui/PixelFormat.h>

#include <hardware/hardware.h>
#include <hardware/gralloc.h>

namespace android {

typedef int32_t    SurfaceID;

class GraphicBuffer;

class ISurface : public IInterface
{
protected:
    enum {
        RESERVED0 = IBinder::FIRST_CALL_TRANSACTION,
        RESERVED1,
        RESERVED2,
        REQUEST_BUFFER,
        SET_BUFFER_COUNT,
    };

public: 
    DECLARE_META_INTERFACE(Surface);

    /*
     * requests a new buffer for the given index. If w, h, or format are
     * null the buffer is created with the parameters assigned to the
     * surface it is bound to. Otherwise the buffer's parameters are
     * set to those specified.
     */
    virtual sp<GraphicBuffer> requestBuffer(int bufferIdx,
            uint32_t w, uint32_t h, uint32_t format, uint32_t usage) = 0;

    /*
     * sets the number of buffers dequeuable for this surface.
     */
    virtual status_t setBufferCount(int bufferCount) = 0;
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

#endif // ANDROID_SF_ISURFACE_H
