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

#define LOG_TAG "AudioTrack-JNI"

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <media/AudioSystem.h>
#include <media/AudioTrack.h>

#include <binder/MemoryHeapBase.h>
#include <binder/MemoryBase.h>

#include <system/audio.h>

// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/AudioTrack";

struct fields_t {
    // these fields provide access from C++ to the...
    jmethodID postNativeEventInJava; //... event post callback method
    // FIX ME: Are the below int type members used anywhere??
    int       STREAM_VOICE_CALL;     //...  stream type constants
    int       STREAM_SYSTEM;         //...  stream type constants
    int       STREAM_RING;           //...  stream type constants
    int       STREAM_MUSIC;          //...  stream type constants
    int       STREAM_ALARM;          //...  stream type constants
    int       STREAM_NOTIFICATION;   //...  stream type constants
    int       STREAM_BLUETOOTH_SCO;  //...  stream type constants
    int       STREAM_DTMF;           //...  stream type constants
    int       MODE_STREAM;           //...  memory mode
    int       MODE_STATIC;           //...  memory mode
    jfieldID  nativeTrackInJavaObj;  // stores in Java the native AudioTrack object
    jfieldID  jniData;      // stores in Java additional resources used by the native AudioTrack
};
static fields_t javaAudioTrackFields;

struct audiotrack_callback_cookie {
    jclass      audioTrack_class;
    jobject     audioTrack_ref;
    bool        busy;
    Condition   cond;
};

// keep these values in sync with AudioTrack.java
#define MODE_STATIC 0
#define MODE_STREAM 1
// keep these values in sync with AudioFormat.java
#define ENCODING_PCM_16BIT 2
#define ENCODING_PCM_8BIT  3
#define ENCODING_AMRNB     100
#define ENCODING_AMRWB     101
#define ENCODING_EVRC      102
#define ENCODING_EVRCB     103
#define ENCODING_EVRCWB    104
#define ENCODING_EVRCNW    105

// ----------------------------------------------------------------------------
class AudioTrackJniStorage {
    public:
        sp<MemoryHeapBase>         mMemHeap;
        sp<MemoryBase>             mMemBase;
        audiotrack_callback_cookie mCallbackData;
        audio_stream_type_t        mStreamType;

    AudioTrackJniStorage() {
        mCallbackData.audioTrack_class = 0;
        mCallbackData.audioTrack_ref = 0;
        mStreamType = AUDIO_STREAM_DEFAULT;
    }

    ~AudioTrackJniStorage() {
        mMemBase.clear();
        mMemHeap.clear();
    }

    bool allocSharedMem(int sizeInBytes) {
        mMemHeap = new MemoryHeapBase(sizeInBytes, 0, "AudioTrack Heap Base");
        if (mMemHeap->getHeapID() < 0) {
            return false;
        }
        mMemBase = new MemoryBase(mMemHeap, 0, sizeInBytes);
        return true;
    }
};

static Mutex sLock;
static SortedVector <audiotrack_callback_cookie *> sAudioTrackCallBackCookies;

// ----------------------------------------------------------------------------
#define DEFAULT_OUTPUT_SAMPLE_RATE   44100

#define AUDIOTRACK_SUCCESS                         0
#define AUDIOTRACK_ERROR                           -1
#define AUDIOTRACK_ERROR_BAD_VALUE                 -2
#define AUDIOTRACK_ERROR_INVALID_OPERATION         -3
#define AUDIOTRACK_ERROR_SETUP_AUDIOSYSTEM         -16
#define AUDIOTRACK_ERROR_SETUP_INVALIDCHANNELMASK  -17
#define AUDIOTRACK_ERROR_SETUP_INVALIDFORMAT       -18
#define AUDIOTRACK_ERROR_SETUP_INVALIDSTREAMTYPE   -19
#define AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED    -20


jint android_media_translateErrorCode(int code) {
    switch (code) {
    case NO_ERROR:
        return AUDIOTRACK_SUCCESS;
    case BAD_VALUE:
        return AUDIOTRACK_ERROR_BAD_VALUE;
    case INVALID_OPERATION:
        return AUDIOTRACK_ERROR_INVALID_OPERATION;
    default:
        return AUDIOTRACK_ERROR;
    }
}


