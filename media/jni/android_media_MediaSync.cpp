/*
 * Copyright 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaSync-JNI"
#include <utils/Log.h>

#include "android_media_MediaSync.h"

#include "android_media_AudioTrack.h"
#include "android_media_PlaybackParams.h"
#include "android_media_SyncParams.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>

#include <gui/Surface.h>

#include <media/AudioResamplerPublic.h>
#include <media/AudioTrack.h>
#include <media/stagefright/MediaClock.h>
#include <media/stagefright/MediaSync.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AString.h>

#include <nativehelper/ScopedLocalRef.h>

namespace android {

struct fields_t {
    jfieldID context;
    jfieldID mediaTimestampMediaTimeUsID;
    jfieldID mediaTimestampNanoTimeID;
    jfieldID mediaTimestampClockRateID;
};

static fields_t gFields;
static PlaybackParams::fields_t gPlaybackParamsFields;
static SyncParams::fields_t gSyncParamsFields;

////////////////////////////////////////////////////////////////////////////////

JMediaSync::JMediaSync() {
    mSync = MediaSync::create();
}

JMediaSync::~JMediaSync() {
}

status_t JMediaSync::setSurface(const sp<IGraphicBufferProducer> &bufferProducer) {
    return mSync->setSurface(bufferProducer);
}

status_t JMediaSync::setAudioTrack(const sp<AudioTrack> &audioTrack) {
    return mSync->setAudioTrack(audioTrack);
}

status_t JMediaSync::createInputSurface(
        sp<IGraphicBufferProducer>* bufferProducer) {
    return mSync->createInputSurface(bufferProducer);
}

sp<const MediaClock> JMediaSync::getMediaClock() {
    return mSync->getMediaClock();
}

status_t JMediaSync::setPlaybackParams(const AudioPlaybackRate& rate) {
    return mSync->setPlaybackSettings(rate);
}

void JMediaSync::getPlaybackParams(AudioPlaybackRate* rate /* nonnull */) {
    mSync->getPlaybackSettings(rate);
}

status_t JMediaSync::setSyncParams(const AVSyncSettings& syncParams) {
    return mSync->setSyncSettings(syncParams);
}

void JMediaSync::getSyncParams(AVSyncSettings* syncParams /* nonnull */) {
    mSync->getSyncSettings(syncParams);
}

status_t JMediaSync::setVideoFrameRateHint(float rate) {
    return mSync->setVideoFrameRateHint(rate);
}

float JMediaSync::getVideoFrameRate() {
    return mSync->getVideoFrameRate();
}

void JMediaSync::flush() {
    mSync->flush();
}

status_t JMediaSync::updateQueuedAudioData(
        int sizeInBytes, int64_t presentationTimeUs) {
    return mSync->updateQueuedAudioData(sizeInBytes, presentationTimeUs);
}

