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

#include "android_media_AudioTrack.h"

#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <utils/Log.h>
#include <media/AudioParameter.h>
#include <media/AudioSystem.h>
#include <media/AudioTrack.h>

#include <android-base/macros.h>
#include <binder/MemoryHeapBase.h>
#include <binder/MemoryBase.h>

#include "android_media_AudioFormat.h"
#include "android_media_AudioErrors.h"
#include "android_media_MediaMetricsJNI.h"
#include "android_media_PlaybackParams.h"
#include "android_media_DeviceCallback.h"
#include "android_media_VolumeShaper.h"
#include "android_media_AudioAttributes.h"

#include <cinttypes>

// ----------------------------------------------------------------------------

using namespace android;

using ::android::media::VolumeShaper;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/AudioTrack";

struct audio_track_fields_t {
    // these fields provide access from C++ to the...
    jmethodID postNativeEventInJava; //... event post callback method
    jfieldID  nativeTrackInJavaObj;  // stores in Java the native AudioTrack object
    jfieldID  jniData;      // stores in Java additional resources used by the native AudioTrack
    jfieldID  fieldStreamType; // ... mStreamType field in the AudioTrack Java object
};
static audio_track_fields_t      javaAudioTrackFields;
static PlaybackParams::fields_t gPlaybackParamsFields;
static VolumeShaperHelper::fields_t gVolumeShaperFields;

struct audiotrack_callback_cookie {
    jclass      audioTrack_class;
    jobject     audioTrack_ref;
    bool        busy;
    Condition   cond;
    bool        isOffload;
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
        sp<JNIDeviceCallback>      mDeviceCallback;

