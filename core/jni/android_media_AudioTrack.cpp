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

#include <android-base/macros.h>
#include <android_os_Parcel.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <media/AudioParameter.h>
#include <media/AudioSystem.h>
#include <media/AudioTrack.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/Log.h>

#include <cinttypes>

#include "android_media_AudioAttributes.h"
#include "android_media_AudioErrors.h"
#include "android_media_AudioFormat.h"
#include "android_media_AudioTrackCallback.h"
#include "android_media_DeviceCallback.h"
#include "android_media_JNIUtils.h"
#include "android_media_MediaMetricsJNI.h"
#include "android_media_PlaybackParams.h"
#include "android_media_VolumeShaper.h"
#include "core_jni_helpers.h"

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

class AudioTrackCallbackImpl : public AudioTrack::IAudioTrackCallback {
  public:
    enum event_type {
    // Keep in sync with java
        EVENT_MORE_DATA = 0,
        EVENT_UNDERRUN = 1,
        EVENT_LOOP_END = 2,
        EVENT_MARKER = 3,
        EVENT_NEW_POS = 4,
        EVENT_BUFFER_END = 5,
        EVENT_NEW_IAUDIOTRACK = 6,
        EVENT_STREAM_END = 7,
        // 8 is reserved for future use
        EVENT_CAN_WRITE_MORE_DATA = 9
    };

    AudioTrackCallbackImpl(jclass audioTrackClass, jobject audioTrackWeakRef, bool isOffload)
          : mIsOffload(isOffload)
    {
      const auto env = getJNIEnvOrDie();
      mAudioTrackClass = (jclass)env->NewGlobalRef(audioTrackClass);
      // we use a weak reference so the AudioTrack object can be garbage collected.
      mAudioTrackWeakRef = env->NewGlobalRef(audioTrackWeakRef);

    }

    AudioTrackCallbackImpl(const AudioTrackCallbackImpl&) = delete;
    AudioTrackCallbackImpl& operator=(const AudioTrackCallbackImpl&) = delete;
    ~AudioTrackCallbackImpl() {
        const auto env = getJNIEnvOrDie();
        env->DeleteGlobalRef(mAudioTrackClass);
        env->DeleteGlobalRef(mAudioTrackWeakRef);
    }

    size_t onCanWriteMoreData(const AudioTrack::Buffer& buffer) override {
      if (!mIsOffload) {
          LOG_FATAL("Received canWrite callback for non-offload track");
          return 0;
      }
      const size_t availableForWrite = buffer.size();
      const int arg = availableForWrite > INT32_MAX ? INT32_MAX : (int) availableForWrite;
      postEvent(EVENT_CAN_WRITE_MORE_DATA, arg);
      return 0;
    }

    void onMarker([[maybe_unused]] uint32_t markerPosition) override {
        postEvent(EVENT_MARKER);
    }
    void onNewPos([[maybe_unused]] uint32_t newPos) override {
        postEvent(EVENT_NEW_POS);
    }


    void onNewIAudioTrack() override {
        if (!mIsOffload) return;
        postEvent(EVENT_NEW_IAUDIOTRACK);
    }

    void onStreamEnd() override {
        if (!mIsOffload) return;
        postEvent(EVENT_STREAM_END);
    }

  protected:
    jobject     mAudioTrackWeakRef;
  private:

     void postEvent(int event, int arg = 0) {
        auto env = getJNIEnvOrDie();
        env->CallStaticVoidMethod(
                mAudioTrackClass,
                javaAudioTrackFields.postNativeEventInJava,
                mAudioTrackWeakRef, event, arg, 0, NULL);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    jclass      mAudioTrackClass;
    const bool  mIsOffload;
};

// keep these values in sync with AudioTrack.java
#define MODE_STATIC 0
#define MODE_STREAM 1

// ----------------------------------------------------------------------------
class AudioTrackJniStorage : public virtual RefBase,
                             public AudioTrackCallbackImpl
{
public:
    // TODO do we always want to initialize the callback implementation?
    AudioTrackJniStorage(jclass audioTrackClass, jobject audioTrackRef, bool isOffload = false)
          : AudioTrackCallbackImpl(audioTrackClass, audioTrackRef, isOffload) {}

    sp<JNIDeviceCallback> mDeviceCallback;
    sp<JNIAudioTrackCallback> mAudioTrackCallback;

    jobject getAudioTrackWeakRef() const {
        return mAudioTrackWeakRef;
    }

};

class TunerConfigurationHelper {
    JNIEnv *const mEnv;
    jobject const mTunerConfiguration;

