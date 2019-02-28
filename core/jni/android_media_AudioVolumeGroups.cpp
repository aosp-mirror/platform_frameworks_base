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

#define LOG_TAG "AudioVolumeGroups-JNI"

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
static const char* const kClassPathName = "android/media/audiopolicy/AudioVolumeGroups";
static const char* const kAudioVolumeGroupClassPathName =
        "android/media/audiopolicy/AudioVolumeGroup";

static jclass gAudioVolumeGroupClass;
static jmethodID gAudioVolumeGroupCstor;
static struct {
    jfieldID    mName;
    jfieldID    mId;
} gAudioVolumeGroupFields;

static jclass gArrayListClass;
static jmethodID gArrayListCstor;
static struct {
    jmethodID    add;
    jmethodID    toArray;
} gArrayListMethods;


static jint convertAudioVolumeGroupsFromNative(
        JNIEnv *env, jobject *jGroup, const AudioVolumeGroup &group)
{
    jint jStatus = (jint)AUDIO_JAVA_SUCCESS;
    jstring jName = NULL;
    jint Id = NULL;

    jintArray jLegacyStreamTypes = NULL;
    jobjectArray jAudioAttributes = NULL;
    jint numAttributes;
    jobject jAudioAttribute = NULL;

    jName = env->NewStringUTF(group.getName().c_str());
    Id = static_cast<jint>(group.getId());

    // Legacy stream types array
    jLegacyStreamTypes = env->NewIntArray(group.getStreamTypes().size());
    if (jLegacyStreamTypes == NULL) {
        jStatus = (jint)AUDIO_JAVA_ERROR;
        goto exit;
    }
    for (size_t streamIndex = 0; streamIndex < group.getStreamTypes().size(); streamIndex++) {
        jint jStream = group.getStreamTypes()[streamIndex];
        env->SetIntArrayRegion(jLegacyStreamTypes, streamIndex, 1, &jStream);
    }

    // Audio Attributes array
    numAttributes = group.getAudioAttributes().size();
    jStatus = JNIAudioAttributeHelper::getJavaArray(env, &jAudioAttributes, numAttributes);
    if (jStatus != (jint)AUDIO_JAVA_SUCCESS) {
        goto exit;
    }

    for (size_t j = 0; j < static_cast<size_t>(numAttributes); j++) {
        auto attributes = group.getAudioAttributes()[j];

        jStatus = JNIAudioAttributeHelper::nativeToJava(env, &jAudioAttribute, attributes);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        env->SetObjectArrayElement(jAudioAttributes, j, jAudioAttribute);
    }

    *jGroup = env->NewObject(gAudioVolumeGroupClass, gAudioVolumeGroupCstor,
                             jName, Id, jAudioAttributes, jLegacyStreamTypes);
exit:
    if (jName != NULL) {
        env->DeleteLocalRef(jName);
    }
    return jStatus;
}

static jint
android_media_AudioSystem_listAudioVolumeGroups(JNIEnv *env, jobject clazz, jobject jVolumeGroups)
{
    if (env == NULL) {
        return AUDIO_JAVA_DEAD_OBJECT;
    }
    if (jVolumeGroups == NULL) {
        ALOGE("listAudioVolumeGroups NULL AudioVolumeGroups");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jVolumeGroups, gArrayListClass)) {
        ALOGE("listAudioVolumeGroups not an arraylist");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    status_t status;
    AudioVolumeGroupVector groups;
    jint jStatus;
    jobject jGroup = NULL;

    status = AudioSystem::listAudioVolumeGroups(groups);
    if (status != NO_ERROR) {
        ALOGE("AudioSystem::listAudioVolumeGroups error %d", status);
        return nativeToJavaStatus(status);
    }
    for (const auto &group : groups) {
        jStatus = convertAudioVolumeGroupsFromNative(env, &jGroup, group);
        if (jStatus != AUDIO_JAVA_SUCCESS) {
            goto exit;
        }
        env->CallBooleanMethod(jVolumeGroups, gArrayListMethods.add, jGroup);
    }
exit:
    if (jGroup != NULL) {
        env->DeleteLocalRef(jGroup);
    }
    return jStatus;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    {"native_list_audio_volume_groups", "(Ljava/util/ArrayList;)I",
                        (void *)android_media_AudioSystem_listAudioVolumeGroups},
};

int register_android_media_AudioVolumeGroups(JNIEnv *env)
{
    jclass arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gArrayListClass = MakeGlobalRefOrDie(env, arrayListClass);
    gArrayListCstor = GetMethodIDOrDie(env, arrayListClass, "<init>", "()V");
    gArrayListMethods.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");
    gArrayListMethods.toArray = GetMethodIDOrDie(env, arrayListClass,
                                                 "toArray", "()[Ljava/lang/Object;");

    jclass audioVolumeGroupClass = FindClassOrDie(env, kAudioVolumeGroupClassPathName);
    gAudioVolumeGroupClass = MakeGlobalRefOrDie(env, audioVolumeGroupClass);
    gAudioVolumeGroupCstor = GetMethodIDOrDie(
                env, audioVolumeGroupClass, "<init>",
                "(Ljava/lang/String;I[Landroid/media/AudioAttributes;[I)V");

    gAudioVolumeGroupFields.mName = GetFieldIDOrDie(
                env, audioVolumeGroupClass, "mName", "Ljava/lang/String;");
    gAudioVolumeGroupFields.mId = GetFieldIDOrDie(
                env, audioVolumeGroupClass, "mId", "I");

    env->DeleteLocalRef(audioVolumeGroupClass);

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}
