/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "Fingerprint-JNI"

#include <nativehelper/JNIHelp.h>
#include <inttypes.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <android_os_MessageQueue.h>
#include <binder/IServiceManager.h>
#include <utils/String16.h>
#include <utils/Looper.h>
#include <keystore/IKeystoreService.h>
#include <keystore/keystore.h> // for error code

#include <hardware/hardware.h>
#include <hardware/fingerprint.h>
#include <hardware/hw_auth_token.h>

#include <utils/Log.h>
#include "core_jni_helpers.h"


namespace android {

static const uint16_t kVersion = HARDWARE_MODULE_API_VERSION(2, 0);

static const char* FINGERPRINT_SERVICE = "com/android/server/fingerprint/FingerprintService";
static struct {
    jclass clazz;
    jmethodID notify;
} gFingerprintServiceClassInfo;

static struct {
    fingerprint_module_t const* module;
    fingerprint_device_t *device;
} gContext;

static sp<Looper> gLooper;
static jobject gCallback;

class CallbackHandler : public MessageHandler {
    int type;
    int arg1, arg2, arg3;
public:
    CallbackHandler(int type, int arg1, int arg2, int arg3)
        : type(type), arg1(arg1), arg2(arg2), arg3(arg3) { }

    virtual void handleMessage(const Message& message) {
        //ALOG(LOG_VERBOSE, LOG_TAG, "hal_notify(msg=%d, arg1=%d, arg2=%d)\n", msg.type, arg1, arg2);
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(gCallback, gFingerprintServiceClassInfo.notify, type, arg1, arg2, arg3);
    }
};

static void notifyKeystore(uint8_t *auth_token, size_t auth_token_length) {
    if (auth_token != NULL && auth_token_length > 0) {
        // TODO: cache service?
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("android.security.keystore"));
        sp<IKeystoreService> service = interface_cast<IKeystoreService>(binder);
        if (service != NULL) {
            status_t ret = service->addAuthToken(auth_token, auth_token_length);
            if (ret != ResponseCode::NO_ERROR) {
                ALOGE("Falure sending auth token to KeyStore: %d", ret);
            }
        } else {
            ALOGE("Unable to communicate with KeyStore");
        }
    }
}

// Called by the HAL to notify us of fingerprint events
static void hal_notify_callback(fingerprint_msg_t msg) {
    uint32_t arg1 = 0;
    uint32_t arg2 = 0;
    uint32_t arg3 = 0;
    switch (msg.type) {
        case FINGERPRINT_ERROR:
            arg1 = msg.data.error;
            break;
        case FINGERPRINT_ACQUIRED:
            arg1 = msg.data.acquired.acquired_info;
            break;
        case FINGERPRINT_AUTHENTICATED:
            arg1 = msg.data.authenticated.finger.fid;
            arg2 = msg.data.authenticated.finger.gid;
            if (arg1 != 0) {
                notifyKeystore(reinterpret_cast<uint8_t *>(&msg.data.authenticated.hat),
                        sizeof(msg.data.authenticated.hat));
            }
            break;
        case FINGERPRINT_TEMPLATE_ENROLLING:
            arg1 = msg.data.enroll.finger.fid;
            arg2 = msg.data.enroll.finger.gid;
            arg3 = msg.data.enroll.samples_remaining;
            break;
        case FINGERPRINT_TEMPLATE_REMOVED:
            arg1 = msg.data.removed.finger.fid;
            arg2 = msg.data.removed.finger.gid;
            break;
        default:
            ALOGE("fingerprint: invalid msg: %d", msg.type);
            return;
    }
    // This call potentially comes in on a thread not owned by us. Hand it off to our
    // looper so it runs on our thread when calling back to FingerprintService.
    // CallbackHandler object is reference-counted, so no cleanup necessary.
    gLooper->sendMessage(new CallbackHandler(msg.type, arg1, arg2, arg3), Message());
}

static void nativeInit(JNIEnv *env, jobject clazz, jobject mQueue, jobject callbackObj) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeInit()\n");
    gCallback = MakeGlobalRefOrDie(env, callbackObj);
    gLooper = android_os_MessageQueue_getMessageQueue(env, mQueue)->getLooper();
}

static jint nativeEnroll(JNIEnv* env, jobject clazz, jbyteArray token, jint groupId, jint timeout) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeEnroll(gid=%d, timeout=%d)\n", groupId, timeout);
    const int tokenSize = env->GetArrayLength(token);
    jbyte* tokenData = env->GetByteArrayElements(token, 0);
    if (tokenSize != sizeof(hw_auth_token_t)) {
        ALOG(LOG_VERBOSE, LOG_TAG, "nativeEnroll() : invalid token size %d\n", tokenSize);
        return -1;
    }
    int ret = gContext.device->enroll(gContext.device,
            reinterpret_cast<const hw_auth_token_t*>(tokenData), groupId, timeout);
    env->ReleaseByteArrayElements(token, tokenData, 0);
    return reinterpret_cast<jint>(ret);
}

static jlong nativePreEnroll(JNIEnv* env, jobject clazz) {
    uint64_t ret = gContext.device->pre_enroll(gContext.device);
    // ALOG(LOG_VERBOSE, LOG_TAG, "nativePreEnroll(), result = %llx", ret);
    return reinterpret_cast<jlong>((int64_t)ret);
}

static jint nativeStopEnrollment(JNIEnv* env, jobject clazz) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeStopEnrollment()\n");
    int ret = gContext.device->cancel(gContext.device);
    return reinterpret_cast<jint>(ret);
}

