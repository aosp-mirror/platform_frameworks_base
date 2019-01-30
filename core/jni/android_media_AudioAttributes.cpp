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

#define LOG_TAG "AudioAttributes-JNI"

#include <inttypes.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <utils/Log.h>
#include <vector>

#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedUtfChars.h>

#include "android_media_AudioAttributes.h"
#include "android_media_AudioErrors.h"

// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/AudioAttributes";

static jclass gAudioAttributesClass;
static struct {
    jfieldID    mUsage;         // AudioAttributes.mUsage
    jfieldID    mSource;        // AudioAttributes.mSource
    jfieldID    mContentType;   // AudioAttributes.mContentType
    jfieldID    mFlags;         // AudioAttributes.mFlags
    jfieldID    mFormattedTags; // AudioAttributes.mFormattedTags
} gAudioAttributesFields;

static jclass gAudioAttributesBuilderClass;
static jmethodID gAudioAttributesBuilderCstor;
static struct {
    jmethodID build;
    jmethodID setUsage;
    jmethodID setInternalCapturePreset;
    jmethodID setContentType;
    jmethodID setFlags;
    jmethodID addTag;
} gAudioAttributesBuilderMethods;


static jint nativeAudioAttributesFromJavaAudioAttributes(
        JNIEnv* env, jobject jAudioAttributes, audio_attributes_t *aa)
{
    if (env == nullptr) {
        return AUDIO_JAVA_DEAD_OBJECT;
    }
    if (jAudioAttributes == nullptr) {
        ALOGE("Invalid AudioAttributes java object");
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    if (!env->IsInstanceOf(jAudioAttributes, gAudioAttributesClass)) {
        return (jint)AUDIO_JAVA_BAD_VALUE;
    }
    const jstring jtags =
            (jstring) env->GetObjectField(jAudioAttributes, gAudioAttributesFields.mFormattedTags);
    if (jtags == nullptr) {
        return AUDIO_JAVA_NO_INIT;
    }
    const char* tags = env->GetStringUTFChars(jtags, NULL);
    // copying array size -1, char array for tags was calloc'd, no need to NULL-terminate it
    strncpy(aa->tags, tags, AUDIO_ATTRIBUTES_TAGS_MAX_SIZE - 1);
    env->ReleaseStringUTFChars(jtags, tags);

    // Record ?
    aa->source = (audio_source_t) env->GetIntField(jAudioAttributes,
                                                    gAudioAttributesFields.mSource);
    // Track ?
    aa->usage = (audio_usage_t) env->GetIntField(jAudioAttributes, gAudioAttributesFields.mUsage);

    aa->content_type =
            (audio_content_type_t) env->GetIntField(jAudioAttributes,
                                                    gAudioAttributesFields.mContentType);

    aa->flags = (audio_flags_mask_t)env->GetIntField(jAudioAttributes,
                                                      gAudioAttributesFields.mFlags);

    ALOGV("AudioAttributes for usage=%d content=%d source=%d tags=%s flags=%08x tags=%s",
          aa->usage, aa->content_type, aa->source, aa->tags, aa->flags, aa->tags);
    return (jint)AUDIO_JAVA_SUCCESS;
}

static jint nativeAudioAttributesToJavaAudioAttributes(
        JNIEnv* env, jobject *jAudioAttributes, const audio_attributes_t &attributes)
{
    ScopedLocalRef<jobject> jAttributeBuilder(env, env->NewObject(gAudioAttributesBuilderClass,
                                                                  gAudioAttributesBuilderCstor));
    if (jAttributeBuilder.get() == nullptr) {
        return (jint)AUDIO_JAVA_ERROR;
    }
    env->CallObjectMethod(jAttributeBuilder.get(),
                          gAudioAttributesBuilderMethods.setUsage,
                          attributes.usage);
    env->CallObjectMethod(jAttributeBuilder.get(),
                          gAudioAttributesBuilderMethods.setInternalCapturePreset,
                          attributes.source);
    env->CallObjectMethod(jAttributeBuilder.get(),
                          gAudioAttributesBuilderMethods.setContentType,
                          attributes.content_type);
    env->CallObjectMethod(jAttributeBuilder.get(),
                          gAudioAttributesBuilderMethods.setFlags,
                          attributes.flags);
    env->CallObjectMethod(jAttributeBuilder.get(),
                          gAudioAttributesBuilderMethods.addTag,
                          env->NewStringUTF(attributes.tags));

    *jAudioAttributes = env->CallObjectMethod(jAttributeBuilder.get(),
                                              gAudioAttributesBuilderMethods.build);
    return (jint)AUDIO_JAVA_SUCCESS;
}

// ----------------------------------------------------------------------------
JNIAudioAttributeHelper::UniqueAaPtr JNIAudioAttributeHelper::makeUnique()
{
    audio_attributes_t *aa = new (calloc(1, sizeof(audio_attributes_t)))
                audio_attributes_t{AUDIO_ATTRIBUTES_INITIALIZER};
    return UniqueAaPtr{aa};
}

jint JNIAudioAttributeHelper::nativeFromJava(JNIEnv* env, jobject jAudioAttributes,
                                             audio_attributes_t *paa)
{
    return nativeAudioAttributesFromJavaAudioAttributes(env, jAudioAttributes, paa);
}

jint JNIAudioAttributeHelper::nativeToJava(
        JNIEnv* env, jobject *jAudioAttributes, const audio_attributes_t &attributes)
{
    return nativeAudioAttributesToJavaAudioAttributes(env, jAudioAttributes, attributes);
}

jint JNIAudioAttributeHelper::getJavaArray(
        JNIEnv* env, jobjectArray *jAudioAttributeArray, jint numAudioAttributes)
{
    *jAudioAttributeArray = env->NewObjectArray(numAudioAttributes, gAudioAttributesClass, NULL);
    return *jAudioAttributeArray == NULL? (jint)AUDIO_JAVA_ERROR : (jint)AUDIO_JAVA_SUCCESS;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    // n/a
};

int register_android_media_AudioAttributes(JNIEnv *env)
{
    jclass audioAttributesClass = FindClassOrDie(env, kClassPathName);
    gAudioAttributesClass = MakeGlobalRefOrDie(env, audioAttributesClass);
    gAudioAttributesFields.mUsage = GetFieldIDOrDie(env, audioAttributesClass, "mUsage", "I");
    gAudioAttributesFields.mSource = GetFieldIDOrDie(env, audioAttributesClass, "mSource", "I");
    gAudioAttributesFields.mContentType =
            GetFieldIDOrDie(env, audioAttributesClass, "mContentType", "I");
    gAudioAttributesFields.mFlags = GetFieldIDOrDie(env, audioAttributesClass, "mFlags", "I");
    gAudioAttributesFields.mFormattedTags =
            GetFieldIDOrDie(env, audioAttributesClass, "mFormattedTags", "Ljava/lang/String;");

    jclass audioAttributesBuilderClass = FindClassOrDie(
                env, "android/media/AudioAttributes$Builder");
    gAudioAttributesBuilderClass = MakeGlobalRefOrDie(env, audioAttributesBuilderClass);
    gAudioAttributesBuilderCstor = GetMethodIDOrDie(
                env, audioAttributesBuilderClass, "<init>", "()V");
    gAudioAttributesBuilderMethods.build = GetMethodIDOrDie(
                env, audioAttributesBuilderClass, "build", "()Landroid/media/AudioAttributes;");
    gAudioAttributesBuilderMethods.setUsage = GetMethodIDOrDie(
                env, audioAttributesBuilderClass, "setUsage",
                "(I)Landroid/media/AudioAttributes$Builder;");
    gAudioAttributesBuilderMethods.setInternalCapturePreset = GetMethodIDOrDie(
                env, audioAttributesBuilderClass, "setInternalCapturePreset",
                "(I)Landroid/media/AudioAttributes$Builder;");
    gAudioAttributesBuilderMethods.setContentType = GetMethodIDOrDie(
                env, audioAttributesBuilderClass, "setContentType",
                "(I)Landroid/media/AudioAttributes$Builder;");
    gAudioAttributesBuilderMethods.setFlags = GetMethodIDOrDie(
                env, audioAttributesBuilderClass, "setFlags",
                "(I)Landroid/media/AudioAttributes$Builder;");
    gAudioAttributesBuilderMethods.addTag = GetMethodIDOrDie(
                env, audioAttributesBuilderClass, "addTag",
                "(Ljava/lang/String;)Landroid/media/AudioAttributes$Builder;");

    env->DeleteLocalRef(audioAttributesClass);

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}