    struct Ids {
        Ids(JNIEnv *env)
              : mClass(FindClassOrDie(env, "android/media/AudioTrack$TunerConfiguration")),
                mContentId(GetFieldIDOrDie(env, mClass, "mContentId", "I")),
                mSyncId(GetFieldIDOrDie(env, mClass, "mSyncId", "I")) {}
        const jclass mClass;
        const jfieldID mContentId;
        const jfieldID mSyncId;
    };

    static const Ids &getIds(JNIEnv *env) {
        // Meyer's singleton, initializes first time control passes through
        // declaration in a block and is thread-safe per ISO/IEC 14882:2011 6.7.4.
        static Ids ids(env);
        return ids;
    }

public:
    TunerConfigurationHelper(JNIEnv *env, jobject tunerConfiguration)
          : mEnv(env), mTunerConfiguration(tunerConfiguration) {}

    int32_t getContentId() const {
        if (mEnv == nullptr || mTunerConfiguration == nullptr) return 0;
        const Ids &ids = getIds(mEnv);
        return (int32_t)mEnv->GetIntField(mTunerConfiguration, ids.mContentId);
    }

    int32_t getSyncId() const {
        if (mEnv == nullptr || mTunerConfiguration == nullptr) return 0;
        const Ids &ids = getIds(mEnv);
        return (int32_t)mEnv->GetIntField(mTunerConfiguration, ids.mSyncId);
    }

