/*
 * Copyright (C) 2010 The Android Open Source Project
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


#ifndef ANDROID_NATIVE_ACTIVITY_H
#define ANDROID_NATIVE_ACTIVITY_H

#include <stdint.h>
#include <sys/types.h>

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

struct android_activity_callbacks_t;

typedef struct android_activity_t {
    struct android_activity_callbacks_t* callbacks;

    JNIEnv* env;
    jobject clazz;

    void* instance;
} android_activity_t;

typedef struct android_activity_callbacks_t {
    void (*onStart)(android_activity_t* activity);
    void (*onResume)(android_activity_t* activity);
    void* (*onSaveInstanceState)(android_activity_t* activity, size_t* outSize);
    void (*onPause)(android_activity_t* activity);
    void (*onStop)(android_activity_t* activity);
    void (*onDestroy)(android_activity_t* activity);

    void (*onLowMemory)(android_activity_t* activity);
    void (*onWindowFocusChanged)(android_activity_t* activity, int hasFocus);
} android_activity_callbacks_t;

typedef void android_activity_create_t(android_activity_t* activity,
        void* savedState, size_t savedStateSize);

extern android_activity_create_t android_onCreateActivity;

#ifdef __cplusplus
};
#endif

#endif // ANDROID_NATIVE_ACTIVITY_H

