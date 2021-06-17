/*
 * Copyright (C) 2008 The Android Open Source Project
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

//#define LOG_NDEBUG 0

#define LOG_TAG "AudioRecord-JNI"

#include <inttypes.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <utils/Log.h>
#include <media/AudioRecord.h>
#include <media/MicrophoneInfo.h>
#include <vector>

#include <android/content/AttributionSourceState.h>
#include <android_os_Parcel.h>

#include <nativehelper/ScopedUtfChars.h>

#include "android_media_AudioFormat.h"
#include "android_media_AudioErrors.h"
#include "android_media_DeviceCallback.h"
#include "android_media_MediaMetricsJNI.h"
#include "android_media_MicrophoneInfo.h"
#include "android_media_AudioAttributes.h"


// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/AudioRecord";

static jclass gArrayListClass;
static struct {
    jmethodID add;
} gArrayListMethods;

struct audio_record_fields_t {
    // these fields provide access from C++ to the...
    jmethodID postNativeEventInJava; //... event post callback method
    jfieldID  nativeRecorderInJavaObj; // provides access to the C++ AudioRecord object
    jfieldID  nativeCallbackCookie;    // provides access to the AudioRecord callback data
    jfieldID  nativeDeviceCallback;    // provides access to the JNIDeviceCallback instance
};
static audio_record_fields_t     javaAudioRecordFields;
static struct {
    jfieldID  fieldFramePosition;     // AudioTimestamp.framePosition
    jfieldID  fieldNanoTime;          // AudioTimestamp.nanoTime
} javaAudioTimestampFields;

struct audiorecord_callback_cookie {
    jclass      audioRecord_class;
    jobject     audioRecord_ref;
    bool        busy;
    Condition   cond;
};

static Mutex sLock;
static SortedVector <audiorecord_callback_cookie *> sAudioRecordCallBackCookies;

// ----------------------------------------------------------------------------

#define AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT      (-16)
#define AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK  (-17)
#define AUDIORECORD_ERROR_SETUP_INVALIDFORMAT       (-18)
#define AUDIORECORD_ERROR_SETUP_INVALIDSOURCE       (-19)
#define AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED    (-20)

// ----------------------------------------------------------------------------
static void recorderCallback(int event, void* user, void *info) {

    audiorecord_callback_cookie *callbackInfo = (audiorecord_callback_cookie *)user;
    {
        Mutex::Autolock l(sLock);
        if (sAudioRecordCallBackCookies.indexOf(callbackInfo) < 0) {
            return;
        }
        callbackInfo->busy = true;
    }

    switch (event) {
    case AudioRecord::EVENT_MARKER: {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        if (user != NULL && env != NULL) {
            env->CallStaticVoidMethod(
                callbackInfo->audioRecord_class,
                javaAudioRecordFields.postNativeEventInJava,
                callbackInfo->audioRecord_ref, event, 0,0, NULL);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        } break;

    case AudioRecord::EVENT_NEW_POS: {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        if (user != NULL && env != NULL) {
            env->CallStaticVoidMethod(
                callbackInfo->audioRecord_class,
                javaAudioRecordFields.postNativeEventInJava,
                callbackInfo->audioRecord_ref, event, 0,0, NULL);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        } break;
    }

    {
        Mutex::Autolock l(sLock);
        callbackInfo->busy = false;
        callbackInfo->cond.broadcast();
    }
}

static sp<JNIDeviceCallback> getJniDeviceCallback(JNIEnv* env, jobject thiz)
{
    Mutex::Autolock l(sLock);
    JNIDeviceCallback* const cb =
            (JNIDeviceCallback*)env->GetLongField(thiz,
                                                  javaAudioRecordFields.nativeDeviceCallback);
    return sp<JNIDeviceCallback>(cb);
}

static sp<JNIDeviceCallback> setJniDeviceCallback(JNIEnv* env,
                                                  jobject thiz,
                                                  const sp<JNIDeviceCallback>& cb)
{
    Mutex::Autolock l(sLock);
    sp<JNIDeviceCallback> old =
            (JNIDeviceCallback*)env->GetLongField(thiz,
                                                  javaAudioRecordFields.nativeDeviceCallback);
    if (cb.get()) {
        cb->incStrong((void*)setJniDeviceCallback);
    }
    if (old != 0) {
        old->decStrong((void*)setJniDeviceCallback);
    }
    env->SetLongField(thiz, javaAudioRecordFields.nativeDeviceCallback, (jlong)cb.get());
    return old;
}

// ----------------------------------------------------------------------------
static sp<AudioRecord> getAudioRecord(JNIEnv* env, jobject thiz)
{
    Mutex::Autolock l(sLock);
    AudioRecord* const ar =
            (AudioRecord*)env->GetLongField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    return sp<AudioRecord>(ar);
}

static sp<AudioRecord> setAudioRecord(JNIEnv* env, jobject thiz, const sp<AudioRecord>& ar)
{
    Mutex::Autolock l(sLock);
    sp<AudioRecord> old =
            (AudioRecord*)env->GetLongField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    if (ar.get()) {
        ar->incStrong((void*)setAudioRecord);
    }
    if (old != 0) {
        old->decStrong((void*)setAudioRecord);
    }
    env->SetLongField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj, (jlong)ar.get());
    return old;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_setup(JNIEnv *env, jobject thiz, jobject weak_this,
                                            jobject jaa, jintArray jSampleRate, jint channelMask,
                                            jint channelIndexMask, jint audioFormat,
                                            jint buffSizeInBytes, jintArray jSession,
                                            jobject jAttributionSource, jlong nativeRecordInJavaObj,
                                            jint sharedAudioHistoryMs) {
    //ALOGV(">> Entering android_media_AudioRecord_setup");
    //ALOGV("sampleRate=%d, audioFormat=%d, channel mask=%x, buffSizeInBytes=%d "
    //     "nativeRecordInJavaObj=0x%llX",
    //     sampleRateInHertz, audioFormat, channelMask, buffSizeInBytes, nativeRecordInJavaObj);
    audio_channel_mask_t localChanMask = inChannelMaskToNative(channelMask);

    if (jSession == NULL) {
        ALOGE("Error creating AudioRecord: invalid session ID pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }

    jint* nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioRecord: Error retrieving session id pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }
    audio_session_t sessionId = (audio_session_t) nSession[0];
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    sp<AudioRecord> lpRecorder = 0;
    audiorecord_callback_cookie *lpCallbackData = NULL;

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find %s when setting up callback.", kClassPathName);
        return (jint) AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED;
    }

    // if we pass in an existing *Native* AudioRecord, we don't need to create/initialize one.
    if (nativeRecordInJavaObj == 0) {
        if (jaa == 0) {
            ALOGE("Error creating AudioRecord: invalid audio attributes");
            return (jint) AUDIO_JAVA_ERROR;
        }

        if (jSampleRate == 0) {
            ALOGE("Error creating AudioRecord: invalid sample rates");
            return (jint) AUDIO_JAVA_ERROR;
        }
        jint elements[1];
        env->GetIntArrayRegion(jSampleRate, 0, 1, elements);
        int sampleRateInHertz = elements[0];

        // channel index mask takes priority over channel position masks.
        if (channelIndexMask) {
            // Java channel index masks need the representation bits set.
            localChanMask = audio_channel_mask_from_representation_and_bits(
                    AUDIO_CHANNEL_REPRESENTATION_INDEX,
                    channelIndexMask);
        }
        // Java channel position masks map directly to the native definition

        if (!audio_is_input_channel(localChanMask)) {
            ALOGE("Error creating AudioRecord: channel mask %#x is not valid.", localChanMask);
            return (jint) AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK;
        }
        uint32_t channelCount = audio_channel_count_from_in_mask(localChanMask);

        // compare the format against the Java constants
        audio_format_t format = audioFormatToNative(audioFormat);
        if (format == AUDIO_FORMAT_INVALID) {
            ALOGE("Error creating AudioRecord: unsupported audio format %d.", audioFormat);
            return (jint) AUDIORECORD_ERROR_SETUP_INVALIDFORMAT;
        }

        size_t bytesPerSample = audio_bytes_per_sample(format);

        if (buffSizeInBytes == 0) {
            ALOGE("Error creating AudioRecord: frameCount is 0.");
            return (jint) AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT;
        }
        size_t frameSize = channelCount * bytesPerSample;
        size_t frameCount = buffSizeInBytes / frameSize;

        // create an uninitialized AudioRecord object
        Parcel* parcel = parcelForJavaObject(env, jAttributionSource);
        android::content::AttributionSourceState attributionSource;
        attributionSource.readFromParcel(parcel);

        lpRecorder = new AudioRecord(attributionSource);

        // read the AudioAttributes values
        auto paa = JNIAudioAttributeHelper::makeUnique();
        jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
        if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        ALOGV("AudioRecord_setup for source=%d tags=%s flags=%08x", paa->source, paa->tags, paa->flags);

        audio_input_flags_t flags = AUDIO_INPUT_FLAG_NONE;
        if (paa->flags & AUDIO_FLAG_HW_HOTWORD) {
            flags = AUDIO_INPUT_FLAG_HW_HOTWORD;
        }
        // create the callback information:
        // this data will be passed with every AudioRecord callback
        lpCallbackData = new audiorecord_callback_cookie;
        lpCallbackData->audioRecord_class = (jclass)env->NewGlobalRef(clazz);
        // we use a weak reference so the AudioRecord object can be garbage collected.
        lpCallbackData->audioRecord_ref = env->NewGlobalRef(weak_this);
        lpCallbackData->busy = false;

        const status_t status =
                lpRecorder->set(paa->source, sampleRateInHertz,
                                format, // word length, PCM
                                localChanMask, frameCount,
                                recorderCallback, // callback_t
                                lpCallbackData,   // void* user
                                0,                // notificationFrames,
                                true,             // threadCanCallJava
                                sessionId, AudioRecord::TRANSFER_DEFAULT, flags, -1,
                                -1, // default uid, pid
                                paa.get(), AUDIO_PORT_HANDLE_NONE, MIC_DIRECTION_UNSPECIFIED,
                                MIC_FIELD_DIMENSION_DEFAULT, sharedAudioHistoryMs);

        if (status != NO_ERROR) {
            ALOGE("Error creating AudioRecord instance: initialization check failed with status %d.",
                    status);
            goto native_init_failure;
        }
        // Set caller name so it can be logged in destructor.
        // MediaMetricsConstants.h: AMEDIAMETRICS_PROP_CALLERNAME_VALUE_JAVA
        lpRecorder->setCallerName("java");
    } else { // end if nativeRecordInJavaObj == 0)
        lpRecorder = (AudioRecord*)nativeRecordInJavaObj;
        // TODO: We need to find out which members of the Java AudioRecord might need to be
        // initialized from the Native AudioRecord
        // these are directly returned from getters:
        //  mSampleRate
        //  mRecordSource
        //  mAudioFormat
        //  mChannelMask
        //  mChannelCount
        //  mState (?)
        //  mRecordingState (?)
        //  mPreferredDevice

        // create the callback information:
        // this data will be passed with every AudioRecord callback
        lpCallbackData = new audiorecord_callback_cookie;
        lpCallbackData->audioRecord_class = (jclass)env->NewGlobalRef(clazz);
        // we use a weak reference so the AudioRecord object can be garbage collected.
        lpCallbackData->audioRecord_ref = env->NewGlobalRef(weak_this);
        lpCallbackData->busy = false;
    }

    nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioRecord: Error retrieving session id pointer");
        goto native_init_failure;
    }
    // read the audio session ID back from AudioRecord in case a new session was created during set()
    nSession[0] = lpRecorder->getSessionId();
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    {
        const jint elements[1] = { (jint) lpRecorder->getSampleRate() };
        env->SetIntArrayRegion(jSampleRate, 0, 1, elements);
    }

    {   // scope for the lock
        Mutex::Autolock l(sLock);
        sAudioRecordCallBackCookies.add(lpCallbackData);
    }
    // save our newly created C++ AudioRecord in the "nativeRecorderInJavaObj" field
    // of the Java object
    setAudioRecord(env, thiz, lpRecorder);

    // save our newly created callback information in the "nativeCallbackCookie" field
    // of the Java object (in mNativeCallbackCookie) so we can free the memory in finalize()
    env->SetLongField(thiz, javaAudioRecordFields.nativeCallbackCookie, (jlong)lpCallbackData);

    return (jint) AUDIO_JAVA_SUCCESS;

    // failure:
native_init_failure:
    env->DeleteGlobalRef(lpCallbackData->audioRecord_class);
    env->DeleteGlobalRef(lpCallbackData->audioRecord_ref);
    delete lpCallbackData;
    env->SetLongField(thiz, javaAudioRecordFields.nativeCallbackCookie, 0);

    // lpRecorder goes out of scope, so reference count drops to zero
    return (jint) AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED;
}

// ----------------------------------------------------------------------------
static jint
android_media_AudioRecord_start(JNIEnv *env, jobject thiz, jint event, jint triggerSession)
{
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return (jint) AUDIO_JAVA_ERROR;
    }

    return nativeToJavaStatus(
            lpRecorder->start((AudioSystem::sync_event_t)event, (audio_session_t) triggerSession));
}


// ----------------------------------------------------------------------------
static void
android_media_AudioRecord_stop(JNIEnv *env, jobject thiz)
{
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    lpRecorder->stop();
    //ALOGV("Called lpRecorder->stop()");
}


// ----------------------------------------------------------------------------

#define CALLBACK_COND_WAIT_TIMEOUT_MS 1000
static void android_media_AudioRecord_release(JNIEnv *env,  jobject thiz) {
    sp<AudioRecord> lpRecorder = setAudioRecord(env, thiz, 0);
    if (lpRecorder == NULL) {
        return;
    }
    ALOGV("About to delete lpRecorder: %p", lpRecorder.get());
    lpRecorder->stop();

    audiorecord_callback_cookie *lpCookie = (audiorecord_callback_cookie *)env->GetLongField(
        thiz, javaAudioRecordFields.nativeCallbackCookie);

    // reset the native resources in the Java object so any attempt to access
    // them after a call to release fails.
    env->SetLongField(thiz, javaAudioRecordFields.nativeCallbackCookie, 0);

    // delete the callback information
    if (lpCookie) {
        Mutex::Autolock l(sLock);
        ALOGV("deleting lpCookie: %p", lpCookie);
        while (lpCookie->busy) {
            if (lpCookie->cond.waitRelative(sLock,
                                            milliseconds(CALLBACK_COND_WAIT_TIMEOUT_MS)) !=
                                                    NO_ERROR) {
                break;
            }
        }
        sAudioRecordCallBackCookies.remove(lpCookie);
        env->DeleteGlobalRef(lpCookie->audioRecord_class);
        env->DeleteGlobalRef(lpCookie->audioRecord_ref);
        delete lpCookie;
    }
}


// ----------------------------------------------------------------------------
static void android_media_AudioRecord_finalize(JNIEnv *env,  jobject thiz) {
    android_media_AudioRecord_release(env, thiz);
}

// overloaded JNI array helper functions
static inline
jbyte *envGetArrayElements(JNIEnv *env, jbyteArray array, jboolean *isCopy) {
    return env->GetByteArrayElements(array, isCopy);
}

static inline
void envReleaseArrayElements(JNIEnv *env, jbyteArray array, jbyte *elems, jint mode) {
    env->ReleaseByteArrayElements(array, elems, mode);
}

static inline
jshort *envGetArrayElements(JNIEnv *env, jshortArray array, jboolean *isCopy) {
    return env->GetShortArrayElements(array, isCopy);
}

static inline
void envReleaseArrayElements(JNIEnv *env, jshortArray array, jshort *elems, jint mode) {
    env->ReleaseShortArrayElements(array, elems, mode);
}

static inline
jfloat *envGetArrayElements(JNIEnv *env, jfloatArray array, jboolean *isCopy) {
    return env->GetFloatArrayElements(array, isCopy);
}

static inline
void envReleaseArrayElements(JNIEnv *env, jfloatArray array, jfloat *elems, jint mode) {
    env->ReleaseFloatArrayElements(array, elems, mode);
}

static inline
jint interpretReadSizeError(ssize_t readSize) {
    if (readSize == WOULD_BLOCK) {
        return (jint)0;
    } else if (readSize == NO_INIT) {
        return AUDIO_JAVA_DEAD_OBJECT;
    } else {
        ALOGE("Error %zd during AudioRecord native read", readSize);
        return nativeToJavaStatus(readSize);
    }
}

template <typename T>
static jint android_media_AudioRecord_readInArray(JNIEnv *env,  jobject thiz,
                                                  T javaAudioData,
                                                  jint offsetInSamples, jint sizeInSamples,
                                                  jboolean isReadBlocking) {
    // get the audio recorder from which we'll read new audio samples
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        ALOGE("Unable to retrieve AudioRecord object");
        return (jint)AUDIO_JAVA_INVALID_OPERATION;
    }

    if (javaAudioData == NULL) {
        ALOGE("Invalid Java array to store recorded audio");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    // NOTE: We may use GetPrimitiveArrayCritical() when the JNI implementation changes in such
    // a way that it becomes much more efficient. When doing so, we will have to prevent the
    // AudioSystem callback to be called while in critical section (in case of media server
    // process crash for instance)

    // get the pointer to where we'll record the audio
    auto *recordBuff = envGetArrayElements(env, javaAudioData, NULL);
    if (recordBuff == NULL) {
        ALOGE("Error retrieving destination for recorded audio data");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    // read the new audio data from the native AudioRecord object
    const size_t sizeInBytes = sizeInSamples * sizeof(*recordBuff);
    ssize_t readSize = lpRecorder->read(
            recordBuff + offsetInSamples, sizeInBytes, isReadBlocking == JNI_TRUE /* blocking */);

    envReleaseArrayElements(env, javaAudioData, recordBuff, 0);

    if (readSize < 0) {
        return interpretReadSizeError(readSize);
    }
    return (jint)(readSize / sizeof(*recordBuff));
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_readInDirectBuffer(JNIEnv *env,  jobject thiz,
                                                         jobject jBuffer, jint sizeInBytes,
                                                         jboolean isReadBlocking) {
    // get the audio recorder from which we'll read new audio samples
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder==NULL)
        return (jint)AUDIO_JAVA_INVALID_OPERATION;

    // direct buffer and direct access supported?
    long capacity = env->GetDirectBufferCapacity(jBuffer);
    if (capacity == -1) {
        // buffer direct access is not supported
        ALOGE("Buffer direct access is not supported, can't record");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    //ALOGV("capacity = %ld", capacity);
    jbyte* nativeFromJavaBuf = (jbyte*) env->GetDirectBufferAddress(jBuffer);
    if (nativeFromJavaBuf==NULL) {
        ALOGE("Buffer direct access is not supported, can't record");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    // read new data from the recorder
    ssize_t readSize = lpRecorder->read(nativeFromJavaBuf,
                                        capacity < sizeInBytes ? capacity : sizeInBytes,
                                        isReadBlocking == JNI_TRUE /* blocking */);
    if (readSize < 0) {
        return interpretReadSizeError(readSize);
    }
    return (jint)readSize;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_buffer_size_in_frames(JNIEnv *env,  jobject thiz) {
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for frameCount()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    return lpRecorder->frameCount();
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_set_marker_pos(JNIEnv *env,  jobject thiz,
        jint markerPos) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for setMarkerPosition()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus( lpRecorder->setMarkerPosition(markerPos) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_marker_pos(JNIEnv *env,  jobject thiz) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    uint32_t markerPos = 0;

    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for getMarkerPosition()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    lpRecorder->getMarkerPosition(&markerPos);
    return (jint)markerPos;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_set_pos_update_period(JNIEnv *env,  jobject thiz,
        jint period) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);

    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for setPositionUpdatePeriod()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus( lpRecorder->setPositionUpdatePeriod(period) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_pos_update_period(JNIEnv *env,  jobject thiz) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    uint32_t period = 0;

    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for getPositionUpdatePeriod()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    lpRecorder->getPositionUpdatePeriod(&period);
    return (jint)period;
}


// ----------------------------------------------------------------------------
// returns the minimum required size for the successful creation of an AudioRecord instance.
// returns 0 if the parameter combination is not supported.
// return -1 if there was an error querying the buffer size.
static jint android_media_AudioRecord_get_min_buff_size(JNIEnv *env,  jobject thiz,
    jint sampleRateInHertz, jint channelCount, jint audioFormat) {

    ALOGV(">> android_media_AudioRecord_get_min_buff_size(%d, %d, %d)",
          sampleRateInHertz, channelCount, audioFormat);

    size_t frameCount = 0;
    audio_format_t format = audioFormatToNative(audioFormat);
    status_t result = AudioRecord::getMinFrameCount(&frameCount,
            sampleRateInHertz,
            format,
            audio_channel_in_mask_from_count(channelCount));

    if (result == BAD_VALUE) {
        return 0;
    }
    if (result != NO_ERROR) {
        return -1;
    }
    return frameCount * channelCount * audio_bytes_per_sample(format);
}

static jboolean android_media_AudioRecord_setInputDevice(
        JNIEnv *env,  jobject thiz, jint device_id) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == 0) {
        return false;
    }
    return lpRecorder->setInputDevice(device_id) == NO_ERROR;
}

static jint android_media_AudioRecord_getRoutedDeviceId(
                JNIEnv *env,  jobject thiz) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == 0) {
        return 0;
    }
    return (jint)lpRecorder->getRoutedDeviceId();
}

