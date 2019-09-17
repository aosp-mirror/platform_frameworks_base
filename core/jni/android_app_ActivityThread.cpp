/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "jni.h"
#include "GraphicsJNI.h"
#include <nativehelper/JNIHelp.h>

#include <minikin/Layout.h>
#include <renderthread/RenderProxy.h>

#include "core_jni_helpers.h"
#include <unistd.h>

#include <bionic/malloc.h>

namespace android {

static void android_app_ActivityThread_purgePendingResources(JNIEnv* env, jobject clazz) {
    // Don't care about return values.
    mallopt(M_PURGE, 0);
}

static void
android_app_ActivityThread_dumpGraphics(JNIEnv* env, jobject clazz, jobject javaFileDescriptor) {
    int fd = jniGetFDFromFileDescriptor(env, javaFileDescriptor);
    android::uirenderer::renderthread::RenderProxy::dumpGraphicsMemory(fd);
    minikin::Layout::dumpMinikinStats(fd);
}

static void android_app_ActivityThread_initZygoteChildHeapProfiling(JNIEnv* env, jobject clazz) {
    android_mallopt(M_INIT_ZYGOTE_CHILD_PROFILING, nullptr, 0);
}

static JNINativeMethod gActivityThreadMethods[] = {
    // ------------ Regular JNI ------------------
    { "nPurgePendingResources",        "()V",
      (void*) android_app_ActivityThread_purgePendingResources },
    { "nDumpGraphicsInfo",        "(Ljava/io/FileDescriptor;)V",
      (void*) android_app_ActivityThread_dumpGraphics },
    { "nInitZygoteChildHeapProfiling",        "()V",
      (void*) android_app_ActivityThread_initZygoteChildHeapProfiling }
};

int register_android_app_ActivityThread(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/app/ActivityThread",
            gActivityThreadMethods, NELEM(gActivityThreadMethods));
}

};
