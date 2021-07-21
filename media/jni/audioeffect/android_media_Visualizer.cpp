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
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/threads.h>
#include "Visualizer.h"

#include <nativehelper/ScopedUtfChars.h>

#include <android/content/AttributionSourceState.h>
#include <android_os_Parcel.h>

using namespace android;

using content::AttributionSourceState;

#define VISUALIZER_SUCCESS                      0
#define VISUALIZER_ERROR                       (-1)
#define VISUALIZER_ERROR_ALREADY_EXISTS        (-2)
#define VISUALIZER_ERROR_NO_INIT               (-3)
#define VISUALIZER_ERROR_BAD_VALUE             (-4)
#define VISUALIZER_ERROR_INVALID_OPERATION     (-5)
#define VISUALIZER_ERROR_NO_MEMORY             (-6)
#define VISUALIZER_ERROR_DEAD_OBJECT           (-7)

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
class VisualizerJniStorage {
    public:
        visualizer_callback_cookie mCallbackData;

    VisualizerJniStorage() {
    }

    ~VisualizerJniStorage() {
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

static Mutex sLock;

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
                jArray);
        }
    }

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// ----------------------------------------------------------------------------

static sp<Visualizer> getVisualizer(JNIEnv* env, jobject thiz)
{
    Mutex::Autolock l(sLock);
    Visualizer* const v =
            (Visualizer*)env->GetLongField(thiz, fields.fidNativeVisualizer);
    return sp<Visualizer>(v);
}

static sp<Visualizer> setVisualizer(JNIEnv* env, jobject thiz,
                                    const sp<Visualizer>& v)
{
    Mutex::Autolock l(sLock);
    sp<Visualizer> old =
            (Visualizer*)env->GetLongField(thiz, fields.fidNativeVisualizer);
    if (v.get()) {
        v->incStrong((void*)setVisualizer);
    }
    if (old != 0) {
        old->decStrong((void*)setVisualizer);
    }
    env->SetLongField(thiz, fields.fidNativeVisualizer, (jlong)v.get());
    return old;
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
            "postEventFromNative", "(Ljava/lang/Object;II[B)V");
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
        VisualizerJniStorage* lpJniStorage = (VisualizerJniStorage*)user;
        visualizer_callback_cookie* callbackInfo = &lpJniStorage->mCallbackData;
        JNIEnv *env = AndroidRuntime::getJNIEnv();

        env->CallStaticVoidMethod(
            callbackInfo->visualizer_class,
            fields.midPostNativeEvent,
            callbackInfo->visualizer_ref,
            NATIVE_EVENT_SERVER_DIED,
            0, NULL);
    }
}

static jint
android_media_visualizer_native_setup(JNIEnv *env, jobject thiz, jobject weak_this,
        jint sessionId, jintArray jId, jobject jAttributionSource)
{
    ALOGV("android_media_visualizer_native_setup");
    VisualizerJniStorage* lpJniStorage = NULL;
    int lStatus = VISUALIZER_ERROR_NO_MEMORY;
    sp<Visualizer> lpVisualizer;
    jint* nId = NULL;
    AttributionSourceState attributionSource;
    Parcel* parcel = nullptr;

    setVisualizer(env, thiz, 0);

    lpJniStorage = new VisualizerJniStorage();
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
    parcel = parcelForJavaObject(env, jAttributionSource);
    attributionSource.readFromParcel(parcel);
    lpVisualizer = sp<Visualizer>::make(attributionSource);
    if (lpVisualizer == 0) {
        ALOGE("Error creating Visualizer");
        goto setup_failure;
    }
    lpVisualizer->set(0,
                      android_media_visualizer_effect_callback,
                      lpJniStorage,
                      (audio_session_t) sessionId);

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

    setVisualizer(env, thiz, lpVisualizer);

    env->SetLongField(thiz, fields.fidJniData, (jlong)lpJniStorage);

    return VISUALIZER_SUCCESS;

    // failures:
setup_failure:

    if (nId != NULL) {
        env->ReleasePrimitiveArrayCritical(jId, nId, 0);
    }

    if (lpJniStorage) {
        env->DeleteGlobalRef(lpJniStorage->mCallbackData.visualizer_class);
        env->DeleteGlobalRef(lpJniStorage->mCallbackData.visualizer_ref);
        delete lpJniStorage;
    }
    env->SetLongField(thiz, fields.fidJniData, 0);

    return (jint) lStatus;
}

