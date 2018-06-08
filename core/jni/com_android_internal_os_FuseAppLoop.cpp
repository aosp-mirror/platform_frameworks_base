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

#include <map>
#include <memory>

#include <android_runtime/Log.h>
#include <android-base/logging.h>
#include <android-base/unique_fd.h>
#include <jni.h>
#include <libappfuse/FuseAppLoop.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "core_jni_helpers.h"

namespace android {
namespace {
constexpr const char* CLASS_NAME = "com/android/internal/os/FuseAppLoop";

jclass gFuseAppLoopClass;
jmethodID gOnCommandMethod;
jmethodID gOnOpenMethod;

class Callback : public fuse::FuseAppLoopCallback {
private:
    typedef ScopedLocalRef<jbyteArray> LocalBytes;
    JNIEnv* const mEnv;
    jobject const mSelf;
    std::map<uint64_t, std::unique_ptr<LocalBytes>> mBuffers;

public:
    Callback(JNIEnv* env, jobject self) :
        mEnv(env), mSelf(self) {}

    void OnLookup(uint64_t unique, uint64_t inode) override {
        CallOnCommand(FUSE_LOOKUP, unique, inode, 0, 0, nullptr);
    }

    void OnGetAttr(uint64_t unique, uint64_t inode) override {
        CallOnCommand(FUSE_GETATTR, unique, inode, 0, 0, nullptr);
    }

    void OnOpen(uint64_t unique, uint64_t inode) override {
        const jbyteArray buffer = static_cast<jbyteArray>(mEnv->CallObjectMethod(
                mSelf, gOnOpenMethod, unique, inode));
        CHECK(!mEnv->ExceptionCheck());
        if (buffer == nullptr) {
            return;
        }

        mBuffers.insert(std::make_pair(inode, std::unique_ptr<LocalBytes>(
                new LocalBytes(mEnv, buffer))));
    }

    void OnFsync(uint64_t unique, uint64_t inode) override {
        CallOnCommand(FUSE_FSYNC, unique, inode, 0, 0, nullptr);
    }

    void OnRelease(uint64_t unique, uint64_t inode) override {
        mBuffers.erase(inode);
        CallOnCommand(FUSE_RELEASE, unique, inode, 0, 0, nullptr);
    }

    void OnRead(uint64_t unique, uint64_t inode, uint64_t offset, uint32_t size) override {
        CHECK_LE(size, static_cast<uint32_t>(fuse::kFuseMaxRead));

        auto it = mBuffers.find(inode);
        CHECK(it != mBuffers.end());

        CallOnCommand(FUSE_READ, unique, inode, offset, size, it->second->get());
    }

