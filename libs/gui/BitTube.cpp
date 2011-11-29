/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <unistd.h>
#include <fcntl.h>

#include <utils/Errors.h>

#include <binder/Parcel.h>

#include <gui/BitTube.h>

namespace android {
// ----------------------------------------------------------------------------

BitTube::BitTube()
    : mSendFd(-1), mReceiveFd(-1)
{
    int fds[2];
    if (pipe(fds) == 0) {
        mReceiveFd = fds[0];
        mSendFd = fds[1];
        fcntl(mReceiveFd, F_SETFL, O_NONBLOCK);
        fcntl(mSendFd, F_SETFL, O_NONBLOCK);
    } else {
        mReceiveFd = -errno;
        LOGE("BitTube: pipe creation failed (%s)", strerror(-mReceiveFd));
    }
}

BitTube::BitTube(const Parcel& data)
    : mSendFd(-1), mReceiveFd(-1)
{
    mReceiveFd = dup(data.readFileDescriptor());
    if (mReceiveFd >= 0) {
        fcntl(mReceiveFd, F_SETFL, O_NONBLOCK);
    } else {
        mReceiveFd = -errno;
        LOGE("BitTube(Parcel): can't dup filedescriptor (%s)",
                strerror(-mReceiveFd));
    }
}

BitTube::~BitTube()
{
    if (mSendFd >= 0)
        close(mSendFd);

    if (mReceiveFd >= 0)
        close(mReceiveFd);
}

status_t BitTube::initCheck() const
{
    if (mReceiveFd < 0) {
        return status_t(mReceiveFd);
    }
    return NO_ERROR;
}

int BitTube::getFd() const
{
    return mReceiveFd;
}

ssize_t BitTube::write(void const* vaddr, size_t size)
{
    ssize_t err, len;
    do {
        len = ::write(mSendFd, vaddr, size);
        err = len < 0 ? errno : 0;
    } while (err == EINTR);
    return err == 0 ? len : -err;

}

ssize_t BitTube::read(void* vaddr, size_t size)
{
    ssize_t err, len;
    do {
        len = ::read(mReceiveFd, vaddr, size);
        err = len < 0 ? errno : 0;
    } while (err == EINTR);
    if (err == EAGAIN || err == EWOULDBLOCK) {
        // EAGAIN means that we have non-blocking I/O but there was
        // no data to be read. Nothing the client should care about.
        return 0;
    }
    return err == 0 ? len : -err;
}

status_t BitTube::writeToParcel(Parcel* reply) const
{
    if (mReceiveFd < 0)
        return -EINVAL;

    status_t result = reply->writeDupFileDescriptor(mReceiveFd);
    close(mReceiveFd);
    mReceiveFd = -1;
    return result;
}

// ----------------------------------------------------------------------------
}; // namespace android
