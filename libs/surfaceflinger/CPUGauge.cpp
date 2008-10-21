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

#define LOG_TAG "CPUGauge"

#include <stdint.h>
#include <limits.h>
#include <sys/types.h>
#include <math.h>

#include <utils/threads.h>
#include <utils/Errors.h>
#include <utils/Log.h>

#include <ui/PixelFormat.h>
#include <ui/Rect.h>
#include <ui/Region.h>
#include <ui/DisplayInfo.h>
#include <ui/ISurfaceComposer.h>
#include <ui/ISurfaceFlingerClient.h>

#include <pixelflinger/pixelflinger.h>

#include "CPUGauge.h"

namespace android {

CPUGauge::CPUGauge( const sp<ISurfaceComposer>& composer,
                    nsecs_t interval,
                    int clock,
                    int refclock)
    :   Thread(false), 
        mInterval(interval), mClock(clock), mRefClock(refclock),
        mReferenceTime(0),
        mReferenceWorkingTime(0), mCpuUsage(0),
        mRefIdleTime(0), mIdleTime(0)
{
    mFd = fopen("/proc/stat", "r");
    setvbuf(mFd, NULL, _IONBF, 0);

    mSession = SurfaceComposerClient::clientForConnection(
        composer->createConnection()->asBinder());
}

CPUGauge::~CPUGauge()
{
    fclose(mFd);
}

const sp<SurfaceComposerClient>& CPUGauge::session() const 
{
    return mSession;
}

void CPUGauge::onFirstRef()
{
    run("CPU Gauge");
}

status_t CPUGauge::readyToRun()
{
    LOGI("Starting CPU gauge...");
    return NO_ERROR;
}

bool CPUGauge::threadLoop()
{
    DisplayInfo dinfo;
    session()->getDisplayInfo(0, &dinfo);
    sp<Surface> s(session()->createSurface(getpid(), 0, dinfo.w, 4, PIXEL_FORMAT_OPAQUE));
    session()->openTransaction();
    s->setLayer(INT_MAX);
    session()->closeTransaction();
    
    static const GGLfixed colors[4][4] = {
            { 0x00000, 0x10000, 0x00000, 0x10000 },
            { 0x10000, 0x10000, 0x00000, 0x10000 },
            { 0x10000, 0x00000, 0x00000, 0x10000 },
            { 0x00000, 0x00000, 0x00000, 0x10000 },
        };

    GGLContext* gl;
    gglInit(&gl);
    gl->activeTexture(gl, 0);
    gl->disable(gl, GGL_TEXTURE_2D);
    gl->disable(gl, GGL_BLEND);

    const int w = dinfo.w;

    while(!exitPending())
    {
        mLock.lock();
            const float cpuUsage = this->cpuUsage();
            const float totalCpuUsage = 1.0f - idle();
        mLock.unlock();

        Surface::SurfaceInfo info;
        s->lock(&info);
            GGLSurface fb;
                fb.version = sizeof(GGLSurface);
                fb.width   = info.w;
                fb.height  = info.h;
                fb.stride  = info.w;
                fb.format  = info.format;
                fb.data = (GGLubyte*)info.bits;

            gl->colorBuffer(gl, &fb);
            gl->color4xv(gl, colors[3]);
            gl->recti(gl, 0, 0, w, 4);
            gl->color4xv(gl, colors[2]); // red
            gl->recti(gl, 0, 0, int(totalCpuUsage*w), 2);
            gl->color4xv(gl, colors[0]); // green
            gl->recti(gl, 0, 2, int(cpuUsage*w), 4);
        
        s->unlockAndPost(); 

        usleep(ns2us(mInterval));
    }

    gglUninit(gl);
    return false;
}

void CPUGauge::sample()
{
    if (mLock.tryLock() == NO_ERROR) {
        const nsecs_t now = systemTime(mRefClock);
        const nsecs_t referenceTime = now-mReferenceTime;
        if (referenceTime >= mInterval) {
            const float reftime = 1.0f / referenceTime;
            const nsecs_t nowWorkingTime = systemTime(mClock);
            
            char buf[256];
            fgets(buf, 256, mFd);
            rewind(mFd);
            char *str = buf+5;
            char const * const usermode = strsep(&str, " ");  (void)usermode;
            char const * const usernice = strsep(&str, " ");  (void)usernice;
            char const * const systemmode = strsep(&str, " ");(void)systemmode;
            char const * const idle = strsep(&str, " ");
            const nsecs_t nowIdleTime = atoi(idle) * 10000000LL;
            mIdleTime = float(nowIdleTime - mRefIdleTime) * reftime;
            mRefIdleTime = nowIdleTime;
            
            const nsecs_t workingTime = nowWorkingTime - mReferenceWorkingTime;
            const float newCpuUsage = float(workingTime) * reftime;
            if (mCpuUsage != newCpuUsage) {        
                mCpuUsage = newCpuUsage;
                mReferenceWorkingTime = nowWorkingTime;
                mReferenceTime = now;
            }
        }
        mLock.unlock();
    }
}


}; // namespace android
