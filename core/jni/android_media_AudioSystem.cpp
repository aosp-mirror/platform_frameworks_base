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

#include <atomic>
#define LOG_TAG "AudioSystem-JNI"
#include <android/binder_ibinder_jni.h>
#include <android/binder_libbinder.h>
#include <android/media/AudioVibratorInfo.h>
#include <android/media/INativeSpatializerCallback.h>
#include <android/media/ISpatializer.h>
#include <android/media/audio/common/AudioConfigBase.h>
#include <android_media_audiopolicy.h>
#include <android_os_Parcel.h>
#include <audiomanager/AudioManager.h>
#include <android-base/properties.h>
#include <binder/IBinder.h>
#include <jni.h>
#include <media/AidlConversion.h>
#include <media/AudioContainers.h>
#include <media/AudioPolicy.h>
#include <media/AudioSystem.h>
#include <mediautils/jthread.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/jni_macros.h>
#include <system/audio.h>
#include <system/audio_policy.h>
#include <sys/system_properties.h>
#include <utils/Log.h>

#include <optional>
#include <sstream>
#include <memory>
#include <vector>

#include "android_media_AudioAttributes.h"
#include "android_media_AudioDescriptor.h"
#include "android_media_AudioDeviceAttributes.h"
#include "android_media_AudioEffectDescriptor.h"
#include "android_media_AudioErrors.h"
#include "android_media_AudioFormat.h"
#include "android_media_AudioMixerAttributes.h"
#include "android_media_AudioProfile.h"
#include "android_media_MicrophoneInfo.h"
#include "android_media_JNIUtils.h"
#include "android_util_Binder.h"
#include "core_jni_helpers.h"

// ----------------------------------------------------------------------------

namespace audio_flags = android::media::audiopolicy;

using namespace android;
using media::audio::common::AudioConfigBase;

static const char* const kClassPathName = "android/media/AudioSystem";

static jclass gArrayListClass;
static struct {
    jmethodID cstor;
    jmethodID add;
    jmethodID toArray;
} gArrayListMethods;

static jclass gIntArrayClass;
static struct {
    jmethodID add;
} gIntArrayMethods;

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
static jmethodID gAudioMixCstor;
static struct {
    jfieldID mRule;
    jfieldID mFormat;
    jfieldID mRouteFlags;
    jfieldID mDeviceType;
    jfieldID mDeviceAddress;
    jfieldID mMixType;
    jfieldID mCallbackFlags;
    jfieldID mToken;
    jfieldID mVirtualDeviceId;
} gAudioMixFields;

static jclass gAudioFormatClass;
static jmethodID gAudioFormatCstor;
static struct {
    jfieldID    mEncoding;
    jfieldID    mSampleRate;
    jfieldID    mChannelMask;
    jfieldID mChannelIndexMask;
    // other fields unused by JNI
} gAudioFormatFields;

static jclass gAudioAttributesClass;
static jmethodID gAudioAttributesCstor;
static struct {
    jfieldID mSource;
    jfieldID mUsage;
} gAudioAttributesFields;

static jclass gAudioMixingRuleClass;
static jmethodID gAudioMixingRuleCstor;
static struct {
    jfieldID    mCriteria;
    jfieldID    mAllowPrivilegedPlaybackCapture;
    jfieldID    mVoiceCommunicationCaptureAllowed;
    // other fields unused by JNI
} gAudioMixingRuleFields;

static jclass gAudioMixMatchCriterionClass;
static jmethodID gAudioMixMatchCriterionAttrCstor;
static jmethodID gAudioMixMatchCriterionIntPropCstor;
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
    jmethodID postRoutingUpdatedFromNative;
    jmethodID postVolRangeInitReqFromNative;
} gAudioPolicyEventHandlerMethods;

jclass gListClass;
static struct {
    jmethodID add;
    jmethodID get;
    jmethodID size;
} gListMethods;

static jclass gAudioDescriptorClass;
static jmethodID gAudioDescriptorCstor;

//
// JNI Initialization for OpenSLES routing
//
jmethodID gMidAudioTrackRoutingProxy_ctor;
jmethodID gMidAudioTrackRoutingProxy_release;
jmethodID gMidAudioRecordRoutingProxy_ctor;
jmethodID gMidAudioRecordRoutingProxy_release;

jclass gClsAudioTrackRoutingProxy;
jclass gClsAudioRecordRoutingProxy;

jclass gAudioProfileClass;
jmethodID gAudioProfileCstor;
static struct {
    jfieldID mSamplingRates;
    jfieldID mChannelMasks;
    jfieldID mChannelIndexMasks;
    jfieldID mEncapsulationType;
    jfieldID mMixerBehaviors;
} gAudioProfileFields;

jclass gVibratorClass;
static struct {
    jmethodID getId;
    jmethodID getResonantFrequency;
    jmethodID getQFactor;
    jmethodID getMaxAmplitude;
} gVibratorMethods;

jclass gAudioMixerAttributesClass;
jmethodID gAudioMixerAttributesCstor;
static struct {
    jfieldID mFormat;
    jfieldID mMixerBehavior;
} gAudioMixerAttributesField;

static struct {
    jclass clazz;
    jmethodID run;
} gRunnableClassInfo;

static JavaVM* gVm;

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

// Keep sync with AudioFormat.java
#define AUDIO_FORMAT_HAS_PROPERTY_ENCODING 0x1
#define AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE 0x2
#define AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK 0x4
#define AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK 0x8

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
    mClass = static_cast<jclass>(env->NewGlobalRef(clazz));

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
    sp<JNIAudioPortCallback> old = reinterpret_cast<JNIAudioPortCallback *>(
            env->GetLongField(thiz, gEventHandlerFields.mJniCallback));
    if (callback.get()) {
        callback->incStrong(reinterpret_cast<void *>(setJniCallback));
    }
    if (old != 0) {
        old->decStrong(reinterpret_cast<void *>(setJniCallback));
    }
    env->SetLongField(thiz, gEventHandlerFields.mJniCallback,
                      reinterpret_cast<jlong>(callback.get()));
    return old;
}

#define check_AudioSystem_Command(...) _check_AudioSystem_Command(__func__, __VA_ARGS__)

static int _check_AudioSystem_Command(const char *caller, status_t status,
                                      std::vector<status_t> ignoredErrors = {}) {
    int jniStatus = kAudioStatusOk;
    switch (status) {
    case DEAD_OBJECT:
        jniStatus = kAudioStatusMediaServerDied;
        break;
    case NO_ERROR:
        break;
    default:
        if (std::find(begin(ignoredErrors), end(ignoredErrors), status) == end(ignoredErrors)) {
            jniStatus = kAudioStatusError;
        }
        break;
    }
    ALOGE_IF(jniStatus != kAudioStatusOk, "Command failed for %s: %d", caller, status);
    return jniStatus;
}

static jint getVectorOfAudioDeviceTypeAddr(JNIEnv *env, jintArray deviceTypes,
                                           jobjectArray deviceAddresses,
                                           AudioDeviceTypeAddrVector &audioDeviceTypeAddrVector) {
    if (deviceTypes == nullptr || deviceAddresses == nullptr) {
    return AUDIO_JAVA_BAD_VALUE;
    }
    jsize deviceCount = env->GetArrayLength(deviceTypes);
    if (deviceCount == 0 || deviceCount != env->GetArrayLength(deviceAddresses)) {
    return AUDIO_JAVA_BAD_VALUE;
    }
    // retrieve all device types
    std::vector<audio_devices_t> deviceTypesVector;
    jint *typesPtr = nullptr;
    typesPtr = env->GetIntArrayElements(deviceTypes, 0);
    if (typesPtr == nullptr) {
    return AUDIO_JAVA_BAD_VALUE;
    }
    for (jint i = 0; i < deviceCount; i++) {
    deviceTypesVector.push_back(static_cast<audio_devices_t>(typesPtr[i]));
    }
    // check each address is a string and add device type/address to list
    jclass stringClass = FindClassOrDie(env, "java/lang/String");
    for (jint i = 0; i < deviceCount; i++) {
        jobject addrJobj = env->GetObjectArrayElement(deviceAddresses, i);
        if (!env->IsInstanceOf(addrJobj, stringClass)) {
        return AUDIO_JAVA_BAD_VALUE;
        }
        const char *address = env->GetStringUTFChars(static_cast<jstring>(addrJobj), NULL);
        AudioDeviceTypeAddr dev =
                AudioDeviceTypeAddr(static_cast<audio_devices_t>(typesPtr[i]), address);
        audioDeviceTypeAddrVector.push_back(dev);
        env->ReleaseStringUTFChars(static_cast<jstring>(addrJobj), address);
    }
    env->ReleaseIntArrayElements(deviceTypes, typesPtr, 0);

    return NO_ERROR;
}

static jint
android_media_AudioSystem_muteMicrophone(JNIEnv *env, jobject thiz, jboolean on)
{
    return check_AudioSystem_Command(AudioSystem::muteMicrophone(on));
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
    AudioSystem::isStreamActive(static_cast<audio_stream_type_t>(stream), &state, inPastMs);
    return state;
}

static jboolean
android_media_AudioSystem_isStreamActiveRemotely(JNIEnv *env, jobject thiz, jint stream,
        jint inPastMs)
{
    bool state = false;
    AudioSystem::isStreamActiveRemotely(static_cast<audio_stream_type_t>(stream), &state, inPastMs);
    return state;
}

static jboolean
android_media_AudioSystem_isSourceActive(JNIEnv *env, jobject thiz, jint source)
{
    bool state = false;
    AudioSystem::isSourceActive(static_cast<audio_source_t>(source), &state);
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
    int id = AudioSystem::newAudioUniqueId(AUDIO_UNIQUE_ID_USE_CLIENT);
    return id != AUDIO_UNIQUE_ID_ALLOCATE ? id : PLAYER_PIID_INVALID;
}

static jint
android_media_AudioSystem_newAudioRecorderId(JNIEnv *env, jobject thiz)
{
    int id = AudioSystem::newAudioUniqueId(AUDIO_UNIQUE_ID_USE_CLIENT);
    return id != AUDIO_UNIQUE_ID_ALLOCATE ? id : RECORD_RIID_INVALID;
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
    return check_AudioSystem_Command(AudioSystem::setParameters(c_keyValuePairs8));
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
    return env->NewStringUTF(AudioSystem::getParameters(c_keys8).c_str());
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
    const char *regIdString = regId.c_str();
    jstring regIdJString = env->NewStringUTF(regIdString);

    env->CallStaticVoidMethod(clazz, gAudioPolicyEventHandlerMethods.postDynPolicyEventFromNative,
                              event, regIdJString, val);

    const char *regIdJChars = env->GetStringUTFChars(regIdJString, NULL);
    env->ReleaseStringUTFChars(regIdJString, regIdJChars);
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
    recParamData[0] = audioFormatFromNative(clientConfig->format);
    // FIXME this doesn't support index-based masks
    recParamData[1] = inChannelMaskFromNative(clientConfig->channel_mask);
    recParamData[2] = clientConfig->sample_rate;
    recParamData[3] = audioFormatFromNative(deviceConfig->format);
    // FIXME this doesn't support index-based masks
    recParamData[4] = inChannelMaskFromNative(deviceConfig->channel_mask);
    recParamData[5] = deviceConfig->sample_rate;
    recParamData[6] = patchHandle;
    env->SetIntArrayRegion(recParamArray, 0, REC_PARAM_SIZE, recParamData);

    jobjectArray jClientEffects;
    convertAudioEffectDescriptorVectorFromNative(env, &jClientEffects, clientEffects);

    jobjectArray jEffects;
    convertAudioEffectDescriptorVectorFromNative(env, &jEffects, effects);

    // callback into java
    jclass clazz = env->FindClass(kClassPathName);

    env->CallStaticVoidMethod(clazz,
                              gAudioPolicyEventHandlerMethods.postRecordConfigEventFromNative,
                              event, clientInfo->riid, clientInfo->uid, clientInfo->session,
                              clientInfo->source, clientInfo->port_id, clientInfo->silenced,
                              recParamArray, jClientEffects, jEffects, source);
    env->DeleteLocalRef(clazz);
    env->DeleteLocalRef(recParamArray);
    env->DeleteLocalRef(jClientEffects);
    env->DeleteLocalRef(jEffects);
}

static void
android_media_AudioSystem_routing_callback()
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }

    // callback into java
    jclass clazz = env->FindClass(kClassPathName);
    env->CallStaticVoidMethod(clazz,
                              gAudioPolicyEventHandlerMethods.postRoutingUpdatedFromNative);
    env->DeleteLocalRef(clazz);
}

static void android_media_AudioSystem_vol_range_init_req_callback()
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }

    // callback into java
    jclass clazz = env->FindClass(kClassPathName);
    env->CallStaticVoidMethod(clazz,
                              gAudioPolicyEventHandlerMethods.postVolRangeInitReqFromNative);
    env->DeleteLocalRef(clazz);
}

static jint android_media_AudioSystem_setDeviceConnectionState(JNIEnv *env, jobject thiz,
                                                               jint state, jobject jParcel,
                                                               jint codec) {
    int status;
    if (Parcel *parcel = parcelForJavaObject(env, jParcel); parcel != nullptr) {
        android::media::audio::common::AudioPort port{};
        if (status_t statusOfParcel = port.readFromParcel(parcel); statusOfParcel == OK) {
        status = check_AudioSystem_Command(
                AudioSystem::setDeviceConnectionState(static_cast<audio_policy_dev_state_t>(state),
                                                      port, static_cast<audio_format_t>(codec)));
        } else {
            ALOGE("Failed to read from parcel: %s", statusToString(statusOfParcel).c_str());
            status = kAudioStatusError;
        }
    } else {
        ALOGE("Failed to retrieve the native parcel from Java parcel");
        status = kAudioStatusError;
    }
    return status;
}

static jint
android_media_AudioSystem_getDeviceConnectionState(JNIEnv *env, jobject thiz, jint device, jstring device_address)
{
    const char *c_address = env->GetStringUTFChars(device_address, NULL);
    int state = static_cast<int>(
            AudioSystem::getDeviceConnectionState(static_cast<audio_devices_t>(device), c_address));
    env->ReleaseStringUTFChars(device_address, c_address);
    return state;
}

static jint
android_media_AudioSystem_handleDeviceConfigChange(JNIEnv *env, jobject thiz, jint device, jstring device_address, jstring device_name,
                                                   jint codec)
{
    const char *c_address = env->GetStringUTFChars(device_address, NULL);
    const char *c_name = env->GetStringUTFChars(device_name, NULL);
    int status = check_AudioSystem_Command(
            AudioSystem::handleDeviceConfigChange(static_cast<audio_devices_t>(device), c_address,
                                                  c_name, static_cast<audio_format_t>(codec)));
    env->ReleaseStringUTFChars(device_address, c_address);
    env->ReleaseStringUTFChars(device_name, c_name);
    return status;
}

static jint android_media_AudioSystem_setPhoneState(JNIEnv *env, jobject thiz, jint state,
                                                    jint uid) {
    return check_AudioSystem_Command(
            AudioSystem::setPhoneState(static_cast<audio_mode_t>(state), static_cast<uid_t>(uid)));
}

static jint
android_media_AudioSystem_setForceUse(JNIEnv *env, jobject thiz, jint usage, jint config)
{
    return check_AudioSystem_Command(
            AudioSystem::setForceUse(static_cast<audio_policy_force_use_t>(usage),
                                     static_cast<audio_policy_forced_cfg_t>(config)));
}

static jint
android_media_AudioSystem_getForceUse(JNIEnv *env, jobject thiz, jint usage)
{
    return static_cast<jint>(
            AudioSystem::getForceUse(static_cast<audio_policy_force_use_t>(usage)));
}

static jint android_media_AudioSystem_setDeviceAbsoluteVolumeEnabled(JNIEnv *env, jobject thiz,
                                                                     jint device, jstring address,
                                                                     jboolean enabled,
                                                                     jint stream) {
    const char *c_address = env->GetStringUTFChars(address, nullptr);
    int state = check_AudioSystem_Command(
            AudioSystem::setDeviceAbsoluteVolumeEnabled(static_cast<audio_devices_t>(device),
                                                        c_address, enabled,
                                                        static_cast<audio_stream_type_t>(stream)));
    env->ReleaseStringUTFChars(address, c_address);
    return state;
}

static jint
android_media_AudioSystem_initStreamVolume(JNIEnv *env, jobject thiz, jint stream, jint indexMin, jint indexMax)
{
    return check_AudioSystem_Command(
            AudioSystem::initStreamVolume(static_cast<audio_stream_type_t>(stream), indexMin,
                                          indexMax));
}

static jint
android_media_AudioSystem_setStreamVolumeIndex(JNIEnv *env,
                                               jobject thiz,
                                               jint stream,
                                               jint index,
                                               jint device)
{
    return check_AudioSystem_Command(
            AudioSystem::setStreamVolumeIndex(static_cast<audio_stream_type_t>(stream), index,
                                              static_cast<audio_devices_t>(device)));
}

static jint
android_media_AudioSystem_getStreamVolumeIndex(JNIEnv *env,
                                               jobject thiz,
                                               jint stream,
                                               jint device)
{
    int index;
    if (AudioSystem::getStreamVolumeIndex(static_cast<audio_stream_type_t>(stream), &index,
                                          static_cast<audio_devices_t>(device)) != NO_ERROR) {
        index = -1;
    }
    return index;
}

static jint
android_media_AudioSystem_setVolumeIndexForAttributes(JNIEnv *env,
                                                      jobject thiz,
                                                      jobject jaa,
                                                      jint index,
                                                      jint device)
{
    // read the AudioAttributes values
    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    return check_AudioSystem_Command(
            AudioSystem::setVolumeIndexForAttributes(*(paa.get()), index,
                                                     static_cast<audio_devices_t>(device)));
}

