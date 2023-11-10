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

#include <nativehelper/ScopedLocalRef.h>

namespace android {

JTvInputHal::JTvInputHal(JNIEnv* env, jobject thiz, std::shared_ptr<ITvInputWrapper> tvInput,
                         const sp<Looper>& looper) {
    mThiz = env->NewWeakGlobalRef(thiz);
    mTvInput = tvInput;
    mLooper = looper;
    mTvInputCallback = std::shared_ptr<TvInputCallbackWrapper>(new TvInputCallbackWrapper(this));
    mTvInput->setCallback(mTvInputCallback);
}

JTvInputHal::~JTvInputHal() {
    mTvInput->setCallback(nullptr);
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mThiz);
    mThiz = NULL;
}

JTvInputHal* JTvInputHal::createInstance(JNIEnv* env, jobject thiz, const sp<Looper>& looper) {
    sp<HidlITvInput> hidlITvInput = HidlITvInput::getService();
    if (hidlITvInput != nullptr) {
        ALOGD("tv.input service is HIDL.");
        return new JTvInputHal(env, thiz,
                               std::shared_ptr<ITvInputWrapper>(new ITvInputWrapper(hidlITvInput)),
                               looper);
    }
    std::shared_ptr<AidlITvInput> aidlITvInput = nullptr;
    if (AServiceManager_isDeclared(TV_INPUT_AIDL_SERVICE_NAME)) {
        ::ndk::SpAIBinder binder(AServiceManager_waitForService(TV_INPUT_AIDL_SERVICE_NAME));
        aidlITvInput = AidlITvInput::fromBinder(binder);
    }
    if (aidlITvInput == nullptr) {
        ALOGE("Couldn't get tv.input service.");
        return nullptr;
    }
    return new JTvInputHal(env, thiz,
                           std::shared_ptr<ITvInputWrapper>(new ITvInputWrapper(aidlITvInput)),
                           looper);
}

int JTvInputHal::addOrUpdateStream(int deviceId, int streamId, const sp<Surface>& surface) {
    Mutex::Autolock autoLock(&mStreamLock);
    KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
    if (connections.indexOfKey(streamId) < 0) {
        connections.add(streamId, Connection());
    }
    Connection& connection = connections.editValueFor(streamId);
    if (connection.mSurface == surface) {
        // Nothing to do
        return NO_ERROR;
    }
    // Clear the surface in the connection.
    if (connection.mSurface != NULL) {
        if (connection.mStreamType == TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE) {
            if (Surface::isValid(connection.mSurface)) {
                connection.mSurface->setSidebandStream(NULL);
            }
        }
        connection.mSurface.clear();
    }
    if (connection.mSourceHandle == NULL && connection.mThread == NULL) {
        // Need to configure stream
        ::ndk::ScopedAStatus status;
        std::vector<AidlTvStreamConfig> list;
        status = mTvInput->getStreamConfigurations(deviceId, &list);
        if (!status.isOk()) {
            ALOGE("Couldn't get stream configs for device id:%d result:%d", deviceId,
                  status.getServiceSpecificError());
            return UNKNOWN_ERROR;
        }
        int configIndex = -1;
        for (size_t i = 0; i < list.size(); ++i) {
            if (list[i].streamId == streamId) {
                configIndex = i;
                break;
            }
        }
        if (configIndex == -1) {
            ALOGE("Cannot find a config with given stream ID: %d", streamId);
            return BAD_VALUE;
        }
        connection.mStreamType = TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE;

        AidlNativeHandle sidebandStream;
        status = mTvInput->openStream(deviceId, streamId, &sidebandStream);
        if (!status.isOk()) {
            ALOGE("Couldn't open stream. device id:%d stream id:%d result:%d", deviceId, streamId,
                  status.getServiceSpecificError());
            return UNKNOWN_ERROR;
        }
        connection.mSourceHandle = NativeHandle::create(dupFromAidl(sidebandStream), true);
    }
    connection.mSurface = surface;
    if (connection.mSurface != nullptr) {
        connection.mSurface->setSidebandStream(connection.mSourceHandle);
    }
    return NO_ERROR;
}