// ----------------------------------------------------------------------------
static void audioCallback(int event, void* user, void *info) {

    audiotrack_callback_cookie *callbackInfo = (audiotrack_callback_cookie *)user;
    {
        Mutex::Autolock l(sLock);
        if (sAudioTrackCallBackCookies.indexOf(callbackInfo) < 0) {
            return;
        }
        callbackInfo->busy = true;
    }

    switch (event) {
    case AudioTrack::EVENT_MARKER: {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        if (user != NULL && env != NULL) {
            env->CallStaticVoidMethod(
                callbackInfo->audioTrack_class,
                javaAudioTrackFields.postNativeEventInJava,
                callbackInfo->audioTrack_ref, event, 0,0, NULL);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        } break;

    case AudioTrack::EVENT_NEW_POS: {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        if (user != NULL && env != NULL) {
            env->CallStaticVoidMethod(
                callbackInfo->audioTrack_class,
                javaAudioTrackFields.postNativeEventInJava,
                callbackInfo->audioTrack_ref, event, 0,0, NULL);
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
static sp<AudioTrack> getAudioTrack(JNIEnv* env, jobject thiz)
{
    Mutex::Autolock l(sLock);
    AudioTrack* const at =
            (AudioTrack*)env->GetIntField(thiz, javaAudioTrackFields.nativeTrackInJavaObj);
    return sp<AudioTrack>(at);
}

static sp<AudioTrack> setAudioTrack(JNIEnv* env, jobject thiz, const sp<AudioTrack>& at)
{
    Mutex::Autolock l(sLock);
    sp<AudioTrack> old =
            (AudioTrack*)env->GetIntField(thiz, javaAudioTrackFields.nativeTrackInJavaObj);
    if (at.get()) {
        at->incStrong((void*)setAudioTrack);
    }
    if (old != 0) {
        old->decStrong((void*)setAudioTrack);
    }
    env->SetIntField(thiz, javaAudioTrackFields.nativeTrackInJavaObj, (int)at.get());
    return old;
}

// ----------------------------------------------------------------------------
int getformat(int audioformat)
{
    switch (audioformat) {
    case ENCODING_PCM_16BIT:
        return AUDIO_FORMAT_PCM_16_BIT;
#ifdef QCOM_HARDWARE
    case ENCODING_PCM_8BIT:
        return AUDIO_FORMAT_PCM_8_BIT;
    case ENCODING_AMRNB:
        return AUDIO_FORMAT_AMR_NB;
    case ENCODING_AMRWB:
        return AUDIO_FORMAT_AMR_WB;
    case ENCODING_EVRC:
        return AUDIO_FORMAT_EVRC;
    case ENCODING_EVRCB:
        return AUDIO_FORMAT_EVRCB;
    case ENCODING_EVRCWB:
        return AUDIO_FORMAT_EVRCWB;
    case ENCODING_EVRCNW:
        return AUDIO_FORMAT_EVRCNW;
#endif
    default:
        return AUDIO_FORMAT_PCM_8_BIT;
    }
}

static int
android_media_AudioTrack_native_setup(JNIEnv *env, jobject thiz, jobject weak_this,
        jint streamType, jint sampleRateInHertz, jint javaChannelMask,
        jint audioFormat, jint buffSizeInBytes, jint memoryMode, jintArray jSession)
{
    ALOGV("sampleRate=%d, audioFormat(from Java)=%d, channel mask=%x, buffSize=%d",
        sampleRateInHertz, audioFormat, javaChannelMask, buffSizeInBytes);
    uint32_t afSampleRate;
    size_t afFrameCount;

    if (AudioSystem::getOutputFrameCount(&afFrameCount, (audio_stream_type_t) streamType) != NO_ERROR) {
        ALOGE("Error creating AudioTrack: Could not get AudioSystem frame count.");
        return AUDIOTRACK_ERROR_SETUP_AUDIOSYSTEM;
    }
    if (AudioSystem::getOutputSamplingRate(&afSampleRate, (audio_stream_type_t) streamType) != NO_ERROR) {
        ALOGE("Error creating AudioTrack: Could not get AudioSystem sampling rate.");
        return AUDIOTRACK_ERROR_SETUP_AUDIOSYSTEM;
    }

    // Java channel masks don't map directly to the native definition, but it's a simple shift
    // to skip the two deprecated channel configurations "default" and "mono".
    uint32_t nativeChannelMask = ((uint32_t)javaChannelMask) >> 2;

    if (!audio_is_output_channel(nativeChannelMask)) {
        ALOGE("Error creating AudioTrack: invalid channel mask %#x.", javaChannelMask);
        return AUDIOTRACK_ERROR_SETUP_INVALIDCHANNELMASK;
    }

    int nbChannels = popcount(nativeChannelMask);

    // check the stream type
    audio_stream_type_t atStreamType;
    switch (streamType) {
    case AUDIO_STREAM_VOICE_CALL:
    case AUDIO_STREAM_SYSTEM:
    case AUDIO_STREAM_RING:
    case AUDIO_STREAM_MUSIC:
    case AUDIO_STREAM_ALARM:
    case AUDIO_STREAM_NOTIFICATION:
    case AUDIO_STREAM_BLUETOOTH_SCO:
    case AUDIO_STREAM_DTMF:
#ifdef QCOM_HARDWARE
    case AUDIO_STREAM_INCALL_MUSIC:
#endif
        atStreamType = (audio_stream_type_t) streamType;
        break;
    default:
        ALOGE("Error creating AudioTrack: unknown stream type.");
        return AUDIOTRACK_ERROR_SETUP_INVALIDSTREAMTYPE;
    }

    // check the format.
    // This function was called from Java, so we compare the format against the Java constants
    if ((audioFormat != ENCODING_PCM_16BIT)
#ifdef QCOM_HARDWARE
        && (audioFormat != ENCODING_AMRNB)
        && (audioFormat != ENCODING_AMRWB)
        && (audioFormat != ENCODING_EVRC)
        && (audioFormat != ENCODING_EVRCB)
        && (audioFormat != ENCODING_EVRCWB)
        && (audioFormat != ENCODING_EVRCNW)
#endif
        && (audioFormat != ENCODING_PCM_8BIT)) {
        ALOGE("Error creating AudioTrack: unsupported audio format.");
        return AUDIOTRACK_ERROR_SETUP_INVALIDFORMAT;
    }

    // for the moment 8bitPCM in MODE_STATIC is not supported natively in the AudioTrack C++ class
    // so we declare everything as 16bitPCM, the 8->16bit conversion for MODE_STATIC will be handled
    // in android_media_AudioTrack_native_write_byte()
    if ((audioFormat == ENCODING_PCM_8BIT)
        && (memoryMode == MODE_STATIC)) {
        ALOGV("android_media_AudioTrack_native_setup(): requesting MODE_STATIC for 8bit \
            buff size of %dbytes, switching to 16bit, buff size of %dbytes",
            buffSizeInBytes, 2*buffSizeInBytes);
        audioFormat = ENCODING_PCM_16BIT;
        // we will need twice the memory to store the data
        buffSizeInBytes *= 2;
    }

    // compute the frame count
    int bytesPerSample;
    if(audioFormat == ENCODING_PCM_16BIT)
        bytesPerSample = 2;
    else
        bytesPerSample = 1;
    audio_format_t format = (audio_format_t)getformat(audioFormat);
    int frameCount = buffSizeInBytes / (nbChannels * bytesPerSample);

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find %s when setting up callback.", kClassPathName);
        return AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED;
    }

    if (jSession == NULL) {
        ALOGE("Error creating AudioTrack: invalid session ID pointer");
        return AUDIOTRACK_ERROR;
    }

    jint* nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioTrack: Error retrieving session id pointer");
        return AUDIOTRACK_ERROR;
    }
    int sessionId = nSession[0];
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    // create the native AudioTrack object
    sp<AudioTrack> lpTrack = new AudioTrack();

    // initialize the callback information:
    // this data will be passed with every AudioTrack callback
    AudioTrackJniStorage* lpJniStorage = new AudioTrackJniStorage();
    lpJniStorage->mStreamType = atStreamType;
    lpJniStorage->mCallbackData.audioTrack_class = (jclass)env->NewGlobalRef(clazz);
    // we use a weak reference so the AudioTrack object can be garbage collected.
    lpJniStorage->mCallbackData.audioTrack_ref = env->NewGlobalRef(weak_this);
    lpJniStorage->mCallbackData.busy = false;

    // initialize the native AudioTrack object
    switch (memoryMode) {
    case MODE_STREAM:

        lpTrack->set(
            atStreamType,// stream type
            sampleRateInHertz,
            format,// word length, PCM
            nativeChannelMask,
            frameCount,
            AUDIO_OUTPUT_FLAG_NONE,
            audioCallback, &(lpJniStorage->mCallbackData),//callback, callback data (user)
            0,// notificationFrames == 0 since not using EVENT_MORE_DATA to feed the AudioTrack
            0,// shared mem
            true,// thread can call Java
            sessionId);// audio session ID
        break;

    case MODE_STATIC:
        // AudioTrack is using shared memory

        if (!lpJniStorage->allocSharedMem(buffSizeInBytes)) {
            ALOGE("Error creating AudioTrack in static mode: error creating mem heap base");
            goto native_init_failure;
        }

        lpTrack->set(
            atStreamType,// stream type
            sampleRateInHertz,
            format,// word length, PCM
            nativeChannelMask,
            frameCount,
            AUDIO_OUTPUT_FLAG_NONE,
            audioCallback, &(lpJniStorage->mCallbackData),//callback, callback data (user));
            0,// notificationFrames == 0 since not using EVENT_MORE_DATA to feed the AudioTrack
            lpJniStorage->mMemBase,// shared mem
            true,// thread can call Java
            sessionId);// audio session ID
        break;

    default:
        ALOGE("Unknown mode %d", memoryMode);
        goto native_init_failure;
    }

    if (lpTrack->initCheck() != NO_ERROR) {
        ALOGE("Error initializing AudioTrack");
        goto native_init_failure;
    }

    nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioTrack: Error retrieving session id pointer");
        goto native_init_failure;
    }
    // read the audio session ID back from AudioTrack in case we create a new session
    nSession[0] = lpTrack->getSessionId();
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    {   // scope for the lock
        Mutex::Autolock l(sLock);
        sAudioTrackCallBackCookies.add(&lpJniStorage->mCallbackData);
    }
    // save our newly created C++ AudioTrack in the "nativeTrackInJavaObj" field
    // of the Java object (in mNativeTrackInJavaObj)
    setAudioTrack(env, thiz, lpTrack);

    // save the JNI resources so we can free them later
    //ALOGV("storing lpJniStorage: %x\n", (int)lpJniStorage);
    env->SetIntField(thiz, javaAudioTrackFields.jniData, (int)lpJniStorage);

    return AUDIOTRACK_SUCCESS;

    // failures:
native_init_failure:
    if (nSession != NULL) {
        env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    }
    env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioTrack_class);
    env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioTrack_ref);
    delete lpJniStorage;
    env->SetIntField(thiz, javaAudioTrackFields.jniData, 0);

    return AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED;
}


