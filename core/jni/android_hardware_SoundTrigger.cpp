/*
**
** Copyright 2014, The Android Open Source Project
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
#define LOG_TAG "SoundTrigger-JNI"
#include <utils/Log.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include <system/sound_trigger.h>
#include <soundtrigger/SoundTriggerCallback.h>
#include <soundtrigger/SoundTrigger.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>
#include <binder/IMemory.h>
#include <binder/MemoryDealer.h>
#include "android_media_AudioFormat.h"

using namespace android;

static jclass gArrayListClass;
static struct {
    jmethodID    add;
} gArrayListMethods;

static jclass gUUIDClass;
static struct {
    jmethodID    toString;
} gUUIDMethods;

static const char* const kSoundTriggerClassPathName = "android/hardware/soundtrigger/SoundTrigger";
static jclass gSoundTriggerClass;

static const char* const kModuleClassPathName = "android/hardware/soundtrigger/SoundTriggerModule";
static jclass gModuleClass;
static struct {
    jfieldID    mNativeContext;
    jfieldID    mId;
} gModuleFields;
static jmethodID   gPostEventFromNative;

static const char* const kModulePropertiesClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$ModuleProperties";
static jclass gModulePropertiesClass;
static jmethodID   gModulePropertiesCstor;

static const char* const kSoundModelClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$SoundModel";
static jclass gSoundModelClass;
static struct {
    jfieldID    uuid;
    jfieldID    vendorUuid;
    jfieldID    data;
} gSoundModelFields;

static const char* const kGenericSoundModelClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$GenericSoundModel";
static jclass gGenericSoundModelClass;

static const char* const kKeyphraseClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$Keyphrase";
static jclass gKeyphraseClass;
static struct {
    jfieldID id;
    jfieldID recognitionModes;
    jfieldID locale;
    jfieldID text;
    jfieldID users;
} gKeyphraseFields;

static const char* const kKeyphraseSoundModelClassPathName =
                                 "android/hardware/soundtrigger/SoundTrigger$KeyphraseSoundModel";
static jclass gKeyphraseSoundModelClass;
static struct {
    jfieldID    keyphrases;
} gKeyphraseSoundModelFields;

static const char* const kRecognitionConfigClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$RecognitionConfig";
static jclass gRecognitionConfigClass;
static struct {
    jfieldID captureRequested;
    jfieldID keyphrases;
    jfieldID data;
} gRecognitionConfigFields;

static const char* const kRecognitionEventClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$RecognitionEvent";
static jclass gRecognitionEventClass;
static jmethodID   gRecognitionEventCstor;

static const char* const kKeyphraseRecognitionEventClassPathName =
                             "android/hardware/soundtrigger/SoundTrigger$KeyphraseRecognitionEvent";
static jclass gKeyphraseRecognitionEventClass;
static jmethodID   gKeyphraseRecognitionEventCstor;

static const char* const kGenericRecognitionEventClassPathName =
                             "android/hardware/soundtrigger/SoundTrigger$GenericRecognitionEvent";
static jclass gGenericRecognitionEventClass;
static jmethodID   gGenericRecognitionEventCstor;

static const char* const kKeyphraseRecognitionExtraClassPathName =
                             "android/hardware/soundtrigger/SoundTrigger$KeyphraseRecognitionExtra";
static jclass gKeyphraseRecognitionExtraClass;
static jmethodID   gKeyphraseRecognitionExtraCstor;
static struct {
    jfieldID id;
    jfieldID recognitionModes;
    jfieldID coarseConfidenceLevel;
    jfieldID confidenceLevels;
} gKeyphraseRecognitionExtraFields;

static const char* const kConfidenceLevelClassPathName =
                             "android/hardware/soundtrigger/SoundTrigger$ConfidenceLevel";
static jclass gConfidenceLevelClass;
static jmethodID   gConfidenceLevelCstor;
static struct {
    jfieldID userId;
    jfieldID confidenceLevel;
} gConfidenceLevelFields;

static const char* const kAudioFormatClassPathName =
                             "android/media/AudioFormat";
static jclass gAudioFormatClass;
static jmethodID gAudioFormatCstor;

static const char* const kSoundModelEventClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$SoundModelEvent";
static jclass gSoundModelEventClass;
static jmethodID   gSoundModelEventCstor;

static Mutex gLock;

enum {
    SOUNDTRIGGER_STATUS_OK = 0,
    SOUNDTRIGGER_STATUS_ERROR = INT_MIN,
    SOUNDTRIGGER_PERMISSION_DENIED = -1,
    SOUNDTRIGGER_STATUS_NO_INIT = -19,
    SOUNDTRIGGER_STATUS_BAD_VALUE = -22,
    SOUNDTRIGGER_STATUS_DEAD_OBJECT = -32,
    SOUNDTRIGGER_INVALID_OPERATION = -38,
};

enum  {
    SOUNDTRIGGER_EVENT_RECOGNITION = 1,
    SOUNDTRIGGER_EVENT_SERVICE_DIED = 2,
    SOUNDTRIGGER_EVENT_SOUNDMODEL = 3,
    SOUNDTRIGGER_EVENT_SERVICE_STATE_CHANGE = 4,
};

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class JNISoundTriggerCallback: public SoundTriggerCallback
{
public:
    JNISoundTriggerCallback(JNIEnv* env, jobject thiz, jobject weak_thiz);
    ~JNISoundTriggerCallback();

    virtual void onRecognitionEvent(struct sound_trigger_recognition_event *event);
    virtual void onSoundModelEvent(struct sound_trigger_model_event *event);
    virtual void onServiceStateChange(sound_trigger_service_state_t state);
    virtual void onServiceDied();

private:
    jclass      mClass;     // Reference to SoundTrigger class
    jobject     mObject;    // Weak ref to SoundTrigger Java object to call on
};

JNISoundTriggerCallback::JNISoundTriggerCallback(JNIEnv* env, jobject thiz, jobject weak_thiz)
{

    // Hold onto the SoundTriggerModule class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find class %s", kModuleClassPathName);
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the SoundTriggerModule object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject  = env->NewGlobalRef(weak_thiz);
}

JNISoundTriggerCallback::~JNISoundTriggerCallback()
{
    // remove global references
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNISoundTriggerCallback::onRecognitionEvent(struct sound_trigger_recognition_event *event)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject jEvent = NULL;
    jbyteArray jData = NULL;

    if (event->data_size) {
        jData = env->NewByteArray(event->data_size);
        jbyte *nData = env->GetByteArrayElements(jData, NULL);
        memcpy(nData, (char *)event + event->data_offset, event->data_size);
        env->ReleaseByteArrayElements(jData, nData, 0);
    }

    jobject jAudioFormat = NULL;
    if (event->trigger_in_data || event->capture_available) {
        jAudioFormat = env->NewObject(gAudioFormatClass,
                                    gAudioFormatCstor,
                                    audioFormatFromNative(event->audio_config.format),
                                    event->audio_config.sample_rate,
                                    inChannelMaskFromNative(event->audio_config.channel_mask),
                                    (jint)0 /* channelIndexMask */);

    }
    if (event->type == SOUND_MODEL_TYPE_KEYPHRASE) {
        struct sound_trigger_phrase_recognition_event *phraseEvent =
                (struct sound_trigger_phrase_recognition_event *)event;

        jobjectArray jExtras = env->NewObjectArray(phraseEvent->num_phrases,
                                                  gKeyphraseRecognitionExtraClass, NULL);
        if (jExtras == NULL) {
            return;
        }

        for (size_t i = 0; i < phraseEvent->num_phrases; i++) {
            jobjectArray jConfidenceLevels = env->NewObjectArray(
                                                        phraseEvent->phrase_extras[i].num_levels,
                                                        gConfidenceLevelClass, NULL);

            if (jConfidenceLevels == NULL) {
                return;
            }
            for (size_t j = 0; j < phraseEvent->phrase_extras[i].num_levels; j++) {
                jobject jConfidenceLevel = env->NewObject(gConfidenceLevelClass,
                                                  gConfidenceLevelCstor,
                                                  phraseEvent->phrase_extras[i].levels[j].user_id,
                                                  phraseEvent->phrase_extras[i].levels[j].level);
                env->SetObjectArrayElement(jConfidenceLevels, j, jConfidenceLevel);
                env->DeleteLocalRef(jConfidenceLevel);
            }

            jobject jNewExtra = env->NewObject(gKeyphraseRecognitionExtraClass,
                                               gKeyphraseRecognitionExtraCstor,
                                               phraseEvent->phrase_extras[i].id,
                                               phraseEvent->phrase_extras[i].recognition_modes,
                                               phraseEvent->phrase_extras[i].confidence_level,
                                               jConfidenceLevels);

            if (jNewExtra == NULL) {
                return;
            }
            env->SetObjectArrayElement(jExtras, i, jNewExtra);
            env->DeleteLocalRef(jNewExtra);
            env->DeleteLocalRef(jConfidenceLevels);
        }
        jEvent = env->NewObject(gKeyphraseRecognitionEventClass, gKeyphraseRecognitionEventCstor,
                                event->status, event->model, event->capture_available,
                                event->capture_session, event->capture_delay_ms,
                                event->capture_preamble_ms, event->trigger_in_data,
                                jAudioFormat, jData, jExtras);
        env->DeleteLocalRef(jExtras);
    } else if (event->type == SOUND_MODEL_TYPE_GENERIC) {
        jEvent = env->NewObject(gGenericRecognitionEventClass, gGenericRecognitionEventCstor,
                                event->status, event->model, event->capture_available,
                                event->capture_session, event->capture_delay_ms,
                                event->capture_preamble_ms, event->trigger_in_data,
                                jAudioFormat, jData);
    } else {
        jEvent = env->NewObject(gRecognitionEventClass, gRecognitionEventCstor,
                                event->status, event->model, event->capture_available,
                                event->capture_session, event->capture_delay_ms,
                                event->capture_preamble_ms, event->trigger_in_data,
                                jAudioFormat, jData);
    }

    if (jAudioFormat != NULL) {
        env->DeleteLocalRef(jAudioFormat);
    }
    if (jData != NULL) {
        env->DeleteLocalRef(jData);
    }

    env->CallStaticVoidMethod(mClass, gPostEventFromNative, mObject,
                              SOUNDTRIGGER_EVENT_RECOGNITION, 0, 0, jEvent);

    env->DeleteLocalRef(jEvent);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}

