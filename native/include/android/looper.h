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


#ifndef ANDROID_LOOPER_H
#define ANDROID_LOOPER_H

#include <poll.h>

#ifdef __cplusplus
extern "C" {
#endif

struct ALooper;
typedef struct ALooper ALooper;

typedef int ALooper_callbackFunc(int fd, int events, void* data);

ALooper* ALooper_forThread();

ALooper* ALooper_prepare();

int32_t ALooper_pollOnce(int timeoutMillis);

void ALooper_acquire(ALooper* looper);

void ALooper_release(ALooper* looper);

void ALooper_setCallback(ALooper* looper, int fd, int events,
        ALooper_callbackFunc* callback, void* data);

int32_t ALooper_removeCallback(ALooper* looper, int fd);

#ifdef __cplusplus
};
#endif

#endif // ANDROID_NATIVE_WINDOW_H