status_t JMediaSync::getPlayTimeForPendingAudioFrames(int64_t *outTimeUs) {
    return mSync->getPlayTimeForPendingAudioFrames(outTimeUs);
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static sp<JMediaSync> setMediaSync(JNIEnv *env, jobject thiz, const sp<JMediaSync> &sync) {
    sp<JMediaSync> old = (JMediaSync *)env->GetLongField(thiz, gFields.context);
    if (sync != NULL) {
        sync->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }

    env->SetLongField(thiz, gFields.context, (jlong)sync.get());

    return old;
}

static sp<JMediaSync> getMediaSync(JNIEnv *env, jobject thiz) {
    return (JMediaSync *)env->GetLongField(thiz, gFields.context);
}

static void android_media_MediaSync_release(JNIEnv *env, jobject thiz) {
    setMediaSync(env, thiz, NULL);
}

static void throwExceptionAsNecessary(
        JNIEnv *env, status_t err, const char *msg = NULL) {
    switch (err) {
        case NO_ERROR:
            break;

        case BAD_VALUE:
            jniThrowException(env, "java/lang/IllegalArgumentException", msg);
            break;

        case NO_INIT:
        case INVALID_OPERATION:
        default:
            if (err > 0) {
                break;
            }
            AString msgWithErrorCode(msg == NULL ? "" : msg);
            msgWithErrorCode.append(" error:");
            msgWithErrorCode.append(err);
            jniThrowException(env, "java/lang/IllegalStateException", msgWithErrorCode.c_str());
            break;
    }
}

static void android_media_MediaSync_native_setSurface(
        JNIEnv *env, jobject thiz, jobject jsurface) {
    ALOGV("android_media_MediaSync_setSurface");

    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sp<IGraphicBufferProducer> bufferProducer;
    if (jsurface != NULL) {
        sp<Surface> surface(android_view_Surface_getSurface(env, jsurface));
        if (surface != NULL) {
            bufferProducer = surface->getIGraphicBufferProducer();
        } else {
            throwExceptionAsNecessary(env, BAD_VALUE, "The surface has been released");
            return;
        }
    }

    status_t err = sync->setSurface(bufferProducer);

    if (err == INVALID_OPERATION) {
        throwExceptionAsNecessary(
                env, INVALID_OPERATION, "Surface has already been configured");
    } if (err != NO_ERROR) {
        AString msg("Failed to connect to surface with error ");
        msg.append(err);
        throwExceptionAsNecessary(env, BAD_VALUE, msg.c_str());
    }
}

static void android_media_MediaSync_native_setAudioTrack(
        JNIEnv *env, jobject thiz, jobject jaudioTrack) {
    ALOGV("android_media_MediaSync_setAudioTrack");

    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sp<AudioTrack> audioTrack;
    if (jaudioTrack != NULL) {
        audioTrack = android_media_AudioTrack_getAudioTrack(env, jaudioTrack);
        if (audioTrack == NULL) {
            throwExceptionAsNecessary(env, BAD_VALUE, "The audio track has been released");
            return;
        }
    }

    status_t err = sync->setAudioTrack(audioTrack);

    if (err == INVALID_OPERATION) {
        throwExceptionAsNecessary(
                env, INVALID_OPERATION, "Audio track has already been configured");
    } if (err != NO_ERROR) {
        AString msg("Failed to configure audio track with error ");
        msg.append(err);
        throwExceptionAsNecessary(env, BAD_VALUE, msg.c_str());
    }
}

static jobject android_media_MediaSync_createInputSurface(
        JNIEnv* env, jobject thiz) {
    ALOGV("android_media_MediaSync_createInputSurface");

    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    // Tell the MediaSync that we want to use a Surface as input.
    sp<IGraphicBufferProducer> bufferProducer;
    status_t err = sync->createInputSurface(&bufferProducer);
    if (err != NO_ERROR) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    // Wrap the IGBP in a Java-language Surface.
    return android_view_Surface_createFromIGraphicBufferProducer(env,
            bufferProducer);
}

static void android_media_MediaSync_native_updateQueuedAudioData(
        JNIEnv *env, jobject thiz, jint sizeInBytes, jlong presentationTimeUs) {
    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = sync->updateQueuedAudioData(sizeInBytes, presentationTimeUs);
    if (err != NO_ERROR) {
        throwExceptionAsNecessary(env, err);
        return;
    }
}

static jboolean android_media_MediaSync_native_getTimestamp(
        JNIEnv *env, jobject thiz, jobject timestamp) {
    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return JNI_FALSE;
    }

    sp<const MediaClock> mediaClock = sync->getMediaClock();
    if (mediaClock == NULL) {
        return JNI_FALSE;
    }

    int64_t nowUs = ALooper::GetNowUs();
    int64_t mediaUs = 0;
    if (mediaClock->getMediaTime(nowUs, &mediaUs) != OK) {
        return JNI_FALSE;
    }

    env->SetLongField(timestamp, gFields.mediaTimestampMediaTimeUsID,
            (jlong)mediaUs);
    env->SetLongField(timestamp, gFields.mediaTimestampNanoTimeID,
            (jlong)(nowUs * 1000));
    env->SetFloatField(timestamp, gFields.mediaTimestampClockRateID,
            (jfloat)mediaClock->getPlaybackRate());
    return JNI_TRUE;
}

static jlong android_media_MediaSync_native_getPlayTimeForPendingAudioFrames(
        JNIEnv *env, jobject thiz) {
    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
    }

    int64_t playTimeUs = 0;
    status_t err = sync->getPlayTimeForPendingAudioFrames(&playTimeUs);
    if (err != NO_ERROR) {
        throwExceptionAsNecessary(env, err);
    }
    return (jlong)playTimeUs;
}