    // optional check to confirm class and field ids can be found.
    static void initCheckOrDie(JNIEnv *env) { (void)getIds(env); }
};


// ----------------------------------------------------------------------------
#define DEFAULT_OUTPUT_SAMPLE_RATE   44100

#define AUDIOTRACK_ERROR_SETUP_AUDIOSYSTEM         (-16)
#define AUDIOTRACK_ERROR_SETUP_INVALIDCHANNELMASK  (-17)
#define AUDIOTRACK_ERROR_SETUP_INVALIDFORMAT       (-18)
#define AUDIOTRACK_ERROR_SETUP_INVALIDSTREAMTYPE   (-19)
#define AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED    (-20)

namespace {
sp<IMemory> allocSharedMem(int sizeInBytes) {
    const auto heap = sp<MemoryHeapBase>::make(sizeInBytes, 0, "AudioTrack Heap Base");
    if (heap->getBase() == MAP_FAILED || heap->getBase() == nullptr) {
        return nullptr;
    }
    return sp<MemoryBase>::make(heap, 0, sizeInBytes);
}

sp<AudioTrack> getAudioTrack(JNIEnv* env, jobject thiz) {
    return getFieldSp<AudioTrack>(env, thiz, javaAudioTrackFields.nativeTrackInJavaObj);
}

} // anonymous
// ----------------------------------------------------------------------------
// For MediaSync
sp<AudioTrack> android_media_AudioTrack_getAudioTrack(JNIEnv* env, jobject audioTrackObj) {
    return getAudioTrack(env, audioTrackObj);
}

// ----------------------------------------------------------------------------
static jint android_media_AudioTrack_setup(JNIEnv *env, jobject thiz, jobject weak_this,
                                           jobject jaa, jintArray jSampleRate,
                                           jint channelPositionMask, jint channelIndexMask,
                                           jint audioFormat, jint buffSizeInBytes, jint memoryMode,
                                           jintArray jSession, jobject jAttributionSource,
                                           jlong nativeAudioTrack, jboolean offload,
                                           jint encapsulationMode, jobject tunerConfiguration,
                                           jstring opPackageName) {
    ALOGV("sampleRates=%p, channel mask=%x, index mask=%x, audioFormat(Java)=%d, buffSize=%d,"
          " nativeAudioTrack=0x%" PRIX64 ", offload=%d encapsulationMode=%d tuner=%p",
          jSampleRate, channelPositionMask, channelIndexMask, audioFormat, buffSizeInBytes,
          nativeAudioTrack, offload, encapsulationMode, tunerConfiguration);

    if (jSession == NULL) {
        ALOGE("Error creating AudioTrack: invalid session ID pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }

    const TunerConfigurationHelper tunerHelper(env, tunerConfiguration);

    jint* nSession = env->GetIntArrayElements(jSession, nullptr /* isCopy */);
    if (nSession == NULL) {
        ALOGE("Error creating AudioTrack: Error retrieving session id pointer");
        return (jint) AUDIO_JAVA_ERROR;
    }
    audio_session_t sessionId = (audio_session_t) nSession[0];
    env->ReleaseIntArrayElements(jSession, nSession, 0 /* mode */);
    nSession = NULL;


    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find %s when setting up callback.", kClassPathName);
        return (jint) AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED;
    }

    // if we pass in an existing *Native* AudioTrack, we don't need to create/initialize one.
    sp<AudioTrack> lpTrack;
    const auto lpJniStorage = sp<AudioTrackJniStorage>::make(clazz, weak_this, offload);
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
        ScopedUtfChars opPackageNameStr(env, opPackageName);

        android::content::AttributionSourceState attributionSource;
        attributionSource.readFromParcel(parcelForJavaObject(env, jAttributionSource));
        lpTrack = sp<AudioTrack>::make(attributionSource);

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
        audio_offload_info_t offloadInfo;
        if (offload == JNI_TRUE) {
            offloadInfo = AUDIO_INFO_INITIALIZER;
            offloadInfo.format = format;
            offloadInfo.sample_rate = sampleRateInHertz;
            offloadInfo.channel_mask = nativeChannelMask;
            offloadInfo.has_video = false;
            offloadInfo.stream_type = AUDIO_STREAM_MUSIC; //required for offload
        }

        if (encapsulationMode != 0) {
            offloadInfo = AUDIO_INFO_INITIALIZER;
            offloadInfo.format = format;
            offloadInfo.sample_rate = sampleRateInHertz;
            offloadInfo.channel_mask = nativeChannelMask;
            offloadInfo.stream_type = AUDIO_STREAM_MUSIC;
            offloadInfo.encapsulation_mode =
                    static_cast<audio_encapsulation_mode_t>(encapsulationMode);
            offloadInfo.content_id = tunerHelper.getContentId();
            offloadInfo.sync_id = tunerHelper.getSyncId();
        }

        // initialize the native AudioTrack object
        status_t status = NO_ERROR;
        switch (memoryMode) {
        case MODE_STREAM:
            status = lpTrack->set(AUDIO_STREAM_DEFAULT, // stream type, but more info conveyed
                                                        // in paa (last argument)
                                  sampleRateInHertz,
                                  format, // word length, PCM
                                  nativeChannelMask, offload ? 0 : frameCount,
                                  offload ? AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD
                                          : AUDIO_OUTPUT_FLAG_NONE,
                                  lpJniStorage,
                                  0,    // notificationFrames == 0 since not using EVENT_MORE_DATA
                                        // to feed the AudioTrack
                                  0,    // shared mem
                                  true, // thread can call Java
                                  sessionId, // audio session ID
                                  offload ? AudioTrack::TRANSFER_SYNC_NOTIF_CALLBACK
                                          : AudioTrack::TRANSFER_SYNC,
                                  (offload || encapsulationMode) ? &offloadInfo : NULL,
                                  attributionSource, // Passed from Java
                                  paa.get());
            break;

        case MODE_STATIC:
        {
            // AudioTrack is using shared memory
            const auto iMem = allocSharedMem(buffSizeInBytes);
            if (iMem == nullptr) {
                ALOGE("Error creating AudioTrack in static mode: error creating mem heap base");
                goto native_init_failure;
            }

            status = lpTrack->set(AUDIO_STREAM_DEFAULT, // stream type, but more info conveyed
                                                        // in paa (last argument)
                                  sampleRateInHertz,
                                  format, // word length, PCM
                                  nativeChannelMask, frameCount, AUDIO_OUTPUT_FLAG_NONE,
                                  lpJniStorage,
                                  0,    // notificationFrames == 0 since not using EVENT_MORE_DATA
                                        // to feed the AudioTrack
                                  iMem, // shared mem
                                  true, // thread can call Java
                                  sessionId, // audio session ID
                                  AudioTrack::TRANSFER_SHARED,
                                  nullptr,           // default offloadInfo
                                  attributionSource, // Passed from Java
                                  paa.get());
            break;
        }
        default:
            ALOGE("Unknown mode %d", memoryMode);
            goto native_init_failure;
        }

        if (status != NO_ERROR) {
            ALOGE("Error %d initializing AudioTrack", status);
            goto native_init_failure;
        }
        // Set caller name so it can be logged in destructor.
        // MediaMetricsConstants.h: AMEDIAMETRICS_PROP_CALLERNAME_VALUE_JAVA
        lpTrack->setCallerName("java");
    } else {  // end if (nativeAudioTrack == 0)
        lpTrack = sp<AudioTrack>::fromExisting(reinterpret_cast<AudioTrack*>(nativeAudioTrack));
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

        // TODO this callback information is useless, it isn't passed to the
        // native AudioTrack object
        /*
        lpJniStorage->mCallbackData.audioTrack_class = (jclass)env->NewGlobalRef(clazz);
        // we use a weak reference so the AudioTrack object can be garbage collected.
        lpJniStorage->mCallbackData.audioTrack_ref = env->NewGlobalRef(weak_this);
        lpJniStorage->mCallbackData.busy = false;
        */
    }
    lpJniStorage->mAudioTrackCallback =
            sp<JNIAudioTrackCallback>::make(env, thiz, lpJniStorage->getAudioTrackWeakRef(),
                                            javaAudioTrackFields.postNativeEventInJava);
    lpTrack->setAudioTrackCallback(lpJniStorage->mAudioTrackCallback);

    nSession = env->GetIntArrayElements(jSession, nullptr /* isCopy */);
    if (nSession == NULL) {
        ALOGE("Error creating AudioTrack: Error retrieving session id pointer");
        goto native_init_failure;
    }
    // read the audio session ID back from AudioTrack in case we create a new session
    nSession[0] = lpTrack->getSessionId();
    env->ReleaseIntArrayElements(jSession, nSession, 0 /* mode */);
    nSession = NULL;

    {
        const jint elements[1] = { (jint) lpTrack->getSampleRate() };
        env->SetIntArrayRegion(jSampleRate, 0, 1, elements);
    }

    // save our newly created C++ AudioTrack in the "nativeTrackInJavaObj" field
    // of the Java object (in mNativeTrackInJavaObj)
    setFieldSp(env, thiz, lpTrack, javaAudioTrackFields.nativeTrackInJavaObj);

    // save the JNI resources so we can free them later
    //ALOGV("storing lpJniStorage: %x\n", (long)lpJniStorage);
    setFieldSp(env, thiz, lpJniStorage, javaAudioTrackFields.jniData);

    // since we had audio attributes, the stream type was derived from them during the
    // creation of the native AudioTrack: push the same value to the Java object
    env->SetIntField(thiz, javaAudioTrackFields.fieldStreamType, (jint) lpTrack->streamType());

    return (jint) AUDIO_JAVA_SUCCESS;

    // failures:
native_init_failure:
    if (nSession != NULL) {
        env->ReleaseIntArrayElements(jSession, nSession, 0 /* mode */);
    }

    setFieldSp(env, thiz, sp<AudioTrack>{}, javaAudioTrackFields.nativeTrackInJavaObj);
    setFieldSp(env, thiz, sp<AudioTrackJniStorage>{}, javaAudioTrackFields.jniData);
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

static void android_media_AudioTrack_release(JNIEnv *env,  jobject thiz) {
    setFieldSp(env, thiz, sp<AudioTrack>(nullptr), javaAudioTrackFields.nativeTrackInJavaObj);
    setFieldSp(env, thiz, sp<AudioTrackJniStorage>(nullptr), javaAudioTrackFields.jniData);
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
        memcpy(track->sharedBuffer()->unsecurePointer(), data + offsetInSamples, sizeInBytes);
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
        jlong* nTimestamp = env->GetLongArrayElements(jTimestamp, nullptr /* isCopy */);
        if (nTimestamp == NULL) {
            ALOGE("Unable to get array for getTimestamp()");
            return (jint)AUDIO_JAVA_ERROR;
        }
        nTimestamp[0] = static_cast<jlong>(timestamp.mPosition);
        nTimestamp[1] = static_cast<jlong>((timestamp.mTime.tv_sec * 1000000000LL) +
                                           timestamp.mTime.tv_nsec);
        env->ReleaseLongArrayElements(jTimestamp, nTimestamp, 0 /* mode */);
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
    mediametrics::Item *item = NULL;

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
static jintArray android_media_AudioTrack_getRoutedDeviceIds(JNIEnv *env, jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == NULL) {
        return NULL;
    }
    DeviceIdVector deviceIds = lpTrack->getRoutedDeviceIds();
    jintArray result;
    result = env->NewIntArray(deviceIds.size());
    if (result == NULL) {
        return NULL;
    }
    jint *values = env->GetIntArrayElements(result, 0);
    for (unsigned int i = 0; i < deviceIds.size(); i++) {
        values[i++] = static_cast<jint>(deviceIds[i]);
    }
    env->ReleaseIntArrayElements(result, values, 0);
    return result;
}

static void android_media_AudioTrack_enableDeviceCallback(
                JNIEnv *env,  jobject thiz) {

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        return;
    }
    const auto pJniStorage =
            getFieldSp<AudioTrackJniStorage>(env, thiz, javaAudioTrackFields.jniData);
    if (pJniStorage == nullptr || pJniStorage->mDeviceCallback != nullptr) {
        return;
    }

    pJniStorage->mDeviceCallback =
            sp<JNIDeviceCallback>::make(env, thiz, pJniStorage->getAudioTrackWeakRef(),
                                        javaAudioTrackFields.postNativeEventInJava);
    lpTrack->addAudioDeviceCallback(pJniStorage->mDeviceCallback);
}

static void android_media_AudioTrack_disableDeviceCallback(
                JNIEnv *env,  jobject thiz) {

    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        return;
    }
    const auto pJniStorage =
            getFieldSp<AudioTrackJniStorage>(env, thiz, javaAudioTrackFields.jniData);