int JTvInputHal::removeStream(int deviceId, int streamId) {
    Mutex::Autolock autoLock(&mStreamLock);
    KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
    if (connections.indexOfKey(streamId) < 0) {
        return BAD_VALUE;
    }
    Connection& connection = connections.editValueFor(streamId);
    if (connection.mSurface == NULL) {
        // Nothing to do
        return NO_ERROR;
    }
    if (Surface::isValid(connection.mSurface)) {
        connection.mSurface->setSidebandStream(NULL);
    }
    connection.mSurface.clear();
    if (connection.mThread != NULL) {
        connection.mThread->shutdown();
        connection.mThread.clear();
    }
    if (!mTvInput->closeStream(deviceId, streamId).isOk()) {
        ALOGE("Couldn't close stream. device id:%d stream id:%d", deviceId, streamId);
        return BAD_VALUE;
    }
    if (connection.mSourceHandle != NULL) {
        connection.mSourceHandle.clear();
    }
    return NO_ERROR;
}

int JTvInputHal::setTvMessageEnabled(int deviceId, int streamId, int type, bool enabled) {
    if (!mTvInput->setTvMessageEnabled(deviceId, streamId,
                                       static_cast<AidlTvMessageEventType>(type), enabled)
                 .isOk()) {
        ALOGE("Error in setTvMessageEnabled. device id:%d stream id:%d", deviceId, streamId);
        return BAD_VALUE;
    }
    return NO_ERROR;
}

const std::vector<AidlTvStreamConfig> JTvInputHal::getStreamConfigs(int deviceId) {
    std::vector<AidlTvStreamConfig> list;
    ::ndk::ScopedAStatus status = mTvInput->getStreamConfigurations(deviceId, &list);
    if (!status.isOk()) {
        ALOGE("Couldn't get stream configs for device id:%d result:%d", deviceId,
              status.getServiceSpecificError());
        return std::vector<AidlTvStreamConfig>();
    }
    return list;
}

static const std::map<std::pair<AidlAudioDeviceType, std::string>, audio_devices_t>
        aidlToNativeAudioType = {
    {{AidlAudioDeviceType::IN_DEVICE, AidlAudioDeviceDescription::CONNECTION_ANALOG},
        AUDIO_DEVICE_IN_LINE},
    {{AidlAudioDeviceType::IN_DEVICE, AidlAudioDeviceDescription::CONNECTION_HDMI},
        AUDIO_DEVICE_IN_HDMI},
    {{AidlAudioDeviceType::IN_DEVICE, AidlAudioDeviceDescription::CONNECTION_HDMI_ARC},
        AUDIO_DEVICE_IN_HDMI_ARC},
    {{AidlAudioDeviceType::IN_DEVICE, AidlAudioDeviceDescription::CONNECTION_HDMI_EARC},
        AUDIO_DEVICE_IN_HDMI_EARC},
    {{AidlAudioDeviceType::IN_DEVICE, AidlAudioDeviceDescription::CONNECTION_IP_V4},
        AUDIO_DEVICE_IN_IP},
    {{AidlAudioDeviceType::IN_DEVICE, AidlAudioDeviceDescription::CONNECTION_SPDIF},
        AUDIO_DEVICE_IN_SPDIF},
    {{AidlAudioDeviceType::IN_LOOPBACK, ""}, AUDIO_DEVICE_IN_LOOPBACK},
    {{AidlAudioDeviceType::IN_TV_TUNER, ""}, AUDIO_DEVICE_IN_TV_TUNER},
};

