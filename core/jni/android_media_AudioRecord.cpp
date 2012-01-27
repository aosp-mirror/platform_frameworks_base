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

#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <math.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include "utils/Log.h"
#include "media/AudioRecord.h"
#include "media/mediarecorder.h"

#include <cutils/bitops.h>

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
    jfieldID  nativeRecorderInJavaObj; // provides access to the C++ AudioRecord object
    jfieldID  nativeCallbackCookie;    // provides access to the AudioRecord callback data
};
static fields_t javaAudioRecordFields;

struct audiorecord_callback_cookie {
    jclass      audioRecord_class;
    jobject     audioRecord_ref;
 };

Mutex sLock;

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
    switch(code) {
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
    if (event == AudioRecord::EVENT_MORE_DATA) {
        // set size to 0 to signal we're not using the callback to read more data
        AudioRecord::Buffer* pBuff = (AudioRecord::Buffer*)info;
        pBuff->size = 0;  
    
    } else if (event == AudioRecord::EVENT_MARKER) {
        audiorecord_callback_cookie *callbackInfo = (audiorecord_callback_cookie *)user;
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        if (user && env) {
            env->CallStaticVoidMethod(
                callbackInfo->audioRecord_class, 
                javaAudioRecordFields.postNativeEventInJava,
                callbackInfo->audioRecord_ref, event, 0,0, NULL);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }

    } else if (event == AudioRecord::EVENT_NEW_POS) {
        audiorecord_callback_cookie *callbackInfo = (audiorecord_callback_cookie *)user;
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        if (user && env) {
            env->CallStaticVoidMethod(
                callbackInfo->audioRecord_class, 
                javaAudioRecordFields.postNativeEventInJava,
                callbackInfo->audioRecord_ref, event, 0,0, NULL);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
    }
}


// ----------------------------------------------------------------------------
static int
android_media_AudioRecord_setup(JNIEnv *env, jobject thiz, jobject weak_this,
        jint source, jint sampleRateInHertz, jint channels,
        jint audioFormat, jint buffSizeInBytes, jintArray jSession)
{
    //ALOGV(">> Entering android_media_AudioRecord_setup");
    //ALOGV("sampleRate=%d, audioFormat=%d, channels=%x, buffSizeInBytes=%d",
    //     sampleRateInHertz, audioFormat, channels,     buffSizeInBytes);

    if (!audio_is_input_channel(channels)) {
        ALOGE("Error creating AudioRecord: channel count is not 1 or 2.");
        return AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK;
    }
    uint32_t nbChannels = popcount(channels);

    // compare the format against the Java constants
    if ((audioFormat != javaAudioRecordFields.PCM16) 
        && (audioFormat != javaAudioRecordFields.PCM8)) {
        ALOGE("Error creating AudioRecord: unsupported audio format.");
        return AUDIORECORD_ERROR_SETUP_INVALIDFORMAT;
    }

    int bytesPerSample = audioFormat==javaAudioRecordFields.PCM16 ? 2 : 1;
    audio_format_t format = audioFormat==javaAudioRecordFields.PCM16 ?
            AUDIO_FORMAT_PCM_16_BIT : AUDIO_FORMAT_PCM_8_BIT;

    if (buffSizeInBytes == 0) {
         ALOGE("Error creating AudioRecord: frameCount is 0.");
        return AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT;
    }
    int frameSize = nbChannels * bytesPerSample;
    size_t frameCount = buffSizeInBytes / frameSize;
    
    if (uint32_t(source) >= AUDIO_SOURCE_CNT) {
        ALOGE("Error creating AudioRecord: unknown source.");
        return AUDIORECORD_ERROR_SETUP_INVALIDSOURCE;
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

    audiorecord_callback_cookie *lpCallbackData = NULL;
    AudioRecord* lpRecorder = NULL;

    // create an uninitialized AudioRecord object
    lpRecorder = new AudioRecord();
        if(lpRecorder == NULL) {
        ALOGE("Error creating AudioRecord instance.");
        return AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED;
    }
    
    // create the callback information:
    // this data will be passed with every AudioRecord callback
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find %s when setting up callback.", kClassPathName);
        goto native_track_failure;
    }
    lpCallbackData = new audiorecord_callback_cookie;
    lpCallbackData->audioRecord_class = (jclass)env->NewGlobalRef(clazz);
    // we use a weak reference so the AudioRecord object can be garbage collected.
    lpCallbackData->audioRecord_ref = env->NewGlobalRef(weak_this);
    
    lpRecorder->set((audio_source_t) source,
        sampleRateInHertz,
        format,        // word length, PCM
        channels,
        frameCount,
        0,             // flags
        recorderCallback,// callback_t
        lpCallbackData,// void* user
        0,             // notificationFrames,
        true,          // threadCanCallJava)
        sessionId);

    if(lpRecorder->initCheck() != NO_ERROR) {
        ALOGE("Error creating AudioRecord instance: initialization check failed.");
        goto native_init_failure;
    }

    nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioRecord: Error retrieving session id pointer");
        goto native_init_failure;
    }
    // read the audio session ID back from AudioTrack in case a new session was created during set()
    nSession[0] = lpRecorder->getSessionId();
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    // save our newly created C++ AudioRecord in the "nativeRecorderInJavaObj" field 
    // of the Java object
    env->SetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj, (int)lpRecorder);
    
    // save our newly created callback information in the "nativeCallbackCookie" field
    // of the Java object (in mNativeCallbackCookie) so we can free the memory in finalize()
    env->SetIntField(thiz, javaAudioRecordFields.nativeCallbackCookie, (int)lpCallbackData);
    
    return AUDIORECORD_SUCCESS;
    
    // failure:
