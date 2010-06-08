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

#ifndef A_AVC_ASSEMBLER_H_

#define A_AVC_ASSEMBLER_H_

#include "ARTPAssembler.h"

#include <utils/List.h>
#include <utils/RefBase.h>

namespace android {

struct ABuffer;
struct AMessage;

struct AAVCAssembler : public ARTPAssembler {
    AAVCAssembler(const sp<AMessage> &notify);

protected:
    virtual ~AAVCAssembler();

    virtual AssemblyStatus assembleMore(const sp<ARTPSource> &source);
    virtual void packetLost();

private:
    sp<AMessage> mNotifyMsg;

    uint32_t mAccessUnitRTPTime;
    bool mNextExpectedSeqNoValid;
    uint32_t mNextExpectedSeqNo;
    bool mAccessUnitDamaged;
    List<sp<ABuffer> > mNALUnits;

    AssemblyStatus addNALUnit(const sp<ARTPSource> &source);
    void addSingleNALUnit(const sp<ABuffer> &buffer);
    AssemblyStatus addFragmentedNALUnit(List<sp<ABuffer> > *queue);
    bool addSingleTimeAggregationPacket(const sp<ABuffer> &buffer);

    void submitAccessUnit();

    DISALLOW_EVIL_CONSTRUCTORS(AAVCAssembler);
};

}  // namespace android

#endif  // A_AVC_ASSEMBLER_H_
