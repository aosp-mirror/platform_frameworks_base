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

#include <assert.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/resource.h>

#include <linux/unistd.h>

#include <utils/Log.h>

#include "DisplayHardware/DisplayHardwareBase.h"
#include "SurfaceFlinger.h"

// ----------------------------------------------------------------------------
namespace android {

static char const * const kSleepFileName = "/sys/power/wait_for_fb_sleep";
static char const * const kWakeFileName  = "/sys/power/wait_for_fb_wake";

// ----------------------------------------------------------------------------

DisplayHardwareBase::DisplayEventThreadBase::DisplayEventThreadBase(
        const sp<SurfaceFlinger>& flinger)
    : Thread(false), mFlinger(flinger) {
}

DisplayHardwareBase::DisplayEventThreadBase::~DisplayEventThreadBase() {
}

// ----------------------------------------------------------------------------

DisplayHardwareBase::DisplayEventThread::DisplayEventThread(
        const sp<SurfaceFlinger>& flinger)
    : DisplayEventThreadBase(flinger)
{
}

DisplayHardwareBase::DisplayEventThread::~DisplayEventThread()
{
}

bool DisplayHardwareBase::DisplayEventThread::threadLoop()
{
    int err = 0;
    char buf;
    int fd;

    fd = open(kSleepFileName, O_RDONLY, 0);
    do {
      err = read(fd, &buf, 1);
    } while (err < 0 && errno == EINTR);
    close(fd);
    LOGW_IF(err<0, "ANDROID_WAIT_FOR_FB_SLEEP failed (%s)", strerror(errno));
    if (err >= 0) {
        sp<SurfaceFlinger> flinger = mFlinger.promote();
        ALOGD("About to give-up screen, flinger = %p", flinger.get());
        if (flinger != 0) {
            mBarrier.close();
            flinger->screenReleased(0);
            mBarrier.wait();
        }
    }
    fd = open(kWakeFileName, O_RDONLY, 0);
    do {
      err = read(fd, &buf, 1);
    } while (err < 0 && errno == EINTR);
    close(fd);
    LOGW_IF(err<0, "ANDROID_WAIT_FOR_FB_WAKE failed (%s)", strerror(errno));
    if (err >= 0) {
        sp<SurfaceFlinger> flinger = mFlinger.promote();
        ALOGD("Screen about to return, flinger = %p", flinger.get());
        if (flinger != 0)
            flinger->screenAcquired(0);
    }
    return true;
}

status_t DisplayHardwareBase::DisplayEventThread::releaseScreen() const
{
    mBarrier.open();
    return NO_ERROR;
}

status_t DisplayHardwareBase::DisplayEventThread::readyToRun()
{
    return NO_ERROR;
}

status_t DisplayHardwareBase::DisplayEventThread::initCheck() const
{
    return ((access(kSleepFileName, R_OK) == 0 &&
            access(kWakeFileName, R_OK) == 0)) ? NO_ERROR : NO_INIT;
}

// ----------------------------------------------------------------------------

DisplayHardwareBase::DisplayHardwareBase(const sp<SurfaceFlinger>& flinger,
        uint32_t displayIndex) 
    : mScreenAcquired(true)
{
    mDisplayEventThread = new DisplayEventThread(flinger);
}

DisplayHardwareBase::~DisplayHardwareBase()
{
    // request exit
    mDisplayEventThread->requestExitAndWait();
}

bool DisplayHardwareBase::canDraw() const
{
    return mScreenAcquired;
}

void DisplayHardwareBase::releaseScreen() const
{
    status_t err = mDisplayEventThread->releaseScreen();
    if (err >= 0) {
        mScreenAcquired = false;
    }
}

void DisplayHardwareBase::acquireScreen() const
{
    status_t err = mDisplayEventThread->acquireScreen();
    if (err >= 0) {
        mScreenAcquired = true;
    }
}

bool DisplayHardwareBase::isScreenAcquired() const
{
    return mScreenAcquired;
}

}; // namespace android