// ----------------------------------------------------------------------------
static void android_media_visualizer_native_release(JNIEnv *env,  jobject thiz) {
    { //limit scope so that lpVisualizer is deleted before JNI storage data.
        sp<Visualizer> lpVisualizer = setVisualizer(env, thiz, 0);
        if (lpVisualizer == 0) {
            return;
        }
        lpVisualizer->release();
    }
    // delete the JNI data
    VisualizerJniStorage* lpJniStorage =
        (VisualizerJniStorage *)env->GetLongField(thiz, fields.fidJniData);

    // reset the native resources in the Java object so any attempt to access
    // them after a call to release fails.
    env->SetLongField(thiz, fields.fidJniData, 0);

    if (lpJniStorage) {
        ALOGV("deleting pJniStorage: %p\n", lpJniStorage);
        env->DeleteGlobalRef(lpJniStorage->mCallbackData.visualizer_class);
        env->DeleteGlobalRef(lpJniStorage->mCallbackData.visualizer_ref);
        delete lpJniStorage;
    }
}

static void android_media_visualizer_native_finalize(JNIEnv *env,  jobject thiz) {
    ALOGV("android_media_visualizer_native_finalize jobject: %p\n", thiz);
    android_media_visualizer_native_release(env, thiz);
}

// ----------------------------------------------------------------------------
static jint
android_media_visualizer_native_setEnabled(JNIEnv *env, jobject thiz, jboolean enabled)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    jint retVal = translateError(lpVisualizer->setEnabled(enabled));

    if (!enabled) {
        VisualizerJniStorage* lpJniStorage = (VisualizerJniStorage *)env->GetLongField(
            thiz, fields.fidJniData);

        if (NULL != lpJniStorage)
            lpJniStorage->mCallbackData.cleanupBuffers();
    }

    return retVal;
}

static jboolean
android_media_visualizer_native_getEnabled(JNIEnv *env, jobject thiz)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return JNI_FALSE;
    }

    if (lpVisualizer->getEnabled()) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static jintArray
android_media_visualizer_native_getCaptureSizeRange(JNIEnv *env, jobject /* thiz */)
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
android_media_visualizer_native_getMaxCaptureRate(JNIEnv* /* env */, jobject /* thiz */)
{
    return (jint) Visualizer::getMaxCaptureRate();
}

static jint
android_media_visualizer_native_setCaptureSize(JNIEnv *env, jobject thiz, jint size)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    return translateError(lpVisualizer->setCaptureSize(size));
}

static jint
android_media_visualizer_native_getCaptureSize(JNIEnv *env, jobject thiz)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return -1;
    }
    return (jint) lpVisualizer->getCaptureSize();
}

static jint
android_media_visualizer_native_setScalingMode(JNIEnv *env, jobject thiz, jint mode)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return VISUALIZER_ERROR_NO_INIT;
    }

    return translateError(lpVisualizer->setScalingMode(mode));
}

static jint
android_media_visualizer_native_getScalingMode(JNIEnv *env, jobject thiz)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return -1;
    }
    return (jint)lpVisualizer->getScalingMode();
}

static jint
android_media_visualizer_native_setMeasurementMode(JNIEnv *env, jobject thiz, jint mode)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return VISUALIZER_ERROR_NO_INIT;
    }
    return translateError(lpVisualizer->setMeasurementMode(mode));
}

static jint
android_media_visualizer_native_getMeasurementMode(JNIEnv *env, jobject thiz)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return MEASUREMENT_MODE_NONE;
    }
    return lpVisualizer->getMeasurementMode();
}

static jint
android_media_visualizer_native_getSamplingRate(JNIEnv *env, jobject thiz)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return -1;
    }
    return (jint) lpVisualizer->getSamplingRate();
}

static jint
android_media_visualizer_native_getWaveForm(JNIEnv *env, jobject thiz, jbyteArray jWaveform)
{
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
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
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
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
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
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
    sp<Visualizer> lpVisualizer = getVisualizer(env, thiz);
    if (lpVisualizer == 0) {
        return VISUALIZER_ERROR_NO_INIT;
    }
    VisualizerJniStorage* lpJniStorage = (VisualizerJniStorage *)env->GetLongField(thiz,
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
static const JNINativeMethod gMethods[] = {
    {"native_init",            "()V",     (void *)android_media_visualizer_native_init},
    {"native_setup",           "(Ljava/lang/Object;I[ILandroid/os/Parcel;)I",
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