static void android_media_AudioRecord_enableDeviceCallback(
                JNIEnv *env,  jobject thiz) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == 0) {
        return;
    }
    sp<JNIDeviceCallback> cb = getJniDeviceCallback(env, thiz);
    if (cb != 0) {
        return;
    }
    audiorecord_callback_cookie *cookie =
            (audiorecord_callback_cookie *)env->GetLongField(thiz,
                                                     javaAudioRecordFields.nativeCallbackCookie);
    if (cookie == NULL) {
        return;
    }

    cb = new JNIDeviceCallback(env, thiz, cookie->audioRecord_ref,
                               javaAudioRecordFields.postNativeEventInJava);
    status_t status = lpRecorder->addAudioDeviceCallback(cb);
    if (status == NO_ERROR) {
        setJniDeviceCallback(env, thiz, cb);
    }
}

static void android_media_AudioRecord_disableDeviceCallback(
                JNIEnv *env,  jobject thiz) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == 0) {
        return;
    }
    sp<JNIDeviceCallback> cb = setJniDeviceCallback(env, thiz, 0);
    if (cb != 0) {
        lpRecorder->removeAudioDeviceCallback(cb);
    }
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_timestamp(JNIEnv *env, jobject thiz,
        jobject timestamp, jint timebase) {
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);

    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for getTimestamp()");
        return (jint)AUDIO_JAVA_ERROR;
    }

    ExtendedTimestamp ts;
    jint status = nativeToJavaStatus(lpRecorder->getTimestamp(&ts));

    if (status == AUDIO_JAVA_SUCCESS) {
        // set the data
        int64_t position, time;

        status = nativeToJavaStatus(ts.getBestTimestamp(&position, &time, timebase));
        if (status == AUDIO_JAVA_SUCCESS) {
            env->SetLongField(
                    timestamp, javaAudioTimestampFields.fieldFramePosition, position);
            env->SetLongField(
                    timestamp, javaAudioTimestampFields.fieldNanoTime, time);
        }
    }
    return status;
}