// ----------------------------------------------------------------------------
static void
android_media_AudioTrack_start(JNIEnv *env, jobject thiz)
{
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for start()");
        return;
    }

    lpTrack->start();
}


// ----------------------------------------------------------------------------
static void
android_media_AudioTrack_stop(JNIEnv *env, jobject thiz)
{
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for stop()");
        return;
    }

    lpTrack->stop();
}


// ----------------------------------------------------------------------------
static void
android_media_AudioTrack_pause(JNIEnv *env, jobject thiz)
{
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for pause()");
        return;
    }

    lpTrack->pause();
}


// ----------------------------------------------------------------------------
static void
android_media_AudioTrack_flush(JNIEnv *env, jobject thiz)
{
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for flush()");
        return;
    }

    lpTrack->flush();
}

// ----------------------------------------------------------------------------
static void
android_media_AudioTrack_set_volume(JNIEnv *env, jobject thiz, jfloat leftVol, jfloat rightVol )
{
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setVolume()");
        return;
    }

    lpTrack->setVolume(leftVol, rightVol);
}

// ----------------------------------------------------------------------------

#define CALLBACK_COND_WAIT_TIMEOUT_MS 1000
static void android_media_AudioTrack_native_release(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = setAudioTrack(env, thiz, 0);
    if (lpTrack == NULL) {
        return;
    }
    //ALOGV("deleting lpTrack: %x\n", (int)lpTrack);
    lpTrack->stop();

    // delete the JNI data
    AudioTrackJniStorage* pJniStorage = (AudioTrackJniStorage *)env->GetIntField(
        thiz, javaAudioTrackFields.jniData);
    // reset the native resources in the Java object so any attempt to access
    // them after a call to release fails.
    env->SetIntField(thiz, javaAudioTrackFields.jniData, 0);

    if (pJniStorage) {
        Mutex::Autolock l(sLock);
        audiotrack_callback_cookie *lpCookie = &pJniStorage->mCallbackData;
        //ALOGV("deleting pJniStorage: %x\n", (int)pJniStorage);
        while (lpCookie->busy) {
            if (lpCookie->cond.waitRelative(sLock,
                                            milliseconds(CALLBACK_COND_WAIT_TIMEOUT_MS)) !=
                                                    NO_ERROR) {
                break;
            }
        }
        sAudioTrackCallBackCookies.remove(lpCookie);
        // delete global refs created in native_setup
        env->DeleteGlobalRef(lpCookie->audioTrack_class);
        env->DeleteGlobalRef(lpCookie->audioTrack_ref);
        delete pJniStorage;
    }
}