static jint
android_media_AudioSystem_getVolumeIndexForAttributes(JNIEnv *env,
                                                      jobject thiz,
                                                      jobject jaa,
                                                      jint device)
{
    // read the AudioAttributes values
    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    int index;
    if (AudioSystem::getVolumeIndexForAttributes(*(paa.get()), index,
                                                 static_cast<audio_devices_t>(device)) !=
        NO_ERROR) {
        index = -1;
    }
    return index;
}

static jint
android_media_AudioSystem_getMinVolumeIndexForAttributes(JNIEnv *env,
                                                         jobject thiz,
                                                         jobject jaa)
{
    // read the AudioAttributes values
    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    int index;
    if (AudioSystem::getMinVolumeIndexForAttributes(*(paa.get()), index)
            != NO_ERROR) {
        index = -1;
    }
    return index;
}

static jint
android_media_AudioSystem_getMaxVolumeIndexForAttributes(JNIEnv *env,
                                                         jobject thiz,
                                                         jobject jaa)
{
    // read the AudioAttributes values
    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    int index;
    if (AudioSystem::getMaxVolumeIndexForAttributes(*(paa.get()), index)
            != NO_ERROR) {
        index = -1;
    }
    return index;
}

static jint
android_media_AudioSystem_setMasterVolume(JNIEnv *env, jobject thiz, jfloat value)
{
    return check_AudioSystem_Command(AudioSystem::setMasterVolume(value));
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
    return check_AudioSystem_Command(AudioSystem::setMasterMute(mute));
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
    return check_AudioSystem_Command(AudioSystem::setMasterMono(mono));
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
    return check_AudioSystem_Command(AudioSystem::setMasterBalance(balance));
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
android_media_AudioSystem_getPrimaryOutputSamplingRate(JNIEnv *env, jobject clazz)
{
    return AudioSystem::getPrimaryOutputSamplingRate();
}

static jint
android_media_AudioSystem_getPrimaryOutputFrameCount(JNIEnv *env, jobject clazz)
{
    return AudioSystem::getPrimaryOutputFrameCount();
}

static jint
android_media_AudioSystem_getOutputLatency(JNIEnv *env, jobject clazz, jint stream)
{
    uint32_t afLatency;
    if (AudioSystem::getOutputLatency(&afLatency, static_cast<audio_stream_type_t>(stream)) !=
        NO_ERROR) {
        afLatency = -1;
    }
    return afLatency;
}

static jint
android_media_AudioSystem_setLowRamDevice(
        JNIEnv *env, jobject clazz, jboolean isLowRamDevice, jlong totalMemory)
{
    return AudioSystem::setLowRamDevice(isLowRamDevice, totalMemory);
}

static jint
android_media_AudioSystem_checkAudioFlinger(JNIEnv *env, jobject clazz)
{
    return check_AudioSystem_Command(AudioSystem::checkAudioFlinger());
}

static void android_media_AudioSystem_setAudioFlingerBinder(JNIEnv *env, jobject clazz,
                                                            jobject audioFlinger) {
    AudioSystem::setAudioFlingerBinder(android::ibinderForJavaObject(env, audioFlinger));
}

static void convertAudioGainConfigToNative(JNIEnv *env,
                                               struct audio_gain_config *nAudioGainConfig,
                                               const jobject jAudioGainConfig,
                                               bool useInMask)
{
    nAudioGainConfig->index = env->GetIntField(jAudioGainConfig, gAudioGainConfigFields.mIndex);
    nAudioGainConfig->mode = static_cast<audio_gain_mode_t>(
            env->GetIntField(jAudioGainConfig, gAudioGainConfigFields.mMode));
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
    jintArray jValues = static_cast<jintArray>(
            env->GetObjectField(jAudioGainConfig, gAudioGainConfigFields.mValues));
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
    nAudioPortConfig->role =
            static_cast<audio_port_role_t>(env->GetIntField(jAudioPort, gAudioPortFields.mRole));
    if (env->IsInstanceOf(jAudioPort, gAudioDevicePortClass)) {
        nAudioPortConfig->type = AUDIO_PORT_TYPE_DEVICE;
    } else if (env->IsInstanceOf(jAudioPort, gAudioMixPortClass)) {
        nAudioPortConfig->type = AUDIO_PORT_TYPE_MIX;
    } else {
        env->DeleteLocalRef(jAudioPort);
        env->DeleteLocalRef(jHandle);
        return AUDIO_JAVA_ERROR;
    }
    ALOGV("convertAudioPortConfigToNative handle %d role %d type %d",
          nAudioPortConfig->id, nAudioPortConfig->role, nAudioPortConfig->type);

    unsigned int configMask = 0;

    nAudioPortConfig->sample_rate = env->GetIntField(jAudioPortConfig,
                                                     gAudioPortConfigFields.mSamplingRate);
    if (nAudioPortConfig->sample_rate != 0) {
        configMask |= AUDIO_PORT_CONFIG_SAMPLE_RATE;
    }

    bool useInMask = audio_port_config_has_input_direction(nAudioPortConfig);
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
    return AUDIO_JAVA_SUCCESS;
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
        return AUDIO_JAVA_BAD_VALUE;
    }

    jobject jAudioDevicePort = env->GetObjectField(jAudioPortConfig,
            gAudioPortConfigFields.mPort);
    nAudioPortConfig->ext.device.type = static_cast<audio_devices_t>(
            env->GetIntField(jAudioDevicePort, gAudioPortFields.mType));
    jstring jDeviceAddress =
            static_cast<jstring>(env->GetObjectField(jAudioDevicePort, gAudioPortFields.mAddress));
    const char *nDeviceAddress = env->GetStringUTFChars(jDeviceAddress, NULL);
    strncpy(nAudioPortConfig->ext.device.address,
            nDeviceAddress, AUDIO_DEVICE_MAX_ADDRESS_LEN - 1);
    env->ReleaseStringUTFChars(jDeviceAddress, nDeviceAddress);
    env->DeleteLocalRef(jDeviceAddress);
    env->DeleteLocalRef(jAudioDevicePort);
    return jStatus;
}

