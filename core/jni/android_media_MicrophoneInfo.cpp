/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "android_media_MicrophoneInfo.h"
#include "android_media_AudioErrors.h"
#include "core_jni_helpers.h"

using namespace android;

static jclass gArrayListClass;
static jmethodID gArrayListCstor;
static struct {
    jmethodID add;
} gArrayListMethods;

static jclass gFloatClass;
static jmethodID gFloatCstor;

static jclass gFloatArrayClass;

static jclass gIntegerClass;
static jmethodID gIntegerCstor;

static jclass gMicrophoneInfoClass;
static jmethodID gMicrophoneInfoCstor;

static jclass gMicrophoneInfoCoordinateClass;
static jmethodID gMicrophoneInfoCoordinateCstor;

static jclass gPairClass;
static jmethodID gPairCstor;

namespace android {

jint convertMicrophoneInfoFromNative(JNIEnv *env, jobject *jMicrophoneInfo,
        const media::MicrophoneInfo *microphoneInfo)
{
    jint jStatus = (jint)AUDIO_JAVA_SUCCESS;
    jstring jDeviceId = NULL;
    jstring jAddress = NULL;
    jobject jGeometricLocation = NULL;
    jobject jOrientation = NULL;
    jobject jFrequencyResponses = NULL;
    jobject jChannelMappings = NULL;

    jDeviceId = env->NewStringUTF(microphoneInfo->getDeviceId().c_str());
    jAddress = env->NewStringUTF(microphoneInfo->getAddress().c_str());
    if (microphoneInfo->getGeometricLocation().size() != 3 ||
            microphoneInfo->getOrientation().size() != 3) {
        jStatus = nativeToJavaStatus(BAD_VALUE);
        goto exit;
    }
    jGeometricLocation = env->NewObject(gMicrophoneInfoCoordinateClass,
                                        gMicrophoneInfoCoordinateCstor,
                                        microphoneInfo->getGeometricLocation()[0],
                                        microphoneInfo->getGeometricLocation()[1],
                                        microphoneInfo->getGeometricLocation()[2]);
    jOrientation = env->NewObject(gMicrophoneInfoCoordinateClass,
                                  gMicrophoneInfoCoordinateCstor,
                                  microphoneInfo->getOrientation()[0],
                                  microphoneInfo->getOrientation()[1],
                                  microphoneInfo->getOrientation()[2]);
    // Create a list of Pair for frequency response.
    if (microphoneInfo->getFrequencyResponses().size() != 2 ||
            microphoneInfo->getFrequencyResponses()[0].size() !=
                    microphoneInfo->getFrequencyResponses()[1].size()) {
        jStatus = nativeToJavaStatus(BAD_VALUE);
        goto exit;
    }
    jFrequencyResponses = env->NewObject(gArrayListClass, gArrayListCstor);
    for (size_t i = 0; i < microphoneInfo->getFrequencyResponses()[0].size(); i++) {
        jobject jFrequency = env->NewObject(gFloatClass, gFloatCstor,
                                            microphoneInfo->getFrequencyResponses()[0][i]);
        jobject jResponse = env->NewObject(gFloatClass, gFloatCstor,
                                          microphoneInfo->getFrequencyResponses()[1][i]);
        jobject jFrequencyResponse = env->NewObject(gPairClass, gPairCstor, jFrequency, jResponse);
        env->CallBooleanMethod(jFrequencyResponses, gArrayListMethods.add, jFrequencyResponse);
        env->DeleteLocalRef(jFrequency);
        env->DeleteLocalRef(jResponse);
        env->DeleteLocalRef(jFrequencyResponse);
    }
    // Create a list of Pair for channel mapping.
    if (microphoneInfo->getChannelMapping().size() != AUDIO_CHANNEL_COUNT_MAX) {
        jStatus = nativeToJavaStatus(BAD_VALUE);
        goto exit;
    }
    jChannelMappings = env->NewObject(gArrayListClass, gArrayListCstor);
    for (size_t i = 0; i < microphoneInfo->getChannelMapping().size(); i++) {
        int channelMappingType = microphoneInfo->getChannelMapping()[i];
        if (channelMappingType != AUDIO_MICROPHONE_CHANNEL_MAPPING_UNUSED) {
            jobject jChannelIndex = env->NewObject(gIntegerClass, gIntegerCstor, i);
            jobject jChannelMappingType = env->NewObject(gIntegerClass, gIntegerCstor,
                                                         channelMappingType);
            jobject jChannelMapping = env->NewObject(gPairClass, gPairCstor,
                                                     jChannelIndex, jChannelMappingType);
            env->CallBooleanMethod(jChannelMappings, gArrayListMethods.add, jChannelMapping);
            env->DeleteLocalRef(jChannelIndex);
            env->DeleteLocalRef(jChannelMappingType);
            env->DeleteLocalRef(jChannelMapping);
        }
    }
    *jMicrophoneInfo = env->NewObject(gMicrophoneInfoClass, gMicrophoneInfoCstor, jDeviceId,
                                      microphoneInfo->getType(), jAddress,
                                      microphoneInfo->getDeviceLocation(),
                                      microphoneInfo->getDeviceGroup(),
                                      microphoneInfo->getIndexInTheGroup(),
                                      jGeometricLocation, jOrientation,
                                      jFrequencyResponses, jChannelMappings,
                                      microphoneInfo->getSensitivity(),
                                      microphoneInfo->getMaxSpl(),
                                      microphoneInfo->getMinSpl(),
                                      microphoneInfo->getDirectionality());

exit:
    if (jDeviceId != NULL) {
        env->DeleteLocalRef(jDeviceId);
    }
    if (jAddress != NULL) {
        env->DeleteLocalRef(jAddress);
    }
    if (jFrequencyResponses != NULL) {
        env->DeleteLocalRef(jFrequencyResponses);
    }
    if (jChannelMappings != NULL) {
        env->DeleteLocalRef(jChannelMappings);
    }
    if (jGeometricLocation != NULL) {
        env->DeleteLocalRef(jGeometricLocation);
    }
    if (jOrientation != NULL) {
        env->DeleteLocalRef(jOrientation);
    }
    return jStatus;
}

}