    if (pJniStorage == nullptr || pJniStorage->mDeviceCallback == nullptr) {
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

static jint android_media_AudioTrack_setAudioDescriptionMixLeveldB(JNIEnv *env, jobject thiz,
                                                                   jfloat level) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", "AudioTrack not initialized");
        return (jint)AUDIO_JAVA_ERROR;
    }

    return nativeToJavaStatus(lpTrack->setAudioDescriptionMixLevel(level));
}

static jint android_media_AudioTrack_getAudioDescriptionMixLeveldB(JNIEnv *env, jobject thiz,
                                                                   jfloatArray level) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        ALOGE("%s: AudioTrack not initialized", __func__);
        return (jint)AUDIO_JAVA_ERROR;
    }
    jfloat *nativeLevel = env->GetFloatArrayElements(level, nullptr /* isCopy */);
    if (nativeLevel == nullptr) {
        ALOGE("%s: Cannot retrieve level pointer", __func__);
        return (jint)AUDIO_JAVA_ERROR;
    }

    status_t status = lpTrack->getAudioDescriptionMixLevel(reinterpret_cast<float *>(nativeLevel));
    env->ReleaseFloatArrayElements(level, nativeLevel, 0 /* mode */);

    return nativeToJavaStatus(status);
}

static jint android_media_AudioTrack_setDualMonoMode(JNIEnv *env, jobject thiz, jint dualMonoMode) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", "AudioTrack not initialized");
        return (jint)AUDIO_JAVA_ERROR;
    }

    return nativeToJavaStatus(
            lpTrack->setDualMonoMode(static_cast<audio_dual_mono_mode_t>(dualMonoMode)));
}