void JTvInputHal::onDeviceAvailable(const TvInputDeviceInfoWrapper& info) {
    {
        Mutex::Autolock autoLock(&mStreamLock);
        mConnections.add(info.deviceId, KeyedVector<int, Connection>());
    }
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject builder = env->NewObject(gTvInputHardwareInfoBuilderClassInfo.clazz,
                                     gTvInputHardwareInfoBuilderClassInfo.constructor);
    env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.deviceId, info.deviceId);
    env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.type, info.type);
    if (info.type == TvInputType::HDMI) {
        env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.hdmiPortId,
                              info.portId);
    }
    env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.cableConnectionStatus,
                          info.cableConnectionStatus);
    if (info.isHidl) {
        hidlSetUpAudioInfo(env, builder, info);
    } else {
        auto it = aidlToNativeAudioType.find({info.aidlAudioDevice.type.type,
                                    info.aidlAudioDevice.type.connection});
        audio_devices_t nativeAudioType = AUDIO_DEVICE_NONE;
        if (it != aidlToNativeAudioType.end()) {
            nativeAudioType = it->second;
        }
        env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.audioType,
            nativeAudioType);
        if (info.aidlAudioDevice.type.type != AidlAudioDeviceType::NONE) {
            std::stringstream ss;
            switch (info.aidlAudioDevice.address.getTag()) {
                case AidlAudioDeviceAddress::id:
                    ss << info.aidlAudioDevice.address.get<AidlAudioDeviceAddress::id>();
                    break;
                case AidlAudioDeviceAddress::mac: {
                    std::vector<uint8_t> addrList =
                            info.aidlAudioDevice.address.get<AidlAudioDeviceAddress::mac>();
                    for (int i = 0; i < addrList.size(); i++) {
                        if (i != 0) {
                            ss << ':';
                        }
                        ss << std::uppercase << std::setfill('0') << std::setw(2) << std::hex
                           << static_cast<int32_t>(addrList[i]);
                    }
                } break;
                case AidlAudioDeviceAddress::ipv4: {
                    std::vector<uint8_t> addrList =
                            info.aidlAudioDevice.address.get<AidlAudioDeviceAddress::ipv4>();
                    for (int i = 0; i < addrList.size(); i++) {
                        if (i != 0) {
                            ss << '.';
                        }
                        ss << static_cast<int32_t>(addrList[i]);
                    }
                } break;
                case AidlAudioDeviceAddress::ipv6: {
                    std::vector<int32_t> addrList =
                            info.aidlAudioDevice.address.get<AidlAudioDeviceAddress::ipv6>();
                    for (int i = 0; i < addrList.size(); i++) {
                        if (i != 0) {
                            ss << ':';
                        }
                        ss << std::uppercase << std::setfill('0') << std::setw(4) << std::hex
                           << addrList[i];
                    }
                } break;
                case AidlAudioDeviceAddress::alsa: {
                    std::vector<int32_t> addrList =
                            info.aidlAudioDevice.address.get<AidlAudioDeviceAddress::alsa>();
                    ss << "card=" << addrList[0] << ";device=" << addrList[1];
                } break;
            }
            std::string bufferStr = ss.str();
            jstring audioAddress = env->NewStringUTF(bufferStr.c_str());
            env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.audioAddress,
                                  audioAddress);
            env->DeleteLocalRef(audioAddress);
        }
    }

    jobject infoObject = env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.build);

    env->CallVoidMethod(mThiz, gTvInputHalClassInfo.deviceAvailable, infoObject);

    env->DeleteLocalRef(builder);
    env->DeleteLocalRef(infoObject);
}

void JTvInputHal::onDeviceUnavailable(int deviceId) {
    {
        Mutex::Autolock autoLock(&mStreamLock);
        KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
        for (size_t i = 0; i < connections.size(); ++i) {
            removeStream(deviceId, connections.keyAt(i));
        }
        connections.clear();
        mConnections.removeItem(deviceId);
    }
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mThiz, gTvInputHalClassInfo.deviceUnavailable, deviceId);
}

void JTvInputHal::onStreamConfigurationsChanged(int deviceId, int cableConnectionStatus) {
    {
        Mutex::Autolock autoLock(&mStreamLock);
        KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
        for (size_t i = 0; i < connections.size(); ++i) {
            removeStream(deviceId, connections.keyAt(i));
        }
        connections.clear();
    }
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mThiz, gTvInputHalClassInfo.streamConfigsChanged, deviceId,
                        cableConnectionStatus);
}

