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
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include <system/sound_trigger.h>
#include <soundtrigger/SoundTriggerCallback.h>
#include <soundtrigger/SoundTrigger.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>
#include <binder/IMemory.h>
#include <binder/MemoryDealer.h>

using namespace android;

static jclass gArrayListClass;
static struct {
    jmethodID    add;
} gArrayListMethods;

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
    jfieldID    data;
} gSoundModelFields;

static const char* const kKeyPhraseClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$KeyPhrase";
static jclass gKeyPhraseClass;
static struct {
    jfieldID recognitionModes;
    jfieldID locale;
    jfieldID text;
    jfieldID numUsers;
} gKeyPhraseFields;

static const char* const kKeyPhraseSoundModelClassPathName =
                                 "android/hardware/soundtrigger/SoundTrigger$KeyPhraseSoundModel";
static jclass gKeyPhraseSoundModelClass;
static struct {
    jfieldID    keyPhrases;
} gKeyPhraseSoundModelFields;


static const char* const kRecognitionEventClassPathName =
                                     "android/hardware/soundtrigger/SoundTrigger$RecognitionEvent";
static jclass gRecognitionEventClass;
static jmethodID   gRecognitionEventCstor;

static const char* const kKeyPhraseRecognitionEventClassPathName =
                             "android/hardware/soundtrigger/SoundTrigger$KeyPhraseRecognitionEvent";
static jclass gKeyPhraseRecognitionEventClass;
static jmethodID   gKeyPhraseRecognitionEventCstor;

static const char* const kKeyPhraseRecognitionExtraClassPathName =
                             "android/hardware/soundtrigger/SoundTrigger$KeyPhraseRecognitionExtra";
