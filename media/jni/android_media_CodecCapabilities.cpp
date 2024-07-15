/*
 * Copyright 2024, The Android Open Source Project
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
#define LOG_TAG "MediaCodec-JNI"

#include "android_runtime/AndroidRuntime.h"
#include "jni.h"

#include <media/AudioCapabilities.h>
#include <media/EncoderCapabilities.h>
#include <media/VideoCapabilities.h>
#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/JNIHelp.h>

namespace android {

struct fields_t {
    jfieldID audioCapsContext;
    jfieldID videoCapsContext;
    jfieldID encoderCapsContext;
};
static fields_t fields;

// Getters

static AudioCapabilities* getAudioCapabilities(JNIEnv *env, jobject thiz) {
    AudioCapabilities* const p = (AudioCapabilities*)env->GetLongField(
            thiz, fields.audioCapsContext);
    return p;
}

static VideoCapabilities* getVideoCapabilities(JNIEnv *env, jobject thiz) {
    VideoCapabilities* const p = (VideoCapabilities*)env->GetLongField(
            thiz, fields.videoCapsContext);
    return p;
}

static EncoderCapabilities* getEncoderCapabilities(JNIEnv *env, jobject thiz) {
    EncoderCapabilities* const p = (EncoderCapabilities*)env->GetLongField(
            thiz, fields.encoderCapsContext);
    return p;
}

// Utils

static jobject convertToJavaIntRange(JNIEnv *env, const Range<int32_t>& range) {
    jclass helperClazz = env->FindClass("android/media/MediaCodecInfo$GenericHelper");
    jmethodID constructIntegerRangeID = env->GetStaticMethodID(helperClazz, "constructIntegerRange",
            "(II)Landroid/util/Range;");
    jobject jRange = env->CallStaticObjectMethod(helperClazz, constructIntegerRangeID,
            range.lower(), range.upper());

    return jRange;
}

static jobject convertToJavaDoubleRange(JNIEnv *env, const Range<double>& range) {
    jclass helperClazz = env->FindClass("android/media/MediaCodecInfo$GenericHelper");
    jmethodID constructDoubleRangeID = env->GetStaticMethodID(helperClazz, "constructDoubleRange",
            "(DD)Landroid/util/Range;");
    jobject jRange = env->CallStaticObjectMethod(helperClazz, constructDoubleRangeID,
            range.lower(), range.upper());
    return jRange;
}

// Converters between Java objects and native instances

static VideoCapabilities::PerformancePoint convertToNativePerformancePoint(
        JNIEnv *env, jobject pp) {
    if (pp == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
    }

    jclass clazz = env->FindClass(
            "android/media/MediaCodecInfo$VideoCapabilities$PerformancePoint");
    CHECK(clazz != NULL);
    CHECK(env->IsInstanceOf(pp, clazz));

    jmethodID getWidthID = env->GetMethodID(clazz, "getWidth", "()I");
    CHECK(getWidthID != NULL);
    jint width = env->CallIntMethod(pp, getWidthID);

    jmethodID getHeightID = env->GetMethodID(clazz, "getHeight", "()I");
    CHECK(getHeightID != NULL);
    jint height = env->CallIntMethod(pp, getHeightID);

    jmethodID getMaxFrameRateID = env->GetMethodID(clazz, "getMaxFrameRate", "()I");
    CHECK(getMaxFrameRateID != NULL);
    jint maxFrameRate = env->CallIntMethod(pp, getMaxFrameRateID);

    jmethodID getMaxMacroBlockRateID = env->GetMethodID(clazz, "getMaxMacroBlockRate", "()J");
    CHECK(getMaxMacroBlockRateID != NULL);
    jlong maxMacroBlockRate = env->CallLongMethod(pp, getMaxMacroBlockRateID);

    jmethodID getBlockWidthID = env->GetMethodID(clazz, "getBlockWidth", "()I");
    CHECK(getBlockWidthID != NULL);
    jint blockWidth = env->CallIntMethod(pp, getBlockWidthID);

    jmethodID getBlockHeightID = env->GetMethodID(clazz, "getBlockHeight", "()I");
    CHECK(getBlockHeightID != NULL);
    jint blockHeight = env->CallIntMethod(pp, getBlockHeightID);

    return VideoCapabilities::PerformancePoint(VideoSize(blockWidth, blockHeight),
            width, height, maxFrameRate, maxMacroBlockRate);
}

}  // namespace android

// ----------------------------------------------------------------------------

using namespace android;

// AudioCapabilities

static void android_media_AudioCapabilities_native_init(JNIEnv *env, jobject /* thiz */) {
    jclass audioCapsImplClazz
            = env->FindClass("android/media/MediaCodecInfo$AudioCapabilities$AudioCapsNativeImpl");
    if (audioCapsImplClazz == NULL) {
        return;
    }

    fields.audioCapsContext = env->GetFieldID(audioCapsImplClazz, "mNativeContext", "J");
    if (fields.audioCapsContext == NULL) {
        return;
    }

    env->DeleteLocalRef(audioCapsImplClazz);
}