static jint convertAudioPortConfigFromNative(JNIEnv *env, ScopedLocalRef<jobject> *jAudioPort,
                                             ScopedLocalRef<jobject> *jAudioPortConfig,
                                             const struct audio_port_config *nAudioPortConfig) {
    jintArray jGainValues;
    bool audioportCreated = false;

    ALOGV("convertAudioPortConfigFromNative jAudioPort %p", jAudioPort);

    if (*jAudioPort == nullptr) {
        ScopedLocalRef<jobject> jHandle(env,
                                        env->NewObject(gAudioHandleClass, gAudioHandleCstor,
                                                       nAudioPortConfig->id));

        ALOGV("convertAudioPortConfigFromNative handle %d is a %s", nAudioPortConfig->id,
              nAudioPortConfig->type == AUDIO_PORT_TYPE_DEVICE ? "device" : "mix");

        if (jHandle == NULL) {
            return AUDIO_JAVA_ERROR;
        }
        // create placeholder port and port config objects with just the correct handle
        // and configuration data. The actual AudioPortConfig objects will be
        // constructed by java code with correct class type (device, mix etc...)
        // and reference to AudioPort instance in this client
        jAudioPort->reset(env->NewObject(gAudioPortClass, gAudioPortCstor,
                                         jHandle.get(), // handle
                                         0,             // role
                                         nullptr,       // name
                                         nullptr,       // samplingRates
                                         nullptr,       // channelMasks
                                         nullptr,       // channelIndexMasks
                                         nullptr,       // formats
                                         nullptr));     // gains

        if (*jAudioPort == nullptr) {
            return AUDIO_JAVA_ERROR;
        }
        ALOGV("convertAudioPortConfigFromNative jAudioPort created for handle %d",
              nAudioPortConfig->id);

        audioportCreated = true;
    }

    ScopedLocalRef<jobject> jAudioGainConfig(env, nullptr);
    ScopedLocalRef<jobject> jAudioGain(env, nullptr);

    bool useInMask = audio_port_config_has_input_direction(nAudioPortConfig);

    audio_channel_mask_t nMask;
    jint jMask;

    int gainIndex = (nAudioPortConfig->config_mask & AUDIO_PORT_CONFIG_GAIN)
            ? nAudioPortConfig->gain.index
            : -1;
    if (gainIndex >= 0) {
        ALOGV("convertAudioPortConfigFromNative gain found with index %d mode %x",
              gainIndex, nAudioPortConfig->gain.mode);
        if (audioportCreated) {
            ALOGV("convertAudioPortConfigFromNative creating gain");
            jAudioGain.reset(env->NewObject(gAudioGainClass, gAudioGainCstor, gainIndex, 0 /*mode*/,
                                            0 /*channelMask*/, 0 /*minValue*/, 0 /*maxValue*/,
                                            0 /*defaultValue*/, 0 /*stepValue*/,
                                            0 /*rampDurationMinMs*/, 0 /*rampDurationMaxMs*/));
            if (jAudioGain == NULL) {
                ALOGV("convertAudioPortConfigFromNative creating gain FAILED");
                return AUDIO_JAVA_ERROR;
            }
        } else {
            ALOGV("convertAudioPortConfigFromNative reading gain from port");
            ScopedLocalRef<jobjectArray>
                    jGains(env,
                           static_cast<jobjectArray>(env->GetObjectField(jAudioPort->get(),
                                                                         gAudioPortFields.mGains)));
            if (jGains == NULL) {
                ALOGV("convertAudioPortConfigFromNative could not get gains from port");
                return AUDIO_JAVA_ERROR;
            }
            jAudioGain.reset(env->GetObjectArrayElement(jGains.get(), gainIndex));
            if (jAudioGain == NULL) {
                ALOGV("convertAudioPortConfigFromNative could not get gain at index %d", gainIndex);
                return AUDIO_JAVA_ERROR;
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
            return AUDIO_JAVA_ERROR;
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

        jAudioGainConfig.reset(env->NewObject(gAudioGainConfigClass, gAudioGainConfigCstor,
                                              gainIndex, jAudioGain.get(),
                                              nAudioPortConfig->gain.mode, jMask, jGainValues,
                                              nAudioPortConfig->gain.ramp_duration_ms));
        env->DeleteLocalRef(jGainValues);
        if (jAudioGainConfig == NULL) {
            ALOGV("convertAudioPortConfigFromNative could not create gain config");
            return AUDIO_JAVA_ERROR;
        }
    }
    jclass clazz;
    jmethodID methodID;
    if (audioportCreated) {
        clazz = gAudioPortConfigClass;
        methodID = gAudioPortConfigCstor;
        ALOGV("convertAudioPortConfigFromNative building a generic port config");
    } else {
        if (env->IsInstanceOf(jAudioPort->get(), gAudioDevicePortClass)) {
            clazz = gAudioDevicePortConfigClass;
            methodID = gAudioDevicePortConfigCstor;
            ALOGV("convertAudioPortConfigFromNative building a device config");
        } else if (env->IsInstanceOf(jAudioPort->get(), gAudioMixPortClass)) {
            clazz = gAudioMixPortConfigClass;
            methodID = gAudioMixPortConfigCstor;
            ALOGV("convertAudioPortConfigFromNative building a mix config");
        } else {
            return AUDIO_JAVA_ERROR;
        }
    }
    nMask = (nAudioPortConfig->config_mask & AUDIO_PORT_CONFIG_CHANNEL_MASK)
            ? nAudioPortConfig->channel_mask
            : AUDIO_CONFIG_BASE_INITIALIZER.channel_mask;
    if (useInMask) {
        jMask = inChannelMaskFromNative(nMask);
        ALOGV("convertAudioPortConfigFromNative IN mask java %x native %x", jMask, nMask);
    } else {
        jMask = outChannelMaskFromNative(nMask);
        ALOGV("convertAudioPortConfigFromNative OUT mask java %x native %x", jMask, nMask);
    }

    jAudioPortConfig->reset(
            env->NewObject(clazz, methodID, jAudioPort->get(),
                           (nAudioPortConfig->config_mask & AUDIO_PORT_CONFIG_SAMPLE_RATE)
                                   ? nAudioPortConfig->sample_rate
                                   : AUDIO_CONFIG_BASE_INITIALIZER.sample_rate,
                           jMask,
                           audioFormatFromNative(
                                   (nAudioPortConfig->config_mask & AUDIO_PORT_CONFIG_FORMAT)
                                           ? nAudioPortConfig->format
                                           : AUDIO_CONFIG_BASE_INITIALIZER.format),
                           jAudioGainConfig.get()));
    if (*jAudioPortConfig == NULL) {
        ALOGV("convertAudioPortConfigFromNative could not create new port config");
        return AUDIO_JAVA_ERROR;
    } else {
        ALOGV("convertAudioPortConfigFromNative OK");
    }
    return AUDIO_JAVA_SUCCESS;
}

static jintArray convertEncapsulationInfoFromNative(JNIEnv *env, uint32_t encapsulationInfo) {
    std::vector<int> encapsulation;
    // Ignore the first bit, which is ENCAPSULATION_.*_NONE, as an empty array
    // should be returned if no encapsulation is supported.
    encapsulationInfo >>= 1;
    for (int bitPosition = 1; encapsulationInfo; encapsulationInfo >>= 1, bitPosition++) {
        if (encapsulationInfo & 1) {
            encapsulation.push_back(bitPosition);
        }
    }
    jintArray result = env->NewIntArray(encapsulation.size());
    env->SetIntArrayRegion(result, 0, encapsulation.size(),
                           reinterpret_cast<jint *>(encapsulation.data()));
    return result;
}

static bool isAudioPortArrayCountOutOfBounds(const struct audio_port_v7 *nAudioPort,
                                             std::stringstream &ss) {
    ss << " num_audio_profiles " << nAudioPort->num_audio_profiles << " num_gains "
       << nAudioPort->num_gains;
    if (nAudioPort->num_audio_profiles > std::size(nAudioPort->audio_profiles) ||
        nAudioPort->num_gains > std::size(nAudioPort->gains)) {
        return true;
    }
    for (size_t i = 0; i < nAudioPort->num_audio_profiles; ++i) {
        ss << " (" << i << ") audio profile,"
           << " num_sample_rates " << nAudioPort->audio_profiles[i].num_sample_rates
           << " num_channel_masks " << nAudioPort->audio_profiles[i].num_channel_masks;
        if (nAudioPort->audio_profiles[i].num_sample_rates >
                    std::size(nAudioPort->audio_profiles[i].sample_rates) ||
            nAudioPort->audio_profiles[i].num_channel_masks >
                    std::size(nAudioPort->audio_profiles[i].channel_masks)) {
            return true;
        }
    }
    return false;
}

static jint convertAudioProfileFromNative(JNIEnv *env, ScopedLocalRef<jobject> *jAudioProfile,
                                          const audio_profile *nAudioProfile, bool useInMask) {
    size_t numPositionMasks = 0;
    size_t numIndexMasks = 0;

    int audioFormat = audioFormatFromNative(nAudioProfile->format);
    if (audioFormat == ENCODING_INVALID) {
        ALOGW("Unknown native audio format for JAVA API: %u", nAudioProfile->format);
        return AUDIO_JAVA_BAD_VALUE;
    }

    // count up how many masks are positional and indexed
    for (size_t index = 0; index < nAudioProfile->num_channel_masks; index++) {
        const audio_channel_mask_t mask = nAudioProfile->channel_masks[index];
        if (audio_channel_mask_get_representation(mask) == AUDIO_CHANNEL_REPRESENTATION_INDEX) {
            numIndexMasks++;
        } else {
            numPositionMasks++;
        }
    }

    ScopedLocalRef<jintArray> jSamplingRates(env,
                                             env->NewIntArray(nAudioProfile->num_sample_rates));
    ScopedLocalRef<jintArray> jChannelMasks(env, env->NewIntArray(numPositionMasks));
    ScopedLocalRef<jintArray> jChannelIndexMasks(env, env->NewIntArray(numIndexMasks));
    if (!jSamplingRates.get() || !jChannelMasks.get() || !jChannelIndexMasks.get()) {
        return AUDIO_JAVA_ERROR;
    }

    if (nAudioProfile->num_sample_rates) {
        env->SetIntArrayRegion(jSamplingRates.get(), 0 /*start*/, nAudioProfile->num_sample_rates,
                               const_cast<jint *>(reinterpret_cast<const jint *>(
                                       nAudioProfile->sample_rates)));
    }

    // put the masks in the output arrays
    for (size_t maskIndex = 0, posMaskIndex = 0, indexedMaskIndex = 0;
         maskIndex < nAudioProfile->num_channel_masks; maskIndex++) {
        const audio_channel_mask_t mask = nAudioProfile->channel_masks[maskIndex];
        if (audio_channel_mask_get_representation(mask) == AUDIO_CHANNEL_REPRESENTATION_INDEX) {
            jint jMask = audio_channel_mask_get_bits(mask);
            env->SetIntArrayRegion(jChannelIndexMasks.get(), indexedMaskIndex++, 1, &jMask);
        } else {
            jint jMask = useInMask ? inChannelMaskFromNative(mask) : outChannelMaskFromNative(mask);
            env->SetIntArrayRegion(jChannelMasks.get(), posMaskIndex++, 1, &jMask);
        }
    }

    int encapsulationType;
    if (audioEncapsulationTypeFromNative(nAudioProfile->encapsulation_type, &encapsulationType) !=
        NO_ERROR) {
        ALOGW("Unknown encapsulation type for JAVA API: %u", nAudioProfile->encapsulation_type);
    }

    jAudioProfile->reset(env->NewObject(gAudioProfileClass, gAudioProfileCstor, audioFormat,
                                        jSamplingRates.get(), jChannelMasks.get(),
                                        jChannelIndexMasks.get(), encapsulationType));
    if (*jAudioProfile == nullptr) {
        return AUDIO_JAVA_ERROR;
    }

    return AUDIO_JAVA_SUCCESS;
}

static jint convertAudioPortFromNative(JNIEnv *env, ScopedLocalRef<jobject> *jAudioPort,
                                       const struct audio_port_v7 *nAudioPort) {
    bool hasFloat = false;
    bool useInMask;

    ALOGV("convertAudioPortFromNative id %d role %d type %d name %s",
        nAudioPort->id, nAudioPort->role, nAudioPort->type, nAudioPort->name);

    // Verify audio port array count info.
    if (std::stringstream ss; isAudioPortArrayCountOutOfBounds(nAudioPort, ss)) {
        std::string s = "convertAudioPortFromNative array count out of bounds:" + ss.str();

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
        return AUDIO_JAVA_ERROR;
    }

    useInMask = audio_has_input_direction(nAudioPort->type, nAudioPort->role);
    ScopedLocalRef<jobject> jAudioProfiles(env,
                                           env->NewObject(gArrayListClass,
                                                          gArrayListMethods.cstor));
    if (jAudioProfiles == nullptr) {
        return AUDIO_JAVA_ERROR;
    }

    ScopedLocalRef<jobject> jPcmFloatProfileFromExtendedInteger(env, nullptr);
    for (size_t i = 0; i < nAudioPort->num_audio_profiles; ++i) {
        ScopedLocalRef<jobject> jAudioProfile(env);
        jint jStatus = convertAudioProfileFromNative(env, &jAudioProfile,
                                                     &nAudioPort->audio_profiles[i], useInMask);
        if (jStatus == AUDIO_JAVA_BAD_VALUE) {
            // skipping Java layer unsupported audio formats
            continue;
        }
        if (jStatus != NO_ERROR) {
            return AUDIO_JAVA_ERROR;
        }
        env->CallBooleanMethod(jAudioProfiles.get(), gArrayListMethods.add, jAudioProfile.get());

        if (nAudioPort->audio_profiles[i].format == AUDIO_FORMAT_PCM_FLOAT) {
            hasFloat = true;
        } else if (jPcmFloatProfileFromExtendedInteger.get() == nullptr &&
                   audio_is_linear_pcm(nAudioPort->audio_profiles[i].format) &&
                   audio_bytes_per_sample(nAudioPort->audio_profiles[i].format) > 2) {
            ScopedLocalRef<jintArray>
                    jSamplingRates(env,
                                   static_cast<jintArray>(
                                           env->GetObjectField(jAudioProfile.get(),
                                                               gAudioProfileFields
                                                                       .mSamplingRates)));
            ScopedLocalRef<jintArray>
                    jChannelMasks(env,
                                  static_cast<jintArray>(
                                          env->GetObjectField(jAudioProfile.get(),
                                                              gAudioProfileFields.mChannelMasks)));
            ScopedLocalRef<jintArray>
                    jChannelIndexMasks(env,
                                       static_cast<jintArray>(
                                               env->GetObjectField(jAudioProfile.get(),
                                                                   gAudioProfileFields
                                                                           .mChannelIndexMasks)));
            int encapsulationType =
                    env->GetIntField(jAudioProfile.get(), gAudioProfileFields.mEncapsulationType);

            jPcmFloatProfileFromExtendedInteger.reset(
                    env->NewObject(gAudioProfileClass, gAudioProfileCstor,
                                   audioFormatFromNative(AUDIO_FORMAT_PCM_FLOAT),
                                   jSamplingRates.get(), jChannelMasks.get(),
                                   jChannelIndexMasks.get(), encapsulationType));
        }
    }
    if (!hasFloat && jPcmFloatProfileFromExtendedInteger.get() != nullptr) {
        // R and earlier compatibility - add ENCODING_PCM_FLOAT to the end
        // (replacing the zero pad). This ensures pre-S apps that look
        // for ENCODING_PCM_FLOAT continue to see that encoding if the device supports
        // extended precision integers.
        env->CallBooleanMethod(jAudioProfiles.get(), gArrayListMethods.add,
                               jPcmFloatProfileFromExtendedInteger.get());
    }

    ScopedLocalRef<jobject> jAudioDescriptors(env,
                                              env->NewObject(gArrayListClass,
                                                             gArrayListMethods.cstor));
    if (jAudioDescriptors == nullptr) {
        return AUDIO_JAVA_ERROR;
    }
    for (size_t i = 0; i < nAudioPort->num_extra_audio_descriptors; ++i) {
        const auto &extraAudioDescriptor = nAudioPort->extra_audio_descriptors[i];
        ScopedLocalRef<jobject> jAudioDescriptor(env);
        if (extraAudioDescriptor.descriptor_length == 0) {
            continue;
        }
        int standard;
        if (audioStandardFromNative(extraAudioDescriptor.standard, &standard) != NO_ERROR) {
            ALOGW("Unknown standard for JAVA API: %u", extraAudioDescriptor.standard);
            continue;
        }
        int encapsulationType;
        if (audioEncapsulationTypeFromNative(extraAudioDescriptor.encapsulation_type,
                                             &encapsulationType) != NO_ERROR) {
            ALOGW("Unknown encapsualtion type for JAVA API: %u",
                  extraAudioDescriptor.encapsulation_type);
            continue;
        }
        ScopedLocalRef<jbyteArray> jDescriptor(env,
                                               env->NewByteArray(
                                                       extraAudioDescriptor.descriptor_length));
        env->SetByteArrayRegion(jDescriptor.get(), 0, extraAudioDescriptor.descriptor_length,
                                reinterpret_cast<const jbyte *>(extraAudioDescriptor.descriptor));
        jAudioDescriptor =
                ScopedLocalRef<jobject>(env,
                                        env->NewObject(gAudioDescriptorClass, gAudioDescriptorCstor,
                                                       standard, encapsulationType,
                                                       jDescriptor.get()));
        env->CallBooleanMethod(jAudioDescriptors.get(), gArrayListMethods.add,
                               jAudioDescriptor.get());
    }

    // gains
    ScopedLocalRef<jobjectArray> jGains(env,
                                        env->NewObjectArray(nAudioPort->num_gains, gAudioGainClass,
                                                            nullptr));
    if (jGains == nullptr) {
        return AUDIO_JAVA_ERROR;
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
            return AUDIO_JAVA_ERROR;
        }
        env->SetObjectArrayElement(jGains.get(), j, jGain);
        env->DeleteLocalRef(jGain);
    }

    ScopedLocalRef<jobject> jHandle(env,
                                    env->NewObject(gAudioHandleClass, gAudioHandleCstor,
                                                   nAudioPort->id));
    if (jHandle == nullptr) {
        return AUDIO_JAVA_ERROR;
    }

    ScopedLocalRef<jstring> jDeviceName(env, env->NewStringUTF(nAudioPort->name));
    if (nAudioPort->type == AUDIO_PORT_TYPE_DEVICE) {
        ScopedLocalRef<jintArray> jEncapsulationModes(
                env,
                convertEncapsulationInfoFromNative(env,
                                                   nAudioPort->ext.device.encapsulation_modes));
        ScopedLocalRef<jintArray> jEncapsulationMetadataTypes(
                env,
                convertEncapsulationInfoFromNative(env,
                                                   nAudioPort->ext.device
                                                           .encapsulation_metadata_types));
        ALOGV("convertAudioPortFromNative is a device %08x", nAudioPort->ext.device.type);
        ScopedLocalRef<jstring> jAddress(env, env->NewStringUTF(nAudioPort->ext.device.address));
        jAudioPort->reset(env->NewObject(gAudioDevicePortClass, gAudioDevicePortCstor,
                                         jHandle.get(), jDeviceName.get(), jAudioProfiles.get(),
                                         jGains.get(), nAudioPort->ext.device.type, jAddress.get(),
                                         jEncapsulationModes.get(),
                                         jEncapsulationMetadataTypes.get(),
                                         jAudioDescriptors.get()));
    } else if (nAudioPort->type == AUDIO_PORT_TYPE_MIX) {
        ALOGV("convertAudioPortFromNative is a mix");
        jAudioPort->reset(env->NewObject(gAudioMixPortClass, gAudioMixPortCstor, jHandle.get(),
                                         nAudioPort->ext.mix.handle, nAudioPort->role,
                                         jDeviceName.get(), jAudioProfiles.get(), jGains.get()));
    } else {
        ALOGE("convertAudioPortFromNative unknown nAudioPort type %d", nAudioPort->type);
        return AUDIO_JAVA_ERROR;
    }
    if (*jAudioPort == NULL) {
        return AUDIO_JAVA_ERROR;
    }

    ScopedLocalRef<jobject> jAudioPortConfig(env, nullptr);

    if (int jStatus = convertAudioPortConfigFromNative(env, jAudioPort, &jAudioPortConfig,
                                                       &nAudioPort->active_config);
        jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    env->SetObjectField(jAudioPort->get(), gAudioPortFields.mActiveConfig, jAudioPortConfig.get());
    return AUDIO_JAVA_SUCCESS;
}

static bool setGeneration(JNIEnv *env, jintArray jGeneration, unsigned int generation1) {
    ScopedIntArrayRW nGeneration(env, jGeneration);
    if (nGeneration.get() == nullptr) {
        return false;
    } else {
        nGeneration[0] = generation1;
        return true;
    }
}

static jint
android_media_AudioSystem_listAudioPorts(JNIEnv *env, jobject clazz,
                                         jobject jPorts, jintArray jGeneration)
{
    ALOGV("listAudioPorts");

    if (jPorts == NULL) {
        ALOGE("listAudioPorts NULL AudioPort ArrayList");
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jPorts, gArrayListClass)) {
        ALOGE("listAudioPorts not an arraylist");
        return AUDIO_JAVA_BAD_VALUE;
    }

    if (jGeneration == NULL || env->GetArrayLength(jGeneration) != 1) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    status_t status;
    unsigned int generation1 = 0;
    unsigned int generation;
    unsigned int numPorts;
    std::vector<audio_port_v7> nPorts;
    int attempts = MAX_PORT_GENERATION_SYNC_ATTEMPTS;
    jint jStatus;

    // get the port count and all the ports until they both return the same generation
    do {
        if (attempts-- < 0) {
            status = TIMED_OUT;
            break;
        }

        numPorts = 0;
        status = AudioSystem::listAudioPorts(AUDIO_PORT_ROLE_NONE, AUDIO_PORT_TYPE_NONE, &numPorts,
                                             nullptr, &generation1);
        if (status != NO_ERROR) {
            ALOGE_IF(status != NO_ERROR, "AudioSystem::listAudioPorts error %d", status);
            break;
        }
        if (numPorts == 0) {
            return setGeneration(env, jGeneration, generation1) ? AUDIO_JAVA_SUCCESS
                                                                : AUDIO_JAVA_ERROR;
        }
        nPorts.resize(numPorts);

        status = AudioSystem::listAudioPorts(AUDIO_PORT_ROLE_NONE, AUDIO_PORT_TYPE_NONE, &numPorts,
                                             &nPorts[0], &generation);
        ALOGV("listAudioPorts AudioSystem::listAudioPorts numPorts %d generation %d generation1 %d",
              numPorts, generation, generation1);
    } while (generation1 != generation && status == NO_ERROR);

    jStatus = nativeToJavaStatus(status);
    if (jStatus == AUDIO_JAVA_SUCCESS) {
        for (size_t i = 0; i < numPorts; i++) {
            ScopedLocalRef<jobject> jAudioPort(env, nullptr);
            jStatus = convertAudioPortFromNative(env, &jAudioPort, &nPorts[i]);
            if (jStatus != AUDIO_JAVA_SUCCESS) break;
            env->CallBooleanMethod(jPorts, gArrayListMethods.add, jAudioPort.get());
        }
    }
    if (!setGeneration(env, jGeneration, generation1)) {
        jStatus = AUDIO_JAVA_ERROR;
    }
    return jStatus;
}

// From AudioDeviceInfo
static const int GET_DEVICES_INPUTS = 0x0001;
static const int GET_DEVICES_OUTPUTS = 0x0002;

static int android_media_AudioSystem_getSupportedDeviceTypes(JNIEnv *env, jobject clazz,
                                                             jint direction, jobject jDeviceTypes) {
    if (jDeviceTypes == NULL) {
        ALOGE("%s NULL Device Types IntArray", __func__);
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jDeviceTypes, gIntArrayClass)) {
        ALOGE("%s not an IntArray", __func__);
        return AUDIO_JAVA_BAD_VALUE;
    }

    // Convert AudioManager.GET_DEVICES_ flags to AUDIO_PORT_ROLE_ constants
    audio_port_role_t role;
    if (direction == GET_DEVICES_INPUTS) {
        role = AUDIO_PORT_ROLE_SOURCE;
    } else if (direction == GET_DEVICES_OUTPUTS) {
        role = AUDIO_PORT_ROLE_SINK;
    } else {
        ALOGE("%s invalid direction : 0x%X", __func__, direction);
        return AUDIO_JAVA_BAD_VALUE;
    }

    std::vector<media::AudioPortFw> deviceList;
    AudioSystem::listDeclaredDevicePorts(static_cast<media::AudioPortRole>(role), &deviceList);

    // Walk the device list
    for (const auto &device : deviceList) {
        ConversionResult<audio_port_v7> result = aidl2legacy_AudioPortFw_audio_port_v7(device);

        struct audio_port_v7 port = VALUE_OR_RETURN_STATUS(result);
        assert(port.type == AUDIO_PORT_TYPE_DEVICE);

        env->CallVoidMethod(jDeviceTypes, gIntArrayMethods.add, port.ext.device.type);
    }

    return AUDIO_JAVA_SUCCESS;
}

static int
android_media_AudioSystem_createAudioPatch(JNIEnv *env, jobject clazz,
                                 jobjectArray jPatches, jobjectArray jSources, jobjectArray jSinks)
{
    status_t status;
    jint jStatus;

    ALOGV("createAudioPatch");
    if (jPatches == NULL || jSources == NULL || jSinks == NULL) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    if (env->GetArrayLength(jPatches) != 1) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    jint numSources = env->GetArrayLength(jSources);
    if (numSources == 0 || numSources > AUDIO_PATCH_PORTS_MAX) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    jint numSinks = env->GetArrayLength(jSinks);
    if (numSinks == 0 || numSinks > AUDIO_PATCH_PORTS_MAX) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    audio_patch_handle_t handle = static_cast<audio_patch_handle_t>(AUDIO_PATCH_HANDLE_NONE);
    ScopedLocalRef<jobject> jPatch(env, env->GetObjectArrayElement(jPatches, 0));
    ScopedLocalRef<jobject> jPatchHandle(env, nullptr);
    if (jPatch != nullptr) {
        if (!env->IsInstanceOf(jPatch.get(), gAudioPatchClass)) {
            return AUDIO_JAVA_BAD_VALUE;
        }
        jPatchHandle.reset(env->GetObjectField(jPatch.get(), gAudioPatchFields.mHandle));
        handle = static_cast<audio_patch_handle_t>(
                env->GetIntField(jPatchHandle.get(), gAudioHandleFields.mId));
    }

    struct audio_patch nPatch = { .id = handle };

    for (jint i = 0; i < numSources; i++) {
        ScopedLocalRef<jobject> jSource(env, env->GetObjectArrayElement(jSources, i));
        if (!env->IsInstanceOf(jSource.get(), gAudioPortConfigClass)) {
            return AUDIO_JAVA_BAD_VALUE;
        }
        jStatus = convertAudioPortConfigToNative(env, &nPatch.sources[i], jSource.get(), false);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        nPatch.num_sources++;
    }

    for (jint i = 0; i < numSinks; i++) {
        ScopedLocalRef<jobject> jSink(env, env->GetObjectArrayElement(jSinks, i));
        if (!env->IsInstanceOf(jSink.get(), gAudioPortConfigClass)) {
            return AUDIO_JAVA_BAD_VALUE;
        }
        jStatus = convertAudioPortConfigToNative(env, &nPatch.sinks[i], jSink.get(), false);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        nPatch.num_sinks++;
    }

    ALOGV("AudioSystem::createAudioPatch");
    status = AudioSystem::createAudioPatch(&nPatch, &handle);
    ALOGV("AudioSystem::createAudioPatch() returned %d hande %d", status, handle);

    jStatus = nativeToJavaStatus(status);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    if (jPatchHandle == nullptr) {
        jPatchHandle.reset(env->NewObject(gAudioHandleClass, gAudioHandleCstor, handle));
        if (jPatchHandle == nullptr) {
            return AUDIO_JAVA_ERROR;
        }
        jPatch.reset(env->NewObject(gAudioPatchClass, gAudioPatchCstor, jPatchHandle.get(),
                                    jSources, jSinks));
        if (jPatch == nullptr) {
            return AUDIO_JAVA_ERROR;
        }
        env->SetObjectArrayElement(jPatches, 0, jPatch.get());
    } else {
        env->SetIntField(jPatchHandle.get(), gAudioHandleFields.mId, handle);
    }
    return jStatus;
}

