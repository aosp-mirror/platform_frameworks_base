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

#include "android_media_CodecCapabilities.h"
#include "android_media_Streams.h"
#include "android_runtime/AndroidRuntime.h"
#include "jni.h"

#include <media/AudioCapabilities.h>
#include <media/CodecCapabilities.h>
#include <media/EncoderCapabilities.h>
#include <media/VideoCapabilities.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <utils/Log.h>

namespace android {

struct fields_t {
    jfieldID audioCapsContext;
    jfieldID videoCapsContext;
    jfieldID encoderCapsContext;
    jfieldID codecCapsContext;
};
static fields_t fields;

// JCodecCapabilities

JCodecCapabilities::JCodecCapabilities(std::shared_ptr<CodecCapabilities> codecCaps)
        : mCodecCaps(codecCaps) {}

std::shared_ptr<CodecCapabilities> JCodecCapabilities::getCodecCaps() const {
    return mCodecCaps;
}

int32_t JCodecCapabilities::getMaxSupportedInstances() const {
    return mCodecCaps->getMaxSupportedInstances();
}

std::string JCodecCapabilities::getMediaType() const {
    return mCodecCaps->getMediaType();
}

bool JCodecCapabilities::isFeatureRequired(const std::string& name) const {
    return mCodecCaps->isFeatureRequired(name);
}

bool JCodecCapabilities::isFeatureSupported(const std::string& name) const {
    return mCodecCaps->isFeatureSupported(name);
}

bool JCodecCapabilities::isFormatSupported(const sp<AMessage> &format) const {
    return mCodecCaps->isFormatSupported(format);
}

bool JCodecCapabilities::isRegular() const {
    return mCodecCaps->isRegular();
}

std::vector<std::string> JCodecCapabilities::validFeatures() const {
    return mCodecCaps->validFeatures();
}

// Setter

static sp<JCodecCapabilities> setCodecCapabilities(JNIEnv *env, jobject thiz,
        const sp<JCodecCapabilities>& jCodecCaps) {
    sp<JCodecCapabilities> old
            = (JCodecCapabilities*)env->GetLongField(thiz, fields.codecCapsContext);
    if (jCodecCaps != NULL) {
        jCodecCaps->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }
    env->SetLongField(thiz, fields.codecCapsContext, (jlong)jCodecCaps.get());
    return old;
}

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

static sp<JCodecCapabilities> getCodecCapabilities(JNIEnv *env, jobject thiz) {
    JCodecCapabilities* const p = (JCodecCapabilities*)env->GetLongField(
            thiz, fields.codecCapsContext);
    return sp<JCodecCapabilities>(p);
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

static jobjectArray convertToJavaIntRangeArray(JNIEnv *env,
        const std::vector<Range<int32_t>>& ranges) {
    jclass rangeClazz = env->FindClass("android/util/Range");
    CHECK(rangeClazz != NULL);
    jobjectArray jRanges = env->NewObjectArray(ranges.size(), rangeClazz, NULL);
    for (int i = 0; i < ranges.size(); i++) {
        Range<int32_t> range = ranges.at(i);
        jobject jRange = convertToJavaIntRange(env, range);
        env->SetObjectArrayElement(jRanges, i, jRange);
        env->DeleteLocalRef(jRange);
        jRange = NULL;
    }
    return jRanges;
}

// Converters between Java objects and native instances

// The Java AudioCapabilities object keep bitrateRange, sampleRates, sampleRateRanges
// and inputChannelRanges in it to prevent reconstruction when called the getters functions.
static jobject convertToJavaAudioCapabilities(
        JNIEnv *env, std::shared_ptr<AudioCapabilities> audioCaps) {
    if (audioCaps == nullptr) {
        return NULL;
    }

    // construct Java bitrateRange
    const Range<int32_t>& bitrateRange = audioCaps->getBitrateRange();
    jobject jBitrateRange = convertToJavaIntRange(env, bitrateRange);

    // construct Java sampleRates array
    const std::vector<int32_t>& sampleRates = audioCaps->getSupportedSampleRates();
    jintArray jSampleRates = env->NewIntArray(sampleRates.size());
    for (size_t i = 0; i < sampleRates.size(); ++i) {
        jint val = sampleRates.at(i);
        env->SetIntArrayRegion(jSampleRates, i, 1, &val);
    }

    // construct Java sampleRateRanges
    const std::vector<Range<int32_t>>& sampleRateRanges = audioCaps->getSupportedSampleRateRanges();
    jobjectArray jSampleRateRanges = convertToJavaIntRangeArray(env, sampleRateRanges);

    // construct Java inputChannelRanges
    const std::vector<Range<int32_t>>& inputChannelRanges = audioCaps->getInputChannelCountRanges();
    jobjectArray jInputChannelRanges = convertToJavaIntRangeArray(env, inputChannelRanges);

    // construct Java AudioCapsNativeImpl
    jclass audioCapsImplClazz
            = env->FindClass("android/media/MediaCodecInfo$AudioCapabilities$AudioCapsNativeImpl");
    CHECK(audioCapsImplClazz != NULL);
    jmethodID audioCapsImplConstructID = env->GetMethodID(audioCapsImplClazz, "<init>",
            "(Landroid/util/Range;"
            "[I"
            "[Landroid/util/Range;"
            "[Landroid/util/Range;)V");
    jobject jAudioCapsImpl = env->NewObject(audioCapsImplClazz, audioCapsImplConstructID,
            jBitrateRange, jSampleRates, jSampleRateRanges, jInputChannelRanges);
    // The native AudioCapabilities won't be destructed until process ends.
    env->SetLongField(jAudioCapsImpl, fields.audioCapsContext, (jlong)audioCaps.get());

    // construct Java AudioCapabilities
    jclass audioCapsClazz
            = env->FindClass("android/media/MediaCodecInfo$AudioCapabilities");
    CHECK(audioCapsClazz != NULL);
    jmethodID audioCapsConstructID = env->GetMethodID(audioCapsClazz, "<init>",
            "(Landroid/media/MediaCodecInfo$AudioCapabilities$AudioCapsIntf;)V");
    jobject jAudioCaps = env->NewObject(audioCapsClazz, audioCapsConstructID, jAudioCapsImpl);

    env->DeleteLocalRef(jBitrateRange);
    jBitrateRange = NULL;

    env->DeleteLocalRef(jSampleRates);
    jSampleRates = NULL;

    env->DeleteLocalRef(jSampleRateRanges);
    jSampleRateRanges = NULL;

    env->DeleteLocalRef(jInputChannelRanges);
    jInputChannelRanges = NULL;

    env->DeleteLocalRef(jAudioCapsImpl);
    jAudioCapsImpl = NULL;

    return jAudioCaps;
}

// convert native PerformancePoints to Java objects
static jobject convertToJavaPerformancePoints(JNIEnv *env,
        const std::vector<VideoCapabilities::PerformancePoint>& performancePoints) {
    jclass performancePointClazz = env->FindClass(
            "android/media/MediaCodecInfo$VideoCapabilities$PerformancePoint");
    CHECK(performancePointClazz != NULL);
    jmethodID performancePointConstructID = env->GetMethodID(performancePointClazz, "<init>",
            "(IIIJII)V");

    jobjectArray jPerformancePoints = env->NewObjectArray(performancePoints.size(),
            performancePointClazz, NULL);
    int i = 0;
    for (auto it = performancePoints.begin(); it != performancePoints.end(); ++it, ++i) {
        jobject jPerformancePoint = env->NewObject(performancePointClazz,
                performancePointConstructID, it->getWidth(),
                it->getHeight(), it->getMaxFrameRate(),
                it->getMaxMacroBlockRate(), it->getBlockSize().getWidth(),
                it->getBlockSize().getHeight());

        env->SetObjectArrayElement(jPerformancePoints, i, jPerformancePoint);

        env->DeleteLocalRef(jPerformancePoint);
    }

    jclass helperClazz = env->FindClass("android/media/MediaCodecInfo$GenericHelper");
    CHECK(helperClazz != NULL);
    jmethodID asListID = env->GetStaticMethodID(helperClazz, "constructPerformancePointList",
            "([Landroid/media/MediaCodecInfo$VideoCapabilities$PerformancePoint;)Ljava/util/List;");
    CHECK(asListID != NULL);
    jobject jList = env->CallStaticObjectMethod(helperClazz, asListID, jPerformancePoints);

    return jList;
}

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

static jobject convertToJavaVideoCapabilities(JNIEnv *env,
        std::shared_ptr<VideoCapabilities> videoCaps) {
    if (videoCaps == nullptr) {
        return NULL;
    }

    // get Java bitrateRange
    const Range<int32_t>& bitrateRange = videoCaps->getBitrateRange();
    jobject jBitrateRange = convertToJavaIntRange(env, bitrateRange);

    // get Java widthRange
    const Range<int32_t>& widthRange = videoCaps->getSupportedWidths();
    jobject jWidthRange = convertToJavaIntRange(env, widthRange);

    // get Java heightRange
    const Range<int32_t>& heightRange = videoCaps->getSupportedHeights();
    jobject jHeightRange = convertToJavaIntRange(env, heightRange);

    // get Java frameRateRange
    const Range<int32_t>& frameRateRange = videoCaps->getSupportedFrameRates();
    jobject jFrameRateRange = convertToJavaIntRange(env, frameRateRange);

    // get Java performancePoints
    const std::vector<VideoCapabilities::PerformancePoint>& performancePoints
            = videoCaps->getSupportedPerformancePoints();
    jobject jPerformancePoints = convertToJavaPerformancePoints(env, performancePoints);

    // get width alignment
    int32_t widthAlignment = videoCaps->getWidthAlignment();

    // get height alignment
    int32_t heightAlignment = videoCaps->getHeightAlignment();

    // get Java VideoCapsNativeImpl
    jclass videoCapsImplClazz = env->FindClass(
            "android/media/MediaCodecInfo$VideoCapabilities$VideoCapsNativeImpl");
    CHECK(videoCapsImplClazz != NULL);
    jmethodID videoCapsImplConstructID = env->GetMethodID(videoCapsImplClazz, "<init>",
            "(Landroid/util/Range;"
            "Landroid/util/Range;"
            "Landroid/util/Range;"
            "Landroid/util/Range;"
            "Ljava/util/List;II)V");
    jobject jVideoCapsImpl = env->NewObject(videoCapsImplClazz, videoCapsImplConstructID,
            jBitrateRange, jWidthRange, jHeightRange, jFrameRateRange, jPerformancePoints,
            widthAlignment, heightAlignment);
    // The native VideoCapabilities won't be destructed until process ends.
    env->SetLongField(jVideoCapsImpl, fields.videoCapsContext, (jlong)videoCaps.get());

    // get Java VideoCapabilities
    jclass videoCapsClazz
            = env->FindClass("android/media/MediaCodecInfo$VideoCapabilities");
    CHECK(videoCapsClazz != NULL);
    jmethodID videoCapsConstructID = env->GetMethodID(videoCapsClazz, "<init>",
            "(Landroid/media/MediaCodecInfo$VideoCapabilities$VideoCapsIntf;)V");
    jobject jVideoCaps = env->NewObject(videoCapsClazz, videoCapsConstructID, jVideoCapsImpl);

    env->DeleteLocalRef(jBitrateRange);
    jBitrateRange = NULL;

    env->DeleteLocalRef(jWidthRange);
    jWidthRange = NULL;

    env->DeleteLocalRef(jHeightRange);
    jHeightRange = NULL;

    env->DeleteLocalRef(jFrameRateRange);
    jFrameRateRange = NULL;

    env->DeleteLocalRef(jPerformancePoints);
    jPerformancePoints = NULL;

    env->DeleteLocalRef(jVideoCapsImpl);
    jVideoCapsImpl = NULL;

    return jVideoCaps;
}

static jobject convertToJavaEncoderCapabilities(JNIEnv *env,
        std::shared_ptr<EncoderCapabilities> encoderCaps) {
    if (encoderCaps == nullptr) {
        return NULL;
    }

    // get quality range
    const Range<int>& qualityRange = encoderCaps->getQualityRange();
    jobject jQualityRange = convertToJavaIntRange(env, qualityRange);

    // get complexity range
    const Range<int>& complexityRange = encoderCaps->getComplexityRange();
    jobject jComplexityRange = convertToJavaIntRange(env, complexityRange);

    // construct java EncoderCapsNativeImpl
    jclass encoderCapsImplClazz = env->FindClass(
            "android/media/MediaCodecInfo$EncoderCapabilities$EncoderCapsNativeImpl");
    CHECK(encoderCapsImplClazz != NULL);
    jmethodID encoderCapsImplConstructID = env->GetMethodID(encoderCapsImplClazz, "<init>",
            "(Landroid/util/Range;Landroid/util/Range;)V");
    jobject jEncoderCapsImpl = env->NewObject(encoderCapsImplClazz, encoderCapsImplConstructID,
            jQualityRange, jComplexityRange);
    // The native EncoderCapabilities won't be destructed until process ends.
    env->SetLongField(jEncoderCapsImpl, fields.encoderCapsContext, (jlong)encoderCaps.get());

    // construct java EncoderCapabilities object
    jclass encoderCapsClazz
            = env->FindClass("android/media/MediaCodecInfo$EncoderCapabilities");
    CHECK(encoderCapsClazz != NULL);
    jmethodID encoderCapsConstructID = env->GetMethodID(encoderCapsClazz, "<init>",
            "(Landroid/media/MediaCodecInfo$EncoderCapabilities$EncoderCapsIntf;)V");
    jobject jEncoderCaps = env->NewObject(encoderCapsClazz, encoderCapsConstructID,
            jEncoderCapsImpl);

    env->DeleteLocalRef(jQualityRange);
    jQualityRange = NULL;

    env->DeleteLocalRef(jComplexityRange);
    jComplexityRange = NULL;

    env->DeleteLocalRef(jEncoderCapsImpl);
    jEncoderCapsImpl = NULL;

    return jEncoderCaps;
}

// Java CodecCapsNativeImpl keeps the defaultFormat, profileLevels, colorFormats, audioCapabilities,
// videoCapabilities and encoderCapabilities in it to prevent reconsturction when called by getter.
static jobject convertToJavaCodecCapsNativeImpl(
            JNIEnv *env, std::shared_ptr<CodecCapabilities> codecCaps) {
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    // Construct defaultFormat
    sp<AMessage> defaultFormat = codecCaps->getDefaultFormat();

    jobject formatMap = NULL;
    if (ConvertMessageToMap(env, defaultFormat, &formatMap)) {
        return NULL;
    }

    ScopedLocalRef<jclass> mediaFormatClass{env, env->FindClass("android/media/MediaFormat")};
    ScopedLocalRef<jobject> jDefaultFormat{env, env->NewObject(
            mediaFormatClass.get(),
            env->GetMethodID(mediaFormatClass.get(), "<init>", "(Ljava/util/Map;)V"),
            formatMap)};

    env->DeleteLocalRef(formatMap);
    formatMap = NULL;

    // Construct Java ProfileLevelArray
    std::vector<ProfileLevel> profileLevels = codecCaps->getProfileLevels();

    jclass profileLevelClazz =
        env->FindClass("android/media/MediaCodecInfo$CodecProfileLevel");
    CHECK(profileLevelClazz != NULL);

    jobjectArray profileLevelArray =
            env->NewObjectArray(profileLevels.size(), profileLevelClazz, NULL);

    jfieldID profileField =
            env->GetFieldID(profileLevelClazz, "profile", "I");
    jfieldID levelField =
            env->GetFieldID(profileLevelClazz, "level", "I");

    for (size_t i = 0; i < profileLevels.size(); ++i) {
        const ProfileLevel &src = profileLevels.at(i);

        jobject profileLevelObj = env->AllocObject(profileLevelClazz);

        env->SetIntField(profileLevelObj, profileField, src.mProfile);
        env->SetIntField(profileLevelObj, levelField, src.mLevel);

        env->SetObjectArrayElement(profileLevelArray, i, profileLevelObj);

        env->DeleteLocalRef(profileLevelObj);
        profileLevelObj = NULL;
    }

    // Construct ColorFormatArray
    std::vector<uint32_t> colorFormats = codecCaps->getColorFormats();

    jintArray colorFormatsArray = env->NewIntArray(colorFormats.size());
    env->SetIntArrayRegion(colorFormatsArray, 0, colorFormats.size(),
            reinterpret_cast<jint*>(colorFormats.data()));

    // Construct and set AudioCapabilities
    std::shared_ptr<AudioCapabilities> audioCaps = codecCaps->getAudioCapabilities();
    jobject jAudioCaps = convertToJavaAudioCapabilities(env, audioCaps);

    // Set VideoCapabilities
    std::shared_ptr<VideoCapabilities> videoCaps = codecCaps->getVideoCapabilities();
    jobject jVideoCaps = convertToJavaVideoCapabilities(env, videoCaps);

    // Set EncoderCapabilities
    std::shared_ptr<EncoderCapabilities> encoderCaps = codecCaps->getEncoderCapabilities();
    jobject jEncoderCaps = convertToJavaEncoderCapabilities(env, encoderCaps);

    // Construct CodecCapsNativeImpl
    jclass codecCapsImplClazz =
        env->FindClass("android/media/MediaCodecInfo$CodecCapabilities$CodecCapsNativeImpl");
    CHECK(codecCapsImplClazz != NULL);
    jmethodID codecCapsImplConstructID = env->GetMethodID(codecCapsImplClazz, "<init>",
                "([Landroid/media/MediaCodecInfo$CodecProfileLevel;[I"
                "Landroid/media/MediaFormat;"
                "Landroid/media/MediaCodecInfo$AudioCapabilities;"
                "Landroid/media/MediaCodecInfo$VideoCapabilities;"
                "Landroid/media/MediaCodecInfo$EncoderCapabilities;)V");
    jobject javaCodecCapsImpl = env->NewObject(codecCapsImplClazz, codecCapsImplConstructID,
            profileLevelArray, colorFormatsArray, jDefaultFormat.get(),
            jAudioCaps, jVideoCaps, jEncoderCaps);

    // Construct JCodecCapabilities and hold the codecCaps in it
    sp<JCodecCapabilities> jCodecCaps = sp<JCodecCapabilities>::make(codecCaps);
    setCodecCapabilities(env, javaCodecCapsImpl, jCodecCaps);

    env->DeleteLocalRef(profileLevelArray);
    profileLevelArray = NULL;

    env->DeleteLocalRef(colorFormatsArray);
    colorFormatsArray = NULL;

    env->DeleteLocalRef(jAudioCaps);
    jAudioCaps = NULL;

    env->DeleteLocalRef(jVideoCaps);
    jVideoCaps = NULL;

    env->DeleteLocalRef(jEncoderCaps);
    jEncoderCaps = NULL;

    return javaCodecCapsImpl;
}

jobject convertToJavaCodecCapabiliites(
        JNIEnv *env, std::shared_ptr<CodecCapabilities> codecCaps) {
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    jobject javaCodecCapsImpl = convertToJavaCodecCapsNativeImpl(env, codecCaps);

    // Construct CodecCapabilities
    jclass codecCapsClazz = env->FindClass("android/media/MediaCodecInfo$CodecCapabilities");
    CHECK(codecCapsClazz != NULL);

    jmethodID codecCapsConstructID = env->GetMethodID(codecCapsClazz, "<init>",
            "(Landroid/media/MediaCodecInfo$CodecCapabilities$CodecCapsIntf;)V");
    jobject javaCodecCaps = env->NewObject(codecCapsClazz, codecCapsConstructID, javaCodecCapsImpl);

    return javaCodecCaps;
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

// CodecCapabilities

static void android_media_CodecCapabilities_native_init(JNIEnv *env, jobject /* thiz */) {
    jclass codecCapsClazz
            = env->FindClass("android/media/MediaCodecInfo$CodecCapabilities$CodecCapsNativeImpl");
    if (codecCapsClazz == NULL) {
        return;
    }

    fields.codecCapsContext = env->GetFieldID(codecCapsClazz, "mNativeContext", "J");
    if (fields.codecCapsContext == NULL) {
        return;
    }

    env->DeleteLocalRef(codecCapsClazz);
}

static jobject android_media_CodecCapabilities_createFromProfileLevel(JNIEnv *env,
        jobject /* thiz */, jstring mediaType, jint profile, jint level) {
    if (mediaType == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    const char *mediaTypeStr = env->GetStringUTFChars(mediaType, nullptr);
    if (mediaTypeStr == nullptr) {
        return NULL;
    }

    std::shared_ptr<CodecCapabilities> codecCaps = CodecCapabilities::CreateFromProfileLevel(
            mediaTypeStr, profile, level);

    jobject javaCodecCapsImpl = convertToJavaCodecCapsNativeImpl(env, codecCaps);

    env->ReleaseStringUTFChars(mediaType, mediaTypeStr);

    return javaCodecCapsImpl;
}

static jobject android_media_CodecCapabilities_native_dup(JNIEnv *env, jobject thiz) {
    sp<JCodecCapabilities> jCodecCaps = getCodecCapabilities(env, thiz);

    // As the CodecCaps objects are ready ony, it is ok to use the default copy constructor.
    // The duplicate CodecCaps will share the same subobjects with the existing one.
    // The lifetime of subobjects are managed by the shared pointer and sp.
    std::shared_ptr<CodecCapabilities> duplicate
            = std::make_shared<CodecCapabilities>(*(jCodecCaps->getCodecCaps()));

    jobject javaCodecCapsImpl = convertToJavaCodecCapsNativeImpl(env, duplicate);

    return javaCodecCapsImpl;
}

static void android_media_CodecCapabilities_native_finalize(JNIEnv *env, jobject thiz) {
    ALOGV("native_finalize");
    setCodecCapabilities(env, thiz, NULL);
}

static jint android_media_CodecCapabilities_getMaxSupportedInstances(JNIEnv *env, jobject thiz) {
    sp<JCodecCapabilities> codecCaps = getCodecCapabilities(env, thiz);
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    int maxSupportedInstances = codecCaps->getMaxSupportedInstances();
    return maxSupportedInstances;
}

static jstring android_media_CodecCapabilities_getMimeType(JNIEnv *env, jobject thiz) {
    sp<JCodecCapabilities> codecCaps = getCodecCapabilities(env, thiz);
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }

    std::string mediaType = codecCaps->getMediaType();
    return env->NewStringUTF(mediaType.c_str());
}

static jboolean android_media_CodecCapabilities_isFeatureRequired(
        JNIEnv *env, jobject thiz, jstring name) {
    sp<JCodecCapabilities> codecCaps = getCodecCapabilities(env, thiz);
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return false;
    }

    if (name == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return -ENOENT;
    }

    const char *nameStr = env->GetStringUTFChars(name, NULL);
    if (nameStr == NULL) {
        // Out of memory exception already pending.
        return -ENOENT;
    }

    bool isFeatureRequired = codecCaps->isFeatureRequired(nameStr);

    env->ReleaseStringUTFChars(name, nameStr);

    return isFeatureRequired;
}

static jboolean android_media_CodecCapabilities_isFeatureSupported(
        JNIEnv *env, jobject thiz, jstring name) {
    sp<JCodecCapabilities> codecCaps = getCodecCapabilities(env, thiz);
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return false;
    }

    if (name == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return -ENOENT;
    }

    const char *nameStr = env->GetStringUTFChars(name, NULL);
    if (nameStr == NULL) {
        // Out of memory exception already pending.
        return -ENOENT;
    }

    bool isFeatureSupported = codecCaps->isFeatureSupported(nameStr);

    env->ReleaseStringUTFChars(name, nameStr);

    return isFeatureSupported;
}

static jboolean android_media_CodecCapabilities_isFormatSupported(JNIEnv *env, jobject thiz,
        jobjectArray keys, jobjectArray values) {
    sp<JCodecCapabilities> codecCaps = getCodecCapabilities(env, thiz);
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return false;
    }