static jclass gKeyPhraseRecognitionExtraClass;
static jmethodID   gKeyPhraseRecognitionExtraCstor;

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
};

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class JNISoundTriggerCallback: public SoundTriggerCallback
{
public:
    JNISoundTriggerCallback(JNIEnv* env, jobject thiz, jobject weak_thiz);
    ~JNISoundTriggerCallback();

    virtual void onRecognitionEvent(struct sound_trigger_recognition_event *event);
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

    jobject jEvent;

    jbyteArray jData = NULL;
    if (event->data_size) {
        jData = env->NewByteArray(event->data_size);
        jbyte *nData = env->GetByteArrayElements(jData, NULL);
        memcpy(nData, (char *)event + event->data_offset, event->data_size);
        env->ReleaseByteArrayElements(jData, nData, 0);
    }

    if (event->type == SOUND_MODEL_TYPE_KEYPHRASE) {
        struct sound_trigger_phrase_recognition_event *phraseEvent =
                (struct sound_trigger_phrase_recognition_event *)event;

        jobjectArray jExtras = env->NewObjectArray(phraseEvent->num_phrases,
                                                  gKeyPhraseRecognitionExtraClass, NULL);
        if (jExtras == NULL) {
            return;
        }

        for (size_t i = 0; i < phraseEvent->num_phrases; i++) {
            jintArray jConfidenceLevels = env->NewIntArray(phraseEvent->phrase_extras[i].num_users);
            if (jConfidenceLevels == NULL) {
                return;
            }
            jint *nConfidenceLevels = env->GetIntArrayElements(jConfidenceLevels, NULL);
            memcpy(nConfidenceLevels,
                   phraseEvent->phrase_extras[i].confidence_levels,
                   phraseEvent->phrase_extras[i].num_users * sizeof(int));
            env->ReleaseIntArrayElements(jConfidenceLevels, nConfidenceLevels, 0);
            jobject jNewExtra = env->NewObject(gKeyPhraseRecognitionExtraClass,
                                               gKeyPhraseRecognitionExtraCstor,
                                               jConfidenceLevels,
                                               phraseEvent->phrase_extras[i].recognition_modes);

            if (jNewExtra == NULL) {
                return;
            }
            env->SetObjectArrayElement(jExtras, i, jNewExtra);

        }
        jEvent = env->NewObject(gKeyPhraseRecognitionEventClass, gKeyPhraseRecognitionEventCstor,
                                event->status, event->model, event->capture_available,
                               event->capture_session, event->capture_delay_ms, jData,
                               phraseEvent->key_phrase_in_capture, jExtras);
    } else {
        jEvent = env->NewObject(gRecognitionEventClass, gRecognitionEventCstor,
                                event->status, event->model, event->capture_available,
                                event->capture_session, event->capture_delay_ms, jData);
    }


    env->CallStaticVoidMethod(mClass, gPostEventFromNative, mObject,
                              SOUNDTRIGGER_EVENT_RECOGNITION, 0, 0, jEvent);
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

        ALOGV("listModules module %d id %d description %s maxSoundModels %d",
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
                                               nModules[i].properties.power_consumption_mw);

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
    char *nData = NULL;
    struct sound_trigger_sound_model *nSoundModel;
    jbyteArray jData;
    sp<MemoryDealer> memoryDealer;
    sp<IMemory> memory;
    size_t size;
    sound_model_handle_t handle;

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
    if (env->IsInstanceOf(jSoundModel, gKeyPhraseSoundModelClass)) {
        offset = sizeof(struct sound_trigger_phrase_sound_model);
        type = SOUND_MODEL_TYPE_KEYPHRASE;
    } else {
        offset = sizeof(struct sound_trigger_sound_model);
        type = SOUND_MODEL_TYPE_UNKNOWN;
    }
    jData = (jbyteArray)env->GetObjectField(jSoundModel, gSoundModelFields.data);
    if (jData == NULL) {
        status = SOUNDTRIGGER_STATUS_BAD_VALUE;
        goto exit;
    }
    size = env->GetArrayLength(jData);

    nData = (char *)env->GetByteArrayElements(jData, NULL);
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
    nSoundModel->data_size = size;
    nSoundModel->data_offset = offset;
    memcpy((char *)nSoundModel + offset, nData, size);
    if (type == SOUND_MODEL_TYPE_KEYPHRASE) {
        struct sound_trigger_phrase_sound_model *phraseModel =
                (struct sound_trigger_phrase_sound_model *)nSoundModel;

        jobjectArray jPhrases =
            (jobjectArray)env->GetObjectField(jSoundModel, gKeyPhraseSoundModelFields.keyPhrases);
        if (jPhrases == NULL) {
            status = SOUNDTRIGGER_STATUS_BAD_VALUE;
            goto exit;
        }

        size_t numPhrases = env->GetArrayLength(jPhrases);
        phraseModel->num_phrases = numPhrases;
        ALOGV("loadSoundModel numPhrases %d", numPhrases);
        for (size_t i = 0; i < numPhrases; i++) {
            jobject jPhrase = env->GetObjectArrayElement(jPhrases, i);
            phraseModel->phrases[i].recognition_mode =
                                    env->GetIntField(jPhrase,gKeyPhraseFields.recognitionModes);
            phraseModel->phrases[i].num_users =
                                    env->GetIntField(jPhrase, gKeyPhraseFields.numUsers);
            jstring jLocale = (jstring)env->GetObjectField(jPhrase, gKeyPhraseFields.locale);
            const char *nLocale = env->GetStringUTFChars(jLocale, NULL);
            strncpy(phraseModel->phrases[i].locale,
                    nLocale,
                    SOUND_TRIGGER_MAX_LOCALE_LEN);
            jstring jText = (jstring)env->GetObjectField(jPhrase, gKeyPhraseFields.text);
            const char *nText = env->GetStringUTFChars(jText, NULL);
            strncpy(phraseModel->phrases[i].text,
                    nText,
                    SOUND_TRIGGER_MAX_STRING_LEN);

            env->ReleaseStringUTFChars(jLocale, nLocale);
            env->DeleteLocalRef(jLocale);
            env->ReleaseStringUTFChars(jText, nText);
            env->DeleteLocalRef(jText);
            ALOGV("loadSoundModel phrases %d text %s locale %s",
                  i, phraseModel->phrases[i].text, phraseModel->phrases[i].locale);
        }
        env->DeleteLocalRef(jPhrases);
    }
    status = module->loadSoundModel(memory, &handle);
    ALOGV("loadSoundModel status %d handle %d", status, handle);

exit:
    if (nHandle != NULL) {
        nHandle[0] = (jint)handle;
        env->ReleaseIntArrayElements(jHandle, nHandle, NULL);
    }
    if (nData != NULL) {
        env->ReleaseByteArrayElements(jData, (jbyte *)nData, NULL);
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
                                               jint jHandle, jbyteArray jData)
{
    jint status = SOUNDTRIGGER_STATUS_OK;
    ALOGV("startRecognition");
    sp<SoundTrigger> module = getSoundTrigger(env, thiz);
    if (module == NULL) {
        return SOUNDTRIGGER_STATUS_ERROR;
    }
    jsize dataSize = 0;
    char *nData = NULL;
    sp<IMemory> memory;
    if (jData != NULL) {
        dataSize = env->GetArrayLength(jData);
        if (dataSize == 0) {
            return SOUNDTRIGGER_STATUS_BAD_VALUE;
        }
        nData = (char *)env->GetByteArrayElements(jData, NULL);
        if (nData == NULL) {
            return SOUNDTRIGGER_STATUS_ERROR;
        }
        sp<MemoryDealer> memoryDealer =
                new MemoryDealer(dataSize, "SoundTrigge-JNI::StartRecognition");
        if (memoryDealer == 0) {
            return SOUNDTRIGGER_STATUS_ERROR;
        }
        memory = memoryDealer->allocate(dataSize);
        if (memory == 0 || memory->pointer() == NULL) {
            return SOUNDTRIGGER_STATUS_ERROR;
        }
        memcpy(memory->pointer(), nData, dataSize);
    }

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

static JNINativeMethod gMethods[] = {
    {"listModules",
        "(Ljava/util/ArrayList;)I",
        (void *)android_hardware_SoundTrigger_listModules},
};


static JNINativeMethod gModuleMethods[] = {
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
        "(I[B)I",
        (void *)android_hardware_SoundTrigger_startRecognition},
    {"stopRecognition",
        "(I)I",
        (void *)android_hardware_SoundTrigger_stopRecognition},
};

int register_android_hardware_SoundTrigger(JNIEnv *env)
{
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    gArrayListClass = (jclass) env->NewGlobalRef(arrayListClass);
    gArrayListMethods.add = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jclass lClass = env->FindClass(kSoundTriggerClassPathName);
    gSoundTriggerClass = (jclass) env->NewGlobalRef(lClass);

    jclass moduleClass = env->FindClass(kModuleClassPathName);
    gModuleClass = (jclass) env->NewGlobalRef(moduleClass);
    gPostEventFromNative = env->GetStaticMethodID(moduleClass, "postEventFromNative",
                                            "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    gModuleFields.mNativeContext = env->GetFieldID(moduleClass, "mNativeContext", "J");
    gModuleFields.mId = env->GetFieldID(moduleClass, "mId", "I");


    jclass modulePropertiesClass = env->FindClass(kModulePropertiesClassPathName);
    gModulePropertiesClass = (jclass) env->NewGlobalRef(modulePropertiesClass);
    gModulePropertiesCstor = env->GetMethodID(modulePropertiesClass, "<init>",
                              "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIIIZIZI)V");

    jclass soundModelClass = env->FindClass(kSoundModelClassPathName);
    gSoundModelClass = (jclass) env->NewGlobalRef(soundModelClass);
    gSoundModelFields.data = env->GetFieldID(soundModelClass, "data", "[B");

    jclass keyPhraseClass = env->FindClass(kKeyPhraseClassPathName);
    gKeyPhraseClass = (jclass) env->NewGlobalRef(keyPhraseClass);
    gKeyPhraseFields.recognitionModes = env->GetFieldID(keyPhraseClass, "recognitionModes", "I");
    gKeyPhraseFields.locale = env->GetFieldID(keyPhraseClass, "locale", "Ljava/lang/String;");
    gKeyPhraseFields.text = env->GetFieldID(keyPhraseClass, "text", "Ljava/lang/String;");
    gKeyPhraseFields.numUsers = env->GetFieldID(keyPhraseClass, "numUsers", "I");

    jclass keyPhraseSoundModelClass = env->FindClass(kKeyPhraseSoundModelClassPathName);
    gKeyPhraseSoundModelClass = (jclass) env->NewGlobalRef(keyPhraseSoundModelClass);
    gKeyPhraseSoundModelFields.keyPhrases = env->GetFieldID(keyPhraseSoundModelClass,
                                         "keyPhrases",
                                         "[Landroid/hardware/soundtrigger/SoundTrigger$KeyPhrase;");


    jclass recognitionEventClass = env->FindClass(kRecognitionEventClassPathName);
    gRecognitionEventClass = (jclass) env->NewGlobalRef(recognitionEventClass);
    gRecognitionEventCstor = env->GetMethodID(recognitionEventClass, "<init>",
                                              "(IIZII[B)V");

    jclass keyPhraseRecognitionEventClass = env->FindClass(kKeyPhraseRecognitionEventClassPathName);
    gKeyPhraseRecognitionEventClass = (jclass) env->NewGlobalRef(keyPhraseRecognitionEventClass);
    gKeyPhraseRecognitionEventCstor = env->GetMethodID(keyPhraseRecognitionEventClass, "<init>",
              "(IIZII[BZ[Landroid/hardware/soundtrigger/SoundTrigger$KeyPhraseRecognitionExtra;)V");


    jclass keyPhraseRecognitionExtraClass = env->FindClass(kKeyPhraseRecognitionExtraClassPathName);
    gKeyPhraseRecognitionExtraClass = (jclass) env->NewGlobalRef(keyPhraseRecognitionExtraClass);
    gKeyPhraseRecognitionExtraCstor = env->GetMethodID(keyPhraseRecognitionExtraClass, "<init>",
                                              "([II)V");

    int status = AndroidRuntime::registerNativeMethods(env,
                kSoundTriggerClassPathName, gMethods, NELEM(gMethods));

    if (status == 0) {
        status = AndroidRuntime::registerNativeMethods(env,
                kModuleClassPathName, gModuleMethods, NELEM(gModuleMethods));
    }

    return status;
}
