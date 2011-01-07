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

#ifndef _MTP_PACKET_H
#define _MTP_PACKET_H

#include "MtpTypes.h"

struct usb_request;

namespace android {

class MtpPacket {

protected:
    uint8_t*            mBuffer;
    // current size of the buffer
    int                 mBufferSize;
    // number of bytes to add when resizing the buffer
    int                 mAllocationIncrement;
    // size of the data in the packet
    int                 mPacketSize;

public:
                        MtpPacket(int bufferSize);
    virtual             ~MtpPacket();

    // sets packet size to the default container size and sets buffer to zero
    virtual void        reset();

    void                allocate(int length);
    void                dump();
    void                copyFrom(const MtpPacket& src);

    uint16_t            getContainerCode() const;
    void                setContainerCode(uint16_t code);

    uint16_t            getContainerType() const;

    MtpTransactionID    getTransactionID() const;
    void                setTransactionID(MtpTransactionID id);

    uint32_t            getParameter(int index) const;
    void                setParameter(int index, uint32_t value);

#ifdef MTP_HOST
    int                 transfer(struct usb_request* request);
#endif

protected:
    uint16_t            getUInt16(int offset) const;
    uint32_t            getUInt32(int offset) const;
    void                putUInt16(int offset, uint16_t value);
    void                putUInt32(int offset, uint32_t value);
};

}; // namespace android

#endif // _MTP_PACKET_H
