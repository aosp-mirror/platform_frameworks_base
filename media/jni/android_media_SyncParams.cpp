/*
 * Copyright 2015, The Android Open Source Project
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

#include "android_media_SyncParams.h"

#include "JNIHelp.h"

namespace android {

void SyncParams::fields_t::init(JNIEnv *env) {
    jclass lclazz = env->FindClass("android/media/SyncParams");
    if (lclazz == NULL) {
        return;
    }

    clazz = (jclass)env->NewGlobalRef(lclazz);
    if (clazz == NULL) {
        return;
    }

    constructID = env->GetMethodID(clazz, "<init>", "()V");

    sync_source = env->GetFieldID(clazz, "mSyncSource", "I");
    audio_adjust_mode = env->GetFieldID(clazz, "mAudioAdjustMode", "I");
    tolerance = env->GetFieldID(clazz, "mTolerance", "F");
    frame_rate = env->GetFieldID(clazz, "mFrameRate", "F");
    set = env->GetFieldID(clazz, "mSet", "I");

    set_sync_source =
        env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "SET_SYNC_SOURCE", "I"));
    set_audio_adjust_mode = env->GetStaticIntField(
            clazz, env->GetStaticFieldID(clazz, "SET_AUDIO_ADJUST_MODE", "I"));
    set_tolerance =
        env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "SET_TOLERANCE", "I"));
    set_frame_rate =
        env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "SET_FRAME_RATE", "I"));

    env->DeleteLocalRef(lclazz);
}

void SyncParams::fields_t::exit(JNIEnv *env) {
    env->DeleteGlobalRef(clazz);
    clazz = NULL;
}

void SyncParams::fillFromJobject(JNIEnv *env, const fields_t& fields, jobject params) {
    sync.mSource = (AVSyncSource)env->GetIntField(params, fields.sync_source);
    sync.mAudioAdjustMode = (AVSyncAudioAdjustMode)env->GetIntField(params, fields.audio_adjust_mode);
    sync.mTolerance = env->GetFloatField(params, fields.tolerance);
    frameRate = env->GetFloatField(params, fields.frame_rate);
    int set = env->GetIntField(params, fields.set);

    syncSourceSet = set & fields.set_sync_source;
    audioAdjustModeSet = set & fields.set_audio_adjust_mode;
    toleranceSet = set & fields.set_tolerance;
    frameRateSet = set & fields.set_frame_rate;
}

jobject SyncParams::asJobject(JNIEnv *env, const fields_t& fields) {
    jobject params = env->NewObject(fields.clazz, fields.constructID);
    if (params == NULL) {
        return NULL;
    }
    env->SetIntField(params, fields.sync_source, (jint)sync.mSource);
    env->SetIntField(params, fields.audio_adjust_mode, (jint)sync.mAudioAdjustMode);
    env->SetFloatField(params, fields.tolerance, (jfloat)sync.mTolerance);
    env->SetFloatField(params, fields.frame_rate, (jfloat)frameRate);
    env->SetIntField(
            params, fields.set,
            (syncSourceSet ? fields.set_sync_source : 0)
                    | (audioAdjustModeSet ? fields.set_audio_adjust_mode : 0)
                    | (toleranceSet ? fields.set_tolerance : 0)
                    | (frameRateSet ? fields.set_frame_rate : 0));

    return params;
}

}  // namespace android
