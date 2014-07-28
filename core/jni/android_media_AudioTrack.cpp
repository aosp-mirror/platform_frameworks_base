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

#define LOG_TAG "AudioTrack-JNI"

#include <JNIHelp.h>
#include <JniConstants.h>
#include <android_runtime/AndroidRuntime.h>

#include "ScopedBytes.h"

#include <utils/Log.h>
#include <media/AudioSystem.h>
#include <media/AudioTrack.h>
#include <audio_utils/primitives.h>

#include <binder/MemoryHeapBase.h>
#include <binder/MemoryBase.h>

#include "android_media_AudioFormat.h"
#include "android_media_AudioErrors.h"

// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/AudioTrack";
static const char* const kAudioAttributesClassPathName = "android/media/AudioAttributes";

struct audio_track_fields_t {
    // these fields provide access from C++ to the...
    jmethodID postNativeEventInJava; //... event post callback method
    jfieldID  nativeTrackInJavaObj;  // stores in Java the native AudioTrack object
    jfieldID  jniData;      // stores in Java additional resources used by the native AudioTrack
    jfieldID  fieldStreamType; // ... mStreamType field in the AudioTrack Java object
};
struct audio_attributes_fields_t {
    jfieldID  fieldUsage;        // AudioAttributes.mUsage
    jfieldID  fieldContentType;  // AudioAttributes.mContentType
    jfieldID  fieldFlags;        // AudioAttributes.mFlags
    jfieldID  fieldFormattedTags;// AudioAttributes.mFormattedTags
};
static audio_track_fields_t      javaAudioTrackFields;
static audio_attributes_fields_t javaAudioAttrFields;

struct audiotrack_callback_cookie {
    jclass      audioTrack_class;
    jobject     audioTrack_ref;
    bool        busy;
    Condition   cond;
};

// keep these values in sync with AudioTrack.java
#define MODE_STATIC 0
#define MODE_STREAM 1

// ----------------------------------------------------------------------------
class AudioTrackJniStorage {
    public:
        sp<MemoryHeapBase>         mMemHeap;
        sp<MemoryBase>             mMemBase;
        audiotrack_callback_cookie mCallbackData;