void JTvInputHal::onTvMessage(int deviceId, int streamId, AidlTvMessageEventType type,
                              AidlTvMessage& message, signed char data[], int dataLength) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef<jobject> bundle(env,
                                   env->NewObject(gBundleClassInfo.clazz,
                                                  gBundleClassInfo.constructor));
    ScopedLocalRef<jbyteArray> convertedData(env, env->NewByteArray(dataLength));
    env->SetByteArrayRegion(convertedData.get(), 0, dataLength, reinterpret_cast<jbyte*>(data));
    std::string key = "android.media.tv.TvInputManager.raw_data";
    ScopedLocalRef<jstring> jkey(env, env->NewStringUTF(key.c_str()));
    env->CallVoidMethod(bundle.get(), gBundleClassInfo.putByteArray, jkey.get(),
                        convertedData.get());
    ScopedLocalRef<jstring> subtype(env, env->NewStringUTF(message.subType.c_str()));
    key = "android.media.tv.TvInputManager.subtype";
    jkey = ScopedLocalRef<jstring>(env, env->NewStringUTF(key.c_str()));
    env->CallVoidMethod(bundle.get(), gBundleClassInfo.putString, jkey.get(), subtype.get());
    key = "android.media.tv.TvInputManager.group_id";
    jkey = ScopedLocalRef<jstring>(env, env->NewStringUTF(key.c_str()));
    env->CallVoidMethod(bundle.get(), gBundleClassInfo.putInt, jkey.get(), message.groupId);
    key = "android.media.tv.TvInputManager.stream_id";
    jkey = ScopedLocalRef<jstring>(env, env->NewStringUTF(key.c_str()));
    env->CallVoidMethod(bundle.get(), gBundleClassInfo.putInt, jkey.get(), streamId);
    env->CallVoidMethod(mThiz, gTvInputHalClassInfo.tvMessageReceived, deviceId,
                        static_cast<jint>(type), bundle.get());
}

void JTvInputHal::onCaptured(int deviceId, int streamId, uint32_t seq, bool succeeded) {
    sp<BufferProducerThread> thread;
    {
        Mutex::Autolock autoLock(&mStreamLock);
        KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
        Connection& connection = connections.editValueFor(streamId);
        if (connection.mThread == NULL) {
            ALOGE("capture thread not existing.");
            return;
        }
        thread = connection.mThread;
    }
    thread->onCaptured(seq, succeeded);
    if (seq == 0) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(mThiz, gTvInputHalClassInfo.firstFrameCaptured, deviceId, streamId);
    }
}

JTvInputHal::TvInputDeviceInfoWrapper
JTvInputHal::TvInputDeviceInfoWrapper::createDeviceInfoWrapper(
        const AidlTvInputDeviceInfo& aidlTvInputDeviceInfo) {
    TvInputDeviceInfoWrapper deviceInfo;
    deviceInfo.isHidl = false;
    deviceInfo.deviceId = aidlTvInputDeviceInfo.deviceId;
    deviceInfo.type = aidlTvInputDeviceInfo.type;
    deviceInfo.portId = aidlTvInputDeviceInfo.portId;
    deviceInfo.cableConnectionStatus = aidlTvInputDeviceInfo.cableConnectionStatus;
    deviceInfo.aidlAudioDevice = aidlTvInputDeviceInfo.audioDevice;
    return deviceInfo;
}

JTvInputHal::TvInputEventWrapper JTvInputHal::TvInputEventWrapper::createEventWrapper(
        const AidlTvInputEvent& aidlTvInputEvent) {
    TvInputEventWrapper event;
    event.type = aidlTvInputEvent.type;
    event.deviceInfo =
            TvInputDeviceInfoWrapper::createDeviceInfoWrapper(aidlTvInputEvent.deviceInfo);
    return event;
}

