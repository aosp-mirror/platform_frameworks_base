/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
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

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <media/AudioRecord.h>

#include <system/audio.h>

// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/AudioRecord";

struct fields_t {
    // these fields provide access from C++ to the...
    jmethodID postNativeEventInJava; //... event post callback method
    int       PCM16;                 //...  format constants
    int       PCM8;                  //...  format constants
    int       AMRNB;                 //...  format constants
    int       AMRWB;                 //...  format constants
    int       EVRC;                  //...  format constants
    int       EVRCB;                 //...  format constants
    int       EVRCWB;                //...  format constants
    int       EVRCNW;                //...  format constants
    jfieldID  nativeRecorderInJavaObj; // provides access to the C++ AudioRecord object
    jfieldID  nativeCallbackCookie;    // provides access to the AudioRecord callback data
};
static fields_t javaAudioRecordFields;

struct audiorecord_callback_cookie {
    jclass      audioRecord_class;
    jobject     audioRecord_ref;
    bool        busy;
    Condition   cond;
};

// keep these values in sync with AudioFormat.java
#define ENCODING_PCM_16BIT 2
#define ENCODING_PCM_8BIT  3

static Mutex sLock;
static SortedVector <audiorecord_callback_cookie *> sAudioRecordCallBackCookies;

// ----------------------------------------------------------------------------

#define AUDIORECORD_SUCCESS                         0
#define AUDIORECORD_ERROR                           -1
#define AUDIORECORD_ERROR_BAD_VALUE                 -2
#define AUDIORECORD_ERROR_INVALID_OPERATION         -3
#define AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT      -16
#define AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK -17
#define AUDIORECORD_ERROR_SETUP_INVALIDFORMAT       -18
#define AUDIORECORD_ERROR_SETUP_INVALIDSOURCE       -19
#define AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED    -20

jint android_media_translateRecorderErrorCode(int code) {
    switch (code) {
    case NO_ERROR:
        return AUDIORECORD_SUCCESS;
    case BAD_VALUE:
        return AUDIORECORD_ERROR_BAD_VALUE;
    case INVALID_OPERATION:
        return AUDIORECORD_ERROR_INVALID_OPERATION;
    default:
        return AUDIORECORD_ERROR;
    }
}


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
            (AudioRecord*)env->GetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    return sp<AudioRecord>(ar);
}

static sp<AudioRecord> setAudioRecord(JNIEnv* env, jobject thiz, const sp<AudioRecord>& ar)
{
    Mutex::Autolock l(sLock);
    sp<AudioRecord> old =
            (AudioRecord*)env->GetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    if (ar.get()) {
        ar->incStrong((void*)setAudioRecord);
    }
    if (old != 0) {
        old->decStrong((void*)setAudioRecord);
    }
    env->SetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj, (int)ar.get());
    return old;
}
int getformatrec(int audioformat)
{
    if(audioformat==javaAudioRecordFields.PCM16)
        return AUDIO_FORMAT_PCM_16_BIT;
    else if(audioformat==javaAudioRecordFields.AMRNB)
        return AUDIO_FORMAT_AMR_NB;
    else if(audioformat==javaAudioRecordFields.AMRWB)
        return AUDIO_FORMAT_AMR_WB;
    else if(audioformat==javaAudioRecordFields.EVRC)
        return AUDIO_FORMAT_EVRC;
    else if(audioformat==javaAudioRecordFields.EVRCB)
        return AUDIO_FORMAT_EVRCB;
    else if(audioformat==javaAudioRecordFields.EVRCWB)
        return AUDIO_FORMAT_EVRCWB;
    else if(audioformat==javaAudioRecordFields.EVRCNW)
        return AUDIO_FORMAT_EVRCNW;
    else
        return AUDIO_FORMAT_PCM_8_BIT;
}