static jint
android_media_AudioSystem_releaseAudioPatch(JNIEnv *env, jobject clazz,
                                               jobject jPatch)
{
    ALOGV("releaseAudioPatch");
    if (jPatch == NULL) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    audio_patch_handle_t handle = static_cast<audio_patch_handle_t>(AUDIO_PATCH_HANDLE_NONE);
    jobject jPatchHandle = NULL;
    if (!env->IsInstanceOf(jPatch, gAudioPatchClass)) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    jPatchHandle = env->GetObjectField(jPatch, gAudioPatchFields.mHandle);
    handle = static_cast<audio_patch_handle_t>(
            env->GetIntField(jPatchHandle, gAudioHandleFields.mId));
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
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jPatches, gArrayListClass)) {
        ALOGE("listAudioPatches not an arraylist");
        return AUDIO_JAVA_BAD_VALUE;
    }

    if (jGeneration == NULL || env->GetArrayLength(jGeneration) != 1) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    status_t status;
    unsigned int generation1;
    unsigned int generation;
    unsigned int numPatches;
    std::vector<audio_patch> nPatches;
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
            return setGeneration(env, jGeneration, generation1) ? AUDIO_JAVA_SUCCESS
                                                                : AUDIO_JAVA_ERROR;
        }

        nPatches.resize(numPatches);

        status = AudioSystem::listAudioPatches(&numPatches, nPatches.data(), &generation);
        ALOGV("listAudioPatches AudioSystem::listAudioPatches numPatches %d generation %d generation1 %d",
              numPatches, generation, generation1);

    } while (generation1 != generation && status == NO_ERROR);

    jStatus = nativeToJavaStatus(status);
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        if (!setGeneration(env, jGeneration, generation1)) {
            jStatus = AUDIO_JAVA_ERROR;
        }
        return jStatus;
    }

    for (size_t i = 0; i < numPatches; i++) {
        ScopedLocalRef<jobject> jPatch(env, nullptr);
        ScopedLocalRef<jobjectArray> jSources(env, nullptr);
        ScopedLocalRef<jobjectArray> jSinks(env, nullptr);
        jobject patchHandle = env->NewObject(gAudioHandleClass, gAudioHandleCstor,
                                                 nPatches[i].id);
        if (patchHandle == NULL) {
            setGeneration(env, jGeneration, generation1);
            return AUDIO_JAVA_ERROR;
        }
        ALOGV("listAudioPatches patch %zu num_sources %d num_sinks %d",
              i, nPatches[i].num_sources, nPatches[i].num_sinks);

        env->SetIntField(patchHandle, gAudioHandleFields.mId, nPatches[i].id);

        // load sources
        jSources.reset(env->NewObjectArray(nPatches[i].num_sources, gAudioPortConfigClass, NULL));
        if (jSources == nullptr) {
            setGeneration(env, jGeneration, generation1);
            return AUDIO_JAVA_ERROR;
        }

        for (size_t j = 0; j < nPatches[i].num_sources; j++) {
            ScopedLocalRef<jobject> jSource(env, nullptr);
            ScopedLocalRef<jobject> jAudioPort(env, nullptr);
            jStatus = convertAudioPortConfigFromNative(env, &jAudioPort, &jSource,
                                                       &nPatches[i].sources[j]);
            if (jStatus != AUDIO_JAVA_SUCCESS) {
                if (!setGeneration(env, jGeneration, generation1)) {
                    jStatus = AUDIO_JAVA_ERROR;
                }
                return jStatus;
            }
            env->SetObjectArrayElement(jSources.get(), j, jSource.get());
            ALOGV("listAudioPatches patch %zu source %zu is a %s handle %d",
                  i, j,
                  nPatches[i].sources[j].type == AUDIO_PORT_TYPE_DEVICE ? "device" : "mix",
                  nPatches[i].sources[j].id);
        }
        // load sinks
        jSinks.reset(env->NewObjectArray(nPatches[i].num_sinks, gAudioPortConfigClass, NULL));
        if (jSinks == nullptr) {
            setGeneration(env, jGeneration, generation1);
            return AUDIO_JAVA_ERROR;
        }

        for (size_t j = 0; j < nPatches[i].num_sinks; j++) {
            ScopedLocalRef<jobject> jSink(env, nullptr);
            ScopedLocalRef<jobject> jAudioPort(env, nullptr);
            jStatus = convertAudioPortConfigFromNative(env, &jAudioPort, &jSink,
                                                       &nPatches[i].sinks[j]);

            if (jStatus != AUDIO_JAVA_SUCCESS) {
                if (!setGeneration(env, jGeneration, generation1)) {
                    jStatus = AUDIO_JAVA_ERROR;
                }
                return jStatus;
            }
            env->SetObjectArrayElement(jSinks.get(), j, jSink.get());
            ALOGV("listAudioPatches patch %zu sink %zu is a %s handle %d",
                  i, j,
                  nPatches[i].sinks[j].type == AUDIO_PORT_TYPE_DEVICE ? "device" : "mix",
                  nPatches[i].sinks[j].id);
        }

        jPatch.reset(env->NewObject(gAudioPatchClass, gAudioPatchCstor, patchHandle, jSources.get(),
                                    jSinks.get()));
        if (jPatch == nullptr) {
            setGeneration(env, jGeneration, generation1);
            return AUDIO_JAVA_ERROR;
        }
        env->CallBooleanMethod(jPatches, gArrayListMethods.add, jPatch.get());
    }
    if (!setGeneration(env, jGeneration, generation1)) {
        jStatus = AUDIO_JAVA_ERROR;
    }
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
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }
    audio_port_handle_t handle;
    status_t status = AudioSystem::startAudioSource(&nAudioPortConfig, paa.get(), &handle);
    ALOGV("AudioSystem::startAudioSource() returned %d handle %d", status, handle);
    if (status != NO_ERROR) {
        return nativeToJavaStatus(status);
    }
    ALOG_ASSERT(handle > 0, "%s: invalid handle reported on successful call", __func__);
    return handle;
}

static jint
android_media_AudioSystem_stopAudioSource(JNIEnv *env, jobject clazz, jint handle)
{
    ALOGV("stopAudioSource");
    status_t status = AudioSystem::stopAudioSource(static_cast<audio_port_handle_t>(handle));
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
    return AudioSystem::getAudioHwSyncForSession(static_cast<audio_session_t>(sessionId));
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

static void
android_media_AudioSystem_registerRoutingCallback(JNIEnv *env, jobject thiz)
{
    AudioSystem::setRoutingCallback(android_media_AudioSystem_routing_callback);
}

static void android_media_AudioSystem_registerVolRangeInitReqCallback(JNIEnv *env, jobject thiz)
{
    AudioSystem::setVolInitReqCallback(android_media_AudioSystem_vol_range_init_req_callback);
}

void javaAudioFormatToNativeAudioConfig(JNIEnv *env, audio_config_t *nConfig,
                                       const jobject jFormat, bool isInput) {
    *nConfig = AUDIO_CONFIG_INITIALIZER;
    nConfig->format = audioFormatToNative(env->GetIntField(jFormat, gAudioFormatFields.mEncoding));
    nConfig->sample_rate = env->GetIntField(jFormat, gAudioFormatFields.mSampleRate);
    jint jChannelMask = env->GetIntField(jFormat, gAudioFormatFields.mChannelMask);
    if (isInput) {
        nConfig->channel_mask = inChannelMaskToNative(jChannelMask);
    } else {
        nConfig->channel_mask = outChannelMaskToNative(jChannelMask);
    }
}

void javaAudioFormatToNativeAudioConfigBase(JNIEnv *env, const jobject jFormat,
                                            audio_config_base_t *nConfigBase, bool isInput) {
    *nConfigBase = AUDIO_CONFIG_BASE_INITIALIZER;
    nConfigBase->format =
            audioFormatToNative(env->GetIntField(jFormat, gAudioFormatFields.mEncoding));
    nConfigBase->sample_rate = env->GetIntField(jFormat, gAudioFormatFields.mSampleRate);
    jint jChannelMask = env->GetIntField(jFormat, gAudioFormatFields.mChannelMask);
    jint jChannelIndexMask = env->GetIntField(jFormat, gAudioFormatFields.mChannelIndexMask);
    nConfigBase->channel_mask = jChannelIndexMask != 0
            ? audio_channel_mask_from_representation_and_bits(AUDIO_CHANNEL_REPRESENTATION_INDEX,
                                                              jChannelIndexMask)
            : isInput ? inChannelMaskToNative(jChannelMask)
                      : outChannelMaskToNative(jChannelMask);
}

jobject nativeAudioConfigBaseToJavaAudioFormat(JNIEnv *env, const audio_config_base_t *nConfigBase,
                                               bool isInput) {
    if (nConfigBase == nullptr) {
        return nullptr;
    }
    int propertyMask = AUDIO_FORMAT_HAS_PROPERTY_ENCODING | AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE;
    int channelMask = 0;
    int channelIndexMask = 0;
    switch (audio_channel_mask_get_representation(nConfigBase->channel_mask)) {
        case AUDIO_CHANNEL_REPRESENTATION_POSITION:
            channelMask = isInput ? inChannelMaskFromNative(nConfigBase->channel_mask)
                                  : outChannelMaskFromNative(nConfigBase->channel_mask);
            propertyMask |= AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK;
            break;
        case AUDIO_CHANNEL_REPRESENTATION_INDEX:
            channelIndexMask = audio_channel_mask_get_bits(nConfigBase->channel_mask);
            propertyMask |= AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK;
            break;
        default:
            // This must not happen
            break;
    }
    return env->NewObject(gAudioFormatClass, gAudioFormatCstor, propertyMask,
                          audioFormatFromNative(nConfigBase->format), nConfigBase->sample_rate,
                          channelMask, channelIndexMask);
}

jint nativeAudioConfigToJavaAudioFormat(JNIEnv *env, const audio_config_t *nConfigBase,
                                        jobject *jAudioFormat, bool isInput) {
    if (!audio_flags::audio_mix_test_api()) {
        return AUDIO_JAVA_INVALID_OPERATION;
    }

    if (nConfigBase == nullptr) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    int propertyMask = AUDIO_FORMAT_HAS_PROPERTY_ENCODING | AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE;
    int channelMask = 0;
    int channelIndexMask = 0;
    switch (audio_channel_mask_get_representation(nConfigBase->channel_mask)) {
        case AUDIO_CHANNEL_REPRESENTATION_POSITION:
            channelMask = isInput ? inChannelMaskFromNative(nConfigBase->channel_mask)
                                  : outChannelMaskFromNative(nConfigBase->channel_mask);
            propertyMask |= AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK;
            break;
        case AUDIO_CHANNEL_REPRESENTATION_INDEX:
            channelIndexMask = audio_channel_mask_get_bits(nConfigBase->channel_mask);
            propertyMask |= AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK;
            break;
        default:
            // This must not happen
            break;
    }

    *jAudioFormat = env->NewObject(gAudioFormatClass, gAudioFormatCstor, propertyMask,
                                   audioFormatFromNative(nConfigBase->format),
                                   nConfigBase->sample_rate, channelMask, channelIndexMask);
    return AUDIO_JAVA_SUCCESS;
}

jint convertAudioMixerAttributesToNative(JNIEnv *env, const jobject jAudioMixerAttributes,
                                         audio_mixer_attributes_t *nMixerAttributes) {
    ScopedLocalRef<jobject> jFormat(env,
                                    env->GetObjectField(jAudioMixerAttributes,
                                                        gAudioMixerAttributesField.mFormat));
    javaAudioFormatToNativeAudioConfigBase(env, jFormat.get(), &nMixerAttributes->config,
                                           false /*isInput*/);
    nMixerAttributes->mixer_behavior = audioMixerBehaviorToNative(
            env->GetIntField(jAudioMixerAttributes, gAudioMixerAttributesField.mMixerBehavior));
    if (nMixerAttributes->mixer_behavior == AUDIO_MIXER_BEHAVIOR_INVALID) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    return (jint)AUDIO_JAVA_SUCCESS;
}

jobject convertAudioMixerAttributesFromNative(JNIEnv *env,
                                              const audio_mixer_attributes_t *nMixerAttributes) {
    if (nMixerAttributes == nullptr) {
        return nullptr;
    }
    jint mixerBehavior = audioMixerBehaviorFromNative(nMixerAttributes->mixer_behavior);
    if (mixerBehavior == MIXER_BEHAVIOR_INVALID) {
        return nullptr;
    }
    ScopedLocalRef<jobject>
            jFormat(env,
                    nativeAudioConfigBaseToJavaAudioFormat(env, &nMixerAttributes->config,
                                                           false /*isInput*/));
    return env->NewObject(gAudioMixerAttributesClass, gAudioMixerAttributesCstor, jFormat.get(),
                          mixerBehavior);
}

static jint convertAudioMixingRuleToNative(JNIEnv *env, const jobject audioMixingRule,
                                           std::vector<AudioMixMatchCriterion> *nCriteria) {
    jobject jRuleCriteria = env->GetObjectField(audioMixingRule, gAudioMixingRuleFields.mCriteria);

    jobjectArray jCriteria = static_cast<jobjectArray>(
            env->CallObjectMethod(jRuleCriteria, gArrayListMethods.toArray));
    env->DeleteLocalRef(jRuleCriteria);

    jint numCriteria = env->GetArrayLength(jCriteria);
    if (numCriteria > MAX_CRITERIA_PER_MIX) {
        numCriteria = MAX_CRITERIA_PER_MIX;
    }

    nCriteria->resize(numCriteria);
    for (jint i = 0; i < numCriteria; i++) {
        AudioMixMatchCriterion &nCriterion = (*nCriteria)[i];

        jobject jCriterion = env->GetObjectArrayElement(jCriteria, i);

        nCriterion.mRule = env->GetIntField(jCriterion, gAudioMixMatchCriterionFields.mRule);

        const uint32_t match_rule = nCriterion.mRule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
            case RULE_MATCH_UID:
                nCriterion.mValue.mUid =
                        env->GetIntField(jCriterion, gAudioMixMatchCriterionFields.mIntProp);
                break;
            case RULE_MATCH_USERID:
                nCriterion.mValue.mUserId =
                        env->GetIntField(jCriterion, gAudioMixMatchCriterionFields.mIntProp);
                break;
            case RULE_MATCH_AUDIO_SESSION_ID: {
                jint jAudioSessionId =
                        env->GetIntField(jCriterion, gAudioMixMatchCriterionFields.mIntProp);
                nCriterion.mValue.mAudioSessionId = static_cast<audio_session_t>(jAudioSessionId);
            } break;
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET: {
                jobject jAttributes =
                        env->GetObjectField(jCriterion, gAudioMixMatchCriterionFields.mAttr);

                auto paa = JNIAudioAttributeHelper::makeUnique();
                jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jAttributes, paa.get());
                if (jStatus != AUDIO_JAVA_SUCCESS) {
                    return jStatus;
                }
                if (match_rule == RULE_MATCH_ATTRIBUTE_USAGE) {
                    nCriterion.mValue.mUsage = paa->usage;
                } else {
                    nCriterion.mValue.mSource = paa->source;
                }
                env->DeleteLocalRef(jAttributes);
            } break;
        }
        env->DeleteLocalRef(jCriterion);
    }
    env->DeleteLocalRef(jCriteria);
    return AUDIO_JAVA_SUCCESS;
}

static jint nativeAudioMixToJavaAudioMixingRule(JNIEnv *env, const AudioMix &nAudioMix,
                                                jobject *jAudioMixingRule) {
    if (!audio_flags::audio_mix_test_api()) {
        return AUDIO_JAVA_INVALID_OPERATION;
    }

    jobject jAudioMixMatchCriterionList = env->NewObject(gArrayListClass, gArrayListMethods.cstor);
    for (const auto &criteria : nAudioMix.mCriteria) {
        jobject jAudioAttributes = NULL;
        jobject jMixMatchCriterion = NULL;
        jobject jValueInteger = NULL;
        switch (criteria.mRule) {
            case RULE_MATCH_UID:
                jValueInteger = env->NewObject(gIntegerClass, gIntegerCstor, criteria.mValue.mUid);
                jMixMatchCriterion = env->NewObject(gAudioMixMatchCriterionClass,
                                                    gAudioMixMatchCriterionIntPropCstor,
                                                    jValueInteger, criteria.mRule);
                break;
            case RULE_MATCH_USERID:
                jValueInteger =
                        env->NewObject(gIntegerClass, gIntegerCstor, criteria.mValue.mUserId);
                jMixMatchCriterion = env->NewObject(gAudioMixMatchCriterionClass,
                                                    gAudioMixMatchCriterionIntPropCstor,
                                                    jValueInteger, criteria.mRule);
                break;
            case RULE_MATCH_AUDIO_SESSION_ID:
                jValueInteger = env->NewObject(gIntegerClass, gIntegerCstor,
                                               criteria.mValue.mAudioSessionId);
                jMixMatchCriterion = env->NewObject(gAudioMixMatchCriterionClass,
                                                    gAudioMixMatchCriterionIntPropCstor,
                                                    jValueInteger, criteria.mRule);
                break;
            case RULE_MATCH_ATTRIBUTE_USAGE:
                jAudioAttributes = env->NewObject(gAudioAttributesClass, gAudioAttributesCstor);
                env->SetIntField(jAudioAttributes, gAudioAttributesFields.mUsage,
                                 criteria.mValue.mUsage);
                jMixMatchCriterion = env->NewObject(gAudioMixMatchCriterionClass,
                                                    gAudioMixMatchCriterionAttrCstor,
                                                    jAudioAttributes, criteria.mRule);
                break;
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                jAudioAttributes = env->NewObject(gAudioAttributesClass, gAudioAttributesCstor);
                env->SetIntField(jAudioAttributes, gAudioAttributesFields.mSource,
                                 criteria.mValue.mSource);
                jMixMatchCriterion = env->NewObject(gAudioMixMatchCriterionClass,
                                                    gAudioMixMatchCriterionAttrCstor,
                                                    jAudioAttributes, criteria.mRule);
                break;
        }
        env->CallBooleanMethod(jAudioMixMatchCriterionList, gArrayListMethods.add,
                               jMixMatchCriterion);
    }

    *jAudioMixingRule = env->NewObject(gAudioMixingRuleClass, gAudioMixingRuleCstor,
                                       nAudioMix.mMixType, jAudioMixMatchCriterionList,
                                       nAudioMix.mAllowPrivilegedMediaPlaybackCapture,
                                       nAudioMix.mVoiceCommunicationCaptureAllowed);
    return AUDIO_JAVA_SUCCESS;
}