JTvInputHal::TvMessageEventWrapper JTvInputHal::TvMessageEventWrapper::createEventWrapper(
        const AidlTvMessageEvent& aidlTvMessageEvent, bool isLegacyMessage) {
    auto messageList = aidlTvMessageEvent.messages;
    TvMessageEventWrapper event;
    // Handle backwards compatibility for V1
    if (isLegacyMessage) {
        event.deviceId = messageList[0].groupId;
        event.messages.insert(event.messages.begin(), std::begin(messageList) + 1,
                              std::end(messageList));
    } else {
        event.deviceId = aidlTvMessageEvent.deviceId;
        event.messages.insert(event.messages.begin(), std::begin(messageList),
                              std::end(messageList));
    }
    event.streamId = aidlTvMessageEvent.streamId;
    event.type = aidlTvMessageEvent.type;
    return event;
}

JTvInputHal::NotifyHandler::NotifyHandler(JTvInputHal* hal, const TvInputEventWrapper& event) {
    mHal = hal;
    mEvent = event;
}

void JTvInputHal::NotifyHandler::handleMessage(const Message& message) {
    switch (mEvent.type) {
        case TvInputEventType::DEVICE_AVAILABLE: {
            mHal->onDeviceAvailable(mEvent.deviceInfo);
        } break;
        case TvInputEventType::DEVICE_UNAVAILABLE: {
            mHal->onDeviceUnavailable(mEvent.deviceInfo.deviceId);
        } break;
        case TvInputEventType::STREAM_CONFIGURATIONS_CHANGED: {
            int cableConnectionStatus = static_cast<int>(mEvent.deviceInfo.cableConnectionStatus);
            mHal->onStreamConfigurationsChanged(mEvent.deviceInfo.deviceId, cableConnectionStatus);
        } break;
        default:
            ALOGE("Unrecognizable event");
    }
}

JTvInputHal::NotifyTvMessageHandler::NotifyTvMessageHandler(JTvInputHal* hal,
                                                            const TvMessageEventWrapper& event) {
    mHal = hal;
    mEvent = event;
}

void JTvInputHal::NotifyTvMessageHandler::handleMessage(const Message& message) {
    std::shared_ptr<AidlMessageQueue<int8_t, SynchronizedReadWrite>> queue =
            mHal->mQueueMap[mEvent.deviceId][mEvent.streamId];
    for (AidlTvMessage item : mEvent.messages) {
        if (queue == NULL || !queue->isValid() || queue->availableToRead() < item.dataLengthBytes) {
            MQDescriptor<int8_t, SynchronizedReadWrite> queueDesc;
            if (mHal->mTvInput->getTvMessageQueueDesc(&queueDesc, mEvent.deviceId, mEvent.streamId)
                        .isOk()) {
                queue = std::make_shared<AidlMessageQueue<int8_t, SynchronizedReadWrite>>(queueDesc,
                                                                                          false);
            }
            if (queue == NULL || !queue->isValid() ||
                queue->availableToRead() < item.dataLengthBytes) {
                ALOGE("Incomplete TvMessageQueue data or missing queue");
                return;
            }
            mHal->mQueueMap[mEvent.deviceId][mEvent.streamId] = queue;
        }
        signed char* buffer = new signed char[item.dataLengthBytes];
        if (queue->read(buffer, item.dataLengthBytes)) {
            mHal->onTvMessage(mEvent.deviceId, mEvent.streamId, mEvent.type, item, buffer,
                              item.dataLengthBytes);
        } else {
            ALOGE("Failed to read from TvMessageQueue");
        }
        delete[] buffer;
    }
}

JTvInputHal::TvInputCallbackWrapper::TvInputCallbackWrapper(JTvInputHal* hal) {
    aidlTvInputCallback = ::ndk::SharedRefBase::make<AidlTvInputCallback>(hal);
    hidlTvInputCallback = sp<HidlTvInputCallback>::make(hal);
}

JTvInputHal::AidlTvInputCallback::AidlTvInputCallback(JTvInputHal* hal) {
    mHal = hal;
}