    AudioTrackJniStorage() {
        mCallbackData.audioTrack_class = 0;
        mCallbackData.audioTrack_ref = 0;
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

#define AUDIOTRACK_ERROR_SETUP_AUDIOSYSTEM         -16
#define AUDIOTRACK_ERROR_SETUP_INVALIDCHANNELMASK  -17
#define AUDIOTRACK_ERROR_SETUP_INVALIDFORMAT       -18
#define AUDIOTRACK_ERROR_SETUP_INVALIDSTREAMTYPE   -19
#define AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED    -20

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
            (AudioTrack*)env->GetLongField(thiz, javaAudioTrackFields.nativeTrackInJavaObj);
    return sp<AudioTrack>(at);
}

static sp<AudioTrack> setAudioTrack(JNIEnv* env, jobject thiz, const sp<AudioTrack>& at)
{
    Mutex::Autolock l(sLock);
    sp<AudioTrack> old =
            (AudioTrack*)env->GetLongField(thiz, javaAudioTrackFields.nativeTrackInJavaObj);
    if (at.get()) {
        at->incStrong((void*)setAudioTrack);
    }
    if (old != 0) {
        old->decStrong((void*)setAudioTrack);
    }
    env->SetLongField(thiz, javaAudioTrackFields.nativeTrackInJavaObj, (jlong)at.get());
    return old;
}
// ----------------------------------------------------------------------------
static jint
android_media_AudioTrack_setup(JNIEnv *env, jobject thiz, jobject weak_this,
        jobject jaa,
        jint sampleRateInHertz, jint javaChannelMask,
        jint audioFormat, jint buffSizeInBytes, jint memoryMode, jintArray jSession) {

    ALOGV("sampleRate=%d, audioFormat(from Java)=%d, channel mask=%x, buffSize=%d",
        sampleRateInHertz, audioFormat, javaChannelMask, buffSizeInBytes);

    if (jaa == 0) {
        ALOGE("Error creating AudioTrack: invalid audio attributes");
        return (jint) AUDIO_JAVA_ERROR;
    }

    // Java channel masks don't map directly to the native definition, but it's a simple shift
    // to skip the two deprecated channel configurations "default" and "mono".
    audio_channel_mask_t nativeChannelMask = ((uint32_t)javaChannelMask) >> 2;

    if (!audio_is_output_channel(nativeChannelMask)) {
        ALOGE("Error creating AudioTrack: invalid channel mask %#x.", javaChannelMask);
        return (jint) AUDIOTRACK_ERROR_SETUP_INVALIDCHANNELMASK;
    }

    uint32_t channelCount = audio_channel_count_from_out_mask(nativeChannelMask);

    // check the format.
    // This function was called from Java, so we compare the format against the Java constants
    audio_format_t format = audioFormatToNative(audioFormat);
    if (format == AUDIO_FORMAT_INVALID) {
        ALOGE("Error creating AudioTrack: unsupported audio format %d.", audioFormat);
        return (jint) AUDIOTRACK_ERROR_SETUP_INVALIDFORMAT;
    }

    // for the moment 8bitPCM in MODE_STATIC is not supported natively in the AudioTrack C++ class
    // so we declare everything as 16bitPCM, the 8->16bit conversion for MODE_STATIC will be handled
    // in android_media_AudioTrack_native_write_byte()
    if ((format == AUDIO_FORMAT_PCM_8_BIT)
        && (memoryMode == MODE_STATIC)) {
        ALOGV("android_media_AudioTrack_setup(): requesting MODE_STATIC for 8bit \
            buff size of %dbytes, switching to 16bit, buff size of %dbytes",
            buffSizeInBytes, 2*buffSizeInBytes);
        format = AUDIO_FORMAT_PCM_16_BIT;
        // we will need twice the memory to store the data
        buffSizeInBytes *= 2;
    }

    // compute the frame count
    size_t frameCount;
    if (audio_is_linear_pcm(format)) {
        const size_t bytesPerSample = audio_bytes_per_sample(format);
        frameCount = buffSizeInBytes / (channelCount * bytesPerSample);
    } else {
        frameCount = buffSizeInBytes;
    }

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find %s when setting up callback.", kClassPathName);
        return (jint) AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED;
    }

