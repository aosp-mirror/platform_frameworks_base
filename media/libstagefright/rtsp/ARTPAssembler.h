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

#ifndef A_RTP_ASSEMBLER_H_

#define A_RTP_ASSEMBLER_H_

#include <media/stagefright/foundation/ABase.h>
#include <utils/RefBase.h>

namespace android {

struct ABuffer;
struct ARTPSource;

struct ARTPAssembler : public RefBase {
    enum AssemblyStatus {
        MALFORMED_PACKET,
        WRONG_SEQUENCE_NUMBER,
        NOT_ENOUGH_DATA,
        OK
    };

    ARTPAssembler();

    void onPacketReceived(const sp<ARTPSource> &source);
    virtual void onByeReceived() = 0;

protected:
    virtual AssemblyStatus assembleMore(const sp<ARTPSource> &source) = 0;
    virtual void packetLost() = 0;

    static void CopyTimes(const sp<ABuffer> &to, const sp<ABuffer> &from);

private:
    int64_t mFirstFailureTimeUs;

    DISALLOW_EVIL_CONSTRUCTORS(ARTPAssembler);
};

}  // namespace android

#endif  // A_RTP_ASSEMBLER_H_