void JNISoundTriggerCallback::onSoundModelEvent(struct sound_trigger_model_event *event)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject jEvent = NULL;
    jbyteArray jData = NULL;

    if (event->data_size) {
        jData = env->NewByteArray(event->data_size);
        jbyte *nData = env->GetByteArrayElements(jData, NULL);
        memcpy(nData, (char *)event + event->data_offset, event->data_size);
        env->ReleaseByteArrayElements(jData, nData, 0);
    }

    jEvent = env->NewObject(gSoundModelEventClass, gSoundModelEventCstor,
                            event->status, event->model, jData);

    env->DeleteLocalRef(jData);
    env->CallStaticVoidMethod(mClass, gPostEventFromNative, mObject,
                              SOUNDTRIGGER_EVENT_SOUNDMODEL, 0, 0, jEvent);
    env->DeleteLocalRef(jEvent);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}

void JNISoundTriggerCallback::onServiceStateChange(sound_trigger_service_state_t state)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallStaticVoidMethod(mClass, gPostEventFromNative, mObject,
                                        SOUNDTRIGGER_EVENT_SERVICE_STATE_CHANGE, state, 0, NULL);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}

void JNISoundTriggerCallback::onServiceDied()
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->CallStaticVoidMethod(mClass, gPostEventFromNative, mObject,
                              SOUNDTRIGGER_EVENT_SERVICE_DIED, 0, 0, NULL);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}

