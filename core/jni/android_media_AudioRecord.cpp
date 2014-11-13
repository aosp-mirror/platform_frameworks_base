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
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <media/AudioRecord.h>

#include "android_media_AudioFormat.h"
#include "android_media_AudioErrors.h"

// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/AudioRecord";
static const char* const kAudioAttributesClassPathName = "android/media/AudioAttributes";

struct audio_record_fields_t {
    // these fields provide access from C++ to the...
    jmethodID postNativeEventInJava; //... event post callback method
    jfieldID  nativeRecorderInJavaObj; // provides access to the C++ AudioRecord object
    jfieldID  nativeCallbackCookie;    // provides access to the AudioRecord callback data
};
struct audio_attributes_fields_t {
    jfieldID  fieldRecSource;    // AudioAttributes.mSource
    jfieldID  fieldFlags;        // AudioAttributes.mFlags
    jfieldID  fieldFormattedTags;// AudioAttributes.mFormattedTags
};
static audio_attributes_fields_t javaAudioAttrFields;
static audio_record_fields_t     javaAudioRecordFields;

struct audiorecord_callback_cookie {
    jclass      audioRecord_class;
    jobject     audioRecord_ref;
    bool        busy;
    Condition   cond;
};

static Mutex sLock;
static SortedVector <audiorecord_callback_cookie *> sAudioRecordCallBackCookies;

// ----------------------------------------------------------------------------

