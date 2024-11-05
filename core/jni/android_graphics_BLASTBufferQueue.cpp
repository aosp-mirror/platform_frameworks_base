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

#define LOG_TAG "BLASTBufferQueue"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_util_Binder.h>
#include <gui/BLASTBufferQueue.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;
    jmethodID ctor;
} gTransactionClassInfo;

struct {
    jmethodID accept;
} gTransactionConsumer;

static JNIEnv* getenv(JavaVM* vm) {
    JNIEnv* env;
    auto result = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
            LOG_ALWAYS_FATAL("Failed to AttachCurrentThread!");
        }
    } else if (result != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", vm);
    }
    return env;
}

struct {
    jmethodID onTransactionHang;
} gTransactionHangCallback;

class TransactionHangCallbackWrapper : public LightRefBase<TransactionHangCallbackWrapper> {
public:
    explicit TransactionHangCallbackWrapper(JNIEnv* env, jobject jobject) {
        env->GetJavaVM(&mVm);
        mTransactionHangObject = env->NewGlobalRef(jobject);
        LOG_ALWAYS_FATAL_IF(!mTransactionHangObject, "Failed to make global ref");
    }

    ~TransactionHangCallbackWrapper() {
        if (mTransactionHangObject != nullptr) {
            getenv(mVm)->DeleteGlobalRef(mTransactionHangObject);
            mTransactionHangObject = nullptr;
        }
    }

    void onTransactionHang(const std::string& reason) {
        if (!mTransactionHangObject) {
            return;
        }
        JNIEnv* env = getenv(mVm);
        ScopedLocalRef<jstring> jReason(env, env->NewStringUTF(reason.c_str()));
        getenv(mVm)->CallVoidMethod(mTransactionHangObject,
                                    gTransactionHangCallback.onTransactionHang, jReason.get());
        DieIfException(env, "Uncaught exception in TransactionHangCallback.");
    }

private:
    JavaVM* mVm;
    jobject mTransactionHangObject;
};

static jlong nativeCreate(JNIEnv* env, jclass clazz, jstring jName,
                          jboolean updateDestinationFrame) {
    ScopedUtfChars name(env, jName);
    sp<BLASTBufferQueue> queue = new BLASTBufferQueue(name.c_str(), updateDestinationFrame);
    queue->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(queue.get());
}

static void nativeDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->decStrong((void*)nativeCreate);
}

static jobject nativeGetSurface(JNIEnv* env, jclass clazz, jlong ptr,
                                jboolean includeSurfaceControlHandle) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    return android_view_Surface_createFromSurface(env,
                                                  queue->getSurface(includeSurfaceControlHandle));
}

class JGlobalRefHolder {
public:
    JGlobalRefHolder(JavaVM* vm, jobject object) : mVm(vm), mObject(object) {}

    virtual ~JGlobalRefHolder() {
        getenv(mVm)->DeleteGlobalRef(mObject);
        mObject = nullptr;
    }

    jobject object() { return mObject; }
    JavaVM* vm() { return mVm; }

private:
    JGlobalRefHolder(const JGlobalRefHolder&) = delete;
    void operator=(const JGlobalRefHolder&) = delete;

    JavaVM* mVm;
    jobject mObject;
};

static bool nativeSyncNextTransaction(JNIEnv* env, jclass clazz, jlong ptr, jobject callback,
                                      jboolean acquireSingleBuffer) {
    LOG_ALWAYS_FATAL_IF(!callback, "callback passed in to syncNextTransaction must not be NULL");

    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    JavaVM* vm = nullptr;
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");

    auto globalCallbackRef = std::make_shared<JGlobalRefHolder>(vm, env->NewGlobalRef(callback));
    return queue->syncNextTransaction(
            [globalCallbackRef](SurfaceComposerClient::Transaction* t) {
                JNIEnv* env = getenv(globalCallbackRef->vm());
                ScopedLocalRef<jobject>
                        transactionObject(env,
                                          env->NewObject(gTransactionClassInfo.clazz,
                                                         gTransactionClassInfo.ctor,
                                                         reinterpret_cast<jlong>(t)));
                env->CallVoidMethod(globalCallbackRef->object(), gTransactionConsumer.accept,
                                    transactionObject.get());
            },
            acquireSingleBuffer);
}

static void nativeStopContinuousSyncTransaction(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->stopContinuousSyncTransaction();
}

static void nativeClearSyncTransaction(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->clearSyncTransaction();
}

static void nativeUpdate(JNIEnv* env, jclass clazz, jlong ptr, jlong surfaceControl, jlong width,
                         jlong height, jint format) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->update(reinterpret_cast<SurfaceControl*>(surfaceControl), width, height, format);
}

