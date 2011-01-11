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

#ifndef A_MPEG4_AUDIO_ASSEMBLER_H_

#define A_MPEG4_AUDIO_ASSEMBLER_H_

#include "ARTPAssembler.h"

#include <utils/List.h>

#include <stdint.h>

namespace android {

struct AMessage;
struct AString;

struct AMPEG4AudioAssembler : public ARTPAssembler {
    AMPEG4AudioAssembler(
            const sp<AMessage> &notify, const AString &params);

protected:
    virtual ~AMPEG4AudioAssembler();

    virtual AssemblyStatus assembleMore(const sp<ARTPSource> &source);
    virtual void onByeReceived();
    virtual void packetLost();

private:
    sp<AMessage> mNotifyMsg;

    bool mMuxConfigPresent;
    unsigned mNumSubFrames;
    unsigned mFrameLengthType;
    bool mOtherDataPresent;
    unsigned mOtherDataLenBits;

    uint32_t mAccessUnitRTPTime;
    bool mNextExpectedSeqNoValid;
    uint32_t mNextExpectedSeqNo;
    bool mAccessUnitDamaged;
    List<sp<ABuffer> > mPackets;

    AssemblyStatus addPacket(const sp<ARTPSource> &source);
    void submitAccessUnit();

    sp<ABuffer> removeLATMFraming(const sp<ABuffer> &buffer);

    DISALLOW_EVIL_CONSTRUCTORS(AMPEG4AudioAssembler);
};

}  // namespace android

#endif  // A_MPEG4_AUDIO_ASSEMBLER_H_
