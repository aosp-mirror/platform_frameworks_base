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

        jfieldID    initial_buffering_mode;
        jfieldID    rebuffering_mode;
        jfieldID    initial_watermark_ms;
        jfieldID    initial_watermark_kb;
        jfieldID    rebuffering_watermark_low_ms;
        jfieldID    rebuffering_watermark_high_ms;
        jfieldID    rebuffering_watermark_low_kb;
        jfieldID    rebuffering_watermark_high_kb;

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

            initial_buffering_mode = env->GetFieldID(clazz, "mInitialBufferingMode", "I");
            rebuffering_mode = env->GetFieldID(clazz, "mRebufferingMode", "I");
            initial_watermark_ms = env->GetFieldID(clazz, "mInitialWatermarkMs", "I");
            initial_watermark_kb = env->GetFieldID(clazz, "mInitialWatermarkKB", "I");
            rebuffering_watermark_low_ms = env->GetFieldID(clazz, "mRebufferingWatermarkLowMs", "I");
            rebuffering_watermark_high_ms = env->GetFieldID(clazz, "mRebufferingWatermarkHighMs", "I");
            rebuffering_watermark_low_kb = env->GetFieldID(clazz, "mRebufferingWatermarkLowKB", "I");
            rebuffering_watermark_high_kb = env->GetFieldID(clazz, "mRebufferingWatermarkHighKB", "I");

            env->DeleteLocalRef(lclazz);
        }

        void exit(JNIEnv *env) {
            env->DeleteGlobalRef(clazz);
            clazz = NULL;
        }
    };

    void fillFromJobject(JNIEnv *env, const fields_t& fields, jobject params) {
        settings.mInitialBufferingMode =
            (BufferingMode)env->GetIntField(params, fields.initial_buffering_mode);
        settings.mRebufferingMode =
            (BufferingMode)env->GetIntField(params, fields.rebuffering_mode);
        settings.mInitialWatermarkMs =
            env->GetIntField(params, fields.initial_watermark_ms);
        settings.mInitialWatermarkKB =
            env->GetIntField(params, fields.initial_watermark_kb);
        settings.mRebufferingWatermarkLowMs =
            env->GetIntField(params, fields.rebuffering_watermark_low_ms);
        settings.mRebufferingWatermarkHighMs =
            env->GetIntField(params, fields.rebuffering_watermark_high_ms);
        settings.mRebufferingWatermarkLowKB =
            env->GetIntField(params, fields.rebuffering_watermark_low_kb);
        settings.mRebufferingWatermarkHighKB =
            env->GetIntField(params, fields.rebuffering_watermark_high_kb);
    }

    jobject asJobject(JNIEnv *env, const fields_t& fields) {
        jobject params = env->NewObject(fields.clazz, fields.constructID);
        if (params == NULL) {
            return NULL;
        }
        env->SetIntField(params, fields.initial_buffering_mode, (jint)settings.mInitialBufferingMode);
        env->SetIntField(params, fields.rebuffering_mode, (jint)settings.mRebufferingMode);
        env->SetIntField(params, fields.initial_watermark_ms, (jint)settings.mInitialWatermarkMs);
        env->SetIntField(params, fields.initial_watermark_kb, (jint)settings.mInitialWatermarkKB);
        env->SetIntField(params, fields.rebuffering_watermark_low_ms, (jint)settings.mRebufferingWatermarkLowMs);
        env->SetIntField(params, fields.rebuffering_watermark_high_ms, (jint)settings.mRebufferingWatermarkHighMs);
        env->SetIntField(params, fields.rebuffering_watermark_low_kb, (jint)settings.mRebufferingWatermarkLowKB);
        env->SetIntField(params, fields.rebuffering_watermark_high_kb, (jint)settings.mRebufferingWatermarkHighKB);

        return params;
    }
};

}  // namespace android

#endif  // _ANDROID_MEDIA_BUFFERING_PARAMS_H_
