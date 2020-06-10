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

#define LOG_TAG "AdbDebuggingManager-JNI"

#define LOG_NDEBUG 0

#include <algorithm>
#include <condition_variable>
#include <mutex>
#include <optional>
#include <random>
#include <string>
#include <vector>

#include <adb/pairing/pairing_server.h>
#include <android-base/properties.h>
#include <utils/Log.h>

#include <nativehelper/JNIHelp.h>
#include "jni.h"

namespace android {

// ----------------------------------------------------------------------------
namespace {

template <class T, class N>
class JSmartWrapper {
public:
    JSmartWrapper(JNIEnv* env, T* jData) : mEnv(env), mJData(jData) {}

    virtual ~JSmartWrapper() = default;

    const N* data() const { return mRawData; }

    jsize size() const { return mSize; }

protected:
    N* mRawData = nullptr;
    JNIEnv* mEnv = nullptr;
    T* mJData = nullptr;
    jsize mSize = 0;
}; // JSmartWrapper

class JStringUTFWrapper : public JSmartWrapper<jstring, const char> {
public:
    explicit JStringUTFWrapper(JNIEnv* env, jstring* str) : JSmartWrapper(env, str) {
        mRawData = env->GetStringUTFChars(*str, NULL);
        mSize = env->GetStringUTFLength(*str);
    }

    virtual ~JStringUTFWrapper() {
        if (data()) {
            mEnv->ReleaseStringUTFChars(*mJData, mRawData);
        }
    }
}; // JStringUTFWrapper

struct ServerDeleter {
    void operator()(PairingServerCtx* p) { pairing_server_destroy(p); }
};
using PairingServerPtr = std::unique_ptr<PairingServerCtx, ServerDeleter>;
struct PairingResultWaiter {
    std::mutex mutex_;
    std::condition_variable cv_;
    std::optional<bool> is_valid_;
    PeerInfo peer_info_;

    static void ResultCallback(const PeerInfo* peer_info, void* opaque) {
        auto* p = reinterpret_cast<PairingResultWaiter*>(opaque);
        {
            std::unique_lock<std::mutex> lock(p->mutex_);
            if (peer_info) {
                memcpy(&(p->peer_info_), peer_info, sizeof(PeerInfo));
            }
            p->is_valid_ = (peer_info != nullptr);
        }
        p->cv_.notify_one();
    }
};

PairingServerPtr sServer;
std::unique_ptr<PairingResultWaiter> sWaiter;
} // namespace

static jint native_pairing_start(JNIEnv* env, jobject thiz, jstring guid, jstring password) {
    // Server-side only sends its GUID on success.
    PeerInfo system_info = {};
    system_info.type = ADB_DEVICE_GUID;
    JStringUTFWrapper guidWrapper(env, &guid);
    memcpy(system_info.data, guidWrapper.data(), guidWrapper.size());

    JStringUTFWrapper passwordWrapper(env, &password);

    // Create the pairing server
    sServer = PairingServerPtr(
            pairing_server_new_no_cert(reinterpret_cast<const uint8_t*>(passwordWrapper.data()),
                                       passwordWrapper.size(), &system_info, 0));

    sWaiter.reset(new PairingResultWaiter);
    uint16_t port = pairing_server_start(sServer.get(), sWaiter->ResultCallback, sWaiter.get());
    if (port == 0) {
        ALOGE("Failed to start pairing server");
        return -1;
    }

    return port;
}

static void native_pairing_cancel(JNIEnv* /* env */, jclass /* clazz */) {
    if (sServer != nullptr) {
        sServer.reset();
    }
}

static jboolean native_pairing_wait(JNIEnv* env, jobject thiz) {
    ALOGI("Waiting for pairing server to complete");
    std::unique_lock<std::mutex> lock(sWaiter->mutex_);
    if (!sWaiter->is_valid_.has_value()) {
        sWaiter->cv_.wait(lock, [&]() { return sWaiter->is_valid_.has_value(); });
    }
    if (!*(sWaiter->is_valid_)) {
        return JNI_FALSE;
    }

    std::string peer_public_key = reinterpret_cast<char*>(sWaiter->peer_info_.data);
    // Write to PairingThread's member variables
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID mPublicKey = env->GetFieldID(clazz, "mPublicKey", "Ljava/lang/String;");
    jstring jpublickey = env->NewStringUTF(peer_public_key.c_str());
    env->SetObjectField(thiz, mPublicKey, jpublickey);
    return JNI_TRUE;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gPairingThreadMethods[] = {
        /* name, signature, funcPtr */
        {"native_pairing_start", "(Ljava/lang/String;Ljava/lang/String;)I",
         (void*)native_pairing_start},
        {"native_pairing_cancel", "()V", (void*)native_pairing_cancel},
        {"native_pairing_wait", "()Z", (void*)native_pairing_wait},
};

int register_android_server_AdbDebuggingManager(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env,
                                       "com/android/server/adb/AdbDebuggingManager$PairingThread",
                                       gPairingThreadMethods, NELEM(gPairingThreadMethods));
    (void)res; // Faked use when LOG_NDEBUG.
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

} /* namespace android */