// ----------------------------------------------------------------------------

static sp<SoundTrigger> getSoundTrigger(JNIEnv* env, jobject thiz)
{
    Mutex::Autolock l(gLock);
    SoundTrigger* const st = (SoundTrigger*)env->GetLongField(thiz,
                                                         gModuleFields.mNativeContext);
    return sp<SoundTrigger>(st);
}

static sp<SoundTrigger> setSoundTrigger(JNIEnv* env, jobject thiz, const sp<SoundTrigger>& module)
{
    Mutex::Autolock l(gLock);
    sp<SoundTrigger> old = (SoundTrigger*)env->GetLongField(thiz,
                                                         gModuleFields.mNativeContext);
    if (module.get()) {
        module->incStrong((void*)setSoundTrigger);
    }
    if (old != 0) {
        old->decStrong((void*)setSoundTrigger);
    }
    env->SetLongField(thiz, gModuleFields.mNativeContext, (jlong)module.get());
    return old;
}


static jint
android_hardware_SoundTrigger_listModules(JNIEnv *env, jobject clazz,
                                          jobject jModules)
{
    ALOGV("listModules");

    if (jModules == NULL) {
        ALOGE("listModules NULL AudioPatch ArrayList");
        return SOUNDTRIGGER_STATUS_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jModules, gArrayListClass)) {
        ALOGE("listModules not an arraylist");
        return SOUNDTRIGGER_STATUS_BAD_VALUE;
    }

    unsigned int numModules = 0;
    struct sound_trigger_module_descriptor *nModules = NULL;

    status_t status = SoundTrigger::listModules(nModules, &numModules);
    if (status != NO_ERROR || numModules == 0) {
        return (jint)status;
    }

    nModules = (struct sound_trigger_module_descriptor *)
                            calloc(numModules, sizeof(struct sound_trigger_module_descriptor));

    status = SoundTrigger::listModules(nModules, &numModules);
    ALOGV("listModules SoundTrigger::listModules status %d numModules %d", status, numModules);

    if (status != NO_ERROR) {
        numModules = 0;
    }

    for (size_t i = 0; i < numModules; i++) {
        char str[SOUND_TRIGGER_MAX_STRING_LEN];

        jstring implementor = env->NewStringUTF(nModules[i].properties.implementor);
        jstring description = env->NewStringUTF(nModules[i].properties.description);
        SoundTrigger::guidToString(&nModules[i].properties.uuid,
                                   str,
                                   SOUND_TRIGGER_MAX_STRING_LEN);
        jstring uuid = env->NewStringUTF(str);

        ALOGV("listModules module %zu id %d description %s maxSoundModels %d",
              i, nModules[i].handle, nModules[i].properties.description,
              nModules[i].properties.max_sound_models);

        jobject newModuleDesc = env->NewObject(gModulePropertiesClass, gModulePropertiesCstor,
                                               nModules[i].handle,
                                               implementor, description, uuid,
                                               nModules[i].properties.version,
                                               nModules[i].properties.max_sound_models,
                                               nModules[i].properties.max_key_phrases,
                                               nModules[i].properties.max_users,
                                               nModules[i].properties.recognition_modes,
                                               nModules[i].properties.capture_transition,
                                               nModules[i].properties.max_buffer_ms,
                                               nModules[i].properties.concurrent_capture,
                                               nModules[i].properties.power_consumption_mw,
                                               nModules[i].properties.trigger_in_event);

        env->DeleteLocalRef(implementor);
        env->DeleteLocalRef(description);
        env->DeleteLocalRef(uuid);
        if (newModuleDesc == NULL) {
            status = SOUNDTRIGGER_STATUS_ERROR;
            goto exit;
        }
        env->CallBooleanMethod(jModules, gArrayListMethods.add, newModuleDesc);
    }

