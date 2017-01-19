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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "FuseAppLoopJNI"
#define LOG_NDEBUG 0

#include <stdlib.h>
#include <sys/stat.h>

#include <android_runtime/Log.h>
#include <android-base/logging.h>
#include <android-base/unique_fd.h>
#include <jni.h>
#include <libappfuse/FuseAppLoop.h>
#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

namespace android {

namespace {

constexpr const char* CLASS_NAME = "com/android/internal/os/FuseAppLoop";

jclass gFuseAppLoopClass;
jmethodID gOnGetSizeMethod;
jmethodID gOnOpenMethod;
jmethodID gOnFsyncMethod;
jmethodID gOnReleaseMethod;
jmethodID gOnReadMethod;
jmethodID gOnWriteMethod;

class Callback : public fuse::FuseAppLoopCallback {
private:
    static constexpr size_t kBufferSize = std::max(fuse::kFuseMaxWrite, fuse::kFuseMaxRead);
    static_assert(kBufferSize <= INT32_MAX, "kBufferSize should be fit in int32_t.");

    JNIEnv* const mEnv;
    jobject const mSelf;
    ScopedLocalRef<jbyteArray> mJniBuffer;

    template <typename T>
    T checkException(T result) const {
        if (mEnv->ExceptionCheck()) {
            LOGE_EX(mEnv, nullptr);
            mEnv->ExceptionClear();
            return -EIO;
        }
        return result;
    }

public:
    Callback(JNIEnv* env, jobject self) :
        mEnv(env),
        mSelf(self),
        mJniBuffer(env, nullptr) {}

    bool Init() {
        mJniBuffer.reset(mEnv->NewByteArray(kBufferSize));
        return mJniBuffer.get();
    }

    bool IsActive() override {
        return true;
    }

    int64_t OnGetSize(uint64_t inode) override {
        return checkException(mEnv->CallLongMethod(mSelf, gOnGetSizeMethod, inode));
    }

    int32_t OnOpen(uint64_t inode) override {
        return checkException(mEnv->CallIntMethod(mSelf, gOnOpenMethod, inode));
    }

    int32_t OnFsync(uint64_t inode) override {
        return checkException(mEnv->CallIntMethod(mSelf, gOnFsyncMethod, inode));
    }

    int32_t OnRelease(uint64_t inode) override {
        return checkException(mEnv->CallIntMethod(mSelf, gOnReleaseMethod, inode));
    }

    int32_t OnRead(uint64_t inode, uint64_t offset, uint32_t size, void* buffer) override {
        CHECK_LE(size, static_cast<uint32_t>(kBufferSize));
        const int32_t result = checkException(mEnv->CallIntMethod(
                mSelf, gOnReadMethod, inode, offset, size, mJniBuffer.get()));
        if (result <= 0) {
            return result;
        }
        if (result > static_cast<int32_t>(size)) {
            LOG(ERROR) << "Returned size is too large.";
            return -EIO;
        }

        mEnv->GetByteArrayRegion(mJniBuffer.get(), 0, result, static_cast<jbyte*>(buffer));
        CHECK(!mEnv->ExceptionCheck());

        return checkException(result);
    }

    int32_t OnWrite(uint64_t inode, uint64_t offset, uint32_t size, const void* buffer) override {
        CHECK_LE(size, static_cast<uint32_t>(kBufferSize));

        mEnv->SetByteArrayRegion(mJniBuffer.get(), 0, size, static_cast<const jbyte*>(buffer));
        CHECK(!mEnv->ExceptionCheck());

        return checkException(mEnv->CallIntMethod(
                mSelf, gOnWriteMethod, inode, offset, size, mJniBuffer.get()));
    }
};

jboolean com_android_internal_os_FuseAppLoop_start_loop(JNIEnv* env, jobject self, jint jfd) {
    base::unique_fd fd(jfd);
    Callback callback(env, self);

    if (!callback.Init()) {
        LOG(ERROR) << "Failed to init callback";
        return JNI_FALSE;
    }

    return fuse::StartFuseAppLoop(fd.release(), &callback);
}

const JNINativeMethod methods[] = {
    {
        "native_start_loop",
        "(I)Z",
        (void *) com_android_internal_os_FuseAppLoop_start_loop
    }
};

}  // namespace

int register_com_android_internal_os_FuseAppLoop(JNIEnv* env) {
    gFuseAppLoopClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, CLASS_NAME));
    gOnGetSizeMethod = GetMethodIDOrDie(env, gFuseAppLoopClass, "onGetSize", "(J)J");
    gOnOpenMethod = GetMethodIDOrDie(env, gFuseAppLoopClass, "onOpen", "(J)I");
    gOnFsyncMethod = GetMethodIDOrDie(env, gFuseAppLoopClass, "onFsync", "(J)I");
    gOnReleaseMethod = GetMethodIDOrDie(env, gFuseAppLoopClass, "onRelease", "(J)I");
    gOnReadMethod = GetMethodIDOrDie(env, gFuseAppLoopClass, "onRead", "(JJI[B)I");
    gOnWriteMethod = GetMethodIDOrDie(env, gFuseAppLoopClass, "onWrite", "(JJI[B)I");
    RegisterMethodsOrDie(env, CLASS_NAME, methods, NELEM(methods));
    return 0;
}

}  // namespace android
