/* 
 ** Copyright 2009, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License"); 
 ** you may not use this file except in compliance with the License. 
 ** You may obtain a copy of the License at 
 **
 **     http://www.apache.org/licenses/LICENSE-2.0 
 **
 ** Unless required by applicable law or agreed to in writing, software 
 ** distributed under the License is distributed on an "AS IS" BASIS, 
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 ** See the License for the specific language governing permissions and 
 ** limitations under the License.
 */

#ifndef ANDROID_EGL_LOADER_H
#define ANDROID_EGL_LOADER_H

#include <ctype.h>
#include <string.h>
#include <errno.h>

#include <utils/Errors.h>
#include <utils/Singleton.h>
#include <utils/String8.h>

#include <EGL/egl.h>

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

struct egl_connection_t;

class Loader : public Singleton<Loader>
{
    friend class Singleton<Loader>;

    typedef __eglMustCastToProperFunctionPointerType (*getProcAddressType)(
            const char*);
   
    enum {
        EGL         = 0x01,
        GLESv1_CM   = 0x02,
        GLESv2      = 0x04
    };
    struct driver_t {
        driver_t(void* gles);
        ~driver_t();
        status_t set(void* hnd, int32_t api);
        void* dso[3];
    };
    
    String8 mDriverTag;
    getProcAddressType getProcAddress;
    
public:
    ~Loader();
    
    void* open(egl_connection_t* cnx);
    status_t close(void* driver);
    
private:
    Loader();
    void *load_driver(const char* kind, const char *tag, egl_connection_t* cnx, uint32_t mask);

    static __attribute__((noinline))
    void init_api(void* dso, 
            char const * const * api, 
            __eglMustCastToProperFunctionPointerType* curr, 
            getProcAddressType getProcAddress); 
};

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif /* ANDROID_EGL_LOADER_H */