    AudioTrackJniStorage() {
        mCallbackData.audioTrack_class = 0;
        mCallbackData.audioTrack_ref = 0;
        mCallbackData.isOffload = false;
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

#define AUDIOTRACK_ERROR_SETUP_AUDIOSYSTEM         (-16)
#define AUDIOTRACK_ERROR_SETUP_INVALIDCHANNELMASK  (-17)
#define AUDIOTRACK_ERROR_SETUP_INVALIDFORMAT       (-18)
#define AUDIOTRACK_ERROR_SETUP_INVALIDSTREAMTYPE   (-19)
#define AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED    (-20)

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

    // used as default argument when event callback doesn't have any, or number of
    // frames for EVENT_CAN_WRITE_MORE_DATA
    int arg = 0;
    bool postEvent = false;
    switch (event) {
    // Offload only events
    case AudioTrack::EVENT_CAN_WRITE_MORE_DATA:
        // this event will read the info return parameter of the callback:
        // for JNI offload, use the returned size to indicate:
        // 1/ no data is returned through callback, as it's all done through write()
        // 2/ do not wait as AudioTrack does when it receives 0 bytes
        if (callbackInfo->isOffload) {
            AudioTrack::Buffer* pBuffer = (AudioTrack::Buffer*) info;
            const size_t availableForWrite = pBuffer->size;
            arg = availableForWrite > INT32_MAX ? INT32_MAX : (int) availableForWrite;
            pBuffer->size = 0;
        }
        FALLTHROUGH_INTENDED;
    case AudioTrack::EVENT_STREAM_END:
    case AudioTrack::EVENT_NEW_IAUDIOTRACK: // a.k.a. tear down
        if (callbackInfo->isOffload) {
            postEvent = true;
        }
        break;

    // PCM and offload events
    case AudioTrack::EVENT_MARKER:
    case AudioTrack::EVENT_NEW_POS:
        postEvent = true;
        break;
    default:
        // event will not be posted
        break;
    }

    if (postEvent) {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        if (env != NULL) {
            env->CallStaticVoidMethod(
                    callbackInfo->audioTrack_class,
                    javaAudioTrackFields.postNativeEventInJava,
                    callbackInfo->audioTrack_ref, event, arg, 0, NULL);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
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
sp<AudioTrack> android_media_AudioTrack_getAudioTrack(JNIEnv* env, jobject audioTrackObj) {
    return getAudioTrack(env, audioTrackObj);
}

// ----------------------------------------------------------------------------
static jint
android_media_AudioTrack_setup(JNIEnv *env, jobject thiz, jobject weak_this, jobject jaa,
        jintArray jSampleRate, jint channelPositionMask, jint channelIndexMask,
        jint audioFormat, jint buffSizeInBytes, jint memoryMode, jintArray jSession,
        jlong nativeAudioTrack, jboolean offload) {

    ALOGV("sampleRates=%p, channel mask=%x, index mask=%x, audioFormat(Java)=%d, buffSize=%d,"
        " nativeAudioTrack=0x%" PRIX64 ", offload=%d",
        jSampleRate, channelPositionMask, channelIndexMask, audioFormat, buffSizeInBytes,
        nativeAudioTrack, offload);

    sp<AudioTrack> lpTrack = 0;

    if (jSession == NULL) {
        ALOGE("Error creating AudioTrack: invalid session ID pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }

    jint* nSession = (jint *) env->GetPrimitiveArrayCritical(jSession, NULL);
    if (nSession == NULL) {
        ALOGE("Error creating AudioTrack: Error retrieving session id pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }
    audio_session_t sessionId = (audio_session_t) nSession[0];
    env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    nSession = NULL;

    AudioTrackJniStorage* lpJniStorage = NULL;

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find %s when setting up callback.", kClassPathName);
        return (jint) AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED;
    }

    // if we pass in an existing *Native* AudioTrack, we don't need to create/initialize one.
    if (nativeAudioTrack == 0) {
        if (jaa == 0) {
            ALOGE("Error creating AudioTrack: invalid audio attributes");
            return (jint) AUDIO_JAVA_ERROR;
        }

        if (jSampleRate == 0) {
            ALOGE("Error creating AudioTrack: invalid sample rates");
            return (jint) AUDIO_JAVA_ERROR;
        }

        int* sampleRates = env->GetIntArrayElements(jSampleRate, NULL);
        int sampleRateInHertz = sampleRates[0];
        env->ReleaseIntArrayElements(jSampleRate, sampleRates, JNI_ABORT);

        // Invalid channel representations are caught by !audio_is_output_channel() below.
        audio_channel_mask_t nativeChannelMask = nativeChannelMaskFromJavaChannelMasks(
                channelPositionMask, channelIndexMask);
        if (!audio_is_output_channel(nativeChannelMask)) {
            ALOGE("Error creating AudioTrack: invalid native channel mask %#x.", nativeChannelMask);
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

        // compute the frame count
        size_t frameCount;
        if (audio_has_proportional_frames(format)) {
            const size_t bytesPerSample = audio_bytes_per_sample(format);
            frameCount = buffSizeInBytes / (channelCount * bytesPerSample);
        } else {
            frameCount = buffSizeInBytes;
        }

        // create the native AudioTrack object
        lpTrack = new AudioTrack();

        // read the AudioAttributes values
        auto paa = JNIAudioAttributeHelper::makeUnique();
        jint jStatus = JNIAudioAttributeHelper::nativeFromJava(env, jaa, paa.get());
        if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
            return jStatus;
        }
        ALOGV("AudioTrack_setup for usage=%d content=%d flags=0x%#x tags=%s",
                paa->usage, paa->content_type, paa->flags, paa->tags);

        // initialize the callback information:
        // this data will be passed with every AudioTrack callback
        lpJniStorage = new AudioTrackJniStorage();
        lpJniStorage->mCallbackData.audioTrack_class = (jclass)env->NewGlobalRef(clazz);
        // we use a weak reference so the AudioTrack object can be garbage collected.
        lpJniStorage->mCallbackData.audioTrack_ref = env->NewGlobalRef(weak_this);
        lpJniStorage->mCallbackData.isOffload = offload;
        lpJniStorage->mCallbackData.busy = false;

        audio_offload_info_t offloadInfo;
        if (offload == JNI_TRUE) {
            offloadInfo = AUDIO_INFO_INITIALIZER;
            offloadInfo.format = format;
            offloadInfo.sample_rate = sampleRateInHertz;
            offloadInfo.channel_mask = nativeChannelMask;
            offloadInfo.has_video = false;
            offloadInfo.stream_type = AUDIO_STREAM_MUSIC; //required for offload
        }

        // initialize the native AudioTrack object
        status_t status = NO_ERROR;
        switch (memoryMode) {
        case MODE_STREAM:
            status = lpTrack->set(
                    AUDIO_STREAM_DEFAULT,// stream type, but more info conveyed in paa (last argument)
                    sampleRateInHertz,
                    format,// word length, PCM
                    nativeChannelMask,
                    offload ? 0 : frameCount,
                    offload ? AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD : AUDIO_OUTPUT_FLAG_NONE,
                    audioCallback, &(lpJniStorage->mCallbackData),//callback, callback data (user)
                    0,// notificationFrames == 0 since not using EVENT_MORE_DATA to feed the AudioTrack
                    0,// shared mem
                    true,// thread can call Java
                    sessionId,// audio session ID
                    offload ? AudioTrack::TRANSFER_SYNC_NOTIF_CALLBACK : AudioTrack::TRANSFER_SYNC,
                    offload ? &offloadInfo : NULL,
                    -1, -1,                       // default uid, pid values
                    paa.get());

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
                    paa.get());
            break;

        default:
            ALOGE("Unknown mode %d", memoryMode);
            goto native_init_failure;
        }

        if (status != NO_ERROR) {
            ALOGE("Error %d initializing AudioTrack", status);
            goto native_init_failure;
        }
    } else {  // end if (nativeAudioTrack == 0)
        lpTrack = (AudioTrack*)nativeAudioTrack;
        // TODO: We need to find out which members of the Java AudioTrack might
        // need to be initialized from the Native AudioTrack
        // these are directly returned from getters:
        //  mSampleRate
        //  mAudioFormat
        //  mStreamType
        //  mChannelConfiguration
        //  mChannelCount
        //  mState (?)
        //  mPlayState (?)
        // these may be used internally (Java AudioTrack.audioParamCheck():
        //  mChannelMask
        //  mChannelIndexMask
        //  mDataLoadMode

        // initialize the callback information:
        // this data will be passed with every AudioTrack callback
        lpJniStorage = new AudioTrackJniStorage();
        lpJniStorage->mCallbackData.audioTrack_class = (jclass)env->NewGlobalRef(clazz);
        // we use a weak reference so the AudioTrack object can be garbage collected.
        lpJniStorage->mCallbackData.audioTrack_ref = env->NewGlobalRef(weak_this);
        lpJniStorage->mCallbackData.busy = false;
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

    {
        const jint elements[1] = { (jint) lpTrack->getSampleRate() };
        env->SetIntArrayRegion(jSampleRate, 0, 1, elements);
    }

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

    return (jint) AUDIO_JAVA_SUCCESS;

    // failures:
native_init_failure:
    if (nSession != NULL) {
        env->ReleasePrimitiveArrayCritical(jSession, nSession, 0);
    }
    env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioTrack_class);
    env->DeleteGlobalRef(lpJniStorage->mCallbackData.audioTrack_ref);
    delete lpJniStorage;
    env->SetLongField(thiz, javaAudioTrackFields.jniData, 0);

    // lpTrack goes out of scope, so reference count drops to zero
    return (jint) AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED;
}

// ----------------------------------------------------------------------------
static jboolean
android_media_AudioTrack_is_direct_output_supported(JNIEnv *env, jobject thiz,
                                             jint encoding, jint sampleRate,
                                             jint channelMask, jint channelIndexMask,
                                             jint contentType, jint usage, jint flags) {
    audio_config_base_t config = {};
    audio_attributes_t attributes = {};
    config.format = static_cast<audio_format_t>(audioFormatToNative(encoding));
    config.sample_rate = static_cast<uint32_t>(sampleRate);
    config.channel_mask = nativeChannelMaskFromJavaChannelMasks(channelMask, channelIndexMask);
    attributes.content_type = static_cast<audio_content_type_t>(contentType);
    attributes.usage = static_cast<audio_usage_t>(usage);
    attributes.flags = static_cast<audio_flags_mask_t>(flags);
    // ignore source and tags attributes as they don't affect querying whether output is supported
    return AudioTrack::isDirectOutputSupported(config, attributes);
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

// overloaded JNI array helper functions (same as in android_media_AudioRecord)
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
jint interpretWriteSizeError(ssize_t writeSize) {
    if (writeSize == WOULD_BLOCK) {
        return (jint)0;
    } else if (writeSize == NO_INIT) {
        return AUDIO_JAVA_DEAD_OBJECT;
    } else {
        ALOGE("Error %zd during AudioTrack native read", writeSize);
        return nativeToJavaStatus(writeSize);
    }
}

// ----------------------------------------------------------------------------
template <typename T>
static jint writeToTrack(const sp<AudioTrack>& track, jint audioFormat, const T *data,
                         jint offsetInSamples, jint sizeInSamples, bool blocking) {
    // give the data to the native AudioTrack object (the data starts at the offset)
    ssize_t written = 0;
    // regular write() or copy the data to the AudioTrack's shared memory?
    size_t sizeInBytes = sizeInSamples * sizeof(T);
    if (track->sharedBuffer() == 0) {
        written = track->write(data + offsetInSamples, sizeInBytes, blocking);
        // for compatibility with earlier behavior of write(), return 0 in this case
        if (written == (ssize_t) WOULD_BLOCK) {
            written = 0;
        }
    } else {
        // writing to shared memory, check for capacity
        if ((size_t)sizeInBytes > track->sharedBuffer()->size()) {
            sizeInBytes = track->sharedBuffer()->size();
        }
        memcpy(track->sharedBuffer()->pointer(), data + offsetInSamples, sizeInBytes);
        written = sizeInBytes;
    }
    if (written >= 0) {
        return written / sizeof(T);
    }
    return interpretWriteSizeError(written);
}

// ----------------------------------------------------------------------------
template <typename T>
static jint android_media_AudioTrack_writeArray(JNIEnv *env, jobject thiz,
                                                T javaAudioData,
                                                jint offsetInSamples, jint sizeInSamples,
                                                jint javaAudioFormat,
                                                jboolean isWriteBlocking) {
    //ALOGV("android_media_AudioTrack_writeArray(offset=%d, sizeInSamples=%d) called",
    //        offsetInSamples, sizeInSamples);
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for write()");
        return (jint)AUDIO_JAVA_INVALID_OPERATION;
    }

    if (javaAudioData == NULL) {
        ALOGE("NULL java array of audio data to play");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    // NOTE: We may use GetPrimitiveArrayCritical() when the JNI implementation changes in such
    // a way that it becomes much more efficient. When doing so, we will have to prevent the
    // AudioSystem callback to be called while in critical section (in case of media server
    // process crash for instance)

    // get the pointer for the audio data from the java array
    auto cAudioData = envGetArrayElements(env, javaAudioData, NULL);
    if (cAudioData == NULL) {
        ALOGE("Error retrieving source of audio data to play");
        return (jint)AUDIO_JAVA_BAD_VALUE; // out of memory or no data to load
    }

    jint samplesWritten = writeToTrack(lpTrack, javaAudioFormat, cAudioData,
            offsetInSamples, sizeInSamples, isWriteBlocking == JNI_TRUE /* blocking */);

    envReleaseArrayElements(env, javaAudioData, cAudioData, 0);

    //ALOGV("write wrote %d (tried %d) samples in the native AudioTrack with offset %d",
    //        (int)samplesWritten, (int)(sizeInSamples), (int)offsetInSamples);
    return samplesWritten;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_write_native_bytes(JNIEnv *env,  jobject thiz,
        jobject javaByteBuffer, jint byteOffset, jint sizeInBytes,
        jint javaAudioFormat, jboolean isWriteBlocking) {
    //ALOGV("android_media_AudioTrack_write_native_bytes(offset=%d, sizeInBytes=%d) called",
    //    offsetInBytes, sizeInBytes);
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Unable to retrieve AudioTrack pointer for write()");
        return (jint)AUDIO_JAVA_INVALID_OPERATION;
    }

    const jbyte* bytes =
            reinterpret_cast<const jbyte*>(env->GetDirectBufferAddress(javaByteBuffer));
    if (bytes == NULL) {
        ALOGE("Error retrieving source of audio data to play, can't play");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    jint written = writeToTrack(lpTrack, javaAudioFormat, bytes, byteOffset,
            sizeInBytes, isWriteBlocking == JNI_TRUE /* blocking */);

    return written;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_buffer_size_frames(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getBufferSizeInFrames()");
        return (jint)AUDIO_JAVA_ERROR;
    }

    ssize_t result = lpTrack->getBufferSizeInFrames();
    if (result < 0) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
            "Internal error detected in getBufferSizeInFrames() = %zd", result);
        return (jint)AUDIO_JAVA_ERROR;
    }
    return (jint)result;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_set_buffer_size_frames(JNIEnv *env,
        jobject thiz, jint bufferSizeInFrames) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for setBufferSizeInFrames()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    // Value will be coerced into the valid range.
    // But internal values are unsigned, size_t, so we need to clip
    // against zero here where it is signed.
    if (bufferSizeInFrames < 0) {
        bufferSizeInFrames = 0;
    }
    ssize_t result = lpTrack->setBufferSizeInFrames(bufferSizeInFrames);
    if (result < 0) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
            "Internal error detected in setBufferSizeInFrames() = %zd", result);
        return (jint)AUDIO_JAVA_ERROR;
    }
    return (jint)result;
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_buffer_capacity_frames(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getBufferCapacityInFrames()");
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
static void android_media_AudioTrack_set_playback_params(JNIEnv *env,  jobject thiz,
        jobject params) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "AudioTrack not initialized");
        return;
    }

    PlaybackParams pbp;
    pbp.fillFromJobject(env, gPlaybackParamsFields, params);

    ALOGV("setPlaybackParams: %d:%f %d:%f %d:%u %d:%u",
            pbp.speedSet, pbp.audioRate.mSpeed,
            pbp.pitchSet, pbp.audioRate.mPitch,
            pbp.audioFallbackModeSet, pbp.audioRate.mFallbackMode,
            pbp.audioStretchModeSet, pbp.audioRate.mStretchMode);

    // to simulate partially set params, we do a read-modify-write.
    // TODO: pass in the valid set mask into AudioTrack.
    AudioPlaybackRate rate = lpTrack->getPlaybackRate();
    bool updatedRate = false;
    if (pbp.speedSet) {
        rate.mSpeed = pbp.audioRate.mSpeed;
        updatedRate = true;
    }
    if (pbp.pitchSet) {
        rate.mPitch = pbp.audioRate.mPitch;
        updatedRate = true;
    }
    if (pbp.audioFallbackModeSet) {
        rate.mFallbackMode = pbp.audioRate.mFallbackMode;
        updatedRate = true;
    }
    if (pbp.audioStretchModeSet) {
        rate.mStretchMode = pbp.audioRate.mStretchMode;
        updatedRate = true;
    }
    if (updatedRate) {
        if (lpTrack->setPlaybackRate(rate) != OK) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    "arguments out of range");
        }
    }
}


// ----------------------------------------------------------------------------
static jobject android_media_AudioTrack_get_playback_params(JNIEnv *env,  jobject thiz,
        jobject params) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "AudioTrack not initialized");
        return NULL;
    }

    PlaybackParams pbs;
    pbs.audioRate = lpTrack->getPlaybackRate();
    pbs.speedSet = true;
    pbs.pitchSet = true;
    pbs.audioFallbackModeSet = true;
    pbs.audioStretchModeSet = true;
    return pbs.asJobject(env, gPlaybackParamsFields);
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
static jint android_media_AudioTrack_get_underrun_count(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getUnderrunCount()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    return (jint)lpTrack->getUnderrunCount();
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_flags(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);

    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve AudioTrack pointer for getFlags()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    return (jint)lpTrack->getFlags();
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
static jobject
android_media_AudioTrack_native_getMetrics(JNIEnv *env, jobject thiz)
{
    ALOGD("android_media_AudioTrack_native_getMetrics");

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);

    if (lpTrack == NULL) {
        ALOGE("Unable to retrieve AudioTrack pointer for getMetrics()");
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return (jobject) NULL;
    }

    // get what we have for the metrics from the track
    MediaAnalyticsItem *item = NULL;

    status_t err = lpTrack->getMetrics(item);
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
    if (audio_has_proportional_frames(format)) {
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

static jboolean android_media_AudioTrack_setOutputDevice(
                JNIEnv *env,  jobject thiz, jint device_id) {

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == 0) {
        return false;
    }
    return lpTrack->setOutputDevice(device_id) == NO_ERROR;
}

static jint android_media_AudioTrack_getRoutedDeviceId(
                JNIEnv *env,  jobject thiz) {

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        return 0;
    }
    return (jint)lpTrack->getRoutedDeviceId();
}

