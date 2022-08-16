/*
 * Copyright 2021 The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_TV_DESCRAMBLER_CLIENT_H_
#define _ANDROID_MEDIA_TV_DESCRAMBLER_CLIENT_H_

#include <aidl/android/hardware/tv/tuner/Result.h>
#include <aidl/android/media/tv/tuner/ITunerDescrambler.h>

#include "DemuxClient.h"
#include "FilterClient.h"

using ::aidl::android::hardware::tv::tuner::DemuxPid;
using ::aidl::android::hardware::tv::tuner::Result;
using ::aidl::android::media::tv::tuner::ITunerDescrambler;

using namespace std;

namespace android {

struct DescramblerClient : public RefBase {

public:
    DescramblerClient(shared_ptr<ITunerDescrambler> tunerDescrambler);
    ~DescramblerClient();

     /**
     * Set a demux as source of the descrambler.
     */
    Result setDemuxSource(sp<DemuxClient> demuxClient);

    /**
     * Set a key token to link descrambler to a key slot.
     */
    Result setKeyToken(vector<uint8_t> keyToken);

    /**
     * Add packets' PID to the descrambler for descrambling.
     */
    Result addPid(DemuxPid pid, sp<FilterClient> optionalSourceFilter);

    /**
     * Remove packets' PID from the descrambler.
     */
    Result removePid(DemuxPid pid, sp<FilterClient> optionalSourceFilter);

    /**
     * Close a new interface of ITunerDescrambler.
     */
    Result close();

private:
    /**
     * An AIDL Tuner Descrambler Singleton assigned at the first time the Tuner Client
     * opens a descrambler. Default null when descrambler is not opened.
     */
    shared_ptr<ITunerDescrambler> mTunerDescrambler;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_DESCRAMBLER_CLIENT_H_
