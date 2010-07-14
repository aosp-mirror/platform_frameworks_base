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

#include <gui/SensorChannel.h>

namespace android {
// ----------------------------------------------------------------------------

SensorChannel::SensorChannel()
    : mSendFd(-1), mReceiveFd(-1)
{
    int fds[2];
    if (pipe(fds) == 0) {
        mReceiveFd = fds[0];
        mSendFd = fds[1];
        fcntl(mReceiveFd, F_SETFL, O_NONBLOCK);
        fcntl(mSendFd, F_SETFL, O_NONBLOCK);
    }
}

SensorChannel::SensorChannel(const Parcel& data)
    : mSendFd(-1), mReceiveFd(-1)
{
    mReceiveFd = dup(data.readFileDescriptor());
    fcntl(mReceiveFd, F_SETFL, O_NONBLOCK);
}

SensorChannel::~SensorChannel()
{
    if (mSendFd >= 0)
        close(mSendFd);

    if (mReceiveFd >= 0)
        close(mReceiveFd);
}

int SensorChannel::getFd() const
{
    return mReceiveFd;
}

ssize_t SensorChannel::write(void const* vaddr, size_t size)
{
    ssize_t len = ::write(mSendFd, vaddr, size);
    if (len < 0)
        return -errno;
    return len;
}

ssize_t SensorChannel::read(void* vaddr, size_t size)
{
    ssize_t len = ::read(mReceiveFd, vaddr, size);
    if (len < 0)
        return -errno;
    return len;
}

status_t SensorChannel::writeToParcel(Parcel* reply) const
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