// ----------------------------------------------------------------------------
static jobject
android_media_AudioRecord_native_getMetrics(JNIEnv *env, jobject thiz)
{
    ALOGV("android_media_AudioRecord_native_getMetrics");

    sp<AudioRecord> lpRecord = getAudioRecord(env, thiz);

    if (lpRecord == NULL) {
        ALOGE("Unable to retrieve AudioRecord pointer for getMetrics()");
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return (jobject) NULL;
    }

    // get what we have for the metrics from the record session
    mediametrics::Item *item = NULL;

    status_t err = lpRecord->getMetrics(item);
    if (err != OK) {
        ALOGE("getMetrics failed");
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return (jobject) NULL;
    }

    jobject mybundle = MediaMetricsJNI::writeMetricsToBundle(env, item, NULL /* mybundle */);

    // housekeeping
    delete item;
    item = NULL;

    return mybundle;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_active_microphones(JNIEnv *env,
        jobject thiz, jobject jActiveMicrophones) {
    if (jActiveMicrophones == NULL) {
        ALOGE("jActiveMicrophones is null");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jActiveMicrophones, gArrayListClass)) {
        ALOGE("getActiveMicrophones not an arraylist");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for getActiveMicrophones()");
        return (jint)AUDIO_JAVA_ERROR;
    }

    jint jStatus = AUDIO_JAVA_SUCCESS;
    std::vector<media::MicrophoneInfo> activeMicrophones;
    status_t status = lpRecorder->getActiveMicrophones(&activeMicrophones);
    if (status != NO_ERROR) {
        ALOGE_IF(status != NO_ERROR, "AudioRecord::getActiveMicrophones error %d", status);
        jStatus = nativeToJavaStatus(status);
        return jStatus;
    }

    for (size_t i = 0; i < activeMicrophones.size(); i++) {
        jobject jMicrophoneInfo;
        jStatus = convertMicrophoneInfoFromNative(env, &jMicrophoneInfo, &activeMicrophones[i]);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        env->CallBooleanMethod(jActiveMicrophones, gArrayListMethods.add, jMicrophoneInfo);
        env->DeleteLocalRef(jMicrophoneInfo);
    }
    return jStatus;
}