exit:
    free(nModules);
    return (jint) status;
}

static void
android_hardware_SoundTrigger_setup(JNIEnv *env, jobject thiz, jobject weak_this)
{
    ALOGV("setup");

    sp<JNISoundTriggerCallback> callback = new JNISoundTriggerCallback(env, thiz, weak_this);

    sound_trigger_module_handle_t handle =
            (sound_trigger_module_handle_t)env->GetIntField(thiz, gModuleFields.mId);

    sp<SoundTrigger> module = SoundTrigger::attach(handle, callback);
    if (module == 0) {
        return;
    }

    setSoundTrigger(env, thiz, module);
}

static void
android_hardware_SoundTrigger_detach(JNIEnv *env, jobject thiz)
{
    ALOGV("detach");
    sp<SoundTrigger> module = setSoundTrigger(env, thiz, 0);
    ALOGV("detach module %p", module.get());
    if (module != 0) {
        ALOGV("detach module->detach()");
        module->detach();
    }
}

static void
android_hardware_SoundTrigger_finalize(JNIEnv *env, jobject thiz)
{
    ALOGV("finalize");
    sp<SoundTrigger> module = getSoundTrigger(env, thiz);
    if (module != 0) {
        ALOGW("SoundTrigger finalized without being detached");
    }
    android_hardware_SoundTrigger_detach(env, thiz);
}

