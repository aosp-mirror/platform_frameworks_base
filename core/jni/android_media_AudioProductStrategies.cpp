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

//#define LOG_NDEBUG 0

#define LOG_TAG "AudioProductStrategies-JNI"

#include <inttypes.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <utils/Log.h>
#include <vector>

#include <media/AudioSystem.h>
#include <media/AudioPolicy.h>

#include <nativehelper/ScopedUtfChars.h>

#include "android_media_AudioAttributes.h"
#include "android_media_AudioErrors.h"

// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/audiopolicy/AudioProductStrategy";
static const char* const kAudioProductStrategyClassPathName =
        "android/media/audiopolicy/AudioProductStrategy";

static const char* const kAudioAttributesGroupsClassPathName =
        "android/media/audiopolicy/AudioProductStrategy$AudioAttributesGroup";

static jclass gAudioProductStrategyClass;
static jmethodID gAudioProductStrategyCstor;
static struct {
    jfieldID    mAudioAttributesGroups;
    jfieldID    mName;
    jfieldID    mId;
} gAudioProductStrategyFields;

static jclass gAudioAttributesGroupClass;
static jmethodID gAudioAttributesGroupCstor;
static struct {
    jfieldID    mVolumeGroupId;
    jfieldID    mLegacyStreamType;
    jfieldID    mAudioAttributes;
} gAudioAttributesGroupsFields;

static jclass gArrayListClass;
static struct {
    jmethodID    add;
    jmethodID    toArray;
} gArrayListMethods;


static jint convertAudioProductStrategiesFromNative(
        JNIEnv *env, jobject *jAudioStrategy, const AudioProductStrategy &strategy)
{
    jint jStatus = (jint)AUDIO_JAVA_SUCCESS;
    jobjectArray jAudioAttributesGroups = NULL;
    jobjectArray jAudioAttributes = NULL;
    jobject jAudioAttribute = NULL;
    jstring jName = NULL;
    jint jStrategyId = NULL;
    jint numAttributesGroups;
    size_t indexGroup = 0;

    jName = env->NewStringUTF(strategy.getName().c_str());
    jStrategyId = static_cast<jint>(strategy.getId());

    // Audio Attributes Group array
    std::map<int, std::vector<AudioAttributes> > groups;
    for (const auto &attr : strategy.getAudioAttributes()) {
        int attrGroupId = attr.getGroupId();
        groups[attrGroupId].push_back(attr);
    }
    numAttributesGroups = groups.size();

    jAudioAttributesGroups = env->NewObjectArray(numAttributesGroups, gAudioAttributesGroupClass, NULL);

    for (const auto &iter : groups) {
        std::vector<AudioAttributes> audioAttributesGroups = iter.second;
        jint numAttributes = audioAttributesGroups.size();
        jint jGroupId = iter.first;
        jint jLegacyStreamType = audioAttributesGroups.front().getStreamType();

        jStatus = JNIAudioAttributeHelper::getJavaArray(env, &jAudioAttributes, numAttributes);
        if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        for (size_t j = 0; j < static_cast<size_t>(numAttributes); j++) {
            auto attributes = audioAttributesGroups[j].getAttributes();

            jStatus = JNIAudioAttributeHelper::nativeToJava(env, &jAudioAttribute, attributes);
            if (jStatus != AUDIO_JAVA_SUCCESS) {
                goto exit;
            }
            env->SetObjectArrayElement(jAudioAttributes, j, jAudioAttribute);
        }
        jobject jAudioAttributesGroup = env->NewObject(gAudioAttributesGroupClass,
                                                       gAudioAttributesGroupCstor,
                                                       jGroupId,
                                                       jLegacyStreamType,
                                                       jAudioAttributes);
        env->SetObjectArrayElement(jAudioAttributesGroups, indexGroup++, jAudioAttributesGroup);

        if (jAudioAttributes != NULL) {
            env->DeleteLocalRef(jAudioAttributes);
            jAudioAttributes = NULL;
        }
        if (jAudioAttribute != NULL) {
            env->DeleteLocalRef(jAudioAttribute);
            jAudioAttribute = NULL;
        }
        if (jAudioAttributesGroup != NULL) {
            env->DeleteLocalRef(jAudioAttributesGroup);
            jAudioAttributesGroup = NULL;
        }
    }
    *jAudioStrategy = env->NewObject(gAudioProductStrategyClass, gAudioProductStrategyCstor,
                                     jName,
                                     jStrategyId,
                                     jAudioAttributesGroups);
exit:
    if (jAudioAttributes != NULL) {
        env->DeleteLocalRef(jAudioAttributes);
    }
    if (jAudioAttribute != NULL) {
        env->DeleteLocalRef(jAudioAttribute);
        jAudioAttribute = NULL;
    }
    if (jAudioAttributesGroups != NULL) {
        env->DeleteLocalRef(jAudioAttributesGroups);
    }
    if (jName != NULL) {
        env->DeleteLocalRef(jName);
    }
    return jStatus;
}

