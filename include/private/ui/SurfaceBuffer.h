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

#ifndef ANDROID_UI_PRIVATE_SURFACE_BUFFER_H
#define ANDROID_UI_PRIVATE_SURFACE_BUFFER_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/RefBase.h>

#include <ui/egl/android_natives.h>

namespace android {

// ---------------------------------------------------------------------------

class BufferMapper;
class Rect;
class Surface;
class SurfaceBuffer;

// ---------------------------------------------------------------------------

class SurfaceBuffer 
    : public EGLNativeBase<
        android_native_buffer_t, 
        SurfaceBuffer, 
        LightRefBase<SurfaceBuffer> >
{
public:
    status_t lock(uint32_t usage, void** vaddr);
    status_t lock(uint32_t usage, const Rect& rect, void** vaddr);
    status_t unlock();

protected:
            SurfaceBuffer();
            SurfaceBuffer(const Parcel& reply);
    virtual ~SurfaceBuffer();
    bool mOwner;

    inline const BufferMapper& getBufferMapper() const { return mBufferMapper; }
    inline BufferMapper& getBufferMapper() { return mBufferMapper; }
    
private:
    friend class Surface;
    friend class BpSurface;
    friend class BnSurface;
    friend class LightRefBase<SurfaceBuffer>;    

    SurfaceBuffer& operator = (const SurfaceBuffer& rhs);
    const SurfaceBuffer& operator = (const SurfaceBuffer& rhs) const;

    static status_t writeToParcel(Parcel* reply, 
            android_native_buffer_t const* buffer);
    
    BufferMapper& mBufferMapper;
};

}; // namespace android

#endif // ANDROID_UI_PRIVATE_SURFACE_BUFFER_H

