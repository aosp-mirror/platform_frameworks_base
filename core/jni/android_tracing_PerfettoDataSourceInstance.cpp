/*
 * Copyright 2023 The Android Open Source Project
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

#define LOG_TAG "Perfetto"

#include "android_tracing_PerfettoDataSourceInstance.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <nativehelper/JNIHelp.h>
#include <perfetto/public/data_source.h>
#include <perfetto/public/producer.h>
#include <perfetto/public/protos/trace/test_event.pzc.h>
#include <perfetto/public/protos/trace/trace_packet.pzc.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <sstream>
#include <thread>

#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;
    jmethodID init;
} gStartCallbackArgumentsClassInfo;

static struct {
    jclass clazz;
    jmethodID init;
} gFlushCallbackArgumentsClassInfo;

static struct {
    jclass clazz;
    jmethodID init;
} gStopCallbackArgumentsClassInfo;

static JavaVM* gVm;

void callJavaMethodWithArgsObject(JNIEnv* env, jobject classRef, jmethodID method, jobject args) {
    ScopedLocalRef<jobject> localClassRef(env, env->NewLocalRef(classRef));

    if (localClassRef == nullptr) {
        ALOGE("Weak reference went out of scope");
        return;
    }

    env->CallVoidMethod(localClassRef.get(), method, args);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

PerfettoDataSourceInstance::PerfettoDataSourceInstance(JNIEnv* env, jobject javaDataSourceInstance,
                                                       PerfettoDsInstanceIndex inst_idx)
      : inst_idx(inst_idx), mJavaDataSourceInstance(env->NewGlobalRef(javaDataSourceInstance)) {}

PerfettoDataSourceInstance::~PerfettoDataSourceInstance() {
    JNIEnv* env = GetOrAttachJNIEnvironment(gVm, JNI_VERSION_1_6);
    env->DeleteGlobalRef(mJavaDataSourceInstance);
}

void PerfettoDataSourceInstance::onStart(JNIEnv* env) {
    ScopedLocalRef<jobject> args(env,
                                 env->NewObject(gStartCallbackArgumentsClassInfo.clazz,
                                                gStartCallbackArgumentsClassInfo.init));
    jclass cls = env->GetObjectClass(mJavaDataSourceInstance);
    jmethodID mid = env->GetMethodID(cls, "onStart",
                                     "(Landroid/tracing/perfetto/StartCallbackArguments;)V");

    callJavaMethodWithArgsObject(env, mJavaDataSourceInstance, mid, args.get());
}

void PerfettoDataSourceInstance::onFlush(JNIEnv* env) {
    ScopedLocalRef<jobject> args(env,
                                 env->NewObject(gFlushCallbackArgumentsClassInfo.clazz,
                                                gFlushCallbackArgumentsClassInfo.init));
    jclass cls = env->GetObjectClass(mJavaDataSourceInstance);
    jmethodID mid = env->GetMethodID(cls, "onFlush",
                                     "(Landroid/tracing/perfetto/FlushCallbackArguments;)V");

    callJavaMethodWithArgsObject(env, mJavaDataSourceInstance, mid, args.get());
}

void PerfettoDataSourceInstance::onStop(JNIEnv* env) {
    ScopedLocalRef<jobject> args(env,
                                 env->NewObject(gStopCallbackArgumentsClassInfo.clazz,
                                                gStopCallbackArgumentsClassInfo.init));
    jclass cls = env->GetObjectClass(mJavaDataSourceInstance);
    jmethodID mid =
            env->GetMethodID(cls, "onStop", "(Landroid/tracing/perfetto/StopCallbackArguments;)V");

    callJavaMethodWithArgsObject(env, mJavaDataSourceInstance, mid, args.get());
}

int register_android_tracing_PerfettoDataSourceInstance(JNIEnv* env) {
    if (env->GetJavaVM(&gVm) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JavaVM from JNIEnv: %p", env);
    }

    jclass clazz = env->FindClass("android/tracing/perfetto/StartCallbackArguments");
    gStartCallbackArgumentsClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gStartCallbackArgumentsClassInfo.init =
            env->GetMethodID(gStartCallbackArgumentsClassInfo.clazz, "<init>", "()V");

    clazz = env->FindClass("android/tracing/perfetto/FlushCallbackArguments");
    gFlushCallbackArgumentsClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gFlushCallbackArgumentsClassInfo.init =
            env->GetMethodID(gFlushCallbackArgumentsClassInfo.clazz, "<init>", "()V");

    clazz = env->FindClass("android/tracing/perfetto/StopCallbackArguments");
    gStopCallbackArgumentsClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gStopCallbackArgumentsClassInfo.init =
            env->GetMethodID(gStopCallbackArgumentsClassInfo.clazz, "<init>", "()V");

    return 0;
}

} // namespace android