static jint convertAudioMixFromNative(JNIEnv *env, jobject *jAudioMix, const AudioMix &nAudioMix) {
    if (!audio_flags::audio_mix_test_api()) {
        return AUDIO_JAVA_INVALID_OPERATION;
    }
    jobject jAudioMixingRule = NULL;
    int status = nativeAudioMixToJavaAudioMixingRule(env, nAudioMix, &jAudioMixingRule);
    if (status != AUDIO_JAVA_SUCCESS) {
        return status;
    }
    jobject jAudioFormat = NULL;
    status = nativeAudioConfigToJavaAudioFormat(env, &nAudioMix.mFormat, &jAudioFormat, false);
    if (status != AUDIO_JAVA_SUCCESS) {
        return status;
    }
    std::unique_ptr<AIBinder, decltype(&AIBinder_decStrong)> aiBinder(AIBinder_fromPlatformBinder(
                                                                              nAudioMix.mToken),
                                                                      &AIBinder_decStrong);
    jobject jBinderToken = AIBinder_toJavaBinder(env, aiBinder.get());

    jstring deviceAddress = env->NewStringUTF(nAudioMix.mDeviceAddress.c_str());
    *jAudioMix = env->NewObject(gAudioMixClass, gAudioMixCstor, jAudioMixingRule, jAudioFormat,
                                nAudioMix.mRouteFlags, nAudioMix.mCbFlags, nAudioMix.mDeviceType,
                                deviceAddress, jBinderToken, nAudioMix.mVirtualDeviceId);
    return AUDIO_JAVA_SUCCESS;
}

static jint convertAudioMixToNative(JNIEnv *env, AudioMix *nAudioMix, const jobject jAudioMix) {
    nAudioMix->mMixType = env->GetIntField(jAudioMix, gAudioMixFields.mMixType);
    nAudioMix->mRouteFlags = env->GetIntField(jAudioMix, gAudioMixFields.mRouteFlags);
    nAudioMix->mDeviceType =
            static_cast<audio_devices_t>(env->GetIntField(jAudioMix, gAudioMixFields.mDeviceType));

    jstring jDeviceAddress =
            static_cast<jstring>(env->GetObjectField(jAudioMix, gAudioMixFields.mDeviceAddress));
    const char *nDeviceAddress = env->GetStringUTFChars(jDeviceAddress, NULL);
    nAudioMix->mDeviceAddress = String8(nDeviceAddress);
    env->ReleaseStringUTFChars(jDeviceAddress, nDeviceAddress);
    env->DeleteLocalRef(jDeviceAddress);

    nAudioMix->mCbFlags = env->GetIntField(jAudioMix, gAudioMixFields.mCallbackFlags);

    jobject jFormat = env->GetObjectField(jAudioMix, gAudioMixFields.mFormat);
    javaAudioFormatToNativeAudioConfig(env, &nAudioMix->mFormat, jFormat, false /*isInput*/);
    env->DeleteLocalRef(jFormat);

    jobject jRule = env->GetObjectField(jAudioMix, gAudioMixFields.mRule);
    nAudioMix->mAllowPrivilegedMediaPlaybackCapture =
            env->GetBooleanField(jRule, gAudioMixingRuleFields.mAllowPrivilegedPlaybackCapture);
    nAudioMix->mVoiceCommunicationCaptureAllowed =
            env->GetBooleanField(jRule, gAudioMixingRuleFields.mVoiceCommunicationCaptureAllowed);

    jobject jToken = env->GetObjectField(jAudioMix, gAudioMixFields.mToken);

    std::unique_ptr<AIBinder, decltype(&AIBinder_decStrong)>
            aiBinder(AIBinder_fromJavaBinder(env, jToken), &AIBinder_decStrong);
    nAudioMix->mToken = AIBinder_toPlatformBinder(aiBinder.get());

    nAudioMix->mVirtualDeviceId = env->GetIntField(jAudioMix, gAudioMixFields.mVirtualDeviceId);
    jint status = convertAudioMixingRuleToNative(env, jRule, &(nAudioMix->mCriteria));

    env->DeleteLocalRef(jRule);

    return status;
}