static jint android_media_AudioCapabilities_getMaxInputChannelCount(JNIEnv *env, jobject thiz) {
    AudioCapabilities* const audioCaps = getAudioCapabilities(env, thiz);
    if (audioCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    int32_t maxInputChannelCount = audioCaps->getMaxInputChannelCount();
    return maxInputChannelCount;
}

static jint android_media_AudioCapabilities_getMinInputChannelCount(JNIEnv *env, jobject thiz) {
    AudioCapabilities* const audioCaps = getAudioCapabilities(env, thiz);
    if (audioCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    int32_t minInputChannelCount = audioCaps->getMinInputChannelCount();
    return minInputChannelCount;
}

static jboolean android_media_AudioCapabilities_isSampleRateSupported(JNIEnv *env, jobject thiz,
        int sampleRate) {
    AudioCapabilities* const audioCaps = getAudioCapabilities(env, thiz);
    if (audioCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    bool res = audioCaps->isSampleRateSupported(sampleRate);
    return res;
}

// PerformancePoint

static jboolean android_media_VideoCapabilities_PerformancePoint_covers(JNIEnv *env, jobject thiz,
        jobject other) {
    VideoCapabilities::PerformancePoint pp0 = convertToNativePerformancePoint(env, thiz);
    VideoCapabilities::PerformancePoint pp1 = convertToNativePerformancePoint(env, other);

    bool res = pp0.covers(pp1);
    return res;
}

static jboolean android_media_VideoCapabilities_PerformancePoint_equals(JNIEnv *env, jobject thiz,
        jobject other) {
    VideoCapabilities::PerformancePoint pp0 = convertToNativePerformancePoint(env, thiz);
    VideoCapabilities::PerformancePoint pp1 = convertToNativePerformancePoint(env, other);

    bool res = pp0.equals(pp1);
    return res;
}

// VideoCapabilities

static void android_media_VideoCapabilities_native_init(JNIEnv *env, jobject /* thiz */) {
    jclass clazz
            = env->FindClass("android/media/MediaCodecInfo$VideoCapabilities$VideoCapsNativeImpl");
    if (clazz == NULL) {
        return;
    }

    fields.videoCapsContext = env->GetFieldID(clazz, "mNativeContext", "J");
    if (fields.videoCapsContext == NULL) {
        return;
    }

    env->DeleteLocalRef(clazz);
}

static jboolean android_media_VideoCapabilities_areSizeAndRateSupported(JNIEnv *env, jobject thiz,
        int32_t width, int32_t height, double frameRate) {
    VideoCapabilities* const videoCaps = getVideoCapabilities(env, thiz);
    if (videoCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    bool res = videoCaps->areSizeAndRateSupported(width, height, frameRate);
    return res;
}

static jboolean android_media_VideoCapabilities_isSizeSupported(JNIEnv *env, jobject thiz,
        int32_t width, int32_t height) {
    VideoCapabilities* const videoCaps = getVideoCapabilities(env, thiz);
    if (videoCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    bool res = videoCaps->isSizeSupported(width, height);
    return res;
}

static jobject android_media_VideoCapabilities_getAchievableFrameRatesFor(JNIEnv *env, jobject thiz,
        int32_t width, int32_t height) {
    VideoCapabilities* const videoCaps = getVideoCapabilities(env, thiz);
    if (videoCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }

    std::optional<Range<double>> frameRates = videoCaps->getAchievableFrameRatesFor(width, height);
    if (!frameRates) {
        return NULL;
    }
    jobject jFrameRates = convertToJavaDoubleRange(env, frameRates.value());
    return jFrameRates;
}

static jobject android_media_VideoCapabilities_getSupportedFrameRatesFor(JNIEnv *env, jobject thiz,
        int32_t width, int32_t height) {
    VideoCapabilities* const videoCaps = getVideoCapabilities(env, thiz);
    if (videoCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }

    std::optional<Range<double>> frameRates = videoCaps->getSupportedFrameRatesFor(width, height);
    if (!frameRates) {
        return NULL;
    }
    jobject jFrameRates = convertToJavaDoubleRange(env, frameRates.value());
    return jFrameRates;
}

static jobject android_media_VideoCapabilities_getSupportedWidthsFor(JNIEnv *env, jobject thiz,
        int32_t height) {
    VideoCapabilities* const videoCaps = getVideoCapabilities(env, thiz);
    if (videoCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }

    std::optional<Range<int32_t>> supportedWidths = videoCaps->getSupportedWidthsFor(height);
    if (!supportedWidths) {
        return NULL;
    }
    jobject jSupportedWidths = convertToJavaIntRange(env, supportedWidths.value());

    return jSupportedWidths;
}

static jobject android_media_VideoCapabilities_getSupportedHeightsFor(JNIEnv *env, jobject thiz,
        int32_t width) {
    VideoCapabilities* const videoCaps = getVideoCapabilities(env, thiz);
    if (videoCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }

    std::optional<Range<int32_t>> supportedHeights = videoCaps->getSupportedHeightsFor(width);
    if (!supportedHeights) {
        return NULL;
    }
    jobject jSupportedHeights = convertToJavaIntRange(env, supportedHeights.value());

    return jSupportedHeights;
}

static jint android_media_VideoCapabilities_getSmallerDimensionUpperLimit(JNIEnv *env,
        jobject thiz) {
    VideoCapabilities* const videoCaps = getVideoCapabilities(env, thiz);
    if (videoCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    int smallerDimensionUpperLimit = videoCaps->getSmallerDimensionUpperLimit();
    return smallerDimensionUpperLimit;
}

// EncoderCapabilities

static void android_media_EncoderCapabilities_native_init(JNIEnv *env, jobject /* thiz */) {
    jclass clazz = env->FindClass(
            "android/media/MediaCodecInfo$EncoderCapabilities$EncoderCapsNativeImpl");
    if (clazz == NULL) {
        return;
    }

    fields.encoderCapsContext = env->GetFieldID(clazz, "mNativeContext", "J");
    if (fields.encoderCapsContext == NULL) {
        return;
    }

    env->DeleteLocalRef(clazz);
}

static jboolean android_media_EncoderCapabilities_isBitrateModeSupported(JNIEnv *env, jobject thiz,
        int mode) {
    EncoderCapabilities* const encoderCaps = getEncoderCapabilities(env, thiz);
    if (encoderCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    bool res = encoderCaps->isBitrateModeSupported(mode);
    return res;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gAudioCapsMethods[] = {
    {"native_init", "()V", (void *)android_media_AudioCapabilities_native_init},
    {"native_getMaxInputChannelCount", "()I", (void *)android_media_AudioCapabilities_getMaxInputChannelCount},
    {"native_getMinInputChannelCount", "()I", (void *)android_media_AudioCapabilities_getMinInputChannelCount},
    {"native_isSampleRateSupported", "(I)Z", (void *)android_media_AudioCapabilities_isSampleRateSupported}
};

static const JNINativeMethod gPerformancePointMethods[] = {
    {"native_covers", "(Landroid/media/MediaCodecInfo$VideoCapabilities$PerformancePoint;)Z", (void *)android_media_VideoCapabilities_PerformancePoint_covers},
    {"native_equals", "(Landroid/media/MediaCodecInfo$VideoCapabilities$PerformancePoint;)Z", (void *)android_media_VideoCapabilities_PerformancePoint_equals},
};

static const JNINativeMethod gVideoCapsMethods[] = {
    {"native_init", "()V", (void *)android_media_VideoCapabilities_native_init},
    {"native_areSizeAndRateSupported", "(IID)Z", (void *)android_media_VideoCapabilities_areSizeAndRateSupported},
    {"native_isSizeSupported", "(II)Z", (void *)android_media_VideoCapabilities_isSizeSupported},
    {"native_getAchievableFrameRatesFor", "(II)Landroid/util/Range;", (void *)android_media_VideoCapabilities_getAchievableFrameRatesFor},
    {"native_getSupportedFrameRatesFor", "(II)Landroid/util/Range;", (void *)android_media_VideoCapabilities_getSupportedFrameRatesFor},
    {"native_getSupportedWidthsFor", "(I)Landroid/util/Range;", (void *)android_media_VideoCapabilities_getSupportedWidthsFor},
    {"native_getSupportedHeightsFor", "(I)Landroid/util/Range;", (void *)android_media_VideoCapabilities_getSupportedHeightsFor},
    {"native_getSmallerDimensionUpperLimit", "()I", (void *)android_media_VideoCapabilities_getSmallerDimensionUpperLimit}
};

static const JNINativeMethod gEncoderCapsMethods[] = {
    {"native_init", "()V", (void *)android_media_EncoderCapabilities_native_init},
    {"native_isBitrateModeSupported", "(I)Z", (void *)android_media_EncoderCapabilities_isBitrateModeSupported}
};

int register_android_media_CodecCapabilities(JNIEnv *env) {
    int result = AndroidRuntime::registerNativeMethods(env,
            "android/media/MediaCodecInfo$AudioCapabilities$AudioCapsNativeImpl",
            gAudioCapsMethods, NELEM(gAudioCapsMethods));
    if (result != JNI_OK) {
        return result;
    }

    result = AndroidRuntime::registerNativeMethods(env,
            "android/media/MediaCodecInfo$VideoCapabilities$PerformancePoint",
            gPerformancePointMethods, NELEM(gPerformancePointMethods));
    if (result != JNI_OK) {
        return result;
    }

    result = AndroidRuntime::registerNativeMethods(env,
            "android/media/MediaCodecInfo$VideoCapabilities$VideoCapsNativeImpl",
            gVideoCapsMethods, NELEM(gVideoCapsMethods));
    if (result != JNI_OK) {
        return result;
    }

    result = AndroidRuntime::registerNativeMethods(env,
            "android/media/MediaCodecInfo$EncoderCapabilities$EncoderCapsNativeImpl",
            gEncoderCapsMethods, NELEM(gEncoderCapsMethods));
    if (result != JNI_OK) {
        return result;
    }

    return result;
}