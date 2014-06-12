/*
 * Copyright 2014 The Android Open Source Project
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

#define LOG_TAG "TvInputHal"

//#define LOG_NDEBUG 0

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "JNIHelp.h"
#include "jni.h"

#include <gui/Surface.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/Log.h>
#include <utils/NativeHandle.h>
#include <hardware/tv_input.h>

namespace android {

static struct {
    jmethodID deviceAvailable;
    jmethodID deviceUnavailable;
    jmethodID streamConfigsChanged;
} gTvInputHalClassInfo;

static struct {
    jclass clazz;
} gTvStreamConfigClassInfo;

static struct {
    jclass clazz;

    jmethodID constructor;
    jmethodID streamId;
    jmethodID type;
    jmethodID maxWidth;
    jmethodID maxHeight;
    jmethodID generation;
    jmethodID build;
} gTvStreamConfigBuilderClassInfo;

static struct {
    jclass clazz;

    jmethodID constructor;
    jmethodID deviceId;
    jmethodID type;
    jmethodID hdmiPortId;
    jmethodID audioType;
    jmethodID audioAddress;
    jmethodID build;
} gTvInputHardwareInfoBuilderClassInfo;

////////////////////////////////////////////////////////////////////////////////

class JTvInputHal {
public:
    ~JTvInputHal();

    static JTvInputHal* createInstance(JNIEnv* env, jobject thiz);

    int setSurface(int deviceId, int streamId, const sp<Surface>& surface);
    void getStreamConfigs(int deviceId, jobjectArray* array);
    const tv_stream_config_t* getStreamConfigs(int deviceId, int* numConfigs);

private:
    class Connection {
    public:
        Connection() : mStreamId(0) {}

        sp<Surface> mSurface;
        sp<NativeHandle> mSourceHandle;
        int mStreamId;
    };

    JTvInputHal(JNIEnv* env, jobject thiz, tv_input_device_t* dev);

    static void notify(
            tv_input_device_t* dev,tv_input_event_t* event, void* data);

    void onDeviceAvailable(const tv_input_device_info_t& info);
    void onDeviceUnavailable(int deviceId);
    void onStreamConfigurationsChanged(int deviceId);

    jweak mThiz;
    tv_input_device_t* mDevice;
    tv_input_callback_ops_t mCallback;

    KeyedVector<int, Connection> mConnections;
};

JTvInputHal::JTvInputHal(JNIEnv* env, jobject thiz, tv_input_device_t* device) {
    mThiz = env->NewWeakGlobalRef(thiz);
    mDevice = device;
    mCallback.notify = &JTvInputHal::notify;

    mDevice->initialize(mDevice, &mCallback, this);
}

JTvInputHal::~JTvInputHal() {
    mDevice->common.close((hw_device_t*)mDevice);

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mThiz);
    mThiz = NULL;
}

JTvInputHal* JTvInputHal::createInstance(JNIEnv* env, jobject thiz) {
    tv_input_module_t* module = NULL;
    status_t err = hw_get_module(TV_INPUT_HARDWARE_MODULE_ID,
            (hw_module_t const**)&module);
    if (err) {
        ALOGE("Couldn't load %s module (%s)",
                TV_INPUT_HARDWARE_MODULE_ID, strerror(-err));
        return 0;
    }

    tv_input_device_t* device = NULL;
    err = module->common.methods->open(
            (hw_module_t*)module,
            TV_INPUT_DEFAULT_DEVICE,
            (hw_device_t**)&device);
    if (err) {
        ALOGE("Couldn't open %s device (%s)",
                TV_INPUT_DEFAULT_DEVICE, strerror(-err));
        return 0;
    }

    return new JTvInputHal(env, thiz, device);
}

int JTvInputHal::setSurface(int deviceId, int streamId, const sp<Surface>& surface) {
    Connection& connection = mConnections.editValueFor(deviceId);
    if (connection.mStreamId == streamId && connection.mSurface == surface) {
        // Nothing to do
        return NO_ERROR;
    }
    if (Surface::isValid(connection.mSurface)) {
        connection.mSurface.clear();
    }
    if (surface == NULL) {
        if (connection.mSurface != NULL) {
            connection.mSurface->setSidebandStream(NULL);
            connection.mSurface.clear();
        }
        if (connection.mSourceHandle != NULL) {
            // Need to reset streams
            if (mDevice->close_stream(
                    mDevice, deviceId, connection.mStreamId) != 0) {
                ALOGE("Couldn't remove stream");
                return BAD_VALUE;
            }
            connection.mSourceHandle.clear();
        }
        return NO_ERROR;
    }
    connection.mSurface = surface;
    if (connection.mSourceHandle == NULL) {
        // Need to configure stream
        int numConfigs = 0;
        const tv_stream_config_t* configs = NULL;
        if (mDevice->get_stream_configurations(
                mDevice, deviceId, &numConfigs, &configs) != 0) {
            ALOGE("Couldn't get stream configs");
            return UNKNOWN_ERROR;
        }
        int configIndex = -1;
        for (int i = 0; i < numConfigs; ++i) {
            if (configs[i].stream_id == streamId) {
                configIndex = i;
                break;
            }
        }
        if (configIndex == -1) {
            ALOGE("Cannot find a config with given stream ID: %d", streamId);
            return BAD_VALUE;
        }
        // TODO: handle buffer producer profile.
        if (configs[configIndex].type !=
                TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE) {
            ALOGE("Profiles other than independent video source is not yet "
                  "supported : type = %d", configs[configIndex].type);
            return INVALID_OPERATION;
        }
        tv_stream_t stream;
        stream.stream_id = configs[configIndex].stream_id;
        if (mDevice->open_stream(mDevice, deviceId, &stream) != 0) {
            ALOGE("Couldn't add stream");
            return UNKNOWN_ERROR;
        }
        connection.mSourceHandle = NativeHandle::create(
                stream.sideband_stream_source_handle, false);
        connection.mStreamId = stream.stream_id;
        connection.mSurface->setSidebandStream(connection.mSourceHandle);
    }
    return NO_ERROR;
}

const tv_stream_config_t* JTvInputHal::getStreamConfigs(int deviceId, int* numConfigs) {
    const tv_stream_config_t* configs = NULL;
    if (mDevice->get_stream_configurations(
            mDevice, deviceId, numConfigs, &configs) != 0) {
        ALOGE("Couldn't get stream configs");
        return NULL;
    }
    return configs;
}

// static
void JTvInputHal::notify(
        tv_input_device_t* dev, tv_input_event_t* event, void* data) {
    JTvInputHal* thiz = (JTvInputHal*)data;
    switch (event->type) {
        case TV_INPUT_EVENT_DEVICE_AVAILABLE: {
            thiz->onDeviceAvailable(event->device_info);
        } break;
        case TV_INPUT_EVENT_DEVICE_UNAVAILABLE: {
            thiz->onDeviceUnavailable(event->device_info.device_id);
        } break;
        case TV_INPUT_EVENT_STREAM_CONFIGURATIONS_CHANGED: {
            thiz->onStreamConfigurationsChanged(event->device_info.device_id);
        } break;
        default:
            ALOGE("Unrecognizable event");
    }
}

void JTvInputHal::onDeviceAvailable(const tv_input_device_info_t& info) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    mConnections.add(info.device_id, Connection());

    jobject builder = env->NewObject(
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            gTvInputHardwareInfoBuilderClassInfo.constructor);
    env->CallObjectMethod(
            builder, gTvInputHardwareInfoBuilderClassInfo.deviceId, info.device_id);
    env->CallObjectMethod(
            builder, gTvInputHardwareInfoBuilderClassInfo.type, info.type);
    if (info.type == TV_INPUT_TYPE_HDMI) {
        env->CallObjectMethod(
                builder, gTvInputHardwareInfoBuilderClassInfo.hdmiPortId, info.hdmi.port_id);
    }
    env->CallObjectMethod(
            builder, gTvInputHardwareInfoBuilderClassInfo.audioType, info.audio_type);
    if (info.audio_type != AUDIO_DEVICE_NONE) {
        jstring audioAddress = env->NewStringUTF(info.audio_address);
        env->CallObjectMethod(
                builder, gTvInputHardwareInfoBuilderClassInfo.audioAddress, audioAddress);
        env->DeleteLocalRef(audioAddress);
    }

    jobject infoObject = env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.build);

    env->CallVoidMethod(
            mThiz,
            gTvInputHalClassInfo.deviceAvailable,
            infoObject);

    env->DeleteLocalRef(builder);
    env->DeleteLocalRef(infoObject);
}

void JTvInputHal::onDeviceUnavailable(int deviceId) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    mConnections.removeItem(deviceId);
    env->CallVoidMethod(
            mThiz,
            gTvInputHalClassInfo.deviceUnavailable,
            deviceId);
}

void JTvInputHal::onStreamConfigurationsChanged(int deviceId) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    mConnections.removeItem(deviceId);
    env->CallVoidMethod(
            mThiz,
            gTvInputHalClassInfo.streamConfigsChanged,
            deviceId);
}

////////////////////////////////////////////////////////////////////////////////

static jlong nativeOpen(JNIEnv* env, jobject thiz) {
    return (jlong)JTvInputHal::createInstance(env, thiz);
}

static int nativeSetSurface(JNIEnv* env, jclass clazz,
        jlong ptr, jint deviceId, jint streamId, jobject jsurface) {
    JTvInputHal* tvInputHal = (JTvInputHal*)ptr;
    sp<Surface> surface(
            jsurface
            ? android_view_Surface_getSurface(env, jsurface)
            : NULL);
    return tvInputHal->setSurface(deviceId, streamId, surface);
}

static jobjectArray nativeGetStreamConfigs(JNIEnv* env, jclass clazz,
        jlong ptr, jint deviceId, jint generation) {
    JTvInputHal* tvInputHal = (JTvInputHal*)ptr;
    int numConfigs = 0;
    const tv_stream_config_t* configs = tvInputHal->getStreamConfigs(deviceId, &numConfigs);

    jobjectArray result = env->NewObjectArray(numConfigs, gTvStreamConfigClassInfo.clazz, NULL);
    for (int i = 0; i < numConfigs; ++i) {
        jobject builder = env->NewObject(
                gTvStreamConfigBuilderClassInfo.clazz,
                gTvStreamConfigBuilderClassInfo.constructor);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.streamId, configs[i].stream_id);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.type, configs[i].type);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.maxWidth, configs[i].max_video_width);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.maxHeight, configs[i].max_video_height);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.generation, generation);

        jobject config = env->CallObjectMethod(builder, gTvStreamConfigBuilderClassInfo.build);

        env->SetObjectArrayElement(result, i, config);

        env->DeleteLocalRef(config);
        env->DeleteLocalRef(builder);
    }
    return result;
}

static void nativeClose(JNIEnv* env, jclass clazz, jlong ptr) {
    JTvInputHal* tvInputHal = (JTvInputHal*)ptr;
    delete tvInputHal;
}

static JNINativeMethod gTvInputHalMethods[] = {
    /* name, signature, funcPtr */
    { "nativeOpen", "()J",
            (void*) nativeOpen },
    { "nativeSetSurface", "(JIILandroid/view/Surface;)I",
            (void*) nativeSetSurface },
    { "nativeGetStreamConfigs", "(JII)[Landroid/media/tv/TvStreamConfig;",
            (void*) nativeGetStreamConfigs },
    { "nativeClose", "(J)V",
            (void*) nativeClose },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className)

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName)

