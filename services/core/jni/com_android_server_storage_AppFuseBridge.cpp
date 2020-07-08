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
#include <android-base/unique_fd.h>
#include <core_jni_helpers.h>
#include <libappfuse/FuseBridgeLoop.h>
#include <libappfuse/FuseBuffer.h>
#include <nativehelper/JNIHelp.h>

namespace android {
namespace {

constexpr const char* CLASS_NAME = "com/android/server/storage/AppFuseBridge";
static jclass gAppFuseClass;
static jmethodID gAppFuseOnMount;
static jmethodID gAppFuseOnClosed;

class Callback : public fuse::FuseBridgeLoopCallback {
    JNIEnv* mEnv;
    jobject mSelf;

public:
    Callback(JNIEnv* env, jobject self) : mEnv(env), mSelf(self) {}
    void OnMount(int mount_id) override {
        mEnv->CallVoidMethod(mSelf, gAppFuseOnMount, mount_id);
        if (mEnv->ExceptionCheck()) {
            LOGE_EX(mEnv, nullptr);
            mEnv->ExceptionClear();
        }
    }

    void OnClosed(int mount_id) override {
        mEnv->CallVoidMethod(mSelf, gAppFuseOnClosed, mount_id);
        if (mEnv->ExceptionCheck()) {
            LOGE_EX(mEnv, nullptr);
            mEnv->ExceptionClear();
        }
    }
};

class MonitorScope final {
public:
    MonitorScope(JNIEnv* env, jobject obj) : mEnv(env), mObj(obj), mLocked(false) {
        if (mEnv->MonitorEnter(obj) == JNI_OK) {
            mLocked = true;
        } else {
            LOG(ERROR) << "Failed to enter monitor.";
        }
    }

    ~MonitorScope() {
        if (mLocked) {
            if (mEnv->MonitorExit(mObj) != JNI_OK) {
                LOG(ERROR) << "Failed to exit monitor.";
            }
        }
    }

    explicit operator bool() {
        return mLocked;
    }

private:
    // Lifetime of |MonitorScope| must be shorter than the reference of mObj.
    JNIEnv* mEnv;
    jobject mObj;
    bool mLocked;

    DISALLOW_COPY_AND_ASSIGN(MonitorScope);
};

jlong com_android_server_storage_AppFuseBridge_new(JNIEnv* env, jobject self) {
    return reinterpret_cast<jlong>(new fuse::FuseBridgeLoop());
}

void com_android_server_storage_AppFuseBridge_delete(JNIEnv* env, jobject self, jlong java_loop) {
    fuse::FuseBridgeLoop* const loop = reinterpret_cast<fuse::FuseBridgeLoop*>(java_loop);
    CHECK(loop);
    delete loop;
}

void com_android_server_storage_AppFuseBridge_start_loop(
        JNIEnv* env, jobject self, jlong java_loop) {
    fuse::FuseBridgeLoop* const loop = reinterpret_cast<fuse::FuseBridgeLoop*>(java_loop);
    CHECK(loop);
    Callback callback(env, self);
    loop->Start(&callback);
}

jint com_android_server_storage_AppFuseBridge_add_bridge(
        JNIEnv* env, jobject self, jlong java_loop, jint mountId, jint javaDevFd) {
    base::unique_fd devFd(javaDevFd);
    fuse::FuseBridgeLoop* const loop = reinterpret_cast<fuse::FuseBridgeLoop*>(java_loop);
    CHECK(loop);

    base::unique_fd proxyFd[2];
    if (!fuse::SetupMessageSockets(&proxyFd)) {
        return -1;
    }

    if (!loop->AddBridge(mountId, std::move(devFd), std::move(proxyFd[0]))) {
        return -1;
    }

    return proxyFd[1].release();
}

void com_android_server_storage_AppFuseBridge_lock(JNIEnv* env, jobject self) {
    fuse::FuseBridgeLoop::Lock();
}

void com_android_server_storage_AppFuseBridge_unlock(JNIEnv* env, jobject self) {
    fuse::FuseBridgeLoop::Unlock();
}

const JNINativeMethod methods[] = {
    {
        "native_new",
        "()J",
        reinterpret_cast<void*>(com_android_server_storage_AppFuseBridge_new)
    },
    {
        "native_delete",
        "(J)V",
        reinterpret_cast<void*>(com_android_server_storage_AppFuseBridge_delete)
    },
    {
        "native_start_loop",
        "(J)V",
        reinterpret_cast<void*>(com_android_server_storage_AppFuseBridge_start_loop)
    },
    {
        "native_add_bridge",
        "(JII)I",
        reinterpret_cast<void*>(com_android_server_storage_AppFuseBridge_add_bridge)
    },
    {
        "native_lock",
        "()V",
        reinterpret_cast<void*>(com_android_server_storage_AppFuseBridge_lock)
    },
    {
        "native_unlock",
        "()V",
        reinterpret_cast<void*>(com_android_server_storage_AppFuseBridge_unlock)
    }
};

}  // namespace

void register_android_server_storage_AppFuse(JNIEnv* env) {
    CHECK(env != nullptr);

    gAppFuseClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, CLASS_NAME));
    gAppFuseOnMount = GetMethodIDOrDie(env, gAppFuseClass, "onMount", "(I)V");
    gAppFuseOnClosed = GetMethodIDOrDie(env, gAppFuseClass, "onClosed", "(I)V");
    RegisterMethodsOrDie(env, CLASS_NAME, methods, NELEM(methods));
}
}  // namespace android
