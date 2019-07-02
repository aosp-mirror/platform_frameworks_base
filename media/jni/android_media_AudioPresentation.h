/*
 * Copyright 2018, The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_AUDIOPRESENTATION_H_
#define _ANDROID_MEDIA_AUDIOPRESENTATION_H_

#include "jni.h"

#include <media/stagefright/foundation/ADebug.h>  // CHECK
#include <media/stagefright/foundation/AudioPresentationInfo.h>
#include <nativehelper/ScopedLocalRef.h>

namespace android {

struct JAudioPresentationInfo {
    struct fields_t {
        jclass      clazz = NULL;
        jmethodID   constructID;

        // list parameters
        jclass listClazz = NULL;
        jmethodID listConstructId;
        jmethodID listAddId;

        // hashmap parameters
        jclass hashMapClazz = NULL;
        jmethodID hashMapConstructID;
        jmethodID hashMapPutID;

        // ulocale parameters
        jclass ulocaleClazz = NULL;
        jmethodID ulocaleConstructID;

        void init(JNIEnv *env) {
            jclass lclazz = env->FindClass("android/media/AudioPresentation");
            CHECK(lclazz != NULL);
            clazz = (jclass)env->NewGlobalRef(lclazz);
            CHECK(clazz != NULL);
            constructID = env->GetMethodID(clazz, "<init>",
                    "(IILandroid/icu/util/ULocale;IZZZLjava/util/Map;)V");
            CHECK(constructID != NULL);

            // list objects
            jclass llistClazz = env->FindClass("java/util/ArrayList");
            CHECK(llistClazz != NULL);
            listClazz = static_cast<jclass>(env->NewGlobalRef(llistClazz));
            CHECK(listClazz != NULL);
            listConstructId = env->GetMethodID(listClazz, "<init>", "()V");
            CHECK(listConstructId != NULL);
            listAddId = env->GetMethodID(listClazz, "add", "(Ljava/lang/Object;)Z");
            CHECK(listAddId != NULL);

            // hashmap objects
            jclass lhashMapClazz = env->FindClass("java/util/HashMap");
            CHECK(lhashMapClazz != NULL);
            hashMapClazz = (jclass)env->NewGlobalRef(lhashMapClazz);
            CHECK(hashMapClazz != NULL);
            hashMapConstructID = env->GetMethodID(hashMapClazz, "<init>", "()V");
            CHECK(hashMapConstructID != NULL);
            hashMapPutID = env->GetMethodID(
                    hashMapClazz,
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
            CHECK(hashMapPutID != NULL);

            jclass lulocaleClazz = env->FindClass("android/icu/util/ULocale");
            CHECK(lulocaleClazz != NULL);
            ulocaleClazz = (jclass)env->NewGlobalRef(lulocaleClazz);
            CHECK(ulocaleClazz != NULL);
            ulocaleConstructID = env->GetMethodID(ulocaleClazz, "<init>", "(Ljava/lang/String;)V");
            CHECK(ulocaleConstructID != NULL);
        }

        void exit(JNIEnv *env) {
            env->DeleteGlobalRef(clazz); clazz = NULL;
            env->DeleteGlobalRef(listClazz); listClazz = NULL;
            env->DeleteGlobalRef(hashMapClazz); hashMapClazz = NULL;
            env->DeleteGlobalRef(ulocaleClazz); ulocaleClazz = NULL;
        }
    };

    static jobject asJobject(JNIEnv *env, const fields_t& fields) {
        return env->NewObject(fields.listClazz, fields.listConstructId);
    }

    static void addPresentations(JNIEnv *env, const fields_t& fields,
                    const AudioPresentationCollection& presentations, jobject presentationsJObj) {
        for (const auto& ap : presentations) {
            ScopedLocalRef<jobject> jLabelObject = convertLabelsToMap(env, fields, ap.mLabels);
            if (jLabelObject == nullptr) return;
            ScopedLocalRef<jstring> jLanguage(env, env->NewStringUTF(ap.mLanguage.c_str()));
            if (jLanguage == nullptr) return;
            ScopedLocalRef<jobject> jLocale(env, env->NewObject(
                            fields.ulocaleClazz, fields.ulocaleConstructID, jLanguage.get()));
            ScopedLocalRef<jobject> jValueObj(env, env->NewObject(fields.clazz, fields.constructID,
                            static_cast<jint>(ap.mPresentationId),
                            static_cast<jint>(ap.mProgramId),
                            jLocale.get(),
                            static_cast<jint>(ap.mMasteringIndication),
                            static_cast<jboolean>((ap.mAudioDescriptionAvailable == 1) ? 1 : 0),
                            static_cast<jboolean>((ap.mSpokenSubtitlesAvailable == 1) ? 1 : 0),
                            static_cast<jboolean>((ap.mDialogueEnhancementAvailable == 1) ? 1 : 0),
                            jLabelObject.get()));
            if (jValueObj != nullptr) {
                env->CallBooleanMethod(presentationsJObj, fields.listAddId, jValueObj.get());
            }
        }
    }

  private:
    static ScopedLocalRef<jobject> convertLabelsToMap(
            JNIEnv *env, const fields_t& fields, const std::map<std::string, std::string> &labels) {
        ScopedLocalRef<jobject> nullMap(env, nullptr);
        ScopedLocalRef<jobject> hashMap(env, env->NewObject(
                        fields.hashMapClazz, fields.hashMapConstructID));
        if (hashMap == nullptr) {
            return nullMap;
        }

        for (const auto& label : labels) {
            ScopedLocalRef<jstring> jLanguage(env, env->NewStringUTF(label.first.c_str()));
            if (jLanguage == nullptr) return nullMap;
            ScopedLocalRef<jobject> jLocale(env, env->NewObject(
                            fields.ulocaleClazz,
                            fields.ulocaleConstructID,
                            jLanguage.get()));
            if (jLocale == nullptr) return nullMap;
            ScopedLocalRef<jobject> jValue(env, env->NewStringUTF(label.second.c_str()));
            if (jValue == nullptr) return nullMap;
            env->CallObjectMethod(hashMap.get(), fields.hashMapPutID, jLocale.get(), jValue.get());
        }
        return hashMap;
    }
};
}  // namespace android

#endif  // _ANDROID_MEDIA_AUDIO_PRESENTATION_H_
