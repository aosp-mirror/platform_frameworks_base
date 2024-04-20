/*
 * Copyright 2024 The Android Open Source Project
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

#define LOG_TAG "SurfaceControlUtils"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <android/surface_control.h>
#include <android/surface_control_jni.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include "core_jni_helpers.h"

namespace android {

namespace {

static struct {
    jclass clazz;
    jmethodID run;
} gRunnableClassInfo;

class TransactionCompletedListenerWrapper {
public:
    explicit TransactionCompletedListenerWrapper(JNIEnv* env, jobject object) {
        env->GetJavaVM(&mVm);
        mTransactionCompletedListenerObject = env->NewGlobalRef(object);
        LOG_ALWAYS_FATAL_IF(!mTransactionCompletedListenerObject, "Failed to make global ref");
    }

    ~TransactionCompletedListenerWrapper() {
        getenv()->DeleteGlobalRef(mTransactionCompletedListenerObject);
    }

    void callback() {
        JNIEnv* env = getenv();

        env->CallVoidMethod(mTransactionCompletedListenerObject, gRunnableClassInfo.run);

        DieIfException(env, "Uncaught exception in TransactionCompletedListener.");
    }

    static void transactionCallbackThunk(void* context, ASurfaceTransactionStats* stats) {
        TransactionCompletedListenerWrapper* listener =
                reinterpret_cast<TransactionCompletedListenerWrapper*>(context);
        listener->callback();
        delete listener;
    }

private:
    jobject mTransactionCompletedListenerObject;
    JavaVM* mVm;

    JNIEnv* getenv() {
        JNIEnv* env;
        mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        return env;
    }
};

static void nativeAddTransactionCompletedListener(JNIEnv* env, jclass clazz,
                                                  jobject transactionObj,
                                                  jobject transactionCompletedListener) {
    ASurfaceTransaction* transaction = ASurfaceTransaction_fromJava(env, transactionObj);
    auto context = new TransactionCompletedListenerWrapper(env, transactionCompletedListener);
    ASurfaceTransaction_setOnComplete(transaction, reinterpret_cast<void*>(context),
                                      TransactionCompletedListenerWrapper::
                                                         transactionCallbackThunk);
}

static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeAddTransactionCompletedListener",
        "(Landroid/view/SurfaceControl$Transaction;Ljava/lang/Runnable;)V",
        (void*) nativeAddTransactionCompletedListener}};
} // namespace

int register_com_android_server_wm_utils_SurfaceControlUtils(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/wm/utils/SurfaceControlUtils",
                                       gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass runnableClazz = FindClassOrDie(env, "java/lang/Runnable");
    gRunnableClassInfo.clazz = MakeGlobalRefOrDie(env, runnableClazz);
    gRunnableClassInfo.run = GetMethodIDOrDie(env, runnableClazz, "run", "()V");

    return 0;
}

} // namespace android