#define AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT      -16
#define AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK -17
#define AUDIORECORD_ERROR_SETUP_INVALIDFORMAT       -18
#define AUDIORECORD_ERROR_SETUP_INVALIDSOURCE       -19
#define AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED    -20

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
static jint
android_media_AudioRecord_setup(JNIEnv *env, jobject thiz, jobject weak_this,
        jobject jaa, jint sampleRateInHertz, jint channelMask,
                // Java channel masks map directly to the native definition
        jint audioFormat, jint buffSizeInBytes, jintArray jSession)
{
    //ALOGV(">> Entering android_media_AudioRecord_setup");
    //ALOGV("sampleRate=%d, audioFormat=%d, channel mask=%x, buffSizeInBytes=%d",
    //     sampleRateInHertz, audioFormat, channelMask, buffSizeInBytes);

    if (jaa == 0) {
        ALOGE("Error creating AudioRecord: invalid audio attributes");
        return (jint) AUDIO_JAVA_ERROR;
    }

    if (!audio_is_input_channel(channelMask)) {
        ALOGE("Error creating AudioRecord: channel mask %#x is not valid.", channelMask);
        return (jint) AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK;
    }
    uint32_t channelCount = audio_channel_count_from_in_mask(channelMask);

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

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find %s when setting up callback.", kClassPathName);
        return (jint) AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED;
    }

    if (jSession == NULL) {
        ALOGE("Error creating AudioRecord: invalid session ID pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }

    jint* nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioRecord: Error retrieving session id pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }
    int sessionId = nSession[0];
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    // create an uninitialized AudioRecord object
    sp<AudioRecord> lpRecorder = new AudioRecord();

    audio_attributes_t *paa = NULL;
    // read the AudioAttributes values
    paa = (audio_attributes_t *) calloc(1, sizeof(audio_attributes_t));
    const jstring jtags =
            (jstring) env->GetObjectField(jaa, javaAudioAttrFields.fieldFormattedTags);
    const char* tags = env->GetStringUTFChars(jtags, NULL);
    // copying array size -1, char array for tags was calloc'd, no need to NULL-terminate it
    strncpy(paa->tags, tags, AUDIO_ATTRIBUTES_TAGS_MAX_SIZE - 1);
    env->ReleaseStringUTFChars(jtags, tags);
    paa->source = (audio_source_t) env->GetIntField(jaa, javaAudioAttrFields.fieldRecSource);
    paa->flags = (audio_flags_mask_t)env->GetIntField(jaa, javaAudioAttrFields.fieldFlags);
    ALOGV("AudioRecord_setup for source=%d tags=%s flags=%08x", paa->source, paa->tags, paa->flags);

    audio_input_flags_t flags = AUDIO_INPUT_FLAG_NONE;
    if (paa->flags & AUDIO_FLAG_HW_HOTWORD) {
        flags = AUDIO_INPUT_FLAG_HW_HOTWORD;
    }
    // create the callback information:
    // this data will be passed with every AudioRecord callback
    audiorecord_callback_cookie *lpCallbackData = new audiorecord_callback_cookie;
    lpCallbackData->audioRecord_class = (jclass)env->NewGlobalRef(clazz);
    // we use a weak reference so the AudioRecord object can be garbage collected.
    lpCallbackData->audioRecord_ref = env->NewGlobalRef(weak_this);
    lpCallbackData->busy = false;

    const status_t status = lpRecorder->set(paa->source,
        sampleRateInHertz,
        format,        // word length, PCM
        channelMask,
        frameCount,
        recorderCallback,// callback_t
        lpCallbackData,// void* user
        0,             // notificationFrames,
        true,          // threadCanCallJava
        sessionId,
        AudioRecord::TRANSFER_DEFAULT,
        flags);

    if (status != NO_ERROR) {
        ALOGE("Error creating AudioRecord instance: initialization check failed with status %d.",
                status);
        goto native_init_failure;
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
            lpRecorder->start((AudioSystem::sync_event_t)event, triggerSession));
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


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_readInByteArray(JNIEnv *env,  jobject thiz,
                                                        jbyteArray javaAudioData,
                                                        jint offsetInBytes, jint sizeInBytes) {
    jbyte* recordBuff = NULL;
    // get the audio recorder from which we'll read new audio samples
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        ALOGE("Unable to retrieve AudioRecord object, can't record");
        return 0;
    }

    if (!javaAudioData) {
        ALOGE("Invalid Java array to store recorded audio, can't record");
        return 0;
    }

    // get the pointer to where we'll record the audio
    // NOTE: We may use GetPrimitiveArrayCritical() when the JNI implementation changes in such
    // a way that it becomes much more efficient. When doing so, we will have to prevent the
    // AudioSystem callback to be called while in critical section (in case of media server
    // process crash for instance)
    recordBuff = (jbyte *)env->GetByteArrayElements(javaAudioData, NULL);

    if (recordBuff == NULL) {
        ALOGE("Error retrieving destination for recorded audio data, can't record");
        return 0;
    }

    // read the new audio data from the native AudioRecord object
    ssize_t recorderBuffSize = lpRecorder->frameCount()*lpRecorder->frameSize();
    ssize_t readSize = lpRecorder->read(recordBuff + offsetInBytes,
                                        sizeInBytes > (jint)recorderBuffSize ?
                                            (jint)recorderBuffSize : sizeInBytes );
    env->ReleaseByteArrayElements(javaAudioData, recordBuff, 0);

    if (readSize < 0) {
        readSize = (jint)AUDIO_JAVA_INVALID_OPERATION;
    }
    return (jint) readSize;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_readInShortArray(JNIEnv *env,  jobject thiz,
                                                        jshortArray javaAudioData,
                                                        jint offsetInShorts, jint sizeInShorts) {

    jshort* recordBuff = NULL;
    // get the audio recorder from which we'll read new audio samples
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL) {
        ALOGE("Unable to retrieve AudioRecord object, can't record");
        return 0;
    }

    if (!javaAudioData) {
        ALOGE("Invalid Java array to store recorded audio, can't record");
        return 0;
    }

    // get the pointer to where we'll record the audio
    // NOTE: We may use GetPrimitiveArrayCritical() when the JNI implementation changes in such
    // a way that it becomes much more efficient. When doing so, we will have to prevent the
    // AudioSystem callback to be called while in critical section (in case of media server
    // process crash for instance)
    recordBuff = (jshort *)env->GetShortArrayElements(javaAudioData, NULL);

    if (recordBuff == NULL) {
        ALOGE("Error retrieving destination for recorded audio data, can't record");
        return 0;
    }

    // read the new audio data from the native AudioRecord object
    const size_t recorderBuffSize = lpRecorder->frameCount()*lpRecorder->frameSize();
    const size_t sizeInBytes = sizeInShorts * sizeof(short);
    ssize_t readSize = lpRecorder->read(recordBuff + offsetInShorts,
                                        sizeInBytes > recorderBuffSize ?
                                            recorderBuffSize : sizeInBytes);

    env->ReleaseShortArrayElements(javaAudioData, recordBuff, 0);

    if (readSize < 0) {
        readSize = (jint)AUDIO_JAVA_INVALID_OPERATION;
    } else {
        readSize /= sizeof(short);
    }
    return (jint) readSize;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_readInDirectBuffer(JNIEnv *env,  jobject thiz,
                                                  jobject jBuffer, jint sizeInBytes) {
    // get the audio recorder from which we'll read new audio samples
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder==NULL)
        return 0;

    // direct buffer and direct access supported?
    long capacity = env->GetDirectBufferCapacity(jBuffer);
    if (capacity == -1) {
        // buffer direct access is not supported
        ALOGE("Buffer direct access is not supported, can't record");
        return 0;
    }
    //ALOGV("capacity = %ld", capacity);
    jbyte* nativeFromJavaBuf = (jbyte*) env->GetDirectBufferAddress(jBuffer);
    if (nativeFromJavaBuf==NULL) {
        ALOGE("Buffer direct access is not supported, can't record");
        return 0;
    }

    // read new data from the recorder
    ssize_t readSize = lpRecorder->read(nativeFromJavaBuf,
                                   capacity < sizeInBytes ? capacity : sizeInBytes);
    if (readSize < 0) {
        readSize = (jint)AUDIO_JAVA_INVALID_OPERATION;
    }
    return (jint)readSize;
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


// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static JNINativeMethod gMethods[] = {
    // name,               signature,  funcPtr
    {"native_start",         "(II)I",    (void *)android_media_AudioRecord_start},
    {"native_stop",          "()V",    (void *)android_media_AudioRecord_stop},
    {"native_setup",         "(Ljava/lang/Object;Ljava/lang/Object;IIII[I)I",
                                       (void *)android_media_AudioRecord_setup},
    {"native_finalize",      "()V",    (void *)android_media_AudioRecord_finalize},
    {"native_release",       "()V",    (void *)android_media_AudioRecord_release},
    {"native_read_in_byte_array",
                             "([BII)I", (void *)android_media_AudioRecord_readInByteArray},
    {"native_read_in_short_array",
                             "([SII)I", (void *)android_media_AudioRecord_readInShortArray},
    {"native_read_in_direct_buffer","(Ljava/lang/Object;I)I",
                                       (void *)android_media_AudioRecord_readInDirectBuffer},
    {"native_set_marker_pos","(I)I",   (void *)android_media_AudioRecord_set_marker_pos},
    {"native_get_marker_pos","()I",    (void *)android_media_AudioRecord_get_marker_pos},
    {"native_set_pos_update_period",
                             "(I)I",   (void *)android_media_AudioRecord_set_pos_update_period},
    {"native_get_pos_update_period",
                             "()I",    (void *)android_media_AudioRecord_get_pos_update_period},
    {"native_get_min_buff_size",
                             "(III)I",   (void *)android_media_AudioRecord_get_min_buff_size},
};