    void OnWrite(uint64_t unique, uint64_t inode, uint64_t offset, uint32_t size,
            const void* buffer) override {
        CHECK_LE(size, static_cast<uint32_t>(fuse::kFuseMaxWrite));

        auto it = mBuffers.find(inode);
        CHECK(it != mBuffers.end());

        jbyteArray const javaBuffer = it->second->get();

        mEnv->SetByteArrayRegion(javaBuffer, 0, size, static_cast<const jbyte*>(buffer));
        CHECK(!mEnv->ExceptionCheck());

        CallOnCommand(FUSE_WRITE, unique, inode, offset, size, javaBuffer);
    }

private:
    // Helper function to make sure we invoke CallVoidMethod with correct size of integer arguments.
    void CallOnCommand(jint command, jlong unique, jlong inode, jlong offset, jint size,
                       jobject bytes) {
        mEnv->CallVoidMethod(mSelf, gOnCommandMethod, command, unique, inode, offset, size, bytes);
        CHECK(!mEnv->ExceptionCheck());
    }
};

jlong com_android_internal_os_FuseAppLoop_new(JNIEnv* env, jobject self, jint jfd) {
    return reinterpret_cast<jlong>(new fuse::FuseAppLoop(base::unique_fd(jfd)));
}

void com_android_internal_os_FuseAppLoop_delete(JNIEnv* env, jobject self, jlong ptr) {
    delete reinterpret_cast<fuse::FuseAppLoop*>(ptr);
}

void com_android_internal_os_FuseAppLoop_start(JNIEnv* env, jobject self, jlong ptr) {
    Callback callback(env, self);
    reinterpret_cast<fuse::FuseAppLoop*>(ptr)->Start(&callback);
}

void com_android_internal_os_FuseAppLoop_replySimple(
        JNIEnv* env, jobject self, jlong ptr, jlong unique, jint result) {
    if (!reinterpret_cast<fuse::FuseAppLoop*>(ptr)->ReplySimple(unique, result)) {
        reinterpret_cast<fuse::FuseAppLoop*>(ptr)->Break();
    }
}

void com_android_internal_os_FuseAppLoop_replyOpen(
        JNIEnv* env, jobject self, jlong ptr, jlong unique, jlong fh) {
    if (!reinterpret_cast<fuse::FuseAppLoop*>(ptr)->ReplyOpen(unique, fh)) {
        reinterpret_cast<fuse::FuseAppLoop*>(ptr)->Break();
    }
}

void com_android_internal_os_FuseAppLoop_replyLookup(
        JNIEnv* env, jobject self, jlong ptr, jlong unique, jlong inode, jlong size) {
    if (!reinterpret_cast<fuse::FuseAppLoop*>(ptr)->ReplyLookup(unique, inode, size)) {
        reinterpret_cast<fuse::FuseAppLoop*>(ptr)->Break();
    }
}

void com_android_internal_os_FuseAppLoop_replyGetAttr(
        JNIEnv* env, jobject self, jlong ptr, jlong unique, jlong inode, jlong size) {
    if (!reinterpret_cast<fuse::FuseAppLoop*>(ptr)->ReplyGetAttr(
            unique, inode, size, S_IFREG | 0777)) {
        reinterpret_cast<fuse::FuseAppLoop*>(ptr)->Break();
    }
}

void com_android_internal_os_FuseAppLoop_replyWrite(
        JNIEnv* env, jobject self, jlong ptr, jlong unique, jint size) {
    if (!reinterpret_cast<fuse::FuseAppLoop*>(ptr)->ReplyWrite(unique, size)) {
        reinterpret_cast<fuse::FuseAppLoop*>(ptr)->Break();
    }
}

void com_android_internal_os_FuseAppLoop_replyRead(
        JNIEnv* env, jobject self, jlong ptr, jlong unique, jint size, jbyteArray data) {
    ScopedByteArrayRO array(env, data);
    CHECK_GE(size, 0);
    CHECK_LE(static_cast<size_t>(size), array.size());
    if (!reinterpret_cast<fuse::FuseAppLoop*>(ptr)->ReplyRead(unique, size, array.get())) {
        reinterpret_cast<fuse::FuseAppLoop*>(ptr)->Break();
    }
}

const JNINativeMethod methods[] = {
    {
        "native_new",
        "(I)J",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_new)
    },
    {
        "native_delete",
        "(J)V",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_delete)
    },
    {
        "native_start",
        "(J)V",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_start)
    },
    {
        "native_replySimple",
        "(JJI)V",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_replySimple)
    },
    {
        "native_replyOpen",
        "(JJJ)V",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_replyOpen)
    },
    {
        "native_replyLookup",
        "(JJJJ)V",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_replyLookup)
    },
    {
        "native_replyGetAttr",
        "(JJJJ)V",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_replyGetAttr)
    },
    {
        "native_replyRead",
        "(JJI[B)V",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_replyRead)
    },
    {
        "native_replyWrite",
        "(JJI)V",
        reinterpret_cast<void*>(com_android_internal_os_FuseAppLoop_replyWrite)
    },
};
}  // namespace

int register_com_android_internal_os_FuseAppLoop(JNIEnv* env) {
    gFuseAppLoopClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, CLASS_NAME));
    gOnCommandMethod = GetMethodIDOrDie(env, gFuseAppLoopClass, "onCommand", "(IJJJI[B)V");
    gOnOpenMethod = GetMethodIDOrDie(env, gFuseAppLoopClass, "onOpen", "(JJ)[B");
    RegisterMethodsOrDie(env, CLASS_NAME, methods, NELEM(methods));
    return 0;
}
}  // namespace android
