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
static jmethodID method_onHeadsetCreated;
static jmethodID method_onHeadsetRemoved;
static jmethodID method_onSinkConnected;
static jmethodID method_onSinkDisconnected;
static jmethodID method_onSinkPlaying;
static jmethodID method_onSinkStopped;

typedef struct {
    JavaVM *vm;
    int envVer;
    DBusConnection *conn;
    jobject me;  // for callbacks to java
} native_data_t;

static native_data_t *nat = NULL;  // global native data

#endif

#ifdef HAVE_BLUETOOTH
static void onConnectSinkResult(DBusMessage *msg, void *user, void *nat);
static void onDisconnectSinkResult(DBusMessage *msg, void *user, void *nat);
#endif

/* Returns true on success (even if adapter is present but disabled).
 * Return false if dbus is down, or another serious error (out of memory)
*/
static bool initNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
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
    LOGV(__FUNCTION__);
    if (nat) {
        dbus_connection_close(nat->conn);
        env->DeleteGlobalRef(nat->me);
        free(nat);
        nat = NULL;
    }
#endif
}
static jobjectArray listHeadsetsNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    if (nat) {
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, "/org/bluez/audio",
                           "org.bluez.audio.Manager", "ListHeadsets",
                           DBUS_TYPE_INVALID);
        return reply ? dbus_returns_array_of_strings(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jstring createHeadsetNative(JNIEnv *env, jobject object,
                                     jstring address) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        LOGV("... address = %s\n", c_address);
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, "/org/bluez/audio",
                           "org.bluez.audio.Manager", "CreateHeadset",
                           DBUS_TYPE_STRING, &c_address,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        return reply ? dbus_returns_string(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jstring removeHeadsetNative(JNIEnv *env, jobject object, jstring path) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, "/org/bluez/audio",
                           "org.bluez.audio.Manager", "RemoveHeadset",
                           DBUS_TYPE_STRING, &c_path,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        return reply ? dbus_returns_string(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jstring getAddressNative(JNIEnv *env, jobject object, jstring path) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, c_path,
                           "org.bluez.audio.Device", "GetAddress",
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        return reply ? dbus_returns_string(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jboolean connectSinkNative(JNIEnv *env, jobject object, jstring path) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        size_t path_sz = env->GetStringUTFLength(path) + 1;
        char *c_path_copy = (char *)malloc(path_sz);  // callback data
        strncpy(c_path_copy, c_path, path_sz);

        bool ret =
            dbus_func_args_async(env, nat->conn, -1,
                           onConnectSinkResult, (void *)c_path_copy, nat,
                           c_path,
                           "org.bluez.audio.Sink", "Connect",
                           DBUS_TYPE_INVALID);

        env->ReleaseStringUTFChars(path, c_path);
        if (!ret) {
            free(c_path_copy);
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean disconnectSinkNative(JNIEnv *env, jobject object,
                                     jstring path) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        size_t path_sz = env->GetStringUTFLength(path) + 1;
        char *c_path_copy = (char *)malloc(path_sz);  // callback data
        strncpy(c_path_copy, c_path, path_sz);

        bool ret =
            dbus_func_args_async(env, nat->conn, -1,
                           onDisconnectSinkResult, (void *)c_path_copy, nat,
                           c_path,
                           "org.bluez.audio.Sink", "Disconnect",
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        if (!ret) {
            free(c_path_copy);
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean isSinkConnectedNative(JNIEnv *env, jobject object, jstring path) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    if (nat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, c_path,
                           "org.bluez.audio.Sink", "IsConnected",
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        return reply ? dbus_returns_boolean(env, reply) : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

#ifdef HAVE_BLUETOOTH
static void onConnectSinkResult(DBusMessage *msg, void *user, void *natData) {
    LOGV(__FUNCTION__);

    char *c_path = (char *)user;
    DBusError err;
    JNIEnv *env;

    if (nat->vm->GetEnv((void**)&env, nat->envVer) < 0) {
        LOGE("%s: error finding Env for our VM\n", __FUNCTION__);
        return;
    }

    dbus_error_init(&err);

    LOGV("... path = %s", c_path);
    if (dbus_set_error_from_message(&err, msg)) {
        /* if (!strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.AuthenticationFailed")) */
        LOGE("%s: D-Bus error: %s (%s)\n", __FUNCTION__, err.name, err.message);
        dbus_error_free(&err);
        env->CallVoidMethod(nat->me,
                            method_onSinkDisconnected,
                            env->NewStringUTF(c_path));
        if (env->ExceptionCheck()) {
            LOGE("VM Exception occurred in native function %s (%s:%d)",
                 __FUNCTION__, __FILE__, __LINE__);
        }
    } // else Java callback is triggered by signal in a2dp_event_filter

    free(c_path);
}

static void onDisconnectSinkResult(DBusMessage *msg, void *user, void *natData) {
    LOGV(__FUNCTION__);

    char *c_path = (char *)user;
    DBusError err;
    JNIEnv *env;

    if (nat->vm->GetEnv((void**)&env, nat->envVer) < 0) {
        LOGE("%s: error finding Env for our VM\n", __FUNCTION__);
        return;
    }

    dbus_error_init(&err);

    LOGV("... path = %s", c_path);
    if (dbus_set_error_from_message(&err, msg)) {
        /* if (!strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.AuthenticationFailed")) */
        LOGE("%s: D-Bus error: %s (%s)\n", __FUNCTION__, err.name, err.message);
        if (strcmp(err.name, "org.bluez.Error.NotConnected") == 0) {
            // we were already disconnected, so report disconnect
            env->CallVoidMethod(nat->me,
                                method_onSinkDisconnected,
                                env->NewStringUTF(c_path));
        } else {
            // Assume it is still connected
            env->CallVoidMethod(nat->me,
                                method_onSinkConnected,
                                env->NewStringUTF(c_path));
        }
        dbus_error_free(&err);
        if (env->ExceptionCheck()) {
            LOGE("VM Exception occurred in native function %s (%s:%d)",
                 __FUNCTION__, __FILE__, __LINE__);
        }
    } // else Java callback is triggered by signal in a2dp_event_filter

    free(c_path);
}

DBusHandlerResult a2dp_event_filter(DBusMessage *msg, JNIEnv *env) {
    DBusError err;

    if (!nat) {
        LOGV("... skipping %s\n", __FUNCTION__);
        LOGV("... ignored\n");
        return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    }

    dbus_error_init(&err);

    if (dbus_message_get_type(msg) != DBUS_MESSAGE_TYPE_SIGNAL) {
        return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    }

    DBusHandlerResult result = DBUS_HANDLER_RESULT_NOT_YET_HANDLED;

    if (dbus_message_is_signal(msg,
                               "org.bluez.audio.Manager",
                               "HeadsetCreated")) {
        char *c_path;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_path,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... path = %s", c_path);
            env->CallVoidMethod(nat->me,
                                method_onHeadsetCreated,
                                env->NewStringUTF(c_path));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        result = DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.audio.Manager",
                                      "HeadsetRemoved")) {
        char *c_path;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_path,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... path = %s", c_path);
            env->CallVoidMethod(nat->me,
                                method_onHeadsetRemoved,
                                env->NewStringUTF(c_path));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        result = DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.audio.Sink",
                                      "Connected")) {
        const char *c_path = dbus_message_get_path(msg);
        LOGV("... path = %s", c_path);
        env->CallVoidMethod(nat->me,
                            method_onSinkConnected,
                            env->NewStringUTF(c_path));
        result = DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.audio.Sink",
                                      "Disconnected")) {
        const char *c_path = dbus_message_get_path(msg);
        LOGV("... path = %s", c_path);
        env->CallVoidMethod(nat->me,
                            method_onSinkDisconnected,
                            env->NewStringUTF(c_path));
        result = DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.audio.Sink",
                                      "Playing")) {
        const char *c_path = dbus_message_get_path(msg);
        LOGV("... path = %s", c_path);
        env->CallVoidMethod(nat->me,
                            method_onSinkPlaying,
                            env->NewStringUTF(c_path));
        result = DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.audio.Sink",
                                      "Stopped")) {
        const char *c_path = dbus_message_get_path(msg);
        LOGV("... path = %s", c_path);
        env->CallVoidMethod(nat->me,
                            method_onSinkStopped,
                            env->NewStringUTF(c_path));
        result = DBUS_HANDLER_RESULT_HANDLED;
    }

    if (result == DBUS_HANDLER_RESULT_NOT_YET_HANDLED) {
        LOGV("... ignored");
    }
    if (env->ExceptionCheck()) {
        LOGE("VM Exception occurred while handling %s.%s (%s) in %s,"
             " leaving for VM",
             dbus_message_get_interface(msg), dbus_message_get_member(msg),
             dbus_message_get_path(msg), __FUNCTION__);
    }

    return result;
}
#endif


static JNINativeMethod sMethods[] = {
    {"initNative", "()Z", (void *)initNative},
    {"cleanupNative", "()V", (void *)cleanupNative},

    /* Bluez audio 3.36 API */
    {"listHeadsetsNative", "()[Ljava/lang/String;", (void*)listHeadsetsNative},
    {"createHeadsetNative", "(Ljava/lang/String;)Ljava/lang/String;", (void*)createHeadsetNative},
    {"removeHeadsetNative", "(Ljava/lang/String;)Z", (void*)removeHeadsetNative},
    {"getAddressNative", "(Ljava/lang/String;)Ljava/lang/String;", (void*)getAddressNative},
    {"connectSinkNative", "(Ljava/lang/String;)Z", (void*)connectSinkNative},
    {"disconnectSinkNative", "(Ljava/lang/String;)Z", (void*)disconnectSinkNative},
    {"isSinkConnectedNative", "(Ljava/lang/String;)Z", (void*)isSinkConnectedNative},
};

int register_android_server_BluetoothA2dpService(JNIEnv *env) {
    jclass clazz = env->FindClass("android/server/BluetoothA2dpService");
    if (clazz == NULL) {
        LOGE("Can't find android/server/BluetoothA2dpService");
        return -1;
    }

#ifdef HAVE_BLUETOOTH
    method_onHeadsetCreated = env->GetMethodID(clazz, "onHeadsetCreated", "(Ljava/lang/String;)V");
    method_onHeadsetRemoved = env->GetMethodID(clazz, "onHeadsetRemoved", "(Ljava/lang/String;)V");
    method_onSinkConnected = env->GetMethodID(clazz, "onSinkConnected", "(Ljava/lang/String;)V");
    method_onSinkDisconnected = env->GetMethodID(clazz, "onSinkDisconnected", "(Ljava/lang/String;)V");
    method_onSinkPlaying = env->GetMethodID(clazz, "onSinkPlaying", "(Ljava/lang/String;)V");
    method_onSinkStopped = env->GetMethodID(clazz, "onSinkStopped", "(Ljava/lang/String;)V");
#endif

    return AndroidRuntime::registerNativeMethods(env,
                "android/server/BluetoothA2dpService", sMethods, NELEM(sMethods));
}

} /* namespace android */
