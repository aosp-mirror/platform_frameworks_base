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
#define LOG_TAG "visualizers-JNI"

#include <utils/Log.h>
#include <nativehelper/jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/threads.h>
#include "media/Visualizer.h"

using namespace android;

#define VISUALIZER_SUCCESS                      0
#define VISUALIZER_ERROR                       -1
#define VISUALIZER_ERROR_ALREADY_EXISTS        -2
#define VISUALIZER_ERROR_NO_INIT               -3
#define VISUALIZER_ERROR_BAD_VALUE             -4
#define VISUALIZER_ERROR_INVALID_OPERATION     -5
#define VISUALIZER_ERROR_NO_MEMORY             -6
#define VISUALIZER_ERROR_DEAD_OBJECT           -7

#define NATIVE_EVENT_PCM_CAPTURE                0
#define NATIVE_EVENT_FFT_CAPTURE                1
#define NATIVE_EVENT_SERVER_DIED                2

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/audiofx/Visualizer";
static const char* const kClassPeakRmsPathName =
        "android/media/audiofx/Visualizer$MeasurementPeakRms";

struct fields_t {
    // these fields provide access from C++ to the...
    jclass    clazzEffect;          // Visualizer class
    jmethodID midPostNativeEvent;   // event post callback method
    jfieldID  fidNativeVisualizer; // stores in Java the native Visualizer object
    jfieldID  fidJniData;           // stores in Java additional resources used by the native Visualizer
    jfieldID  fidPeak; // to access Visualizer.MeasurementPeakRms.mPeak
    jfieldID  fidRms;  // to access Visualizer.MeasurementPeakRms.mRms
};
static fields_t fields;

struct visualizer_callback_cookie {
    jclass      visualizer_class;  // Visualizer class
    jobject     visualizer_ref;    // Visualizer object instance

    // Lazily allocated arrays used to hold callback data provided to java
    // applications.  These arrays are allocated during the first callback and
    // reallocated when the size of the callback data changes.  Allocating on
    // demand and saving the arrays means that applications cannot safely hold a
    // reference to the provided data (they need to make a copy if they want to
    // hold onto outside of the callback scope), but it avoids GC thrash caused
    // by constantly allocating and releasing arrays to hold callback data.
    Mutex       callback_data_lock;
    jbyteArray  waveform_data;
    jbyteArray  fft_data;

    visualizer_callback_cookie() {
        waveform_data = NULL;
        fft_data = NULL;
    }

    ~visualizer_callback_cookie() {
        cleanupBuffers();
    }

    void cleanupBuffers() {
        AutoMutex lock(&callback_data_lock);
        if (waveform_data || fft_data) {
            JNIEnv *env = AndroidRuntime::getJNIEnv();

            if (waveform_data) {
                env->DeleteGlobalRef(waveform_data);
                waveform_data = NULL;
            }

            if (fft_data) {
                env->DeleteGlobalRef(fft_data);
                fft_data = NULL;
            }
        }
    }
 };

// ----------------------------------------------------------------------------
class visualizerJniStorage {
    public:
        visualizer_callback_cookie mCallbackData;

    visualizerJniStorage() {
    }

    ~visualizerJniStorage() {
    }
};


static jint translateError(int code) {
    switch(code) {
    case NO_ERROR:
        return VISUALIZER_SUCCESS;
    case ALREADY_EXISTS:
        return VISUALIZER_ERROR_ALREADY_EXISTS;
    case NO_INIT:
        return VISUALIZER_ERROR_NO_INIT;
    case BAD_VALUE:
        return VISUALIZER_ERROR_BAD_VALUE;
    case INVALID_OPERATION:
        return VISUALIZER_ERROR_INVALID_OPERATION;
    case NO_MEMORY:
        return VISUALIZER_ERROR_NO_MEMORY;
    case DEAD_OBJECT:
        return VISUALIZER_ERROR_DEAD_OBJECT;
    default:
        return VISUALIZER_ERROR;
    }
}


// ----------------------------------------------------------------------------
static void ensureArraySize(JNIEnv *env, jbyteArray *array, uint32_t size) {
    if (NULL != *array) {
        uint32_t len = env->GetArrayLength(*array);
        if (len == size)
            return;

        env->DeleteGlobalRef(*array);
        *array = NULL;
    }

    jbyteArray localRef = env->NewByteArray(size);
    if (NULL != localRef) {
        // Promote to global ref.
        *array = (jbyteArray)env->NewGlobalRef(localRef);

        // Release our (now pointless) local ref.
        env->DeleteLocalRef(localRef);
    }
}