static jint nativeAuthenticate(JNIEnv* env, jobject clazz, jlong sessionId, jint groupId) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeAuthenticate(sid=%" PRId64 ", gid=%d)\n", sessionId, groupId);
    int ret = gContext.device->authenticate(gContext.device, sessionId, groupId);
    return reinterpret_cast<jint>(ret);
}

static jint nativeStopAuthentication(JNIEnv* env, jobject clazz) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeStopAuthentication()\n");
    int ret = gContext.device->cancel(gContext.device);
    return reinterpret_cast<jint>(ret);
}

static jint nativeRemove(JNIEnv* env, jobject clazz, jint fingerId, jint groupId) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeRemove(fid=%d, gid=%d)\n", fingerId, groupId);
    fingerprint_finger_id_t finger;
    finger.fid = fingerId;
    finger.gid = groupId;
    int ret = gContext.device->remove(gContext.device, finger);
    return reinterpret_cast<jint>(ret);
}

static jlong nativeGetAuthenticatorId(JNIEnv *, jobject clazz) {
    return gContext.device->get_authenticator_id(gContext.device);
}

static jint nativeSetActiveGroup(JNIEnv *env, jobject clazz, jint gid, jbyteArray path) {
    const int pathSize = env->GetArrayLength(path);
    jbyte* pathData = env->GetByteArrayElements(path, 0);
    if (pathSize >= PATH_MAX) {
	ALOGE("Path name is too long\n");
        return -1;
    }
    char path_name[PATH_MAX] = {0};
    memcpy(path_name, pathData, pathSize);
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeSetActiveGroup() path: %s, gid: %d\n", path_name, gid);
    int result = gContext.device->set_active_group(gContext.device, gid, path_name);
    env->ReleaseByteArrayElements(path, pathData, 0);
    return result;
}

static jint nativeOpenHal(JNIEnv* env, jobject clazz) {
    ALOG(LOG_VERBOSE, LOG_TAG, "nativeOpenHal()\n");
    int err;
    const hw_module_t *hw_module = NULL;
    if (0 != (err = hw_get_module(FINGERPRINT_HARDWARE_MODULE_ID, &hw_module))) {
        ALOGE("Can't open fingerprint HW Module, error: %d", err);
        return 0;
    }
    if (NULL == hw_module) {
        ALOGE("No valid fingerprint module");
        return 0;
    }

    gContext.module = reinterpret_cast<const fingerprint_module_t*>(hw_module);

    if (gContext.module->common.methods->open == NULL) {
        ALOGE("No valid open method");
        return 0;
    }

    hw_device_t *device = NULL;

    if (0 != (err = gContext.module->common.methods->open(hw_module, NULL, &device))) {
        ALOGE("Can't open fingerprint methods, error: %d", err);
        return 0;
    }

    if (kVersion != device->version) {
        ALOGE("Wrong fp version. Expected %d, got %d", kVersion, device->version);
        // return 0; // FIXME
    }

    gContext.device = reinterpret_cast<fingerprint_device_t*>(device);
    err = gContext.device->set_notify(gContext.device, hal_notify_callback);
    if (err < 0) {
        ALOGE("Failed in call to set_notify(), err=%d", err);
        return 0;
    }

    // Sanity check - remove
    if (gContext.device->notify != hal_notify_callback) {
        ALOGE("NOTIFY not set properly: %p != %p", gContext.device->notify, hal_notify_callback);
    }

    ALOG(LOG_VERBOSE, LOG_TAG, "fingerprint HAL successfully initialized");
    return reinterpret_cast<jlong>(gContext.device);
}

static jint nativeCloseHal(JNIEnv* env, jobject clazz) {
    return -ENOSYS; // TODO
}


// ----------------------------------------------------------------------------


// TODO: clean up void methods
static const JNINativeMethod g_methods[] = {
    { "nativeAuthenticate", "(JI)I", (void*)nativeAuthenticate },
    { "nativeStopAuthentication", "()I", (void*)nativeStopAuthentication },
    { "nativeEnroll", "([BII)I", (void*)nativeEnroll },
    { "nativeSetActiveGroup", "(I[B)I", (void*)nativeSetActiveGroup },
    { "nativePreEnroll", "()J", (void*)nativePreEnroll },
    { "nativeStopEnrollment", "()I", (void*)nativeStopEnrollment },
    { "nativeRemove", "(II)I", (void*)nativeRemove },
    { "nativeGetAuthenticatorId", "()J", (void*)nativeGetAuthenticatorId },
    { "nativeOpenHal", "()I", (void*)nativeOpenHal },
    { "nativeCloseHal", "()I", (void*)nativeCloseHal },
    { "nativeInit","(Landroid/os/MessageQueue;"
            "Lcom/android/server/fingerprint/FingerprintService;)V", (void*)nativeInit }
};

int register_android_server_fingerprint_FingerprintService(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, FINGERPRINT_SERVICE);
    gFingerprintServiceClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gFingerprintServiceClassInfo.notify =
            GetMethodIDOrDie(env, gFingerprintServiceClassInfo.clazz,"notify", "(IIII)V");
    int result = RegisterMethodsOrDie(env, FINGERPRINT_SERVICE, g_methods, NELEM(g_methods));
    ALOG(LOG_VERBOSE, LOG_TAG, "FingerprintManager JNI ready.\n");
    return result;
}

} // namespace android