static jint
android_media_AudioSystem_registerPolicyMixes(JNIEnv *env, jobject clazz,
                                              jobject jMixesList, jboolean registration)
{
    ALOGV("registerPolicyMixes");

    if (jMixesList == NULL) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jMixesList, gArrayListClass)) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    jobjectArray jMixes =
            static_cast<jobjectArray>(env->CallObjectMethod(jMixesList, gArrayListMethods.toArray));
    jint numMixes = env->GetArrayLength(jMixes);
    if (numMixes > MAX_MIXES_PER_POLICY) {
        numMixes = MAX_MIXES_PER_POLICY;
    }

    status_t status;
    Vector <AudioMix> mixes;
    for (jint i = 0; i < numMixes; i++) {
        ScopedLocalRef<jobject> jAudioMix(env, env->GetObjectArrayElement(jMixes, i));
        if (!env->IsInstanceOf(jAudioMix.get(), gAudioMixClass)) {
            return AUDIO_JAVA_BAD_VALUE;
        }
        AudioMix mix;
        if (jint jStatus = convertAudioMixToNative(env, &mix, jAudioMix.get());
            jStatus != AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        mixes.add(mix);
    }

    ALOGV("AudioSystem::registerPolicyMixes numMixes %d registration %d", numMixes, registration);
    status = AudioSystem::registerPolicyMixes(mixes, registration);
    ALOGV("AudioSystem::registerPolicyMixes() returned %d", status);

    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_getRegisteredPolicyMixes(JNIEnv *env, jobject clazz,
                                                               jobject jMixes) {
    if (!audio_flags::audio_mix_test_api()) {
        return AUDIO_JAVA_INVALID_OPERATION;
    }

    status_t status;
    std::vector<AudioMix> mixes;
    ALOGV("AudioSystem::getRegisteredPolicyMixes");
    status = AudioSystem::getRegisteredPolicyMixes(mixes);
    ALOGV("AudioSystem::getRegisteredPolicyMixes() returned %zu mixes. Status=%d", mixes.size(),
          status);
    if (status != NO_ERROR) {
        return nativeToJavaStatus(status);
    }

    for (const auto &mix : mixes) {
        jobject jAudioMix = NULL;
        int conversionStatus = convertAudioMixFromNative(env, &jAudioMix, mix);
        if (conversionStatus != AUDIO_JAVA_SUCCESS) {
            return conversionStatus;
        }
        env->CallBooleanMethod(jMixes, gListMethods.add, jAudioMix);
    }

    return AUDIO_JAVA_SUCCESS;
}

static jint android_media_AudioSystem_updatePolicyMixes(JNIEnv *env, jobject clazz,
                                                        jobjectArray mixes,
                                                        jobjectArray updatedMixingRules) {
    if (mixes == nullptr || updatedMixingRules == nullptr) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    jsize updatesCount = env->GetArrayLength(mixes);
    if (updatesCount == 0 || updatesCount != env->GetArrayLength(updatedMixingRules)) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    std::vector<std::pair<AudioMix, std::vector<AudioMixMatchCriterion>>> updates(updatesCount);
    for (int i = 0; i < updatesCount; i++) {
        jobject jAudioMix = env->GetObjectArrayElement(mixes, i);
        jobject jAudioMixingRule = env->GetObjectArrayElement(updatedMixingRules, i);
        if (!env->IsInstanceOf(jAudioMix, gAudioMixClass) ||
            !env->IsInstanceOf(jAudioMixingRule, gAudioMixingRuleClass)) {
            return AUDIO_JAVA_BAD_VALUE;
        }

        jint ret;
        if ((ret = convertAudioMixToNative(env, &updates[i].first, jAudioMix)) !=
            AUDIO_JAVA_SUCCESS) {
            return ret;
        }
        if ((ret = convertAudioMixingRuleToNative(env, jAudioMixingRule, &updates[i].second)) !=
            AUDIO_JAVA_SUCCESS) {
            return ret;
        }
    }

    ALOGV("AudioSystem::updatePolicyMixes numMixes %d", updatesCount);
    int status = AudioSystem::updatePolicyMixes(updates);
    ALOGV("AudioSystem::updatePolicyMixes returned %d", status);

    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_setUidDeviceAffinities(JNIEnv *env, jobject clazz,
        jint uid, jintArray deviceTypes, jobjectArray deviceAddresses) {
    AudioDeviceTypeAddrVector deviceVector;
    jint results = getVectorOfAudioDeviceTypeAddr(env, deviceTypes, deviceAddresses, deviceVector);
    if (results != NO_ERROR) {
        return results;
    }
    status_t status = AudioSystem::setUidDeviceAffinities(uid, deviceVector);
    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_removeUidDeviceAffinities(JNIEnv *env, jobject clazz,
        jint uid) {
    status_t status = AudioSystem::removeUidDeviceAffinities(static_cast<uid_t>(uid));
    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_setUserIdDeviceAffinities(JNIEnv *env, jobject clazz,
                                                                jint userId, jintArray deviceTypes,
                                                                jobjectArray deviceAddresses) {
    AudioDeviceTypeAddrVector deviceVector;
    jint results = getVectorOfAudioDeviceTypeAddr(env, deviceTypes, deviceAddresses, deviceVector);
    if (results != NO_ERROR) {
        return results;
    }
    status_t status = AudioSystem::setUserIdDeviceAffinities(userId, deviceVector);
    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_removeUserIdDeviceAffinities(JNIEnv *env, jobject clazz,
                                                                   jint userId) {
    status_t status = AudioSystem::removeUserIdDeviceAffinities(userId);
    return nativeToJavaStatus(status);
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
    return AudioSystem::getStreamVolumeDB(static_cast<audio_stream_type_t>(stream), index,
                                          static_cast<audio_devices_t>(device));
}

static jint android_media_AudioSystem_getOffloadSupport(JNIEnv *env, jobject thiz, jint encoding,
                                                        jint sampleRate, jint channelMask,
                                                        jint channelIndexMask, jint streamType) {
    audio_offload_info_t format = AUDIO_INFO_INITIALIZER;
    format.format = static_cast<audio_format_t>(audioFormatToNative(encoding));
    format.sample_rate = sampleRate;
    format.channel_mask = nativeChannelMaskFromJavaChannelMasks(channelMask, channelIndexMask);
    format.stream_type = static_cast<audio_stream_type_t>(streamType);
    format.has_video = false;
    format.is_streaming = false;
    // offload duration unknown at this point:
    // client side code cannot access "audio.offload.min.duration.secs" property to make a query
    // agnostic of duration, so using acceptable estimate of 2mn
    format.duration_us = 120 * 1000000;
    return AudioSystem::getOffloadSupport(format);
}

static jint
android_media_AudioSystem_getMicrophones(JNIEnv *env, jobject thiz, jobject jMicrophonesInfo)
{
    ALOGV("getMicrophones");

    if (jMicrophonesInfo == NULL) {
        ALOGE("jMicrophonesInfo NULL MicrophoneInfo ArrayList");
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jMicrophonesInfo, gArrayListClass)) {
        ALOGE("getMicrophones not an arraylist");
        return AUDIO_JAVA_BAD_VALUE;
    }

    jint jStatus;
    std::vector<media::MicrophoneInfoFw> microphones;
    status_t status = AudioSystem::getMicrophones(&microphones);
    if (status != NO_ERROR) {
        ALOGE("AudioSystem::getMicrophones error %d", status);
        jStatus = nativeToJavaStatus(status);
        return jStatus;
    }
    if (microphones.size() == 0) {
        jStatus = AUDIO_JAVA_SUCCESS;
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

static jint android_media_AudioSystem_getHwOffloadFormatsSupportedForBluetoothMedia(
        JNIEnv *env, jobject thiz, jint deviceType, jobject jEncodingFormatList) {
    ALOGV("%s", __FUNCTION__);
    jint jStatus = AUDIO_JAVA_SUCCESS;
    if (!env->IsInstanceOf(jEncodingFormatList, gArrayListClass)) {
        ALOGE("%s: jEncodingFormatList not an ArrayList", __FUNCTION__);
        return AUDIO_JAVA_BAD_VALUE;
    }
    std::vector<audio_format_t> encodingFormats;
    status_t status =
            AudioSystem::getHwOffloadFormatsSupportedForBluetoothMedia(static_cast<audio_devices_t>(
                                                                               deviceType),
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

static jint android_media_AudioSystem_getSurroundFormats(JNIEnv *env, jobject thiz,
                                                         jobject jSurroundFormats) {
    ALOGV("getSurroundFormats");

    if (jSurroundFormats == nullptr) {
        ALOGE("jSurroundFormats is NULL");
        return static_cast<jint>(AUDIO_JAVA_BAD_VALUE);
    }
    if (!env->IsInstanceOf(jSurroundFormats, gMapClass)) {
        ALOGE("getSurroundFormats not a map");
        return static_cast<jint>(AUDIO_JAVA_BAD_VALUE);
    }

    jint jStatus;
    unsigned int numSurroundFormats = 0;
    status_t status = AudioSystem::getSurroundFormats(&numSurroundFormats, nullptr, nullptr);
    if (status != NO_ERROR) {
        ALOGE_IF(status != NO_ERROR, "AudioSystem::getSurroundFormats error %d", status);
        return nativeToJavaStatus(status);
    }
    if (numSurroundFormats == 0) {
        return static_cast<jint>(AUDIO_JAVA_SUCCESS);
    }
    auto surroundFormats = std::make_unique<audio_format_t[]>(numSurroundFormats);
    auto surroundFormatsEnabled = std::make_unique<bool[]>(numSurroundFormats);
    status = AudioSystem::getSurroundFormats(&numSurroundFormats, &surroundFormats[0],
                                             &surroundFormatsEnabled[0]);
    jStatus = nativeToJavaStatus(status);
    if (status != NO_ERROR) {
        ALOGE_IF(status != NO_ERROR, "AudioSystem::getSurroundFormats error %d", status);
        return jStatus;
    }
    for (size_t i = 0; i < numSurroundFormats; i++) {
        int audioFormat = audioFormatFromNative(surroundFormats[i]);
        if (audioFormat == ENCODING_INVALID) {
            // skipping Java layer unsupported audio formats
            ALOGW("Unknown surround native audio format for JAVA API: %u", surroundFormats[i]);
            continue;
        }
        jobject surroundFormat = env->NewObject(gIntegerClass, gIntegerCstor, audioFormat);
        jobject enabled = env->NewObject(gBooleanClass, gBooleanCstor, surroundFormatsEnabled[i]);
        env->CallObjectMethod(jSurroundFormats, gMapPut, surroundFormat, enabled);
        env->DeleteLocalRef(surroundFormat);
        env->DeleteLocalRef(enabled);
    }

    return jStatus;
}

static jint android_media_AudioSystem_getReportedSurroundFormats(JNIEnv *env, jobject thiz,
                                                                 jobject jSurroundFormats) {
    ALOGV("getReportedSurroundFormats");

    if (jSurroundFormats == nullptr) {
        ALOGE("jSurroundFormats is NULL");
        return static_cast<jint>(AUDIO_JAVA_BAD_VALUE);
    }
    if (!env->IsInstanceOf(jSurroundFormats, gArrayListClass)) {
        ALOGE("jSurroundFormats not an arraylist");
        return static_cast<jint>(AUDIO_JAVA_BAD_VALUE);
    }
    jint jStatus;
    unsigned int numSurroundFormats = 0;
    status_t status = AudioSystem::getReportedSurroundFormats(&numSurroundFormats, nullptr);
    if (status != NO_ERROR) {
        ALOGE_IF(status != NO_ERROR, "AudioSystem::getReportedSurroundFormats error %d", status);
        return nativeToJavaStatus(status);
    }
    if (numSurroundFormats == 0) {
        return static_cast<jint>(AUDIO_JAVA_SUCCESS);
    }
    auto surroundFormats = std::make_unique<audio_format_t[]>(numSurroundFormats);
    status = AudioSystem::getReportedSurroundFormats(&numSurroundFormats, &surroundFormats[0]);
    jStatus = nativeToJavaStatus(status);
    if (status != NO_ERROR) {
        ALOGE_IF(status != NO_ERROR, "AudioSystem::getReportedSurroundFormats error %d", status);
        return jStatus;
    }
    for (size_t i = 0; i < numSurroundFormats; i++) {
        int audioFormat = audioFormatFromNative(surroundFormats[i]);
        if (audioFormat == ENCODING_INVALID) {
            // skipping Java layer unsupported audio formats
            ALOGW("Unknown surround native audio format for JAVA API: %u", surroundFormats[i]);
            continue;
        }
        jobject surroundFormat = env->NewObject(gIntegerClass, gIntegerCstor, audioFormat);
        env->CallObjectMethod(jSurroundFormats, gArrayListMethods.add, surroundFormat);
        env->DeleteLocalRef(surroundFormat);
    }

    return jStatus;
}

static jint
android_media_AudioSystem_setSurroundFormatEnabled(JNIEnv *env, jobject thiz,
                                                   jint audioFormat, jboolean enabled)
{
    status_t status =
            AudioSystem::setSurroundFormatEnabled(audioFormatToNative(audioFormat), enabled);
    ALOGE_IF(status != NO_ERROR, "AudioSystem::setSurroundFormatEnabled error %d", status);
    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_getMaxChannelCount(JNIEnv *env, jobject thiz) {
    return FCC_LIMIT;
}

static jint android_media_AudioSystem_getMaxSampleRate(JNIEnv *env, jobject thiz) {
    return SAMPLE_RATE_HZ_MAX;
}

static jint android_media_AudioSystem_getMinSampleRate(JNIEnv *env, jobject thiz) {
    return SAMPLE_RATE_HZ_MIN;
}

static std::vector<uid_t> convertJIntArrayToUidVector(JNIEnv *env, jintArray jArray) {
    std::vector<uid_t> nativeVector;
    if (jArray != nullptr) {
        jsize len = env->GetArrayLength(jArray);

        if (len > 0) {
            int *nativeArray = nullptr;
            nativeArray = env->GetIntArrayElements(jArray, 0);
            if (nativeArray != nullptr) {
                for (size_t i = 0; i < static_cast<size_t>(len); i++) {
                    nativeVector.push_back(nativeArray[i]);
                }
                env->ReleaseIntArrayElements(jArray, nativeArray, 0);
            }
        }
    }
    return nativeVector;
}

static jint android_media_AudioSystem_setAssistantServicesUids(JNIEnv *env, jobject thiz,
                                                               jintArray uids) {
    std::vector<uid_t> nativeUidsVector = convertJIntArrayToUidVector(env, uids);

    status_t status = AudioSystem::setAssistantServicesUids(nativeUidsVector);

    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_setActiveAssistantServicesUids(JNIEnv *env, jobject thiz,
                                                                     jintArray activeUids) {
    std::vector<uid_t> nativeActiveUidsVector = convertJIntArrayToUidVector(env, activeUids);

    status_t status = AudioSystem::setActiveAssistantServicesUids(nativeActiveUidsVector);

    return nativeToJavaStatus(status);
}

static jint
android_media_AudioSystem_setA11yServicesUids(JNIEnv *env, jobject thiz, jintArray uids) {
    std::vector<uid_t> nativeUidsVector = convertJIntArrayToUidVector(env, uids);

    status_t status = AudioSystem::setA11yServicesUids(nativeUidsVector);
    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_setCurrentImeUid(JNIEnv *env, jobject thiz, jint uid) {
    status_t status = AudioSystem::setCurrentImeUid(uid);
    return nativeToJavaStatus(status);
}

static jboolean
android_media_AudioSystem_isHapticPlaybackSupported(JNIEnv *env, jobject thiz)
{
    return AudioSystem::isHapticPlaybackSupported();
}

static jboolean android_media_AudioSystem_isUltrasoundSupported(JNIEnv *env, jobject thiz) {
    return AudioSystem::isUltrasoundSupported();
}

static jint android_media_AudioSystem_setSupportedSystemUsages(JNIEnv *env, jobject thiz,
                                                               jintArray systemUsages) {
    std::vector<audio_usage_t> nativeSystemUsagesVector;

    if (systemUsages == nullptr) {
        return AUDIO_JAVA_BAD_VALUE;
    }

    int *nativeSystemUsages = nullptr;
    nativeSystemUsages = env->GetIntArrayElements(systemUsages, 0);

    if (nativeSystemUsages != nullptr) {
        jsize len = env->GetArrayLength(systemUsages);
        for (size_t i = 0; i < static_cast<size_t>(len); i++) {
            audio_usage_t nativeAudioUsage =
                    static_cast<audio_usage_t>(nativeSystemUsages[i]);
            nativeSystemUsagesVector.push_back(nativeAudioUsage);
        }
        env->ReleaseIntArrayElements(systemUsages, nativeSystemUsages, 0);
    }

    status_t status = AudioSystem::setSupportedSystemUsages(nativeSystemUsagesVector);
    return nativeToJavaStatus(status);
}

static jint
android_media_AudioSystem_setAllowedCapturePolicy(JNIEnv *env, jobject thiz, jint uid, jint flags) {
    return AudioSystem::setAllowedCapturePolicy(uid, static_cast<audio_flags_mask_t>(flags));
}

static jint
android_media_AudioSystem_setRttEnabled(JNIEnv *env, jobject thiz, jboolean enabled)
{
    return check_AudioSystem_Command(AudioSystem::setRttEnabled(enabled));
}

static jint
android_media_AudioSystem_setAudioHalPids(JNIEnv *env, jobject clazz, jintArray jPids)
{
    if (jPids == NULL) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    pid_t *nPidsArray = reinterpret_cast<pid_t *>(env->GetIntArrayElements(jPids, nullptr));
    std::vector<pid_t> nPids(nPidsArray, nPidsArray + env->GetArrayLength(jPids));
    status_t status = AudioSystem::setAudioHalPids(nPids);
    env->ReleaseIntArrayElements(jPids, nPidsArray, 0);
    jint jStatus = nativeToJavaStatus(status);
    return jStatus;
}

static jboolean
android_media_AudioSystem_isCallScreeningModeSupported(JNIEnv *env, jobject thiz)
{
    return AudioSystem::isCallScreenModeSupported();
}

static jint android_media_AudioSystem_setDevicesRoleForStrategy(JNIEnv *env, jobject thiz,
                                                                jint strategy, jint role,
                                                                jintArray jDeviceTypes,
                                                                jobjectArray jDeviceAddresses) {
    AudioDeviceTypeAddrVector nDevices;
    jint results = getVectorOfAudioDeviceTypeAddr(env, jDeviceTypes, jDeviceAddresses, nDevices);
    if (results != NO_ERROR) {
        return results;
    }
    int status = check_AudioSystem_Command(
            AudioSystem::setDevicesRoleForStrategy(static_cast<product_strategy_t>(strategy),
                                                   static_cast<device_role_t>(role), nDevices));
    return status;
}

static jint android_media_AudioSystem_removeDevicesRoleForStrategy(JNIEnv *env, jobject thiz,
                                                                   jint strategy, jint role,
                                                                   jintArray jDeviceTypes,
                                                                   jobjectArray jDeviceAddresses) {
    AudioDeviceTypeAddrVector nDevices;
    jint results = getVectorOfAudioDeviceTypeAddr(env, jDeviceTypes, jDeviceAddresses, nDevices);
    if (results != NO_ERROR) {
        return results;
    }
    int status = check_AudioSystem_Command(
            AudioSystem::removeDevicesRoleForStrategy(static_cast<product_strategy_t>(strategy),
                                                      static_cast<device_role_t>(role), nDevices));
    return (jint)status;
}

static jint android_media_AudioSystem_clearDevicesRoleForStrategy(JNIEnv *env, jobject thiz,
                                                                  jint strategy, jint role) {
    return (jint)
            check_AudioSystem_Command(AudioSystem::clearDevicesRoleForStrategy((product_strategy_t)
                                                                                       strategy,
                                                                               (device_role_t)role),
                                      {NAME_NOT_FOUND});
}

static jint android_media_AudioSystem_getDevicesForRoleAndStrategy(JNIEnv *env, jobject thiz,
                                                                   jint strategy, jint role,
                                                                   jobject jDevices) {
    AudioDeviceTypeAddrVector nDevices;
    status_t status = check_AudioSystem_Command(
            AudioSystem::getDevicesForRoleAndStrategy(static_cast<product_strategy_t>(strategy),
                                                      static_cast<device_role_t>(role), nDevices));
    if (status != NO_ERROR) {
        return status;
    }
    for (const auto &device : nDevices) {
        jobject jAudioDeviceAttributes = NULL;
        jint jStatus = createAudioDeviceAttributesFromNative(env, &jAudioDeviceAttributes, &device);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        env->CallBooleanMethod(jDevices, gListMethods.add, jAudioDeviceAttributes);
        env->DeleteLocalRef(jAudioDeviceAttributes);
    }
    return AUDIO_JAVA_SUCCESS;
}

static jint android_media_AudioSystem_setDevicesRoleForCapturePreset(
        JNIEnv *env, jobject thiz, jint capturePreset, jint role, jintArray jDeviceTypes,
        jobjectArray jDeviceAddresses) {
    AudioDeviceTypeAddrVector nDevices;
    jint results = getVectorOfAudioDeviceTypeAddr(env, jDeviceTypes, jDeviceAddresses, nDevices);
    if (results != NO_ERROR) {
        return results;
    }
    int status = check_AudioSystem_Command(
            AudioSystem::setDevicesRoleForCapturePreset(static_cast<audio_source_t>(capturePreset),
                                                        static_cast<device_role_t>(role),
                                                        nDevices));
    return status;
}

static jint android_media_AudioSystem_addDevicesRoleForCapturePreset(
        JNIEnv *env, jobject thiz, jint capturePreset, jint role, jintArray jDeviceTypes,
        jobjectArray jDeviceAddresses) {
    AudioDeviceTypeAddrVector nDevices;
    jint results = getVectorOfAudioDeviceTypeAddr(env, jDeviceTypes, jDeviceAddresses, nDevices);
    if (results != NO_ERROR) {
        return results;
    }
    int status = check_AudioSystem_Command(
            AudioSystem::addDevicesRoleForCapturePreset(static_cast<audio_source_t>(capturePreset),
                                                        static_cast<device_role_t>(role),
                                                        nDevices));
    return status;
}

static jint android_media_AudioSystem_removeDevicesRoleForCapturePreset(
        JNIEnv *env, jobject thiz, jint capturePreset, jint role, jintArray jDeviceTypes,
        jobjectArray jDeviceAddresses) {
    AudioDeviceTypeAddrVector nDevices;
    jint results = getVectorOfAudioDeviceTypeAddr(env, jDeviceTypes, jDeviceAddresses, nDevices);
    if (results != NO_ERROR) {
        return results;
    }
    int status = check_AudioSystem_Command(
            AudioSystem::removeDevicesRoleForCapturePreset(static_cast<audio_source_t>(
                                                                   capturePreset),
                                                           static_cast<device_role_t>(role),
                                                           nDevices));
    return status;
}

static jint android_media_AudioSystem_clearDevicesRoleForCapturePreset(JNIEnv *env, jobject thiz,
                                                                       jint capturePreset,
                                                                       jint role) {
    return static_cast<jint>(check_AudioSystem_Command(
            AudioSystem::clearDevicesRoleForCapturePreset(static_cast<audio_source_t>(
                                                                  capturePreset),
                                                          static_cast<device_role_t>(role))));
}

static jint android_media_AudioSystem_getDevicesForRoleAndCapturePreset(JNIEnv *env, jobject thiz,
                                                                        jint capturePreset,
                                                                        jint role,
                                                                        jobject jDevices) {
    AudioDeviceTypeAddrVector nDevices;
    status_t status = check_AudioSystem_Command(
            AudioSystem::getDevicesForRoleAndCapturePreset(static_cast<audio_source_t>(
                                                                   capturePreset),
                                                           static_cast<device_role_t>(role),
                                                           nDevices));
    if (status != NO_ERROR) {
        return status;
    }
    for (const auto &device : nDevices) {
        jobject jAudioDeviceAttributes = NULL;
        jint jStatus = createAudioDeviceAttributesFromNative(env, &jAudioDeviceAttributes, &device);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        env->CallBooleanMethod(jDevices, gListMethods.add, jAudioDeviceAttributes);
        env->DeleteLocalRef(jAudioDeviceAttributes);
    }
    return AUDIO_JAVA_SUCCESS;
}

static jint android_media_AudioSystem_getDevicesForAttributes(JNIEnv *env, jobject thiz,
                                                              jobject jaa,
                                                              jobjectArray jDeviceArray,
                                                              jboolean forVolume) {
    const jsize maxResultSize = env->GetArrayLength(jDeviceArray);
    // the JNI is always expected to provide us with an array capable of holding enough
    // devices i.e. the most we ever route a track to. This is preferred over receiving an ArrayList
    // with reverse JNI to make the array grow as need as this would be less efficient, and some
    // components call this method often
    if (jDeviceArray == nullptr || maxResultSize == 0) {
        ALOGE("%s invalid array to store AudioDeviceAttributes", __FUNCTION__);
        return AUDIO_JAVA_BAD_VALUE;
    }

    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    AudioDeviceTypeAddrVector devices;
    jStatus = check_AudioSystem_Command(
            AudioSystem::getDevicesForAttributes(*(paa.get()), &devices, forVolume));
    if (jStatus != NO_ERROR) {
        return jStatus;
    }

    if (devices.size() > static_cast<size_t>(maxResultSize)) {
        return AUDIO_JAVA_INVALID_OPERATION;
    }
    size_t index = 0;
    jobject jAudioDeviceAttributes = NULL;
    for (const auto& device : devices) {
        jStatus = createAudioDeviceAttributesFromNative(env, &jAudioDeviceAttributes, &device);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        env->SetObjectArrayElement(jDeviceArray, index++, jAudioDeviceAttributes);
    }
    return jStatus;
}

static jint android_media_AudioSystem_setVibratorInfos(JNIEnv *env, jobject thiz,
                                                       jobject jVibrators) {
    if (!env->IsInstanceOf(jVibrators, gListClass)) {
        return AUDIO_JAVA_BAD_VALUE;
    }
    const jint size = env->CallIntMethod(jVibrators, gListMethods.size);
    std::vector<media::AudioVibratorInfo> vibratorInfos;
    for (jint i = 0; i < size; ++i) {
        ScopedLocalRef<jobject> jVibrator(env,
                                          env->CallObjectMethod(jVibrators, gListMethods.get, i));
        if (!env->IsInstanceOf(jVibrator.get(), gVibratorClass)) {
            return AUDIO_JAVA_BAD_VALUE;
        }
        media::AudioVibratorInfo vibratorInfo;
        vibratorInfo.id = env->CallIntMethod(jVibrator.get(), gVibratorMethods.getId);
        vibratorInfo.resonantFrequency =
                env->CallFloatMethod(jVibrator.get(), gVibratorMethods.getResonantFrequency);
        vibratorInfo.qFactor = env->CallFloatMethod(jVibrator.get(), gVibratorMethods.getQFactor);
        vibratorInfo.maxAmplitude =
                env->CallFloatMethod(jVibrator.get(), gVibratorMethods.getMaxAmplitude);
        vibratorInfos.push_back(vibratorInfo);
    }
    return check_AudioSystem_Command(AudioSystem::setVibratorInfos(vibratorInfos));
}

static jobject android_media_AudioSystem_getSpatializer(JNIEnv *env, jobject thiz,
                                                       jobject jISpatializerCallback) {
    sp<media::INativeSpatializerCallback> nISpatializerCallback
            = interface_cast<media::INativeSpatializerCallback>(
                    ibinderForJavaObject(env, jISpatializerCallback));
    sp<media::ISpatializer> nSpatializer;
    status_t status = AudioSystem::getSpatializer(nISpatializerCallback,
                                        &nSpatializer);
    if (status != NO_ERROR) {
        return nullptr;
    }
    return javaObjectForIBinder(env, IInterface::asBinder(nSpatializer));
}

static jboolean android_media_AudioSystem_canBeSpatialized(JNIEnv *env, jobject thiz,
                                                       jobject jaa, jobject jFormat,
                                                       jobjectArray jDeviceArray) {
    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return false;
    }

    AudioDeviceTypeAddrVector nDevices;

    const size_t numDevices = env->GetArrayLength(jDeviceArray);
    for (size_t i = 0;  i < numDevices; ++i) {
        AudioDeviceTypeAddr device;
        jobject jDevice  = env->GetObjectArrayElement(jDeviceArray, i);
        if (jDevice == nullptr) {
            return false;
        }
        jStatus = createAudioDeviceTypeAddrFromJava(env, &device, jDevice);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            return false;
        }
        nDevices.push_back(device);
    }

    audio_config_t nConfig;
    javaAudioFormatToNativeAudioConfig(env, &nConfig, jFormat, false /*isInput*/);

    bool canBeSpatialized;
    status_t status =
            AudioSystem::canBeSpatialized(paa.get(), &nConfig, nDevices, &canBeSpatialized);
    if (status != NO_ERROR) {
        ALOGW("%s native returned error %d", __func__, status);
        return false;
    }
    return canBeSpatialized;
}

static jobject android_media_AudioSystem_nativeGetSoundDose(JNIEnv *env, jobject thiz,
                                                            jobject jISoundDoseCallback) {
    sp<media::ISoundDoseCallback> nISoundDoseCallback = interface_cast<media::ISoundDoseCallback>(
            ibinderForJavaObject(env, jISoundDoseCallback));

    sp<media::ISoundDose> nSoundDose;
    status_t status = AudioSystem::getSoundDoseInterface(nISoundDoseCallback, &nSoundDose);

    if (status != NO_ERROR) {
        return nullptr;
    }
    return javaObjectForIBinder(env, IInterface::asBinder(nSoundDose));
}

// keep these values in sync with AudioSystem.java
#define DIRECT_NOT_SUPPORTED 0
#define DIRECT_OFFLOAD_SUPPORTED 1
#define DIRECT_OFFLOAD_GAPLESS_SUPPORTED 3
#define DIRECT_BITSTREAM_SUPPORTED 4

static jint convertAudioDirectModeFromNative(audio_direct_mode_t directMode) {
    jint result = DIRECT_NOT_SUPPORTED;
    if ((directMode & AUDIO_DIRECT_OFFLOAD_SUPPORTED) != AUDIO_DIRECT_NOT_SUPPORTED) {
        result |= DIRECT_OFFLOAD_SUPPORTED;
    }
    if ((directMode & AUDIO_DIRECT_OFFLOAD_GAPLESS_SUPPORTED) != AUDIO_DIRECT_NOT_SUPPORTED) {
        result |= DIRECT_OFFLOAD_GAPLESS_SUPPORTED;
    }
    if ((directMode & AUDIO_DIRECT_BITSTREAM_SUPPORTED) != AUDIO_DIRECT_NOT_SUPPORTED) {
        result |= DIRECT_BITSTREAM_SUPPORTED;
    }
    return result;
}

static jint android_media_AudioSystem_getDirectPlaybackSupport(JNIEnv *env, jobject thiz,
                                                               jobject jFormat, jobject jaa) {
    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return DIRECT_NOT_SUPPORTED;
    }

    audio_config_t nConfig;
    javaAudioFormatToNativeAudioConfig(env, &nConfig, jFormat, false /*isInput*/);

    audio_direct_mode_t directMode;
    status_t status = AudioSystem::getDirectPlaybackSupport(paa.get(), &nConfig, &directMode);
    if (status != NO_ERROR) {
        ALOGW("%s native returned error %d", __func__, status);
        return DIRECT_NOT_SUPPORTED;
    }
    return convertAudioDirectModeFromNative(directMode);
}

static jint android_media_AudioSystem_getDirectProfilesForAttributes(JNIEnv *env, jobject thiz,
                                                                     jobject jAudioAttributes,
                                                                     jobject jAudioProfilesList) {
    ALOGV("getDirectProfilesForAttributes");

    if (jAudioAttributes == nullptr) {
        ALOGE("jAudioAttributes is NULL");
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (jAudioProfilesList == nullptr) {
        ALOGE("jAudioProfilesList is NULL");
        return AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jAudioProfilesList, gArrayListClass)) {
        ALOGE("jAudioProfilesList not an ArrayList");
        return AUDIO_JAVA_BAD_VALUE;
    }

    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jAudioAttributes, paa.get());
    if (jStatus != AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    std::vector<audio_profile> audioProfiles;
    status_t status = AudioSystem::getDirectProfilesForAttributes(paa.get(), &audioProfiles);
    if (status != NO_ERROR) {
        ALOGE("AudioSystem::getDirectProfilesForAttributes error %d", status);
        jStatus = nativeToJavaStatus(status);
        return jStatus;
    }

    for (const auto &audioProfile : audioProfiles) {
        ScopedLocalRef<jobject> jAudioProfile(env);
        jint jConvertProfileStatus = convertAudioProfileFromNative(
                                        env, &jAudioProfile, &audioProfile, false);
        if (jConvertProfileStatus == AUDIO_JAVA_BAD_VALUE) {
            // skipping Java layer unsupported audio formats
            continue;
        }
        if (jConvertProfileStatus != AUDIO_JAVA_SUCCESS) {
            return jConvertProfileStatus;
        }
        env->CallBooleanMethod(jAudioProfilesList, gArrayListMethods.add, jAudioProfile.get());
    }
    return jStatus;
}

static jint android_media_AudioSystem_getSupportedMixerAttributes(JNIEnv *env, jobject thiz,
                                                                  jint jDeviceId,
                                                                  jobject jAudioMixerAttributes) {
    ALOGV("%s", __func__);
    if (jAudioMixerAttributes == NULL) {
        ALOGE("getSupportedMixerAttributes NULL AudioMixerAttributes list");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jAudioMixerAttributes, gListClass)) {
        ALOGE("getSupportedMixerAttributes not a list");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    std::vector<audio_mixer_attributes_t> nMixerAttributes;
    status_t status = AudioSystem::getSupportedMixerAttributes((audio_port_handle_t)jDeviceId,
                                                               &nMixerAttributes);
    if (status != NO_ERROR) {
        return nativeToJavaStatus(status);
    }
    for (const auto &mixerAttr : nMixerAttributes) {
        ScopedLocalRef<jobject> jMixerAttributes(env,
                                                 convertAudioMixerAttributesFromNative(env,
                                                                                       &mixerAttr));
        if (jMixerAttributes.get() == nullptr) {
            return (jint)AUDIO_JAVA_ERROR;
        }

        env->CallBooleanMethod(jAudioMixerAttributes, gListMethods.add, jMixerAttributes.get());
    }

    return (jint)AUDIO_JAVA_SUCCESS;
}

static jint android_media_AudioSystem_setPreferredMixerAttributes(JNIEnv *env, jobject thiz,
                                                                  jobject jAudioAttributes,
                                                                  jint portId, jint uid,
                                                                  jobject jAudioMixerAttributes) {
    ALOGV("%s", __func__);

    if (jAudioAttributes == nullptr) {
        ALOGE("jAudioAttributes is NULL");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (jAudioMixerAttributes == nullptr) {
        ALOGE("jAudioMixerAttributes is NULL");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jAudioAttributes, paa.get());
    if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    audio_mixer_attributes_t mixerAttributes = AUDIO_MIXER_ATTRIBUTES_INITIALIZER;
    jStatus = convertAudioMixerAttributesToNative(env, jAudioMixerAttributes, &mixerAttributes);
    if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    status_t status =
            AudioSystem::setPreferredMixerAttributes(paa.get(), (audio_port_handle_t)portId,
                                                     (uid_t)uid, &mixerAttributes);
    return nativeToJavaStatus(status);
}

static jint android_media_AudioSystem_getPreferredMixerAttributes(JNIEnv *env, jobject thiz,
                                                                  jobject jAudioAttributes,
                                                                  jint portId,
                                                                  jobject jAudioMixerAttributes) {
    ALOGV("%s", __func__);

    if (jAudioAttributes == nullptr) {
        ALOGE("getPreferredMixerAttributes jAudioAttributes is NULL");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (jAudioMixerAttributes == NULL) {
        ALOGE("getPreferredMixerAttributes NULL AudioMixerAttributes list");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jAudioMixerAttributes, gListClass)) {
        ALOGE("getPreferredMixerAttributes not a list");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jAudioAttributes, paa.get());
    if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    std::optional<audio_mixer_attributes_t> nMixerAttributes;
    status_t status =
            AudioSystem::getPreferredMixerAttributes(paa.get(), (audio_port_handle_t)portId,
                                                     &nMixerAttributes);
    if (status != NO_ERROR) {
        return nativeToJavaStatus(status);
    }

    ScopedLocalRef<jobject>
            jMixerAttributes(env,
                             convertAudioMixerAttributesFromNative(env,
                                                                   nMixerAttributes.has_value()
                                                                           ? &nMixerAttributes
                                                                                      .value()
                                                                           : nullptr));
    if (jMixerAttributes.get() == nullptr) {
        return (jint)AUDIO_JAVA_ERROR;
    }

    env->CallBooleanMethod(jAudioMixerAttributes, gListMethods.add, jMixerAttributes.get());
    return AUDIO_JAVA_SUCCESS;
}

static jint android_media_AudioSystem_clearPreferredMixerAttributes(JNIEnv *env, jobject thiz,
                                                                    jobject jAudioAttributes,
                                                                    jint portId, jint uid) {
    ALOGV("%s", __func__);

    if (jAudioAttributes == nullptr) {
        ALOGE("jAudioAttributes is NULL");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    JNIAudioAttributeHelper::UniqueAaPtr paa = JNIAudioAttributeHelper::makeUnique();
    jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jAudioAttributes, paa.get());
    if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
        return jStatus;
    }

    status_t status =
            AudioSystem::clearPreferredMixerAttributes(paa.get(), (audio_port_handle_t)portId,
                                                       (uid_t)uid);
    return nativeToJavaStatus(status);
}

static jboolean android_media_AudioSystem_supportsBluetoothVariableLatency(JNIEnv *env,
                                                                           jobject thiz) {
    bool supports;
    if (AudioSystem::supportsBluetoothVariableLatency(&supports) != NO_ERROR) {
        supports = false;
    }
    return supports;
}

static int android_media_AudioSystem_setBluetoothVariableLatencyEnabled(JNIEnv *env, jobject thiz,
                                                                        jboolean enabled) {
    return check_AudioSystem_Command(AudioSystem::setBluetoothVariableLatencyEnabled(enabled));
}

static jboolean android_media_AudioSystem_isBluetoothVariableLatencyEnabled(JNIEnv *env,
                                                                            jobject thiz) {
    bool enabled;
    if (AudioSystem::isBluetoothVariableLatencyEnabled(&enabled) != NO_ERROR) {
        enabled = false;
    }
    return enabled;
}

class JavaSystemPropertyListener {
  public:
    JavaSystemPropertyListener(JNIEnv* env, jobject javaCallback, std::string sysPropName) :
            mCallback {javaCallback, env},
            mSysPropName(sysPropName),
            mCachedProperty(android::base::CachedProperty{std::move(sysPropName)}),
            mListenerThread([this](mediautils::stop_token stok) mutable {
                while (!stok.stop_requested()) {
                    using namespace std::chrono_literals;
                    // 1s timeout so this thread can eventually respond to the stop token
                    std::string newVal = mCachedProperty.WaitForChange(1000ms) ?: "";
                    updateValue(newVal);
                }
            }) {}

    void triggerUpdateIfChanged() {
        // We must check the property without using the cached property due to thread safety issues
        std::string newVal = base::GetProperty(mSysPropName, "");
        updateValue(newVal);
    }

  private:
    void updateValue(std::string newVal) {
        if (newVal == "") return;
        std::lock_guard l{mLock};
        if (mLastVal == newVal) return;
        const auto threadEnv = GetOrAttachJNIEnvironment(gVm);
        threadEnv->CallVoidMethod(mCallback.get(), gRunnableClassInfo.run);
        mLastVal = std::move(newVal);
    }

    // Should outlive thread object
    const GlobalRef mCallback;
    const std::string mSysPropName;
    android::base::CachedProperty mCachedProperty;
    std::string mLastVal = "";
    std::mutex mLock;
    const mediautils::jthread mListenerThread;
};

// A logical set keyed by address
std::vector<std::unique_ptr<JavaSystemPropertyListener>> gSystemPropertyListeners;
std::mutex gSysPropLock{};

static jlong android_media_AudioSystem_listenForSystemPropertyChange(JNIEnv *env,  jobject thiz,
        jstring sysProp,
        jobject javaCallback) {
    ScopedUtfChars sysPropChars{env, sysProp};
    auto listener = std::make_unique<JavaSystemPropertyListener>(env, javaCallback,
            std::string{sysPropChars.c_str()});
    std::unique_lock _l{gSysPropLock};
    gSystemPropertyListeners.push_back(std::move(listener));
    return reinterpret_cast<jlong>(gSystemPropertyListeners.back().get());
}

static void android_media_AudioSystem_triggerSystemPropertyUpdate(JNIEnv *env,  jobject thiz,
        jlong nativeHandle) {
    std::unique_lock _l{gSysPropLock};
    const auto iter = std::find_if(gSystemPropertyListeners.begin(), gSystemPropertyListeners.end(),
            [nativeHandle](const auto& x) { return reinterpret_cast<jlong>(x.get()) == nativeHandle; });
    if (iter != gSystemPropertyListeners.end()) {
        (*iter)->triggerUpdateIfChanged();
    } else {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid handle");
    }
}


// ----------------------------------------------------------------------------

#define MAKE_AUDIO_SYSTEM_METHOD(x) \
    MAKE_JNI_NATIVE_METHOD_AUTOSIG(#x, android_media_AudioSystem_##x)

static const JNINativeMethod gMethods[] =
        {MAKE_AUDIO_SYSTEM_METHOD(setParameters),
         MAKE_AUDIO_SYSTEM_METHOD(getParameters),
         MAKE_AUDIO_SYSTEM_METHOD(muteMicrophone),
         MAKE_AUDIO_SYSTEM_METHOD(isMicrophoneMuted),
         MAKE_AUDIO_SYSTEM_METHOD(isStreamActive),
         MAKE_AUDIO_SYSTEM_METHOD(isStreamActiveRemotely),
         MAKE_AUDIO_SYSTEM_METHOD(isSourceActive),
         MAKE_AUDIO_SYSTEM_METHOD(newAudioSessionId),
         MAKE_AUDIO_SYSTEM_METHOD(newAudioPlayerId),
         MAKE_AUDIO_SYSTEM_METHOD(newAudioRecorderId),
         MAKE_JNI_NATIVE_METHOD("setDeviceConnectionState", "(ILandroid/os/Parcel;I)I",
                                android_media_AudioSystem_setDeviceConnectionState),
         MAKE_AUDIO_SYSTEM_METHOD(getDeviceConnectionState),
         MAKE_AUDIO_SYSTEM_METHOD(handleDeviceConfigChange),
         MAKE_AUDIO_SYSTEM_METHOD(setPhoneState),
         MAKE_AUDIO_SYSTEM_METHOD(setForceUse),
         MAKE_AUDIO_SYSTEM_METHOD(getForceUse),
         MAKE_AUDIO_SYSTEM_METHOD(setDeviceAbsoluteVolumeEnabled),
         MAKE_AUDIO_SYSTEM_METHOD(initStreamVolume),
         MAKE_AUDIO_SYSTEM_METHOD(setStreamVolumeIndex),
         MAKE_AUDIO_SYSTEM_METHOD(getStreamVolumeIndex),
         MAKE_JNI_NATIVE_METHOD("setVolumeIndexForAttributes",
                                "(Landroid/media/AudioAttributes;II)I",
                                android_media_AudioSystem_setVolumeIndexForAttributes),
         MAKE_JNI_NATIVE_METHOD("getVolumeIndexForAttributes",
                                "(Landroid/media/AudioAttributes;I)I",
                                android_media_AudioSystem_getVolumeIndexForAttributes),
         MAKE_JNI_NATIVE_METHOD("getMinVolumeIndexForAttributes",
                                "(Landroid/media/AudioAttributes;)I",
                                android_media_AudioSystem_getMinVolumeIndexForAttributes),
         MAKE_JNI_NATIVE_METHOD("getMaxVolumeIndexForAttributes",
                                "(Landroid/media/AudioAttributes;)I",
                                android_media_AudioSystem_getMaxVolumeIndexForAttributes),
         MAKE_AUDIO_SYSTEM_METHOD(setMasterVolume),
         MAKE_AUDIO_SYSTEM_METHOD(getMasterVolume),
         MAKE_AUDIO_SYSTEM_METHOD(setMasterMute),
         MAKE_AUDIO_SYSTEM_METHOD(getMasterMute),
         MAKE_AUDIO_SYSTEM_METHOD(setMasterMono),
         MAKE_AUDIO_SYSTEM_METHOD(getMasterMono),
         MAKE_AUDIO_SYSTEM_METHOD(setMasterBalance),
         MAKE_AUDIO_SYSTEM_METHOD(getMasterBalance),
         MAKE_AUDIO_SYSTEM_METHOD(getPrimaryOutputSamplingRate),
         MAKE_AUDIO_SYSTEM_METHOD(getPrimaryOutputFrameCount),
         MAKE_AUDIO_SYSTEM_METHOD(getOutputLatency),
         MAKE_AUDIO_SYSTEM_METHOD(setLowRamDevice),
         MAKE_AUDIO_SYSTEM_METHOD(checkAudioFlinger),
         MAKE_JNI_NATIVE_METHOD("setAudioFlingerBinder", "(Landroid/os/IBinder;)V",
                                android_media_AudioSystem_setAudioFlingerBinder),
         MAKE_JNI_NATIVE_METHOD("listAudioPorts", "(Ljava/util/ArrayList;[I)I",
                                android_media_AudioSystem_listAudioPorts),
         MAKE_JNI_NATIVE_METHOD("getSupportedDeviceTypes", "(ILandroid/util/IntArray;)I",
                                android_media_AudioSystem_getSupportedDeviceTypes),
         MAKE_JNI_NATIVE_METHOD("createAudioPatch",
                                "([Landroid/media/AudioPatch;[Landroid/media/"
                                "AudioPortConfig;[Landroid/media/AudioPortConfig;)I",
                                android_media_AudioSystem_createAudioPatch),
         MAKE_JNI_NATIVE_METHOD("releaseAudioPatch", "(Landroid/media/AudioPatch;)I",
                                android_media_AudioSystem_releaseAudioPatch),
         MAKE_JNI_NATIVE_METHOD("listAudioPatches", "(Ljava/util/ArrayList;[I)I",
                                android_media_AudioSystem_listAudioPatches),
         MAKE_JNI_NATIVE_METHOD("setAudioPortConfig", "(Landroid/media/AudioPortConfig;)I",
                                android_media_AudioSystem_setAudioPortConfig),
         MAKE_JNI_NATIVE_METHOD("startAudioSource",
                                "(Landroid/media/AudioPortConfig;Landroid/media/AudioAttributes;)I",
                                android_media_AudioSystem_startAudioSource),
         MAKE_AUDIO_SYSTEM_METHOD(stopAudioSource),
         MAKE_AUDIO_SYSTEM_METHOD(getAudioHwSyncForSession),
         MAKE_JNI_NATIVE_METHOD("registerPolicyMixes", "(Ljava/util/ArrayList;Z)I",
                                android_media_AudioSystem_registerPolicyMixes),
         MAKE_JNI_NATIVE_METHOD("getRegisteredPolicyMixes", "(Ljava/util/List;)I",
                                android_media_AudioSystem_getRegisteredPolicyMixes),
         MAKE_JNI_NATIVE_METHOD("updatePolicyMixes",
                                "([Landroid/media/audiopolicy/AudioMix;[Landroid/media/audiopolicy/"
                                "AudioMixingRule;)I",
                                android_media_AudioSystem_updatePolicyMixes),
         MAKE_JNI_NATIVE_METHOD("setUidDeviceAffinities", "(I[I[Ljava/lang/String;)I",
                                android_media_AudioSystem_setUidDeviceAffinities),
         MAKE_AUDIO_SYSTEM_METHOD(removeUidDeviceAffinities),
         MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_register_dynamic_policy_callback",
                                        android_media_AudioSystem_registerDynPolicyCallback),
         MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_register_recording_callback",
                                        android_media_AudioSystem_registerRecordingCallback),
         MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_register_routing_callback",
                                        android_media_AudioSystem_registerRoutingCallback),
         MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_register_vol_range_init_req_callback",
                                        android_media_AudioSystem_registerVolRangeInitReqCallback),
         MAKE_AUDIO_SYSTEM_METHOD(systemReady),
         MAKE_AUDIO_SYSTEM_METHOD(getStreamVolumeDB),
         MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_get_offload_support",
                                        android_media_AudioSystem_getOffloadSupport),
         MAKE_JNI_NATIVE_METHOD("getMicrophones", "(Ljava/util/ArrayList;)I",
                                android_media_AudioSystem_getMicrophones),
         MAKE_JNI_NATIVE_METHOD("getSurroundFormats", "(Ljava/util/Map;)I",
                                android_media_AudioSystem_getSurroundFormats),
         MAKE_JNI_NATIVE_METHOD("getReportedSurroundFormats", "(Ljava/util/ArrayList;)I",
                                android_media_AudioSystem_getReportedSurroundFormats),
         MAKE_AUDIO_SYSTEM_METHOD(setSurroundFormatEnabled),
         MAKE_AUDIO_SYSTEM_METHOD(setAssistantServicesUids),
         MAKE_AUDIO_SYSTEM_METHOD(setActiveAssistantServicesUids),
         MAKE_AUDIO_SYSTEM_METHOD(setA11yServicesUids),
         MAKE_AUDIO_SYSTEM_METHOD(isHapticPlaybackSupported),
         MAKE_AUDIO_SYSTEM_METHOD(isUltrasoundSupported),
         MAKE_JNI_NATIVE_METHOD(
                 "getHwOffloadFormatsSupportedForBluetoothMedia", "(ILjava/util/ArrayList;)I",
                 android_media_AudioSystem_getHwOffloadFormatsSupportedForBluetoothMedia),
         MAKE_AUDIO_SYSTEM_METHOD(setSupportedSystemUsages),
         MAKE_AUDIO_SYSTEM_METHOD(setAllowedCapturePolicy),
         MAKE_AUDIO_SYSTEM_METHOD(setRttEnabled),
         MAKE_AUDIO_SYSTEM_METHOD(setAudioHalPids),
         MAKE_AUDIO_SYSTEM_METHOD(isCallScreeningModeSupported),
         MAKE_JNI_NATIVE_METHOD("setDevicesRoleForStrategy", "(II[I[Ljava/lang/String;)I",
                                android_media_AudioSystem_setDevicesRoleForStrategy),
         MAKE_JNI_NATIVE_METHOD("removeDevicesRoleForStrategy", "(II[I[Ljava/lang/String;)I",
                                android_media_AudioSystem_removeDevicesRoleForStrategy),
         MAKE_AUDIO_SYSTEM_METHOD(clearDevicesRoleForStrategy),
         MAKE_JNI_NATIVE_METHOD("getDevicesForRoleAndStrategy", "(IILjava/util/List;)I",
                                android_media_AudioSystem_getDevicesForRoleAndStrategy),
         MAKE_JNI_NATIVE_METHOD("setDevicesRoleForCapturePreset", "(II[I[Ljava/lang/String;)I",
                                android_media_AudioSystem_setDevicesRoleForCapturePreset),
         MAKE_JNI_NATIVE_METHOD("addDevicesRoleForCapturePreset", "(II[I[Ljava/lang/String;)I",
                                android_media_AudioSystem_addDevicesRoleForCapturePreset),
         MAKE_JNI_NATIVE_METHOD("removeDevicesRoleForCapturePreset", "(II[I[Ljava/lang/String;)I",
                                android_media_AudioSystem_removeDevicesRoleForCapturePreset),
         MAKE_AUDIO_SYSTEM_METHOD(clearDevicesRoleForCapturePreset),
         MAKE_JNI_NATIVE_METHOD("getDevicesForRoleAndCapturePreset", "(IILjava/util/List;)I",
                                android_media_AudioSystem_getDevicesForRoleAndCapturePreset),
         MAKE_JNI_NATIVE_METHOD("getDevicesForAttributes",
                                "(Landroid/media/AudioAttributes;[Landroid/media/"
                                "AudioDeviceAttributes;Z)I",
                                android_media_AudioSystem_getDevicesForAttributes),
         MAKE_JNI_NATIVE_METHOD("setUserIdDeviceAffinities", "(I[I[Ljava/lang/String;)I",
                                android_media_AudioSystem_setUserIdDeviceAffinities),
         MAKE_AUDIO_SYSTEM_METHOD(removeUserIdDeviceAffinities),
         MAKE_AUDIO_SYSTEM_METHOD(setCurrentImeUid),
         MAKE_JNI_NATIVE_METHOD("setVibratorInfos", "(Ljava/util/List;)I",
                                android_media_AudioSystem_setVibratorInfos),
         MAKE_JNI_NATIVE_METHOD("nativeGetSpatializer",
                                "(Landroid/media/INativeSpatializerCallback;)Landroid/os/IBinder;",
                                android_media_AudioSystem_getSpatializer),
         MAKE_JNI_NATIVE_METHOD("canBeSpatialized",
                                "(Landroid/media/AudioAttributes;Landroid/media/AudioFormat;"
                                "[Landroid/media/AudioDeviceAttributes;)Z",
                                android_media_AudioSystem_canBeSpatialized),
         MAKE_JNI_NATIVE_METHOD("nativeGetSoundDose",
                                "(Landroid/media/ISoundDoseCallback;)Landroid/os/IBinder;",
                                android_media_AudioSystem_nativeGetSoundDose),
         MAKE_JNI_NATIVE_METHOD("getDirectPlaybackSupport",
                                "(Landroid/media/AudioFormat;Landroid/media/AudioAttributes;)I",
                                android_media_AudioSystem_getDirectPlaybackSupport),
         MAKE_JNI_NATIVE_METHOD("getDirectProfilesForAttributes",
                                "(Landroid/media/AudioAttributes;Ljava/util/ArrayList;)I",
                                android_media_AudioSystem_getDirectProfilesForAttributes),
         MAKE_JNI_NATIVE_METHOD("getSupportedMixerAttributes", "(ILjava/util/List;)I",
                                android_media_AudioSystem_getSupportedMixerAttributes),
         MAKE_JNI_NATIVE_METHOD("setPreferredMixerAttributes",
                                "(Landroid/media/AudioAttributes;IILandroid/media/"
                                "AudioMixerAttributes;)I",
                                android_media_AudioSystem_setPreferredMixerAttributes),
         MAKE_JNI_NATIVE_METHOD("getPreferredMixerAttributes",
                                "(Landroid/media/AudioAttributes;ILjava/util/List;)I",
                                android_media_AudioSystem_getPreferredMixerAttributes),
         MAKE_JNI_NATIVE_METHOD("clearPreferredMixerAttributes",
                                "(Landroid/media/AudioAttributes;II)I",
                                android_media_AudioSystem_clearPreferredMixerAttributes),
         MAKE_AUDIO_SYSTEM_METHOD(supportsBluetoothVariableLatency),
         MAKE_AUDIO_SYSTEM_METHOD(setBluetoothVariableLatencyEnabled),
         MAKE_AUDIO_SYSTEM_METHOD(isBluetoothVariableLatencyEnabled),
         MAKE_JNI_NATIVE_METHOD("listenForSystemPropertyChange",
                                "(Ljava/lang/String;Ljava/lang/Runnable;)J",
                                android_media_AudioSystem_listenForSystemPropertyChange),
         MAKE_JNI_NATIVE_METHOD("triggerSystemPropertyUpdate",
                                "(J)V",
                                android_media_AudioSystem_triggerSystemPropertyUpdate),

        };

static const JNINativeMethod gEventHandlerMethods[] =
        {MAKE_JNI_NATIVE_METHOD("native_setup", "(Ljava/lang/Object;)V",
                                android_media_AudioSystem_eventHandlerSetup),
         MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_finalize",
                                        android_media_AudioSystem_eventHandlerFinalize)};

static const JNINativeMethod gFrameworkCapabilities[] =
        {MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_getMaxChannelCount",
                                        android_media_AudioSystem_getMaxChannelCount),
         MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_getMaxSampleRate",
                                        android_media_AudioSystem_getMaxSampleRate),
         MAKE_JNI_NATIVE_METHOD_AUTOSIG("native_getMinSampleRate",
                                        android_media_AudioSystem_getMinSampleRate)};

int register_android_media_AudioSystem(JNIEnv *env)
{
    // This needs to be done before hooking up methods AudioTrackRoutingProxy (below)
    // as the calls are performed in the static initializer of AudioSystem.
    RegisterMethodsOrDie(env, kClassPathName, gFrameworkCapabilities,
                         NELEM(gFrameworkCapabilities));

    jclass arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gArrayListClass = MakeGlobalRefOrDie(env, arrayListClass);
    gArrayListMethods.cstor = GetMethodIDOrDie(env, arrayListClass, "<init>", "()V");
    gArrayListMethods.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");
    gArrayListMethods.toArray = GetMethodIDOrDie(env, arrayListClass, "toArray", "()[Ljava/lang/Object;");

    jclass intArrayClass = FindClassOrDie(env, "android/util/IntArray");
    gIntArrayClass = MakeGlobalRefOrDie(env, intArrayClass);
    gIntArrayMethods.add = GetMethodIDOrDie(env, gIntArrayClass, "add", "(I)V");

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
    gAudioDevicePortCstor =
            GetMethodIDOrDie(env, audioDevicePortClass, "<init>",
                             "(Landroid/media/AudioHandle;Ljava/lang/String;Ljava/util/List;"
                             "[Landroid/media/AudioGain;ILjava/lang/String;[I[I"
                             "Ljava/util/List;)V");

    // When access AudioPort as AudioDevicePort
    gAudioPortFields.mType = GetFieldIDOrDie(env, audioDevicePortClass, "mType", "I");
    gAudioPortFields.mAddress = GetFieldIDOrDie(env, audioDevicePortClass, "mAddress",
            "Ljava/lang/String;");

    jclass audioMixPortClass = FindClassOrDie(env, "android/media/AudioMixPort");
    gAudioMixPortClass = MakeGlobalRefOrDie(env, audioMixPortClass);
    gAudioMixPortCstor =
            GetMethodIDOrDie(env, audioMixPortClass, "<init>",
                             "(Landroid/media/AudioHandle;IILjava/lang/String;Ljava/util/List;"
                             "[Landroid/media/AudioGain;)V");

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
                    "recordingCallbackFromNative", "(IIIIIIZ[I[Landroid/media/audiofx/AudioEffect$Descriptor;[Landroid/media/audiofx/AudioEffect$Descriptor;I)V");
    gAudioPolicyEventHandlerMethods.postRoutingUpdatedFromNative =
            GetStaticMethodIDOrDie(env, env->FindClass(kClassPathName),
                    "routingCallbackFromNative", "()V");
    gAudioPolicyEventHandlerMethods.postVolRangeInitReqFromNative =
            GetStaticMethodIDOrDie(env, env->FindClass(kClassPathName),
                    "volRangeInitReqCallbackFromNative", "()V");

    jclass audioMixClass = FindClassOrDie(env, "android/media/audiopolicy/AudioMix");
    gAudioMixClass = MakeGlobalRefOrDie(env, audioMixClass);
    if (audio_flags::audio_mix_test_api()) {
        gAudioMixCstor =
                GetMethodIDOrDie(env, audioMixClass, "<init>",
                                 "(Landroid/media/audiopolicy/AudioMixingRule;Landroid/"
                                 "media/AudioFormat;IIILjava/lang/String;Landroid/os/IBinder;I)V");
    }
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
    gAudioMixFields.mToken = GetFieldIDOrDie(env, audioMixClass, "mToken", "Landroid/os/IBinder;");
    gAudioMixFields.mVirtualDeviceId = GetFieldIDOrDie(env, audioMixClass, "mVirtualDeviceId", "I");

    jclass audioFormatClass = FindClassOrDie(env, "android/media/AudioFormat");
    gAudioFormatClass = MakeGlobalRefOrDie(env, audioFormatClass);
    gAudioFormatCstor = GetMethodIDOrDie(env, audioFormatClass, "<init>", "(IIIII)V");
    gAudioFormatFields.mEncoding = GetFieldIDOrDie(env, audioFormatClass, "mEncoding", "I");
    gAudioFormatFields.mSampleRate = GetFieldIDOrDie(env, audioFormatClass, "mSampleRate", "I");
    gAudioFormatFields.mChannelMask = GetFieldIDOrDie(env, audioFormatClass, "mChannelMask", "I");
    gAudioFormatFields.mChannelIndexMask =
            GetFieldIDOrDie(env, audioFormatClass, "mChannelIndexMask", "I");

    jclass audioMixingRuleClass = FindClassOrDie(env, "android/media/audiopolicy/AudioMixingRule");
    gAudioMixingRuleClass = MakeGlobalRefOrDie(env, audioMixingRuleClass);
    if (audio_flags::audio_mix_test_api()) {
        gAudioMixingRuleCstor = GetMethodIDOrDie(env, audioMixingRuleClass, "<init>",
                                                 "(ILjava/util/Collection;ZZ)V");
    }
    gAudioMixingRuleFields.mCriteria = GetFieldIDOrDie(env, audioMixingRuleClass, "mCriteria",
                                                       "Ljava/util/ArrayList;");
    gAudioMixingRuleFields.mAllowPrivilegedPlaybackCapture =
            GetFieldIDOrDie(env, audioMixingRuleClass, "mAllowPrivilegedPlaybackCapture", "Z");

    gAudioMixingRuleFields.mVoiceCommunicationCaptureAllowed =
            GetFieldIDOrDie(env, audioMixingRuleClass, "mVoiceCommunicationCaptureAllowed", "Z");

    if (audio_flags::audio_mix_test_api()) {
        jclass audioAttributesClass = FindClassOrDie(env, "android/media/AudioAttributes");
        gAudioAttributesClass = MakeGlobalRefOrDie(env, audioAttributesClass);
        gAudioAttributesCstor = GetMethodIDOrDie(env, gAudioAttributesClass, "<init>", "()V");
        gAudioAttributesFields.mSource = GetFieldIDOrDie(env, gAudioAttributesClass, "mUsage", "I");
        gAudioAttributesFields.mUsage = GetFieldIDOrDie(env, gAudioAttributesClass, "mSource", "I");
    }

    jclass audioMixMatchCriterionClass =
                FindClassOrDie(env, "android/media/audiopolicy/AudioMixingRule$AudioMixMatchCriterion");
    gAudioMixMatchCriterionClass = MakeGlobalRefOrDie(env,audioMixMatchCriterionClass);
    if (audio_flags::audio_mix_test_api()) {
        gAudioMixMatchCriterionAttrCstor =
                GetMethodIDOrDie(env, gAudioMixMatchCriterionClass, "<init>",
                                 "(Landroid/media/AudioAttributes;I)V");
        gAudioMixMatchCriterionIntPropCstor = GetMethodIDOrDie(env, gAudioMixMatchCriterionClass,
                                                               "<init>", "(Ljava/lang/Integer;I)V");
    }
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
    gClsAudioTrackRoutingProxy = static_cast<jclass>(env->NewGlobalRef(gClsAudioTrackRoutingProxy));

    gMidAudioTrackRoutingProxy_ctor =
            android::GetMethodIDOrDie(env, gClsAudioTrackRoutingProxy, "<init>", "(J)V");
    gMidAudioTrackRoutingProxy_release =
            android::GetMethodIDOrDie(env, gClsAudioTrackRoutingProxy, "native_release", "()V");

    // AudioRecordRoutingProxy
    gClsAudioRecordRoutingProxy =
            android::FindClassOrDie(env, "android/media/AudioRecordRoutingProxy");
    // make sure this reference doesn't get deleted
    gClsAudioRecordRoutingProxy =
            static_cast<jclass>(env->NewGlobalRef(gClsAudioRecordRoutingProxy));

    gMidAudioRecordRoutingProxy_ctor =
            android::GetMethodIDOrDie(env, gClsAudioRecordRoutingProxy, "<init>", "(J)V");
    gMidAudioRecordRoutingProxy_release =
            android::GetMethodIDOrDie(env, gClsAudioRecordRoutingProxy, "native_release", "()V");

    jclass listClass = FindClassOrDie(env, "java/util/List");
    gListClass = MakeGlobalRefOrDie(env, listClass);
    gListMethods.add = GetMethodIDOrDie(env, listClass, "add", "(Ljava/lang/Object;)Z");
    gListMethods.get = GetMethodIDOrDie(env, listClass, "get", "(I)Ljava/lang/Object;");
    gListMethods.size = GetMethodIDOrDie(env, listClass, "size", "()I");

    jclass audioProfileClass = FindClassOrDie(env, "android/media/AudioProfile");
    gAudioProfileClass = MakeGlobalRefOrDie(env, audioProfileClass);
    gAudioProfileCstor = GetMethodIDOrDie(env, audioProfileClass, "<init>", "(I[I[I[II)V");
    gAudioProfileFields.mSamplingRates =
            GetFieldIDOrDie(env, audioProfileClass, "mSamplingRates", "[I");
    gAudioProfileFields.mChannelMasks =
            GetFieldIDOrDie(env, audioProfileClass, "mChannelMasks", "[I");
    gAudioProfileFields.mChannelIndexMasks =
            GetFieldIDOrDie(env, audioProfileClass, "mChannelIndexMasks", "[I");
    gAudioProfileFields.mEncapsulationType =
            GetFieldIDOrDie(env, audioProfileClass, "mEncapsulationType", "I");

    jclass audioDescriptorClass = FindClassOrDie(env, "android/media/AudioDescriptor");
    gAudioDescriptorClass = MakeGlobalRefOrDie(env, audioDescriptorClass);
    gAudioDescriptorCstor = GetMethodIDOrDie(env, audioDescriptorClass, "<init>", "(II[B)V");

    jclass vibratorClass = FindClassOrDie(env, "android/os/Vibrator");
    gVibratorClass = MakeGlobalRefOrDie(env, vibratorClass);
    gVibratorMethods.getId = GetMethodIDOrDie(env, vibratorClass, "getId", "()I");
    gVibratorMethods.getResonantFrequency =
            GetMethodIDOrDie(env, vibratorClass, "getResonantFrequency", "()F");
    gVibratorMethods.getQFactor = GetMethodIDOrDie(env, vibratorClass, "getQFactor", "()F");
    gVibratorMethods.getMaxAmplitude =
            GetMethodIDOrDie(env, vibratorClass, "getHapticChannelMaximumAmplitude", "()F");

    jclass audioMixerAttributesClass = FindClassOrDie(env, "android/media/AudioMixerAttributes");
    gAudioMixerAttributesClass = MakeGlobalRefOrDie(env, audioMixerAttributesClass);
    gAudioMixerAttributesCstor = GetMethodIDOrDie(env, audioMixerAttributesClass, "<init>",
                                                  "(Landroid/media/AudioFormat;I)V");
    gAudioMixerAttributesField.mFormat = GetFieldIDOrDie(env, audioMixerAttributesClass, "mFormat",
                                                         "Landroid/media/AudioFormat;");
    gAudioMixerAttributesField.mMixerBehavior =
            GetFieldIDOrDie(env, audioMixerAttributesClass, "mMixerBehavior", "I");

    jclass runnableClazz = FindClassOrDie(env, "java/lang/Runnable");
    gRunnableClassInfo.clazz = MakeGlobalRefOrDie(env, runnableClazz);
    gRunnableClassInfo.run = GetMethodIDOrDie(env, runnableClazz, "run", "()V");

    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&gVm) != 0);

    AudioSystem::addErrorCallback(android_media_AudioSystem_error_callback);

    RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
    return RegisterMethodsOrDie(env, kEventHandlerClassPathName, gEventHandlerMethods,
                                NELEM(gEventHandlerMethods));
}