// ----------------------------------------------------------------------------
static void android_media_AudioTrack_native_finalize(JNIEnv *env,  jobject thiz) {
    //ALOGV("android_media_AudioTrack_native_finalize jobject: %x\n", (int)thiz);
    android_media_AudioTrack_native_release(env, thiz);
}

// ----------------------------------------------------------------------------
jint writeToTrack(const sp<AudioTrack>& track, jint audioFormat, jbyte* data,
                  jint offsetInBytes, jint sizeInBytes) {
    // give the data to the native AudioTrack object (the data starts at the offset)
    ssize_t written = 0;
    // regular write() or copy the data to the AudioTrack's shared memory?
    if (track->sharedBuffer() == 0) {
        written = track->write(data + offsetInBytes, sizeInBytes);
        // for compatibility with earlier behavior of write(), return 0 in this case
        if (written == (ssize_t) WOULD_BLOCK) {
            written = 0;
        }
    } else {
#ifdef QCOM_HARDWARE
        if ((audioFormat == ENCODING_PCM_16BIT)
        || (audioFormat == ENCODING_AMRNB)
        || (audioFormat == ENCODING_AMRWB)
        || (audioFormat == ENCODING_EVRC)
        || (audioFormat == ENCODING_EVRCB)
        || (audioFormat == ENCODING_EVRCWB)
        || (audioFormat == ENCODING_EVRCNW)) {
#else
        if (audioFormat == ENCODING_PCM_16BIT) {
#endif
            // writing to shared memory, check for capacity
            if ((size_t)sizeInBytes > track->sharedBuffer()->size()) {
                sizeInBytes = track->sharedBuffer()->size();
            }
            memcpy(track->sharedBuffer()->pointer(), data + offsetInBytes, sizeInBytes);
            written = sizeInBytes;
        } else if (audioFormat == ENCODING_PCM_8BIT) {
            // data contains 8bit data we need to expand to 16bit before copying
            // to the shared memory
            // writing to shared memory, check for capacity,
            // note that input data will occupy 2X the input space due to 8 to 16bit conversion
            if (((size_t)sizeInBytes)*2 > track->sharedBuffer()->size()) {
                sizeInBytes = track->sharedBuffer()->size() / 2;
            }
            int count = sizeInBytes;
            int16_t *dst = (int16_t *)track->sharedBuffer()->pointer();
            const int8_t *src = (const int8_t *)(data + offsetInBytes);
            while (count--) {
                *dst++ = (int16_t)(*src++^0x80) << 8;
            }
            // even though we wrote 2*sizeInBytes, we only report sizeInBytes as written to hide
            // the 8bit mixer restriction from the user of this function
            written = sizeInBytes;
        }
    }
    return written;

}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_native_write_byte(JNIEnv *env,  jobject thiz,
                                                  jbyteArray javaAudioData,
                                                  jint offsetInBytes, jint sizeInBytes,
                                                  jint javaAudioFormat) {
    //ALOGV("android_media_AudioTrack_native_write_byte(offset=%d, sizeInBytes=%d) called",
    //    offsetInBytes, sizeInBytes);
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for write()");
        return 0;
    }

    // get the pointer for the audio data from the java array
    // NOTE: We may use GetPrimitiveArrayCritical() when the JNI implementation changes in such
    // a way that it becomes much more efficient. When doing so, we will have to prevent the
    // AudioSystem callback to be called while in critical section (in case of media server
    // process crash for instance)
    jbyte* cAudioData = NULL;
    if (javaAudioData) {
        cAudioData = (jbyte *)env->GetByteArrayElements(javaAudioData, NULL);
        if (cAudioData == NULL) {
            ALOGE("Error retrieving source of audio data to play, can't play");
            return 0; // out of memory or no data to load
        }
    } else {
        ALOGE("NULL java array of audio data to play, can't play");
        return 0;
    }

    jint written = writeToTrack(lpTrack, javaAudioFormat, cAudioData, offsetInBytes, sizeInBytes);

    env->ReleaseByteArrayElements(javaAudioData, cAudioData, 0);

    //ALOGV("write wrote %d (tried %d) bytes in the native AudioTrack with offset %d",
    //     (int)written, (int)(sizeInBytes), (int)offsetInBytes);
    return written;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_native_write_short(JNIEnv *env,  jobject thiz,
                                                  jshortArray javaAudioData,
                                                  jint offsetInShorts, jint sizeInShorts,
                                                  jint javaAudioFormat) {
    jint written = android_media_AudioTrack_native_write_byte(env, thiz,
                                                 (jbyteArray) javaAudioData,
                                                 offsetInShorts*2, sizeInShorts*2,
                                                 javaAudioFormat);
    if (written > 0) {
        written /= 2;
    }
    return written;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_native_frame_count(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for frameCount()");
        return AUDIOTRACK_ERROR;
    }

    return lpTrack->frameCount();
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_set_playback_rate(JNIEnv *env,  jobject thiz,
        jint sampleRateInHz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setSampleRate()");
        return AUDIOTRACK_ERROR;
    }
    return android_media_translateErrorCode(lpTrack->setSampleRate(sampleRateInHz));
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_playback_rate(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getSampleRate()");
        return AUDIOTRACK_ERROR;
    }
    return (jint) lpTrack->getSampleRate();
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_set_marker_pos(JNIEnv *env,  jobject thiz,
        jint markerPos) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setMarkerPosition()");
        return AUDIOTRACK_ERROR;
    }
    return android_media_translateErrorCode( lpTrack->setMarkerPosition(markerPos) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_marker_pos(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    uint32_t markerPos = 0;

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getMarkerPosition()");
        return AUDIOTRACK_ERROR;
    }
    lpTrack->getMarkerPosition(&markerPos);
    return (jint)markerPos;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_set_pos_update_period(JNIEnv *env,  jobject thiz,
        jint period) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setPositionUpdatePeriod()");
        return AUDIOTRACK_ERROR;
    }
    return android_media_translateErrorCode( lpTrack->setPositionUpdatePeriod(period) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_pos_update_period(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    uint32_t period = 0;

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getPositionUpdatePeriod()");
        return AUDIOTRACK_ERROR;
    }
    lpTrack->getPositionUpdatePeriod(&period);
    return (jint)period;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_set_position(JNIEnv *env,  jobject thiz,
        jint position) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setPosition()");
        return AUDIOTRACK_ERROR;
    }
    return android_media_translateErrorCode( lpTrack->setPosition(position) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_position(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    uint32_t position = 0;

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getPosition()");
        return AUDIOTRACK_ERROR;
    }
    lpTrack->getPosition(&position);
    return (jint)position;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_latency(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for latency()");
        return AUDIOTRACK_ERROR;
    }
    return (jint)lpTrack->latency();
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_timestamp(JNIEnv *env,  jobject thiz, jlongArray jTimestamp) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);

    if (lpTrack == NULL) {
        ALOGE("Unable to retrieve AudioTrack pointer for getTimestamp()");
        return AUDIOTRACK_ERROR;
    }
    AudioTimestamp timestamp;
    status_t status = lpTrack->getTimestamp(timestamp);
    if (status == OK) {
        jlong* nTimestamp = (jlong *) env->GetPrimitiveArrayCritical(jTimestamp, NULL);
        if (nTimestamp == NULL) {
            ALOGE("Unable to get array for getTimestamp()");
            return AUDIOTRACK_ERROR;
        }
        nTimestamp[0] = (jlong) timestamp.mPosition;
        nTimestamp[1] = (jlong) ((timestamp.mTime.tv_sec * 1000000000LL) + timestamp.mTime.tv_nsec);
        env->ReleasePrimitiveArrayCritical(jTimestamp, nTimestamp, 0);
    }
    return (jint) android_media_translateErrorCode(status);
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_set_loop(JNIEnv *env,  jobject thiz,
        jint loopStart, jint loopEnd, jint loopCount) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setLoop()");
        return AUDIOTRACK_ERROR;
    }
    return android_media_translateErrorCode( lpTrack->setLoop(loopStart, loopEnd, loopCount) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_reload(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for reload()");
        return AUDIOTRACK_ERROR;
    }
    return android_media_translateErrorCode( lpTrack->reload() );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_output_sample_rate(JNIEnv *env,  jobject thiz,
        jint javaStreamType) {
    uint32_t afSamplingRate;
    // convert the stream type from Java to native value
    // FIXME: code duplication with android_media_AudioTrack_native_setup()
    audio_stream_type_t nativeStreamType;
    switch (javaStreamType) {
    case AUDIO_STREAM_VOICE_CALL:
    case AUDIO_STREAM_SYSTEM:
    case AUDIO_STREAM_RING:
    case AUDIO_STREAM_MUSIC:
    case AUDIO_STREAM_ALARM:
    case AUDIO_STREAM_NOTIFICATION:
    case AUDIO_STREAM_BLUETOOTH_SCO:
    case AUDIO_STREAM_DTMF:
#ifdef QCOM_HARDWARE
    case AUDIO_STREAM_INCALL_MUSIC:
#endif
        nativeStreamType = (audio_stream_type_t) javaStreamType;
        break;
    default:
        nativeStreamType = AUDIO_STREAM_DEFAULT;
        break;
    }

    if (AudioSystem::getOutputSamplingRate(&afSamplingRate, nativeStreamType) != NO_ERROR) {
        ALOGE("AudioSystem::getOutputSamplingRate() for stream type %d failed in AudioTrack JNI",
            nativeStreamType);
        return DEFAULT_OUTPUT_SAMPLE_RATE;
    } else {
        return afSamplingRate;
    }
}


// ----------------------------------------------------------------------------
// returns the minimum required size for the successful creation of a streaming AudioTrack
// returns -1 if there was an error querying the hardware.
static jint android_media_AudioTrack_get_min_buff_size(JNIEnv *env,  jobject thiz,
    jint sampleRateInHertz, jint nbChannels, jint audioFormat) {

    size_t frameCount = 0;
    if (AudioTrack::getMinFrameCount(&frameCount, AUDIO_STREAM_DEFAULT,
            sampleRateInHertz) != NO_ERROR) {
        return -1;
    }
    return frameCount * nbChannels * (audioFormat == ENCODING_PCM_16BIT ? 2 : 1);
}

// ----------------------------------------------------------------------------
static void
android_media_AudioTrack_setAuxEffectSendLevel(JNIEnv *env, jobject thiz, jfloat level )
{
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setAuxEffectSendLevel()");
        return;
    }

    lpTrack->setAuxEffectSendLevel(level);
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_attachAuxEffect(JNIEnv *env,  jobject thiz,
        jint effectId) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for attachAuxEffect()");
        return AUDIOTRACK_ERROR;
    }
    return android_media_translateErrorCode( lpTrack->attachAuxEffect(effectId) );
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static JNINativeMethod gMethods[] = {
    // name,              signature,     funcPtr
    {"native_start",         "()V",      (void *)android_media_AudioTrack_start},
    {"native_stop",          "()V",      (void *)android_media_AudioTrack_stop},
    {"native_pause",         "()V",      (void *)android_media_AudioTrack_pause},
    {"native_flush",         "()V",      (void *)android_media_AudioTrack_flush},
    {"native_setup",         "(Ljava/lang/Object;IIIIII[I)I",
                                         (void *)android_media_AudioTrack_native_setup},
    {"native_finalize",      "()V",      (void *)android_media_AudioTrack_native_finalize},
    {"native_release",       "()V",      (void *)android_media_AudioTrack_native_release},
    {"native_write_byte",    "([BIII)I", (void *)android_media_AudioTrack_native_write_byte},
    {"native_write_short",   "([SIII)I", (void *)android_media_AudioTrack_native_write_short},
    {"native_setVolume",     "(FF)V",    (void *)android_media_AudioTrack_set_volume},
    {"native_get_native_frame_count",
                             "()I",      (void *)android_media_AudioTrack_get_native_frame_count},
    {"native_set_playback_rate",
                             "(I)I",     (void *)android_media_AudioTrack_set_playback_rate},
    {"native_get_playback_rate",
                             "()I",      (void *)android_media_AudioTrack_get_playback_rate},
    {"native_set_marker_pos","(I)I",     (void *)android_media_AudioTrack_set_marker_pos},
    {"native_get_marker_pos","()I",      (void *)android_media_AudioTrack_get_marker_pos},
    {"native_set_pos_update_period",
                             "(I)I",     (void *)android_media_AudioTrack_set_pos_update_period},
    {"native_get_pos_update_period",
                             "()I",      (void *)android_media_AudioTrack_get_pos_update_period},
    {"native_set_position",  "(I)I",     (void *)android_media_AudioTrack_set_position},
    {"native_get_position",  "()I",      (void *)android_media_AudioTrack_get_position},
    {"native_get_latency",   "()I",      (void *)android_media_AudioTrack_get_latency},
    {"native_get_timestamp", "([J)I",    (void *)android_media_AudioTrack_get_timestamp},
    {"native_set_loop",      "(III)I",   (void *)android_media_AudioTrack_set_loop},
    {"native_reload_static", "()I",      (void *)android_media_AudioTrack_reload},
    {"native_get_output_sample_rate",
                             "(I)I",      (void *)android_media_AudioTrack_get_output_sample_rate},
    {"native_get_min_buff_size",
                             "(III)I",   (void *)android_media_AudioTrack_get_min_buff_size},
    {"native_setAuxEffectSendLevel",
                             "(F)V",     (void *)android_media_AudioTrack_setAuxEffectSendLevel},
    {"native_attachAuxEffect",
                             "(I)I",     (void *)android_media_AudioTrack_attachAuxEffect},
};


