/**
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_RADIO_TUNER_TUNERCALLBACK_H
#define _ANDROID_SERVER_RADIO_TUNER_TUNERCALLBACK_H

#include "NativeCallbackThread.h"

#include <android/hardware/broadcastradio/1.1/ITunerCallback.h>
#include <jni.h>

namespace android {

void register_android_server_radio_Tuner_TunerCallback(JavaVM *vm, JNIEnv *env);

namespace server {
namespace radio {
namespace Tuner {

class TunerCallback : public hardware::broadcastradio::V1_1::ITunerCallback {
    jobject mTuner;
    jobject mClientCallback;
    NativeCallbackThread mCallbackThread;

    DISALLOW_COPY_AND_ASSIGN(TunerCallback);

public:
    TunerCallback(JNIEnv *env, jobject tuner, jobject clientCallback);
    virtual ~TunerCallback();

    void detach();

    virtual hardware::Return<void> hardwareFailure();
    virtual hardware::Return<void> configChange(hardware::broadcastradio::V1_0::Result result,
            const hardware::broadcastradio::V1_0::BandConfig& config);
    virtual hardware::Return<void> tuneComplete(hardware::broadcastradio::V1_0::Result result,
            const hardware::broadcastradio::V1_0::ProgramInfo& info);
    virtual hardware::Return<void> afSwitch(
            const hardware::broadcastradio::V1_0::ProgramInfo& info);
    virtual hardware::Return<void> antennaStateChange(bool connected);
    virtual hardware::Return<void> trafficAnnouncement(bool active);
    virtual hardware::Return<void> emergencyAnnouncement(bool active);
    virtual hardware::Return<void> newMetadata(uint32_t channel, uint32_t subChannel,
            const hardware::hidl_vec<hardware::broadcastradio::V1_0::MetaData>& metadata);
    virtual hardware::Return<void> tuneComplete_1_1(hardware::broadcastradio::V1_0::Result result,
            const hardware::broadcastradio::V1_1::ProgramInfo& info);
    virtual hardware::Return<void> afSwitch_1_1(const hardware::broadcastradio::V1_1::ProgramInfo& info);
    virtual hardware::Return<void> backgroundScanAvailable(bool isAvailable);
    virtual hardware::Return<void> backgroundScanComplete(
            hardware::broadcastradio::V1_1::ProgramListResult result);
    virtual hardware::Return<void> programListChanged();
};

} // namespace Tuner
} // namespace radio
} // namespace server
} // namespace android

#endif // _ANDROID_SERVER_RADIO_TUNER_TUNERCALLBACK_H
