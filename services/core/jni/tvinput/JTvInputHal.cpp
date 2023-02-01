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

namespace android {

JTvInputHal::JTvInputHal(JNIEnv* env, jobject thiz, std::shared_ptr<ITvInputWrapper> tvInput,
                         const sp<Looper>& looper) {
    mThiz = env->NewWeakGlobalRef(thiz);
    mTvInput = tvInput;
    mLooper = looper;
    mTvInputCallback = ::ndk::SharedRefBase::make<TvInputCallback>(this);
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
        connection.mSourceHandle = NativeHandle::create(makeFromAidl(sidebandStream), true);
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

void JTvInputHal::onDeviceAvailable(const TvInputDeviceInfoWrapper& info) {
    {
        Mutex::Autolock autoLock(&mLock);
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
        AidlAudioDeviceType audioType = info.aidlAudioDevice.type.type;
        env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.audioType, audioType);
        if (audioType != AidlAudioDeviceType::NONE) {
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
        Mutex::Autolock autoLock(&mLock);
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
        Mutex::Autolock autoLock(&mLock);
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

void JTvInputHal::onCaptured(int deviceId, int streamId, uint32_t seq, bool succeeded) {
    sp<BufferProducerThread> thread;
    {
        Mutex::Autolock autoLock(&mLock);
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

JTvInputHal::TvInputCallback::TvInputCallback(JTvInputHal* hal) {
    mHal = hal;
}

::ndk::ScopedAStatus JTvInputHal::TvInputCallback::notify(const AidlTvInputEvent& event) {
    mHal->mLooper->sendMessage(new NotifyHandler(mHal,
                                                 TvInputEventWrapper::createEventWrapper(event)),
                               static_cast<int>(event.type));
    return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus JTvInputHal::TvInputCallback::notifyTvMessageEvent(
        const AidlTvMessageEvent& event) {
    // TODO: Implement this
    return ::ndk::ScopedAStatus::ok();
}

JTvInputHal::ITvInputWrapper::ITvInputWrapper(std::shared_ptr<AidlITvInput>& aidlTvInput)
      : mIsHidl(false), mAidlTvInput(aidlTvInput) {}

::ndk::ScopedAStatus JTvInputHal::ITvInputWrapper::setCallback(
        const std::shared_ptr<TvInputCallback>& in_callback) {
    if (mIsHidl) {
        return hidlSetCallback(in_callback);
    } else {
        return mAidlTvInput->setCallback(in_callback);
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

} // namespace android