static jint android_media_AudioTrack_getDualMonoMode(JNIEnv *env, jobject thiz,
                                                     jintArray dualMonoMode) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        ALOGE("%s: AudioTrack not initialized", __func__);
        return (jint)AUDIO_JAVA_ERROR;
    }
    jint *nativeDualMonoMode = env->GetIntArrayElements(dualMonoMode, nullptr /* isCopy */);
    if (nativeDualMonoMode == nullptr) {
        ALOGE("%s: Cannot retrieve dualMonoMode pointer", __func__);
        return (jint)AUDIO_JAVA_ERROR;
    }

    status_t status = lpTrack->getDualMonoMode(
            reinterpret_cast<audio_dual_mono_mode_t *>(nativeDualMonoMode));
    env->ReleaseIntArrayElements(dualMonoMode, nativeDualMonoMode, 0 /* mode */);

    return nativeToJavaStatus(status);
}

static void android_media_AudioTrack_setLogSessionId(JNIEnv *env, jobject thiz,
                                                     jstring jlogSessionId) {
    sp<AudioTrack> track = getAudioTrack(env, thiz);
    if (track == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Unable to retrieve AudioTrack pointer for setLogSessionId()");
    }
    if (jlogSessionId == nullptr) {
        ALOGV("%s: logSessionId nullptr", __func__);
        track->setLogSessionId(nullptr);
        return;
    }
    ScopedUtfChars logSessionId(env, jlogSessionId);
    ALOGV("%s: logSessionId '%s'", __func__, logSessionId.c_str());
    track->setLogSessionId(logSessionId.c_str());
}

