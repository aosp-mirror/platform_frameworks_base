/*
**
** Copyright 2015, The Android Open Source Project
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
#define LOG_TAG "Radio-JNI"
#include <utils/Log.h>

#include "jni.h"
#include "JNIHelp.h"
#include "core_jni_helpers.h"
#include <system/radio.h>
#include <system/RadioMetadataWrapper.h>
#include <radio/RadioCallback.h>
#include <radio/Radio.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>
#include <binder/IMemory.h>
#include <binder/MemoryDealer.h>

using namespace android;

static jclass gArrayListClass;
static struct {
    jmethodID    add;
} gArrayListMethods;

static const char* const kRadioManagerClassPathName = "android/hardware/radio/RadioManager";
static jclass gRadioManagerClass;

static const char* const kRadioModuleClassPathName = "android/hardware/radio/RadioModule";
static jclass gRadioModuleClass;
static struct {
    jfieldID    mNativeContext;
    jfieldID    mId;
} gModuleFields;
static jmethodID gPostEventFromNative;

static const char* const kModulePropertiesClassPathName =
                                     "android/hardware/radio/RadioManager$ModuleProperties";
static jclass gModulePropertiesClass;
static jmethodID gModulePropertiesCstor;


static const char* const kRadioBandDescriptorClassPathName =
                             "android/hardware/radio/RadioManager$BandDescriptor";
static jclass gRadioBandDescriptorClass;
static struct {
    jfieldID mRegion;
    jfieldID mType;
    jfieldID mLowerLimit;
    jfieldID mUpperLimit;
    jfieldID mSpacing;
} gRadioBandDescriptorFields;

static const char* const kRadioFmBandDescriptorClassPathName =
                             "android/hardware/radio/RadioManager$FmBandDescriptor";
static jclass gRadioFmBandDescriptorClass;
static jmethodID gRadioFmBandDescriptorCstor;

static const char* const kRadioAmBandDescriptorClassPathName =
                             "android/hardware/radio/RadioManager$AmBandDescriptor";
static jclass gRadioAmBandDescriptorClass;
static jmethodID gRadioAmBandDescriptorCstor;

static const char* const kRadioBandConfigClassPathName =
                             "android/hardware/radio/RadioManager$BandConfig";
static jclass gRadioBandConfigClass;
static struct {
    jfieldID mDescriptor;
} gRadioBandConfigFields;


static const char* const kRadioFmBandConfigClassPathName =
                             "android/hardware/radio/RadioManager$FmBandConfig";
static jclass gRadioFmBandConfigClass;
static jmethodID gRadioFmBandConfigCstor;
static struct {
    jfieldID mStereo;
    jfieldID mRds;
    jfieldID mTa;
    jfieldID mAf;
    jfieldID mEa;
} gRadioFmBandConfigFields;

static const char* const kRadioAmBandConfigClassPathName =
                             "android/hardware/radio/RadioManager$AmBandConfig";
static jclass gRadioAmBandConfigClass;
static jmethodID gRadioAmBandConfigCstor;
static struct {
    jfieldID mStereo;
} gRadioAmBandConfigFields;


static const char* const kRadioProgramInfoClassPathName =
                             "android/hardware/radio/RadioManager$ProgramInfo";
static jclass gRadioProgramInfoClass;
static jmethodID gRadioProgramInfoCstor;

static const char* const kRadioMetadataClassPathName =
                             "android/hardware/radio/RadioMetadata";
static jclass gRadioMetadataClass;
static jmethodID gRadioMetadataCstor;
static struct {
    jmethodID putIntFromNative;
    jmethodID putStringFromNative;
    jmethodID putBitmapFromNative;
    jmethodID putClockFromNative;
} gRadioMetadataMethods;

static Mutex gLock;

enum {
    RADIO_STATUS_OK = 0,
    RADIO_STATUS_ERROR = INT_MIN,
    RADIO_PERMISSION_DENIED = -1,
    RADIO_STATUS_NO_INIT = -19,
    RADIO_STATUS_BAD_VALUE = -22,
    RADIO_STATUS_DEAD_OBJECT = -32,
    RADIO_STATUS_INVALID_OPERATION = -38,
    RADIO_STATUS_TIMED_OUT = -110,
};


// ----------------------------------------------------------------------------

static sp<Radio> getRadio(JNIEnv* env, jobject thiz)
{
    Mutex::Autolock l(gLock);
    Radio* const radio = (Radio*)env->GetLongField(thiz, gModuleFields.mNativeContext);
    return sp<Radio>(radio);
}

static sp<Radio> setRadio(JNIEnv* env, jobject thiz, const sp<Radio>& module)
{
    Mutex::Autolock l(gLock);
    sp<Radio> old = (Radio*)env->GetLongField(thiz, gModuleFields.mNativeContext);
    if (module.get()) {
        module->incStrong((void*)setRadio);
    }
    if (old != 0) {
        old->decStrong((void*)setRadio);
    }
    env->SetLongField(thiz, gModuleFields.mNativeContext, (jlong)module.get());
    return old;
}

static jint convertBandDescriptorFromNative(JNIEnv *env,
                                           jobject *jBandDescriptor,
                                           const radio_band_config_t *nBandconfig)
{
    ALOGV("%s type %d region %d", __FUNCTION__, nBandconfig->band.type, nBandconfig->region);

    if (nBandconfig->band.type == RADIO_BAND_FM ||
            nBandconfig->band.type == RADIO_BAND_FM_HD) {
        *jBandDescriptor = env->NewObject(gRadioFmBandDescriptorClass, gRadioFmBandDescriptorCstor,
                                      nBandconfig->region, nBandconfig->band.type,
                                      nBandconfig->band.lower_limit, nBandconfig->band.upper_limit,
                                      nBandconfig->band.spacings[0],
                                      nBandconfig->band.fm.stereo,
                                      nBandconfig->band.fm.rds != RADIO_RDS_NONE,
                                      nBandconfig->band.fm.ta,
                                      nBandconfig->band.fm.af,
                                      nBandconfig->band.fm.ea);
    } else if (nBandconfig->band.type == RADIO_BAND_AM) {
        *jBandDescriptor = env->NewObject(gRadioAmBandDescriptorClass, gRadioAmBandDescriptorCstor,
                                      nBandconfig->region, nBandconfig->band.type,
                                      nBandconfig->band.lower_limit, nBandconfig->band.upper_limit,
                                      nBandconfig->band.spacings[0],
                                      nBandconfig->band.am.stereo);
    } else {
        ALOGE("%s unknown band type %d", __FUNCTION__, nBandconfig->band.type);
        return (jint)RADIO_STATUS_BAD_VALUE;
    }

    if (*jBandDescriptor == NULL) {
        return (jint)RADIO_STATUS_NO_INIT;
    }

    return (jint)RADIO_STATUS_OK;
}

static jint convertBandConfigFromNative(JNIEnv *env,
                                           jobject *jBandConfig,
                                           const radio_band_config_t *nBandconfig)
{
    ALOGV("%s type %d region %d", __FUNCTION__, nBandconfig->band.type, nBandconfig->region);

    if (nBandconfig->band.type == RADIO_BAND_FM ||
            nBandconfig->band.type == RADIO_BAND_FM_HD) {
        *jBandConfig = env->NewObject(gRadioFmBandConfigClass, gRadioFmBandConfigCstor,
                                      nBandconfig->region, nBandconfig->band.type,
                                      nBandconfig->band.lower_limit, nBandconfig->band.upper_limit,
                                      nBandconfig->band.spacings[0],
                                      nBandconfig->band.fm.stereo,
                                      nBandconfig->band.fm.rds != RADIO_RDS_NONE,
                                      nBandconfig->band.fm.ta,
                                      nBandconfig->band.fm.af,
                                      nBandconfig->band.fm.ea);
    } else if (nBandconfig->band.type == RADIO_BAND_AM) {
        *jBandConfig = env->NewObject(gRadioAmBandConfigClass, gRadioAmBandConfigCstor,
                                      nBandconfig->region, nBandconfig->band.type,
                                      nBandconfig->band.lower_limit, nBandconfig->band.upper_limit,
                                      nBandconfig->band.spacings[0],
                                      nBandconfig->band.am.stereo);
    } else {
        ALOGE("%s unknown band type %d", __FUNCTION__, nBandconfig->band.type);
        return (jint)RADIO_STATUS_BAD_VALUE;
    }

    if (*jBandConfig == NULL) {
        return (jint)RADIO_STATUS_NO_INIT;
    }

    return (jint)RADIO_STATUS_OK;
}

static jint convertMetadataFromNative(JNIEnv *env,
                                           jobject *jMetadata,
                                           const radio_metadata_t *nMetadata)
{
    ALOGV("%s", __FUNCTION__);
    int count = radio_metadata_get_count(nMetadata);
    if (count <= 0) {
        return (jint)count;
    }
    *jMetadata = env->NewObject(gRadioMetadataClass, gRadioMetadataCstor);

    jint jCount = 0;
    jint jStatus = 0;
    for (unsigned int i = 0; i < (unsigned int)count; i++) {
        radio_metadata_key_t key;
        radio_metadata_type_t type;
        void *value;
        unsigned int size;
        if (radio_metadata_get_at_index(nMetadata, i , &key, &type, &value, &size) != 0) {
            continue;
        }
        switch (type) {
            case RADIO_METADATA_TYPE_INT: {
                ALOGV("%s RADIO_METADATA_TYPE_INT %d", __FUNCTION__, key);
                jStatus = env->CallIntMethod(*jMetadata,
                                   gRadioMetadataMethods.putIntFromNative,
                                   key, *(jint *)value);
                if (jStatus == 0) {
                    jCount++;
                }
            } break;
            case RADIO_METADATA_TYPE_TEXT: {
                ALOGV("%s RADIO_METADATA_TYPE_TEXT %d", __FUNCTION__, key);
                jstring jText = env->NewStringUTF((char *)value);
                jStatus = env->CallIntMethod(*jMetadata,
                                   gRadioMetadataMethods.putStringFromNative,
                                   key, jText);
                if (jStatus == 0) {
                    jCount++;
                }
                env->DeleteLocalRef(jText);
            } break;
            case RADIO_METADATA_TYPE_RAW: {
                ALOGV("%s RADIO_METADATA_TYPE_RAW %d size %u", __FUNCTION__, key, size);
                if (size == 0) {
                    break;
                }
                jbyteArray jData = env->NewByteArray(size);
                if (jData == NULL) {
                    break;
                }
                env->SetByteArrayRegion(jData, 0, size, (jbyte *)value);
                jStatus = env->CallIntMethod(*jMetadata,
                                   gRadioMetadataMethods.putBitmapFromNative,
                                   key, jData);
                if (jStatus == 0) {
                    jCount++;
                }
                env->DeleteLocalRef(jData);
            } break;
            case RADIO_METADATA_TYPE_CLOCK: {
                  ALOGV("%s RADIO_METADATA_TYPE_CLOCK %d", __FUNCTION__, key);
                  radio_metadata_clock_t *clock = (radio_metadata_clock_t *) value;
                  jStatus =
                      env->CallIntMethod(*jMetadata,
                                         gRadioMetadataMethods.putClockFromNative,
                                         key, (jint) clock->utc_seconds_since_epoch,
                                         (jint) clock->timezone_offset_in_minutes);
                  if (jStatus == 0) {
                      jCount++;
                  }
            } break;
        }
    }
    return jCount;
}

static jint convertProgramInfoFromNative(JNIEnv *env,
                                           jobject *jProgramInfo,
                                           const radio_program_info_t *nProgramInfo)
{
    ALOGV("%s", __FUNCTION__);
    int jStatus;
    jobject jMetadata = NULL;
    if (nProgramInfo->metadata != NULL) {
        ALOGV("%s metadata %p", __FUNCTION__, nProgramInfo->metadata);
        jStatus = convertMetadataFromNative(env, &jMetadata, nProgramInfo->metadata);
        if (jStatus < 0) {
            return jStatus;
        }
    }

    ALOGV("%s channel %d tuned %d", __FUNCTION__, nProgramInfo->channel, nProgramInfo->tuned);

    *jProgramInfo = env->NewObject(gRadioProgramInfoClass, gRadioProgramInfoCstor,
                                  nProgramInfo->channel, nProgramInfo->sub_channel,
                                  nProgramInfo->tuned, nProgramInfo->stereo,
                                  nProgramInfo->digital, nProgramInfo->signal_strength,
                                  jMetadata);

    env->DeleteLocalRef(jMetadata);
    return (jint)RADIO_STATUS_OK;
}


static jint convertBandConfigToNative(JNIEnv *env,
                                      radio_band_config_t *nBandconfig,
                                      jobject jBandConfig)
{
    ALOGV("%s", __FUNCTION__);

    jobject jDescriptor = env->GetObjectField(jBandConfig, gRadioBandConfigFields.mDescriptor);

    if (jDescriptor == NULL) {
        return (jint)RADIO_STATUS_NO_INIT;
    }

    nBandconfig->region =
            (radio_region_t)env->GetIntField(jDescriptor, gRadioBandDescriptorFields.mRegion);
    nBandconfig->band.type =
            (radio_band_t)env->GetIntField(jDescriptor, gRadioBandDescriptorFields.mType);
    nBandconfig->band.lower_limit =
            env->GetIntField(jDescriptor, gRadioBandDescriptorFields.mLowerLimit);
    nBandconfig->band.upper_limit =
            env->GetIntField(jDescriptor, gRadioBandDescriptorFields.mUpperLimit);
    nBandconfig->band.num_spacings = 1;
    nBandconfig->band.spacings[0] =
            env->GetIntField(jDescriptor, gRadioBandDescriptorFields.mSpacing);

    if (env->IsInstanceOf(jBandConfig, gRadioFmBandConfigClass)) {
        nBandconfig->band.fm.deemphasis = radio_demephasis_for_region(nBandconfig->region);
        nBandconfig->band.fm.stereo =
                env->GetBooleanField(jBandConfig, gRadioFmBandConfigFields.mStereo);
        nBandconfig->band.fm.rds =
                radio_rds_for_region(env->GetBooleanField(jBandConfig,
                                                          gRadioFmBandConfigFields.mRds),
                                     nBandconfig->region);
        nBandconfig->band.fm.ta = env->GetBooleanField(jBandConfig, gRadioFmBandConfigFields.mTa);
        nBandconfig->band.fm.af = env->GetBooleanField(jBandConfig, gRadioFmBandConfigFields.mAf);
        nBandconfig->band.fm.ea = env->GetBooleanField(jBandConfig, gRadioFmBandConfigFields.mEa);
    } else if (env->IsInstanceOf(jBandConfig, gRadioAmBandConfigClass)) {
        nBandconfig->band.am.stereo =
                env->GetBooleanField(jBandConfig, gRadioAmBandConfigFields.mStereo);
    } else {
        return (jint)RADIO_STATUS_BAD_VALUE;
    }

    return (jint)RADIO_STATUS_OK;
}

static jint
android_hardware_Radio_listModules(JNIEnv *env, jobject clazz,
                                          jobject jModules)
{
    ALOGV("%s", __FUNCTION__);

    if (jModules == NULL) {
        ALOGE("listModules NULL ArrayList");
        return RADIO_STATUS_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jModules, gArrayListClass)) {
        ALOGE("listModules not an arraylist");
        return RADIO_STATUS_BAD_VALUE;
    }

    unsigned int numModules = 0;
    radio_properties_t *nModules = NULL;

    status_t status = Radio::listModules(nModules, &numModules);
    if (status != NO_ERROR || numModules == 0) {
        return (jint)status;
    }

    nModules = (radio_properties_t *)calloc(numModules, sizeof(radio_properties_t));

    status = Radio::listModules(nModules, &numModules);
    ALOGV("%s Radio::listModules status %d numModules %d", __FUNCTION__, status, numModules);

    if (status != NO_ERROR) {
        numModules = 0;
    }

    for (size_t i = 0; i < numModules; i++) {
        if (nModules[i].num_bands == 0) {
            continue;
        }
        ALOGV("%s module %zu id %d implementor %s product %s",
              __FUNCTION__, i, nModules[i].handle, nModules[i].implementor,
              nModules[i].product);


        jobjectArray jBands = env->NewObjectArray(nModules[i].num_bands,
                                                  gRadioBandDescriptorClass, NULL);

        for (size_t j = 0; j < nModules[i].num_bands; j++) {
            jobject jBandDescriptor;
            int jStatus =
                    convertBandDescriptorFromNative(env, &jBandDescriptor, &nModules[i].bands[j]);
            if (jStatus != RADIO_STATUS_OK) {
                continue;
            }
            env->SetObjectArrayElement(jBands, j, jBandDescriptor);
            env->DeleteLocalRef(jBandDescriptor);
        }

        if (env->GetArrayLength(jBands) == 0) {
            continue;
        }
        jstring jImplementor = env->NewStringUTF(nModules[i].implementor);
        jstring jProduct = env->NewStringUTF(nModules[i].product);
        jstring jVersion = env->NewStringUTF(nModules[i].version);
        jstring jSerial = env->NewStringUTF(nModules[i].serial);
        jobject jModule = env->NewObject(gModulePropertiesClass, gModulePropertiesCstor,
                                               nModules[i].handle, nModules[i].class_id,
                                               jImplementor, jProduct, jVersion, jSerial,
                                               nModules[i].num_tuners,
                                               nModules[i].num_audio_sources,
                                               nModules[i].supports_capture,
                                               jBands);

        env->DeleteLocalRef(jImplementor);
        env->DeleteLocalRef(jProduct);
        env->DeleteLocalRef(jVersion);
        env->DeleteLocalRef(jSerial);
        env->DeleteLocalRef(jBands);
        if (jModule == NULL) {
            continue;
        }
        env->CallBooleanMethod(jModules, gArrayListMethods.add, jModule);
    }

    free(nModules);
    return (jint) status;
}

// ----------------------------------------------------------------------------

class JNIRadioCallback: public RadioCallback
{
public:
    JNIRadioCallback(JNIEnv* env, jobject thiz, jobject weak_thiz);
    ~JNIRadioCallback();

    virtual void onEvent(struct radio_event *event);

private:
    jclass      mClass;     // Reference to Radio class
    jobject     mObject;    // Weak ref to Radio Java object to call on
};

JNIRadioCallback::JNIRadioCallback(JNIEnv* env, jobject thiz, jobject weak_thiz)
{

    // Hold onto the RadioModule class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find class %s", kRadioModuleClassPathName);
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the RadioModule object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject  = env->NewGlobalRef(weak_thiz);
}

JNIRadioCallback::~JNIRadioCallback()
{
    // remove global references
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNIRadioCallback::onEvent(struct radio_event *event)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }

    ALOGV("%s", __FUNCTION__);

    jobject jObj = NULL;
    jint jArg2 = 0;
    jint jStatus = RADIO_STATUS_OK;
    switch (event->type) {
        case RADIO_EVENT_CONFIG:
            jStatus = convertBandConfigFromNative(env, &jObj, &event->config);
            break;
        case RADIO_EVENT_TUNED:
        case RADIO_EVENT_AF_SWITCH:
            ALOGV("%s RADIO_EVENT_TUNED channel %d", __FUNCTION__, event->info.channel);
            jStatus = convertProgramInfoFromNative(env, &jObj, &event->info);
            break;
        case RADIO_EVENT_METADATA:
            jStatus = convertMetadataFromNative(env, &jObj, event->metadata);
            if (jStatus >= 0) {
                jStatus = RADIO_STATUS_OK;
            }
            break;
        case RADIO_EVENT_ANTENNA:
        case RADIO_EVENT_TA:
        case RADIO_EVENT_EA:
        case RADIO_EVENT_CONTROL:
            jArg2 = event->on ? 1 : 0;
            break;
    }

    if (jStatus != RADIO_STATUS_OK) {
        return;
    }
    env->CallStaticVoidMethod(mClass, gPostEventFromNative, mObject,
                              event->type, event->status, jArg2, jObj);

    env->DeleteLocalRef(jObj);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}

// ----------------------------------------------------------------------------

static void
android_hardware_Radio_setup(JNIEnv *env, jobject thiz,
                             jobject weak_this, jobject jConfig, jboolean withAudio)
{
    ALOGV("%s", __FUNCTION__);

    setRadio(env, thiz, 0);

    sp<JNIRadioCallback> callback = new JNIRadioCallback(env, thiz, weak_this);

    radio_handle_t handle = (radio_handle_t)env->GetIntField(thiz, gModuleFields.mId);

    struct radio_band_config nConfig;
    struct radio_band_config *configPtr = NULL;
    if (jConfig != NULL) {
        jint jStatus = convertBandConfigToNative(env, &nConfig, jConfig);
        if (jStatus != RADIO_STATUS_OK) {
            return;
        }
        configPtr = &nConfig;
    }
    sp<Radio> module = Radio::attach(handle, configPtr, (bool)withAudio, callback);
    if (module == 0) {
        return;
    }

    setRadio(env, thiz, module);
}

static void
android_hardware_Radio_close(JNIEnv *env, jobject thiz)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = setRadio(env, thiz, 0);
    ALOGV("detach module %p", module.get());
    if (module != 0) {
        ALOGV("detach module->detach()");
        module->detach();
    }
}

static void
android_hardware_Radio_finalize(JNIEnv *env, jobject thiz)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module != 0) {
        ALOGW("Radio finalized without being detached");
    }
    android_hardware_Radio_close(env, thiz);
}

static jint
android_hardware_Radio_setConfiguration(JNIEnv *env, jobject thiz, jobject jConfig)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return RADIO_STATUS_NO_INIT;
    }

    if (!env->IsInstanceOf(jConfig, gRadioFmBandConfigClass) &&
            !env->IsInstanceOf(jConfig, gRadioAmBandConfigClass)) {
        return RADIO_STATUS_BAD_VALUE;
    }

    struct radio_band_config nConfig;
    jint jStatus = convertBandConfigToNative(env, &nConfig, jConfig);
    if (jStatus != RADIO_STATUS_OK) {
        return jStatus;
    }

    status_t status = module->setConfiguration(&nConfig);
    return (jint)status;
}

static jint
android_hardware_Radio_getConfiguration(JNIEnv *env, jobject thiz, jobjectArray jConfigs)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return RADIO_STATUS_NO_INIT;
    }
    if (env->GetArrayLength(jConfigs) != 1) {
        return (jint)RADIO_STATUS_BAD_VALUE;
    }

    struct radio_band_config nConfig;

    status_t status = module->getConfiguration(&nConfig);
    if (status != NO_ERROR) {
        return (jint)status;
    }
    jobject jConfig;
    int jStatus = convertBandConfigFromNative(env, &jConfig, &nConfig);
    if (jStatus != RADIO_STATUS_OK) {
        return jStatus;
    }
    env->SetObjectArrayElement(jConfigs, 0, jConfig);
    env->DeleteLocalRef(jConfig);
    return RADIO_STATUS_OK;
}

static jint
android_hardware_Radio_setMute(JNIEnv *env, jobject thiz, jboolean mute)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return RADIO_STATUS_NO_INIT;
    }
    status_t status = module->setMute((bool)mute);
    return (jint)status;
}

static jboolean
android_hardware_Radio_getMute(JNIEnv *env, jobject thiz)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return true;
    }
    bool mute = true;
    status_t status = module->getMute(&mute);
    if (status != NO_ERROR) {
        return true;
    }
    return (jboolean)mute;
}

static jint
android_hardware_Radio_step(JNIEnv *env, jobject thiz, jint direction, jboolean skipSubChannel)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return RADIO_STATUS_NO_INIT;
    }
    status_t status = module->step((radio_direction_t)direction, (bool)skipSubChannel);
    return (jint)status;
}

static jint
android_hardware_Radio_scan(JNIEnv *env, jobject thiz, jint direction, jboolean skipSubChannel)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return RADIO_STATUS_NO_INIT;
    }
    status_t status = module->scan((radio_direction_t)direction, (bool)skipSubChannel);
    return (jint)status;
}

static jint
android_hardware_Radio_tune(JNIEnv *env, jobject thiz, jint channel, jint subChannel)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return RADIO_STATUS_NO_INIT;
    }
    status_t status = module->tune((unsigned int)channel, (unsigned int)subChannel);
    return (jint)status;
}

static jint
android_hardware_Radio_cancel(JNIEnv *env, jobject thiz)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return RADIO_STATUS_NO_INIT;
    }
    status_t status = module->cancel();
    return (jint)status;
}

static jint
android_hardware_Radio_getProgramInformation(JNIEnv *env, jobject thiz, jobjectArray jInfos)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return RADIO_STATUS_NO_INIT;
    }
    if (env->GetArrayLength(jInfos) != 1) {
        return (jint)RADIO_STATUS_BAD_VALUE;
    }

    struct radio_program_info nInfo;
    RadioMetadataWrapper metadataWrapper(&nInfo.metadata);
    jobject jInfo = NULL;
    int jStatus;

    jStatus = (int)module->getProgramInformation(&nInfo);
    if (jStatus != RADIO_STATUS_OK) {
        goto exit;
    }
    jStatus = convertProgramInfoFromNative(env, &jInfo, &nInfo);
    if (jStatus != RADIO_STATUS_OK) {
        goto exit;
    }
    env->SetObjectArrayElement(jInfos, 0, jInfo);

exit:
    if (jInfo != NULL) {
        env->DeleteLocalRef(jInfo);
    }
    return jStatus;
}

static jboolean
android_hardware_Radio_isAntennaConnected(JNIEnv *env, jobject thiz)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return false;
    }

    struct radio_band_config nConfig;

    status_t status = module->getConfiguration(&nConfig);
    if (status != NO_ERROR) {
        return false;
    }

    return (jboolean)nConfig.band.antenna_connected;
}


static jboolean
android_hardware_Radio_hasControl(JNIEnv *env, jobject thiz)
{
    ALOGV("%s", __FUNCTION__);
    sp<Radio> module = getRadio(env, thiz);
    if (module == NULL) {
        return false;
    }

    bool hasControl;
    status_t status = module->hasControl(&hasControl);
    if (status != NO_ERROR) {
        return false;
    }

    return (jboolean)hasControl;
}


static JNINativeMethod gMethods[] = {
    {"listModules",
        "(Ljava/util/List;)I",
        (void *)android_hardware_Radio_listModules},
};

static JNINativeMethod gModuleMethods[] = {
    {"native_setup",
        "(Ljava/lang/Object;Landroid/hardware/radio/RadioManager$BandConfig;Z)V",
        (void *)android_hardware_Radio_setup},
    {"native_finalize",
        "()V",
        (void *)android_hardware_Radio_finalize},
    {"close",
        "()V",
        (void *)android_hardware_Radio_close},
    {"setConfiguration",
        "(Landroid/hardware/radio/RadioManager$BandConfig;)I",
        (void *)android_hardware_Radio_setConfiguration},
    {"getConfiguration",
        "([Landroid/hardware/radio/RadioManager$BandConfig;)I",
        (void *)android_hardware_Radio_getConfiguration},
    {"setMute",
        "(Z)I",
        (void *)android_hardware_Radio_setMute},
    {"getMute",
        "()Z",
        (void *)android_hardware_Radio_getMute},
    {"step",
        "(IZ)I",
        (void *)android_hardware_Radio_step},
    {"scan",
        "(IZ)I",
        (void *)android_hardware_Radio_scan},
    {"tune",
        "(II)I",
        (void *)android_hardware_Radio_tune},
    {"cancel",
        "()I",
        (void *)android_hardware_Radio_cancel},
    {"getProgramInformation",
        "([Landroid/hardware/radio/RadioManager$ProgramInfo;)I",
        (void *)android_hardware_Radio_getProgramInformation},
    {"isAntennaConnected",
        "()Z",
        (void *)android_hardware_Radio_isAntennaConnected},
    {"hasControl",
        "()Z",
        (void *)android_hardware_Radio_hasControl},
};

int register_android_hardware_Radio(JNIEnv *env)
{
    jclass arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gArrayListClass = MakeGlobalRefOrDie(env, arrayListClass);
    gArrayListMethods.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jclass lClass = FindClassOrDie(env, kRadioManagerClassPathName);
    gRadioManagerClass = MakeGlobalRefOrDie(env, lClass);

    jclass moduleClass = FindClassOrDie(env, kRadioModuleClassPathName);
    gRadioModuleClass = MakeGlobalRefOrDie(env, moduleClass);
    gPostEventFromNative = GetStaticMethodIDOrDie(env, moduleClass, "postEventFromNative",
                                                  "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    gModuleFields.mNativeContext = GetFieldIDOrDie(env, moduleClass, "mNativeContext", "J");
    gModuleFields.mId = GetFieldIDOrDie(env, moduleClass, "mId", "I");

    jclass modulePropertiesClass = FindClassOrDie(env, kModulePropertiesClassPathName);
    gModulePropertiesClass = MakeGlobalRefOrDie(env, modulePropertiesClass);
    gModulePropertiesCstor = GetMethodIDOrDie(env, modulePropertiesClass, "<init>",
            "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIZ[Landroid/hardware/radio/RadioManager$BandDescriptor;)V");

    jclass bandDescriptorClass = FindClassOrDie(env, kRadioBandDescriptorClassPathName);
    gRadioBandDescriptorClass = MakeGlobalRefOrDie(env, bandDescriptorClass);
    gRadioBandDescriptorFields.mRegion = GetFieldIDOrDie(env, bandDescriptorClass, "mRegion", "I");
    gRadioBandDescriptorFields.mType = GetFieldIDOrDie(env, bandDescriptorClass, "mType", "I");
    gRadioBandDescriptorFields.mLowerLimit =
            GetFieldIDOrDie(env, bandDescriptorClass, "mLowerLimit", "I");
    gRadioBandDescriptorFields.mUpperLimit =
            GetFieldIDOrDie(env, bandDescriptorClass, "mUpperLimit", "I");
    gRadioBandDescriptorFields.mSpacing =
            GetFieldIDOrDie(env, bandDescriptorClass, "mSpacing", "I");

    jclass fmBandDescriptorClass = FindClassOrDie(env, kRadioFmBandDescriptorClassPathName);
    gRadioFmBandDescriptorClass = MakeGlobalRefOrDie(env, fmBandDescriptorClass);
    gRadioFmBandDescriptorCstor = GetMethodIDOrDie(env, fmBandDescriptorClass, "<init>",
            "(IIIIIZZZZZ)V");

    jclass amBandDescriptorClass = FindClassOrDie(env, kRadioAmBandDescriptorClassPathName);
    gRadioAmBandDescriptorClass = MakeGlobalRefOrDie(env, amBandDescriptorClass);
    gRadioAmBandDescriptorCstor = GetMethodIDOrDie(env, amBandDescriptorClass, "<init>",
            "(IIIIIZ)V");

    jclass bandConfigClass = FindClassOrDie(env, kRadioBandConfigClassPathName);
    gRadioBandConfigClass = MakeGlobalRefOrDie(env, bandConfigClass);
    gRadioBandConfigFields.mDescriptor =
            GetFieldIDOrDie(env, bandConfigClass, "mDescriptor",
                            "Landroid/hardware/radio/RadioManager$BandDescriptor;");

    jclass fmBandConfigClass = FindClassOrDie(env, kRadioFmBandConfigClassPathName);
    gRadioFmBandConfigClass = MakeGlobalRefOrDie(env, fmBandConfigClass);
    gRadioFmBandConfigCstor = GetMethodIDOrDie(env, fmBandConfigClass, "<init>",
            "(IIIIIZZZZZ)V");
    gRadioFmBandConfigFields.mStereo = GetFieldIDOrDie(env, fmBandConfigClass, "mStereo", "Z");
    gRadioFmBandConfigFields.mRds = GetFieldIDOrDie(env, fmBandConfigClass, "mRds", "Z");
    gRadioFmBandConfigFields.mTa = GetFieldIDOrDie(env, fmBandConfigClass, "mTa", "Z");
    gRadioFmBandConfigFields.mAf = GetFieldIDOrDie(env, fmBandConfigClass, "mAf", "Z");
    gRadioFmBandConfigFields.mEa =
        GetFieldIDOrDie(env, fmBandConfigClass, "mEa", "Z");


    jclass amBandConfigClass = FindClassOrDie(env, kRadioAmBandConfigClassPathName);
    gRadioAmBandConfigClass = MakeGlobalRefOrDie(env, amBandConfigClass);
    gRadioAmBandConfigCstor = GetMethodIDOrDie(env, amBandConfigClass, "<init>",
            "(IIIIIZ)V");
    gRadioAmBandConfigFields.mStereo = GetFieldIDOrDie(env, amBandConfigClass, "mStereo", "Z");

    jclass programInfoClass = FindClassOrDie(env, kRadioProgramInfoClassPathName);
    gRadioProgramInfoClass = MakeGlobalRefOrDie(env, programInfoClass);
    gRadioProgramInfoCstor = GetMethodIDOrDie(env, programInfoClass, "<init>",
            "(IIZZZILandroid/hardware/radio/RadioMetadata;)V");

    jclass metadataClass = FindClassOrDie(env, kRadioMetadataClassPathName);
    gRadioMetadataClass = MakeGlobalRefOrDie(env, metadataClass);
    gRadioMetadataCstor = GetMethodIDOrDie(env, metadataClass, "<init>", "()V");
    gRadioMetadataMethods.putIntFromNative = GetMethodIDOrDie(env, metadataClass,
                                                              "putIntFromNative",
                                                              "(II)I");
    gRadioMetadataMethods.putStringFromNative = GetMethodIDOrDie(env, metadataClass,
                                                                 "putStringFromNative",
                                                                 "(ILjava/lang/String;)I");
    gRadioMetadataMethods.putBitmapFromNative = GetMethodIDOrDie(env, metadataClass,
                                                                 "putBitmapFromNative",
                                                                 "(I[B)I");
    gRadioMetadataMethods.putClockFromNative = GetMethodIDOrDie(env, metadataClass,
                                                                "putClockFromNative",
                                                                "(IJI)I");


    RegisterMethodsOrDie(env, kRadioManagerClassPathName, gMethods, NELEM(gMethods));

    int ret = RegisterMethodsOrDie(env, kRadioModuleClassPathName, gModuleMethods, NELEM(gModuleMethods));

    ALOGI("%s DONE", __FUNCTION__);

    return ret;
}