    if (jSession == NULL) {
        ALOGE("Error creating AudioTrack: invalid session ID pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }

    jint* nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioTrack: Error retrieving session id pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }
    int sessionId = nSession[0];
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    // create the native AudioTrack object
    sp<AudioTrack> lpTrack = new AudioTrack();

    audio_attributes_t *paa = NULL;
    // read the AudioAttributes values
    paa = (audio_attributes_t *) calloc(1, sizeof(audio_attributes_t));
    const jstring jtags =
            (jstring) env->GetObjectField(jaa, javaAudioAttrFields.fieldFormattedTags);
    const char* tags = env->GetStringUTFChars(jtags, NULL);
    // copying array size -1, char array for tags was calloc'd, no need to NULL-terminate it
    strncpy(paa->tags, tags, AUDIO_ATTRIBUTES_TAGS_MAX_SIZE - 1);
    env->ReleaseStringUTFChars(jtags, tags);
    paa->usage = (audio_usage_t) env->GetIntField(jaa, javaAudioAttrFields.fieldUsage);
    paa->content_type =
            (audio_content_type_t) env->GetIntField(jaa, javaAudioAttrFields.fieldContentType);
    paa->flags = env->GetIntField(jaa, javaAudioAttrFields.fieldFlags);

    ALOGV("AudioTrack_setup for usage=%d content=%d flags=0x%#x tags=%s",
            paa->usage, paa->content_type, paa->flags, paa->tags);

    // initialize the callback information:
    // this data will be passed with every AudioTrack callback
    AudioTrackJniStorage* lpJniStorage = new AudioTrackJniStorage();
    lpJniStorage->mCallbackData.audioTrack_class = (jclass)env->NewGlobalRef(clazz);
    // we use a weak reference so the AudioTrack object can be garbage collected.
    lpJniStorage->mCallbackData.audioTrack_ref = env->NewGlobalRef(weak_this);
    lpJniStorage->mCallbackData.busy = false;

    // initialize the native AudioTrack object
    status_t status = NO_ERROR;
    switch (memoryMode) {
    case MODE_STREAM:

        status = lpTrack->set(
                AUDIO_STREAM_DEFAULT,// stream type, but more info conveyed in paa (last argument)
                sampleRateInHertz,
                format,// word length, PCM
                nativeChannelMask,
                frameCount,
                AUDIO_OUTPUT_FLAG_NONE,
                audioCallback, &(lpJniStorage->mCallbackData),//callback, callback data (user)
                0,// notificationFrames == 0 since not using EVENT_MORE_DATA to feed the AudioTrack
                0,// shared mem
                true,// thread can call Java
                sessionId,// audio session ID
                AudioTrack::TRANSFER_SYNC,
                NULL,                         // default offloadInfo
                -1, -1,                       // default uid, pid values
                paa);
        break;

    case MODE_STATIC:
        // AudioTrack is using shared memory

        if (!lpJniStorage->allocSharedMem(buffSizeInBytes)) {
            ALOGE("Error creating AudioTrack in static mode: error creating mem heap base");
            goto native_init_failure;
        }

        status = lpTrack->set(
                AUDIO_STREAM_DEFAULT,// stream type, but more info conveyed in paa (last argument)
                sampleRateInHertz,
                format,// word length, PCM
                nativeChannelMask,
                frameCount,
                AUDIO_OUTPUT_FLAG_NONE,
                audioCallback, &(lpJniStorage->mCallbackData),//callback, callback data (user));
                0,// notificationFrames == 0 since not using EVENT_MORE_DATA to feed the AudioTrack
                lpJniStorage->mMemBase,// shared mem
                true,// thread can call Java
                sessionId,// audio session ID
                AudioTrack::TRANSFER_SHARED,
                NULL,                         // default offloadInfo
                -1, -1,                       // default uid, pid values
                paa);
        break;

    default:
        ALOGE("Unknown mode %d", memoryMode);
        goto native_init_failure;
    }

    if (status != NO_ERROR) {
        ALOGE("Error %d initializing AudioTrack", status);
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
    //ALOGV("storing lpJniStorage: %x\n", (long)lpJniStorage);
    env->SetLongField(thiz, javaAudioTrackFields.jniData, (jlong)lpJniStorage);

    // since we had audio attributes, the stream type was derived from them during the
    // creation of the native AudioTrack: push the same value to the Java object
    env->SetIntField(thiz, javaAudioTrackFields.fieldStreamType, (jint) lpTrack->streamType());
    // audio attributes were copied in AudioTrack creation
    free(paa);
    paa = NULL;


    return (jint) AUDIO_JAVA_SUCCESS;

    // failures:
native_init_failure:
    if (paa != NULL) {
        free(paa);
    }
    if (nSession != NULL) {
        env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    }
    env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioTrack_class);
    env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioTrack_ref);
    delete lpJniStorage;
    env->SetLongField(thiz, javaAudioTrackFields.jniData, 0);