static jfloat android_media_MediaSync_setPlaybackParams(
        JNIEnv *env, jobject thiz, jobject params) {
    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return (jfloat)0.f;
    }

    PlaybackParams pbs;
    pbs.fillFromJobject(env, gPlaybackParamsFields, params);
    ALOGV("setPlaybackParams: %d:%f %d:%f %d:%u %d:%u",
            pbs.speedSet, pbs.audioRate.mSpeed,
            pbs.pitchSet, pbs.audioRate.mPitch,
            pbs.audioFallbackModeSet, pbs.audioRate.mFallbackMode,
            pbs.audioStretchModeSet, pbs.audioRate.mStretchMode);

    AudioPlaybackRate rate;
    sync->getPlaybackParams(&rate);
    bool updatedRate = false;
    if (pbs.speedSet) {
        rate.mSpeed = pbs.audioRate.mSpeed;
        updatedRate = true;
    }
    if (pbs.pitchSet) {
        rate.mPitch = pbs.audioRate.mPitch;
        updatedRate = true;
    }
    if (pbs.audioFallbackModeSet) {
        rate.mFallbackMode = pbs.audioRate.mFallbackMode;
        updatedRate = true;
    }
    if (pbs.audioStretchModeSet) {
        rate.mStretchMode = pbs.audioRate.mStretchMode;
        updatedRate = true;
    }
    if (updatedRate) {
        status_t err = sync->setPlaybackParams(rate);
        if (err != OK) {
            throwExceptionAsNecessary(env, err);
            return (jfloat)0.f;
        }
    }

    sp<const MediaClock> mediaClock = sync->getMediaClock();
    if (mediaClock == NULL) {
        return (jfloat)0.f;
    }

    return (jfloat)mediaClock->getPlaybackRate();
}

static jobject android_media_MediaSync_getPlaybackParams(
        JNIEnv *env, jobject thiz) {
    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    PlaybackParams pbs;
    AudioPlaybackRate &audioRate = pbs.audioRate;
    sync->getPlaybackParams(&audioRate);
    ALOGV("getPlaybackParams: %f %f %d %d",
            audioRate.mSpeed, audioRate.mPitch, audioRate.mFallbackMode, audioRate.mStretchMode);

    pbs.speedSet = true;
    pbs.pitchSet = true;
    pbs.audioFallbackModeSet = true;
    pbs.audioStretchModeSet = true;

    return pbs.asJobject(env, gPlaybackParamsFields);
}

static jfloat android_media_MediaSync_setSyncParams(
        JNIEnv *env, jobject thiz, jobject params) {
    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return (jfloat)0.f;
    }

    SyncParams scs;
    scs.fillFromJobject(env, gSyncParamsFields, params);
    ALOGV("setSyncParams: %d:%d %d:%d %d:%f %d:%f",
            scs.syncSourceSet, scs.sync.mSource,
            scs.audioAdjustModeSet, scs.sync.mAudioAdjustMode,
            scs.toleranceSet, scs.sync.mTolerance,
            scs.frameRateSet, scs.frameRate);

    AVSyncSettings avsync;
    sync->getSyncParams(&avsync);
    bool updatedSync = false;
    status_t err = OK;
    if (scs.syncSourceSet) {
        avsync.mSource = scs.sync.mSource;
        updatedSync = true;
    }
    if (scs.audioAdjustModeSet) {
        avsync.mAudioAdjustMode = scs.sync.mAudioAdjustMode;
        updatedSync = true;
    }
    if (scs.toleranceSet) {
        avsync.mTolerance = scs.sync.mTolerance;
        updatedSync = true;
    }
    if (updatedSync) {
        err = sync->setSyncParams(avsync);
    }

    if (scs.frameRateSet && err == OK) {
        err = sync->setVideoFrameRateHint(scs.frameRate);
    }
    if (err != OK) {
        throwExceptionAsNecessary(env, err);
        return (jfloat)0.f;
    }

    sp<const MediaClock> mediaClock = sync->getMediaClock();
    if (mediaClock == NULL) {
        return (jfloat)0.f;
    }

    return (jfloat)mediaClock->getPlaybackRate();
}