// ----------------------------------------------------------------------------
static int
android_media_AudioRecord_setup(JNIEnv *env, jobject thiz, jobject weak_this,
        jint source, jint sampleRateInHertz, jint channelMask,
                // Java channel masks map directly to the native definition
        jint audioFormat, jint buffSizeInBytes, jintArray jSession)
{
    //ALOGV(">> Entering android_media_AudioRecord_setup");
    //ALOGV("sampleRate=%d, audioFormat=%d, channel mask=%x, buffSizeInBytes=%d",
    //     sampleRateInHertz, audioFormat, channelMask, buffSizeInBytes);

    if (!audio_is_input_channel(channelMask)) {
        ALOGE("Error creating AudioRecord: channel mask %#x is not valid.", channelMask);
        return AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK;
    }
    uint32_t nbChannels = popcount(channelMask);

    // compare the format against the Java constants
    if ((audioFormat != javaAudioRecordFields.PCM16)
        && (audioFormat != javaAudioRecordFields.PCM8)
        && (audioFormat != javaAudioRecordFields.AMRNB)
        && (audioFormat != javaAudioRecordFields.AMRWB)
        && (audioFormat != javaAudioRecordFields.EVRC)
        && (audioFormat != javaAudioRecordFields.EVRCB)
        && (audioFormat != javaAudioRecordFields.EVRCWB)
        && (audioFormat != javaAudioRecordFields.EVRCNW)) {
        ALOGE("Error creating AudioRecord: unsupported audio format.");
        return AUDIORECORD_ERROR_SETUP_INVALIDFORMAT;
    }
    int bytesPerSample;
    if(audioFormat == javaAudioRecordFields.PCM16)
        bytesPerSample = 2;
    else if((audioFormat == javaAudioRecordFields.AMRWB) &&
            ((uint32_t)source != AUDIO_SOURCE_VOICE_COMMUNICATION))
        bytesPerSample = 61;
    else
        bytesPerSample = 1;
    audio_format_t format = (audio_format_t)getformatrec(audioFormat);

    if (buffSizeInBytes == 0) {
         ALOGE("Error creating AudioRecord: frameCount is 0.");
        return AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT;
    }
    int frameSize = nbChannels * bytesPerSample;
    size_t frameCount = buffSizeInBytes / frameSize;

    if ((uint32_t(source) >= AUDIO_SOURCE_CNT) && (uint32_t(source) != AUDIO_SOURCE_HOTWORD)) {
        ALOGE("Error creating AudioRecord: unknown source.");
        return AUDIORECORD_ERROR_SETUP_INVALIDSOURCE;
    }

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find %s when setting up callback.", kClassPathName);
        return AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED;
    }

    if (jSession == NULL) {
        ALOGE("Error creating AudioRecord: invalid session ID pointer");
        return AUDIORECORD_ERROR;
    }

    jint* nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioRecord: Error retrieving session id pointer");
        return AUDIORECORD_ERROR;
    }
    int sessionId = nSession[0];
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    // create an uninitialized AudioRecord object
    sp<AudioRecord> lpRecorder = new AudioRecord();

    // create the callback information:
    // this data will be passed with every AudioRecord callback
    audiorecord_callback_cookie *lpCallbackData = new audiorecord_callback_cookie;
    lpCallbackData->audioRecord_class = (jclass)env->NewGlobalRef(clazz);
    // we use a weak reference so the AudioRecord object can be garbage collected.
    lpCallbackData->audioRecord_ref = env->NewGlobalRef(weak_this);
    lpCallbackData->busy = false;

    lpRecorder->set((audio_source_t) source,
        sampleRateInHertz,
        format,        // word length, PCM
        channelMask,
        frameCount,
        recorderCallback,// callback_t
        lpCallbackData,// void* user
        0,             // notificationFrames,
        true,          // threadCanCallJava
        sessionId);

    if (lpRecorder->initCheck() != NO_ERROR) {
        ALOGE("Error creating AudioRecord instance: initialization check failed.");
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
    env->SetIntField(thiz, javaAudioRecordFields.nativeCallbackCookie, (int)lpCallbackData);

    return AUDIORECORD_SUCCESS;

    // failure:
native_init_failure:
    env->DeleteGlobalRef(lpCallbackData->audioRecord_class);
    env->DeleteGlobalRef(lpCallbackData->audioRecord_ref);
    delete lpCallbackData;
    env->SetIntField(thiz, javaAudioRecordFields.nativeCallbackCookie, 0);

    return AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED;
}



