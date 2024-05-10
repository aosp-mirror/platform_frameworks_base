/*
 * Copyright 2022 The Android Open Source Project
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

#include "JTvInputHal.h"

// Implement all HIDL related functions here.

namespace android {

void JTvInputHal::hidlSetUpAudioInfo(JNIEnv* env, jobject& builder,
                                     const TvInputDeviceInfoWrapper& info) {
    env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.audioType,
                          info.hidlAudioType);
    if (info.hidlAudioType != HidlAudioDevice::NONE) {
        uint8_t buffer[info.hidlAudioAddress.size() + 1];
        memcpy(buffer, info.hidlAudioAddress.data(), info.hidlAudioAddress.size());
        buffer[info.hidlAudioAddress.size()] = '\0';
        jstring audioAddress = env->NewStringUTF(reinterpret_cast<const char*>(buffer));
        env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.audioAddress,
                              audioAddress);
        env->DeleteLocalRef(audioAddress);
    }
}

JTvInputHal::TvInputDeviceInfoWrapper
JTvInputHal::TvInputDeviceInfoWrapper::createDeviceInfoWrapper(
        const HidlTvInputDeviceInfo& hidlTvInputDeviceInfo) {
    TvInputDeviceInfoWrapper deviceInfo;
    deviceInfo.isHidl = true;
    deviceInfo.deviceId = hidlTvInputDeviceInfo.deviceId;
    deviceInfo.type = TvInputType(static_cast<int32_t>(hidlTvInputDeviceInfo.type));
    deviceInfo.portId = hidlTvInputDeviceInfo.portId;
    deviceInfo.cableConnectionStatus = CableConnectionStatus(
            static_cast<int32_t>(hidlTvInputDeviceInfo.cableConnectionStatus));
    deviceInfo.hidlAudioType = hidlTvInputDeviceInfo.audioType;
    deviceInfo.hidlAudioAddress = hidlTvInputDeviceInfo.audioAddress;
    return deviceInfo;
}

JTvInputHal::TvInputEventWrapper JTvInputHal::TvInputEventWrapper::createEventWrapper(
        const HidlTvInputEvent& hidlTvInputEvent) {
    TvInputEventWrapper event;
    event.type = TvInputEventType(static_cast<int32_t>(hidlTvInputEvent.type));
    event.deviceInfo =
            TvInputDeviceInfoWrapper::createDeviceInfoWrapper(hidlTvInputEvent.deviceInfo);
    return event;
}

JTvInputHal::HidlTvInputCallback::HidlTvInputCallback(JTvInputHal* hal) {
    mHal = hal;
}

Return<void> JTvInputHal::HidlTvInputCallback::notify(const HidlTvInputEvent& event) {
    mHal->mLooper->sendMessage(new NotifyHandler(mHal,
                                                 TvInputEventWrapper::createEventWrapper(event)),
                               static_cast<int>(event.type));
    return Void();
}

JTvInputHal::ITvInputWrapper::ITvInputWrapper(sp<HidlITvInput>& hidlTvInput)
      : mIsHidl(true), mHidlTvInput(hidlTvInput) {}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::hidlSetCallback(
        const sp<HidlTvInputCallback>& in_callback) {
    mHidlTvInput->setCallback(in_callback);
    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::hidlGetStreamConfigurations(
        int32_t in_deviceId, std::vector<AidlTvStreamConfig>* _aidl_return) {
    Result result = Result::UNKNOWN;
    hidl_vec<HidlTvStreamConfig> list;
    mHidlTvInput->getStreamConfigurations(in_deviceId,
                                          [&result, &list](Result res,
                                                           hidl_vec<HidlTvStreamConfig> configs) {
                                              result = res;
                                              if (res == Result::OK) {
                                                  list = configs;
                                              }
                                          });
    if (result != Result::OK) {
        ALOGE("Couldn't get stream configs for device id:%d result:%d", in_deviceId, result);
        return ::ndk::ScopedAStatus::fromServiceSpecificError(static_cast<int32_t>(result));
    }
    for (size_t i = 0; i < list.size(); ++i) {
        AidlTvStreamConfig config;
        config.streamId = list[i].streamId;
        config.maxVideoHeight = list[i].maxVideoHeight;
        config.maxVideoWidth = list[i].maxVideoWidth;
        _aidl_return->push_back(config);
    }
    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::hidlOpenStream(int32_t in_deviceId,
                                                                  int32_t in_streamId,
                                                                  AidlNativeHandle* _aidl_return) {
    Result result = Result::UNKNOWN;
    native_handle_t* sidebandStream;
    mHidlTvInput->openStream(in_deviceId, in_streamId,
                             [&result, &sidebandStream](Result res, const native_handle_t* handle) {
                                 result = res;
                                 if (res == Result::OK) {
                                     if (handle) {
                                         sidebandStream = native_handle_clone(handle);
                                     }
                                 }
                             });
    if (result != Result::OK) {
        ALOGE("Couldn't open stream. device id:%d stream id:%d result:%d", in_deviceId, in_streamId,
              result);
        return ::ndk::ScopedAStatus::fromServiceSpecificError(static_cast<int32_t>(result));
    }
    *_aidl_return = makeToAidl(sidebandStream);
    native_handle_delete(sidebandStream);
    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::hidlCloseStream(int32_t in_deviceId,
                                                                   int32_t in_streamId) {
    Result result = mHidlTvInput->closeStream(in_deviceId, in_streamId);
    if (result != Result::OK) {
        return ::ndk::ScopedAStatus::fromServiceSpecificError(static_cast<int32_t>(result));
    }
    return ::ndk::ScopedAStatus::ok();
}

} // namespace android