static jint
android_hardware_SoundTrigger_loadSoundModel(JNIEnv *env, jobject thiz,
                                             jobject jSoundModel, jintArray jHandle)
{
    jint status = SOUNDTRIGGER_STATUS_OK;
    jbyte *nData = NULL;
    struct sound_trigger_sound_model *nSoundModel;
    jbyteArray jData;
    sp<MemoryDealer> memoryDealer;
    sp<IMemory> memory;
    size_t size;
    sound_model_handle_t handle = 0;
    jobject jUuid;
    jstring jUuidString;
    const char *nUuidString;

    ALOGV("loadSoundModel");
    sp<SoundTrigger> module = getSoundTrigger(env, thiz);
    if (module == NULL) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }
    if (jHandle == NULL) {
        return SOUNDTRIGGER_STATUS_BAD_VALUE;
    }
    jsize jHandleLen = env->GetArrayLength(jHandle);
    if (jHandleLen == 0) {
        return SOUNDTRIGGER_STATUS_BAD_VALUE;
    }
    jint *nHandle = env->GetIntArrayElements(jHandle, NULL);
    if (nHandle == NULL) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }
    if (!env->IsInstanceOf(jSoundModel, gSoundModelClass)) {
        status = SOUNDTRIGGER_STATUS_BAD_VALUE;
        goto exit;
    }
    size_t offset;
    sound_trigger_sound_model_type_t type;
    if (env->IsInstanceOf(jSoundModel, gKeyphraseSoundModelClass)) {
        offset = sizeof(struct sound_trigger_phrase_sound_model);
        type = SOUND_MODEL_TYPE_KEYPHRASE;
    } else if (env->IsInstanceOf(jSoundModel, gGenericSoundModelClass)) {
        offset = sizeof(struct sound_trigger_generic_sound_model);
        type = SOUND_MODEL_TYPE_GENERIC;
    } else {
        offset = sizeof(struct sound_trigger_sound_model);
        type = SOUND_MODEL_TYPE_UNKNOWN;
    }

    jUuid = env->GetObjectField(jSoundModel, gSoundModelFields.uuid);
    jUuidString = (jstring)env->CallObjectMethod(jUuid, gUUIDMethods.toString);
    nUuidString = env->GetStringUTFChars(jUuidString, NULL);
    sound_trigger_uuid_t nUuid;
    SoundTrigger::stringToGuid(nUuidString, &nUuid);
    env->ReleaseStringUTFChars(jUuidString, nUuidString);
    env->DeleteLocalRef(jUuidString);

    sound_trigger_uuid_t nVendorUuid;
    jUuid = env->GetObjectField(jSoundModel, gSoundModelFields.vendorUuid);
    if (jUuid != NULL) {
        jUuidString = (jstring)env->CallObjectMethod(jUuid, gUUIDMethods.toString);
        nUuidString = env->GetStringUTFChars(jUuidString, NULL);
        SoundTrigger::stringToGuid(nUuidString, &nVendorUuid);
        env->ReleaseStringUTFChars(jUuidString, nUuidString);
        env->DeleteLocalRef(jUuidString);
    } else {
        SoundTrigger::stringToGuid("00000000-0000-0000-0000-000000000000", &nVendorUuid);
    }

    jData = (jbyteArray)env->GetObjectField(jSoundModel, gSoundModelFields.data);
    if (jData == NULL) {
        status = SOUNDTRIGGER_STATUS_BAD_VALUE;
        goto exit;
    }
    size = env->GetArrayLength(jData);

    nData = env->GetByteArrayElements(jData, NULL);
    if (jData == NULL) {
        status = SOUNDTRIGGER_STATUS_ERROR;
        goto exit;
    }

    memoryDealer = new MemoryDealer(offset + size, "SoundTrigge-JNI::LoadModel");
    if (memoryDealer == 0) {
        status = SOUNDTRIGGER_STATUS_ERROR;
        goto exit;
    }
    memory = memoryDealer->allocate(offset + size);
    if (memory == 0 || memory->pointer() == NULL) {
        status = SOUNDTRIGGER_STATUS_ERROR;
        goto exit;
    }

    nSoundModel = (struct sound_trigger_sound_model *)memory->pointer();

    nSoundModel->type = type;
    nSoundModel->uuid = nUuid;
    nSoundModel->vendor_uuid = nVendorUuid;
    nSoundModel->data_size = size;
    nSoundModel->data_offset = offset;
    memcpy((char *)nSoundModel + offset, nData, size);
    if (type == SOUND_MODEL_TYPE_KEYPHRASE) {
        struct sound_trigger_phrase_sound_model *phraseModel =
                (struct sound_trigger_phrase_sound_model *)nSoundModel;

        jobjectArray jPhrases =
            (jobjectArray)env->GetObjectField(jSoundModel, gKeyphraseSoundModelFields.keyphrases);
        if (jPhrases == NULL) {
            status = SOUNDTRIGGER_STATUS_BAD_VALUE;
            goto exit;
        }

        size_t numPhrases = env->GetArrayLength(jPhrases);
        phraseModel->num_phrases = numPhrases;
        ALOGV("loadSoundModel numPhrases %zu", numPhrases);
        for (size_t i = 0; i < numPhrases; i++) {
            jobject jPhrase = env->GetObjectArrayElement(jPhrases, i);
            phraseModel->phrases[i].id =
                                    env->GetIntField(jPhrase,gKeyphraseFields.id);
            phraseModel->phrases[i].recognition_mode =
                                    env->GetIntField(jPhrase,gKeyphraseFields.recognitionModes);

            jintArray jUsers = (jintArray)env->GetObjectField(jPhrase, gKeyphraseFields.users);
            phraseModel->phrases[i].num_users = env->GetArrayLength(jUsers);
            jint *nUsers = env->GetIntArrayElements(jUsers, NULL);
            memcpy(phraseModel->phrases[i].users,
                   nUsers,
                   phraseModel->phrases[i].num_users * sizeof(int));
            env->ReleaseIntArrayElements(jUsers, nUsers, 0);
            env->DeleteLocalRef(jUsers);

            jstring jLocale = (jstring)env->GetObjectField(jPhrase, gKeyphraseFields.locale);
            const char *nLocale = env->GetStringUTFChars(jLocale, NULL);
            strncpy(phraseModel->phrases[i].locale,
                    nLocale,
                    SOUND_TRIGGER_MAX_LOCALE_LEN);
            jstring jText = (jstring)env->GetObjectField(jPhrase, gKeyphraseFields.text);
            const char *nText = env->GetStringUTFChars(jText, NULL);
            strncpy(phraseModel->phrases[i].text,
                    nText,
                    SOUND_TRIGGER_MAX_STRING_LEN);

            env->ReleaseStringUTFChars(jLocale, nLocale);
            env->DeleteLocalRef(jLocale);
            env->ReleaseStringUTFChars(jText, nText);
            env->DeleteLocalRef(jText);
            ALOGV("loadSoundModel phrases %zu text %s locale %s",
                  i, phraseModel->phrases[i].text, phraseModel->phrases[i].locale);
            env->DeleteLocalRef(jPhrase);
        }
        env->DeleteLocalRef(jPhrases);
    } else if (type == SOUND_MODEL_TYPE_GENERIC) {
        /* No initialization needed */
    }
    status = module->loadSoundModel(memory, &handle);
    ALOGV("loadSoundModel status %d handle %d", status, handle);

exit:
    if (nHandle != NULL) {
        nHandle[0] = (jint)handle;
        env->ReleaseIntArrayElements(jHandle, nHandle, NULL);
    }
    if (nData != NULL) {
        env->ReleaseByteArrayElements(jData, nData, NULL);
    }
    return status;
}

static jint
android_hardware_SoundTrigger_unloadSoundModel(JNIEnv *env, jobject thiz,
                                               jint jHandle)
{
    jint status = SOUNDTRIGGER_STATUS_OK;
    ALOGV("unloadSoundModel");
    sp<SoundTrigger> module = getSoundTrigger(env, thiz);
    if (module == NULL) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }
    status = module->unloadSoundModel((sound_model_handle_t)jHandle);

    return status;
}

