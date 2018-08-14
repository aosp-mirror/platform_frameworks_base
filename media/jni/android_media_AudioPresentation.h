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

#ifndef _ANDROID_MEDIA_AUDIO_PRESENTATION_H_
#define _ANDROID_MEDIA_AUDIO_PRESENTATION_H_

#include "jni.h"

#include <media/AudioPresentationInfo.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>

#include <nativehelper/ScopedLocalRef.h>

namespace android {

struct JAudioPresentationInfo {
    struct fields_t {
        jclass      clazz;
        jmethodID   constructID;

        // list parameters
        jclass listclazz;
        jmethodID listConstructId;
        jmethodID listAddId;

        void init(JNIEnv *env) {
            jclass lclazz = env->FindClass("android/media/AudioPresentation");
            if (lclazz == NULL) {
                return;
            }

            clazz = (jclass)env->NewGlobalRef(lclazz);
            if (clazz == NULL) {
                return;
            }

            constructID = env->GetMethodID(clazz, "<init>",
                                "(IILjava/util/Map;Ljava/lang/String;IZZZ)V");
            env->DeleteLocalRef(lclazz);

            // list objects
            jclass llistclazz = env->FindClass("java/util/ArrayList");
            CHECK(llistclazz != NULL);
            listclazz = static_cast<jclass>(env->NewGlobalRef(llistclazz));
            CHECK(listclazz != NULL);
            listConstructId = env->GetMethodID(listclazz, "<init>", "()V");
            CHECK(listConstructId != NULL);
            listAddId = env->GetMethodID(listclazz, "add", "(Ljava/lang/Object;)Z");
            CHECK(listAddId != NULL);
            env->DeleteLocalRef(llistclazz);
        }

        void exit(JNIEnv *env) {
            env->DeleteGlobalRef(clazz);
            clazz = NULL;
            env->DeleteGlobalRef(listclazz);
            listclazz = NULL;
        }
    };

    static status_t ConvertMessageToMap(JNIEnv *env, const sp<AMessage> &msg, jobject *map) {
        ScopedLocalRef<jclass> hashMapClazz(env, env->FindClass("java/util/HashMap"));

        if (hashMapClazz.get() == NULL) {
            return -EINVAL;
        }
        jmethodID hashMapConstructID =
            env->GetMethodID(hashMapClazz.get(), "<init>", "()V");

        if (hashMapConstructID == NULL) {
            return -EINVAL;
        }
        jmethodID hashMapPutID =
            env->GetMethodID(
                    hashMapClazz.get(),
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

        if (hashMapPutID == NULL) {
            return -EINVAL;
        }

        jobject hashMap = env->NewObject(hashMapClazz.get(), hashMapConstructID);

        for (size_t i = 0; i < msg->countEntries(); ++i) {
            AMessage::Type valueType;
            const char *key = msg->getEntryNameAt(i, &valueType);

            if (!strncmp(key, "android._", 9)) {
                // don't expose private keys (starting with android._)
                continue;
            }

            jobject valueObj = NULL;

            AString val;
            CHECK(msg->findString(key, &val));

            valueObj = env->NewStringUTF(val.c_str());

            if (valueObj != NULL) {
                jstring keyObj = env->NewStringUTF(key);

                env->CallObjectMethod(hashMap, hashMapPutID, keyObj, valueObj);

                env->DeleteLocalRef(keyObj); keyObj = NULL;
                env->DeleteLocalRef(valueObj); valueObj = NULL;
            }
        }

        *map = hashMap;

        return OK;
    }

    jobject asJobject(JNIEnv *env, const fields_t& fields, const AudioPresentationInfo &info) {
        jobject list = env->NewObject(fields.listclazz, fields.listConstructId);

        for (size_t i = 0; i < info.countPresentations(); ++i) {
            const sp<AudioPresentation> &ap = info.getPresentation(i);
            jobject jLabelObject;

            sp<AMessage> labelMessage = new AMessage();
            for (size_t i = 0; i < ap->mLabels.size(); ++i) {
                labelMessage->setString(ap->mLabels.keyAt(i).string(),
                                        ap->mLabels.valueAt(i).string());
            }
            if (ConvertMessageToMap(env, labelMessage, &jLabelObject) != OK) {
                return NULL;
            }
            jstring jLanguage = env->NewStringUTF(ap->mLanguage.string());

            jobject jValueObj = env->NewObject(fields.clazz, fields.constructID,
                                static_cast<jint>(ap->mPresentationId),
                                static_cast<jint>(ap->mProgramId),
                                jLabelObject,
                                jLanguage,
                                static_cast<jint>(ap->mMasteringIndication),
                                static_cast<jboolean>((ap->mAudioDescriptionAvailable == 1) ?
                                    1 : 0),
                                static_cast<jboolean>((ap->mSpokenSubtitlesAvailable == 1) ?
                                    1 : 0),
                                static_cast<jboolean>((ap->mDialogueEnhancementAvailable == 1) ?
                                    1 : 0));
            if (jValueObj == NULL) {
                env->DeleteLocalRef(jLanguage); jLanguage = NULL;
                return NULL;
            }

            env->CallBooleanMethod(list, fields.listAddId, jValueObj);
            env->DeleteLocalRef(jValueObj); jValueObj = NULL;
            env->DeleteLocalRef(jLanguage); jLanguage = NULL;
        }
        return list;
    }
};
}  // namespace android

#endif  // _ANDROID_MEDIA_AUDIO_PRESENTATION_H_
