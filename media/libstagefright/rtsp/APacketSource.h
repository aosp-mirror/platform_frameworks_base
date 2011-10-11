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

#ifndef A_PACKET_SOURCE_H_

#define A_PACKET_SOURCE_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/MetaData.h>
#include <utils/RefBase.h>

namespace android {

struct ASessionDescription;

struct APacketSource : public RefBase {
    APacketSource(const sp<ASessionDescription> &sessionDesc, size_t index);

    status_t initCheck() const;

    virtual sp<MetaData> getFormat();

protected:
    virtual ~APacketSource();

private:
    status_t mInitCheck;

    sp<MetaData> mFormat;

    DISALLOW_EVIL_CONSTRUCTORS(APacketSource);
};


}  // namespace android

#endif  // A_PACKET_SOURCE_H_
