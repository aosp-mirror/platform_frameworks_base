/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <android/file_descriptor_jni.h>

#include <stddef.h>

#define LOG_TAG "file_descriptor_jni"
#include <log/log.h>

#include "JniConstants.h"

static void EnsureArgumentIsFileDescriptor(JNIEnv* env, jobject instance) {
    LOG_ALWAYS_FATAL_IF(instance == NULL, "FileDescriptor is NULL");
    jclass jifd = JniConstants_FileDescriptorClass(env);
    LOG_ALWAYS_FATAL_IF(!(*env)->IsInstanceOf(env, instance, jifd),
                         "Argument is not a FileDescriptor");
}

JNIEXPORT _Nullable jobject AFileDescriptor_create(JNIEnv* env) {
    return (*env)->NewObject(env,
                             JniConstants_FileDescriptorClass(env),
                             JniConstants_FileDescriptor_init(env));
}

JNIEXPORT int AFileDescriptor_getFd(JNIEnv* env, jobject fileDescriptor) {
    EnsureArgumentIsFileDescriptor(env, fileDescriptor);
    return (*env)->GetIntField(env, fileDescriptor, JniConstants_FileDescriptor_fd(env));
}

JNIEXPORT void AFileDescriptor_setFd(JNIEnv* env, jobject fileDescriptor, int fd) {
    EnsureArgumentIsFileDescriptor(env, fileDescriptor);
    (*env)->SetIntField(env, fileDescriptor, JniConstants_FileDescriptor_fd(env), fd);
}
