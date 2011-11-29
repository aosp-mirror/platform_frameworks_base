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

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/fb.h>

#include "DisplayHardware/VSyncBarrier.h"

#ifndef FBIO_WAITFORVSYNC
#define FBIO_WAITFORVSYNC   _IOW('F', 0x20, __u32)
#endif

namespace android {
// ---------------------------------------------------------------------------

VSyncBarrier::VSyncBarrier() : mFd(-EINVAL) {
#if HAS_WAITFORVSYNC
    mFd = open("/dev/graphics/fb0", O_RDWR);
    if (mFd < 0) {
        mFd = -errno;
    }
    // try to see if FBIO_WAITFORVSYNC is supported
    uint32_t crt = 0;
    int err = ioctl(mFd, FBIO_WAITFORVSYNC, &crt);
    if (err < 0) {
        close(mFd);
        mFd = -EINVAL;
    }
#endif
}

VSyncBarrier::~VSyncBarrier() {
    if (mFd >= 0) {
        close(mFd);
    }
}

status_t VSyncBarrier::initCheck() const {
    return mFd < 0 ? mFd : status_t(NO_ERROR);
}

// this must be thread-safe
status_t VSyncBarrier::wait(nsecs_t* timestamp) const {
    if (mFd < 0) {
        return mFd;
    }

    int err;
    uint32_t crt = 0;
    do {
        err = ioctl(mFd, FBIO_WAITFORVSYNC, &crt);
    } while (err<0 && errno==EINTR);
    if (err < 0) {
        return -errno;
    }
    // ideally this would come from the driver
    timestamp[0] = systemTime();
    return NO_ERROR;
}

// ---------------------------------------------------------------------------
}; // namespace android
