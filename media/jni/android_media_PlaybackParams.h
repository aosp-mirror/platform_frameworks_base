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

#ifndef _ANDROID_MEDIA_PLAYBACK_PARAMS_H_
#define _ANDROID_MEDIA_PLAYBACK_PARAMS_H_

#include <media/AudioResamplerPublic.h>

namespace android {

// This entire class is inline as it is used from both core and media
struct PlaybackParams {
    AudioPlaybackRate audioRate;
    bool speedSet;
    bool pitchSet;
    bool audioFallbackModeSet;
    bool audioStretchModeSet;

    struct fields_t {
        jclass      clazz;
        jmethodID   constructID;

        jfieldID    speed;
        jfieldID    pitch;
        jfieldID    audio_fallback_mode;
        jfieldID    audio_stretch_mode;
        jfieldID    set;
        jint        set_speed;
        jint        set_pitch;
        jint        set_audio_fallback_mode;
        jint        set_audio_stretch_mode;

        void init(JNIEnv *env) {
            jclass lclazz = env->FindClass("android/media/PlaybackParams");
            if (lclazz == NULL) {
                return;
            }

            clazz = (jclass)env->NewGlobalRef(lclazz);
            if (clazz == NULL) {
                return;
            }

            constructID = env->GetMethodID(clazz, "<init>", "()V");

            speed = env->GetFieldID(clazz, "mSpeed", "F");
            pitch = env->GetFieldID(clazz, "mPitch", "F");
            audio_fallback_mode = env->GetFieldID(clazz, "mAudioFallbackMode", "I");
            audio_stretch_mode = env->GetFieldID(clazz, "mAudioStretchMode", "I");
            set = env->GetFieldID(clazz, "mSet", "I");

            set_speed =
                env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "SET_SPEED", "I"));
            set_pitch =
                env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "SET_PITCH", "I"));
            set_audio_fallback_mode = env->GetStaticIntField(
                    clazz, env->GetStaticFieldID(clazz, "SET_AUDIO_FALLBACK_MODE", "I"));
            set_audio_stretch_mode = env->GetStaticIntField(
                    clazz, env->GetStaticFieldID(clazz, "SET_AUDIO_STRETCH_MODE", "I"));

            env->DeleteLocalRef(lclazz);
        }

        void exit(JNIEnv *env) {
            env->DeleteGlobalRef(clazz);
            clazz = NULL;
        }
    };

    void fillFromJobject(JNIEnv *env, const fields_t& fields, jobject params) {
        audioRate.mSpeed = env->GetFloatField(params, fields.speed);
        audioRate.mPitch = env->GetFloatField(params, fields.pitch);
        audioRate.mFallbackMode =
            (AudioTimestretchFallbackMode)env->GetIntField(params, fields.audio_fallback_mode);
        audioRate.mStretchMode =
            (AudioTimestretchStretchMode)env->GetIntField(params, fields.audio_stretch_mode);
        int set = env->GetIntField(params, fields.set);

        speedSet = set & fields.set_speed;
        pitchSet = set & fields.set_pitch;
        audioFallbackModeSet = set & fields.set_audio_fallback_mode;
        audioStretchModeSet = set & fields.set_audio_stretch_mode;
    }

    jobject asJobject(JNIEnv *env, const fields_t& fields) {
        jobject params = env->NewObject(fields.clazz, fields.constructID);
        if (params == NULL) {
            return NULL;
        }
        env->SetFloatField(params, fields.speed, (jfloat)audioRate.mSpeed);
        env->SetFloatField(params, fields.pitch, (jfloat)audioRate.mPitch);
        env->SetIntField(params, fields.audio_fallback_mode, (jint)audioRate.mFallbackMode);
        env->SetIntField(params, fields.audio_stretch_mode, (jint)audioRate.mStretchMode);
        env->SetIntField(
                params, fields.set,
                (speedSet ? fields.set_speed : 0)
                        | (pitchSet ? fields.set_pitch : 0)
                        | (audioFallbackModeSet ? fields.set_audio_fallback_mode : 0)
                        | (audioStretchModeSet  ? fields.set_audio_stretch_mode : 0));

        return params;
    }
};

}  // namespace android

#endif  // _ANDROID_MEDIA_PLAYBACK_PARAMS_H_
