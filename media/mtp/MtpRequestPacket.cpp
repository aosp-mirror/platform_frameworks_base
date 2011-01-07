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

#define LOG_TAG "MtpRequestPacket"

#include <stdio.h>
#include <sys/types.h>
#include <fcntl.h>

#include "MtpRequestPacket.h"

#include <usbhost/usbhost.h>

namespace android {

MtpRequestPacket::MtpRequestPacket()
    :   MtpPacket(512)
{
}

MtpRequestPacket::~MtpRequestPacket() {
}

#ifdef MTP_DEVICE
int MtpRequestPacket::read(int fd) {
    int ret = ::read(fd, mBuffer, mBufferSize);
    if (ret >= 0)
        mPacketSize = ret;
    else
        mPacketSize = 0;
    return ret;
}
#endif

#ifdef MTP_HOST
    // write our buffer to the given endpoint (host mode)
int MtpRequestPacket::write(struct usb_request *request)
{
    putUInt32(MTP_CONTAINER_LENGTH_OFFSET, mPacketSize);
    putUInt16(MTP_CONTAINER_TYPE_OFFSET, MTP_CONTAINER_TYPE_COMMAND);
    request->buffer = mBuffer;
    request->buffer_length = mPacketSize;
    return transfer(request);
}
#endif

}  // namespace android
