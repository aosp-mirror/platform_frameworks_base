/*
 * Copyright (C) 2010 The Android Open Source Project
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


#include <stdio.h>

//#define LOG_NDEBUG 0
#define LOG_TAG "AudioEffects-JNI"

#include <utils/Log.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include "media/AudioEffect.h"

#include <android/content/AttributionSourceState.h>
#include <android_os_Parcel.h>

#include <nativehelper/ScopedUtfChars.h>

#include "android_media_AudioEffect.h"
#include "android_media_AudioEffectDescriptor.h"
#include "android_media_AudioErrors.h"

using namespace android;

#define AUDIOEFFECT_SUCCESS                      0
#define AUDIOEFFECT_ERROR                       (-1)
#define AUDIOEFFECT_ERROR_ALREADY_EXISTS        (-2)
#define AUDIOEFFECT_ERROR_NO_INIT               (-3)
#define AUDIOEFFECT_ERROR_BAD_VALUE             (-4)
#define AUDIOEFFECT_ERROR_INVALID_OPERATION     (-5)
#define AUDIOEFFECT_ERROR_NO_MEMORY             (-6)
#define AUDIOEFFECT_ERROR_DEAD_OBJECT           (-7)

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/audiofx/AudioEffect";

struct fields_t {
    // these fields provide access from C++ to the...
    jclass    clazzEffect;          // AudioEffect class
    jmethodID midPostNativeEvent;   // event post callback method
    jfieldID  fidNativeAudioEffect; // stores in Java the native AudioEffect object
    jfieldID  fidJniData;           // stores in Java additional resources used by the native AudioEffect
};
static fields_t fields;

struct effect_callback_cookie {
    jclass      audioEffect_class;  // AudioEffect class
    jobject     audioEffect_ref;    // AudioEffect object instance
 };

// ----------------------------------------------------------------------------
class AudioEffectJniStorage {
    public:
        effect_callback_cookie mCallbackData;

    AudioEffectJniStorage() {
    }

    ~AudioEffectJniStorage() {
    }

};


jint AudioEffectJni::translateNativeErrorToJava(int code) {
    switch(code) {
    case NO_ERROR:
        return AUDIOEFFECT_SUCCESS;
    case ALREADY_EXISTS:
        return AUDIOEFFECT_ERROR_ALREADY_EXISTS;
    case NO_INIT:
        return AUDIOEFFECT_ERROR_NO_INIT;
    case BAD_VALUE:
        return AUDIOEFFECT_ERROR_BAD_VALUE;
    case NAME_NOT_FOUND:
        // Name not found means the client tried to create an effect not found on the system,
        // which is a form of bad value.
        return AUDIOEFFECT_ERROR_BAD_VALUE;
    case INVALID_OPERATION:
        return AUDIOEFFECT_ERROR_INVALID_OPERATION;
    case NO_MEMORY:
        return AUDIOEFFECT_ERROR_NO_MEMORY;
    case DEAD_OBJECT:
    case FAILED_TRANSACTION: // Hidl crash shows as FAILED_TRANSACTION: -2147483646
        return AUDIOEFFECT_ERROR_DEAD_OBJECT;
    default:
        return AUDIOEFFECT_ERROR;
    }
}

static Mutex sLock;

// ----------------------------------------------------------------------------
static void effectCallback(int event, void* user, void *info) {

    effect_param_t *p;
    int arg1 = 0;
    int arg2 = 0;
    jobject obj = NULL;
    jbyteArray array = NULL;
    jbyte *bytes;
    bool param;
    size_t size;

    effect_callback_cookie *callbackInfo = (effect_callback_cookie *)user;
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    if (!user || !env) {
        ALOGW("effectCallback error user %p, env %p", user, env);
        return;
    }

    ALOGV("effectCallback: callbackInfo %p, audioEffect_ref %p audioEffect_class %p",
            callbackInfo,
            callbackInfo->audioEffect_ref,
            callbackInfo->audioEffect_class);

    switch (event) {
    case AudioEffect::EVENT_CONTROL_STATUS_CHANGED:
        if (info == 0) {
            ALOGW("EVENT_CONTROL_STATUS_CHANGED info == NULL");
            goto effectCallback_Exit;
        }
        param = *(bool *)info;
        arg1 = (int)param;
        ALOGV("EVENT_CONTROL_STATUS_CHANGED");
        break;
    case AudioEffect::EVENT_ENABLE_STATUS_CHANGED:
        if (info == 0) {
            ALOGW("EVENT_ENABLE_STATUS_CHANGED info == NULL");
            goto effectCallback_Exit;
        }
        param = *(bool *)info;
        arg1 = (int)param;
        ALOGV("EVENT_ENABLE_STATUS_CHANGED");
        break;
    case AudioEffect::EVENT_PARAMETER_CHANGED:
        if (info == 0) {
            ALOGW("EVENT_PARAMETER_CHANGED info == NULL");
            goto effectCallback_Exit;
        }
        p = (effect_param_t *)info;
        if (p->psize == 0 || p->vsize == 0) {
            goto effectCallback_Exit;
        }
        // arg1 contains offset of parameter value from start of byte array
        arg1 = sizeof(effect_param_t) + ((p->psize - 1) / sizeof(int) + 1) * sizeof(int);
        size = arg1 + p->vsize;
        array = env->NewByteArray(size);
        if (array == NULL) {
            ALOGE("effectCallback: Couldn't allocate byte array for parameter data");
            goto effectCallback_Exit;
        }
        bytes = env->GetByteArrayElements(array, NULL);
        memcpy(bytes, p, size);
        env->ReleaseByteArrayElements(array, bytes, 0);
        obj = array;
        ALOGV("EVENT_PARAMETER_CHANGED");
       break;
    case AudioEffect::EVENT_ERROR:
        ALOGW("EVENT_ERROR");
        break;
    }

    env->CallStaticVoidMethod(
        callbackInfo->audioEffect_class,
        fields.midPostNativeEvent,
        callbackInfo->audioEffect_ref, event, arg1, arg2, obj);

effectCallback_Exit:
    if (array) {
        env->DeleteLocalRef(array);
    }

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// ----------------------------------------------------------------------------

static sp<AudioEffect> getAudioEffect(JNIEnv* env, jobject thiz)
{
    Mutex::Autolock l(sLock);
    AudioEffect* const ae =
            (AudioEffect*)env->GetLongField(thiz, fields.fidNativeAudioEffect);
    return sp<AudioEffect>(ae);
}

static sp<AudioEffect> setAudioEffect(JNIEnv* env, jobject thiz,
                                    const sp<AudioEffect>& ae)
{
    Mutex::Autolock l(sLock);
    sp<AudioEffect> old =
            (AudioEffect*)env->GetLongField(thiz, fields.fidNativeAudioEffect);
    if (ae.get()) {
        ae->incStrong((void*)setAudioEffect);
    }
    if (old != 0) {
        old->decStrong((void*)setAudioEffect);
    }
    env->SetLongField(thiz, fields.fidNativeAudioEffect, (jlong)ae.get());
    return old;
}

// ----------------------------------------------------------------------------
// This function gets some field IDs, which in turn causes class initialization.
// It is called from a static block in AudioEffect, which won't run until the
// first time an instance of this class is used.
static void
android_media_AudioEffect_native_init(JNIEnv *env)
{

    ALOGV("android_media_AudioEffect_native_init");

    fields.clazzEffect = NULL;

    // Get the AudioEffect class
    jclass clazz = env->FindClass(kClassPathName);
    if (clazz == NULL) {
        ALOGE("Can't find %s", kClassPathName);
        return;
    }

    fields.clazzEffect = (jclass)env->NewGlobalRef(clazz);

    // Get the postEvent method
    fields.midPostNativeEvent = env->GetStaticMethodID(
            fields.clazzEffect,
            "postEventFromNative", "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (fields.midPostNativeEvent == NULL) {
        ALOGE("Can't find AudioEffect.%s", "postEventFromNative");
        return;
    }

    // Get the variables fields
    //      nativeTrackInJavaObj
    fields.fidNativeAudioEffect = env->GetFieldID(
            fields.clazzEffect,
            "mNativeAudioEffect", "J");
    if (fields.fidNativeAudioEffect == NULL) {
        ALOGE("Can't find AudioEffect.%s", "mNativeAudioEffect");
        return;
    }
    //      fidJniData;
    fields.fidJniData = env->GetFieldID(
            fields.clazzEffect,
            "mJniData", "J");
    if (fields.fidJniData == NULL) {
        ALOGE("Can't find AudioEffect.%s", "mJniData");
        return;
    }
}


static jint
android_media_AudioEffect_native_setup(JNIEnv *env, jobject thiz, jobject weak_this,
        jstring type, jstring uuid, jint priority, jint sessionId,
        jint deviceType, jstring deviceAddress,
        jintArray jId, jobjectArray javadesc, jobject jAttributionSource, jboolean probe)
{
    ALOGV("android_media_AudioEffect_native_setup");
    AudioEffectJniStorage* lpJniStorage = NULL;
    int lStatus = AUDIOEFFECT_ERROR_NO_MEMORY;
    sp<AudioEffect> lpAudioEffect;
    jint* nId = NULL;
    const char *typeStr = NULL;
    const char *uuidStr = NULL;
    effect_descriptor_t desc;
    jobject jdesc;
    AudioDeviceTypeAddr device;
    AttributionSourceState attributionSource;
    Parcel* parcel = NULL;

    setAudioEffect(env, thiz, 0);

    if (type != NULL) {
        typeStr = env->GetStringUTFChars(type, NULL);
        if (typeStr == NULL) {  // Out of memory
            jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
            goto setup_failure;
        }
    }

    if (uuid != NULL) {
        uuidStr = env->GetStringUTFChars(uuid, NULL);
        if (uuidStr == NULL) {  // Out of memory
            jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
            goto setup_failure;
        }
    }

    if (typeStr == NULL && uuidStr == NULL) {
        lStatus = AUDIOEFFECT_ERROR_BAD_VALUE;
        goto setup_failure;
    }

    lpJniStorage = new AudioEffectJniStorage();
    if (lpJniStorage == NULL) {
        ALOGE("setup: Error creating JNI Storage");
        goto setup_failure;
    }

    lpJniStorage->mCallbackData.audioEffect_class = (jclass)env->NewGlobalRef(fields.clazzEffect);
    // we use a weak reference so the AudioEffect object can be garbage collected.
    lpJniStorage->mCallbackData.audioEffect_ref = env->NewGlobalRef(weak_this);

    ALOGV("setup: lpJniStorage: %p audioEffect_ref %p audioEffect_class %p, &mCallbackData %p",
            lpJniStorage,
            lpJniStorage->mCallbackData.audioEffect_ref,
            lpJniStorage->mCallbackData.audioEffect_class,
            &lpJniStorage->mCallbackData);

    if (jId == NULL) {
        ALOGE("setup: NULL java array for id pointer");
        lStatus = AUDIOEFFECT_ERROR_BAD_VALUE;
        goto setup_failure;
    }

    if (deviceType != AUDIO_DEVICE_NONE) {
        device.mType = (audio_devices_t)deviceType;
        ScopedUtfChars address(env, deviceAddress);
        device.setAddress(address.c_str());
    }

    // create the native AudioEffect object
    parcel = parcelForJavaObject(env, jAttributionSource);
    attributionSource.readFromParcel(parcel);
    lpAudioEffect = new AudioEffect(attributionSource);
    if (lpAudioEffect == 0) {
        ALOGE("Error creating AudioEffect");
        goto setup_failure;
    }

    lpAudioEffect->set(typeStr,
                       uuidStr,
                       priority,
                       effectCallback,
                       &lpJniStorage->mCallbackData,
                       (audio_session_t) sessionId,
                       AUDIO_IO_HANDLE_NONE,
                       device,
                       probe);
    lStatus = AudioEffectJni::translateNativeErrorToJava(lpAudioEffect->initCheck());
    if (lStatus != AUDIOEFFECT_SUCCESS && lStatus != AUDIOEFFECT_ERROR_ALREADY_EXISTS) {
        ALOGE("AudioEffect initCheck failed %d", lStatus);
        goto setup_failure;
    }

    nId = (jint *) env->GetPrimitiveArrayCritical(jId, NULL);
    if (nId == NULL) {
        ALOGE("setup: Error retrieving id pointer");
        lStatus = AUDIOEFFECT_ERROR_BAD_VALUE;
        goto setup_failure;
    }
    nId[0] = lpAudioEffect->id();
    env->ReleasePrimitiveArrayCritical(jId, nId, 0);
    nId = NULL;

    if (typeStr) {
        env->ReleaseStringUTFChars(type, typeStr);
        typeStr = NULL;
    }

    if (uuidStr) {
        env->ReleaseStringUTFChars(uuid, uuidStr);
        uuidStr = NULL;
    }

    // get the effect descriptor
    desc = lpAudioEffect->descriptor();

    if (convertAudioEffectDescriptorFromNative(env, &jdesc, &desc) != AUDIO_JAVA_SUCCESS) {
        goto setup_failure;
    }

    env->SetObjectArrayElement(javadesc, 0, jdesc);
    env->DeleteLocalRef(jdesc);

    // In probe mode, release the native object and clear our strong reference
    // to force all method calls from JAVA to be rejected.
    if (probe) {
        setAudioEffect(env, thiz, 0);
    } else {
        setAudioEffect(env, thiz, lpAudioEffect);
    }

    env->SetLongField(thiz, fields.fidJniData, (jlong)lpJniStorage);

    return (jint) AUDIOEFFECT_SUCCESS;

    // failures:
setup_failure:

    if (nId != NULL) {
        env->ReleasePrimitiveArrayCritical(jId, nId, 0);
    }

    if (lpJniStorage) {
        env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioEffect_class);
        env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioEffect_ref);
        delete lpJniStorage;
    }
    env->SetLongField(thiz, fields.fidJniData, 0);

    if (uuidStr != NULL) {
        env->ReleaseStringUTFChars(uuid, uuidStr);
    }

    if (typeStr != NULL) {
        env->ReleaseStringUTFChars(type, typeStr);
    }

    return (jint)lStatus;
}


// ----------------------------------------------------------------------------
static void android_media_AudioEffect_native_release(JNIEnv *env,  jobject thiz) {
    sp<AudioEffect> lpAudioEffect = setAudioEffect(env, thiz, 0);
    if (lpAudioEffect == 0) {
        return;
    }

    // delete the JNI data
    AudioEffectJniStorage* lpJniStorage =
        (AudioEffectJniStorage *)env->GetLongField(thiz, fields.fidJniData);

    // reset the native resources in the Java object so any attempt to access
    // them after a call to release fails.
    env->SetLongField(thiz, fields.fidJniData, 0);

    if (lpJniStorage) {
        ALOGV("deleting pJniStorage: %p\n", lpJniStorage);
        env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioEffect_class);
        env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioEffect_ref);
        delete lpJniStorage;
    }
}

// ----------------------------------------------------------------------------
static void android_media_AudioEffect_native_finalize(JNIEnv *env,  jobject thiz) {
    ALOGV("android_media_AudioEffect_native_finalize jobject: %p\n", thiz);
    android_media_AudioEffect_native_release(env, thiz);
}

static jint
android_media_AudioEffect_native_setEnabled(JNIEnv *env, jobject thiz, jboolean enabled)
{
    sp<AudioEffect> lpAudioEffect = getAudioEffect(env, thiz);
    if (lpAudioEffect == 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioEffect pointer for enable()");
        return AUDIOEFFECT_ERROR_NO_INIT;
    }

    return AudioEffectJni::translateNativeErrorToJava(lpAudioEffect->setEnabled(enabled));
}

static jboolean
android_media_AudioEffect_native_getEnabled(JNIEnv *env, jobject thiz)
{
  sp<AudioEffect> lpAudioEffect = getAudioEffect(env, thiz);
  if (lpAudioEffect == 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioEffect pointer for getEnabled()");
        return JNI_FALSE;
    }

    if (lpAudioEffect->getEnabled()) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}


static jboolean
android_media_AudioEffect_native_hasControl(JNIEnv *env, jobject thiz)
{
  sp<AudioEffect> lpAudioEffect = getAudioEffect(env, thiz);
  if (lpAudioEffect == 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioEffect pointer for hasControl()");
        return JNI_FALSE;
    }

    if (lpAudioEffect->initCheck() == NO_ERROR) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static jint android_media_AudioEffect_native_setParameter(JNIEnv *env,
        jobject thiz, jint psize, jbyteArray pJavaParam, jint vsize,
        jbyteArray pJavaValue) {
    // retrieve the AudioEffect object
    jbyte* lpValue = NULL;
    jbyte* lpParam = NULL;
    jint lStatus = AUDIOEFFECT_ERROR_BAD_VALUE;
    effect_param_t *p;
    int voffset;

    sp<AudioEffect> lpAudioEffect = getAudioEffect(env, thiz);
    if (lpAudioEffect == 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Unable to retrieve AudioEffect pointer for setParameter()");
        return AUDIOEFFECT_ERROR_NO_INIT;
    }

    if (psize == 0 || vsize == 0 || pJavaParam == NULL || pJavaValue == NULL) {
        return AUDIOEFFECT_ERROR_BAD_VALUE;
    }

    // get the pointer for the param from the java array
    lpParam = (jbyte *) env->GetPrimitiveArrayCritical(pJavaParam, NULL);
    if (lpParam == NULL) {
        ALOGE("setParameter: Error retrieving param pointer");
        goto setParameter_Exit;
    }

    // get the pointer for the value from the java array
    lpValue = (jbyte *) env->GetPrimitiveArrayCritical(pJavaValue, NULL);
    if (lpValue == NULL) {
        ALOGE("setParameter: Error retrieving value pointer");
        goto setParameter_Exit;
    }

    voffset = ((psize - 1) / sizeof(int) + 1) * sizeof(int);
    p = (effect_param_t *) malloc(sizeof(effect_param_t) + voffset + vsize);
    memcpy(p->data, lpParam, psize);
    p->psize = psize;
    memcpy(p->data + voffset, lpValue, vsize);
    p->vsize = vsize;

    lStatus = lpAudioEffect->setParameter(p);
    if (lStatus == NO_ERROR) {
        lStatus = p->status;
    }

    free(p);

setParameter_Exit:

    if (lpParam != NULL) {
        env->ReleasePrimitiveArrayCritical(pJavaParam, lpParam, 0);
    }
    if (lpValue != NULL) {
        env->ReleasePrimitiveArrayCritical(pJavaValue, lpValue, 0);
    }
    return AudioEffectJni::translateNativeErrorToJava(lStatus);
}

static jint
android_media_AudioEffect_native_getParameter(JNIEnv *env,
        jobject thiz, jint psize, jbyteArray pJavaParam,
        jint vsize, jbyteArray pJavaValue) {
    // retrieve the AudioEffect object
    jbyte* lpParam = NULL;
    jbyte* lpValue = NULL;
    jint lStatus = AUDIOEFFECT_ERROR_BAD_VALUE;
    effect_param_t *p;
    int voffset;

    sp<AudioEffect> lpAudioEffect = getAudioEffect(env, thiz);
    if (lpAudioEffect == 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Unable to retrieve AudioEffect pointer for getParameter()");
        return AUDIOEFFECT_ERROR_NO_INIT;
    }

    if (psize == 0 || vsize == 0 || pJavaParam == NULL || pJavaValue == NULL) {
        return AUDIOEFFECT_ERROR_BAD_VALUE;
    }

    // get the pointer for the param from the java array
    lpParam = (jbyte *) env->GetPrimitiveArrayCritical(pJavaParam, NULL);
    if (lpParam == NULL) {
        ALOGE("getParameter: Error retrieving param pointer");
        goto getParameter_Exit;
    }

    // get the pointer for the value from the java array
    lpValue = (jbyte *) env->GetPrimitiveArrayCritical(pJavaValue, NULL);
    if (lpValue == NULL) {
        ALOGE("getParameter: Error retrieving value pointer");
        goto getParameter_Exit;
    }

    voffset = ((psize - 1) / sizeof(int) + 1) * sizeof(int);
    p = (effect_param_t *) malloc(sizeof(effect_param_t) + voffset + vsize);
    memcpy(p->data, lpParam, psize);
    p->psize = psize;
    p->vsize = vsize;

    lStatus = lpAudioEffect->getParameter(p);
    if (lStatus == NO_ERROR) {
        lStatus = p->status;
        if (lStatus == NO_ERROR) {
            memcpy(lpValue, p->data + voffset, p->vsize);
            vsize = p->vsize;
        }
    }

    free(p);

getParameter_Exit:

    if (lpParam != NULL) {
        env->ReleasePrimitiveArrayCritical(pJavaParam, lpParam, 0);
    }
    if (lpValue != NULL) {
        env->ReleasePrimitiveArrayCritical(pJavaValue, lpValue, 0);
    }

    if (lStatus == NO_ERROR) {
        return vsize;
    }
    return AudioEffectJni::translateNativeErrorToJava(lStatus);
}

static jint android_media_AudioEffect_native_command(JNIEnv *env, jobject thiz,
        jint cmdCode, jint cmdSize, jbyteArray jCmdData, jint replySize,
        jbyteArray jReplyData) {
    jbyte* pCmdData = NULL;
    jbyte* pReplyData = NULL;
    jint lStatus = AUDIOEFFECT_ERROR_BAD_VALUE;

    sp<AudioEffect> lpAudioEffect = getAudioEffect(env, thiz);
    if (lpAudioEffect == 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Unable to retrieve AudioEffect pointer for setParameter()");
        return AUDIOEFFECT_ERROR_NO_INIT;
    }

    if ((cmdSize != 0 && jCmdData == NULL) || (replySize != 0 && jReplyData == NULL)) {
        return AUDIOEFFECT_ERROR_BAD_VALUE;
    }

    // get the pointer for the command from the java array
    if (cmdSize != 0) {
        pCmdData = (jbyte *) env->GetPrimitiveArrayCritical(jCmdData, NULL);
        if (pCmdData == NULL) {
            ALOGE("setParameter: Error retrieving command pointer");
            goto command_Exit;
        }
    }

    // get the pointer for the reply from the java array
    if (replySize != 0 && jReplyData != NULL) {
        pReplyData = (jbyte *) env->GetPrimitiveArrayCritical(jReplyData, NULL);
        if (pReplyData == NULL) {
            ALOGE("setParameter: Error retrieving reply pointer");
            goto command_Exit;
        }
    }

    lStatus = AudioEffectJni::translateNativeErrorToJava(
            lpAudioEffect->command((uint32_t)cmdCode,
                                   (uint32_t)cmdSize,
                                   pCmdData,
                                   (uint32_t *)&replySize,
                                   pReplyData));

command_Exit:

    if (pCmdData != NULL) {
        env->ReleasePrimitiveArrayCritical(jCmdData, pCmdData, 0);
    }
    if (pReplyData != NULL) {
        env->ReleasePrimitiveArrayCritical(jReplyData, pReplyData, 0);
    }

    if (lStatus == NO_ERROR) {
        return replySize;
    }
    return lStatus;
}

static jobjectArray
android_media_AudioEffect_native_queryEffects(JNIEnv *env, jclass clazz __unused)
{
    effect_descriptor_t desc;
    uint32_t totalEffectsCount = 0;
    uint32_t returnedEffectsCount = 0;
    uint32_t i = 0;
    jobjectArray ret;

    if (AudioEffect::queryNumberEffects(&totalEffectsCount) != NO_ERROR) {
        return NULL;
    }

    jobjectArray temp = env->NewObjectArray(totalEffectsCount, audioEffectDescriptorClass(), NULL);
    if (temp == NULL) {
        return temp;
    }

    ALOGV("queryEffects() totalEffectsCount: %d", totalEffectsCount);

    for (i = 0; i < totalEffectsCount; i++) {
        if (AudioEffect::queryEffect(i, &desc) != NO_ERROR) {
            goto queryEffects_failure;
        }

        jobject jdesc;
        if (convertAudioEffectDescriptorFromNative(env, &jdesc, &desc) != AUDIO_JAVA_SUCCESS) {
            continue;
        }
        env->SetObjectArrayElement(temp, returnedEffectsCount++, jdesc);
        env->DeleteLocalRef(jdesc);
    }

    if (returnedEffectsCount == 0) {
        goto queryEffects_failure;
    }
    ret = env->NewObjectArray(returnedEffectsCount, audioEffectDescriptorClass(), NULL);
    if (ret == NULL) {
        goto queryEffects_failure;
    }
    for (i = 0; i < returnedEffectsCount; i++) {
        env->SetObjectArrayElement(ret, i, env->GetObjectArrayElement(temp, i));
    }
    env->DeleteLocalRef(temp);
    return ret;

queryEffects_failure:

    if (temp != NULL) {
        env->DeleteLocalRef(temp);
    }
    return NULL;

}



static jobjectArray
android_media_AudioEffect_native_queryPreProcessings(JNIEnv *env, jclass clazz __unused,
                                                     jint audioSession)
{
    auto descriptors = std::make_unique<effect_descriptor_t[]>(AudioEffect::kMaxPreProcessing);
    uint32_t numEffects = AudioEffect::kMaxPreProcessing;

    status_t status = AudioEffect::queryDefaultPreProcessing((audio_session_t) audioSession,
                                           descriptors.get(),
                                           &numEffects);
    if (status != NO_ERROR || numEffects == 0) {
        return NULL;
    }
    ALOGV("queryDefaultPreProcessing() got %d effects", numEffects);

    std::vector<effect_descriptor_t> descVector(descriptors.get(), descriptors.get() + numEffects);

    jobjectArray ret;
    convertAudioEffectDescriptorVectorFromNative(env, &ret, descVector);
    return ret;
}

// ----------------------------------------------------------------------------

// Dalvik VM type signatures
static const JNINativeMethod gMethods[] = {
    {"native_init",          "()V",      (void *)android_media_AudioEffect_native_init},
    {"native_setup",         "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;[I[Ljava/lang/Object;Landroid/os/Parcel;Z)I",
                                         (void *)android_media_AudioEffect_native_setup},
    {"native_finalize",      "()V",      (void *)android_media_AudioEffect_native_finalize},
    {"native_release",       "()V",      (void *)android_media_AudioEffect_native_release},
    {"native_setEnabled",    "(Z)I",      (void *)android_media_AudioEffect_native_setEnabled},
    {"native_getEnabled",    "()Z",      (void *)android_media_AudioEffect_native_getEnabled},
    {"native_hasControl",    "()Z",      (void *)android_media_AudioEffect_native_hasControl},
    {"native_setParameter",  "(I[BI[B)I",  (void *)android_media_AudioEffect_native_setParameter},
    {"native_getParameter",  "(I[BI[B)I",  (void *)android_media_AudioEffect_native_getParameter},
    {"native_command",       "(II[BI[B)I", (void *)android_media_AudioEffect_native_command},
    {"native_query_effects", "()[Ljava/lang/Object;", (void *)android_media_AudioEffect_native_queryEffects},
    {"native_query_pre_processing", "(I)[Ljava/lang/Object;",
            (void *)android_media_AudioEffect_native_queryPreProcessings},
};


// ----------------------------------------------------------------------------

extern int register_android_media_SourceDefaultEffect(JNIEnv *env);
extern int register_android_media_StreamDefaultEffect(JNIEnv *env);
extern int register_android_media_visualizer(JNIEnv *env);

int register_android_media_AudioEffect(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

jint JNI_OnLoad(JavaVM* vm, void* reserved __unused)
{

    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != NULL);

    if (register_android_media_AudioEffect(env) < 0) {
        ALOGE("ERROR: AudioEffect native registration failed\n");
        goto bail;
    }

    if (register_android_media_SourceDefaultEffect(env) < 0) {
        ALOGE("ERROR: SourceDefaultEffect native registration failed\n");
        goto bail;
    }

    if (register_android_media_StreamDefaultEffect(env) < 0) {
        ALOGE("ERROR: StreamDefaultEffect native registration failed\n");
        goto bail;
    }

    if (register_android_media_visualizer(env) < 0) {
        ALOGE("ERROR: Visualizer native registration failed\n");
        goto bail;
    }

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}