static jobject android_media_MediaSync_getSyncParams(JNIEnv *env, jobject thiz) {
    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    SyncParams scs;
    sync->getSyncParams(&scs.sync);
    scs.frameRate = sync->getVideoFrameRate();

    ALOGV("getSyncParams: %d %d %f %f",
            scs.sync.mSource, scs.sync.mAudioAdjustMode, scs.sync.mTolerance, scs.frameRate);

    // sanity check params
    if (scs.sync.mSource >= AVSYNC_SOURCE_MAX
            || scs.sync.mAudioAdjustMode >= AVSYNC_AUDIO_ADJUST_MODE_MAX
            || scs.sync.mTolerance < 0.f
            || scs.sync.mTolerance >= AVSYNC_TOLERANCE_MAX) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    scs.syncSourceSet = true;
    scs.audioAdjustModeSet = true;
    scs.toleranceSet = true;
    scs.frameRateSet = scs.frameRate >= 0.f;

    return scs.asJobject(env, gSyncParamsFields);
}

static void android_media_MediaSync_native_flush(JNIEnv *env, jobject thiz) {
    sp<JMediaSync> sync = getMediaSync(env, thiz);
    if (sync == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sync->flush();
}

static void android_media_MediaSync_native_init(JNIEnv *env) {
    ScopedLocalRef<jclass> clazz(env, env->FindClass("android/media/MediaSync"));
    CHECK(clazz.get() != NULL);

    gFields.context = env->GetFieldID(clazz.get(), "mNativeContext", "J");
    CHECK(gFields.context != NULL);

    clazz.reset(env->FindClass("android/media/MediaTimestamp"));
    CHECK(clazz.get() != NULL);

    gFields.mediaTimestampMediaTimeUsID =
        env->GetFieldID(clazz.get(), "mediaTimeUs", "J");
    CHECK(gFields.mediaTimestampMediaTimeUsID != NULL);

    gFields.mediaTimestampNanoTimeID =
        env->GetFieldID(clazz.get(), "nanoTime", "J");
    CHECK(gFields.mediaTimestampNanoTimeID != NULL);

    gFields.mediaTimestampClockRateID =
        env->GetFieldID(clazz.get(), "clockRate", "F");
    CHECK(gFields.mediaTimestampClockRateID != NULL);

    gSyncParamsFields.init(env);
    gPlaybackParamsFields.init(env);
}

static void android_media_MediaSync_native_setup(JNIEnv *env, jobject thiz) {
    sp<JMediaSync> sync = new JMediaSync();

    setMediaSync(env, thiz, sync);
}

static void android_media_MediaSync_native_finalize(JNIEnv *env, jobject thiz) {
    android_media_MediaSync_release(env, thiz);
}

static JNINativeMethod gMethods[] = {
    { "native_setSurface",
      "(Landroid/view/Surface;)V",
      (void *)android_media_MediaSync_native_setSurface },

    { "native_setAudioTrack",
      "(Landroid/media/AudioTrack;)V",
      (void *)android_media_MediaSync_native_setAudioTrack },

    { "createInputSurface", "()Landroid/view/Surface;",
      (void *)android_media_MediaSync_createInputSurface },

    { "native_updateQueuedAudioData",
      "(IJ)V",
      (void *)android_media_MediaSync_native_updateQueuedAudioData },

    { "native_getTimestamp",
      "(Landroid/media/MediaTimestamp;)Z",
      (void *)android_media_MediaSync_native_getTimestamp },

    { "native_getPlayTimeForPendingAudioFrames",
      "()J",
      (void *)android_media_MediaSync_native_getPlayTimeForPendingAudioFrames },

    { "native_flush", "()V", (void *)android_media_MediaSync_native_flush },

    { "native_init", "()V", (void *)android_media_MediaSync_native_init },

    { "native_setup", "()V", (void *)android_media_MediaSync_native_setup },

    { "native_release", "()V", (void *)android_media_MediaSync_release },

    { "native_setPlaybackParams", "(Landroid/media/PlaybackParams;)F",
      (void *)android_media_MediaSync_setPlaybackParams },

    { "getPlaybackParams", "()Landroid/media/PlaybackParams;",
      (void *)android_media_MediaSync_getPlaybackParams },

    { "native_setSyncParams", "(Landroid/media/SyncParams;)F",
      (void *)android_media_MediaSync_setSyncParams },

    { "getSyncParams", "()Landroid/media/SyncParams;",
      (void *)android_media_MediaSync_getSyncParams },

    { "native_finalize", "()V", (void *)android_media_MediaSync_native_finalize },
};

int register_android_media_MediaSync(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(
                   env, "android/media/MediaSync", gMethods, NELEM(gMethods));
}