static jint
android_hardware_SoundTrigger_startRecognition(JNIEnv *env, jobject thiz,
                                               jint jHandle, jobject jConfig)
{
    jint status = SOUNDTRIGGER_STATUS_OK;
    ALOGV("startRecognition");
    sp<SoundTrigger> module = getSoundTrigger(env, thiz);
    if (module == NULL) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }

    if (!env->IsInstanceOf(jConfig, gRecognitionConfigClass)) {
        return SOUNDTRIGGER_STATUS_BAD_VALUE;
    }

    jbyteArray jData = (jbyteArray)env->GetObjectField(jConfig, gRecognitionConfigFields.data);
    jsize dataSize = 0;
    jbyte *nData = NULL;
    if (jData != NULL) {
        dataSize = env->GetArrayLength(jData);
        if (dataSize == 0) {
            return SOUNDTRIGGER_STATUS_BAD_VALUE;
        }
        nData = env->GetByteArrayElements(jData, NULL);
        if (nData == NULL) {
            return SOUNDTRIGGER_STATUS_ERROR;
        }
    }

    size_t totalSize = sizeof(struct sound_trigger_recognition_config) + dataSize;
    sp<MemoryDealer> memoryDealer =
            new MemoryDealer(totalSize, "SoundTrigge-JNI::StartRecognition");
    if (memoryDealer == 0) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }
    sp<IMemory> memory = memoryDealer->allocate(totalSize);
    if (memory == 0 || memory->pointer() == NULL) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }
    if (dataSize != 0) {
        memcpy((char *)memory->pointer() + sizeof(struct sound_trigger_recognition_config),
                nData,
                dataSize);
        env->ReleaseByteArrayElements(jData, nData, 0);
    }
    env->DeleteLocalRef(jData);
    struct sound_trigger_recognition_config *config =
                                    (struct sound_trigger_recognition_config *)memory->pointer();
    config->data_size = dataSize;
    config->data_offset = sizeof(struct sound_trigger_recognition_config);
    config->capture_requested = env->GetBooleanField(jConfig,
                                                 gRecognitionConfigFields.captureRequested);

    config->num_phrases = 0;
    jobjectArray jPhrases =
        (jobjectArray)env->GetObjectField(jConfig, gRecognitionConfigFields.keyphrases);
    if (jPhrases != NULL) {
        config->num_phrases = env->GetArrayLength(jPhrases);
    }
    ALOGV("startRecognition num phrases %d", config->num_phrases);
    for (size_t i = 0; i < config->num_phrases; i++) {
        jobject jPhrase = env->GetObjectArrayElement(jPhrases, i);
        config->phrases[i].id = env->GetIntField(jPhrase,
                                                gKeyphraseRecognitionExtraFields.id);
        config->phrases[i].recognition_modes = env->GetIntField(jPhrase,
                                                gKeyphraseRecognitionExtraFields.recognitionModes);
        config->phrases[i].confidence_level = env->GetIntField(jPhrase,
                                            gKeyphraseRecognitionExtraFields.coarseConfidenceLevel);
        config->phrases[i].num_levels = 0;
        jobjectArray jConfidenceLevels = (jobjectArray)env->GetObjectField(jPhrase,
                                                gKeyphraseRecognitionExtraFields.confidenceLevels);
        if (jConfidenceLevels != NULL) {
            config->phrases[i].num_levels = env->GetArrayLength(jConfidenceLevels);
        }
        ALOGV("startRecognition phrase %zu num_levels %d", i, config->phrases[i].num_levels);
        for (size_t j = 0; j < config->phrases[i].num_levels; j++) {
            jobject jConfidenceLevel = env->GetObjectArrayElement(jConfidenceLevels, j);
            config->phrases[i].levels[j].user_id = env->GetIntField(jConfidenceLevel,
                                                                    gConfidenceLevelFields.userId);
            config->phrases[i].levels[j].level = env->GetIntField(jConfidenceLevel,
                                                          gConfidenceLevelFields.confidenceLevel);
            env->DeleteLocalRef(jConfidenceLevel);
        }
        ALOGV("startRecognition phrases %zu", i);
        env->DeleteLocalRef(jConfidenceLevels);
        env->DeleteLocalRef(jPhrase);
    }
    env->DeleteLocalRef(jPhrases);

    status = module->startRecognition(jHandle, memory);
    return status;
}

static jint
android_hardware_SoundTrigger_stopRecognition(JNIEnv *env, jobject thiz,
                                               jint jHandle)
{
    jint status = SOUNDTRIGGER_STATUS_OK;
    ALOGV("stopRecognition");
    sp<SoundTrigger> module = getSoundTrigger(env, thiz);
    if (module == NULL) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }
    status = module->stopRecognition(jHandle);
    return status;
}