static void captureCallback(void* user,
        uint32_t waveformSize,
        uint8_t *waveform,
        uint32_t fftSize,
        uint8_t *fft,
        uint32_t samplingrate) {

    int arg1 = 0;
    int arg2 = 0;
    size_t size;

    visualizer_callback_cookie *callbackInfo = (visualizer_callback_cookie *)user;
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    if (!user || !env) {
        ALOGW("captureCallback error user %p, env %p", user, env);
        return;
    }

    ALOGV("captureCallback: callbackInfo %p, visualizer_ref %p visualizer_class %p",
            callbackInfo,
            callbackInfo->visualizer_ref,
            callbackInfo->visualizer_class);

    AutoMutex lock(&callbackInfo->callback_data_lock);

    if (waveformSize != 0 && waveform != NULL) {
        jbyteArray jArray;

        ensureArraySize(env, &callbackInfo->waveform_data, waveformSize);
        jArray = callbackInfo->waveform_data;

        if (jArray != NULL) {
            jbyte *nArray = env->GetByteArrayElements(jArray, NULL);
            memcpy(nArray, waveform, waveformSize);
            env->ReleaseByteArrayElements(jArray, nArray, 0);
            env->CallStaticVoidMethod(
                callbackInfo->visualizer_class,
                fields.midPostNativeEvent,
                callbackInfo->visualizer_ref,
                NATIVE_EVENT_PCM_CAPTURE,
                samplingrate,
                0,
                jArray);
        }
    }

    if (fftSize != 0 && fft != NULL) {
        jbyteArray jArray;

        ensureArraySize(env, &callbackInfo->fft_data, fftSize);
        jArray = callbackInfo->fft_data;

        if (jArray != NULL) {
            jbyte *nArray = env->GetByteArrayElements(jArray, NULL);
            memcpy(nArray, fft, fftSize);
            env->ReleaseByteArrayElements(jArray, nArray, 0);
            env->CallStaticVoidMethod(
                callbackInfo->visualizer_class,
                fields.midPostNativeEvent,
                callbackInfo->visualizer_ref,
                NATIVE_EVENT_FFT_CAPTURE,
                samplingrate,
                0,
                jArray);
        }
    }

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

static Visualizer *getVisualizer(JNIEnv* env, jobject thiz)
{
    Visualizer *v = (Visualizer *)env->GetLongField(
        thiz, fields.fidNativeVisualizer);
    if (v == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve Visualizer pointer");
    }
    return v;
}

// ----------------------------------------------------------------------------
// This function gets some field IDs, which in turn causes class initialization.
// It is called from a static block in Visualizer, which won't run until the
// first time an instance of this class is used.
static void
android_media_visualizer_native_init(JNIEnv *env)
{

    ALOGV("android_media_visualizer_native_init");

    fields.clazzEffect = NULL;

    // Get the Visualizer class
    jclass clazz = env->FindClass(kClassPathName);
    if (clazz == NULL) {
        ALOGE("Can't find %s", kClassPathName);
        return;
    }

    fields.clazzEffect = (jclass)env->NewGlobalRef(clazz);

    // Get the Visualizer.MeasurementPeakRms class
    clazz = env->FindClass(kClassPeakRmsPathName);
    if (clazz == NULL) {
        ALOGE("Can't find %s", kClassPeakRmsPathName);
        return;
    }
    jclass clazzMeasurementPeakRms = (jclass)env->NewGlobalRef(clazz);

    // Get the postEvent method
    fields.midPostNativeEvent = env->GetStaticMethodID(
            fields.clazzEffect,
            "postEventFromNative", "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (fields.midPostNativeEvent == NULL) {
        ALOGE("Can't find Visualizer.%s", "postEventFromNative");
        return;
    }

    // Get the variables fields
    //      nativeTrackInJavaObj
    fields.fidNativeVisualizer = env->GetFieldID(
            fields.clazzEffect,
            "mNativeVisualizer", "J");
    if (fields.fidNativeVisualizer == NULL) {
        ALOGE("Can't find Visualizer.%s", "mNativeVisualizer");
        return;
    }
    //      fidJniData;
    fields.fidJniData = env->GetFieldID(
            fields.clazzEffect,
            "mJniData", "J");
    if (fields.fidJniData == NULL) {
        ALOGE("Can't find Visualizer.%s", "mJniData");
        return;
    }
    //      fidPeak
    fields.fidPeak = env->GetFieldID(
            clazzMeasurementPeakRms,
            "mPeak", "I");
    if (fields.fidPeak == NULL) {
        ALOGE("Can't find Visualizer.MeasurementPeakRms.%s", "mPeak");
        return;
    }
    //      fidRms
    fields.fidRms = env->GetFieldID(
            clazzMeasurementPeakRms,
            "mRms", "I");
    if (fields.fidRms == NULL) {
        ALOGE("Can't find Visualizer.MeasurementPeakRms.%s", "mPeak");
        return;
    }

    env->DeleteGlobalRef(clazzMeasurementPeakRms);
}

static void android_media_visualizer_effect_callback(int32_t event,
                                                     void *user,
                                                     void *info) {
    if ((event == AudioEffect::EVENT_ERROR) &&
        (*((status_t*)info) == DEAD_OBJECT)) {
        visualizerJniStorage* lpJniStorage = (visualizerJniStorage*)user;
        visualizer_callback_cookie* callbackInfo = &lpJniStorage->mCallbackData;
        JNIEnv *env = AndroidRuntime::getJNIEnv();

        env->CallStaticVoidMethod(
            callbackInfo->visualizer_class,
            fields.midPostNativeEvent,
            callbackInfo->visualizer_ref,
            NATIVE_EVENT_SERVER_DIED,
            0, 0, NULL);
    }
}

static jint
android_media_visualizer_native_setup(JNIEnv *env, jobject thiz, jobject weak_this,
        jint sessionId, jintArray jId)
{
    ALOGV("android_media_visualizer_native_setup");
    visualizerJniStorage* lpJniStorage = NULL;
    int lStatus = VISUALIZER_ERROR_NO_MEMORY;
    Visualizer* lpVisualizer = NULL;
    jint* nId = NULL;

    lpJniStorage = new visualizerJniStorage();
    if (lpJniStorage == NULL) {
        ALOGE("setup: Error creating JNI Storage");
        goto setup_failure;
    }

    lpJniStorage->mCallbackData.visualizer_class = (jclass)env->NewGlobalRef(fields.clazzEffect);
    // we use a weak reference so the Visualizer object can be garbage collected.
    lpJniStorage->mCallbackData.visualizer_ref = env->NewGlobalRef(weak_this);

    ALOGV("setup: lpJniStorage: %p visualizer_ref %p visualizer_class %p, &mCallbackData %p",
            lpJniStorage,
            lpJniStorage->mCallbackData.visualizer_ref,
            lpJniStorage->mCallbackData.visualizer_class,
            &lpJniStorage->mCallbackData);

    if (jId == NULL) {
        ALOGE("setup: NULL java array for id pointer");
        lStatus = VISUALIZER_ERROR_BAD_VALUE;
        goto setup_failure;
    }

    // create the native Visualizer object
    lpVisualizer = new Visualizer(0,
                                  android_media_visualizer_effect_callback,
                                  lpJniStorage,
                                  sessionId);
    if (lpVisualizer == NULL) {
        ALOGE("Error creating Visualizer");
        goto setup_failure;
    }

    lStatus = translateError(lpVisualizer->initCheck());
    if (lStatus != VISUALIZER_SUCCESS && lStatus != VISUALIZER_ERROR_ALREADY_EXISTS) {
        ALOGE("Visualizer initCheck failed %d", lStatus);
        goto setup_failure;
    }

    nId = (jint *) env->GetPrimitiveArrayCritical(jId, NULL);
    if (nId == NULL) {
        ALOGE("setup: Error retrieving id pointer");
        lStatus = VISUALIZER_ERROR_BAD_VALUE;
        goto setup_failure;
    }
    nId[0] = lpVisualizer->id();
    env->ReleasePrimitiveArrayCritical(jId, nId, 0);
    nId = NULL;

    env->SetLongField(thiz, fields.fidNativeVisualizer, (jlong)lpVisualizer);

    env->SetLongField(thiz, fields.fidJniData, (jlong)lpJniStorage);

    return VISUALIZER_SUCCESS;

    // failures:
setup_failure:

    if (nId != NULL) {
        env->ReleasePrimitiveArrayCritical(jId, nId, 0);
    }

    if (lpVisualizer) {
        delete lpVisualizer;
    }
    env->SetLongField(thiz, fields.fidNativeVisualizer, 0);

    if (lpJniStorage) {
        delete lpJniStorage;
    }
    env->SetLongField(thiz, fields.fidJniData, 0);

    return (jint) lStatus;
}

// ----------------------------------------------------------------------------
static void android_media_visualizer_native_finalize(JNIEnv *env,  jobject thiz) {
    ALOGV("android_media_visualizer_native_finalize jobject: %p\n", thiz);

    // delete the Visualizer object
    Visualizer* lpVisualizer = (Visualizer *)env->GetLongField(
        thiz, fields.fidNativeVisualizer);
    if (lpVisualizer) {
        ALOGV("deleting Visualizer: %p\n", lpVisualizer);
        delete lpVisualizer;
    }

    // delete the JNI data
    visualizerJniStorage* lpJniStorage = (visualizerJniStorage *)env->GetLongField(
        thiz, fields.fidJniData);
    if (lpJniStorage) {
        ALOGV("deleting pJniStorage: %p\n", lpJniStorage);
        delete lpJniStorage;
    }
}

// ----------------------------------------------------------------------------
static void android_media_visualizer_native_release(JNIEnv *env,  jobject thiz) {

    // do everything a call to finalize would
    android_media_visualizer_native_finalize(env, thiz);
    // + reset the native resources in the Java object so any attempt to access
    // them after a call to release fails.
    env->SetLongField(thiz, fields.fidNativeVisualizer, 0);
    env->SetLongField(thiz, fields.fidJniData, 0);
}

static jint
android_media_visualizer_native_setEnabled(JNIEnv *env, jobject thiz, jboolean enabled)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    jint retVal = translateError(lpVisualizer->setEnabled(enabled));

    if (!enabled) {
        visualizerJniStorage* lpJniStorage = (visualizerJniStorage *)env->GetLongField(
            thiz, fields.fidJniData);

        if (NULL != lpJniStorage)
            lpJniStorage->mCallbackData.cleanupBuffers();
    }

    return retVal;
}