static int android_media_AudioRecord_set_preferred_microphone_direction(
                                JNIEnv *env, jobject thiz, jint direction) {
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for setPreferredMicrophoneDirection()");
        return (jint)AUDIO_JAVA_ERROR;
    }

    jint jStatus = AUDIO_JAVA_SUCCESS;
    status_t status = lpRecorder->setPreferredMicrophoneDirection(
                            static_cast<audio_microphone_direction_t>(direction));
    if (status != NO_ERROR) {
        jStatus = nativeToJavaStatus(status);
    }

    return jStatus;
}

static int android_media_AudioRecord_set_preferred_microphone_field_dimension(
                                JNIEnv *env, jobject thiz, jfloat zoom) {
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for setPreferredMicrophoneFieldDimension()");
        return (jint)AUDIO_JAVA_ERROR;
    }

    jint jStatus = AUDIO_JAVA_SUCCESS;
    status_t status = lpRecorder->setPreferredMicrophoneFieldDimension(zoom);
    if (status != NO_ERROR) {
        jStatus = nativeToJavaStatus(status);
    }

    return jStatus;
}

static void android_media_AudioRecord_setLogSessionId(JNIEnv *env, jobject thiz,
                                                      jstring jlogSessionId) {
    sp<AudioRecord> record = getAudioRecord(env, thiz);
    if (record == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Unable to retrieve AudioRecord pointer for setLogSessionId()");
    }
    if (jlogSessionId == nullptr) {
        ALOGV("%s: logSessionId nullptr", __func__);
        record->setLogSessionId(nullptr);
        return;
    }
    ScopedUtfChars logSessionId(env, jlogSessionId);
    ALOGV("%s: logSessionId '%s'", __func__, logSessionId.c_str());
    record->setLogSessionId(logSessionId.c_str());
}