// ----------------------------------------------------------------------------
static int
android_media_AudioRecord_start(JNIEnv *env, jobject thiz, jint event, jint triggerSession)
{
    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    if (lpRecorder == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return AUDIORECORD_ERROR;
    }

    return android_media_translateRecorderErrorCode(
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
    ALOGV("About to delete lpRecorder: %x\n", (int)lpRecorder.get());
    lpRecorder->stop();

    audiorecord_callback_cookie *lpCookie = (audiorecord_callback_cookie *)env->GetIntField(
        thiz, javaAudioRecordFields.nativeCallbackCookie);

    // reset the native resources in the Java object so any attempt to access
    // them after a call to release fails.
    env->SetIntField(thiz, javaAudioRecordFields.nativeCallbackCookie, 0);

    // delete the callback information
    if (lpCookie) {
        Mutex::Autolock l(sLock);
        ALOGV("deleting lpCookie: %x\n", (int)lpCookie);
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
        readSize = AUDIORECORD_ERROR_INVALID_OPERATION;
    }
    return (jint) readSize;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_readInShortArray(JNIEnv *env,  jobject thiz,
                                                        jshortArray javaAudioData,
                                                        jint offsetInShorts, jint sizeInShorts) {

    jint read = android_media_AudioRecord_readInByteArray(env, thiz,
                                                        (jbyteArray) javaAudioData,
                                                        offsetInShorts*2, sizeInShorts*2);
    if (read > 0) {
        read /= 2;
    }
    return read;
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
        readSize = AUDIORECORD_ERROR_INVALID_OPERATION;
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
        return AUDIORECORD_ERROR;
    }
    return android_media_translateRecorderErrorCode( lpRecorder->setMarkerPosition(markerPos) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_marker_pos(JNIEnv *env,  jobject thiz) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    uint32_t markerPos = 0;

    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for getMarkerPosition()");
        return AUDIORECORD_ERROR;
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
        return AUDIORECORD_ERROR;
    }
    return android_media_translateRecorderErrorCode( lpRecorder->setPositionUpdatePeriod(period) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_pos_update_period(JNIEnv *env,  jobject thiz) {

    sp<AudioRecord> lpRecorder = getAudioRecord(env, thiz);
    uint32_t period = 0;

    if (lpRecorder == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for getPositionUpdatePeriod()");
        return AUDIORECORD_ERROR;
    }
    lpRecorder->getPositionUpdatePeriod(&period);
    return (jint)period;
}


// ----------------------------------------------------------------------------
// returns the minimum required size for the successful creation of an AudioRecord instance.
// returns 0 if the parameter combination is not supported.
// return -1 if there was an error querying the buffer size.
static jint android_media_AudioRecord_get_min_buff_size(JNIEnv *env,  jobject thiz,
    jint sampleRateInHertz, jint nbChannels, jint audioFormat) {

    ALOGV(">> android_media_AudioRecord_get_min_buff_size(%d, %d, %d)",
          sampleRateInHertz, nbChannels, audioFormat);

    size_t frameCount = 0;
    status_t result = AudioRecord::getMinFrameCount(&frameCount,
            sampleRateInHertz,
            (audio_format_t)getformatrec(audioFormat),
            audio_channel_in_mask_from_count(nbChannels));

    if (result == BAD_VALUE) {
        return 0;
    }
    if (result != NO_ERROR) {
        return -1;
    }
    int bytesPerSample;
    if(audioFormat == javaAudioRecordFields.PCM16)
        bytesPerSample = 2;
    else if(audioFormat == javaAudioRecordFields.AMRWB)
        bytesPerSample = 61;
    else
        bytesPerSample = 1;

    return frameCount * nbChannels * bytesPerSample;
}


// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static JNINativeMethod gMethods[] = {
    // name,               signature,  funcPtr
    {"native_start",         "(II)I",    (void *)android_media_AudioRecord_start},
    {"native_stop",          "()V",    (void *)android_media_AudioRecord_stop},
    {"native_setup",         "(Ljava/lang/Object;IIIII[I)I",
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
                        JAVA_NATIVERECORDERINJAVAOBJ_FIELD_NAME, "I");
    if (javaAudioRecordFields.nativeRecorderInJavaObj == NULL) {
        ALOGE("Can't find AudioRecord.%s", JAVA_NATIVERECORDERINJAVAOBJ_FIELD_NAME);
        return -1;
    }
    //     mNativeCallbackCookie
    javaAudioRecordFields.nativeCallbackCookie = env->GetFieldID(
            audioRecordClass,
            JAVA_NATIVECALLBACKINFO_FIELD_NAME, "I");
    if (javaAudioRecordFields.nativeCallbackCookie == NULL) {
        ALOGE("Can't find AudioRecord.%s", JAVA_NATIVECALLBACKINFO_FIELD_NAME);
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

// ----------------------------------------------------------------------------