static jboolean
android_media_visualizer_native_getEnabled(JNIEnv *env, jobject thiz)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return JNI_FALSE;
    }

    if (lpVisualizer->getEnabled()) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static jintArray
android_media_visualizer_native_getCaptureSizeRange(JNIEnv *env, jobject thiz)
{
    jintArray jRange = env->NewIntArray(2);
    jint *nRange = env->GetIntArrayElements(jRange, NULL);
    nRange[0] = Visualizer::getMinCaptureSize();
    nRange[1] = Visualizer::getMaxCaptureSize();
    ALOGV("getCaptureSizeRange() min %d max %d", nRange[0], nRange[1]);
    env->ReleaseIntArrayElements(jRange, nRange, 0);
    return jRange;
}

static jint
android_media_visualizer_native_getMaxCaptureRate(JNIEnv *env, jobject thiz)
{
    return (jint) Visualizer::getMaxCaptureRate();
}

static jint
android_media_visualizer_native_setCaptureSize(JNIEnv *env, jobject thiz, jint size)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    return translateError(lpVisualizer->setCaptureSize(size));
}

static jint
android_media_visualizer_native_getCaptureSize(JNIEnv *env, jobject thiz)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return -1;
    }
    return (jint) lpVisualizer->getCaptureSize();
}

static jint
android_media_visualizer_native_setScalingMode(JNIEnv *env, jobject thiz, jint mode)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    return translateError(lpVisualizer->setScalingMode(mode));
}