static void nativeMergeWithNextTransaction(JNIEnv*, jclass clazz, jlong ptr, jlong transactionPtr,
                                           jlong framenumber) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionPtr);
    queue->mergeWithNextTransaction(transaction, CC_UNLIKELY(framenumber < 0) ? 0 : framenumber);
}

static jlong nativeGetLastAcquiredFrameNum(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    return queue->getLastAcquiredFrameNum();
}

static void nativeApplyPendingTransactions(JNIEnv* env, jclass clazz, jlong ptr, jlong frameNum) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    queue->applyPendingTransactions(frameNum);
}

static bool nativeIsSameSurfaceControl(JNIEnv* env, jclass clazz, jlong ptr, jlong surfaceControl) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    return queue->isSameSurfaceControl(reinterpret_cast<SurfaceControl*>(surfaceControl));
}

static void nativeSetTransactionHangCallback(JNIEnv* env, jclass clazz, jlong ptr,
                                             jobject transactionHangCallback) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    if (transactionHangCallback == nullptr) {
        queue->setTransactionHangCallback(nullptr);
    } else {
        sp<TransactionHangCallbackWrapper> wrapper =
                new TransactionHangCallbackWrapper{env, transactionHangCallback};
        queue->setTransactionHangCallback(
                [wrapper](const std::string& reason) { wrapper->onTransactionHang(reason); });
    }
}

static jobject nativeGatherPendingTransactions(JNIEnv* env, jclass clazz, jlong ptr,
                                               jlong frameNum) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    SurfaceComposerClient::Transaction* transaction = queue->gatherPendingTransactions(frameNum);
    return env->NewObject(gTransactionClassInfo.clazz, gTransactionClassInfo.ctor,
                          reinterpret_cast<jlong>(transaction));
}

static void nativeSetApplyToken(JNIEnv* env, jclass clazz, jlong ptr, jobject applyTokenObject) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    sp<IBinder> token(ibinderForJavaObject(env, applyTokenObject));
    return queue->setApplyToken(std::move(token));
}

static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        // clang-format off
        {"nativeCreate", "(Ljava/lang/String;Z)J", (void*)nativeCreate},
        {"nativeGetSurface", "(JZ)Landroid/view/Surface;", (void*)nativeGetSurface},
        {"nativeDestroy", "(J)V", (void*)nativeDestroy},
        {"nativeSyncNextTransaction", "(JLjava/util/function/Consumer;Z)Z", (void*)nativeSyncNextTransaction},
        {"nativeStopContinuousSyncTransaction", "(J)V", (void*)nativeStopContinuousSyncTransaction},
        {"nativeClearSyncTransaction", "(J)V", (void*)nativeClearSyncTransaction},
        {"nativeUpdate", "(JJJJI)V", (void*)nativeUpdate},
        {"nativeMergeWithNextTransaction", "(JJJ)V", (void*)nativeMergeWithNextTransaction},
        {"nativeGetLastAcquiredFrameNum", "(J)J", (void*)nativeGetLastAcquiredFrameNum},
        {"nativeApplyPendingTransactions", "(JJ)V", (void*)nativeApplyPendingTransactions},
        {"nativeIsSameSurfaceControl", "(JJ)Z", (void*)nativeIsSameSurfaceControl},
        {"nativeGatherPendingTransactions", "(JJ)Landroid/view/SurfaceControl$Transaction;", (void*)nativeGatherPendingTransactions},
        {"nativeSetTransactionHangCallback",
         "(JLandroid/graphics/BLASTBufferQueue$TransactionHangCallback;)V",
         (void*)nativeSetTransactionHangCallback},
        {"nativeSetApplyToken", "(JLandroid/os/IBinder;)V", (void*)nativeSetApplyToken},
        // clang-format on
};

int register_android_graphics_BLASTBufferQueue(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/graphics/BLASTBufferQueue",
            gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass transactionClazz = FindClassOrDie(env, "android/view/SurfaceControl$Transaction");
    gTransactionClassInfo.clazz = MakeGlobalRefOrDie(env, transactionClazz);
    gTransactionClassInfo.ctor =
            GetMethodIDOrDie(env, gTransactionClassInfo.clazz, "<init>", "(J)V");

    jclass consumer = FindClassOrDie(env, "java/util/function/Consumer");
    gTransactionConsumer.accept =
            GetMethodIDOrDie(env, consumer, "accept", "(Ljava/lang/Object;)V");
    jclass transactionHangClass =
            FindClassOrDie(env, "android/graphics/BLASTBufferQueue$TransactionHangCallback");
    gTransactionHangCallback.onTransactionHang =
            GetMethodIDOrDie(env, transactionHangClass, "onTransactionHang",
                             "(Ljava/lang/String;)V");

    return 0;
}

} // namespace android