static jint android_media_AudioRecord_shareAudioHistory(JNIEnv *env, jobject thiz,
                                                        jstring jSharedPackageName,
                                                        jlong jSharedStartMs) {
    sp<AudioRecord> record = getAudioRecord(env, thiz);
    if (record == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Unable to retrieve AudioRecord pointer for setLogSessionId()");
    }
    if (jSharedPackageName == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "package name cannot be null");
    }
    ScopedUtfChars nSharedPackageName(env, jSharedPackageName);
    ALOGV("%s: nSharedPackageName '%s'", __func__, nSharedPackageName.c_str());
    return nativeToJavaStatus(record->shareAudioHistory(nSharedPackageName.c_str(),
                                                        static_cast<int64_t>(jSharedStartMs)));
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_port_id(JNIEnv *env,  jobject thiz) {
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Unable to retrieve AudioRecord pointer for getId()");
        return (jint)AUDIO_PORT_HANDLE_NONE;
    }
    return (jint)lpRecorder->getPortId();
}


// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static const JNINativeMethod gMethods[] = {
        // name,               signature,  funcPtr
        {"native_start", "(II)I", (void *)android_media_AudioRecord_start},
        {"native_stop", "()V", (void *)android_media_AudioRecord_stop},
        {"native_setup",
         "(Ljava/lang/Object;Ljava/lang/Object;[IIIII[ILandroid/os/Parcel;JI)I",
         (void *)android_media_AudioRecord_setup},
        {"native_finalize", "()V", (void *)android_media_AudioRecord_finalize},
        {"native_release", "()V", (void *)android_media_AudioRecord_release},
        {"native_read_in_byte_array", "([BIIZ)I",
         (void *)android_media_AudioRecord_readInArray<jbyteArray>},
        {"native_read_in_short_array", "([SIIZ)I",
         (void *)android_media_AudioRecord_readInArray<jshortArray>},
        {"native_read_in_float_array", "([FIIZ)I",
         (void *)android_media_AudioRecord_readInArray<jfloatArray>},
        {"native_read_in_direct_buffer", "(Ljava/lang/Object;IZ)I",
         (void *)android_media_AudioRecord_readInDirectBuffer},
        {"native_get_buffer_size_in_frames", "()I",
         (void *)android_media_AudioRecord_get_buffer_size_in_frames},
        {"native_set_marker_pos", "(I)I", (void *)android_media_AudioRecord_set_marker_pos},
        {"native_get_marker_pos", "()I", (void *)android_media_AudioRecord_get_marker_pos},
        {"native_set_pos_update_period", "(I)I",
         (void *)android_media_AudioRecord_set_pos_update_period},
        {"native_get_pos_update_period", "()I",
         (void *)android_media_AudioRecord_get_pos_update_period},
        {"native_get_min_buff_size", "(III)I", (void *)android_media_AudioRecord_get_min_buff_size},
        {"native_getMetrics", "()Landroid/os/PersistableBundle;",
         (void *)android_media_AudioRecord_native_getMetrics},
        {"native_setInputDevice", "(I)Z", (void *)android_media_AudioRecord_setInputDevice},
        {"native_getRoutedDeviceId", "()I", (void *)android_media_AudioRecord_getRoutedDeviceId},
        {"native_enableDeviceCallback", "()V",
         (void *)android_media_AudioRecord_enableDeviceCallback},
        {"native_disableDeviceCallback", "()V",
         (void *)android_media_AudioRecord_disableDeviceCallback},
        {"native_get_timestamp", "(Landroid/media/AudioTimestamp;I)I",
         (void *)android_media_AudioRecord_get_timestamp},
        {"native_get_active_microphones", "(Ljava/util/ArrayList;)I",
         (void *)android_media_AudioRecord_get_active_microphones},
        {"native_getPortId", "()I", (void *)android_media_AudioRecord_get_port_id},
        {"native_set_preferred_microphone_direction", "(I)I",
         (void *)android_media_AudioRecord_set_preferred_microphone_direction},
        {"native_set_preferred_microphone_field_dimension", "(F)I",
         (void *)android_media_AudioRecord_set_preferred_microphone_field_dimension},
        {"native_setLogSessionId", "(Ljava/lang/String;)V",
         (void *)android_media_AudioRecord_setLogSessionId},
        {"native_shareAudioHistory", "(Ljava/lang/String;J)I",
         (void *)android_media_AudioRecord_shareAudioHistory},
};