static jint
android_media_visualizer_native_getScalingMode(JNIEnv *env, jobject thiz)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return -1;
    }
    return (jint)lpVisualizer->getScalingMode();
}

static jint
android_media_visualizer_native_setMeasurementMode(JNIEnv *env, jobject thiz, jint mode)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }
    return translateError(lpVisualizer->setMeasurementMode(mode));
}

static jint
android_media_visualizer_native_getMeasurementMode(JNIEnv *env, jobject thiz)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return MEASUREMENT_MODE_NONE;
    }
    return lpVisualizer->getMeasurementMode();
}

static jint
android_media_visualizer_native_getSamplingRate(JNIEnv *env, jobject thiz)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return -1;
    }
    return (jint) lpVisualizer->getSamplingRate();
}

static jint
android_media_visualizer_native_getWaveForm(JNIEnv *env, jobject thiz, jbyteArray jWaveform)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    jbyte* nWaveform = (jbyte *) env->GetPrimitiveArrayCritical(jWaveform, NULL);
    if (nWaveform == NULL) {
        return VISUALIZER_ERROR_NO_MEMORY;
    }
    jint status = translateError(lpVisualizer->getWaveForm((uint8_t *)nWaveform));

    env->ReleasePrimitiveArrayCritical(jWaveform, nWaveform, 0);
    return status;
}

