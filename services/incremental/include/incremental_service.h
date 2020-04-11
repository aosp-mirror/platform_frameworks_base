/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_INCREMENTAL_SERVICE_H
#define ANDROID_INCREMENTAL_SERVICE_H

#include <sys/cdefs.h>
#include <jni.h>

__BEGIN_DECLS

#define INCREMENTAL_LIBRARY_NAME "service.incremental.so"

jlong Incremental_IncrementalService_Start(JNIEnv* env);
void Incremental_IncrementalService_OnSystemReady(jlong self);
void Incremental_IncrementalService_OnDump(jlong self, jint fd);

__END_DECLS

#endif  // ANDROID_INCREMENTAL_SERVICE_H