static void android_media_AudioTrack_enableDeviceCallback(
                JNIEnv *env,  jobject thiz) {

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        return;
    }
    AudioTrackJniStorage* pJniStorage = (AudioTrackJniStorage *)env->GetLongField(
        thiz, javaAudioTrackFields.jniData);
    if (pJniStorage == NULL || pJniStorage->mDeviceCallback != 0) {
        return;
    }
    pJniStorage->mDeviceCallback =
    new JNIDeviceCallback(env, thiz, pJniStorage->mCallbackData.audioTrack_ref,
                          javaAudioTrackFields.postNativeEventInJava);
    lpTrack->addAudioDeviceCallback(pJniStorage->mDeviceCallback);
}

static void android_media_AudioTrack_disableDeviceCallback(
                JNIEnv *env,  jobject thiz) {

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        return;
    }
    AudioTrackJniStorage* pJniStorage = (AudioTrackJniStorage *)env->GetLongField(
        thiz, javaAudioTrackFields.jniData);
    if (pJniStorage == NULL || pJniStorage->mDeviceCallback == 0) {
        return;
    }
    lpTrack->removeAudioDeviceCallback(pJniStorage->mDeviceCallback);
    pJniStorage->mDeviceCallback.clear();
}

// Pass through the arguments to the AudioFlinger track implementation.
static jint android_media_AudioTrack_apply_volume_shaper(JNIEnv *env, jobject thiz,
        jobject jconfig, jobject joperation) {
    // NOTE: hard code here to prevent platform issues. Must match VolumeShaper.java
    const int VOLUME_SHAPER_INVALID_OPERATION = -38;

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        return (jint)VOLUME_SHAPER_INVALID_OPERATION;
    }

    sp<VolumeShaper::Configuration> configuration;
    sp<VolumeShaper::Operation> operation;
    if (jconfig != nullptr) {
        configuration = VolumeShaperHelper::convertJobjectToConfiguration(
                env, gVolumeShaperFields, jconfig);
        ALOGV("applyVolumeShaper configuration: %s", configuration->toString().c_str());
    }
    if (joperation != nullptr) {
        operation = VolumeShaperHelper::convertJobjectToOperation(
                env, gVolumeShaperFields, joperation);
        ALOGV("applyVolumeShaper operation: %s", operation->toString().c_str());
    }
    VolumeShaper::Status status = lpTrack->applyVolumeShaper(configuration, operation);
    if (status == INVALID_OPERATION) {
        status = VOLUME_SHAPER_INVALID_OPERATION;
    }
    return (jint)status; // if status < 0 an error, else a VolumeShaper id
}