static jint
android_hardware_SoundTrigger_getModelState(JNIEnv *env, jobject thiz,
                                            jint jHandle)
{
    jint status = SOUNDTRIGGER_STATUS_OK;
    ALOGV("getModelState");
    sp<SoundTrigger> module = getSoundTrigger(env, thiz);
    if (module == NULL) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }
    status = module->getModelState(jHandle);
    return status;
}

static const JNINativeMethod gMethods[] = {
    {"listModules",
        "(Ljava/util/ArrayList;)I",
        (void *)android_hardware_SoundTrigger_listModules},
};


static const JNINativeMethod gModuleMethods[] = {
    {"native_setup",
        "(Ljava/lang/Object;)V",
        (void *)android_hardware_SoundTrigger_setup},
    {"native_finalize",
        "()V",
        (void *)android_hardware_SoundTrigger_finalize},
    {"detach",
        "()V",
        (void *)android_hardware_SoundTrigger_detach},
    {"loadSoundModel",
        "(Landroid/hardware/soundtrigger/SoundTrigger$SoundModel;[I)I",
        (void *)android_hardware_SoundTrigger_loadSoundModel},
    {"unloadSoundModel",
        "(I)I",
        (void *)android_hardware_SoundTrigger_unloadSoundModel},
    {"startRecognition",
        "(ILandroid/hardware/soundtrigger/SoundTrigger$RecognitionConfig;)I",
        (void *)android_hardware_SoundTrigger_startRecognition},
    {"stopRecognition",
        "(I)I",
        (void *)android_hardware_SoundTrigger_stopRecognition},
    {"getModelState",
        "(I)I",
        (void *)android_hardware_SoundTrigger_getModelState},
};