static jint
android_media_AudioSystem_listAudioProductStrategies(JNIEnv *env, jobject clazz,
                                                     jobject jStrategies)
{
    if (env == NULL) {
        return AUDIO_JAVA_DEAD_OBJECT;
    }
    if (jStrategies == NULL) {
        ALOGE("listAudioProductStrategies NULL AudioProductStrategies");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jStrategies, gArrayListClass)) {
        ALOGE("listAudioProductStrategies not an arraylist");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    status_t status;
    AudioProductStrategyVector strategies;
    jint jStatus;
    jobject jStrategy = NULL;

    status = AudioSystem::listAudioProductStrategies(strategies);
    if (status != NO_ERROR) {
        ALOGE("AudioSystem::listAudioProductStrategies error %d", status);
        return nativeToJavaStatus(status);
    }
    for (const auto &strategy : strategies) {
        jStatus = convertAudioProductStrategiesFromNative(env, &jStrategy, strategy);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        env->CallBooleanMethod(jStrategies, gArrayListMethods.add, jStrategy);
    }
exit:
    if (jStrategy != NULL) {
        env->DeleteLocalRef(jStrategy);
    }
    return jStatus;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    {"native_list_audio_product_strategies", "(Ljava/util/ArrayList;)I",
                        (void *)android_media_AudioSystem_listAudioProductStrategies},
};

int register_android_media_AudioProductStrategies(JNIEnv *env)
{
    jclass arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gArrayListClass = MakeGlobalRefOrDie(env, arrayListClass);
    gArrayListMethods.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");
    gArrayListMethods.toArray = GetMethodIDOrDie(env, arrayListClass,
                                                 "toArray", "()[Ljava/lang/Object;");

    jclass audioProductStrategyClass = FindClassOrDie(env, kAudioProductStrategyClassPathName);
    gAudioProductStrategyClass = MakeGlobalRefOrDie(env, audioProductStrategyClass);
    gAudioProductStrategyCstor = GetMethodIDOrDie(
                env, audioProductStrategyClass, "<init>",
                "(Ljava/lang/String;I[Landroid/media/audiopolicy/AudioProductStrategy$AudioAttributesGroup;)V");
    gAudioProductStrategyFields.mAudioAttributesGroups = GetFieldIDOrDie(
                env, audioProductStrategyClass, "mAudioAttributesGroups",
                "[Landroid/media/audiopolicy/AudioProductStrategy$AudioAttributesGroup;");
    gAudioProductStrategyFields.mName = GetFieldIDOrDie(
                env, audioProductStrategyClass, "mName", "Ljava/lang/String;");
    gAudioProductStrategyFields.mId = GetFieldIDOrDie(
                env, audioProductStrategyClass, "mId", "I");

    jclass audioAttributesGroupClass = FindClassOrDie(env, kAudioAttributesGroupsClassPathName);
    gAudioAttributesGroupClass = MakeGlobalRefOrDie(env, audioAttributesGroupClass);
    gAudioAttributesGroupCstor = GetMethodIDOrDie(env, audioAttributesGroupClass, "<init>",
                                                  "(II[Landroid/media/AudioAttributes;)V");
    gAudioAttributesGroupsFields.mVolumeGroupId = GetFieldIDOrDie(
                env, audioAttributesGroupClass, "mVolumeGroupId", "I");
    gAudioAttributesGroupsFields.mLegacyStreamType = GetFieldIDOrDie(
                env, audioAttributesGroupClass, "mLegacyStreamType", "I");
    gAudioAttributesGroupsFields.mAudioAttributes = GetFieldIDOrDie(
                env, audioAttributesGroupClass, "mAudioAttributes",
                "[Landroid/media/AudioAttributes;");

    env->DeleteLocalRef(audioProductStrategyClass);
    env->DeleteLocalRef(audioAttributesGroupClass);

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}
