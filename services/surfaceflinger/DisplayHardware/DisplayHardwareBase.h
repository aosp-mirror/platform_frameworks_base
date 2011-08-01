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

#ifndef ANDROID_DISPLAY_HARDWARE_BASE_H
#define ANDROID_DISPLAY_HARDWARE_BASE_H

#include <stdint.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <linux/kd.h>
#include <linux/vt.h>
#include "Barrier.h"

namespace android {

class SurfaceFlinger; 

class DisplayHardwareBase
{
public:
                DisplayHardwareBase(
                        const sp<SurfaceFlinger>& flinger,
                        uint32_t displayIndex);

                ~DisplayHardwareBase();

    // console management
    void releaseScreen() const;
    void acquireScreen() const;
    bool isScreenAcquired() const;

    bool canDraw() const;
    void setCanDraw(bool canDraw);


private:
    class DisplayEventThreadBase : public Thread {
    protected:
        wp<SurfaceFlinger> mFlinger;
    public:
        DisplayEventThreadBase(const sp<SurfaceFlinger>& flinger);
        virtual ~DisplayEventThreadBase();
        virtual void onFirstRef() {
            run("DisplayEventThread", PRIORITY_URGENT_DISPLAY);
        }
        virtual status_t acquireScreen() const { return NO_ERROR; };
        virtual status_t releaseScreen() const { return NO_ERROR; };
        virtual status_t initCheck() const = 0;
    };

    class DisplayEventThread : public DisplayEventThreadBase 
    {
        mutable Barrier mBarrier;
    public:
                DisplayEventThread(const sp<SurfaceFlinger>& flinger);
        virtual ~DisplayEventThread();
        virtual bool threadLoop();
        virtual status_t readyToRun();
        virtual status_t releaseScreen() const;
        virtual status_t initCheck() const;
    };

    sp<DisplayEventThreadBase>  mDisplayEventThread;
    mutable int                 mCanDraw;
    mutable int                 mScreenAcquired;
};

}; // namespace android

#endif // ANDROID_DISPLAY_HARDWARE_BASE_H
