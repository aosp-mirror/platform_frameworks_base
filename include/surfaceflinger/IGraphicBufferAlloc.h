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

#ifndef ANDROID_SF_IGRAPHIC_BUFFER_ALLOC_H
#define ANDROID_SF_IGRAPHIC_BUFFER_ALLOC_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/RefBase.h>

#include <binder/IInterface.h>

namespace android {
// ----------------------------------------------------------------------------

class IGraphicBufferAlloc : public IInterface
{
public:
    DECLARE_META_INTERFACE(GraphicBufferAlloc);

    /* Create a new GraphicBuffer for the client to use.  The server will
     * maintain a reference to the newly created GraphicBuffer until
     * freeAllGraphicBuffers is called.
     */
    virtual sp<GraphicBuffer> createGraphicBuffer(uint32_t w, uint32_t h,
            PixelFormat format, uint32_t usage) = 0;

    /* Free all but one of the GraphicBuffer objects that the server is
     * currently referencing. If bufIndex is not a valid index of the buffers
     * the server is referencing, then all buffers are freed.
     */
    virtual void freeAllGraphicBuffersExcept(int bufIndex) = 0;
};

// ----------------------------------------------------------------------------

class BnGraphicBufferAlloc : public BnInterface<IGraphicBufferAlloc>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_SF_IGRAPHIC_BUFFER_ALLOC_H
