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
// the sim build doesn't have gettid

#ifndef HAVE_GETTID
# define gettid getpid
#endif

// ----------------------------------------------------------------------------
namespace android {

static char const * kSleepFileName = "/sys/power/wait_for_fb_sleep";
static char const * kWakeFileName = "/sys/power/wait_for_fb_wake";
static char const * const kOldSleepFileName = "/sys/android_power/wait_for_fb_sleep";
static char const * const kOldWakeFileName = "/sys/android_power/wait_for_fb_wake";

// This dir exists if the framebuffer console is present, either built into
// the kernel or loaded as a module.
static char const * const kFbconSysDir = "/sys/class/graphics/fbcon";

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
        LOGD("About to give-up screen, flinger = %p", flinger.get());
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
        LOGD("Screen about to return, flinger = %p", flinger.get());
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
    if (access(kSleepFileName, R_OK) || access(kWakeFileName, R_OK)) {
        if (access(kOldSleepFileName, R_OK) || access(kOldWakeFileName, R_OK)) {
            LOGE("Couldn't open %s or %s", kSleepFileName, kWakeFileName);
            return NO_INIT;
        }
        kSleepFileName = kOldSleepFileName;
        kWakeFileName = kOldWakeFileName;
    }
    return NO_ERROR;
}

status_t DisplayHardwareBase::DisplayEventThread::initCheck() const
{
    return (((access(kSleepFileName, R_OK) == 0 &&
            access(kWakeFileName, R_OK) == 0) ||
            (access(kOldSleepFileName, R_OK) == 0 &&
            access(kOldWakeFileName, R_OK) == 0)) &&
            access(kFbconSysDir, F_OK) != 0) ? NO_ERROR : NO_INIT;
}

// ----------------------------------------------------------------------------

pid_t DisplayHardwareBase::ConsoleManagerThread::sSignalCatcherPid = 0;

DisplayHardwareBase::ConsoleManagerThread::ConsoleManagerThread(
        const sp<SurfaceFlinger>& flinger)
    : DisplayEventThreadBase(flinger), consoleFd(-1)
{   
    sSignalCatcherPid = 0;

    // create a new console
    char const * const ttydev = "/dev/tty0";
    int fd = open(ttydev, O_RDWR | O_SYNC);
    if (fd<0) {
        LOGE("Can't open %s", ttydev);
        this->consoleFd = -errno;
        return;
    }

    // to make sure that we are in text mode
    int res = ioctl(fd, KDSETMODE, (void*) KD_TEXT);
    if (res<0) {
        LOGE("ioctl(%d, KDSETMODE, ...) failed, res %d (%s)",
                fd, res, strerror(errno));
    }
    
    // get the current console
    struct vt_stat vs;
    res = ioctl(fd, VT_GETSTATE, &vs);
    if (res<0) {
        LOGE("ioctl(%d, VT_GETSTATE, ...) failed, res %d (%s)",
                fd, res, strerror(errno));
        this->consoleFd = -errno;
        return;
    }

    // switch to console 7 (which is what X normaly uses)
    int vtnum = 7;
    do {
        res = ioctl(fd, VT_ACTIVATE, (void*)vtnum);
    } while(res < 0 && errno == EINTR);
    if (res<0) {
        LOGE("ioctl(%d, VT_ACTIVATE, ...) failed, %d (%s) for %d",
                fd, errno, strerror(errno), vtnum);
        this->consoleFd = -errno;
        return;
    }

    do {
        res = ioctl(fd, VT_WAITACTIVE, (void*)vtnum);
    } while(res < 0 && errno == EINTR);
    if (res<0) {
        LOGE("ioctl(%d, VT_WAITACTIVE, ...) failed, %d %d %s for %d",
                fd, res, errno, strerror(errno), vtnum);
        this->consoleFd = -errno;
        return;
    }

    // open the new console
    close(fd);
    fd = open(ttydev, O_RDWR | O_SYNC);
    if (fd<0) {
        LOGE("Can't open new console %s", ttydev);
        this->consoleFd = -errno;
        return;
    }

    /* disable console line buffer, echo, ... */
    struct termios ttyarg;
    ioctl(fd, TCGETS , &ttyarg);
    ttyarg.c_iflag = 0;
    ttyarg.c_lflag = 0;
    ioctl(fd, TCSETS , &ttyarg);

    // set up signals so we're notified when the console changes
    // we can't use SIGUSR1 because it's used by the java-vm
    vm.mode = VT_PROCESS;
    vm.waitv = 0;
    vm.relsig = SIGUSR2;
    vm.acqsig = SIGUNUSED;
    vm.frsig = 0;

    struct sigaction act;
    sigemptyset(&act.sa_mask);
    act.sa_handler = sigHandler;
    act.sa_flags = 0;
    sigaction(vm.relsig, &act, NULL);

    sigemptyset(&act.sa_mask);
    act.sa_handler = sigHandler;
    act.sa_flags = 0;
    sigaction(vm.acqsig, &act, NULL);

    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, vm.relsig);
    sigaddset(&mask, vm.acqsig);
    sigprocmask(SIG_BLOCK, &mask, NULL);

    // switch to graphic mode
    res = ioctl(fd, KDSETMODE, (void*)KD_GRAPHICS);
    LOGW_IF(res<0,
            "ioctl(%d, KDSETMODE, KD_GRAPHICS) failed, res %d", fd, res);

    this->prev_vt_num = vs.v_active;
    this->vt_num = vtnum;
    this->consoleFd = fd;
}