int register_android_media_MicrophoneInfo(JNIEnv *env)
{
    jclass arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gArrayListClass = MakeGlobalRefOrDie(env, arrayListClass);
    gArrayListCstor = GetMethodIDOrDie(env, arrayListClass, "<init>", "()V");
    gArrayListMethods.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jclass floatClass = FindClassOrDie(env, "java/lang/Float");
    gFloatClass = MakeGlobalRefOrDie(env, floatClass);
    gFloatCstor = GetMethodIDOrDie(env, floatClass, "<init>", "(F)V");

    jclass floatArrayClass = FindClassOrDie(env, "[F");
    gFloatArrayClass = MakeGlobalRefOrDie(env, floatArrayClass);

    jclass integerClass = FindClassOrDie(env, "java/lang/Integer");
    gIntegerClass = MakeGlobalRefOrDie(env, integerClass);
    gIntegerCstor = GetMethodIDOrDie(env, integerClass, "<init>", "(I)V");

    jclass microphoneInfoClass = FindClassOrDie(env, "android/media/MicrophoneInfo");
    gMicrophoneInfoClass = MakeGlobalRefOrDie(env, microphoneInfoClass);
    gMicrophoneInfoCstor = GetMethodIDOrDie(env, microphoneInfoClass, "<init>",
            "(Ljava/lang/String;ILjava/lang/String;IIILandroid/media/MicrophoneInfo$Coordinate3F;Landroid/media/MicrophoneInfo$Coordinate3F;Ljava/util/List;Ljava/util/List;FFFI)V");

    jclass microphoneInfoCoordinateClass = FindClassOrDie(
            env, "android/media/MicrophoneInfo$Coordinate3F");
    gMicrophoneInfoCoordinateClass = MakeGlobalRefOrDie(env, microphoneInfoCoordinateClass);
    gMicrophoneInfoCoordinateCstor = GetMethodIDOrDie(env, microphoneInfoCoordinateClass, "<init>",
           "(FFF)V");

    jclass pairClass = FindClassOrDie(env, "android/util/Pair");
    gPairClass = MakeGlobalRefOrDie(env, pairClass);
    gPairCstor = GetMethodIDOrDie(env, pairClass, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");

    return 0;
}