// field names found in android/media/AudioTrack.java
#define JAVA_POSTEVENT_CALLBACK_NAME                    "postEventFromNative"
#define JAVA_NATIVETRACKINJAVAOBJ_FIELD_NAME            "mNativeTrackInJavaObj"
#define JAVA_JNIDATA_FIELD_NAME                         "mJniData"

// ----------------------------------------------------------------------------
// preconditions:
//    theClass is valid
bool android_media_getIntConstantFromClass(JNIEnv* pEnv, jclass theClass, const char* className,
                             const char* constName, int* constVal) {
    jfieldID javaConst = NULL;
    javaConst = pEnv->GetStaticFieldID(theClass, constName, "I");
    if (javaConst != NULL) {
        *constVal = pEnv->GetStaticIntField(theClass, javaConst);
        return true;
    } else {
        ALOGE("Can't find %s.%s", className, constName);
        return false;
    }
}


// ----------------------------------------------------------------------------
int register_android_media_AudioTrack(JNIEnv *env)
{
    javaAudioTrackFields.nativeTrackInJavaObj = NULL;
    javaAudioTrackFields.postNativeEventInJava = NULL;

    // Get the AudioTrack class
    jclass audioTrackClass = env->FindClass(kClassPathName);
    if (audioTrackClass == NULL) {
        ALOGE("Can't find %s", kClassPathName);
        return -1;
    }

    // Get the postEvent method
    javaAudioTrackFields.postNativeEventInJava = env->GetStaticMethodID(
            audioTrackClass,
            JAVA_POSTEVENT_CALLBACK_NAME, "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (javaAudioTrackFields.postNativeEventInJava == NULL) {
        ALOGE("Can't find AudioTrack.%s", JAVA_POSTEVENT_CALLBACK_NAME);
        return -1;
    }

    // Get the variables fields
    //      nativeTrackInJavaObj
    javaAudioTrackFields.nativeTrackInJavaObj = env->GetFieldID(
            audioTrackClass,
            JAVA_NATIVETRACKINJAVAOBJ_FIELD_NAME, "I");
    if (javaAudioTrackFields.nativeTrackInJavaObj == NULL) {
        ALOGE("Can't find AudioTrack.%s", JAVA_NATIVETRACKINJAVAOBJ_FIELD_NAME);
        return -1;
    }
    //      jniData;
    javaAudioTrackFields.jniData = env->GetFieldID(
            audioTrackClass,
            JAVA_JNIDATA_FIELD_NAME, "I");
    if (javaAudioTrackFields.jniData == NULL) {
        ALOGE("Can't find AudioTrack.%s", JAVA_JNIDATA_FIELD_NAME);
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}


// ----------------------------------------------------------------------------