DisplayHardwareBase::ConsoleManagerThread::~ConsoleManagerThread()
{   
    if (this->consoleFd >= 0) {
        int fd = this->consoleFd;
        int prev_vt_num = this->prev_vt_num;
        int res;
        ioctl(fd, KDSETMODE, (void*)KD_TEXT);
        do {
            res = ioctl(fd, VT_ACTIVATE, (void*)prev_vt_num);
        } while(res < 0 && errno == EINTR);
        do {
            res = ioctl(fd, VT_WAITACTIVE, (void*)prev_vt_num);
        } while(res < 0 && errno == EINTR);
        close(fd);    
        char const * const ttydev = "/dev/tty0";
        fd = open(ttydev, O_RDWR | O_SYNC);
        ioctl(fd, VT_DISALLOCATE, 0);
        close(fd);
    }
}

status_t DisplayHardwareBase::ConsoleManagerThread::readyToRun()
{
    if (this->consoleFd >= 0) {
        sSignalCatcherPid = gettid();
        
        sigset_t mask;
        sigemptyset(&mask);
        sigaddset(&mask, vm.relsig);
        sigaddset(&mask, vm.acqsig);
        sigprocmask(SIG_BLOCK, &mask, NULL);

        int res = ioctl(this->consoleFd, VT_SETMODE, &vm);
        if (res<0) {
            LOGE("ioctl(%d, VT_SETMODE, ...) failed, %d (%s)",
                    this->consoleFd, errno, strerror(errno));
        }
        return NO_ERROR;
    }
    return this->consoleFd;
}

void DisplayHardwareBase::ConsoleManagerThread::requestExit()
{
    Thread::requestExit();
    if (sSignalCatcherPid != 0) {
        // wake the thread up
        kill(sSignalCatcherPid, SIGINT);
        // wait for it...
    }
}

void DisplayHardwareBase::ConsoleManagerThread::sigHandler(int sig)
{
    // resend the signal to our signal catcher thread
    LOGW("received signal %d in thread %d, resending to %d",
            sig, gettid(), sSignalCatcherPid);

    // we absolutely need the delays below because without them
    // our main thread never gets a chance to handle the signal.
    usleep(10000);
    kill(sSignalCatcherPid, sig);
    usleep(10000);
}

status_t DisplayHardwareBase::ConsoleManagerThread::releaseScreen() const
{
    int fd = this->consoleFd;
    int err = ioctl(fd, VT_RELDISP, (void*)1);
    LOGE_IF(err<0, "ioctl(%d, VT_RELDISP, 1) failed %d (%s)",
        fd, errno, strerror(errno));
    return (err<0) ? (-errno) : status_t(NO_ERROR);
}

bool DisplayHardwareBase::ConsoleManagerThread::threadLoop()
{
    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, vm.relsig);
    sigaddset(&mask, vm.acqsig);

    int sig = 0;
    sigwait(&mask, &sig);

    if (sig == vm.relsig) {
        sp<SurfaceFlinger> flinger = mFlinger.promote();
        //LOGD("About to give-up screen, flinger = %p", flinger.get());
        if (flinger != 0)
            flinger->screenReleased(0);
    } else if (sig == vm.acqsig) {
        sp<SurfaceFlinger> flinger = mFlinger.promote();
        //LOGD("Screen about to return, flinger = %p", flinger.get());
        if (flinger != 0) 
            flinger->screenAcquired(0);
    }
    
    return true;
}

status_t DisplayHardwareBase::ConsoleManagerThread::initCheck() const
{
    return consoleFd >= 0 ? NO_ERROR : NO_INIT;
}

// ----------------------------------------------------------------------------

DisplayHardwareBase::DisplayHardwareBase(const sp<SurfaceFlinger>& flinger,
        uint32_t displayIndex) 
    : mCanDraw(true)
{
    mDisplayEventThread = new DisplayEventThread(flinger);
    if (mDisplayEventThread->initCheck() != NO_ERROR) {
        // fall-back on the console
        mDisplayEventThread = new ConsoleManagerThread(flinger);
    }
}

DisplayHardwareBase::~DisplayHardwareBase()
{
    // request exit
    mDisplayEventThread->requestExitAndWait();
}


bool DisplayHardwareBase::canDraw() const
{
    return mCanDraw;
}

void DisplayHardwareBase::releaseScreen() const
{
    status_t err = mDisplayEventThread->releaseScreen();
    if (err >= 0) {
        //LOGD("screen given-up");
        mCanDraw = false;
    }
}

void DisplayHardwareBase::acquireScreen() const
{
    status_t err = mDisplayEventThread->acquireScreen();
    if (err >= 0) {
        //LOGD("screen returned");
        mCanDraw = true;
    }
}

}; // namespace android
