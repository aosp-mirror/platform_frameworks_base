/*
 * Copyright 2017, The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_BUFFERING_PARAMS_H_
#define _ANDROID_MEDIA_BUFFERING_PARAMS_H_

#include <media/BufferingSettings.h>

namespace android {

// This entire class is inline
struct BufferingParams {
    BufferingSettings settings;

    struct fields_t {
        jclass      clazz;
        jmethodID   constructID;

        jfieldID    initial_mark_ms;
        jfieldID    resume_playback_mark_ms;

        void init(JNIEnv *env) {
            jclass lclazz = env->FindClass("android/media/BufferingParams");
            if (lclazz == NULL) {
                return;
            }

            clazz = (jclass)env->NewGlobalRef(lclazz);
            if (clazz == NULL) {
                return;
            }

            constructID = env->GetMethodID(clazz, "<init>", "()V");

            initial_mark_ms = env->GetFieldID(clazz, "mInitialMarkMs", "I");
            resume_playback_mark_ms = env->GetFieldID(clazz, "mResumePlaybackMarkMs", "I");

            env->DeleteLocalRef(lclazz);
        }

        void exit(JNIEnv *env) {
            env->DeleteGlobalRef(clazz);
            clazz = NULL;
        }
    };

    void fillFromJobject(JNIEnv *env, const fields_t& fields, jobject params) {
        settings.mInitialMarkMs =
            env->GetIntField(params, fields.initial_mark_ms);
        settings.mResumePlaybackMarkMs =
            env->GetIntField(params, fields.resume_playback_mark_ms);
    }

    jobject asJobject(JNIEnv *env, const fields_t& fields) {
        jobject params = env->NewObject(fields.clazz, fields.constructID);
        if (params == NULL) {
            return NULL;
        }
        env->SetIntField(params, fields.initial_mark_ms, (jint)settings.mInitialMarkMs);
        env->SetIntField(params, fields.resume_playback_mark_ms, (jint)settings.mResumePlaybackMarkMs);

        return params;
    }
};

}  // namespace android

#endif  // _ANDROID_MEDIA_BUFFERING_PARAMS_H_