static jint
android_media_visualizer_native_getFft(JNIEnv *env, jobject thiz, jbyteArray jFft)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    jbyte* nFft = (jbyte *) env->GetPrimitiveArrayCritical(jFft, NULL);
    if (nFft == NULL) {
        return VISUALIZER_ERROR_NO_MEMORY;
    }
    jint status = translateError(lpVisualizer->getFft((uint8_t *)nFft));

    env->ReleasePrimitiveArrayCritical(jFft, nFft, 0);

    return status;
}

static jint
android_media_visualizer_native_getPeakRms(JNIEnv *env, jobject thiz, jobject jPeakRmsObj)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }
    int32_t measurements[2];
    jint status = translateError(
                lpVisualizer->getIntMeasurements(MEASUREMENT_MODE_PEAK_RMS,
                        2, measurements));
    if (status == VISUALIZER_SUCCESS) {
        // measurement worked, write the values to the java object
        env->SetIntField(jPeakRmsObj, fields.fidPeak, measurements[MEASUREMENT_IDX_PEAK]);
        env->SetIntField(jPeakRmsObj, fields.fidRms, measurements[MEASUREMENT_IDX_RMS]);
    }
    return status;
}

static jint
android_media_setPeriodicCapture(JNIEnv *env, jobject thiz, jint rate, jboolean jWaveform, jboolean jFft)
{
    Visualizer* lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }
    visualizerJniStorage* lpJniStorage = (visualizerJniStorage *)env->GetLongField(thiz,
            fields.fidJniData);
    if (lpJniStorage == NULL) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    ALOGV("setPeriodicCapture: rate %d, jWaveform %d jFft %d",
            rate,
            jWaveform,
            jFft);

    uint32_t flags = Visualizer::CAPTURE_CALL_JAVA;
    if (jWaveform) flags |= Visualizer::CAPTURE_WAVEFORM;
    if (jFft) flags |= Visualizer::CAPTURE_FFT;
    Visualizer::capture_cbk_t cbk = captureCallback;
    if (!jWaveform && !jFft) cbk = NULL;

    return translateError(lpVisualizer->setCaptureCallBack(cbk,
                                                &lpJniStorage->mCallbackData,
                                                flags,
                                                rate));
}

// ----------------------------------------------------------------------------

// Dalvik VM type signatures
static JNINativeMethod gMethods[] = {
    {"native_init",            "()V",     (void *)android_media_visualizer_native_init},
    {"native_setup",           "(Ljava/lang/Object;I[I)I",
                                          (void *)android_media_visualizer_native_setup},
    {"native_finalize",          "()V",   (void *)android_media_visualizer_native_finalize},
    {"native_release",           "()V",   (void *)android_media_visualizer_native_release},
    {"native_setEnabled",        "(Z)I",  (void *)android_media_visualizer_native_setEnabled},
    {"native_getEnabled",        "()Z",   (void *)android_media_visualizer_native_getEnabled},
    {"getCaptureSizeRange",      "()[I",  (void *)android_media_visualizer_native_getCaptureSizeRange},
    {"getMaxCaptureRate",        "()I",   (void *)android_media_visualizer_native_getMaxCaptureRate},
    {"native_setCaptureSize",    "(I)I",  (void *)android_media_visualizer_native_setCaptureSize},
    {"native_getCaptureSize",    "()I",   (void *)android_media_visualizer_native_getCaptureSize},
    {"native_setScalingMode",    "(I)I",  (void *)android_media_visualizer_native_setScalingMode},
    {"native_getScalingMode",    "()I",   (void *)android_media_visualizer_native_getScalingMode},
    {"native_setMeasurementMode","(I)I",  (void *)android_media_visualizer_native_setMeasurementMode},
    {"native_getMeasurementMode","()I",   (void *)android_media_visualizer_native_getMeasurementMode},
    {"native_getSamplingRate",   "()I",   (void *)android_media_visualizer_native_getSamplingRate},
    {"native_getWaveForm",       "([B)I", (void *)android_media_visualizer_native_getWaveForm},
    {"native_getFft",            "([B)I", (void *)android_media_visualizer_native_getFft},
    {"native_getPeakRms",      "(Landroid/media/audiofx/Visualizer$MeasurementPeakRms;)I",
                                          (void *)android_media_visualizer_native_getPeakRms},
    {"native_setPeriodicCapture","(IZZ)I",(void *)android_media_setPeriodicCapture},
};

// ----------------------------------------------------------------------------

int register_android_media_visualizer(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

