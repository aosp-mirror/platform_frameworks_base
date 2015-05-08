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

#ifndef _ANDROID_MEDIA_SYNC_PARAMS_H_
#define _ANDROID_MEDIA_SYNC_PARAMS_H_

#include "jni.h"

#include <media/stagefright/MediaSync.h>

namespace android {

struct SyncParams {
    AVSyncSettings sync;
    float frameRate;

    bool syncSourceSet;
    bool audioAdjustModeSet;
    bool toleranceSet;
    bool frameRateSet;

    struct fields_t {
        jclass      clazz;
        jmethodID   constructID;

        jfieldID    sync_source;
        jfieldID    audio_adjust_mode;
        jfieldID    tolerance;
        jfieldID    frame_rate;
        jfieldID    set;
        jint        set_sync_source;
        jint        set_audio_adjust_mode;
        jint        set_tolerance;
        jint        set_frame_rate;

        // initializes fields
        void init(JNIEnv *env);

        // releases global references held
        void exit(JNIEnv *env);
    };

    // fills this from an android.media.SyncParams object
    void fillFromJobject(JNIEnv *env, const fields_t& fields, jobject params);

    // returns this as a android.media.SyncParams object
    jobject asJobject(JNIEnv *env, const fields_t& fields);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_SYNC_PARAMS_H_
