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
#include <fmq/AidlMessageQueue.h>
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

using ::android::AidlMessageQueue;

using ::aidl::android::hardware::common::fmq::MQDescriptor;
using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using ::aidl::android::hardware::tv::input::BnTvInputCallback;
using ::aidl::android::hardware::tv::input::CableConnectionStatus;
using ::aidl::android::hardware::tv::input::TvInputEventType;
using ::aidl::android::hardware::tv::input::TvInputType;
using ::aidl::android::hardware::tv::input::TvMessageEvent;
using ::aidl::android::hardware::tv::input::TvMessageEventType;

using AidlAudioDevice = ::aidl::android::media::audio::common::AudioDevice;
using AidlAudioDeviceAddress = ::aidl::android::media::audio::common::AudioDeviceAddress;
using AidlAudioDeviceDescription = ::aidl::android::media::audio::common::AudioDeviceDescription;
using AidlAudioDeviceType = ::aidl::android::media::audio::common::AudioDeviceType;
using AidlITvInput = ::aidl::android::hardware::tv::input::ITvInput;
using AidlNativeHandle = ::aidl::android::hardware::common::NativeHandle;
using AidlTvInputDeviceInfo = ::aidl::android::hardware::tv::input::TvInputDeviceInfo;
using AidlTvInputEvent = ::aidl::android::hardware::tv::input::TvInputEvent;
using AidlTvMessage = ::aidl::android::hardware::tv::input::TvMessage;
using AidlTvMessageEvent = ::aidl::android::hardware::tv::input::TvMessageEvent;
using AidlTvMessageEventType = ::aidl::android::hardware::tv::input::TvMessageEventType;
using AidlTvStreamConfig = ::aidl::android::hardware::tv::input::TvStreamConfig;

using AidlMessageQueueMap = std::unordered_map<
        int,
        std::unordered_map<int, std::shared_ptr<AidlMessageQueue<int8_t, SynchronizedReadWrite>>>>;

extern gBundleClassInfoType gBundleClassInfo;
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
    int setTvMessageEnabled(int deviceId, int streamId, int type, bool enabled);
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

    class TvMessageEventWrapper {
    public:
        TvMessageEventWrapper() {}

        static TvMessageEventWrapper createEventWrapper(
                const AidlTvMessageEvent& aidlTvMessageEvent, bool isLegacyMessage);

        int streamId;
        int deviceId;
        std::vector<AidlTvMessage> messages;
        AidlTvMessageEventType type;
    };

    class NotifyHandler : public MessageHandler {
    public:
        NotifyHandler(JTvInputHal* hal, const TvInputEventWrapper& event);

        void handleMessage(const Message& message) override;

    private:
        TvInputEventWrapper mEvent;
        JTvInputHal* mHal;
    };

    class NotifyTvMessageHandler : public MessageHandler {
    public:
        NotifyTvMessageHandler(JTvInputHal* hal, const TvMessageEventWrapper& event);

        void handleMessage(const Message& message) override;

    private:
        TvMessageEventWrapper mEvent;
        JTvInputHal* mHal;
    };

    class AidlTvInputCallback : public BnTvInputCallback {
    public:
        explicit AidlTvInputCallback(JTvInputHal* hal);
        ::ndk::ScopedAStatus notify(const AidlTvInputEvent& event) override;
        ::ndk::ScopedAStatus notifyTvMessageEvent(const AidlTvMessageEvent& event) override;

    private:
        JTvInputHal* mHal;
    };

    class HidlTvInputCallback : public HidlITvInputCallback {
    public:
        explicit HidlTvInputCallback(JTvInputHal* hal);
        Return<void> notify(const HidlTvInputEvent& event) override;

    private:
        JTvInputHal* mHal;
    };

    class TvInputCallbackWrapper {
    public:
        explicit TvInputCallbackWrapper(JTvInputHal* hal);
        std::shared_ptr<AidlTvInputCallback> aidlTvInputCallback;
        sp<HidlTvInputCallback> hidlTvInputCallback;
    };

    class ITvInputWrapper {
    public:
        ITvInputWrapper(std::shared_ptr<AidlITvInput>& aidlTvInput);
        ITvInputWrapper(sp<HidlITvInput>& hidlTvInput);

        ::ndk::ScopedAStatus setCallback(
                const std::shared_ptr<TvInputCallbackWrapper>& in_callback);
        ::ndk::ScopedAStatus getStreamConfigurations(int32_t in_deviceId,
                                                     std::vector<AidlTvStreamConfig>* _aidl_return);
        ::ndk::ScopedAStatus openStream(int32_t in_deviceId, int32_t in_streamId,
                                        AidlNativeHandle* _aidl_return);
        ::ndk::ScopedAStatus closeStream(int32_t in_deviceId, int32_t in_streamId);
        ::ndk::ScopedAStatus setTvMessageEnabled(int32_t deviceId, int32_t streamId,
                                                 TvMessageEventType in_type, bool enabled);
        ::ndk::ScopedAStatus getTvMessageQueueDesc(
                MQDescriptor<int8_t, SynchronizedReadWrite>* out_queue, int32_t in_deviceId,
                int32_t in_streamId);
        ::ndk::ScopedAStatus getAidlInterfaceVersion(int32_t* _aidl_return);

    private:
        ::ndk::ScopedAStatus hidlSetCallback(const sp<HidlTvInputCallback>& in_callback);
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
    void onTvMessage(int deviceId, int streamId, AidlTvMessageEventType type,
                     AidlTvMessage& message, signed char data[], int dataLength);

    Mutex mStreamLock;
    jweak mThiz;
    sp<Looper> mLooper;
    AidlMessageQueueMap mQueueMap;

    KeyedVector<int, KeyedVector<int, Connection> > mConnections;

    std::shared_ptr<ITvInputWrapper> mTvInput;
    std::shared_ptr<TvInputCallbackWrapper> mTvInputCallback;
};

} // namespace android
