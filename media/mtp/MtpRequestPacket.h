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

#ifndef _MTP_REQUEST_PACKET_H
#define _MTP_REQUEST_PACKET_H

#include "MtpPacket.h"
#include "mtp.h"

struct usb_request;

namespace android {

class MtpRequestPacket : public MtpPacket {

public:
                        MtpRequestPacket();
    virtual             ~MtpRequestPacket();

#ifdef MTP_DEVICE
    // fill our buffer with data from the given file descriptor
    int                 read(int fd);
#endif

#ifdef MTP_HOST
    // write our buffer to the given endpoint
    int                 write(struct usb_request *request);
#endif

    inline MtpOperationCode    getOperationCode() const { return getContainerCode(); }
    inline void                setOperationCode(MtpOperationCode code)
                                                    { return setContainerCode(code); }
};

}; // namespace android

#endif // _MTP_REQUEST_PACKET_H
