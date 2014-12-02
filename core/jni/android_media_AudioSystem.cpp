/*
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

//#define LOG_NDEBUG 0

#define LOG_TAG "AudioSystem-JNI"
#include <utils/Log.h>

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <media/AudioSystem.h>
#include <media/AudioPolicy.h>

#include <system/audio.h>
#include <system/audio_policy.h>
#include "android_media_AudioFormat.h"
#include "android_media_AudioErrors.h"

// ----------------------------------------------------------------------------

using namespace android;

static const char* const kClassPathName = "android/media/AudioSystem";

static jclass gArrayListClass;
static struct {
    jmethodID    add;
    jmethodID    toArray;
} gArrayListMethods;

static jclass gAudioHandleClass;
static jmethodID gAudioHandleCstor;
static struct {
    jfieldID    mId;
} gAudioHandleFields;

static jclass gAudioPortClass;
static jmethodID gAudioPortCstor;
static struct {
    jfieldID    mHandle;
    jfieldID    mRole;
    jfieldID    mGains;
    jfieldID    mActiveConfig;
    // other fields unused by JNI
} gAudioPortFields;

static jclass gAudioPortConfigClass;
static jmethodID gAudioPortConfigCstor;
static struct {
    jfieldID    mPort;
    jfieldID    mSamplingRate;
    jfieldID    mChannelMask;
    jfieldID    mFormat;
    jfieldID    mGain;
    jfieldID    mConfigMask;
} gAudioPortConfigFields;

static jclass gAudioDevicePortClass;
static jmethodID gAudioDevicePortCstor;

static jclass gAudioDevicePortConfigClass;
static jmethodID gAudioDevicePortConfigCstor;

static jclass gAudioMixPortClass;
static jmethodID gAudioMixPortCstor;

static jclass gAudioMixPortConfigClass;
static jmethodID gAudioMixPortConfigCstor;

static jclass gAudioGainClass;
static jmethodID gAudioGainCstor;

static jclass gAudioGainConfigClass;
static jmethodID gAudioGainConfigCstor;
static struct {
    jfieldID mIndex;
    jfieldID mMode;
    jfieldID mChannelMask;
    jfieldID mValues;
    jfieldID mRampDurationMs;
    // other fields unused by JNI
} gAudioGainConfigFields;

static jclass gAudioPatchClass;
static jmethodID gAudioPatchCstor;
static struct {
    jfieldID    mHandle;
    // other fields unused by JNI
} gAudioPatchFields;

static jclass gAudioMixClass;
static struct {
    jfieldID    mRule;
    jfieldID    mFormat;
    jfieldID    mRouteFlags;
    jfieldID    mRegistrationId;
    jfieldID    mMixType;
} gAudioMixFields;

static jclass gAudioFormatClass;
static struct {
    jfieldID    mEncoding;
    jfieldID    mSampleRate;
    jfieldID    mChannelMask;
    // other fields unused by JNI
} gAudioFormatFields;

static jclass gAudioMixingRuleClass;
static struct {
    jfieldID    mCriteria;
    // other fields unused by JNI
} gAudioMixingRuleFields;

static jclass gAttributeMatchCriterionClass;
static struct {
    jfieldID    mAttr;
    jfieldID    mRule;
} gAttributeMatchCriterionFields;

static jclass gAudioAttributesClass;
static struct {
    jfieldID    mUsage;
    jfieldID    mSource;
} gAudioAttributesFields;


static const char* const kEventHandlerClassPathName =
        "android/media/AudioPortEventHandler";
static jmethodID gPostEventFromNative;

enum AudioError {
    kAudioStatusOk = 0,
    kAudioStatusError = 1,
    kAudioStatusMediaServerDied = 100
};

enum  {
    AUDIOPORT_EVENT_PORT_LIST_UPDATED = 1,
    AUDIOPORT_EVENT_PATCH_LIST_UPDATED = 2,
    AUDIOPORT_EVENT_SERVICE_DIED = 3,
};

#define MAX_PORT_GENERATION_SYNC_ATTEMPTS 5

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class JNIAudioPortCallback: public AudioSystem::AudioPortCallback
{
public:
    JNIAudioPortCallback(JNIEnv* env, jobject thiz, jobject weak_thiz);
    ~JNIAudioPortCallback();

    virtual void onAudioPortListUpdate();
    virtual void onAudioPatchListUpdate();
    virtual void onServiceDied();

private:
    void sendEvent(int event);

    jclass      mClass;     // Reference to AudioPortEventHandlerDelegate class
    jobject     mObject;    // Weak ref to AudioPortEventHandlerDelegate Java object to call on
};

JNIAudioPortCallback::JNIAudioPortCallback(JNIEnv* env, jobject thiz, jobject weak_thiz)
{

    // Hold onto the SoundTriggerModule class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find class %s", kEventHandlerClassPathName);
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the SoundTriggerModule object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject  = env->NewGlobalRef(weak_thiz);
}

JNIAudioPortCallback::~JNIAudioPortCallback()
{
    // remove global references
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNIAudioPortCallback::sendEvent(int event)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }
    env->CallStaticVoidMethod(mClass, gPostEventFromNative, mObject,
                              event, 0, 0, NULL);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}

void JNIAudioPortCallback::onAudioPortListUpdate()
{
    sendEvent(AUDIOPORT_EVENT_PORT_LIST_UPDATED);
}

void JNIAudioPortCallback::onAudioPatchListUpdate()
{
    sendEvent(AUDIOPORT_EVENT_PATCH_LIST_UPDATED);
}

void JNIAudioPortCallback::onServiceDied()
{
    sendEvent(AUDIOPORT_EVENT_SERVICE_DIED);
}

static int check_AudioSystem_Command(status_t status)
{
    switch (status) {
    case DEAD_OBJECT:
        return kAudioStatusMediaServerDied;
    case NO_ERROR:
        return kAudioStatusOk;
    default:
        break;
    }
    return kAudioStatusError;
}

static jint
android_media_AudioSystem_muteMicrophone(JNIEnv *env, jobject thiz, jboolean on)
{
    return (jint) check_AudioSystem_Command(AudioSystem::muteMicrophone(on));
}

static jboolean
android_media_AudioSystem_isMicrophoneMuted(JNIEnv *env, jobject thiz)
{
    bool state = false;
    AudioSystem::isMicrophoneMuted(&state);
    return state;
}

static jboolean
android_media_AudioSystem_isStreamActive(JNIEnv *env, jobject thiz, jint stream, jint inPastMs)
{
    bool state = false;
    AudioSystem::isStreamActive((audio_stream_type_t) stream, &state, inPastMs);
    return state;
}

static jboolean
android_media_AudioSystem_isStreamActiveRemotely(JNIEnv *env, jobject thiz, jint stream,
        jint inPastMs)
{
    bool state = false;
    AudioSystem::isStreamActiveRemotely((audio_stream_type_t) stream, &state, inPastMs);
    return state;
}

static jboolean
android_media_AudioSystem_isSourceActive(JNIEnv *env, jobject thiz, jint source)
{
    bool state = false;
    AudioSystem::isSourceActive((audio_source_t) source, &state);
    return state;
}

static jint
android_media_AudioSystem_newAudioSessionId(JNIEnv *env, jobject thiz)
{
    return AudioSystem::newAudioUniqueId();
}

static jint
android_media_AudioSystem_setParameters(JNIEnv *env, jobject thiz, jstring keyValuePairs)
{
    const jchar* c_keyValuePairs = env->GetStringCritical(keyValuePairs, 0);
    String8 c_keyValuePairs8;
    if (keyValuePairs) {
        c_keyValuePairs8 = String8(c_keyValuePairs, env->GetStringLength(keyValuePairs));
        env->ReleaseStringCritical(keyValuePairs, c_keyValuePairs);
    }
    int status = check_AudioSystem_Command(AudioSystem::setParameters(c_keyValuePairs8));
    return (jint) status;
}

static jstring
android_media_AudioSystem_getParameters(JNIEnv *env, jobject thiz, jstring keys)
{
    const jchar* c_keys = env->GetStringCritical(keys, 0);
    String8 c_keys8;
    if (keys) {
        c_keys8 = String8(c_keys, env->GetStringLength(keys));
        env->ReleaseStringCritical(keys, c_keys);
    }
    return env->NewStringUTF(AudioSystem::getParameters(c_keys8).string());
}

static void
android_media_AudioSystem_error_callback(status_t err)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }

    jclass clazz = env->FindClass(kClassPathName);

    env->CallStaticVoidMethod(clazz, env->GetStaticMethodID(clazz,
                              "errorCallbackFromNative","(I)V"),
                              check_AudioSystem_Command(err));

    env->DeleteLocalRef(clazz);
}

static jint
android_media_AudioSystem_setDeviceConnectionState(JNIEnv *env, jobject thiz, jint device, jint state, jstring device_address)
{
    const char *c_address = env->GetStringUTFChars(device_address, NULL);
    int status = check_AudioSystem_Command(AudioSystem::setDeviceConnectionState(static_cast <audio_devices_t>(device),
                                          static_cast <audio_policy_dev_state_t>(state),
                                          c_address));
    env->ReleaseStringUTFChars(device_address, c_address);
    return (jint) status;
}

static jint
android_media_AudioSystem_getDeviceConnectionState(JNIEnv *env, jobject thiz, jint device, jstring device_address)
{
    const char *c_address = env->GetStringUTFChars(device_address, NULL);
    int state = static_cast <int>(AudioSystem::getDeviceConnectionState(static_cast <audio_devices_t>(device),
                                          c_address));
    env->ReleaseStringUTFChars(device_address, c_address);
    return (jint) state;
}

static jint
android_media_AudioSystem_setPhoneState(JNIEnv *env, jobject thiz, jint state)
{
    return (jint) check_AudioSystem_Command(AudioSystem::setPhoneState((audio_mode_t) state));
}

static jint
android_media_AudioSystem_setForceUse(JNIEnv *env, jobject thiz, jint usage, jint config)
{
    return (jint) check_AudioSystem_Command(AudioSystem::setForceUse(static_cast <audio_policy_force_use_t>(usage),
                                                           static_cast <audio_policy_forced_cfg_t>(config)));
}

static jint
android_media_AudioSystem_getForceUse(JNIEnv *env, jobject thiz, jint usage)
{
    return static_cast <jint>(AudioSystem::getForceUse(static_cast <audio_policy_force_use_t>(usage)));
}

static jint
android_media_AudioSystem_initStreamVolume(JNIEnv *env, jobject thiz, jint stream, jint indexMin, jint indexMax)
{
    return (jint) check_AudioSystem_Command(AudioSystem::initStreamVolume(static_cast <audio_stream_type_t>(stream),
                                                                   indexMin,
                                                                   indexMax));
}

static jint
android_media_AudioSystem_setStreamVolumeIndex(JNIEnv *env,
                                               jobject thiz,
                                               jint stream,
                                               jint index,
                                               jint device)
{
    return (jint) check_AudioSystem_Command(
            AudioSystem::setStreamVolumeIndex(static_cast <audio_stream_type_t>(stream),
                                              index,
                                              (audio_devices_t)device));
}

static jint
android_media_AudioSystem_getStreamVolumeIndex(JNIEnv *env,
                                               jobject thiz,
                                               jint stream,
                                               jint device)
{
    int index;
    if (AudioSystem::getStreamVolumeIndex(static_cast <audio_stream_type_t>(stream),
                                          &index,
                                          (audio_devices_t)device)
            != NO_ERROR) {
        index = -1;
    }
    return (jint) index;
}

static jint
android_media_AudioSystem_setMasterVolume(JNIEnv *env, jobject thiz, jfloat value)
{
    return (jint) check_AudioSystem_Command(AudioSystem::setMasterVolume(value));
}

static jfloat
android_media_AudioSystem_getMasterVolume(JNIEnv *env, jobject thiz)
{
    float value;
    if (AudioSystem::getMasterVolume(&value) != NO_ERROR) {
        value = -1.0;
    }
    return value;
}

static jint
android_media_AudioSystem_setMasterMute(JNIEnv *env, jobject thiz, jboolean mute)
{
    return (jint) check_AudioSystem_Command(AudioSystem::setMasterMute(mute));
}

static jboolean
android_media_AudioSystem_getMasterMute(JNIEnv *env, jobject thiz)
{
    bool mute;
    if (AudioSystem::getMasterMute(&mute) != NO_ERROR) {
        mute = false;
    }
    return mute;
}

static jint
android_media_AudioSystem_getDevicesForStream(JNIEnv *env, jobject thiz, jint stream)
{
    return (jint) AudioSystem::getDevicesForStream(static_cast <audio_stream_type_t>(stream));
}

static jint
android_media_AudioSystem_getPrimaryOutputSamplingRate(JNIEnv *env, jobject clazz)
{
    return (jint) AudioSystem::getPrimaryOutputSamplingRate();
}

static jint
android_media_AudioSystem_getPrimaryOutputFrameCount(JNIEnv *env, jobject clazz)
{
    return (jint) AudioSystem::getPrimaryOutputFrameCount();
}

static jint
android_media_AudioSystem_getOutputLatency(JNIEnv *env, jobject clazz, jint stream)
{
    uint32_t afLatency;
    if (AudioSystem::getOutputLatency(&afLatency, static_cast <audio_stream_type_t>(stream))
            != NO_ERROR) {
        afLatency = -1;
    }
    return (jint) afLatency;
}

static jint
android_media_AudioSystem_setLowRamDevice(JNIEnv *env, jobject clazz, jboolean isLowRamDevice)
{
    return (jint) AudioSystem::setLowRamDevice((bool) isLowRamDevice);
}

static jint
android_media_AudioSystem_checkAudioFlinger(JNIEnv *env, jobject clazz)
{
    return (jint) check_AudioSystem_Command(AudioSystem::checkAudioFlinger());
}


static bool useInChannelMask(audio_port_type_t type, audio_port_role_t role)
{
    return ((type == AUDIO_PORT_TYPE_DEVICE) && (role == AUDIO_PORT_ROLE_SOURCE)) ||
                ((type == AUDIO_PORT_TYPE_MIX) && (role == AUDIO_PORT_ROLE_SINK));
}

static void convertAudioGainConfigToNative(JNIEnv *env,
                                               struct audio_gain_config *nAudioGainConfig,
                                               const jobject jAudioGainConfig,
                                               bool useInMask)
{
    nAudioGainConfig->index = env->GetIntField(jAudioGainConfig, gAudioGainConfigFields.mIndex);
    nAudioGainConfig->mode = env->GetIntField(jAudioGainConfig, gAudioGainConfigFields.mMode);
    ALOGV("convertAudioGainConfigToNative got gain index %d", nAudioGainConfig->index);
    jint jMask = env->GetIntField(jAudioGainConfig, gAudioGainConfigFields.mChannelMask);
    audio_channel_mask_t nMask;
    if (useInMask) {
        nMask = inChannelMaskToNative(jMask);
        ALOGV("convertAudioGainConfigToNative IN mask java %x native %x", jMask, nMask);
    } else {
        nMask = outChannelMaskToNative(jMask);
        ALOGV("convertAudioGainConfigToNative OUT mask java %x native %x", jMask, nMask);
    }
    nAudioGainConfig->channel_mask = nMask;
    nAudioGainConfig->ramp_duration_ms = env->GetIntField(jAudioGainConfig,
                                                       gAudioGainConfigFields.mRampDurationMs);
    jintArray jValues = (jintArray)env->GetObjectField(jAudioGainConfig,
                                                       gAudioGainConfigFields.mValues);
    int *nValues = env->GetIntArrayElements(jValues, NULL);
    size_t size = env->GetArrayLength(jValues);
    memcpy(nAudioGainConfig->values, nValues, size * sizeof(int));
    env->DeleteLocalRef(jValues);
}


static jint convertAudioPortConfigToNative(JNIEnv *env,
                                               struct audio_port_config *nAudioPortConfig,
                                               const jobject jAudioPortConfig,
                                               bool useConfigMask)
{
    jobject jAudioPort = env->GetObjectField(jAudioPortConfig, gAudioPortConfigFields.mPort);
    jobject jHandle = env->GetObjectField(jAudioPort, gAudioPortFields.mHandle);
    nAudioPortConfig->id = env->GetIntField(jHandle, gAudioHandleFields.mId);
    nAudioPortConfig->role = (audio_port_role_t)env->GetIntField(jAudioPort,
                                                                 gAudioPortFields.mRole);
    if (env->IsInstanceOf(jAudioPort, gAudioDevicePortClass)) {
        nAudioPortConfig->type = AUDIO_PORT_TYPE_DEVICE;
    } else if (env->IsInstanceOf(jAudioPort, gAudioMixPortClass)) {
        nAudioPortConfig->type = AUDIO_PORT_TYPE_MIX;
    } else {
        env->DeleteLocalRef(jAudioPort);
        env->DeleteLocalRef(jHandle);
        return (jint)AUDIO_JAVA_ERROR;
    }
    ALOGV("convertAudioPortConfigToNative handle %d role %d type %d",
          nAudioPortConfig->id, nAudioPortConfig->role, nAudioPortConfig->type);

    unsigned int configMask = 0;

    nAudioPortConfig->sample_rate = env->GetIntField(jAudioPortConfig,
                                                     gAudioPortConfigFields.mSamplingRate);
    if (nAudioPortConfig->sample_rate != 0) {
        configMask |= AUDIO_PORT_CONFIG_SAMPLE_RATE;
    }

    bool useInMask = useInChannelMask(nAudioPortConfig->type, nAudioPortConfig->role);
    audio_channel_mask_t nMask;
    jint jMask = env->GetIntField(jAudioPortConfig,
                                   gAudioPortConfigFields.mChannelMask);
    if (useInMask) {
        nMask = inChannelMaskToNative(jMask);
        ALOGV("convertAudioPortConfigToNative IN mask java %x native %x", jMask, nMask);
    } else {
        nMask = outChannelMaskToNative(jMask);
        ALOGV("convertAudioPortConfigToNative OUT mask java %x native %x", jMask, nMask);
    }
    nAudioPortConfig->channel_mask = nMask;
    if (nAudioPortConfig->channel_mask != AUDIO_CHANNEL_NONE) {
        configMask |= AUDIO_PORT_CONFIG_CHANNEL_MASK;
    }

    jint jFormat = env->GetIntField(jAudioPortConfig, gAudioPortConfigFields.mFormat);
    audio_format_t nFormat = audioFormatToNative(jFormat);
    ALOGV("convertAudioPortConfigToNative format %d native %d", jFormat, nFormat);
    nAudioPortConfig->format = nFormat;
    if (nAudioPortConfig->format != AUDIO_FORMAT_DEFAULT &&
            nAudioPortConfig->format != AUDIO_FORMAT_INVALID) {
        configMask |= AUDIO_PORT_CONFIG_FORMAT;
    }

    jobject jGain = env->GetObjectField(jAudioPortConfig, gAudioPortConfigFields.mGain);
    if (jGain != NULL) {
        convertAudioGainConfigToNative(env, &nAudioPortConfig->gain, jGain, useInMask);
        env->DeleteLocalRef(jGain);
        configMask |= AUDIO_PORT_CONFIG_GAIN;
    } else {
        ALOGV("convertAudioPortConfigToNative no gain");
        nAudioPortConfig->gain.index = -1;
    }
    if (useConfigMask) {
        nAudioPortConfig->config_mask = env->GetIntField(jAudioPortConfig,
                                                         gAudioPortConfigFields.mConfigMask);
    } else {
        nAudioPortConfig->config_mask = configMask;
    }
    env->DeleteLocalRef(jAudioPort);
    env->DeleteLocalRef(jHandle);
    return (jint)AUDIO_JAVA_SUCCESS;
}

static jint convertAudioPortConfigFromNative(JNIEnv *env,
                                                 jobject jAudioPort,
                                                 jobject *jAudioPortConfig,
                                                 const struct audio_port_config *nAudioPortConfig)
{
    jint jStatus = AUDIO_JAVA_SUCCESS;
    jobject jAudioGainConfig = NULL;
    jobject jAudioGain = NULL;
    jintArray jGainValues;
    bool audioportCreated = false;

    ALOGV("convertAudioPortConfigFromNative jAudioPort %p", jAudioPort);

    if (jAudioPort == NULL) {
        jobject jHandle = env->NewObject(gAudioHandleClass, gAudioHandleCstor,
                                                 nAudioPortConfig->id);

        ALOGV("convertAudioPortConfigFromNative handle %d is a %s", nAudioPortConfig->id,
              nAudioPortConfig->type == AUDIO_PORT_TYPE_DEVICE ? "device" : "mix");

        if (jHandle == NULL) {
            return (jint)AUDIO_JAVA_ERROR;
        }
        // create dummy port and port config objects with just the correct handle
        // and configuration data. The actual AudioPortConfig objects will be
        // constructed by java code with correct class type (device, mix etc...)
        // and reference to AudioPort instance in this client
        jAudioPort = env->NewObject(gAudioPortClass, gAudioPortCstor,
                                           jHandle,
                                           0,
                                           NULL,
                                           NULL,
                                           NULL,
                                           NULL);
        env->DeleteLocalRef(jHandle);
        if (jAudioPort == NULL) {
            return (jint)AUDIO_JAVA_ERROR;
        }
        ALOGV("convertAudioPortConfigFromNative jAudioPort created for handle %d",
              nAudioPortConfig->id);

        audioportCreated = true;
    }

    bool useInMask = useInChannelMask(nAudioPortConfig->type, nAudioPortConfig->role);

    audio_channel_mask_t nMask;
    jint jMask;

    int gainIndex = nAudioPortConfig->gain.index;
    if (gainIndex >= 0) {
        ALOGV("convertAudioPortConfigFromNative gain found with index %d mode %x",
              gainIndex, nAudioPortConfig->gain.mode);
        if (audioportCreated) {
            ALOGV("convertAudioPortConfigFromNative creating gain");
            jAudioGain = env->NewObject(gAudioGainClass, gAudioGainCstor,
                                               gainIndex,
                                               0,
                                               0,
                                               0,
                                               0,
                                               0,
                                               0,
                                               0,
                                               0);
            if (jAudioGain == NULL) {
                ALOGV("convertAudioPortConfigFromNative creating gain FAILED");
                jStatus = (jint)AUDIO_JAVA_ERROR;
                goto exit;
            }
        } else {
            ALOGV("convertAudioPortConfigFromNative reading gain from port");
            jobjectArray jGains = (jobjectArray)env->GetObjectField(jAudioPort,
                                                                      gAudioPortFields.mGains);
            if (jGains == NULL) {
                ALOGV("convertAudioPortConfigFromNative could not get gains from port");
                jStatus = (jint)AUDIO_JAVA_ERROR;
                goto exit;
            }
            jAudioGain = env->GetObjectArrayElement(jGains, gainIndex);
            env->DeleteLocalRef(jGains);
            if (jAudioGain == NULL) {
                ALOGV("convertAudioPortConfigFromNative could not get gain at index %d", gainIndex);
                jStatus = (jint)AUDIO_JAVA_ERROR;
                goto exit;
            }
        }
        int numValues;
        if (useInMask) {
            numValues = audio_channel_count_from_in_mask(nAudioPortConfig->gain.channel_mask);
        } else {
            numValues = audio_channel_count_from_out_mask(nAudioPortConfig->gain.channel_mask);
        }
        jGainValues = env->NewIntArray(numValues);
        if (jGainValues == NULL) {
            ALOGV("convertAudioPortConfigFromNative could not create gain values %d", numValues);
            jStatus = (jint)AUDIO_JAVA_ERROR;
            goto exit;
        }
        env->SetIntArrayRegion(jGainValues, 0, numValues,
                               nAudioPortConfig->gain.values);

        nMask = nAudioPortConfig->gain.channel_mask;
        if (useInMask) {
            jMask = inChannelMaskFromNative(nMask);
            ALOGV("convertAudioPortConfigFromNative IN mask java %x native %x", jMask, nMask);
        } else {
            jMask = outChannelMaskFromNative(nMask);
            ALOGV("convertAudioPortConfigFromNative OUT mask java %x native %x", jMask, nMask);
        }

        jAudioGainConfig = env->NewObject(gAudioGainConfigClass,
                                        gAudioGainConfigCstor,
                                        gainIndex,
                                        jAudioGain,
                                        nAudioPortConfig->gain.mode,
                                        jMask,
                                        jGainValues,
                                        nAudioPortConfig->gain.ramp_duration_ms);
        env->DeleteLocalRef(jGainValues);
        if (jAudioGainConfig == NULL) {
            ALOGV("convertAudioPortConfigFromNative could not create gain config");
            jStatus = (jint)AUDIO_JAVA_ERROR;
            goto exit;
        }
    }
    jclass clazz;
    jmethodID methodID;
    if (audioportCreated) {
        clazz = gAudioPortConfigClass;
        methodID = gAudioPortConfigCstor;
        ALOGV("convertAudioPortConfigFromNative building a generic port config");
    } else {
        if (env->IsInstanceOf(jAudioPort, gAudioDevicePortClass)) {
            clazz = gAudioDevicePortConfigClass;
            methodID = gAudioDevicePortConfigCstor;
            ALOGV("convertAudioPortConfigFromNative building a device config");
        } else if (env->IsInstanceOf(jAudioPort, gAudioMixPortClass)) {
            clazz = gAudioMixPortConfigClass;
            methodID = gAudioMixPortConfigCstor;
            ALOGV("convertAudioPortConfigFromNative building a mix config");
        } else {
            jStatus = (jint)AUDIO_JAVA_ERROR;
            goto exit;
        }
    }
    nMask = nAudioPortConfig->channel_mask;
    if (useInMask) {
        jMask = inChannelMaskFromNative(nMask);
        ALOGV("convertAudioPortConfigFromNative IN mask java %x native %x", jMask, nMask);
    } else {
        jMask = outChannelMaskFromNative(nMask);
        ALOGV("convertAudioPortConfigFromNative OUT mask java %x native %x", jMask, nMask);
    }

    *jAudioPortConfig = env->NewObject(clazz, methodID,
                                       jAudioPort,
                                       nAudioPortConfig->sample_rate,
                                       jMask,
                                       audioFormatFromNative(nAudioPortConfig->format),
                                       jAudioGainConfig);
    if (*jAudioPortConfig == NULL) {
        ALOGV("convertAudioPortConfigFromNative could not create new port config");
        jStatus = (jint)AUDIO_JAVA_ERROR;
    } else {
        ALOGV("convertAudioPortConfigFromNative OK");
    }

exit:
    if (audioportCreated) {
        env->DeleteLocalRef(jAudioPort);
        if (jAudioGain != NULL) {
            env->DeleteLocalRef(jAudioGain);
        }
    }
    if (jAudioGainConfig != NULL) {
        env->DeleteLocalRef(jAudioGainConfig);
    }
    return jStatus;
}

static jint convertAudioPortFromNative(JNIEnv *env,
                                           jobject *jAudioPort, const struct audio_port *nAudioPort)
{
    jint jStatus = (jint)AUDIO_JAVA_SUCCESS;
    jintArray jSamplingRates = NULL;
    jintArray jChannelMasks = NULL;
    jintArray jFormats = NULL;
    jobjectArray jGains = NULL;
    jobject jHandle = NULL;
    bool useInMask;

    ALOGV("convertAudioPortFromNative id %d role %d type %d",
                                  nAudioPort->id, nAudioPort->role, nAudioPort->type);

    jSamplingRates = env->NewIntArray(nAudioPort->num_sample_rates);
    if (jSamplingRates == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    if (nAudioPort->num_sample_rates) {
        env->SetIntArrayRegion(jSamplingRates, 0, nAudioPort->num_sample_rates,
                               (jint *)nAudioPort->sample_rates);
    }

    jChannelMasks = env->NewIntArray(nAudioPort->num_channel_masks);
    if (jChannelMasks == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    useInMask = useInChannelMask(nAudioPort->type, nAudioPort->role);

    jint jMask;
    for (size_t j = 0; j < nAudioPort->num_channel_masks; j++) {
        if (useInMask) {
            jMask = inChannelMaskFromNative(nAudioPort->channel_masks[j]);
        } else {
            jMask = outChannelMaskFromNative(nAudioPort->channel_masks[j]);
        }
        env->SetIntArrayRegion(jChannelMasks, j, 1, &jMask);
    }

    jFormats = env->NewIntArray(nAudioPort->num_formats);
    if (jFormats == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    for (size_t j = 0; j < nAudioPort->num_formats; j++) {
        jint jFormat = audioFormatFromNative(nAudioPort->formats[j]);
        env->SetIntArrayRegion(jFormats, j, 1, &jFormat);
    }

    jGains = env->NewObjectArray(nAudioPort->num_gains,
                                          gAudioGainClass, NULL);
    if (jGains == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    for (size_t j = 0; j < nAudioPort->num_gains; j++) {
        audio_channel_mask_t nMask = nAudioPort->gains[j].channel_mask;
        if (useInMask) {
            jMask = inChannelMaskFromNative(nMask);
            ALOGV("convertAudioPortConfigFromNative IN mask java %x native %x", jMask, nMask);
        } else {
            jMask = outChannelMaskFromNative(nMask);
            ALOGV("convertAudioPortConfigFromNative OUT mask java %x native %x", jMask, nMask);
        }

        jobject jGain = env->NewObject(gAudioGainClass, gAudioGainCstor,
                                                 j,
                                                 nAudioPort->gains[j].mode,
                                                 jMask,
                                                 nAudioPort->gains[j].min_value,
                                                 nAudioPort->gains[j].max_value,
                                                 nAudioPort->gains[j].default_value,
                                                 nAudioPort->gains[j].step_value,
                                                 nAudioPort->gains[j].min_ramp_ms,
                                                 nAudioPort->gains[j].max_ramp_ms);
        if (jGain == NULL) {
            jStatus = (jint)AUDIO_JAVA_ERROR;
            goto exit;
        }
        env->SetObjectArrayElement(jGains, j, jGain);
        env->DeleteLocalRef(jGain);
    }

    jHandle = env->NewObject(gAudioHandleClass, gAudioHandleCstor,
                                             nAudioPort->id);
    if (jHandle == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }

    if (nAudioPort->type == AUDIO_PORT_TYPE_DEVICE) {
        ALOGV("convertAudioPortFromNative is a device %08x", nAudioPort->ext.device.type);
        jstring jAddress = env->NewStringUTF(nAudioPort->ext.device.address);
        *jAudioPort = env->NewObject(gAudioDevicePortClass, gAudioDevicePortCstor,
                                     jHandle, jSamplingRates, jChannelMasks, jFormats, jGains,
                                     nAudioPort->ext.device.type, jAddress);
        env->DeleteLocalRef(jAddress);
    } else if (nAudioPort->type == AUDIO_PORT_TYPE_MIX) {
        ALOGV("convertAudioPortFromNative is a mix");
        *jAudioPort = env->NewObject(gAudioMixPortClass, gAudioMixPortCstor,
                                     jHandle, nAudioPort->role, jSamplingRates, jChannelMasks,
                                     jFormats, jGains);
    } else {
        ALOGE("convertAudioPortFromNative unknown nAudioPort type %d", nAudioPort->type);
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    if (*jAudioPort == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }

    jobject jAudioPortConfig;
    jStatus = convertAudioPortConfigFromNative(env,
                                                       *jAudioPort,
                                                       &jAudioPortConfig,
                                                       &nAudioPort->active_config);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    env->SetObjectField(*jAudioPort, gAudioPortFields.mActiveConfig, jAudioPortConfig);

exit:
    if (jSamplingRates != NULL) {
        env->DeleteLocalRef(jSamplingRates);
    }
    if (jChannelMasks != NULL) {
        env->DeleteLocalRef(jChannelMasks);
    }
    if (jFormats != NULL) {
        env->DeleteLocalRef(jFormats);
    }
    if (jGains != NULL) {
        env->DeleteLocalRef(jGains);
    }
    if (jHandle != NULL) {
        env->DeleteLocalRef(jHandle);
    }

    return jStatus;
}


static jint
android_media_AudioSystem_listAudioPorts(JNIEnv *env, jobject clazz,
                                         jobject jPorts, jintArray jGeneration)
{
    ALOGV("listAudioPorts");

    if (jPorts == NULL) {
        ALOGE("listAudioPorts NULL AudioPort ArrayList");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jPorts, gArrayListClass)) {
        ALOGE("listAudioPorts not an arraylist");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    if (jGeneration == NULL || env->GetArrayLength(jGeneration) != 1) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    status_t status;
    unsigned int generation1;
    unsigned int generation;
    unsigned int numPorts;
    jint *nGeneration;
    struct audio_port *nPorts = NULL;
    int attempts = MAX_PORT_GENERATION_SYNC_ATTEMPTS;

    // get the port count and all the ports until they both return the same generation
    do {
        if (attempts-- < 0) {
            status = TIMED_OUT;
            break;
        }

        numPorts = 0;
        status = AudioSystem::listAudioPorts(AUDIO_PORT_ROLE_NONE,
                                             AUDIO_PORT_TYPE_NONE,
                                                      &numPorts,
                                                      NULL,
                                                      &generation1);
        if (status != NO_ERROR || numPorts == 0) {
            ALOGE_IF(status != NO_ERROR, "AudioSystem::listAudioPorts error %d", status);
            break;
        }
        nPorts = (struct audio_port *)realloc(nPorts, numPorts * sizeof(struct audio_port));

        status = AudioSystem::listAudioPorts(AUDIO_PORT_ROLE_NONE,
                                             AUDIO_PORT_TYPE_NONE,
                                                      &numPorts,
                                                      nPorts,
                                                      &generation);
        ALOGV("listAudioPorts AudioSystem::listAudioPorts numPorts %d generation %d generation1 %d",
              numPorts, generation, generation1);
    } while (generation1 != generation && status == NO_ERROR);

    jint jStatus = nativeToJavaStatus(status);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        goto exit;
    }

    nGeneration = env->GetIntArrayElements(jGeneration, NULL);
    if (nGeneration == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    nGeneration[0] = generation1;
    env->ReleaseIntArrayElements(jGeneration, nGeneration, 0);

    for (size_t i = 0; i < numPorts; i++) {
        jobject jAudioPort;
        jStatus = convertAudioPortFromNative(env, &jAudioPort, &nPorts[i]);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        env->CallBooleanMethod(jPorts, gArrayListMethods.add, jAudioPort);
    }

exit:
    free(nPorts);
    return jStatus;
}

static int
android_media_AudioSystem_createAudioPatch(JNIEnv *env, jobject clazz,
                                 jobjectArray jPatches, jobjectArray jSources, jobjectArray jSinks)
{
    status_t status;
    jint jStatus;

    ALOGV("createAudioPatch");
    if (jPatches == NULL || jSources == NULL || jSinks == NULL) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    if (env->GetArrayLength(jPatches) != 1) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    jint numSources = env->GetArrayLength(jSources);
    if (numSources == 0 || numSources > AUDIO_PATCH_PORTS_MAX) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    jint numSinks = env->GetArrayLength(jSinks);
    if (numSinks == 0 || numSinks > AUDIO_PATCH_PORTS_MAX) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    audio_patch_handle_t handle = (audio_patch_handle_t)0;
    jobject jPatch = env->GetObjectArrayElement(jPatches, 0);
    jobject jPatchHandle = NULL;
    if (jPatch != NULL) {
        if (!env->IsInstanceOf(jPatch, gAudioPatchClass)) {
            return (jint)AUDIO_JAVA_BAD_VALUE;
        }
        jPatchHandle = env->GetObjectField(jPatch, gAudioPatchFields.mHandle);
        handle = (audio_patch_handle_t)env->GetIntField(jPatchHandle, gAudioHandleFields.mId);
    }

    struct audio_patch nPatch;

    nPatch.id = handle;
    nPatch.num_sources = 0;
    nPatch.num_sinks = 0;
    jobject jSource = NULL;
    jobject jSink = NULL;

    for (jint i = 0; i < numSources; i++) {
        jSource = env->GetObjectArrayElement(jSources, i);
        if (!env->IsInstanceOf(jSource, gAudioPortConfigClass)) {
            jStatus = (jint)AUDIO_JAVA_BAD_VALUE;
            goto exit;
        }
        jStatus = convertAudioPortConfigToNative(env, &nPatch.sources[i], jSource, false);
        env->DeleteLocalRef(jSource);
        jSource = NULL;
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        nPatch.num_sources++;
    }

    for (jint i = 0; i < numSinks; i++) {
        jSink = env->GetObjectArrayElement(jSinks, i);
        if (!env->IsInstanceOf(jSink, gAudioPortConfigClass)) {
            jStatus = (jint)AUDIO_JAVA_BAD_VALUE;
            goto exit;
        }
        jStatus = convertAudioPortConfigToNative(env, &nPatch.sinks[i], jSink, false);
        env->DeleteLocalRef(jSink);
        jSink = NULL;
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        nPatch.num_sinks++;
    }

    ALOGV("AudioSystem::createAudioPatch");
    status = AudioSystem::createAudioPatch(&nPatch, &handle);
    ALOGV("AudioSystem::createAudioPatch() returned %d hande %d", status, handle);

    jStatus = nativeToJavaStatus(status);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        goto exit;
    }

    if (jPatchHandle == NULL) {
        jPatchHandle = env->NewObject(gAudioHandleClass, gAudioHandleCstor,
                                           handle);
        if (jPatchHandle == NULL) {
            jStatus = (jint)AUDIO_JAVA_ERROR;
            goto exit;
        }
        jPatch = env->NewObject(gAudioPatchClass, gAudioPatchCstor, jPatchHandle, jSources, jSinks);
        if (jPatch == NULL) {
            jStatus = (jint)AUDIO_JAVA_ERROR;
            goto exit;
        }
        env->SetObjectArrayElement(jPatches, 0, jPatch);
    } else {
        env->SetIntField(jPatchHandle, gAudioHandleFields.mId, handle);
    }

exit:
    if (jPatchHandle != NULL) {
        env->DeleteLocalRef(jPatchHandle);
    }
    if (jPatch != NULL) {
        env->DeleteLocalRef(jPatch);
    }
    if (jSource != NULL) {
        env->DeleteLocalRef(jSource);
    }
    if (jSink != NULL) {
        env->DeleteLocalRef(jSink);
    }
    return jStatus;
}

static int
android_media_AudioSystem_releaseAudioPatch(JNIEnv *env, jobject clazz,
                                               jobject jPatch)
{
    ALOGV("releaseAudioPatch");
    if (jPatch == NULL) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    audio_patch_handle_t handle = (audio_patch_handle_t)0;
    jobject jPatchHandle = NULL;
    if (!env->IsInstanceOf(jPatch, gAudioPatchClass)) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    jPatchHandle = env->GetObjectField(jPatch, gAudioPatchFields.mHandle);
    handle = (audio_patch_handle_t)env->GetIntField(jPatchHandle, gAudioHandleFields.mId);
    env->DeleteLocalRef(jPatchHandle);

    ALOGV("AudioSystem::releaseAudioPatch");
    status_t status = AudioSystem::releaseAudioPatch(handle);
    ALOGV("AudioSystem::releaseAudioPatch() returned %d", status);
    jint jStatus = nativeToJavaStatus(status);
    return status;
}

static jint
android_media_AudioSystem_listAudioPatches(JNIEnv *env, jobject clazz,
                                           jobject jPatches, jintArray jGeneration)
{
    ALOGV("listAudioPatches");
    if (jPatches == NULL) {
        ALOGE("listAudioPatches NULL AudioPatch ArrayList");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jPatches, gArrayListClass)) {
        ALOGE("listAudioPatches not an arraylist");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    if (jGeneration == NULL || env->GetArrayLength(jGeneration) != 1) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    status_t status;
    unsigned int generation1;
    unsigned int generation;
    unsigned int numPatches;
    jint *nGeneration;
    struct audio_patch *nPatches = NULL;
    jobjectArray jSources = NULL;
    jobject jSource = NULL;
    jobjectArray jSinks = NULL;
    jobject jSink = NULL;
    jobject jPatch = NULL;
    int attempts = MAX_PORT_GENERATION_SYNC_ATTEMPTS;

    // get the patch count and all the patches until they both return the same generation
    do {
        if (attempts-- < 0) {
            status = TIMED_OUT;
            break;
        }

        numPatches = 0;
        status = AudioSystem::listAudioPatches(&numPatches,
                                               NULL,
                                               &generation1);
        if (status != NO_ERROR || numPatches == 0) {
            ALOGE_IF(status != NO_ERROR, "listAudioPatches AudioSystem::listAudioPatches error %d",
                                      status);
            break;
        }
        nPatches = (struct audio_patch *)realloc(nPatches, numPatches * sizeof(struct audio_patch));

        status = AudioSystem::listAudioPatches(&numPatches,
                                               nPatches,
                                               &generation);
        ALOGV("listAudioPatches AudioSystem::listAudioPatches numPatches %d generation %d generation1 %d",
              numPatches, generation, generation1);

    } while (generation1 != generation && status == NO_ERROR);

    jint jStatus = nativeToJavaStatus(status);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        goto exit;
    }

    nGeneration = env->GetIntArrayElements(jGeneration, NULL);
    if (nGeneration == NULL) {
        jStatus = AUDIO_JAVA_ERROR;
        goto exit;
    }
    nGeneration[0] = generation1;
    env->ReleaseIntArrayElements(jGeneration, nGeneration, 0);

    for (size_t i = 0; i < numPatches; i++) {
        jobject patchHandle = env->NewObject(gAudioHandleClass, gAudioHandleCstor,
                                                 nPatches[i].id);
        if (patchHandle == NULL) {
            jStatus = AUDIO_JAVA_ERROR;
            goto exit;
        }
        ALOGV("listAudioPatches patch %d num_sources %d num_sinks %d",
              i, nPatches[i].num_sources, nPatches[i].num_sinks);

        env->SetIntField(patchHandle, gAudioHandleFields.mId, nPatches[i].id);

        // load sources
        jSources = env->NewObjectArray(nPatches[i].num_sources,
                                       gAudioPortConfigClass, NULL);
        if (jSources == NULL) {
            jStatus = AUDIO_JAVA_ERROR;
            goto exit;
        }

        for (size_t j = 0; j < nPatches[i].num_sources; j++) {
            jStatus = convertAudioPortConfigFromNative(env,
                                                      NULL,
                                                      &jSource,
                                                      &nPatches[i].sources[j]);
            if (jStatus != AUDIO_JAVA_SUCCESS) {
                goto exit;
            }
            env->SetObjectArrayElement(jSources, j, jSource);
            env->DeleteLocalRef(jSource);
            jSource = NULL;
            ALOGV("listAudioPatches patch %d source %d is a %s handle %d",
                  i, j,
                  nPatches[i].sources[j].type == AUDIO_PORT_TYPE_DEVICE ? "device" : "mix",
                  nPatches[i].sources[j].id);
        }
        // load sinks
        jSinks = env->NewObjectArray(nPatches[i].num_sinks,
                                     gAudioPortConfigClass, NULL);
        if (jSinks == NULL) {
            jStatus = AUDIO_JAVA_ERROR;
            goto exit;
        }

        for (size_t j = 0; j < nPatches[i].num_sinks; j++) {
            jStatus = convertAudioPortConfigFromNative(env,
                                                      NULL,
                                                      &jSink,
                                                      &nPatches[i].sinks[j]);

            if (jStatus != AUDIO_JAVA_SUCCESS) {
                goto exit;
            }
            env->SetObjectArrayElement(jSinks, j, jSink);
            env->DeleteLocalRef(jSink);
            jSink = NULL;
            ALOGV("listAudioPatches patch %d sink %d is a %s handle %d",
                  i, j,
                  nPatches[i].sinks[j].type == AUDIO_PORT_TYPE_DEVICE ? "device" : "mix",
                  nPatches[i].sinks[j].id);
        }

        jPatch = env->NewObject(gAudioPatchClass, gAudioPatchCstor,
                                       patchHandle, jSources, jSinks);
        env->DeleteLocalRef(jSources);
        jSources = NULL;
        env->DeleteLocalRef(jSinks);
        jSinks = NULL;
        if (jPatch == NULL) {
            jStatus = AUDIO_JAVA_ERROR;
            goto exit;
        }
        env->CallBooleanMethod(jPatches, gArrayListMethods.add, jPatch);
        env->DeleteLocalRef(jPatch);
        jPatch = NULL;
    }

exit:
    if (jSources != NULL) {
        env->DeleteLocalRef(jSources);
    }
    if (jSource != NULL) {
        env->DeleteLocalRef(jSource);
    }
    if (jSinks != NULL) {
        env->DeleteLocalRef(jSinks);
    }
    if (jSink != NULL) {
        env->DeleteLocalRef(jSink);
    }
    if (jPatch != NULL) {
        env->DeleteLocalRef(jPatch);
    }
    free(nPatches);
    return jStatus;
}

static jint
android_media_AudioSystem_setAudioPortConfig(JNIEnv *env, jobject clazz,
                                 jobject jAudioPortConfig)
{
    ALOGV("setAudioPortConfig");
    if (jAudioPortConfig == NULL) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jAudioPortConfig, gAudioPortConfigClass)) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    struct audio_port_config nAudioPortConfig;
    jint jStatus = convertAudioPortConfigToNative(env, &nAudioPortConfig, jAudioPortConfig, true);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    status_t status = AudioSystem::setAudioPortConfig(&nAudioPortConfig);
    ALOGV("AudioSystem::setAudioPortConfig() returned %d", status);
    jStatus = nativeToJavaStatus(status);
    return jStatus;
}

static void
android_media_AudioSystem_eventHandlerSetup(JNIEnv *env, jobject thiz, jobject weak_this)
{
    ALOGV("eventHandlerSetup");

    sp<JNIAudioPortCallback> callback = new JNIAudioPortCallback(env, thiz, weak_this);

    AudioSystem::setAudioPortCallback(callback);
}

static void
android_media_AudioSystem_eventHandlerFinalize(JNIEnv *env, jobject thiz)
{
    ALOGV("eventHandlerFinalize");

    sp<JNIAudioPortCallback> callback;

    AudioSystem::setAudioPortCallback(callback);
}

static jint
android_media_AudioSystem_getAudioHwSyncForSession(JNIEnv *env, jobject thiz, jint sessionId)
{
    return (jint)AudioSystem::getAudioHwSyncForSession((audio_session_t)sessionId);
}




static jint convertAudioMixToNative(JNIEnv *env,
                                    AudioMix *nAudioMix,
                                    const jobject jAudioMix)
{
    nAudioMix->mMixType = env->GetIntField(jAudioMix, gAudioMixFields.mMixType);
    nAudioMix->mRouteFlags = env->GetIntField(jAudioMix, gAudioMixFields.mRouteFlags);

    jstring jRegistrationId = (jstring)env->GetObjectField(jAudioMix,
                                                           gAudioMixFields.mRegistrationId);
    const char *nRegistrationId = env->GetStringUTFChars(jRegistrationId, NULL);
    nAudioMix->mRegistrationId = String8(nRegistrationId);
    env->ReleaseStringUTFChars(jRegistrationId, nRegistrationId);
    env->DeleteLocalRef(jRegistrationId);

    jobject jFormat = env->GetObjectField(jAudioMix, gAudioMixFields.mFormat);
    nAudioMix->mFormat.sample_rate = env->GetIntField(jFormat,
                                                     gAudioFormatFields.mSampleRate);
    nAudioMix->mFormat.channel_mask = outChannelMaskToNative(env->GetIntField(jFormat,
                                                     gAudioFormatFields.mChannelMask));
    nAudioMix->mFormat.format = audioFormatToNative(env->GetIntField(jFormat,
                                                     gAudioFormatFields.mEncoding));
    env->DeleteLocalRef(jFormat);

    jobject jRule = env->GetObjectField(jAudioMix, gAudioMixFields.mRule);
    jobject jRuleCriteria = env->GetObjectField(jRule, gAudioMixingRuleFields.mCriteria);
    env->DeleteLocalRef(jRule);
    jobjectArray jCriteria = (jobjectArray)env->CallObjectMethod(jRuleCriteria,
                                                                 gArrayListMethods.toArray);
    env->DeleteLocalRef(jRuleCriteria);

    jint numCriteria = env->GetArrayLength(jCriteria);
    if (numCriteria > MAX_CRITERIA_PER_MIX) {
        numCriteria = MAX_CRITERIA_PER_MIX;
    }

    for (jint i = 0; i < numCriteria; i++) {
        AttributeMatchCriterion nCriterion;

        jobject jCriterion = env->GetObjectArrayElement(jCriteria, i);

        nCriterion.mRule = env->GetIntField(jCriterion, gAttributeMatchCriterionFields.mRule);

        jobject jAttributes = env->GetObjectField(jCriterion, gAttributeMatchCriterionFields.mAttr);
        if (nCriterion.mRule == RULE_MATCH_ATTRIBUTE_USAGE ||
                nCriterion.mRule == RULE_EXCLUDE_ATTRIBUTE_USAGE) {
            nCriterion.mAttr.mUsage = (audio_usage_t)env->GetIntField(jAttributes,
                                                       gAudioAttributesFields.mUsage);
        } else {
            nCriterion.mAttr.mSource = (audio_source_t)env->GetIntField(jAttributes,
                                                        gAudioAttributesFields.mSource);
        }
        env->DeleteLocalRef(jAttributes);

        nAudioMix->mCriteria.add(nCriterion);
        env->DeleteLocalRef(jCriterion);
    }

    env->DeleteLocalRef(jCriteria);

    return (jint)AUDIO_JAVA_SUCCESS;
}

static jint
android_media_AudioSystem_registerPolicyMixes(JNIEnv *env, jobject clazz,
                                              jobject jMixesList, jboolean registration)
{
    ALOGV("registerPolicyMixes");

    if (jMixesList == NULL) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jMixesList, gArrayListClass)) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    jobjectArray jMixes = (jobjectArray)env->CallObjectMethod(jMixesList,
                                                              gArrayListMethods.toArray);
    jint numMixes = env->GetArrayLength(jMixes);
    if (numMixes > MAX_MIXES_PER_POLICY) {
        numMixes = MAX_MIXES_PER_POLICY;
    }

    status_t status;
    jint jStatus;
    jobject jAudioMix = NULL;
    Vector <AudioMix> mixes;
    for (jint i = 0; i < numMixes; i++) {
        jAudioMix = env->GetObjectArrayElement(jMixes, i);
        if (!env->IsInstanceOf(jAudioMix, gAudioMixClass)) {
            jStatus = (jint)AUDIO_JAVA_BAD_VALUE;
            goto exit;
        }
        AudioMix mix;
        jStatus = convertAudioMixToNative(env, &mix, jAudioMix);
        env->DeleteLocalRef(jAudioMix);
        jAudioMix = NULL;
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        mixes.add(mix);
    }

    ALOGV("AudioSystem::registerPolicyMixes numMixes %d registration %d", numMixes, registration);
    status = AudioSystem::registerPolicyMixes(mixes, registration);
    ALOGV("AudioSystem::registerPolicyMixes() returned %d", status);

    jStatus = nativeToJavaStatus(status);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        goto exit;
    }

exit:
    if (jAudioMix != NULL) {
        env->DeleteLocalRef(jAudioMix);
    }
    return jStatus;
}



// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"setParameters",        "(Ljava/lang/String;)I", (void *)android_media_AudioSystem_setParameters},
    {"getParameters",        "(Ljava/lang/String;)Ljava/lang/String;", (void *)android_media_AudioSystem_getParameters},
    {"muteMicrophone",      "(Z)I",     (void *)android_media_AudioSystem_muteMicrophone},
    {"isMicrophoneMuted",   "()Z",      (void *)android_media_AudioSystem_isMicrophoneMuted},
    {"isStreamActive",      "(II)Z",    (void *)android_media_AudioSystem_isStreamActive},
    {"isStreamActiveRemotely","(II)Z",  (void *)android_media_AudioSystem_isStreamActiveRemotely},
    {"isSourceActive",      "(I)Z",     (void *)android_media_AudioSystem_isSourceActive},
    {"newAudioSessionId",   "()I",      (void *)android_media_AudioSystem_newAudioSessionId},
    {"setDeviceConnectionState", "(IILjava/lang/String;)I", (void *)android_media_AudioSystem_setDeviceConnectionState},
    {"getDeviceConnectionState", "(ILjava/lang/String;)I",  (void *)android_media_AudioSystem_getDeviceConnectionState},
    {"setPhoneState",       "(I)I",     (void *)android_media_AudioSystem_setPhoneState},
    {"setForceUse",         "(II)I",    (void *)android_media_AudioSystem_setForceUse},
    {"getForceUse",         "(I)I",     (void *)android_media_AudioSystem_getForceUse},
    {"initStreamVolume",    "(III)I",   (void *)android_media_AudioSystem_initStreamVolume},
    {"setStreamVolumeIndex","(III)I",   (void *)android_media_AudioSystem_setStreamVolumeIndex},
    {"getStreamVolumeIndex","(II)I",    (void *)android_media_AudioSystem_getStreamVolumeIndex},
    {"setMasterVolume",     "(F)I",     (void *)android_media_AudioSystem_setMasterVolume},
    {"getMasterVolume",     "()F",      (void *)android_media_AudioSystem_getMasterVolume},
    {"setMasterMute",       "(Z)I",     (void *)android_media_AudioSystem_setMasterMute},
    {"getMasterMute",       "()Z",      (void *)android_media_AudioSystem_getMasterMute},
    {"getDevicesForStream", "(I)I",     (void *)android_media_AudioSystem_getDevicesForStream},
    {"getPrimaryOutputSamplingRate", "()I", (void *)android_media_AudioSystem_getPrimaryOutputSamplingRate},
    {"getPrimaryOutputFrameCount",   "()I", (void *)android_media_AudioSystem_getPrimaryOutputFrameCount},
    {"getOutputLatency",    "(I)I",     (void *)android_media_AudioSystem_getOutputLatency},
    {"setLowRamDevice",     "(Z)I",     (void *)android_media_AudioSystem_setLowRamDevice},
    {"checkAudioFlinger",    "()I",     (void *)android_media_AudioSystem_checkAudioFlinger},
    {"listAudioPorts",      "(Ljava/util/ArrayList;[I)I",
                                                (void *)android_media_AudioSystem_listAudioPorts},
    {"createAudioPatch",    "([Landroid/media/AudioPatch;[Landroid/media/AudioPortConfig;[Landroid/media/AudioPortConfig;)I",
                                            (void *)android_media_AudioSystem_createAudioPatch},
    {"releaseAudioPatch",   "(Landroid/media/AudioPatch;)I",
                                            (void *)android_media_AudioSystem_releaseAudioPatch},
    {"listAudioPatches",    "(Ljava/util/ArrayList;[I)I",
                                                (void *)android_media_AudioSystem_listAudioPatches},
    {"setAudioPortConfig",   "(Landroid/media/AudioPortConfig;)I",
                                            (void *)android_media_AudioSystem_setAudioPortConfig},
    {"getAudioHwSyncForSession", "(I)I",
                                    (void *)android_media_AudioSystem_getAudioHwSyncForSession},
    {"registerPolicyMixes",    "(Ljava/util/ArrayList;Z)I",
                                            (void *)android_media_AudioSystem_registerPolicyMixes},

};


static JNINativeMethod gEventHandlerMethods[] = {
    {"native_setup",
        "(Ljava/lang/Object;)V",
        (void *)android_media_AudioSystem_eventHandlerSetup},
    {"native_finalize",
        "()V",
        (void *)android_media_AudioSystem_eventHandlerFinalize},
};

int register_android_media_AudioSystem(JNIEnv *env)
{

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    gArrayListClass = (jclass) env->NewGlobalRef(arrayListClass);
    gArrayListMethods.add = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    gArrayListMethods.toArray = env->GetMethodID(arrayListClass, "toArray", "()[Ljava/lang/Object;");

    jclass audioHandleClass = env->FindClass("android/media/AudioHandle");
    gAudioHandleClass = (jclass) env->NewGlobalRef(audioHandleClass);
    gAudioHandleCstor = env->GetMethodID(audioHandleClass, "<init>", "(I)V");
    gAudioHandleFields.mId = env->GetFieldID(audioHandleClass, "mId", "I");

    jclass audioPortClass = env->FindClass("android/media/AudioPort");
    gAudioPortClass = (jclass) env->NewGlobalRef(audioPortClass);
    gAudioPortCstor = env->GetMethodID(audioPortClass, "<init>",
                               "(Landroid/media/AudioHandle;I[I[I[I[Landroid/media/AudioGain;)V");
    gAudioPortFields.mHandle = env->GetFieldID(audioPortClass, "mHandle",
                                               "Landroid/media/AudioHandle;");
    gAudioPortFields.mRole = env->GetFieldID(audioPortClass, "mRole", "I");
    gAudioPortFields.mGains = env->GetFieldID(audioPortClass, "mGains",
                                              "[Landroid/media/AudioGain;");
    gAudioPortFields.mActiveConfig = env->GetFieldID(audioPortClass, "mActiveConfig",
                                              "Landroid/media/AudioPortConfig;");

    jclass audioPortConfigClass = env->FindClass("android/media/AudioPortConfig");
    gAudioPortConfigClass = (jclass) env->NewGlobalRef(audioPortConfigClass);
    gAudioPortConfigCstor = env->GetMethodID(audioPortConfigClass, "<init>",
                                 "(Landroid/media/AudioPort;IIILandroid/media/AudioGainConfig;)V");
    gAudioPortConfigFields.mPort = env->GetFieldID(audioPortConfigClass, "mPort",
                                                   "Landroid/media/AudioPort;");
    gAudioPortConfigFields.mSamplingRate = env->GetFieldID(audioPortConfigClass,
                                                           "mSamplingRate", "I");
    gAudioPortConfigFields.mChannelMask = env->GetFieldID(audioPortConfigClass,
                                                          "mChannelMask", "I");
    gAudioPortConfigFields.mFormat = env->GetFieldID(audioPortConfigClass, "mFormat", "I");
    gAudioPortConfigFields.mGain = env->GetFieldID(audioPortConfigClass, "mGain",
                                                   "Landroid/media/AudioGainConfig;");
    gAudioPortConfigFields.mConfigMask = env->GetFieldID(audioPortConfigClass, "mConfigMask", "I");

    jclass audioDevicePortConfigClass = env->FindClass("android/media/AudioDevicePortConfig");
    gAudioDevicePortConfigClass = (jclass) env->NewGlobalRef(audioDevicePortConfigClass);
    gAudioDevicePortConfigCstor = env->GetMethodID(audioDevicePortConfigClass, "<init>",
                         "(Landroid/media/AudioDevicePort;IIILandroid/media/AudioGainConfig;)V");

    jclass audioMixPortConfigClass = env->FindClass("android/media/AudioMixPortConfig");
    gAudioMixPortConfigClass = (jclass) env->NewGlobalRef(audioMixPortConfigClass);
    gAudioMixPortConfigCstor = env->GetMethodID(audioMixPortConfigClass, "<init>",
                         "(Landroid/media/AudioMixPort;IIILandroid/media/AudioGainConfig;)V");

    jclass audioDevicePortClass = env->FindClass("android/media/AudioDevicePort");
    gAudioDevicePortClass = (jclass) env->NewGlobalRef(audioDevicePortClass);
    gAudioDevicePortCstor = env->GetMethodID(audioDevicePortClass, "<init>",
             "(Landroid/media/AudioHandle;[I[I[I[Landroid/media/AudioGain;ILjava/lang/String;)V");

    jclass audioMixPortClass = env->FindClass("android/media/AudioMixPort");
    gAudioMixPortClass = (jclass) env->NewGlobalRef(audioMixPortClass);
    gAudioMixPortCstor = env->GetMethodID(audioMixPortClass, "<init>",
                              "(Landroid/media/AudioHandle;I[I[I[I[Landroid/media/AudioGain;)V");

    jclass audioGainClass = env->FindClass("android/media/AudioGain");
    gAudioGainClass = (jclass) env->NewGlobalRef(audioGainClass);
    gAudioGainCstor = env->GetMethodID(audioGainClass, "<init>", "(IIIIIIIII)V");

    jclass audioGainConfigClass = env->FindClass("android/media/AudioGainConfig");
    gAudioGainConfigClass = (jclass) env->NewGlobalRef(audioGainConfigClass);
    gAudioGainConfigCstor = env->GetMethodID(audioGainConfigClass, "<init>",
                                             "(ILandroid/media/AudioGain;II[II)V");
    gAudioGainConfigFields.mIndex = env->GetFieldID(gAudioGainConfigClass, "mIndex", "I");
    gAudioGainConfigFields.mMode = env->GetFieldID(audioGainConfigClass, "mMode", "I");
    gAudioGainConfigFields.mChannelMask = env->GetFieldID(audioGainConfigClass, "mChannelMask",
                                                          "I");
    gAudioGainConfigFields.mValues = env->GetFieldID(audioGainConfigClass, "mValues", "[I");
    gAudioGainConfigFields.mRampDurationMs = env->GetFieldID(audioGainConfigClass,
                                                             "mRampDurationMs", "I");

    jclass audioPatchClass = env->FindClass("android/media/AudioPatch");
    gAudioPatchClass = (jclass) env->NewGlobalRef(audioPatchClass);
    gAudioPatchCstor = env->GetMethodID(audioPatchClass, "<init>",
"(Landroid/media/AudioHandle;[Landroid/media/AudioPortConfig;[Landroid/media/AudioPortConfig;)V");
    gAudioPatchFields.mHandle = env->GetFieldID(audioPatchClass, "mHandle",
                                                "Landroid/media/AudioHandle;");

    jclass eventHandlerClass = env->FindClass(kEventHandlerClassPathName);
    gPostEventFromNative = env->GetStaticMethodID(eventHandlerClass, "postEventFromNative",
                                            "(Ljava/lang/Object;IIILjava/lang/Object;)V");


    jclass audioMixClass = env->FindClass("android/media/audiopolicy/AudioMix");
    gAudioMixClass = (jclass) env->NewGlobalRef(audioMixClass);
    gAudioMixFields.mRule = env->GetFieldID(audioMixClass, "mRule",
                                                "Landroid/media/audiopolicy/AudioMixingRule;");
    gAudioMixFields.mFormat = env->GetFieldID(audioMixClass, "mFormat",
                                                "Landroid/media/AudioFormat;");
    gAudioMixFields.mRouteFlags = env->GetFieldID(audioMixClass, "mRouteFlags", "I");
    gAudioMixFields.mRegistrationId = env->GetFieldID(audioMixClass, "mRegistrationId",
                                                      "Ljava/lang/String;");
    gAudioMixFields.mMixType = env->GetFieldID(audioMixClass, "mMixType", "I");

    jclass audioFormatClass = env->FindClass("android/media/AudioFormat");
    gAudioFormatClass = (jclass) env->NewGlobalRef(audioFormatClass);
    gAudioFormatFields.mEncoding = env->GetFieldID(audioFormatClass, "mEncoding", "I");
    gAudioFormatFields.mSampleRate = env->GetFieldID(audioFormatClass, "mSampleRate", "I");
    gAudioFormatFields.mChannelMask = env->GetFieldID(audioFormatClass, "mChannelMask", "I");

    jclass audioMixingRuleClass = env->FindClass("android/media/audiopolicy/AudioMixingRule");
    gAudioMixingRuleClass = (jclass) env->NewGlobalRef(audioMixingRuleClass);
    gAudioMixingRuleFields.mCriteria = env->GetFieldID(audioMixingRuleClass, "mCriteria",
                                                       "Ljava/util/ArrayList;");

    jclass attributeMatchCriterionClass =
                env->FindClass("android/media/audiopolicy/AudioMixingRule$AttributeMatchCriterion");
    gAttributeMatchCriterionClass = (jclass) env->NewGlobalRef(attributeMatchCriterionClass);
    gAttributeMatchCriterionFields.mAttr = env->GetFieldID(attributeMatchCriterionClass, "mAttr",
                                                       "Landroid/media/AudioAttributes;");
    gAttributeMatchCriterionFields.mRule = env->GetFieldID(attributeMatchCriterionClass, "mRule",
                                                       "I");

    jclass audioAttributesClass = env->FindClass("android/media/AudioAttributes");
    gAudioAttributesClass = (jclass) env->NewGlobalRef(audioAttributesClass);
    gAudioAttributesFields.mUsage = env->GetFieldID(audioAttributesClass, "mUsage", "I");
    gAudioAttributesFields.mSource = env->GetFieldID(audioAttributesClass, "mSource", "I");

    AudioSystem::setErrorCallback(android_media_AudioSystem_error_callback);

    int status = AndroidRuntime::registerNativeMethods(env,
                kClassPathName, gMethods, NELEM(gMethods));

    if (status == 0) {
        status = AndroidRuntime::registerNativeMethods(env,
                kEventHandlerClassPathName, gEventHandlerMethods, NELEM(gEventHandlerMethods));
    }
    return status;
}
