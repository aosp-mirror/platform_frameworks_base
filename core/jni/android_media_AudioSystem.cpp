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

#include <sstream>
#include <vector>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <media/AudioSystem.h>
#include <media/AudioPolicy.h>
#include <media/MicrophoneInfo.h>
#include <nativehelper/ScopedLocalRef.h>
#include <system/audio.h>
#include <system/audio_policy.h>
#include "android_media_AudioEffectDescriptor.h"
#include "android_media_AudioFormat.h"
#include "android_media_AudioErrors.h"
#include "android_media_MicrophoneInfo.h"
#include "android_media_AudioAttributes.h"

// ----------------------------------------------------------------------------

using namespace android;

static const char* const kClassPathName = "android/media/AudioSystem";

static jclass gArrayListClass;
static struct {
    jmethodID    add;
    jmethodID    toArray;
} gArrayListMethods;

static jclass gBooleanClass;
static jmethodID gBooleanCstor;

static jclass gIntegerClass;
static jmethodID gIntegerCstor;

static jclass gMapClass;
static jmethodID gMapPut;

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
    // Valid only if an AudioDevicePort
    jfieldID    mType;
    jfieldID    mAddress;
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
    jfieldID    mDeviceType;
    jfieldID    mDeviceAddress;
    jfieldID    mMixType;
    jfieldID    mCallbackFlags;
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

static jclass gAudioMixMatchCriterionClass;
static struct {
    jfieldID    mAttr;
    jfieldID    mIntProp;
    jfieldID    mRule;
} gAudioMixMatchCriterionFields;

static const char* const kEventHandlerClassPathName =
        "android/media/AudioPortEventHandler";
static struct {
    jfieldID    mJniCallback;
} gEventHandlerFields;
static struct {
    jmethodID    postEventFromNative;
} gAudioPortEventHandlerMethods;

static struct {
    jmethodID postDynPolicyEventFromNative;
    jmethodID postRecordConfigEventFromNative;
} gAudioPolicyEventHandlerMethods;

//
// JNI Initialization for OpenSLES routing
//
jmethodID gMidAudioTrackRoutingProxy_ctor;
jmethodID gMidAudioTrackRoutingProxy_release;
jmethodID gMidAudioRecordRoutingProxy_ctor;
jmethodID gMidAudioRecordRoutingProxy_release;

jclass gClsAudioTrackRoutingProxy;
jclass gClsAudioRecordRoutingProxy;

static Mutex gLock;

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
// ref-counted object for audio port callbacks
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

    jclass      mClass;     // Reference to AudioPortEventHandler class
    jobject     mObject;    // Weak ref to AudioPortEventHandler Java object to call on
};

JNIAudioPortCallback::JNIAudioPortCallback(JNIEnv* env, jobject thiz, jobject weak_thiz)
{

    // Hold onto the AudioPortEventHandler class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find class %s", kEventHandlerClassPathName);
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the AudioPortEventHandler object can be garbage collected.
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
    env->CallStaticVoidMethod(mClass, gAudioPortEventHandlerMethods.postEventFromNative, mObject,
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

static sp<JNIAudioPortCallback> setJniCallback(JNIEnv* env,
                                       jobject thiz,
                                       const sp<JNIAudioPortCallback>& callback)
{
    Mutex::Autolock l(gLock);
    sp<JNIAudioPortCallback> old =
            (JNIAudioPortCallback*)env->GetLongField(thiz, gEventHandlerFields.mJniCallback);
    if (callback.get()) {
        callback->incStrong((void*)setJniCallback);
    }
    if (old != 0) {
        old->decStrong((void*)setJniCallback);
    }
    env->SetLongField(thiz, gEventHandlerFields.mJniCallback, (jlong)callback.get());
    return old;
}

#define check_AudioSystem_Command(status) _check_AudioSystem_Command(__func__, (status))

static int _check_AudioSystem_Command(const char* caller, status_t status)
{
    ALOGE_IF(status, "Command failed for %s: %d", caller, status);
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
    return AudioSystem::newAudioUniqueId(AUDIO_UNIQUE_ID_USE_SESSION);
}

static jint
android_media_AudioSystem_newAudioPlayerId(JNIEnv *env, jobject thiz)
{
    return AudioSystem::newAudioUniqueId(AUDIO_UNIQUE_ID_USE_PLAYER);
}

static jint
android_media_AudioSystem_setParameters(JNIEnv *env, jobject thiz, jstring keyValuePairs)
{
    const jchar* c_keyValuePairs = env->GetStringCritical(keyValuePairs, 0);
    String8 c_keyValuePairs8;
    if (keyValuePairs) {
        c_keyValuePairs8 = String8(
            reinterpret_cast<const char16_t*>(c_keyValuePairs),
            env->GetStringLength(keyValuePairs));
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
        c_keys8 = String8(reinterpret_cast<const char16_t*>(c_keys),
                          env->GetStringLength(keys));
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

static void
android_media_AudioSystem_dyn_policy_callback(int event, String8 regId, int val)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }

    jclass clazz = env->FindClass(kClassPathName);
    const char* zechars = regId.string();
    jstring zestring = env->NewStringUTF(zechars);

    env->CallStaticVoidMethod(clazz, gAudioPolicyEventHandlerMethods.postDynPolicyEventFromNative,
            event, zestring, val);

    env->ReleaseStringUTFChars(zestring, zechars);
    env->DeleteLocalRef(clazz);
}

static void
android_media_AudioSystem_recording_callback(int event,
                                             const record_client_info_t *clientInfo,
                                             const audio_config_base_t *clientConfig,
                                             std::vector<effect_descriptor_t> clientEffects,
                                             const audio_config_base_t *deviceConfig,
                                             std::vector<effect_descriptor_t> effects __unused,
                                             audio_patch_handle_t patchHandle,
                                             audio_source_t source)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }
    if (clientInfo == NULL || clientConfig == NULL || deviceConfig == NULL) {
        ALOGE("Unexpected null client/device info or configurations in recording callback");
        return;
    }

    // create an array for 2*3 integers to store the record configurations (client + device)
    //                 plus 1 integer for the patch handle
    const int REC_PARAM_SIZE = 7;
    jintArray recParamArray = env->NewIntArray(REC_PARAM_SIZE);
    if (recParamArray == NULL) {
        ALOGE("recording callback: Couldn't allocate int array for configuration data");
        return;
    }
    jint recParamData[REC_PARAM_SIZE];
    recParamData[0] = (jint) audioFormatFromNative(clientConfig->format);
    // FIXME this doesn't support index-based masks
    recParamData[1] = (jint) inChannelMaskFromNative(clientConfig->channel_mask);
    recParamData[2] = (jint) clientConfig->sample_rate;
    recParamData[3] = (jint) audioFormatFromNative(deviceConfig->format);
    // FIXME this doesn't support index-based masks
    recParamData[4] = (jint) inChannelMaskFromNative(deviceConfig->channel_mask);
    recParamData[5] = (jint) deviceConfig->sample_rate;
    recParamData[6] = (jint) patchHandle;
    env->SetIntArrayRegion(recParamArray, 0, REC_PARAM_SIZE, recParamData);

    jobjectArray jClientEffects;
    convertAudioEffectDescriptorVectorFromNative(env, &jClientEffects, clientEffects);

    jobjectArray jEffects;
    convertAudioEffectDescriptorVectorFromNative(env, &jEffects, effects);

    // callback into java
    jclass clazz = env->FindClass(kClassPathName);

    env->CallStaticVoidMethod(clazz,
                              gAudioPolicyEventHandlerMethods.postRecordConfigEventFromNative,
                              event, (jint) clientInfo->uid, clientInfo->session,
                              clientInfo->source, clientInfo->port_id, clientInfo->silenced,
                              recParamArray, jClientEffects, jEffects, source);
    env->DeleteLocalRef(clazz);
    env->DeleteLocalRef(recParamArray);
    env->DeleteLocalRef(jClientEffects);
    env->DeleteLocalRef(jEffects);
}

