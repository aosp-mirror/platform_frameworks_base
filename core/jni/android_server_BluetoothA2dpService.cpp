/*
** Copyright 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "BluetoothA2dpService.cpp"

#include "android_bluetooth_common.h"
#include "android_runtime/AndroidRuntime.h"
#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>

#ifdef HAVE_BLUETOOTH
#include <dbus/dbus.h>
#endif

namespace android {

#ifdef HAVE_BLUETOOTH
static jmethodID method_onSinkPropertyChanged;
static jmethodID method_onConnectSinkResult;

typedef struct {
    JavaVM *vm;
    int envVer;
    DBusConnection *conn;
    jobject me;  // for callbacks to java
} native_data_t;

static native_data_t *nat = NULL;  // global native data
static void onConnectSinkResult(DBusMessage *msg, void *user, void *n);

static Properties sink_properties[] = {
        {"State", DBUS_TYPE_STRING},
        {"Connected", DBUS_TYPE_BOOLEAN},
        {"Playing", DBUS_TYPE_BOOLEAN},
      };
#endif

/* Returns true on success (even if adapter is present but disabled).
 * Return false if dbus is down, or another serious error (out of memory)
*/
static bool initNative(JNIEnv* env, jobject object) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    nat = (native_data_t *)calloc(1, sizeof(native_data_t));
    if (NULL == nat) {
        LOGE("%s: out of memory!", __FUNCTION__);
        return false;
    }
    env->GetJavaVM( &(nat->vm) );
    nat->envVer = env->GetVersion();
    nat->me = env->NewGlobalRef(object);

    DBusError err;
    dbus_error_init(&err);
    dbus_threads_init_default();
    nat->conn = dbus_bus_get(DBUS_BUS_SYSTEM, &err);
    if (dbus_error_is_set(&err)) {
        LOGE("Could not get onto the system bus: %s", err.message);
        dbus_error_free(&err);
        return false;
    }
    dbus_connection_set_exit_on_disconnect(nat->conn, FALSE);
#endif  /*HAVE_BLUETOOTH*/
    return true;
}

static void cleanupNative(JNIEnv* env, jobject object) {
#ifdef HAVE_BLUETOOTH
    ALOGV("%s", __FUNCTION__);
    if (nat) {
        dbus_connection_close(nat->conn);
        env->DeleteGlobalRef(nat->me);
        free(nat);
        nat = NULL;
    }
#endif
}

