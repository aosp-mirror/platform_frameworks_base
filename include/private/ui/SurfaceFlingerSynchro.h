/*
 * Copyright (C) 2008 The Android Open Source Project
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


#ifndef ANDROID_SURFACE_FLINGER_SYNCHRO_H
#define ANDROID_SURFACE_FLINGER_SYNCHRO_H

#include <stdint.h>
#include <sys/types.h>
#include <utils/Errors.h>
#include <utils/threads.h>
#include <ui/ISurfaceComposer.h>

namespace android {

class SurfaceFlinger;

class SurfaceFlingerSynchro
{
public:

                // client constructor
                SurfaceFlingerSynchro(const sp<ISurfaceComposer>& flinger);
                ~SurfaceFlingerSynchro();
    
                // signal surfaceflinger for some work
    status_t    signal();
    
private:
    class Barrier {
    public:
        Barrier();
        ~Barrier();
        void open();
        void close();
        void waitAndClose();
        status_t waitAndClose(nsecs_t timeout);
    private:
        enum { OPENED, CLOSED };
        mutable     Mutex       lock;
        mutable     Condition   cv;
        volatile    int         state;
    };

    friend class SurfaceFlinger;

                // server constructor
                SurfaceFlingerSynchro();
                
    void        open();
    
                // wait until there is some work to do
    status_t    wait();
    status_t    wait(nsecs_t timeout);
    
    sp<ISurfaceComposer> mSurfaceComposer;
    Barrier              mBarrier;
};

}; // namespace android

#endif // ANDROID_SURFACE_FLINGER_SYNCHRO_H