static jint
android_media_AudioSystem_setDeviceConnectionState(JNIEnv *env, jobject thiz, jint device, jint state, jstring device_address, jstring device_name,
                                                   jint codec)
{
    const char *c_address = env->GetStringUTFChars(device_address, NULL);
    const char *c_name = env->GetStringUTFChars(device_name, NULL);
    int status = check_AudioSystem_Command(AudioSystem::setDeviceConnectionState(static_cast <audio_devices_t>(device),
                                          static_cast <audio_policy_dev_state_t>(state),
                                          c_address, c_name,
                                          static_cast <audio_format_t>(codec)));
    env->ReleaseStringUTFChars(device_address, c_address);
    env->ReleaseStringUTFChars(device_name, c_name);
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
android_media_AudioSystem_handleDeviceConfigChange(JNIEnv *env, jobject thiz, jint device, jstring device_address, jstring device_name,
                                                   jint codec)
{
    const char *c_address = env->GetStringUTFChars(device_address, NULL);
    const char *c_name = env->GetStringUTFChars(device_name, NULL);
    int status = check_AudioSystem_Command(AudioSystem::handleDeviceConfigChange(static_cast <audio_devices_t>(device),
                                          c_address, c_name, static_cast <audio_format_t>(codec)));
    env->ReleaseStringUTFChars(device_address, c_address);
    env->ReleaseStringUTFChars(device_name, c_name);
    return (jint) status;
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
android_media_AudioSystem_setMasterMono(JNIEnv *env, jobject thiz, jboolean mono)
{
    return (jint) check_AudioSystem_Command(AudioSystem::setMasterMono(mono));
}

static jboolean
android_media_AudioSystem_getMasterMono(JNIEnv *env, jobject thiz)
{
    bool mono;
    if (AudioSystem::getMasterMono(&mono) != NO_ERROR) {
        mono = false;
    }
    return mono;
}

static jint
android_media_AudioSystem_setMasterBalance(JNIEnv *env, jobject thiz, jfloat balance)
{
    return (jint) check_AudioSystem_Command(AudioSystem::setMasterBalance(balance));
}

static jfloat
android_media_AudioSystem_getMasterBalance(JNIEnv *env, jobject thiz)
{
    float balance;
    const status_t status = AudioSystem::getMasterBalance(&balance);
    if (status != NO_ERROR) {
        ALOGW("%s getMasterBalance error %d, returning 0.f, audioserver down?", __func__, status);
        balance = 0.f;
    }
    return balance;
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
android_media_AudioSystem_setLowRamDevice(
        JNIEnv *env, jobject clazz, jboolean isLowRamDevice, jlong totalMemory)
{
    return (jint) AudioSystem::setLowRamDevice((bool) isLowRamDevice, (int64_t) totalMemory);
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

/**
 * Extends convertAudioPortConfigToNative with extra device port info.
 * Mix / Session specific info is not fulfilled.
 */
static jint convertAudioPortConfigToNativeWithDevicePort(JNIEnv *env,
                                                         struct audio_port_config *nAudioPortConfig,
                                                         const jobject jAudioPortConfig,
                                                         bool useConfigMask)
{
    jint jStatus = convertAudioPortConfigToNative(env,
            nAudioPortConfig,
            jAudioPortConfig,
            useConfigMask);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    // Supports AUDIO_PORT_TYPE_DEVICE only
    if (nAudioPortConfig->type != AUDIO_PORT_TYPE_DEVICE) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    jobject jAudioDevicePort = env->GetObjectField(jAudioPortConfig,
            gAudioPortConfigFields.mPort);
    nAudioPortConfig->ext.device.type = env->GetIntField(jAudioDevicePort,
            gAudioPortFields.mType);
    jstring jDeviceAddress = (jstring)env->GetObjectField(jAudioDevicePort,
            gAudioPortFields.mAddress);
    const char *nDeviceAddress = env->GetStringUTFChars(jDeviceAddress, NULL);
    strncpy(nAudioPortConfig->ext.device.address,
            nDeviceAddress, AUDIO_DEVICE_MAX_ADDRESS_LEN - 1);
    env->ReleaseStringUTFChars(jDeviceAddress, nDeviceAddress);
    env->DeleteLocalRef(jDeviceAddress);
    env->DeleteLocalRef(jAudioDevicePort);
    return jStatus;
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
                                           jHandle, // handle
                                           0,       // role
                                           NULL,    // name
                                           NULL,    // samplingRates
                                           NULL,    // channelMasks
                                           NULL,    // channelIndexMasks
                                           NULL,    // formats
                                           NULL);   // gains
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

static bool hasFormat(int* formats, size_t size, int format) {
    for (size_t index = 0; index < size; index++) {
        if (formats[index] == format) {
            return true; // found
        }
    }
    return false; // not found
}

// TODO: pull out to separate file
template <typename T, size_t N>
static constexpr size_t array_size(const T (&)[N]) {
    return N;
}

static jint convertAudioPortFromNative(JNIEnv *env,
                                           jobject *jAudioPort, const struct audio_port *nAudioPort)
{
    jint jStatus = (jint)AUDIO_JAVA_SUCCESS;
    jintArray jSamplingRates = NULL;
    jintArray jChannelMasks = NULL;
    jintArray jChannelIndexMasks = NULL;
    int* cFormats = NULL;
    jintArray jFormats = NULL;
    jobjectArray jGains = NULL;
    jobject jHandle = NULL;
    jobject jAudioPortConfig = NULL;
    jstring jDeviceName = NULL;
    bool useInMask;
    size_t numPositionMasks = 0;
    size_t numIndexMasks = 0;
    size_t numUniqueFormats = 0;

    ALOGV("convertAudioPortFromNative id %d role %d type %d name %s",
        nAudioPort->id, nAudioPort->role, nAudioPort->type, nAudioPort->name);

    // Verify audio port array count info.
    if (nAudioPort->num_sample_rates > array_size(nAudioPort->sample_rates)
            || nAudioPort->num_channel_masks > array_size(nAudioPort->channel_masks)
            || nAudioPort->num_formats > array_size(nAudioPort->formats)
            || nAudioPort->num_gains > array_size(nAudioPort->gains)) {

        std::stringstream ss;
        ss << "convertAudioPortFromNative array count out of bounds:"
                << " num_sample_rates " << nAudioPort->num_sample_rates
                << " num_channel_masks " << nAudioPort->num_channel_masks
                << " num_formats " << nAudioPort->num_formats
                << " num_gains " << nAudioPort->num_gains
                ;
        std::string s = ss.str();

        // Prefer to log through Java wtf instead of native ALOGE.
        ScopedLocalRef<jclass> jLogClass(env, env->FindClass("android/util/Log"));
        jmethodID jWtfId = (jLogClass.get() == nullptr)
                ? nullptr
                : env->GetStaticMethodID(jLogClass.get(), "wtf",
                        "(Ljava/lang/String;Ljava/lang/String;)I");
        if (jWtfId != nullptr) {
            ScopedLocalRef<jstring> jMessage(env, env->NewStringUTF(s.c_str()));
            ScopedLocalRef<jstring> jTag(env, env->NewStringUTF(LOG_TAG));
            (void)env->CallStaticIntMethod(jLogClass.get(), jWtfId, jTag.get(), jMessage.get());
        } else {
            ALOGE("%s", s.c_str());
        }
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }

    jSamplingRates = env->NewIntArray(nAudioPort->num_sample_rates);
    if (jSamplingRates == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    if (nAudioPort->num_sample_rates) {
        env->SetIntArrayRegion(jSamplingRates, 0, nAudioPort->num_sample_rates,
                               (jint *)nAudioPort->sample_rates);
    }

    // count up how many masks are positional and indexed
    for(size_t index = 0; index < nAudioPort->num_channel_masks; index++) {
        const audio_channel_mask_t mask = nAudioPort->channel_masks[index];
        if (audio_channel_mask_get_representation(mask) == AUDIO_CHANNEL_REPRESENTATION_INDEX) {
            numIndexMasks++;
        } else {
            numPositionMasks++;
        }
    }

    jChannelMasks = env->NewIntArray(numPositionMasks);
    if (jChannelMasks == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    jChannelIndexMasks = env->NewIntArray(numIndexMasks);
    if (jChannelIndexMasks == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    useInMask = useInChannelMask(nAudioPort->type, nAudioPort->role);

    // put the masks in the output arrays
    for (size_t maskIndex = 0, posMaskIndex = 0, indexedMaskIndex = 0;
         maskIndex < nAudioPort->num_channel_masks; maskIndex++) {
        const audio_channel_mask_t mask = nAudioPort->channel_masks[maskIndex];
        if (audio_channel_mask_get_representation(mask) == AUDIO_CHANNEL_REPRESENTATION_INDEX) {
            jint jMask = audio_channel_mask_get_bits(mask);
            env->SetIntArrayRegion(jChannelIndexMasks, indexedMaskIndex++, 1, &jMask);
        } else {
            jint jMask = useInMask ? inChannelMaskFromNative(mask)
                                   : outChannelMaskFromNative(mask);
            env->SetIntArrayRegion(jChannelMasks, posMaskIndex++, 1, &jMask);
        }
    }

    // formats
    if (nAudioPort->num_formats != 0) {
        cFormats = new int[nAudioPort->num_formats];
        for (size_t index = 0; index < nAudioPort->num_formats; index++) {
            int format = audioFormatFromNative(nAudioPort->formats[index]);
            if (!hasFormat(cFormats, numUniqueFormats, format)) {
                cFormats[numUniqueFormats++] = format;
            }
        }
    }
    jFormats = env->NewIntArray(numUniqueFormats);
    if (jFormats == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    if (numUniqueFormats != 0) {
        env->SetIntArrayRegion(jFormats, 0, numUniqueFormats, cFormats);
    }

    // gains
    jGains = env->NewObjectArray(nAudioPort->num_gains,
                                          gAudioGainClass, NULL);
    if (jGains == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }

    for (size_t j = 0; j < nAudioPort->num_gains; j++) {
        audio_channel_mask_t nMask = nAudioPort->gains[j].channel_mask;
        jint jMask;
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

    jDeviceName = env->NewStringUTF(nAudioPort->name);

    if (nAudioPort->type == AUDIO_PORT_TYPE_DEVICE) {
        ALOGV("convertAudioPortFromNative is a device %08x", nAudioPort->ext.device.type);
        jstring jAddress = env->NewStringUTF(nAudioPort->ext.device.address);
        *jAudioPort = env->NewObject(gAudioDevicePortClass, gAudioDevicePortCstor,
                                     jHandle, jDeviceName,
                                     jSamplingRates, jChannelMasks, jChannelIndexMasks,
                                     jFormats, jGains,
                                     nAudioPort->ext.device.type, jAddress);
        env->DeleteLocalRef(jAddress);
    } else if (nAudioPort->type == AUDIO_PORT_TYPE_MIX) {
        ALOGV("convertAudioPortFromNative is a mix");
        *jAudioPort = env->NewObject(gAudioMixPortClass, gAudioMixPortCstor,
                                     jHandle, nAudioPort->ext.mix.handle,
                                     nAudioPort->role, jDeviceName,
                                     jSamplingRates, jChannelMasks, jChannelIndexMasks,
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

    jStatus = convertAudioPortConfigFromNative(env,
                                                       *jAudioPort,
                                                       &jAudioPortConfig,
                                                       &nAudioPort->active_config);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        goto exit;
    }

    env->SetObjectField(*jAudioPort, gAudioPortFields.mActiveConfig, jAudioPortConfig);

exit:
    if (jDeviceName != NULL) {
        env->DeleteLocalRef(jDeviceName);
    }
    if (jSamplingRates != NULL) {
        env->DeleteLocalRef(jSamplingRates);
    }
    if (jChannelMasks != NULL) {
        env->DeleteLocalRef(jChannelMasks);
    }
    if (jChannelIndexMasks != NULL) {
        env->DeleteLocalRef(jChannelIndexMasks);
    }
    if (cFormats != NULL) {
        delete[] cFormats;
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
    if (jAudioPortConfig != NULL) {
        env->DeleteLocalRef(jAudioPortConfig);
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
    jint jStatus;

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
        if (status != NO_ERROR) {
            ALOGE_IF(status != NO_ERROR, "AudioSystem::listAudioPorts error %d", status);
            break;
        }
        if (numPorts == 0) {
            jStatus = (jint)AUDIO_JAVA_SUCCESS;
            goto exit;
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

    jStatus = nativeToJavaStatus(status);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        goto exit;
    }

    for (size_t i = 0; i < numPorts; i++) {
        jobject jAudioPort = NULL;
        jStatus = convertAudioPortFromNative(env, &jAudioPort, &nPorts[i]);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        env->CallBooleanMethod(jPorts, gArrayListMethods.add, jAudioPort);
        if (jAudioPort != NULL) {
            env->DeleteLocalRef(jAudioPort);
        }
    }

exit:
    nGeneration = env->GetIntArrayElements(jGeneration, NULL);
    if (nGeneration == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
    } else {
        nGeneration[0] = generation1;
        env->ReleaseIntArrayElements(jGeneration, nGeneration, 0);
    }
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

    struct audio_patch nPatch = { .id = handle };

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

static jint
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
    return jStatus;
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
    jint jStatus;

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
        if (status != NO_ERROR) {
            ALOGE_IF(status != NO_ERROR, "listAudioPatches AudioSystem::listAudioPatches error %d",
                                      status);
            break;
        }
        if (numPatches == 0) {
            jStatus = (jint)AUDIO_JAVA_SUCCESS;
            goto exit;
        }

        nPatches = (struct audio_patch *)realloc(nPatches, numPatches * sizeof(struct audio_patch));

        status = AudioSystem::listAudioPatches(&numPatches,
                                               nPatches,
                                               &generation);
        ALOGV("listAudioPatches AudioSystem::listAudioPatches numPatches %d generation %d generation1 %d",
              numPatches, generation, generation1);

    } while (generation1 != generation && status == NO_ERROR);

    jStatus = nativeToJavaStatus(status);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        goto exit;
    }

    for (size_t i = 0; i < numPatches; i++) {
        jobject patchHandle = env->NewObject(gAudioHandleClass, gAudioHandleCstor,
                                                 nPatches[i].id);
        if (patchHandle == NULL) {
            jStatus = AUDIO_JAVA_ERROR;
            goto exit;
        }
        ALOGV("listAudioPatches patch %zu num_sources %d num_sinks %d",
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
            ALOGV("listAudioPatches patch %zu source %zu is a %s handle %d",
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
            ALOGV("listAudioPatches patch %zu sink %zu is a %s handle %d",
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

    nGeneration = env->GetIntArrayElements(jGeneration, NULL);
    if (nGeneration == NULL) {
        jStatus = AUDIO_JAVA_ERROR;
    } else {
        nGeneration[0] = generation1;
        env->ReleaseIntArrayElements(jGeneration, nGeneration, 0);
    }

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
    struct audio_port_config nAudioPortConfig = {};
    jint jStatus = convertAudioPortConfigToNative(env, &nAudioPortConfig, jAudioPortConfig, true);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    status_t status = AudioSystem::setAudioPortConfig(&nAudioPortConfig);
    ALOGV("AudioSystem::setAudioPortConfig() returned %d", status);
    jStatus = nativeToJavaStatus(status);
    return jStatus;
}

/**
 * Returns handle if the audio source is successfully started.
 */
static jint
android_media_AudioSystem_startAudioSource(JNIEnv *env, jobject clazz,
                                           jobject jAudioPortConfig,
                                           jobject jAudioAttributes)
{
    ALOGV("startAudioSource");
    if (jAudioPortConfig == NULL || jAudioAttributes == NULL) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jAudioPortConfig, gAudioPortConfigClass)) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    struct audio_port_config nAudioPortConfig = {};
    jint jStatus = convertAudioPortConfigToNativeWithDevicePort(env,
            &nAudioPortConfig, jAudioPortConfig, false);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    auto paa = JNIAudioAttributeHelper::makeUnique();
    jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jAudioAttributes, paa.get());
    if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    audio_port_handle_t handle;
    status_t status = AudioSystem::startAudioSource(&nAudioPortConfig, paa.get(), &handle);
    ALOGV("AudioSystem::startAudioSource() returned %d handle %d", status, handle);
    return handle > 0 ? handle : nativeToJavaStatus(status);
}

static jint
android_media_AudioSystem_stopAudioSource(JNIEnv *env, jobject clazz, jint handle)
{
    ALOGV("stopAudioSource");
    status_t status = AudioSystem::stopAudioSource(
            static_cast <audio_port_handle_t>(handle));
    ALOGV("AudioSystem::stopAudioSource() returned %d", status);
    return nativeToJavaStatus(status);
}

static void
android_media_AudioSystem_eventHandlerSetup(JNIEnv *env, jobject thiz, jobject weak_this)
{
    ALOGV("eventHandlerSetup");

    sp<JNIAudioPortCallback> callback = new JNIAudioPortCallback(env, thiz, weak_this);

    if (AudioSystem::addAudioPortCallback(callback) == NO_ERROR) {
        setJniCallback(env, thiz, callback);
    }
}

static void
android_media_AudioSystem_eventHandlerFinalize(JNIEnv *env, jobject thiz)
{
    ALOGV("eventHandlerFinalize");

    sp<JNIAudioPortCallback> callback = setJniCallback(env, thiz, 0);

    if (callback != 0) {
        AudioSystem::removeAudioPortCallback(callback);
    }
}

static jint
android_media_AudioSystem_getAudioHwSyncForSession(JNIEnv *env, jobject thiz, jint sessionId)
{
    return (jint) AudioSystem::getAudioHwSyncForSession((audio_session_t) sessionId);
}

static void
android_media_AudioSystem_registerDynPolicyCallback(JNIEnv *env, jobject thiz)
{
    AudioSystem::setDynPolicyCallback(android_media_AudioSystem_dyn_policy_callback);
}

static void
android_media_AudioSystem_registerRecordingCallback(JNIEnv *env, jobject thiz)
{
    AudioSystem::setRecordConfigCallback(android_media_AudioSystem_recording_callback);
}


static jint convertAudioMixToNative(JNIEnv *env,
                                    AudioMix *nAudioMix,
                                    const jobject jAudioMix)
{
    nAudioMix->mMixType = env->GetIntField(jAudioMix, gAudioMixFields.mMixType);
    nAudioMix->mRouteFlags = env->GetIntField(jAudioMix, gAudioMixFields.mRouteFlags);
    nAudioMix->mDeviceType = (audio_devices_t)
            env->GetIntField(jAudioMix, gAudioMixFields.mDeviceType);

    jstring jDeviceAddress = (jstring)env->GetObjectField(jAudioMix,
                                                           gAudioMixFields.mDeviceAddress);
    const char *nDeviceAddress = env->GetStringUTFChars(jDeviceAddress, NULL);
    nAudioMix->mDeviceAddress = String8(nDeviceAddress);
    env->ReleaseStringUTFChars(jDeviceAddress, nDeviceAddress);
    env->DeleteLocalRef(jDeviceAddress);

    nAudioMix->mCbFlags = env->GetIntField(jAudioMix, gAudioMixFields.mCallbackFlags);

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
        AudioMixMatchCriterion nCriterion;

        jobject jCriterion = env->GetObjectArrayElement(jCriteria, i);

        nCriterion.mRule = env->GetIntField(jCriterion, gAudioMixMatchCriterionFields.mRule);

        const uint32_t match_rule = nCriterion.mRule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
        case RULE_MATCH_UID:
            nCriterion.mValue.mUid = env->GetIntField(jCriterion,
                    gAudioMixMatchCriterionFields.mIntProp);
            break;
        case RULE_MATCH_ATTRIBUTE_USAGE:
        case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET: {
            jobject jAttributes = env->GetObjectField(jCriterion, gAudioMixMatchCriterionFields.mAttr);

            auto paa = JNIAudioAttributeHelper::makeUnique();
            jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jAttributes, paa.get());
            if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
                return jStatus;
            }
            if (match_rule == RULE_MATCH_ATTRIBUTE_USAGE) {
                nCriterion.mValue.mUsage = paa->usage;
            } else {
                nCriterion.mValue.mSource = paa->source;
            }
            env->DeleteLocalRef(jAttributes);
            }
            break;
        }

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

static jint android_media_AudioSystem_setUidDeviceAffinities(JNIEnv *env, jobject clazz,
        jint uid, jintArray deviceTypes, jobjectArray deviceAddresses) {
    if (deviceTypes == nullptr || deviceAddresses == nullptr) {
        return (jint) AUDIO_JAVA_BAD_VALUE;
    }
    jsize nb = env->GetArrayLength(deviceTypes);
    if (nb == 0 || nb != env->GetArrayLength(deviceAddresses)) {
        return (jint) AUDIO_JAVA_BAD_VALUE;
    }
    // retrieve all device types
    std::vector<audio_devices_t> deviceTypesVector;
    jint* typesPtr = nullptr;
    typesPtr = env->GetIntArrayElements(deviceTypes, 0);
    if (typesPtr == nullptr) {
        return (jint) AUDIO_JAVA_BAD_VALUE;
    }
    for (jint i = 0; i < nb; i++) {
        deviceTypesVector.push_back((audio_devices_t) typesPtr[i]);
    }

    // check each address is a string and add device type/address to list for device affinity
    Vector<AudioDeviceTypeAddr> deviceVector;
    jclass stringClass = FindClassOrDie(env, "java/lang/String");
    for (jint i = 0; i < nb; i++) {
        jobject addrJobj = env->GetObjectArrayElement(deviceAddresses, i);
        if (!env->IsInstanceOf(addrJobj, stringClass)) {
            return (jint) AUDIO_JAVA_BAD_VALUE;
        }
        String8 address = String8(env->GetStringUTFChars((jstring) addrJobj, NULL));
        AudioDeviceTypeAddr dev = AudioDeviceTypeAddr(typesPtr[i], address);
        deviceVector.add(dev);
    }
    env->ReleaseIntArrayElements(deviceTypes, typesPtr, 0);

    status_t status = AudioSystem::setUidDeviceAffinities((uid_t) uid, deviceVector);
    return (jint) nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_removeUidDeviceAffinities(JNIEnv *env, jobject clazz,
        jint uid) {

    //###
    status_t status = NO_ERROR;//AudioSystem::removeUidDeviceAffinities();
    return (jint) nativeToJavaStatus(status);
}


static jint
android_media_AudioSystem_systemReady(JNIEnv *env, jobject thiz)
{
    return nativeToJavaStatus(AudioSystem::systemReady());
}

static jfloat
android_media_AudioSystem_getStreamVolumeDB(JNIEnv *env, jobject thiz,
                                            jint stream, jint index, jint device)
{
    return (jfloat)AudioSystem::getStreamVolumeDB((audio_stream_type_t)stream,
                                                  (int)index,
                                                  (audio_devices_t)device);
}

static jboolean
android_media_AudioSystem_isOffloadSupported(JNIEnv *env, jobject thiz,
        jint encoding, jint sampleRate, jint channelMask, jint channelIndexMask)
{
    audio_offload_info_t format = AUDIO_INFO_INITIALIZER;
    format.format = (audio_format_t) audioFormatToNative(encoding);
    format.sample_rate = (uint32_t) sampleRate;
    format.channel_mask = nativeChannelMaskFromJavaChannelMasks(channelMask, channelIndexMask);
    format.stream_type = AUDIO_STREAM_MUSIC;
    format.has_video = false;
    format.is_streaming = false;
    // offload duration unknown at this point:
    // client side code cannot access "audio.offload.min.duration.secs" property to make a query
    // agnostic of duration, so using acceptable estimate of 2mn
    format.duration_us = 120 * 1000000;
    return AudioSystem::isOffloadSupported(format);
}

static jint
android_media_AudioSystem_getMicrophones(JNIEnv *env, jobject thiz, jobject jMicrophonesInfo)
{
    ALOGV("getMicrophones");

    if (jMicrophonesInfo == NULL) {
        ALOGE("jMicrophonesInfo NULL MicrophoneInfo ArrayList");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jMicrophonesInfo, gArrayListClass)) {
        ALOGE("getMicrophones not an arraylist");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    jint jStatus;
    std::vector<media::MicrophoneInfo> microphones;
    status_t status = AudioSystem::getMicrophones(&microphones);
    if (status != NO_ERROR) {
        ALOGE("AudioSystem::getMicrophones error %d", status);
        jStatus = nativeToJavaStatus(status);
        return jStatus;
    }
    if (microphones.size() == 0) {
        jStatus = (jint)AUDIO_JAVA_SUCCESS;
        return jStatus;
    }
    for (size_t i = 0; i < microphones.size(); i++) {
        jobject jMicrophoneInfo;
        jStatus = convertMicrophoneInfoFromNative(env, &jMicrophoneInfo, &microphones[i]);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        env->CallBooleanMethod(jMicrophonesInfo, gArrayListMethods.add, jMicrophoneInfo);
        env->DeleteLocalRef(jMicrophoneInfo);
    }

    return jStatus;
}

static jint
android_media_AudioSystem_getHwOffloadEncodingFormatsSupportedForA2DP(
                        JNIEnv *env, jobject thiz, jobject jEncodingFormatList)
{
    ALOGV("%s", __FUNCTION__);
    jint jStatus = AUDIO_JAVA_SUCCESS;
    if (!env->IsInstanceOf(jEncodingFormatList, gArrayListClass)) {
        ALOGE("%s: jEncodingFormatList not an ArrayList", __FUNCTION__);
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    std::vector<audio_format_t> encodingFormats;
    status_t status = AudioSystem::getHwOffloadEncodingFormatsSupportedForA2DP(
                          &encodingFormats);
    if (status != NO_ERROR) {
        ALOGE("%s: error %d", __FUNCTION__, status);
        jStatus = nativeToJavaStatus(status);
        return jStatus;
    }

    for (size_t i = 0; i < encodingFormats.size(); i++) {
        ScopedLocalRef<jobject> jEncodingFormat(
            env, env->NewObject(gIntegerClass, gIntegerCstor, encodingFormats[i]));
        env->CallBooleanMethod(jEncodingFormatList, gArrayListMethods.add,
                               jEncodingFormat.get());
    }
    return jStatus;
}

static jint
android_media_AudioSystem_getSurroundFormats(JNIEnv *env, jobject thiz,
                                             jobject jSurroundFormats, jboolean reported)
{
    ALOGV("getSurroundFormats");

    if (jSurroundFormats == NULL) {
        ALOGE("jSurroundFormats is NULL");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jSurroundFormats, gMapClass)) {
        ALOGE("getSurroundFormats not a map");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    jint jStatus;
    unsigned int numSurroundFormats = 0;
    audio_format_t *surroundFormats = NULL;
    bool *surroundFormatsEnabled = NULL;
    status_t status = AudioSystem::getSurroundFormats(
            &numSurroundFormats, surroundFormats, surroundFormatsEnabled, reported);
    if (status != NO_ERROR) {
        ALOGE_IF(status != NO_ERROR, "AudioSystem::getSurroundFormats error %d", status);
        jStatus = nativeToJavaStatus(status);
        goto exit;
    }
    if (numSurroundFormats == 0) {
        jStatus = (jint)AUDIO_JAVA_SUCCESS;
        goto exit;
    }
    surroundFormats = (audio_format_t *)calloc(numSurroundFormats, sizeof(audio_format_t));
    surroundFormatsEnabled = (bool *)calloc(numSurroundFormats, sizeof(bool));
    status = AudioSystem::getSurroundFormats(
            &numSurroundFormats, surroundFormats, surroundFormatsEnabled, reported);
    jStatus = nativeToJavaStatus(status);
    if (status != NO_ERROR) {
        ALOGE_IF(status != NO_ERROR, "AudioSystem::getSurroundFormats error %d", status);
        goto exit;
    }
    for (size_t i = 0; i < numSurroundFormats; i++) {
        jobject surroundFormat = env->NewObject(gIntegerClass, gIntegerCstor,
                                                audioFormatFromNative(surroundFormats[i]));
        jobject enabled = env->NewObject(gBooleanClass, gBooleanCstor, surroundFormatsEnabled[i]);
        env->CallObjectMethod(jSurroundFormats, gMapPut, surroundFormat, enabled);
        env->DeleteLocalRef(surroundFormat);
        env->DeleteLocalRef(enabled);
    }

exit:
    free(surroundFormats);
    free(surroundFormatsEnabled);
    return jStatus;
}

static jint
android_media_AudioSystem_setSurroundFormatEnabled(JNIEnv *env, jobject thiz,
                                                   jint audioFormat, jboolean enabled)
{
    status_t status = AudioSystem::setSurroundFormatEnabled(audioFormatToNative(audioFormat),
                                                            (bool)enabled);
    if (status != NO_ERROR) {
        ALOGE_IF(status != NO_ERROR, "AudioSystem::setSurroundFormatEnabled error %d", status);
    }
    return (jint)nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_get_FCC_8(JNIEnv *env, jobject thiz) {
    return FCC_8;
}

static jint
android_media_AudioSystem_setAssistantUid(JNIEnv *env, jobject thiz, jint uid)
{
    status_t status = AudioSystem::setAssistantUid(uid);
    return (jint)nativeToJavaStatus(status);
}

static jint
android_media_AudioSystem_setA11yServicesUids(JNIEnv *env, jobject thiz, jintArray uids) {
    std::vector<uid_t> nativeUidsVector;

    if (uids != nullptr) {
       jsize len = env->GetArrayLength(uids);

       if (len > 0) {
           int *nativeUids = nullptr;
           nativeUids = env->GetIntArrayElements(uids, 0);
           if (nativeUids != nullptr) {
               for (size_t i = 0; i < len; i++) {
                   nativeUidsVector.push_back(nativeUids[i]);
               }
               env->ReleaseIntArrayElements(uids, nativeUids, 0);
           }
       }
    }
    status_t status = AudioSystem::setA11yServicesUids(nativeUidsVector);
    return (jint)nativeToJavaStatus(status);
}

static jboolean
android_media_AudioSystem_isHapticPlaybackSupported(JNIEnv *env, jobject thiz)
{
    return AudioSystem::isHapticPlaybackSupported();
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMethods[] = {
    {"setParameters",        "(Ljava/lang/String;)I", (void *)android_media_AudioSystem_setParameters},
    {"getParameters",        "(Ljava/lang/String;)Ljava/lang/String;", (void *)android_media_AudioSystem_getParameters},
    {"muteMicrophone",      "(Z)I",     (void *)android_media_AudioSystem_muteMicrophone},
    {"isMicrophoneMuted",   "()Z",      (void *)android_media_AudioSystem_isMicrophoneMuted},
    {"isStreamActive",      "(II)Z",    (void *)android_media_AudioSystem_isStreamActive},
    {"isStreamActiveRemotely","(II)Z",  (void *)android_media_AudioSystem_isStreamActiveRemotely},
    {"isSourceActive",      "(I)Z",     (void *)android_media_AudioSystem_isSourceActive},
    {"newAudioSessionId",   "()I",      (void *)android_media_AudioSystem_newAudioSessionId},
    {"newAudioPlayerId",    "()I",      (void *)android_media_AudioSystem_newAudioPlayerId},
    {"setDeviceConnectionState", "(IILjava/lang/String;Ljava/lang/String;I)I", (void *)android_media_AudioSystem_setDeviceConnectionState},
    {"getDeviceConnectionState", "(ILjava/lang/String;)I",  (void *)android_media_AudioSystem_getDeviceConnectionState},
    {"handleDeviceConfigChange", "(ILjava/lang/String;Ljava/lang/String;I)I", (void *)android_media_AudioSystem_handleDeviceConfigChange},
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
    {"setMasterMono",       "(Z)I",     (void *)android_media_AudioSystem_setMasterMono},
    {"getMasterMono",       "()Z",      (void *)android_media_AudioSystem_getMasterMono},
    {"setMasterBalance",    "(F)I",     (void *)android_media_AudioSystem_setMasterBalance},
    {"getMasterBalance",    "()F",      (void *)android_media_AudioSystem_getMasterBalance},
    {"getDevicesForStream", "(I)I",     (void *)android_media_AudioSystem_getDevicesForStream},
    {"getPrimaryOutputSamplingRate", "()I", (void *)android_media_AudioSystem_getPrimaryOutputSamplingRate},
    {"getPrimaryOutputFrameCount",   "()I", (void *)android_media_AudioSystem_getPrimaryOutputFrameCount},
    {"getOutputLatency",    "(I)I",     (void *)android_media_AudioSystem_getOutputLatency},
    {"setLowRamDevice",     "(ZJ)I",    (void *)android_media_AudioSystem_setLowRamDevice},
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
    {"startAudioSource",    "(Landroid/media/AudioPortConfig;Landroid/media/AudioAttributes;)I",
                                            (void *)android_media_AudioSystem_startAudioSource},
    {"stopAudioSource",     "(I)I", (void *)android_media_AudioSystem_stopAudioSource},
    {"getAudioHwSyncForSession", "(I)I",
                                    (void *)android_media_AudioSystem_getAudioHwSyncForSession},
    {"registerPolicyMixes",    "(Ljava/util/ArrayList;Z)I",
                                            (void *)android_media_AudioSystem_registerPolicyMixes},
    {"setUidDeviceAffinities", "(I[I[Ljava/lang/String;)I",
                                        (void *)android_media_AudioSystem_setUidDeviceAffinities},
    {"removeUidDeviceAffinities", "(I)I",
                                        (void *)android_media_AudioSystem_removeUidDeviceAffinities},
    {"native_register_dynamic_policy_callback", "()V",
                                    (void *)android_media_AudioSystem_registerDynPolicyCallback},
    {"native_register_recording_callback", "()V",
                                    (void *)android_media_AudioSystem_registerRecordingCallback},
    {"systemReady", "()I", (void *)android_media_AudioSystem_systemReady},
    {"getStreamVolumeDB", "(III)F", (void *)android_media_AudioSystem_getStreamVolumeDB},
    {"native_is_offload_supported", "(IIII)Z", (void *)android_media_AudioSystem_isOffloadSupported},
    {"getMicrophones", "(Ljava/util/ArrayList;)I", (void *)android_media_AudioSystem_getMicrophones},
    {"getSurroundFormats", "(Ljava/util/Map;Z)I", (void *)android_media_AudioSystem_getSurroundFormats},
    {"setSurroundFormatEnabled", "(IZ)I", (void *)android_media_AudioSystem_setSurroundFormatEnabled},
    {"setAssistantUid", "(I)I", (void *)android_media_AudioSystem_setAssistantUid},
    {"setA11yServicesUids", "([I)I", (void *)android_media_AudioSystem_setA11yServicesUids},
    {"isHapticPlaybackSupported", "()Z", (void *)android_media_AudioSystem_isHapticPlaybackSupported},
    {"getHwOffloadEncodingFormatsSupportedForA2DP", "(Ljava/util/ArrayList;)I",
                    (void*)android_media_AudioSystem_getHwOffloadEncodingFormatsSupportedForA2DP},
};

static const JNINativeMethod gEventHandlerMethods[] = {
    {"native_setup",
        "(Ljava/lang/Object;)V",
        (void *)android_media_AudioSystem_eventHandlerSetup},
    {"native_finalize",
        "()V",
        (void *)android_media_AudioSystem_eventHandlerFinalize},
};

static const JNINativeMethod gGetFCC8Methods[] = {
    {"native_get_FCC_8", "()I", (void *)android_media_AudioSystem_get_FCC_8},
};

int register_android_media_AudioSystem(JNIEnv *env)
{
    // This needs to be done before hooking up methods AudioTrackRoutingProxy (below)
    RegisterMethodsOrDie(env, kClassPathName, gGetFCC8Methods, NELEM(gGetFCC8Methods));

    jclass arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gArrayListClass = MakeGlobalRefOrDie(env, arrayListClass);
    gArrayListMethods.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");
    gArrayListMethods.toArray = GetMethodIDOrDie(env, arrayListClass, "toArray", "()[Ljava/lang/Object;");

    jclass booleanClass = FindClassOrDie(env, "java/lang/Boolean");
    gBooleanClass = MakeGlobalRefOrDie(env, booleanClass);
    gBooleanCstor = GetMethodIDOrDie(env, booleanClass, "<init>", "(Z)V");

    jclass integerClass = FindClassOrDie(env, "java/lang/Integer");
    gIntegerClass = MakeGlobalRefOrDie(env, integerClass);
    gIntegerCstor = GetMethodIDOrDie(env, integerClass, "<init>", "(I)V");

    jclass mapClass = FindClassOrDie(env, "java/util/Map");
    gMapClass = MakeGlobalRefOrDie(env, mapClass);
    gMapPut = GetMethodIDOrDie(env, mapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jclass audioHandleClass = FindClassOrDie(env, "android/media/AudioHandle");
    gAudioHandleClass = MakeGlobalRefOrDie(env, audioHandleClass);
    gAudioHandleCstor = GetMethodIDOrDie(env, audioHandleClass, "<init>", "(I)V");
    gAudioHandleFields.mId = GetFieldIDOrDie(env, audioHandleClass, "mId", "I");

    jclass audioPortClass = FindClassOrDie(env, "android/media/AudioPort");
    gAudioPortClass = MakeGlobalRefOrDie(env, audioPortClass);
    gAudioPortCstor = GetMethodIDOrDie(env, audioPortClass, "<init>",
            "(Landroid/media/AudioHandle;ILjava/lang/String;[I[I[I[I[Landroid/media/AudioGain;)V");
    gAudioPortFields.mHandle = GetFieldIDOrDie(env, audioPortClass, "mHandle",
                                               "Landroid/media/AudioHandle;");
    gAudioPortFields.mRole = GetFieldIDOrDie(env, audioPortClass, "mRole", "I");
    gAudioPortFields.mGains = GetFieldIDOrDie(env, audioPortClass, "mGains",
                                              "[Landroid/media/AudioGain;");
    gAudioPortFields.mActiveConfig = GetFieldIDOrDie(env, audioPortClass, "mActiveConfig",
                                                     "Landroid/media/AudioPortConfig;");

    jclass audioPortConfigClass = FindClassOrDie(env, "android/media/AudioPortConfig");
    gAudioPortConfigClass = MakeGlobalRefOrDie(env, audioPortConfigClass);
    gAudioPortConfigCstor = GetMethodIDOrDie(env, audioPortConfigClass, "<init>",
            "(Landroid/media/AudioPort;IIILandroid/media/AudioGainConfig;)V");
    gAudioPortConfigFields.mPort = GetFieldIDOrDie(env, audioPortConfigClass, "mPort",
                                                   "Landroid/media/AudioPort;");
    gAudioPortConfigFields.mSamplingRate = GetFieldIDOrDie(env, audioPortConfigClass,
                                                           "mSamplingRate", "I");
    gAudioPortConfigFields.mChannelMask = GetFieldIDOrDie(env, audioPortConfigClass,
                                                          "mChannelMask", "I");
    gAudioPortConfigFields.mFormat = GetFieldIDOrDie(env, audioPortConfigClass, "mFormat", "I");
    gAudioPortConfigFields.mGain = GetFieldIDOrDie(env, audioPortConfigClass, "mGain",
                                                   "Landroid/media/AudioGainConfig;");
    gAudioPortConfigFields.mConfigMask = GetFieldIDOrDie(env, audioPortConfigClass, "mConfigMask",
                                                         "I");

    jclass audioDevicePortConfigClass = FindClassOrDie(env, "android/media/AudioDevicePortConfig");
    gAudioDevicePortConfigClass = MakeGlobalRefOrDie(env, audioDevicePortConfigClass);
    gAudioDevicePortConfigCstor = GetMethodIDOrDie(env, audioDevicePortConfigClass, "<init>",
            "(Landroid/media/AudioDevicePort;IIILandroid/media/AudioGainConfig;)V");

    jclass audioMixPortConfigClass = FindClassOrDie(env, "android/media/AudioMixPortConfig");
    gAudioMixPortConfigClass = MakeGlobalRefOrDie(env, audioMixPortConfigClass);
    gAudioMixPortConfigCstor = GetMethodIDOrDie(env, audioMixPortConfigClass, "<init>",
            "(Landroid/media/AudioMixPort;IIILandroid/media/AudioGainConfig;)V");

    jclass audioDevicePortClass = FindClassOrDie(env, "android/media/AudioDevicePort");
    gAudioDevicePortClass = MakeGlobalRefOrDie(env, audioDevicePortClass);
    gAudioDevicePortCstor = GetMethodIDOrDie(env, audioDevicePortClass, "<init>",
            "(Landroid/media/AudioHandle;Ljava/lang/String;[I[I[I[I[Landroid/media/AudioGain;ILjava/lang/String;)V");

    // When access AudioPort as AudioDevicePort
    gAudioPortFields.mType = GetFieldIDOrDie(env, audioDevicePortClass, "mType", "I");
    gAudioPortFields.mAddress = GetFieldIDOrDie(env, audioDevicePortClass, "mAddress",
            "Ljava/lang/String;");

    jclass audioMixPortClass = FindClassOrDie(env, "android/media/AudioMixPort");
    gAudioMixPortClass = MakeGlobalRefOrDie(env, audioMixPortClass);
    gAudioMixPortCstor = GetMethodIDOrDie(env, audioMixPortClass, "<init>",
            "(Landroid/media/AudioHandle;IILjava/lang/String;[I[I[I[I[Landroid/media/AudioGain;)V");

    jclass audioGainClass = FindClassOrDie(env, "android/media/AudioGain");
    gAudioGainClass = MakeGlobalRefOrDie(env, audioGainClass);
    gAudioGainCstor = GetMethodIDOrDie(env, audioGainClass, "<init>", "(IIIIIIIII)V");

    jclass audioGainConfigClass = FindClassOrDie(env, "android/media/AudioGainConfig");
    gAudioGainConfigClass = MakeGlobalRefOrDie(env, audioGainConfigClass);
    gAudioGainConfigCstor = GetMethodIDOrDie(env, audioGainConfigClass, "<init>",
                                             "(ILandroid/media/AudioGain;II[II)V");
    gAudioGainConfigFields.mIndex = GetFieldIDOrDie(env, gAudioGainConfigClass, "mIndex", "I");
    gAudioGainConfigFields.mMode = GetFieldIDOrDie(env, audioGainConfigClass, "mMode", "I");
    gAudioGainConfigFields.mChannelMask = GetFieldIDOrDie(env, audioGainConfigClass, "mChannelMask",
                                                          "I");
    gAudioGainConfigFields.mValues = GetFieldIDOrDie(env, audioGainConfigClass, "mValues", "[I");
    gAudioGainConfigFields.mRampDurationMs = GetFieldIDOrDie(env, audioGainConfigClass,
                                                             "mRampDurationMs", "I");

    jclass audioPatchClass = FindClassOrDie(env, "android/media/AudioPatch");
    gAudioPatchClass = MakeGlobalRefOrDie(env, audioPatchClass);
    gAudioPatchCstor = GetMethodIDOrDie(env, audioPatchClass, "<init>",
"(Landroid/media/AudioHandle;[Landroid/media/AudioPortConfig;[Landroid/media/AudioPortConfig;)V");
    gAudioPatchFields.mHandle = GetFieldIDOrDie(env, audioPatchClass, "mHandle",
                                                "Landroid/media/AudioHandle;");

    jclass eventHandlerClass = FindClassOrDie(env, kEventHandlerClassPathName);
    gAudioPortEventHandlerMethods.postEventFromNative = GetStaticMethodIDOrDie(
                                                    env, eventHandlerClass, "postEventFromNative",
                                                    "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    gEventHandlerFields.mJniCallback = GetFieldIDOrDie(env,
                                                    eventHandlerClass, "mJniCallback", "J");

    gAudioPolicyEventHandlerMethods.postDynPolicyEventFromNative =
            GetStaticMethodIDOrDie(env, env->FindClass(kClassPathName),
                    "dynamicPolicyCallbackFromNative", "(ILjava/lang/String;I)V");
    gAudioPolicyEventHandlerMethods.postRecordConfigEventFromNative =
            GetStaticMethodIDOrDie(env, env->FindClass(kClassPathName),
                    "recordingCallbackFromNative", "(IIIIIZ[I[Landroid/media/audiofx/AudioEffect$Descriptor;[Landroid/media/audiofx/AudioEffect$Descriptor;I)V");

    jclass audioMixClass = FindClassOrDie(env, "android/media/audiopolicy/AudioMix");
    gAudioMixClass = MakeGlobalRefOrDie(env, audioMixClass);
    gAudioMixFields.mRule = GetFieldIDOrDie(env, audioMixClass, "mRule",
                                                "Landroid/media/audiopolicy/AudioMixingRule;");
    gAudioMixFields.mFormat = GetFieldIDOrDie(env, audioMixClass, "mFormat",
                                                "Landroid/media/AudioFormat;");
    gAudioMixFields.mRouteFlags = GetFieldIDOrDie(env, audioMixClass, "mRouteFlags", "I");
    gAudioMixFields.mDeviceType = GetFieldIDOrDie(env, audioMixClass, "mDeviceSystemType", "I");
    gAudioMixFields.mDeviceAddress = GetFieldIDOrDie(env, audioMixClass, "mDeviceAddress",
                                                      "Ljava/lang/String;");
    gAudioMixFields.mMixType = GetFieldIDOrDie(env, audioMixClass, "mMixType", "I");
    gAudioMixFields.mCallbackFlags = GetFieldIDOrDie(env, audioMixClass, "mCallbackFlags", "I");

    jclass audioFormatClass = FindClassOrDie(env, "android/media/AudioFormat");
    gAudioFormatClass = MakeGlobalRefOrDie(env, audioFormatClass);
    gAudioFormatFields.mEncoding = GetFieldIDOrDie(env, audioFormatClass, "mEncoding", "I");
    gAudioFormatFields.mSampleRate = GetFieldIDOrDie(env, audioFormatClass, "mSampleRate", "I");
    gAudioFormatFields.mChannelMask = GetFieldIDOrDie(env, audioFormatClass, "mChannelMask", "I");

    jclass audioMixingRuleClass = FindClassOrDie(env, "android/media/audiopolicy/AudioMixingRule");
    gAudioMixingRuleClass = MakeGlobalRefOrDie(env, audioMixingRuleClass);
    gAudioMixingRuleFields.mCriteria = GetFieldIDOrDie(env, audioMixingRuleClass, "mCriteria",
                                                       "Ljava/util/ArrayList;");

    jclass audioMixMatchCriterionClass =
                FindClassOrDie(env, "android/media/audiopolicy/AudioMixingRule$AudioMixMatchCriterion");
    gAudioMixMatchCriterionClass = MakeGlobalRefOrDie(env,audioMixMatchCriterionClass);
    gAudioMixMatchCriterionFields.mAttr = GetFieldIDOrDie(env, audioMixMatchCriterionClass, "mAttr",
                                                       "Landroid/media/AudioAttributes;");
    gAudioMixMatchCriterionFields.mIntProp = GetFieldIDOrDie(env, audioMixMatchCriterionClass, "mIntProp",
                                                       "I");
    gAudioMixMatchCriterionFields.mRule = GetFieldIDOrDie(env, audioMixMatchCriterionClass, "mRule",
                                                       "I");
    // AudioTrackRoutingProxy methods
    gClsAudioTrackRoutingProxy =
            android::FindClassOrDie(env, "android/media/AudioTrackRoutingProxy");
    // make sure this reference doesn't get deleted
    gClsAudioTrackRoutingProxy = (jclass)env->NewGlobalRef(gClsAudioTrackRoutingProxy);

    gMidAudioTrackRoutingProxy_ctor =
            android::GetMethodIDOrDie(env, gClsAudioTrackRoutingProxy, "<init>", "(J)V");
    gMidAudioTrackRoutingProxy_release =
            android::GetMethodIDOrDie(env, gClsAudioTrackRoutingProxy, "native_release", "()V");

    // AudioRecordRoutingProxy
    gClsAudioRecordRoutingProxy =
            android::FindClassOrDie(env, "android/media/AudioRecordRoutingProxy");
    // make sure this reference doesn't get deleted
    gClsAudioRecordRoutingProxy = (jclass)env->NewGlobalRef(gClsAudioRecordRoutingProxy);

    gMidAudioRecordRoutingProxy_ctor =
            android::GetMethodIDOrDie(env, gClsAudioRecordRoutingProxy, "<init>", "(J)V");
    gMidAudioRecordRoutingProxy_release =
            android::GetMethodIDOrDie(env, gClsAudioRecordRoutingProxy, "native_release", "()V");

    AudioSystem::setErrorCallback(android_media_AudioSystem_error_callback);

    RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
    return RegisterMethodsOrDie(env, kEventHandlerClassPathName, gEventHandlerMethods,
                                NELEM(gEventHandlerMethods));
}