    sp<AMessage> format;
    status_t err = ConvertKeyValueArraysToMessage(env, keys, values, &format);
    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return -ENOENT;;
    }

    return codecCaps->isFormatSupported(format);
}

static jboolean android_media_CodecCapabilities_isRegular(JNIEnv *env, jobject thiz) {
    sp<JCodecCapabilities> codecCaps = getCodecCapabilities(env, thiz);
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return false;
    }

    bool res = codecCaps->isRegular();
    return res;
}

static jobjectArray android_media_CodecCapabilities_validFeatures(JNIEnv *env, jobject thiz) {
    sp<JCodecCapabilities> codecCaps = getCodecCapabilities(env, thiz);
    if (codecCaps == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }

    std::vector<std::string> features = codecCaps->validFeatures();

    jclass stringClazz = env->FindClass("java/lang/String");
    CHECK(stringClazz != NULL);
    jobjectArray jFeatures = env->NewObjectArray(features.size(), stringClazz, NULL);
    for (int i = 0; i < features.size(); i++) {
        jstring jFeature = env->NewStringUTF(features.at(i).c_str());
        env->SetObjectArrayElement(jFeatures, i, jFeature);
        env->DeleteLocalRef(jFeature);
        jFeature = NULL;
    }

    return jFeatures;
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

static const JNINativeMethod gCodecCapsMethods[] = {
    { "native_init", "()V", (void *)android_media_CodecCapabilities_native_init },
    { "native_createFromProfileLevel", "(Ljava/lang/String;II)Landroid/media/MediaCodecInfo$CodecCapabilities$CodecCapsNativeImpl;", (void *)android_media_CodecCapabilities_createFromProfileLevel },
    { "native_dup", "()Landroid/media/MediaCodecInfo$CodecCapabilities$CodecCapsNativeImpl;", (void *)android_media_CodecCapabilities_native_dup },
    { "native_finalize", "()V", (void *)android_media_CodecCapabilities_native_finalize },
    { "native_getMaxSupportedInstances", "()I", (void *)android_media_CodecCapabilities_getMaxSupportedInstances },
    { "native_getMimeType", "()Ljava/lang/String;", (void *)android_media_CodecCapabilities_getMimeType },
    { "native_isFeatureRequired", "(Ljava/lang/String;)Z", (void *)android_media_CodecCapabilities_isFeatureRequired },
    { "native_isFeatureSupported", "(Ljava/lang/String;)Z", (void *)android_media_CodecCapabilities_isFeatureSupported },
    { "native_isFormatSupported", "([Ljava/lang/String;[Ljava/lang/Object;)Z", (void *)android_media_CodecCapabilities_isFormatSupported },
    { "native_isRegular", "()Z", (void *)android_media_CodecCapabilities_isRegular },
    { "native_validFeatures", "()[Ljava/lang/String;", (void *)android_media_CodecCapabilities_validFeatures },
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

    result = AndroidRuntime::registerNativeMethods(env,
            "android/media/MediaCodecInfo$CodecCapabilities$CodecCapsNativeImpl",
            gCodecCapsMethods, NELEM(gCodecCapsMethods));
    return result;
}