static jobjectArray getSinkPropertiesNative(JNIEnv *env, jobject object,
                                            jstring path) {
#ifdef HAVE_BLUETOOTH
    ALOGV("%s", __FUNCTION__);
    if (nat) {
        DBusMessage *msg, *reply;
        DBusError err;
        dbus_error_init(&err);

        const char *c_path = env->GetStringUTFChars(path, NULL);
        reply = dbus_func_args_timeout(env,
                                   nat->conn, -1, c_path,
                                   "org.bluez.AudioSink", "GetProperties",
                                   DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        if (!reply && dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
            return NULL;
        } else if (!reply) {
            LOGE("DBus reply is NULL in function %s", __FUNCTION__);
            return NULL;
        }
        DBusMessageIter iter;
        if (dbus_message_iter_init(reply, &iter))
            return parse_properties(env, &iter, (Properties *)&sink_properties,
                                 sizeof(sink_properties) / sizeof(Properties));
    }
#endif
    return NULL;
}


static jboolean connectSinkNative(JNIEnv *env, jobject object, jstring path) {
#ifdef HAVE_BLUETOOTH
    ALOGV("%s", __FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        int len = env->GetStringLength(path) + 1;
        char *context_path = (char *)calloc(len, sizeof(char));
        strlcpy(context_path, c_path, len);  // for callback

        bool ret = dbus_func_args_async(env, nat->conn, -1, onConnectSinkResult, context_path,
                                    nat, c_path, "org.bluez.AudioSink", "Connect",
                                    DBUS_TYPE_INVALID);

        env->ReleaseStringUTFChars(path, c_path);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean disconnectSinkNative(JNIEnv *env, jobject object,
                                     jstring path) {
#ifdef HAVE_BLUETOOTH
    ALOGV("%s", __FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);

        bool ret = dbus_func_args_async(env, nat->conn, -1, NULL, NULL, nat,
                                    c_path, "org.bluez.AudioSink", "Disconnect",
                                    DBUS_TYPE_INVALID);

        env->ReleaseStringUTFChars(path, c_path);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean suspendSinkNative(JNIEnv *env, jobject object,
                                     jstring path) {
#ifdef HAVE_BLUETOOTH
    ALOGV("%s", __FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        bool ret = dbus_func_args_async(env, nat->conn, -1, NULL, NULL, nat,
                           c_path, "org.bluez.audio.Sink", "Suspend",
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean resumeSinkNative(JNIEnv *env, jobject object,
                                     jstring path) {
#ifdef HAVE_BLUETOOTH
    ALOGV("%s", __FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        bool ret = dbus_func_args_async(env, nat->conn, -1, NULL, NULL, nat,
                           c_path, "org.bluez.audio.Sink", "Resume",
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean avrcpVolumeUpNative(JNIEnv *env, jobject object,
                                     jstring path) {
#ifdef HAVE_BLUETOOTH
    ALOGV("%s", __FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        bool ret = dbus_func_args_async(env, nat->conn, -1, NULL, NULL, nat,
                           c_path, "org.bluez.Control", "VolumeUp",
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean avrcpVolumeDownNative(JNIEnv *env, jobject object,
                                     jstring path) {
#ifdef HAVE_BLUETOOTH
    ALOGV("%s", __FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        bool ret = dbus_func_args_async(env, nat->conn, -1, NULL, NULL, nat,
                           c_path, "org.bluez.Control", "VolumeDown",
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

#ifdef HAVE_BLUETOOTH
DBusHandlerResult a2dp_event_filter(DBusMessage *msg, JNIEnv *env) {
    DBusError err;

    if (!nat) {
        ALOGV("... skipping %s\n", __FUNCTION__);
        ALOGV("... ignored\n");
        return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    }

    dbus_error_init(&err);

    if (dbus_message_get_type(msg) != DBUS_MESSAGE_TYPE_SIGNAL) {
        return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    }

    DBusHandlerResult result = DBUS_HANDLER_RESULT_NOT_YET_HANDLED;

    if (dbus_message_is_signal(msg, "org.bluez.AudioSink",
                                      "PropertyChanged")) {
        jobjectArray str_array =
                    parse_property_change(env, msg, (Properties *)&sink_properties,
                                sizeof(sink_properties) / sizeof(Properties));
        const char *c_path = dbus_message_get_path(msg);
        jstring path = env->NewStringUTF(c_path);
        env->CallVoidMethod(nat->me,
                            method_onSinkPropertyChanged,
                            path,
                            str_array);
        env->DeleteLocalRef(path);
        result = DBUS_HANDLER_RESULT_HANDLED;
        return result;
    } else {
        ALOGV("... ignored");
    }
    if (env->ExceptionCheck()) {
        LOGE("VM Exception occurred while handling %s.%s (%s) in %s,"
             " leaving for VM",
             dbus_message_get_interface(msg), dbus_message_get_member(msg),
             dbus_message_get_path(msg), __FUNCTION__);
    }

    return result;
}

void onConnectSinkResult(DBusMessage *msg, void *user, void *n) {
    ALOGV("%s", __FUNCTION__);

    native_data_t *nat = (native_data_t *)n;
    const char *path = (const char *)user;
    DBusError err;
    dbus_error_init(&err);
    JNIEnv *env;
    nat->vm->GetEnv((void**)&env, nat->envVer);


    bool result = JNI_TRUE;
    if (dbus_set_error_from_message(&err, msg)) {
        LOG_AND_FREE_DBUS_ERROR(&err);
        result = JNI_FALSE;
    }
    ALOGV("... Device Path = %s, result = %d", path, result);

    jstring jPath = env->NewStringUTF(path);
    env->CallVoidMethod(nat->me,
                        method_onConnectSinkResult,
                        jPath,
                        result);
    env->DeleteLocalRef(jPath);
    free(user);
}


#endif


static JNINativeMethod sMethods[] = {
    {"initNative", "()Z", (void *)initNative},
    {"cleanupNative", "()V", (void *)cleanupNative},

    /* Bluez audio 4.47 API */
    {"connectSinkNative", "(Ljava/lang/String;)Z", (void *)connectSinkNative},
    {"disconnectSinkNative", "(Ljava/lang/String;)Z", (void *)disconnectSinkNative},
    {"suspendSinkNative", "(Ljava/lang/String;)Z", (void*)suspendSinkNative},
    {"resumeSinkNative", "(Ljava/lang/String;)Z", (void*)resumeSinkNative},
    {"getSinkPropertiesNative", "(Ljava/lang/String;)[Ljava/lang/Object;",
                                    (void *)getSinkPropertiesNative},
    {"avrcpVolumeUpNative", "(Ljava/lang/String;)Z", (void*)avrcpVolumeUpNative},
    {"avrcpVolumeDownNative", "(Ljava/lang/String;)Z", (void*)avrcpVolumeDownNative},
};

int register_android_server_BluetoothA2dpService(JNIEnv *env) {
    jclass clazz = env->FindClass("android/server/BluetoothA2dpService");
    if (clazz == NULL) {
        LOGE("Can't find android/server/BluetoothA2dpService");
        return -1;
    }

#ifdef HAVE_BLUETOOTH
    method_onSinkPropertyChanged = env->GetMethodID(clazz, "onSinkPropertyChanged",
                                          "(Ljava/lang/String;[Ljava/lang/String;)V");
    method_onConnectSinkResult = env->GetMethodID(clazz, "onConnectSinkResult",
                                                         "(Ljava/lang/String;Z)V");
#endif

    return AndroidRuntime::registerNativeMethods(env,
                "android/server/BluetoothA2dpService", sMethods, NELEM(sMethods));
}

} /* namespace android */