// Pass through the arguments to the AudioFlinger track implementation.
static jobject android_media_AudioTrack_get_volume_shaper_state(JNIEnv *env, jobject thiz,
        jint id) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        return (jobject)nullptr;
    }

    sp<VolumeShaper::State> state = lpTrack->getVolumeShaperState((int)id);
    if (state.get() == nullptr) {
        return (jobject)nullptr;
    }
    return VolumeShaperHelper::convertStateToJobject(env, gVolumeShaperFields, state);
}

static int android_media_AudioTrack_setPresentation(
                                JNIEnv *env,  jobject thiz, jint presentationId, jint programId) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "AudioTrack not initialized");
        return (jint)AUDIO_JAVA_ERROR;
    }

    return (jint)lpTrack->selectPresentation((int)presentationId, (int)programId);
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_get_port_id(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "AudioTrack not initialized");
        return (jint)AUDIO_PORT_HANDLE_NONE;
    }
    return (jint)lpTrack->getPortId();
}

// ----------------------------------------------------------------------------
static void android_media_AudioTrack_set_delay_padding(JNIEnv *env,  jobject thiz,
        jint delayInFrames, jint paddingInFrames) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "AudioTrack not initialized");
        return;
    }
    AudioParameter param = AudioParameter();
    param.addInt(String8(AUDIO_OFFLOAD_CODEC_DELAY_SAMPLES), (int) delayInFrames);
    param.addInt(String8(AUDIO_OFFLOAD_CODEC_PADDING_SAMPLES), (int) paddingInFrames);
    lpTrack->setParameters(param.toString());
}