    return (jint) AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED;
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
static void android_media_AudioTrack_release(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = setAudioTrack(env, thiz, 0);
    if (lpTrack == NULL) {
        return;
    }
    //ALOGV("deleting lpTrack: %x\n", (int)lpTrack);
    lpTrack->stop();

    // delete the JNI data
    AudioTrackJniStorage* pJniStorage = (AudioTrackJniStorage *)env->GetLongField(
        thiz, javaAudioTrackFields.jniData);
    // reset the native resources in the Java object so any attempt to access
    // them after a call to release fails.
    env->SetLongField(thiz, javaAudioTrackFields.jniData, 0);

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
static void android_media_AudioTrack_finalize(JNIEnv *env,  jobject thiz) {
    //ALOGV("android_media_AudioTrack_finalize jobject: %x\n", (int)thiz);
    android_media_AudioTrack_release(env, thiz);
}

// ----------------------------------------------------------------------------
jint writeToTrack(const sp<AudioTrack>& track, jint audioFormat, const jbyte* data,
                  jint offsetInBytes, jint sizeInBytes, bool blocking = true) {
    // give the data to the native AudioTrack object (the data starts at the offset)
    ssize_t written = 0;
    // regular write() or copy the data to the AudioTrack's shared memory?
    if (track->sharedBuffer() == 0) {
        written = track->write(data + offsetInBytes, sizeInBytes, blocking);
        // for compatibility with earlier behavior of write(), return 0 in this case
        if (written == (ssize_t) WOULD_BLOCK) {
            written = 0;
        }
    } else {
        const audio_format_t format = audioFormatToNative(audioFormat);
        switch (format) {

        default:
        case AUDIO_FORMAT_PCM_FLOAT:
        case AUDIO_FORMAT_PCM_16_BIT: {
            // writing to shared memory, check for capacity
            if ((size_t)sizeInBytes > track->sharedBuffer()->size()) {
                sizeInBytes = track->sharedBuffer()->size();
            }
            memcpy(track->sharedBuffer()->pointer(), data + offsetInBytes, sizeInBytes);
            written = sizeInBytes;
            } break;

        case AUDIO_FORMAT_PCM_8_BIT: {
            // data contains 8bit data we need to expand to 16bit before copying
            // to the shared memory
            // writing to shared memory, check for capacity,
            // note that input data will occupy 2X the input space due to 8 to 16bit conversion
            if (((size_t)sizeInBytes)*2 > track->sharedBuffer()->size()) {
                sizeInBytes = track->sharedBuffer()->size() / 2;
            }
            int count = sizeInBytes;
            int16_t *dst = (int16_t *)track->sharedBuffer()->pointer();
            const uint8_t *src = (const uint8_t *)(data + offsetInBytes);
            memcpy_to_i16_from_u8(dst, src, count);
            // even though we wrote 2*sizeInBytes, we only report sizeInBytes as written to hide
            // the 8bit mixer restriction from the user of this function
            written = sizeInBytes;
            } break;

        }
    }
    return written;

}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_write_byte(JNIEnv *env,  jobject thiz,
                                                  jbyteArray javaAudioData,
                                                  jint offsetInBytes, jint sizeInBytes,
                                                  jint javaAudioFormat,
                                                  jboolean isWriteBlocking) {
    //ALOGV("android_media_AudioTrack_write_byte(offset=%d, sizeInBytes=%d) called",
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

    jint written = writeToTrack(lpTrack, javaAudioFormat, cAudioData, offsetInBytes, sizeInBytes,
            isWriteBlocking == JNI_TRUE /* blocking */);

    env->ReleaseByteArrayElements(javaAudioData, cAudioData, 0);

    //ALOGV("write wrote %d (tried %d) bytes in the native AudioTrack with offset %d",
    //     (int)written, (int)(sizeInBytes), (int)offsetInBytes);
    return written;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_write_native_bytes(JNIEnv *env,  jobject thiz,
        jbyteArray javaBytes, jint byteOffset, jint sizeInBytes,
        jint javaAudioFormat, jboolean isWriteBlocking) {
    //ALOGV("android_media_AudioTrack_write_native_bytes(offset=%d, sizeInBytes=%d) called",
    //    offsetInBytes, sizeInBytes);
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Unable to retrieve AudioTrack pointer for write()");
        return 0;
    }

    ScopedBytesRO bytes(env, javaBytes);
    if (bytes.get() == NULL) {
        ALOGE("Error retrieving source of audio data to play, can't play");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    jint written = writeToTrack(lpTrack, javaAudioFormat, bytes.get(), byteOffset,
            sizeInBytes, isWriteBlocking == JNI_TRUE /* blocking */);

    return written;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_write_short(JNIEnv *env,  jobject thiz,
                                                  jshortArray javaAudioData,
                                                  jint offsetInShorts, jint sizeInShorts,
                                                  jint javaAudioFormat) {

    //ALOGV("android_media_AudioTrack_write_short(offset=%d, sizeInShorts=%d) called",
    //    offsetInShorts, sizeInShorts);
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
    jshort* cAudioData = NULL;
    if (javaAudioData) {
        cAudioData = (jshort *)env->GetShortArrayElements(javaAudioData, NULL);
        if (cAudioData == NULL) {
            ALOGE("Error retrieving source of audio data to play, can't play");
            return 0; // out of memory or no data to load
        }
    } else {
        ALOGE("NULL java array of audio data to play, can't play");
        return 0;
    }
    jint written = writeToTrack(lpTrack, javaAudioFormat, (jbyte *)cAudioData,
                                offsetInShorts * sizeof(short), sizeInShorts * sizeof(short),
            true /*blocking write, legacy behavior*/);
    env->ReleaseShortArrayElements(javaAudioData, cAudioData, 0);

    if (written > 0) {
        written /= sizeof(short);
    }
    //ALOGV("write wrote %d (tried %d) shorts in the native AudioTrack with offset %d",
    //     (int)written, (int)(sizeInShorts), (int)offsetInShorts);

    return written;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_write_float(JNIEnv *env,  jobject thiz,
                                                  jfloatArray javaAudioData,
                                                  jint offsetInFloats, jint sizeInFloats,
                                                  jint javaAudioFormat,
                                                  jboolean isWriteBlocking) {

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for write()");
        return 0;
    }

    jfloat* cAudioData = NULL;
    if (javaAudioData) {
        cAudioData = (jfloat *)env->GetFloatArrayElements(javaAudioData, NULL);
        if (cAudioData == NULL) {
            ALOGE("Error retrieving source of audio data to play, can't play");
            return 0; // out of memory or no data to load
        }
    } else {
        ALOGE("NULL java array of audio data to play, can't play");
        return 0;
    }
    jint written = writeToTrack(lpTrack, javaAudioFormat, (jbyte *)cAudioData,
                                offsetInFloats * sizeof(float), sizeInFloats * sizeof(float),
                                isWriteBlocking == JNI_TRUE /* blocking */);
    env->ReleaseFloatArrayElements(javaAudioData, cAudioData, 0);

    if (written > 0) {
        written /= sizeof(float);
    }

    return written;
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_native_frame_count(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for frameCount()");
        return (jint)AUDIO_JAVA_ERROR;
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
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus(lpTrack->setSampleRate(sampleRateInHz));
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_playback_rate(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getSampleRate()");
        return (jint)AUDIO_JAVA_ERROR;
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
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus( lpTrack->setMarkerPosition(markerPos) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_marker_pos(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    uint32_t markerPos = 0;

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getMarkerPosition()");
        return (jint)AUDIO_JAVA_ERROR;
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
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus( lpTrack->setPositionUpdatePeriod(period) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_pos_update_period(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    uint32_t period = 0;

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getPositionUpdatePeriod()");
        return (jint)AUDIO_JAVA_ERROR;
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
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus( lpTrack->setPosition(position) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_position(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    uint32_t position = 0;

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getPosition()");
        return (jint)AUDIO_JAVA_ERROR;
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
        return (jint)AUDIO_JAVA_ERROR;
    }
    return (jint)lpTrack->latency();
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_timestamp(JNIEnv *env,  jobject thiz, jlongArray jTimestamp) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);

    if (lpTrack == NULL) {
        ALOGE("Unable to retrieve AudioTrack pointer for getTimestamp()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    AudioTimestamp timestamp;
    status_t status = lpTrack->getTimestamp(timestamp);
    if (status == OK) {
        jlong* nTimestamp = (jlong *) env->GetPrimitiveArrayCritical(jTimestamp, NULL);
        if (nTimestamp == NULL) {
            ALOGE("Unable to get array for getTimestamp()");
            return (jint)AUDIO_JAVA_ERROR;
        }
        nTimestamp[0] = (jlong) timestamp.mPosition;
        nTimestamp[1] = (jlong) ((timestamp.mTime.tv_sec * 1000000000LL) + timestamp.mTime.tv_nsec);
        env->ReleasePrimitiveArrayCritical(jTimestamp, nTimestamp, 0);
    }
    return (jint) nativeToJavaStatus(status);
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_set_loop(JNIEnv *env,  jobject thiz,
        jint loopStart, jint loopEnd, jint loopCount) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setLoop()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus( lpTrack->setLoop(loopStart, loopEnd, loopCount) );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_reload(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for reload()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus( lpTrack->reload() );
}


// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_output_sample_rate(JNIEnv *env,  jobject thiz,
        jint javaStreamType) {
    uint32_t afSamplingRate;
    // convert the stream type from Java to native value
    // FIXME: code duplication with android_media_AudioTrack_setup()
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
        nativeStreamType = (audio_stream_type_t) javaStreamType;
        break;
    default:
        nativeStreamType = AUDIO_STREAM_DEFAULT;
        break;
    }

    status_t status = AudioSystem::getOutputSamplingRate(&afSamplingRate, nativeStreamType);
    if (status != NO_ERROR) {
        ALOGE("Error %d in AudioSystem::getOutputSamplingRate() for stream type %d "
              "in AudioTrack JNI", status, nativeStreamType);
        return DEFAULT_OUTPUT_SAMPLE_RATE;
    } else {
        return afSamplingRate;
    }
}


// ----------------------------------------------------------------------------
// returns the minimum required size for the successful creation of a streaming AudioTrack
// returns -1 if there was an error querying the hardware.
static jint android_media_AudioTrack_get_min_buff_size(JNIEnv *env,  jobject thiz,
    jint sampleRateInHertz, jint channelCount, jint audioFormat) {

    size_t frameCount;
    const status_t status = AudioTrack::getMinFrameCount(&frameCount, AUDIO_STREAM_DEFAULT,
            sampleRateInHertz);
    if (status != NO_ERROR) {
        ALOGE("AudioTrack::getMinFrameCount() for sample rate %d failed with status %d",
                sampleRateInHertz, status);
        return -1;
    }
    const audio_format_t format = audioFormatToNative(audioFormat);
    if (audio_is_linear_pcm(format)) {
        const size_t bytesPerSample = audio_bytes_per_sample(format);
        return frameCount * channelCount * bytesPerSample;
    } else {
        return frameCount;
    }
}

// ----------------------------------------------------------------------------
static jint
android_media_AudioTrack_setAuxEffectSendLevel(JNIEnv *env, jobject thiz, jfloat level )
{
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setAuxEffectSendLevel()");
        return -1;
    }

    status_t status = lpTrack->setAuxEffectSendLevel(level);
    if (status != NO_ERROR) {
        ALOGE("AudioTrack::setAuxEffectSendLevel() for level %g failed with status %d",
                level, status);
    }
    return (jint) status;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_attachAuxEffect(JNIEnv *env,  jobject thiz,
        jint effectId) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for attachAuxEffect()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    return nativeToJavaStatus( lpTrack->attachAuxEffect(effectId) );
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static JNINativeMethod gMethods[] = {
    // name,              signature,     funcPtr
    {"native_start",         "()V",      (void *)android_media_AudioTrack_start},
    {"native_stop",          "()V",      (void *)android_media_AudioTrack_stop},
    {"native_pause",         "()V",      (void *)android_media_AudioTrack_pause},
    {"native_flush",         "()V",      (void *)android_media_AudioTrack_flush},
    {"native_setup",     "(Ljava/lang/Object;Ljava/lang/Object;IIIII[I)I",
                                         (void *)android_media_AudioTrack_setup},
    {"native_finalize",      "()V",      (void *)android_media_AudioTrack_finalize},
    {"native_release",       "()V",      (void *)android_media_AudioTrack_release},
    {"native_write_byte",    "([BIIIZ)I",(void *)android_media_AudioTrack_write_byte},
    {"native_write_native_bytes",
                             "(Ljava/lang/Object;IIIZ)I",
                                         (void *)android_media_AudioTrack_write_native_bytes},
    {"native_write_short",   "([SIII)I", (void *)android_media_AudioTrack_write_short},
    {"native_write_float",   "([FIIIZ)I",(void *)android_media_AudioTrack_write_float},
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
                             "(F)I",     (void *)android_media_AudioTrack_setAuxEffectSendLevel},
    {"native_attachAuxEffect",
                             "(I)I",     (void *)android_media_AudioTrack_attachAuxEffect},
};


// field names found in android/media/AudioTrack.java
#define JAVA_POSTEVENT_CALLBACK_NAME                    "postEventFromNative"
#define JAVA_NATIVETRACKINJAVAOBJ_FIELD_NAME            "mNativeTrackInJavaObj"
#define JAVA_JNIDATA_FIELD_NAME                         "mJniData"
#define JAVA_STREAMTYPE_FIELD_NAME                      "mStreamType"

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
            JAVA_NATIVETRACKINJAVAOBJ_FIELD_NAME, "J");
    if (javaAudioTrackFields.nativeTrackInJavaObj == NULL) {
        ALOGE("Can't find AudioTrack.%s", JAVA_NATIVETRACKINJAVAOBJ_FIELD_NAME);
        return -1;
    }
    //      jniData
    javaAudioTrackFields.jniData = env->GetFieldID(
            audioTrackClass,
            JAVA_JNIDATA_FIELD_NAME, "J");
    if (javaAudioTrackFields.jniData == NULL) {
        ALOGE("Can't find AudioTrack.%s", JAVA_JNIDATA_FIELD_NAME);
        return -1;
    }
    //      fieldStreamType
    javaAudioTrackFields.fieldStreamType = env->GetFieldID(audioTrackClass,
            JAVA_STREAMTYPE_FIELD_NAME, "I");
    if (javaAudioTrackFields.fieldStreamType == NULL) {
        ALOGE("Can't find AudioTrack.%s", JAVA_STREAMTYPE_FIELD_NAME);
        return -1;
    }

    // Get the AudioAttributes class and fields
    jclass audioAttrClass = env->FindClass(kAudioAttributesClassPathName);
    if (audioAttrClass == NULL) {
        ALOGE("Can't find %s", kAudioAttributesClassPathName);
        return -1;
    }
    jclass audioAttributesClassRef = (jclass)env->NewGlobalRef(audioAttrClass);
    javaAudioAttrFields.fieldUsage = env->GetFieldID(audioAttributesClassRef, "mUsage", "I");
    javaAudioAttrFields.fieldContentType
                                   = env->GetFieldID(audioAttributesClassRef, "mContentType", "I");
    javaAudioAttrFields.fieldFlags = env->GetFieldID(audioAttributesClassRef, "mFlags", "I");
    javaAudioAttrFields.fieldFormattedTags =
            env->GetFieldID(audioAttributesClassRef, "mFormattedTags", "Ljava/lang/String;");
    env->DeleteGlobalRef(audioAttributesClassRef);
    if (javaAudioAttrFields.fieldUsage == NULL || javaAudioAttrFields.fieldContentType == NULL
            || javaAudioAttrFields.fieldFlags == NULL
            || javaAudioAttrFields.fieldFormattedTags == NULL) {
        ALOGE("Can't initialize AudioAttributes fields");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}


// ----------------------------------------------------------------------------