int register_android_server_tv_TvInputHal(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/tv/TvInputHal",
            gTvInputHalMethods, NELEM(gTvInputHalMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/tv/TvInputHal");

    GET_METHOD_ID(
            gTvInputHalClassInfo.deviceAvailable, clazz,
            "deviceAvailableFromNative", "(Landroid/media/tv/TvInputHardwareInfo;)V");
    GET_METHOD_ID(
            gTvInputHalClassInfo.deviceUnavailable, clazz, "deviceUnavailableFromNative", "(I)V");
    GET_METHOD_ID(
            gTvInputHalClassInfo.streamConfigsChanged, clazz,
            "streamConfigsChangedFromNative", "(I)V");

    FIND_CLASS(gTvStreamConfigClassInfo.clazz, "android/media/tv/TvStreamConfig");
    gTvStreamConfigClassInfo.clazz = jclass(env->NewGlobalRef(gTvStreamConfigClassInfo.clazz));

    FIND_CLASS(gTvStreamConfigBuilderClassInfo.clazz, "android/media/tv/TvStreamConfig$Builder");
    gTvStreamConfigBuilderClassInfo.clazz =
            jclass(env->NewGlobalRef(gTvStreamConfigBuilderClassInfo.clazz));

    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.constructor,
            gTvStreamConfigBuilderClassInfo.clazz,
            "<init>", "()V");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.streamId,
            gTvStreamConfigBuilderClassInfo.clazz,
            "streamId", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.type,
            gTvStreamConfigBuilderClassInfo.clazz,
            "type", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.maxWidth,
            gTvStreamConfigBuilderClassInfo.clazz,
            "maxWidth", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.maxHeight,
            gTvStreamConfigBuilderClassInfo.clazz,
            "maxHeight", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.generation,
            gTvStreamConfigBuilderClassInfo.clazz,
            "generation", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.build,
            gTvStreamConfigBuilderClassInfo.clazz,
            "build", "()Landroid/media/tv/TvStreamConfig;");

    FIND_CLASS(gTvInputHardwareInfoBuilderClassInfo.clazz,
            "android/media/tv/TvInputHardwareInfo$Builder");
    gTvInputHardwareInfoBuilderClassInfo.clazz =
            jclass(env->NewGlobalRef(gTvInputHardwareInfoBuilderClassInfo.clazz));

    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.constructor,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "<init>", "()V");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.deviceId,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "deviceId", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.type,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "type", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.hdmiPortId,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "hdmiPortId", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.audioType,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "audioType", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.audioAddress,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "audioAddress", "(Ljava/lang/String;)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.build,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "build", "()Landroid/media/tv/TvInputHardwareInfo;");

    return 0;
}

} /* namespace android */