static void android_media_AudioTrack_setPlayerIId(JNIEnv *env, jobject thiz, jint playerIId) {
    sp<AudioTrack> track = getAudioTrack(env, thiz);
    if (track == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Unable to retrieve AudioTrack pointer for setPlayerIId()");
    }
    ALOGV("%s: playerIId %d", __func__, playerIId);
    track->setPlayerIId(playerIId);
}

static jint android_media_AudioTrack_getStartThresholdInFrames(JNIEnv *env, jobject thiz) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Unable to retrieve AudioTrack pointer for getStartThresholdInFrames()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    const ssize_t result = lpTrack->getStartThresholdInFrames();
    if (result <= 0) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                             "Internal error detected in getStartThresholdInFrames() = %zd",
                             result);
        return (jint)AUDIO_JAVA_ERROR;
    }
    return (jint)result; // this should be a positive value.
}

static jint android_media_AudioTrack_setStartThresholdInFrames(JNIEnv *env, jobject thiz,
                                                               jint startThresholdInFrames) {
    sp<AudioTrack> lpTrack = getAudioTrack(env, thiz);
    if (lpTrack == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Unable to retrieve AudioTrack pointer for setStartThresholdInFrames()");
        return (jint)AUDIO_JAVA_ERROR;
    }
    // non-positive values of startThresholdInFrames are not allowed by the Java layer.
    const ssize_t result = lpTrack->setStartThresholdInFrames(startThresholdInFrames);
    if (result <= 0) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                             "Internal error detected in setStartThresholdInFrames() = %zd",
                             result);
        return (jint)AUDIO_JAVA_ERROR;
    }
    return (jint)result; // this should be a positive value.
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static const JNINativeMethod gMethods[] = {
        // name,              signature,     funcPtr
        {"native_is_direct_output_supported", "(IIIIIII)Z",
         (void *)android_media_AudioTrack_is_direct_output_supported},
        {"native_start", "()V", (void *)android_media_AudioTrack_start},
        {"native_stop", "()V", (void *)android_media_AudioTrack_stop},
        {"native_pause", "()V", (void *)android_media_AudioTrack_pause},
        {"native_flush", "()V", (void *)android_media_AudioTrack_flush},
        {"native_setup",
         "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[ILandroid/os/Parcel;"
         "JZILjava/lang/Object;Ljava/lang/String;)I",
         (void *)android_media_AudioTrack_setup},
        {"native_finalize", "()V", (void *)android_media_AudioTrack_finalize},
        {"native_release", "()V", (void *)android_media_AudioTrack_release},
        {"native_write_byte", "([BIIIZ)I", (void *)android_media_AudioTrack_writeArray<jbyteArray>},
        {"native_write_native_bytes", "(Ljava/nio/ByteBuffer;IIIZ)I",
         (void *)android_media_AudioTrack_write_native_bytes},
        {"native_write_short", "([SIIIZ)I",
         (void *)android_media_AudioTrack_writeArray<jshortArray>},
        {"native_write_float", "([FIIIZ)I",
         (void *)android_media_AudioTrack_writeArray<jfloatArray>},
        {"native_setVolume", "(FF)V", (void *)android_media_AudioTrack_set_volume},
        {"native_get_buffer_size_frames", "()I",
         (void *)android_media_AudioTrack_get_buffer_size_frames},
        {"native_set_buffer_size_frames", "(I)I",
         (void *)android_media_AudioTrack_set_buffer_size_frames},
        {"native_get_buffer_capacity_frames", "()I",
         (void *)android_media_AudioTrack_get_buffer_capacity_frames},
        {"native_set_playback_rate", "(I)I", (void *)android_media_AudioTrack_set_playback_rate},
        {"native_get_playback_rate", "()I", (void *)android_media_AudioTrack_get_playback_rate},
        {"native_set_playback_params", "(Landroid/media/PlaybackParams;)V",
         (void *)android_media_AudioTrack_set_playback_params},
        {"native_get_playback_params", "()Landroid/media/PlaybackParams;",
         (void *)android_media_AudioTrack_get_playback_params},
        {"native_set_marker_pos", "(I)I", (void *)android_media_AudioTrack_set_marker_pos},
        {"native_get_marker_pos", "()I", (void *)android_media_AudioTrack_get_marker_pos},
        {"native_set_pos_update_period", "(I)I",
         (void *)android_media_AudioTrack_set_pos_update_period},
        {"native_get_pos_update_period", "()I",
         (void *)android_media_AudioTrack_get_pos_update_period},
        {"native_set_position", "(I)I", (void *)android_media_AudioTrack_set_position},
        {"native_get_position", "()I", (void *)android_media_AudioTrack_get_position},
        {"native_get_latency", "()I", (void *)android_media_AudioTrack_get_latency},
        {"native_get_underrun_count", "()I", (void *)android_media_AudioTrack_get_underrun_count},
        {"native_get_flags", "()I", (void *)android_media_AudioTrack_get_flags},
        {"native_get_timestamp", "([J)I", (void *)android_media_AudioTrack_get_timestamp},
        {"native_getMetrics", "()Landroid/os/PersistableBundle;",
         (void *)android_media_AudioTrack_native_getMetrics},
        {"native_set_loop", "(III)I", (void *)android_media_AudioTrack_set_loop},
        {"native_reload_static", "()I", (void *)android_media_AudioTrack_reload},
        {"native_get_output_sample_rate", "(I)I",
         (void *)android_media_AudioTrack_get_output_sample_rate},
        {"native_get_min_buff_size", "(III)I", (void *)android_media_AudioTrack_get_min_buff_size},
        {"native_setAuxEffectSendLevel", "(F)I",
         (void *)android_media_AudioTrack_setAuxEffectSendLevel},
        {"native_attachAuxEffect", "(I)I", (void *)android_media_AudioTrack_attachAuxEffect},
        {"native_setOutputDevice", "(I)Z", (void *)android_media_AudioTrack_setOutputDevice},
        {"native_getRoutedDeviceIds", "()[I", (void *)android_media_AudioTrack_getRoutedDeviceIds},
        {"native_enableDeviceCallback", "()V",
         (void *)android_media_AudioTrack_enableDeviceCallback},
        {"native_disableDeviceCallback", "()V",
         (void *)android_media_AudioTrack_disableDeviceCallback},
        {"native_applyVolumeShaper",
         "(Landroid/media/VolumeShaper$Configuration;Landroid/media/VolumeShaper$Operation;)I",
         (void *)android_media_AudioTrack_apply_volume_shaper},
        {"native_getVolumeShaperState", "(I)Landroid/media/VolumeShaper$State;",
         (void *)android_media_AudioTrack_get_volume_shaper_state},
        {"native_setPresentation", "(II)I", (void *)android_media_AudioTrack_setPresentation},
        {"native_getPortId", "()I", (void *)android_media_AudioTrack_get_port_id},
        {"native_set_delay_padding", "(II)V", (void *)android_media_AudioTrack_set_delay_padding},
        {"native_set_audio_description_mix_level_db", "(F)I",
         (void *)android_media_AudioTrack_setAudioDescriptionMixLeveldB},
        {"native_get_audio_description_mix_level_db", "([F)I",
         (void *)android_media_AudioTrack_getAudioDescriptionMixLeveldB},
        {"native_set_dual_mono_mode", "(I)I", (void *)android_media_AudioTrack_setDualMonoMode},
        {"native_get_dual_mono_mode", "([I)I", (void *)android_media_AudioTrack_getDualMonoMode},
        {"native_setLogSessionId", "(Ljava/lang/String;)V",
         (void *)android_media_AudioTrack_setLogSessionId},
        {"native_setPlayerIId", "(I)V", (void *)android_media_AudioTrack_setPlayerIId},
        {"native_setStartThresholdInFrames", "(I)I",
         (void *)android_media_AudioTrack_setStartThresholdInFrames},
        {"native_getStartThresholdInFrames", "()I",
         (void *)android_media_AudioTrack_getStartThresholdInFrames},
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

    // optional check that the TunerConfiguration class and fields exist.
    TunerConfigurationHelper::initCheckOrDie(env);

    return res;
}


// ----------------------------------------------------------------------------