native_init_failure:
    env->DeleteGlobalRef(lpCallbackData->audioRecord_class);
    env->DeleteGlobalRef(lpCallbackData->audioRecord_ref);
    delete lpCallbackData;

native_track_failure:
    delete lpRecorder;

    env->SetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj, 0);
    env->SetIntField(thiz, javaAudioRecordFields.nativeCallbackCookie, 0);
    
    return AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED;
}



// ----------------------------------------------------------------------------
static int
android_media_AudioRecord_start(JNIEnv *env, jobject thiz)
{
    AudioRecord *lpRecorder = 
            (AudioRecord *)env->GetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    if (lpRecorder == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return AUDIORECORD_ERROR;
    }
    
    return android_media_translateRecorderErrorCode(lpRecorder->start());
}


// ----------------------------------------------------------------------------
static void
android_media_AudioRecord_stop(JNIEnv *env, jobject thiz)
{
    AudioRecord *lpRecorder = 
            (AudioRecord *)env->GetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    if (lpRecorder == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    lpRecorder->stop();
    //ALOGV("Called lpRecorder->stop()");
}


// ----------------------------------------------------------------------------
static void android_media_AudioRecord_release(JNIEnv *env,  jobject thiz) {

    // serialize access. Ugly, but functional.
    Mutex::Autolock lock(&sLock);
    AudioRecord *lpRecorder = 
            (AudioRecord *)env->GetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    audiorecord_callback_cookie *lpCookie = (audiorecord_callback_cookie *)env->GetIntField(
        thiz, javaAudioRecordFields.nativeCallbackCookie);

    // reset the native resources in the Java object so any attempt to access
    // them after a call to release fails.
    env->SetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj, 0);
    env->SetIntField(thiz, javaAudioRecordFields.nativeCallbackCookie, 0);

    // delete the AudioRecord object
    if (lpRecorder) {
        ALOGV("About to delete lpRecorder: %x\n", (int)lpRecorder);
        lpRecorder->stop();
        delete lpRecorder;
    }
    
    // delete the callback information
    if (lpCookie) {
        ALOGV("deleting lpCookie: %x\n", (int)lpCookie);
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
    AudioRecord *lpRecorder = NULL;

    // get the audio recorder from which we'll read new audio samples
    lpRecorder = 
            (AudioRecord *)env->GetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
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

    return (jint) readSize;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_readInShortArray(JNIEnv *env,  jobject thiz,
                                                        jshortArray javaAudioData,
                                                        jint offsetInShorts, jint sizeInShorts) {

    return (android_media_AudioRecord_readInByteArray(env, thiz,
                                                        (jbyteArray) javaAudioData,
                                                        offsetInShorts*2, sizeInShorts*2)
            / 2);
}

// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_readInDirectBuffer(JNIEnv *env,  jobject thiz,
                                                  jobject jBuffer, jint sizeInBytes) {
    AudioRecord *lpRecorder = NULL;
    //ALOGV("Entering android_media_AudioRecord_readInBuffer");

    // get the audio recorder from which we'll read new audio samples
    lpRecorder = 
        (AudioRecord *)env->GetIntField(thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    if(lpRecorder==NULL)
        return 0;

    // direct buffer and direct access supported?
    long capacity = env->GetDirectBufferCapacity(jBuffer);
    if(capacity == -1) {
        // buffer direct access is not supported
        ALOGE("Buffer direct access is not supported, can't record");
        return 0;
    }
    //ALOGV("capacity = %ld", capacity);
    jbyte* nativeFromJavaBuf = (jbyte*) env->GetDirectBufferAddress(jBuffer);
    if(nativeFromJavaBuf==NULL) {
        ALOGE("Buffer direct access is not supported, can't record");
        return 0;
    } 

    // read new data from the recorder
    return (jint) lpRecorder->read(nativeFromJavaBuf, 
                                   capacity < sizeInBytes ? capacity : sizeInBytes);
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_set_marker_pos(JNIEnv *env,  jobject thiz, 
        jint markerPos) {
            
    AudioRecord *lpRecorder = (AudioRecord *)env->GetIntField(
                thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
                
    if (lpRecorder) {
        return 
            android_media_translateRecorderErrorCode( lpRecorder->setMarkerPosition(markerPos) );   
    } else {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for setMarkerPosition()");
        return AUDIORECORD_ERROR;
    }
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_marker_pos(JNIEnv *env,  jobject thiz) {
    
    AudioRecord *lpRecorder = (AudioRecord *)env->GetIntField(
                thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    uint32_t markerPos = 0;
                
    if (lpRecorder) {
        lpRecorder->getMarkerPosition(&markerPos);
        return (jint)markerPos;
    } else {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for getMarkerPosition()");
        return AUDIORECORD_ERROR;
    }
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_set_pos_update_period(JNIEnv *env,  jobject thiz,
        jint period) {
            
    AudioRecord *lpRecorder = (AudioRecord *)env->GetIntField(
                thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
                
    if (lpRecorder) {
        return 
            android_media_translateRecorderErrorCode( lpRecorder->setPositionUpdatePeriod(period) );   
    } else {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for setPositionUpdatePeriod()");
        return AUDIORECORD_ERROR;
    }            
}


// ----------------------------------------------------------------------------
static jint android_media_AudioRecord_get_pos_update_period(JNIEnv *env,  jobject thiz) {
    
    AudioRecord *lpRecorder = (AudioRecord *)env->GetIntField(
                thiz, javaAudioRecordFields.nativeRecorderInJavaObj);
    uint32_t period = 0;
                
    if (lpRecorder) {
        lpRecorder->getPositionUpdatePeriod(&period);
        return (jint)period;
    } else {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioRecord pointer for getPositionUpdatePeriod()");
        return AUDIORECORD_ERROR;
    }
}


// ----------------------------------------------------------------------------
// returns the minimum required size for the successful creation of an AudioRecord instance.
// returns 0 if the parameter combination is not supported.
// return -1 if there was an error querying the buffer size.
static jint android_media_AudioRecord_get_min_buff_size(JNIEnv *env,  jobject thiz,
    jint sampleRateInHertz, jint nbChannels, jint audioFormat) {

    ALOGV(">> android_media_AudioRecord_get_min_buff_size(%d, %d, %d)", sampleRateInHertz, nbChannels, audioFormat);

    int frameCount = 0;
    status_t result = AudioRecord::getMinFrameCount(&frameCount,
            sampleRateInHertz,
            (audioFormat == javaAudioRecordFields.PCM16 ?
                AUDIO_FORMAT_PCM_16_BIT : AUDIO_FORMAT_PCM_8_BIT),
            nbChannels);

    if (result == BAD_VALUE) {
        return 0;
    }
    if (result != NO_ERROR) {
        return -1;
    }
    return frameCount * nbChannels * (audioFormat == javaAudioRecordFields.PCM16 ? 2 : 1);
}


// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static JNINativeMethod gMethods[] = {
    // name,               signature,  funcPtr
    {"native_start",         "()I",    (void *)android_media_AudioRecord_start},
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
#define JAVA_CONST_PCM16_NAME         "ENCODING_PCM_16BIT"
#define JAVA_CONST_PCM8_NAME          "ENCODING_PCM_8BIT"
#define JAVA_NATIVERECORDERINJAVAOBJ_FIELD_NAME  "mNativeRecorderInJavaObj"
#define JAVA_NATIVECALLBACKINFO_FIELD_NAME       "mNativeCallbackCookie"

#define JAVA_AUDIOFORMAT_CLASS_NAME "android/media/AudioFormat"

// ----------------------------------------------------------------------------

extern bool android_media_getIntConstantFromClass(JNIEnv* pEnv, 
                jclass theClass, const char* className, const char* constName, int* constVal);

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

    // Get the format constants from the AudioFormat class
    jclass audioFormatClass = NULL;
    audioFormatClass = env->FindClass(JAVA_AUDIOFORMAT_CLASS_NAME);
    if (audioFormatClass == NULL) {
        ALOGE("Can't find %s", JAVA_AUDIOFORMAT_CLASS_NAME);
        return -1;
    }
    if ( !android_media_getIntConstantFromClass(env, audioFormatClass, 
                JAVA_AUDIOFORMAT_CLASS_NAME, 
                JAVA_CONST_PCM16_NAME, &(javaAudioRecordFields.PCM16))
           || !android_media_getIntConstantFromClass(env, audioFormatClass, 
                JAVA_AUDIOFORMAT_CLASS_NAME, 
                JAVA_CONST_PCM8_NAME, &(javaAudioRecordFields.PCM8)) ) {
        // error log performed in getIntConstantFromClass() 
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

// ----------------------------------------------------------------------------
