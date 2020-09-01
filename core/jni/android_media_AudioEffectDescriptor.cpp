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

#include "core_jni_helpers.h"
#include "android_media_AudioErrors.h"
#include "media/AudioEffect.h"

using namespace android;

static jclass gAudioEffectDescriptorClass;
static jmethodID gAudioEffectDescriptorCstor;

namespace android {

jclass audioEffectDescriptorClass() {
    return gAudioEffectDescriptorClass;
}

jint convertAudioEffectDescriptorFromNative(JNIEnv* env, jobject* jDescriptor,
        const effect_descriptor_t* nDescriptor)
{
    jstring jType;
    jstring jUuid;
    jstring jConnect;
    jstring jName;
    jstring jImplementor;
    char str[EFFECT_STRING_LEN_MAX];

    switch (nDescriptor->flags & EFFECT_FLAG_TYPE_MASK) {
        case EFFECT_FLAG_TYPE_AUXILIARY:
            jConnect = env->NewStringUTF("Auxiliary");
            break;
        case EFFECT_FLAG_TYPE_INSERT:
            jConnect = env->NewStringUTF("Insert");
            break;
        case EFFECT_FLAG_TYPE_PRE_PROC:
            jConnect = env->NewStringUTF("Pre Processing");
            break;
        case EFFECT_FLAG_TYPE_POST_PROC:
            jConnect = env->NewStringUTF("Post Processing");
            break;
        default:
            return (jint)AUDIO_JAVA_BAD_VALUE;
    }

    AudioEffect::guidToString(&nDescriptor->type, str, EFFECT_STRING_LEN_MAX);
    jType = env->NewStringUTF(str);

    AudioEffect::guidToString(&nDescriptor->uuid, str, EFFECT_STRING_LEN_MAX);
    jUuid = env->NewStringUTF(str);

    jName = env->NewStringUTF(nDescriptor->name);
    jImplementor = env->NewStringUTF(nDescriptor->implementor);

    *jDescriptor = env->NewObject(gAudioEffectDescriptorClass,
                                  gAudioEffectDescriptorCstor,
                                  jType,
                                  jUuid,
                                  jConnect,
                                  jName,
                                  jImplementor);
    env->DeleteLocalRef(jType);
    env->DeleteLocalRef(jUuid);
    env->DeleteLocalRef(jConnect);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(jImplementor);

    return (jint) AUDIO_JAVA_SUCCESS;
}

void convertAudioEffectDescriptorVectorFromNative(JNIEnv *env, jobjectArray *jDescriptors,
        const std::vector<effect_descriptor_t>& nDescriptors)
{
    jobjectArray temp = env->NewObjectArray(nDescriptors.size(),
                                            audioEffectDescriptorClass(), NULL);
    size_t actualSize = 0;
    for (size_t i = 0; i < nDescriptors.size(); i++) {
        jobject jdesc;
        if (convertAudioEffectDescriptorFromNative(env,
                                                   &jdesc,
                                                   &nDescriptors[i])
            != AUDIO_JAVA_SUCCESS) {
            continue;
        }

        env->SetObjectArrayElement(temp, actualSize++, jdesc);
        env->DeleteLocalRef(jdesc);
    }

    *jDescriptors = env->NewObjectArray(actualSize, audioEffectDescriptorClass(), NULL);
    for (size_t i = 0; i < actualSize; i++) {
        jobject jdesc = env->GetObjectArrayElement(temp, i);
        env->SetObjectArrayElement(*jDescriptors, i, jdesc);
        env->DeleteLocalRef(jdesc);
    }
    env->DeleteLocalRef(temp);
}

}; // namespace android

int register_android_media_AudioEffectDescriptor(JNIEnv* env) {
    jclass audioEffectDescriptorClass =
        FindClassOrDie(env, "android/media/audiofx/AudioEffect$Descriptor");
    gAudioEffectDescriptorClass =
        MakeGlobalRefOrDie(env, audioEffectDescriptorClass);
    gAudioEffectDescriptorCstor =
        GetMethodIDOrDie(env,
                         audioEffectDescriptorClass,
                         "<init>",
                         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

    env->DeleteLocalRef(audioEffectDescriptorClass);
    return 0;
}
