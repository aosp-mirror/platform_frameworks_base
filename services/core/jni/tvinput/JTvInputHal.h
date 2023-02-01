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

#pragma once

#define TV_INPUT_AIDL_SERVICE_NAME "android.hardware.tv.input.ITvInput/default"

#include <aidl/android/hardware/tv/input/BnTvInputCallback.h>
#include <aidl/android/hardware/tv/input/CableConnectionStatus.h>
#include <aidl/android/hardware/tv/input/ITvInput.h>
#include <aidl/android/media/audio/common/AudioDevice.h>
#include <aidlcommonsupport/NativeHandle.h>
#include <android/binder_manager.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/NativeHandle.h>

#include <iomanip>

#include "BufferProducerThread.h"
#include "TvInputHal_hidl.h"
#include "android_os_MessageQueue.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "tvinput/jstruct.h"

using ::aidl::android::hardware::tv::input::BnTvInputCallback;
using ::aidl::android::hardware::tv::input::CableConnectionStatus;
using ::aidl::android::hardware::tv::input::TvInputEventType;
using ::aidl::android::hardware::tv::input::TvInputType;

using AidlAudioDevice = ::aidl::android::media::audio::common::AudioDevice;
using AidlAudioDeviceAddress = ::aidl::android::media::audio::common::AudioDeviceAddress;
using AidlAudioDeviceType = ::aidl::android::media::audio::common::AudioDeviceType;
using AidlITvInput = ::aidl::android::hardware::tv::input::ITvInput;
using AidlNativeHandle = ::aidl::android::hardware::common::NativeHandle;
using AidlTvInputDeviceInfo = ::aidl::android::hardware::tv::input::TvInputDeviceInfo;
using AidlTvInputEvent = ::aidl::android::hardware::tv::input::TvInputEvent;
using AidlTvMessageEvent = ::aidl::android::hardware::tv::input::TvMessageEvent;
using AidlTvStreamConfig = ::aidl::android::hardware::tv::input::TvStreamConfig;

extern gTvInputHalClassInfoType gTvInputHalClassInfo;
extern gTvStreamConfigClassInfoType gTvStreamConfigClassInfo;
extern gTvStreamConfigBuilderClassInfoType gTvStreamConfigBuilderClassInfo;
extern gTvInputHardwareInfoBuilderClassInfoType gTvInputHardwareInfoBuilderClassInfo;

namespace android {

class JTvInputHal {
public:
    ~JTvInputHal();

    static JTvInputHal* createInstance(JNIEnv* env, jobject thiz, const sp<Looper>& looper);

    int addOrUpdateStream(int deviceId, int streamId, const sp<Surface>& surface);
    int removeStream(int deviceId, int streamId);
    const std::vector<AidlTvStreamConfig> getStreamConfigs(int deviceId);

private:
    // Connection between a surface and a stream.
    class Connection {
    public:
        Connection() {}

        sp<Surface> mSurface;
        tv_stream_type_t mStreamType;

        // Only valid when mStreamType == TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE
        sp<NativeHandle> mSourceHandle;
        // Only valid when mStreamType == TV_STREAM_TYPE_BUFFER_PRODUCER
        sp<BufferProducerThread> mThread;
    };

    class TvInputDeviceInfoWrapper {
    public:
        TvInputDeviceInfoWrapper() {}

        static TvInputDeviceInfoWrapper createDeviceInfoWrapper(
                const AidlTvInputDeviceInfo& aidlTvInputDeviceInfo);
        static TvInputDeviceInfoWrapper createDeviceInfoWrapper(
                const HidlTvInputDeviceInfo& hidlTvInputDeviceInfo);

        bool isHidl;
        int deviceId;
        TvInputType type;
        int portId;
        CableConnectionStatus cableConnectionStatus;
        AidlAudioDevice aidlAudioDevice;
        HidlAudioDevice hidlAudioType;
        ::android::hardware::hidl_array<uint8_t, 32> hidlAudioAddress;
    };

    class TvInputEventWrapper {
    public:
        TvInputEventWrapper() {}

        static TvInputEventWrapper createEventWrapper(const AidlTvInputEvent& aidlTvInputEvent);
        static TvInputEventWrapper createEventWrapper(const HidlTvInputEvent& hidlTvInputEvent);

        TvInputEventType type;
        TvInputDeviceInfoWrapper deviceInfo;
    };

    class NotifyHandler : public MessageHandler {
    public:
        NotifyHandler(JTvInputHal* hal, const TvInputEventWrapper& event);

        void handleMessage(const Message& message) override;

    private:
        TvInputEventWrapper mEvent;
        JTvInputHal* mHal;
    };

    class TvInputCallback : public HidlITvInputCallback, public BnTvInputCallback {
    public:
        explicit TvInputCallback(JTvInputHal* hal);
        ::ndk::ScopedAStatus notify(const AidlTvInputEvent& event) override;
        ::ndk::ScopedAStatus notifyTvMessageEvent(const AidlTvMessageEvent& event) override;
        Return<void> notify(const HidlTvInputEvent& event) override;

    private:
        JTvInputHal* mHal;
    };

    class ITvInputWrapper {
    public:
        ITvInputWrapper(std::shared_ptr<AidlITvInput>& aidlTvInput);
        ITvInputWrapper(sp<HidlITvInput>& hidlTvInput);

        ::ndk::ScopedAStatus setCallback(const std::shared_ptr<TvInputCallback>& in_callback);
        ::ndk::ScopedAStatus getStreamConfigurations(int32_t in_deviceId,
                                                     std::vector<AidlTvStreamConfig>* _aidl_return);
        ::ndk::ScopedAStatus openStream(int32_t in_deviceId, int32_t in_streamId,
                                        AidlNativeHandle* _aidl_return);
        ::ndk::ScopedAStatus closeStream(int32_t in_deviceId, int32_t in_streamId);

    private:
        ::ndk::ScopedAStatus hidlSetCallback(const std::shared_ptr<TvInputCallback>& in_callback);
        ::ndk::ScopedAStatus hidlGetStreamConfigurations(
                int32_t in_deviceId, std::vector<AidlTvStreamConfig>* _aidl_return);
        ::ndk::ScopedAStatus hidlOpenStream(int32_t in_deviceId, int32_t in_streamId,
                                            AidlNativeHandle* _aidl_return);
        ::ndk::ScopedAStatus hidlCloseStream(int32_t in_deviceId, int32_t in_streamId);

        bool mIsHidl;
        sp<HidlITvInput> mHidlTvInput;
        std::shared_ptr<AidlITvInput> mAidlTvInput;
    };

    JTvInputHal(JNIEnv* env, jobject thiz, std::shared_ptr<ITvInputWrapper> tvInput,
                const sp<Looper>& looper);

    void hidlSetUpAudioInfo(JNIEnv* env, jobject& builder, const TvInputDeviceInfoWrapper& info);
    void onDeviceAvailable(const TvInputDeviceInfoWrapper& info);
    void onDeviceUnavailable(int deviceId);
    void onStreamConfigurationsChanged(int deviceId, int cableConnectionStatus);
    void onCaptured(int deviceId, int streamId, uint32_t seq, bool succeeded);

    Mutex mLock;
    Mutex mStreamLock;
    jweak mThiz;
    sp<Looper> mLooper;

    KeyedVector<int, KeyedVector<int, Connection> > mConnections;

    std::shared_ptr<ITvInputWrapper> mTvInput;
    std::shared_ptr<TvInputCallback> mTvInputCallback;
};

} // namespace android