// field names found in android/media/AudioRecord.java
#define JAVA_POSTEVENT_CALLBACK_NAME  "postEventFromNative"
#define JAVA_NATIVERECORDERINJAVAOBJ_FIELD_NAME  "mNativeRecorderInJavaObj"
#define JAVA_NATIVECALLBACKINFO_FIELD_NAME       "mNativeCallbackCookie"
#define JAVA_NATIVEDEVICECALLBACK_FIELD_NAME       "mNativeDeviceCallback"

// ----------------------------------------------------------------------------
int register_android_media_AudioRecord(JNIEnv *env)
{
    javaAudioRecordFields.postNativeEventInJava = NULL;
    javaAudioRecordFields.nativeRecorderInJavaObj = NULL;
    javaAudioRecordFields.nativeCallbackCookie = NULL;
    javaAudioRecordFields.nativeDeviceCallback = NULL;


    // Get the AudioRecord class
    jclass audioRecordClass = FindClassOrDie(env, kClassPathName);
    // Get the postEvent method
    javaAudioRecordFields.postNativeEventInJava = GetStaticMethodIDOrDie(env,
            audioRecordClass, JAVA_POSTEVENT_CALLBACK_NAME,
            "(Ljava/lang/Object;IIILjava/lang/Object;)V");

    // Get the variables
    //    mNativeRecorderInJavaObj
    javaAudioRecordFields.nativeRecorderInJavaObj = GetFieldIDOrDie(env,
            audioRecordClass, JAVA_NATIVERECORDERINJAVAOBJ_FIELD_NAME, "J");
    //     mNativeCallbackCookie
    javaAudioRecordFields.nativeCallbackCookie = GetFieldIDOrDie(env,
            audioRecordClass, JAVA_NATIVECALLBACKINFO_FIELD_NAME, "J");

    javaAudioRecordFields.nativeDeviceCallback = GetFieldIDOrDie(env,
            audioRecordClass, JAVA_NATIVEDEVICECALLBACK_FIELD_NAME, "J");

    // Get the RecordTimestamp class and fields
    jclass audioTimestampClass = FindClassOrDie(env, "android/media/AudioTimestamp");
    javaAudioTimestampFields.fieldFramePosition =
            GetFieldIDOrDie(env, audioTimestampClass, "framePosition", "J");
    javaAudioTimestampFields.fieldNanoTime =
            GetFieldIDOrDie(env, audioTimestampClass, "nanoTime", "J");

    jclass arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gArrayListClass = MakeGlobalRefOrDie(env, arrayListClass);
    gArrayListMethods.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

// ----------------------------------------------------------------------------