// field names found in android/media/AudioRecord.java
#define JAVA_POSTEVENT_CALLBACK_NAME  "postEventFromNative"
#define JAVA_NATIVERECORDERINJAVAOBJ_FIELD_NAME  "mNativeRecorderInJavaObj"
#define JAVA_NATIVECALLBACKINFO_FIELD_NAME       "mNativeCallbackCookie"

// ----------------------------------------------------------------------------
int register_android_media_AudioRecord(JNIEnv *env)
{
    javaAudioRecordFields.postNativeEventInJava = NULL;
    javaAudioRecordFields.nativeRecorderInJavaObj = NULL;
    javaAudioRecordFields.nativeCallbackCookie = NULL;


    // Get the AudioRecord class
    jclass audioRecordClass = env->FindClass(kClassPathName);
    if (audioRecordClass == NULL) {
        ALOGE("Can't find %s", kClassPathName);
        return -1;
    }
    // Get the postEvent method
    javaAudioRecordFields.postNativeEventInJava = env->GetStaticMethodID(
            audioRecordClass,
            JAVA_POSTEVENT_CALLBACK_NAME, "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (javaAudioRecordFields.postNativeEventInJava == NULL) {
        ALOGE("Can't find AudioRecord.%s", JAVA_POSTEVENT_CALLBACK_NAME);
        return -1;
    }

    // Get the variables
    //    mNativeRecorderInJavaObj
    javaAudioRecordFields.nativeRecorderInJavaObj =
        env->GetFieldID(audioRecordClass,
                        JAVA_NATIVERECORDERINJAVAOBJ_FIELD_NAME, "J");
    if (javaAudioRecordFields.nativeRecorderInJavaObj == NULL) {
        ALOGE("Can't find AudioRecord.%s", JAVA_NATIVERECORDERINJAVAOBJ_FIELD_NAME);
        return -1;
    }
    //     mNativeCallbackCookie
    javaAudioRecordFields.nativeCallbackCookie = env->GetFieldID(
            audioRecordClass,
            JAVA_NATIVECALLBACKINFO_FIELD_NAME, "J");
    if (javaAudioRecordFields.nativeCallbackCookie == NULL) {
        ALOGE("Can't find AudioRecord.%s", JAVA_NATIVECALLBACKINFO_FIELD_NAME);
        return -1;
    }

    // Get the AudioAttributes class and fields
    jclass audioAttrClass = env->FindClass(kAudioAttributesClassPathName);
    if (audioAttrClass == NULL) {
        ALOGE("Can't find %s", kAudioAttributesClassPathName);
        return -1;
    }
    jclass audioAttributesClassRef = (jclass)env->NewGlobalRef(audioAttrClass);
    javaAudioAttrFields.fieldRecSource = env->GetFieldID(audioAttributesClassRef, "mSource", "I");
    javaAudioAttrFields.fieldFlags = env->GetFieldID(audioAttributesClassRef, "mFlags", "I");
    javaAudioAttrFields.fieldFormattedTags =
            env->GetFieldID(audioAttributesClassRef, "mFormattedTags", "Ljava/lang/String;");
    env->DeleteGlobalRef(audioAttributesClassRef);
    if (javaAudioAttrFields.fieldRecSource == NULL
            || javaAudioAttrFields.fieldFlags == NULL
            || javaAudioAttrFields.fieldFormattedTags == NULL) {
        ALOGE("Can't initialize AudioAttributes fields");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

// ----------------------------------------------------------------------------