int register_android_hardware_SoundTrigger(JNIEnv *env)
{
    jclass arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gArrayListClass = MakeGlobalRefOrDie(env, arrayListClass);
    gArrayListMethods.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jclass uuidClass = FindClassOrDie(env, "java/util/UUID");
    gUUIDClass = MakeGlobalRefOrDie(env, uuidClass);
    gUUIDMethods.toString = GetMethodIDOrDie(env, uuidClass, "toString", "()Ljava/lang/String;");

    jclass lClass = FindClassOrDie(env, kSoundTriggerClassPathName);
    gSoundTriggerClass = MakeGlobalRefOrDie(env, lClass);

    jclass moduleClass = FindClassOrDie(env, kModuleClassPathName);
    gModuleClass = MakeGlobalRefOrDie(env, moduleClass);
    gPostEventFromNative = GetStaticMethodIDOrDie(env, moduleClass, "postEventFromNative",
                                                  "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    gModuleFields.mNativeContext = GetFieldIDOrDie(env, moduleClass, "mNativeContext", "J");
    gModuleFields.mId = GetFieldIDOrDie(env, moduleClass, "mId", "I");

    jclass modulePropertiesClass = FindClassOrDie(env, kModulePropertiesClassPathName);
    gModulePropertiesClass = MakeGlobalRefOrDie(env, modulePropertiesClass);
    gModulePropertiesCstor = GetMethodIDOrDie(env, modulePropertiesClass, "<init>",
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIIIZIZIZ)V");

    jclass soundModelClass = FindClassOrDie(env, kSoundModelClassPathName);
    gSoundModelClass = MakeGlobalRefOrDie(env, soundModelClass);
    gSoundModelFields.uuid = GetFieldIDOrDie(env, soundModelClass, "uuid", "Ljava/util/UUID;");
    gSoundModelFields.vendorUuid = GetFieldIDOrDie(env, soundModelClass, "vendorUuid",
                                                   "Ljava/util/UUID;");
    gSoundModelFields.data = GetFieldIDOrDie(env, soundModelClass, "data", "[B");

    jclass genericSoundModelClass = FindClassOrDie(env, kGenericSoundModelClassPathName);
    gGenericSoundModelClass = MakeGlobalRefOrDie(env, genericSoundModelClass);

    jclass keyphraseClass = FindClassOrDie(env, kKeyphraseClassPathName);
    gKeyphraseClass = MakeGlobalRefOrDie(env, keyphraseClass);
    gKeyphraseFields.id = GetFieldIDOrDie(env, keyphraseClass, "id", "I");
    gKeyphraseFields.recognitionModes = GetFieldIDOrDie(env, keyphraseClass, "recognitionModes",
                                                        "I");
    gKeyphraseFields.locale = GetFieldIDOrDie(env, keyphraseClass, "locale", "Ljava/lang/String;");
    gKeyphraseFields.text = GetFieldIDOrDie(env, keyphraseClass, "text", "Ljava/lang/String;");
    gKeyphraseFields.users = GetFieldIDOrDie(env, keyphraseClass, "users", "[I");

    jclass keyphraseSoundModelClass = FindClassOrDie(env, kKeyphraseSoundModelClassPathName);
    gKeyphraseSoundModelClass = MakeGlobalRefOrDie(env, keyphraseSoundModelClass);
    gKeyphraseSoundModelFields.keyphrases = GetFieldIDOrDie(env, keyphraseSoundModelClass,
                                         "keyphrases",
                                         "[Landroid/hardware/soundtrigger/SoundTrigger$Keyphrase;");

    jclass recognitionEventClass = FindClassOrDie(env, kRecognitionEventClassPathName);
    gRecognitionEventClass = MakeGlobalRefOrDie(env, recognitionEventClass);
    gRecognitionEventCstor = GetMethodIDOrDie(env, recognitionEventClass, "<init>",
                                              "(IIZIIIZLandroid/media/AudioFormat;[B)V");

    jclass keyphraseRecognitionEventClass = FindClassOrDie(env,
                                                           kKeyphraseRecognitionEventClassPathName);
    gKeyphraseRecognitionEventClass = MakeGlobalRefOrDie(env, keyphraseRecognitionEventClass);
    gKeyphraseRecognitionEventCstor = GetMethodIDOrDie(env, keyphraseRecognitionEventClass, "<init>",
              "(IIZIIIZLandroid/media/AudioFormat;[B[Landroid/hardware/soundtrigger/SoundTrigger$KeyphraseRecognitionExtra;)V");

    jclass genericRecognitionEventClass = FindClassOrDie(env,
                                                           kGenericRecognitionEventClassPathName);
    gGenericRecognitionEventClass = MakeGlobalRefOrDie(env, genericRecognitionEventClass);
    gGenericRecognitionEventCstor = GetMethodIDOrDie(env, genericRecognitionEventClass, "<init>",
                                              "(IIZIIIZLandroid/media/AudioFormat;[B)V");

    jclass keyRecognitionConfigClass = FindClassOrDie(env, kRecognitionConfigClassPathName);
    gRecognitionConfigClass = MakeGlobalRefOrDie(env, keyRecognitionConfigClass);
    gRecognitionConfigFields.captureRequested = GetFieldIDOrDie(env, keyRecognitionConfigClass,
                                                                "captureRequested", "Z");
    gRecognitionConfigFields.keyphrases = GetFieldIDOrDie(env, keyRecognitionConfigClass,
           "keyphrases", "[Landroid/hardware/soundtrigger/SoundTrigger$KeyphraseRecognitionExtra;");
    gRecognitionConfigFields.data = GetFieldIDOrDie(env, keyRecognitionConfigClass, "data", "[B");

    jclass keyphraseRecognitionExtraClass = FindClassOrDie(env,
                                                           kKeyphraseRecognitionExtraClassPathName);
    gKeyphraseRecognitionExtraClass = MakeGlobalRefOrDie(env, keyphraseRecognitionExtraClass);
    gKeyphraseRecognitionExtraCstor = GetMethodIDOrDie(env, keyphraseRecognitionExtraClass,
            "<init>", "(III[Landroid/hardware/soundtrigger/SoundTrigger$ConfidenceLevel;)V");
    gKeyphraseRecognitionExtraFields.id = GetFieldIDOrDie(env, gKeyphraseRecognitionExtraClass,
                                                          "id", "I");
    gKeyphraseRecognitionExtraFields.recognitionModes = GetFieldIDOrDie(env,
            gKeyphraseRecognitionExtraClass, "recognitionModes", "I");
    gKeyphraseRecognitionExtraFields.coarseConfidenceLevel = GetFieldIDOrDie(env,
            gKeyphraseRecognitionExtraClass, "coarseConfidenceLevel", "I");
    gKeyphraseRecognitionExtraFields.confidenceLevels = GetFieldIDOrDie(env,
            gKeyphraseRecognitionExtraClass, "confidenceLevels",
            "[Landroid/hardware/soundtrigger/SoundTrigger$ConfidenceLevel;");

    jclass confidenceLevelClass = FindClassOrDie(env, kConfidenceLevelClassPathName);
    gConfidenceLevelClass = MakeGlobalRefOrDie(env, confidenceLevelClass);
    gConfidenceLevelCstor = GetMethodIDOrDie(env, confidenceLevelClass, "<init>", "(II)V");
    gConfidenceLevelFields.userId = GetFieldIDOrDie(env, confidenceLevelClass, "userId", "I");
    gConfidenceLevelFields.confidenceLevel = GetFieldIDOrDie(env, confidenceLevelClass,
                                                             "confidenceLevel", "I");

    jclass audioFormatClass = FindClassOrDie(env, kAudioFormatClassPathName);
    gAudioFormatClass = MakeGlobalRefOrDie(env, audioFormatClass);
    gAudioFormatCstor = GetMethodIDOrDie(env, audioFormatClass, "<init>", "(IIII)V");

    jclass soundModelEventClass = FindClassOrDie(env, kSoundModelEventClassPathName);
    gSoundModelEventClass = MakeGlobalRefOrDie(env, soundModelEventClass);
    gSoundModelEventCstor = GetMethodIDOrDie(env, soundModelEventClass, "<init>", "(II[B)V");


    RegisterMethodsOrDie(env, kSoundTriggerClassPathName, gMethods, NELEM(gMethods));
    return RegisterMethodsOrDie(env, kModuleClassPathName, gModuleMethods, NELEM(gModuleMethods));
}
