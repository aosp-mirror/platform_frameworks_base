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

#define LOG_TAG "SurfaceFlingerSynchro"

#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/stat.h>

#include <utils/IPCThreadState.h>
#include <utils/Log.h>

#include <private/ui/SurfaceFlingerSynchro.h>

namespace android {

// ---------------------------------------------------------------------------

SurfaceFlingerSynchro::Barrier::Barrier()
    : state(CLOSED) { 
}

SurfaceFlingerSynchro::Barrier::~Barrier() { 
}

void SurfaceFlingerSynchro::Barrier::open() {
    asm volatile ("":::"memory");
    Mutex::Autolock _l(lock);
    state = OPENED;
    cv.broadcast();
}

void SurfaceFlingerSynchro::Barrier::close() {
    Mutex::Autolock _l(lock);
    state = CLOSED;
}

void SurfaceFlingerSynchro::Barrier::waitAndClose() 
{
    Mutex::Autolock _l(lock);
    while (state == CLOSED) {
        // we're about to wait, flush the binder command buffer
        IPCThreadState::self()->flushCommands();
        cv.wait(lock);
    }
    state = CLOSED;
}

status_t SurfaceFlingerSynchro::Barrier::waitAndClose(nsecs_t timeout) 
{
    Mutex::Autolock _l(lock);
    while (state == CLOSED) {
        // we're about to wait, flush the binder command buffer
        IPCThreadState::self()->flushCommands();
        int err = cv.waitRelative(lock, timeout);
        if (err != 0)
            return err;
    }
    state = CLOSED;
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

SurfaceFlingerSynchro::SurfaceFlingerSynchro(const sp<ISurfaceComposer>& flinger)
    : mSurfaceComposer(flinger)
{
}

SurfaceFlingerSynchro::SurfaceFlingerSynchro()
{
}

SurfaceFlingerSynchro::~SurfaceFlingerSynchro()
{
}

status_t SurfaceFlingerSynchro::signal()
{
    mSurfaceComposer->signal();
    return NO_ERROR;
}

status_t SurfaceFlingerSynchro::wait()
{
    mBarrier.waitAndClose();
    return NO_ERROR;
}

status_t SurfaceFlingerSynchro::wait(nsecs_t timeout)
{
    if (timeout == 0)
        return SurfaceFlingerSynchro::wait();
    return mBarrier.waitAndClose(timeout);
}

void SurfaceFlingerSynchro::open()
{
    mBarrier.open();
}

// ---------------------------------------------------------------------------

}; // namespace android