static void android_media_AudioTrack_set_eos(JNIEnv *env,  jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "AudioTrack not initialized");
        return;
    }
    AudioParameter param = AudioParameter();
    param.addInt(String8("EOS"), 1);
    lpTrack->setParameters(param.toString());
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static const JNINativeMethod gMethods[] = {
    // name,              signature,     funcPtr
    {"native_is_direct_output_supported",
                             "(IIIIIII)Z",
                                         (void *)android_media_AudioTrack_is_direct_output_supported},
    {"native_start",         "()V",      (void *)android_media_AudioTrack_start},
    {"native_stop",          "()V",      (void *)android_media_AudioTrack_stop},
    {"native_pause",         "()V",      (void *)android_media_AudioTrack_pause},
    {"native_flush",         "()V",      (void *)android_media_AudioTrack_flush},
    {"native_setup",     "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[IJZ)I",
                                         (void *)android_media_AudioTrack_setup},
    {"native_finalize",      "()V",      (void *)android_media_AudioTrack_finalize},
    {"native_release",       "()V",      (void *)android_media_AudioTrack_release},
    {"native_write_byte",    "([BIIIZ)I",(void *)android_media_AudioTrack_writeArray<jbyteArray>},
    {"native_write_native_bytes",
                             "(Ljava/nio/ByteBuffer;IIIZ)I",
                                         (void *)android_media_AudioTrack_write_native_bytes},
    {"native_write_short",   "([SIIIZ)I",(void *)android_media_AudioTrack_writeArray<jshortArray>},
    {"native_write_float",   "([FIIIZ)I",(void *)android_media_AudioTrack_writeArray<jfloatArray>},
    {"native_setVolume",     "(FF)V",    (void *)android_media_AudioTrack_set_volume},
    {"native_get_buffer_size_frames",
                             "()I",      (void *)android_media_AudioTrack_get_buffer_size_frames},
    {"native_set_buffer_size_frames",
                             "(I)I",     (void *)android_media_AudioTrack_set_buffer_size_frames},
    {"native_get_buffer_capacity_frames",
                             "()I",      (void *)android_media_AudioTrack_get_buffer_capacity_frames},
    {"native_set_playback_rate",
                             "(I)I",     (void *)android_media_AudioTrack_set_playback_rate},
    {"native_get_playback_rate",
                             "()I",      (void *)android_media_AudioTrack_get_playback_rate},
    {"native_set_playback_params",
                             "(Landroid/media/PlaybackParams;)V",
                                         (void *)android_media_AudioTrack_set_playback_params},
    {"native_get_playback_params",
                             "()Landroid/media/PlaybackParams;",
                                         (void *)android_media_AudioTrack_get_playback_params},
    {"native_set_marker_pos","(I)I",     (void *)android_media_AudioTrack_set_marker_pos},
    {"native_get_marker_pos","()I",      (void *)android_media_AudioTrack_get_marker_pos},
    {"native_set_pos_update_period",
                             "(I)I",     (void *)android_media_AudioTrack_set_pos_update_period},
    {"native_get_pos_update_period",
                             "()I",      (void *)android_media_AudioTrack_get_pos_update_period},
    {"native_set_position",  "(I)I",     (void *)android_media_AudioTrack_set_position},
    {"native_get_position",  "()I",      (void *)android_media_AudioTrack_get_position},
    {"native_get_latency",   "()I",      (void *)android_media_AudioTrack_get_latency},
    {"native_get_underrun_count", "()I",      (void *)android_media_AudioTrack_get_underrun_count},
    {"native_get_flags",     "()I",      (void *)android_media_AudioTrack_get_flags},
    {"native_get_timestamp", "([J)I",    (void *)android_media_AudioTrack_get_timestamp},
    {"native_getMetrics",    "()Landroid/os/PersistableBundle;",
                                         (void *)android_media_AudioTrack_native_getMetrics},
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
    {"native_setOutputDevice", "(I)Z",
                             (void *)android_media_AudioTrack_setOutputDevice},
    {"native_getRoutedDeviceId", "()I", (void *)android_media_AudioTrack_getRoutedDeviceId},
    {"native_enableDeviceCallback", "()V", (void *)android_media_AudioTrack_enableDeviceCallback},
    {"native_disableDeviceCallback", "()V", (void *)android_media_AudioTrack_disableDeviceCallback},
    {"native_applyVolumeShaper",
            "(Landroid/media/VolumeShaper$Configuration;Landroid/media/VolumeShaper$Operation;)I",
                                         (void *)android_media_AudioTrack_apply_volume_shaper},
    {"native_getVolumeShaperState",
            "(I)Landroid/media/VolumeShaper$State;",
                                        (void *)android_media_AudioTrack_get_volume_shaper_state},
    {"native_setPresentation", "(II)I", (void *)android_media_AudioTrack_setPresentation},
    {"native_getPortId", "()I", (void *)android_media_AudioTrack_get_port_id},
    {"native_set_delay_padding", "(II)V", (void *)android_media_AudioTrack_set_delay_padding},
    {"native_set_eos",        "()V",    (void *)android_media_AudioTrack_set_eos},
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
    // must be first
    int res = RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));

    javaAudioTrackFields.nativeTrackInJavaObj = NULL;
    javaAudioTrackFields.postNativeEventInJava = NULL;

    // Get the AudioTrack class
    jclass audioTrackClass = FindClassOrDie(env, kClassPathName);

    // Get the postEvent method
    javaAudioTrackFields.postNativeEventInJava = GetStaticMethodIDOrDie(env,
            audioTrackClass, JAVA_POSTEVENT_CALLBACK_NAME,
            "(Ljava/lang/Object;IIILjava/lang/Object;)V");

    // Get the variables fields
    //      nativeTrackInJavaObj
    javaAudioTrackFields.nativeTrackInJavaObj = GetFieldIDOrDie(env,
            audioTrackClass, JAVA_NATIVETRACKINJAVAOBJ_FIELD_NAME, "J");
    //      jniData
    javaAudioTrackFields.jniData = GetFieldIDOrDie(env,
            audioTrackClass, JAVA_JNIDATA_FIELD_NAME, "J");
    //      fieldStreamType
    javaAudioTrackFields.fieldStreamType = GetFieldIDOrDie(env,
            audioTrackClass, JAVA_STREAMTYPE_FIELD_NAME, "I");

    env->DeleteLocalRef(audioTrackClass);

    // initialize PlaybackParams field info
    gPlaybackParamsFields.init(env);

    gVolumeShaperFields.init(env);
    return res;
}


// ----------------------------------------------------------------------------