::ndk::ScopedAStatus JTvInputHal::AidlTvInputCallback::notify(const AidlTvInputEvent& event) {
    mHal->mLooper->sendMessage(new NotifyHandler(mHal,
                                                 TvInputEventWrapper::createEventWrapper(event)),
                               static_cast<int>(event.type));
    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus JTvInputHal::AidlTvInputCallback::notifyTvMessageEvent(
        const AidlTvMessageEvent& event) {
    const std::string DEVICE_ID_SUBTYPE = "device_id";
    ::ndk::ScopedAStatus status = ::ndk::ScopedAStatus::ok();
    int32_t aidlVersion = 0;
    if (mHal->mTvInput->getAidlInterfaceVersion(&aidlVersion).isOk() && event.messages.size() > 0) {
        bool validLegacyMessage = aidlVersion == 1 &&
                event.messages[0].subType == DEVICE_ID_SUBTYPE && event.messages.size() > 1;
        bool validTvMessage = aidlVersion > 1 && event.messages.size() > 0;
        if (validLegacyMessage || validTvMessage) {
            mHal->mLooper->sendMessage(
                    new NotifyTvMessageHandler(mHal,
                                               TvMessageEventWrapper::
                                                       createEventWrapper(event,
                                                                          validLegacyMessage)),
                    static_cast<int>(event.type));
        } else {
            status = ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
            ALOGE("The TVMessage event was malformed for HAL version: %d", aidlVersion);
        }
    } else {
        status = ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        ALOGE("The TVMessage event was empty or the HAL version (version: %d) could not "
              "be inferred.",
              aidlVersion);
    }
    return status;
}

JTvInputHal::ITvInputWrapper::ITvInputWrapper(std::shared_ptr<AidlITvInput>& aidlTvInput)
      : mIsHidl(false), mAidlTvInput(aidlTvInput) {}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::setCallback(
        const std::shared_ptr<TvInputCallbackWrapper>& in_callback) {
    if (mIsHidl) {
        in_callback->aidlTvInputCallback = nullptr;
        return hidlSetCallback(in_callback == nullptr ? nullptr : in_callback->hidlTvInputCallback);
    } else {
        in_callback->hidlTvInputCallback = nullptr;
        return mAidlTvInput->setCallback(in_callback == nullptr ? nullptr
                                                                : in_callback->aidlTvInputCallback);
    }
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::getStreamConfigurations(
        int32_t in_deviceId, std::vector<AidlTvStreamConfig>* _aidl_return) {
    if (mIsHidl) {
        return hidlGetStreamConfigurations(in_deviceId, _aidl_return);
    } else {
        return mAidlTvInput->getStreamConfigurations(in_deviceId, _aidl_return);
    }
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::openStream(int32_t in_deviceId,
                                                              int32_t in_streamId,
                                                              AidlNativeHandle* _aidl_return) {
    if (mIsHidl) {
        return hidlOpenStream(in_deviceId, in_streamId, _aidl_return);
    } else {
        return mAidlTvInput->openStream(in_deviceId, in_streamId, _aidl_return);
    }
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::closeStream(int32_t in_deviceId,
                                                               int32_t in_streamId) {
    if (mIsHidl) {
        return hidlCloseStream(in_deviceId, in_streamId);
    } else {
        return mAidlTvInput->closeStream(in_deviceId, in_streamId);
    }
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::setTvMessageEnabled(int32_t deviceId,
                                                                       int32_t streamId,
                                                                       TvMessageEventType in_type,
                                                                       bool enabled) {
    if (mIsHidl) {
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    } else {
        return mAidlTvInput->setTvMessageEnabled(deviceId, streamId, in_type, enabled);
    }
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::getTvMessageQueueDesc(
        MQDescriptor<int8_t, SynchronizedReadWrite>* out_queue, int32_t in_deviceId,
        int32_t in_streamId) {
    if (mIsHidl) {
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    } else {
        return mAidlTvInput->getTvMessageQueueDesc(out_queue, in_deviceId, in_streamId);
    }
}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::getAidlInterfaceVersion(int32_t* _aidl_return) {
    if (mIsHidl) {
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    } else {
        return mAidlTvInput->getInterfaceVersion(_aidl_return);
    }
}

} // namespace android
