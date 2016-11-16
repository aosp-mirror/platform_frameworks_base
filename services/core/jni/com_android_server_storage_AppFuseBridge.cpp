/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * See the License for the specic language governing permissions and
 * limitations under the License.
 */

// Need to use LOGE_EX.
#define LOG_TAG "AppFuseBridge"

#include <android_runtime/Log.h>
#include <android-base/logging.h>
#include <core_jni_helpers.h>
#include <libappfuse/FuseBridgeLoop.h>
#include <nativehelper/JNIHelp.h>

namespace android {
namespace {

constexpr const char* CLASS_NAME = "com/android/server/storage/AppFuseBridge";
static jclass appFuseClass;
static jmethodID appFuseOnMount;

class Callback : public fuse::FuseBridgeLoopCallback {
    JNIEnv* mEnv;
    jobject mSelf;

public:
    Callback(JNIEnv* env, jobject self) : mEnv(env), mSelf(self) {}
    void OnMount() override {
        mEnv->CallVoidMethod(mSelf, appFuseOnMount);
        if (mEnv->ExceptionCheck()) {
            LOGE_EX(mEnv, nullptr);
            mEnv->ExceptionClear();
        }
    }
};

jboolean com_android_server_storage_AppFuseBridge_start_loop(
        JNIEnv* env, jobject self, jint devJavaFd, jint proxyJavaFd) {
    Callback callback(env, self);
    return fuse::StartFuseBridgeLoop(devJavaFd, proxyJavaFd, &callback);
}

const JNINativeMethod methods[] = {
    {
        "native_start_loop",
        "(II)Z",
        (void *) com_android_server_storage_AppFuseBridge_start_loop
    }
};

}  // namespace

void register_android_server_storage_AppFuse(JNIEnv* env) {
    CHECK(env != nullptr);

    appFuseClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, CLASS_NAME));
    appFuseOnMount = GetMethodIDOrDie(env, appFuseClass, "onMount", "()V");
    RegisterMethodsOrDie(env, CLASS_NAME, methods, NELEM(methods));
}
}  // namespace android
