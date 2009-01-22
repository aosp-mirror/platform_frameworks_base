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

#define LOG_TAG "BluetoothEventLoop.cpp"

#include "android_bluetooth_common.h"
#include "android_runtime/AndroidRuntime.h"
#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>

#ifdef HAVE_BLUETOOTH
#include <dbus/dbus.h>
#endif

namespace android {

#ifdef HAVE_BLUETOOTH
static jfieldID field_mNativeData;

static jmethodID method_onModeChanged;
static jmethodID method_onNameChanged;
static jmethodID method_onDiscoveryStarted;
static jmethodID method_onDiscoveryCompleted;
static jmethodID method_onRemoteDeviceFound;
static jmethodID method_onRemoteDeviceDisappeared;
static jmethodID method_onRemoteClassUpdated;
static jmethodID method_onRemoteNameUpdated;
static jmethodID method_onRemoteNameFailed;
static jmethodID method_onRemoteAliasChanged;
static jmethodID method_onRemoteAliasCleared;
static jmethodID method_onRemoteDeviceConnected;
static jmethodID method_onRemoteDeviceDisconnectRequested;
static jmethodID method_onRemoteDeviceDisconnected;
static jmethodID method_onBondingCreated;
static jmethodID method_onBondingRemoved;

static jmethodID method_onCreateBondingResult;
static jmethodID method_onGetRemoteServiceChannelResult;

static jmethodID method_onPasskeyAgentRequest;
static jmethodID method_onPasskeyAgentCancel;

typedef event_loop_native_data_t native_data_t;

// Only valid during waitForAndDispatchEventNative()
native_data_t *event_loop_nat;

static inline native_data_t * get_native_data(JNIEnv *env, jobject object) {
    return (native_data_t *)(env->GetIntField(object,
                                                 field_mNativeData));
}

#endif
static void classInitNative(JNIEnv* env, jclass clazz) {
    LOGV(__FUNCTION__);

#ifdef HAVE_BLUETOOTH
    method_onModeChanged = env->GetMethodID(clazz, "onModeChanged", "(Ljava/lang/String;)V");
    method_onNameChanged = env->GetMethodID(clazz, "onNameChanged", "(Ljava/lang/String;)V");
    method_onDiscoveryStarted = env->GetMethodID(clazz, "onDiscoveryStarted", "()V");
    method_onDiscoveryCompleted = env->GetMethodID(clazz, "onDiscoveryCompleted", "()V");
    method_onRemoteDeviceFound = env->GetMethodID(clazz, "onRemoteDeviceFound", "(Ljava/lang/String;IS)V");
    method_onRemoteDeviceDisappeared = env->GetMethodID(clazz, "onRemoteDeviceDisappeared", "(Ljava/lang/String;)V");
    method_onRemoteClassUpdated = env->GetMethodID(clazz, "onRemoteClassUpdated", "(Ljava/lang/String;I)V");
    method_onRemoteNameUpdated = env->GetMethodID(clazz, "onRemoteNameUpdated", "(Ljava/lang/String;Ljava/lang/String;)V");
    method_onRemoteNameFailed = env->GetMethodID(clazz, "onRemoteNameFailed", "(Ljava/lang/String;)V");
    method_onRemoteAliasChanged = env->GetMethodID(clazz, "onRemoteAliasChanged", "(Ljava/lang/String;Ljava/lang/String;)V");
    method_onRemoteDeviceConnected = env->GetMethodID(clazz, "onRemoteDeviceConnected", "(Ljava/lang/String;)V");
    method_onRemoteDeviceDisconnectRequested = env->GetMethodID(clazz, "onRemoteDeviceDisconnectRequested", "(Ljava/lang/String;)V");
    method_onRemoteDeviceDisconnected = env->GetMethodID(clazz, "onRemoteDeviceDisconnected", "(Ljava/lang/String;)V");
    method_onBondingCreated = env->GetMethodID(clazz, "onBondingCreated", "(Ljava/lang/String;)V");
    method_onBondingRemoved = env->GetMethodID(clazz, "onBondingRemoved", "(Ljava/lang/String;)V");

    method_onCreateBondingResult = env->GetMethodID(clazz, "onCreateBondingResult", "(Ljava/lang/String;I)V");

    method_onPasskeyAgentRequest = env->GetMethodID(clazz, "onPasskeyAgentRequest", "(Ljava/lang/String;I)V");
    method_onPasskeyAgentCancel = env->GetMethodID(clazz, "onPasskeyAgentCancel", "(Ljava/lang/String;)V");
    method_onGetRemoteServiceChannelResult = env->GetMethodID(clazz, "onGetRemoteServiceChannelResult", "(Ljava/lang/String;I)V");

    field_mNativeData = env->GetFieldID(clazz, "mNativeData", "I");
#endif
}

static void initializeNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = (native_data_t *)calloc(1, sizeof(native_data_t));
    if (NULL == nat) {
        LOGE("%s: out of memory!", __FUNCTION__);
        return;
    }
    env->SetIntField(object, field_mNativeData, (jint)nat);

    {
        DBusError err;
        dbus_error_init(&err);
        dbus_threads_init_default();
        nat->conn = dbus_bus_get(DBUS_BUS_SYSTEM, &err);
        if (dbus_error_is_set(&err)) {
            LOGE("%s: Could not get onto the system bus!", __FUNCTION__);
            dbus_error_free(&err);
        }
    }
#endif
}

static void cleanupNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat =
            (native_data_t *)env->GetIntField(object, field_mNativeData);
    if (nat) {
        free(nat);
    }
#endif
}

#ifdef HAVE_BLUETOOTH
static DBusHandlerResult event_filter(DBusConnection *conn, DBusMessage *msg,
                                      void *data);
static DBusHandlerResult passkey_agent_event_filter(DBusConnection *conn,
                                                    DBusMessage *msg,
                                                    void *data);

static const DBusObjectPathVTable passkey_agent_vtable = {
    NULL, passkey_agent_event_filter, NULL, NULL, NULL, NULL
};
#endif

static jboolean setUpEventLoopNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    dbus_threads_init_default();
    native_data_t *nat = get_native_data(env, object);
    DBusError err;
    dbus_error_init(&err);

    if (nat != NULL && nat->conn != NULL) {
        // Add a filter for all incoming messages
        if (!dbus_connection_add_filter(nat->conn, event_filter, nat, NULL)){
            return JNI_FALSE;
        }

        // Set which messages will be processed by this dbus connection
        dbus_bus_add_match(nat->conn,
                "type='signal',interface='"BLUEZ_DBUS_BASE_IFC".Adapter'",
                &err);
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR(&err);
            return JNI_FALSE;
        }
        dbus_bus_add_match(nat->conn,
                "type='signal',interface='org.bluez.audio.Manager'",
                &err);
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR(&err);
            return JNI_FALSE;
        }
        dbus_bus_add_match(nat->conn,
                "type='signal',interface='org.bluez.audio.Device'",
                &err);
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR(&err);
            return JNI_FALSE;
        }
        dbus_bus_add_match(nat->conn,
                "type='signal',interface='org.bluez.audio.Sink'",
                &err);
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR(&err);
            return JNI_FALSE;
        }

        // Add an object handler for passkey agent method calls
        const char *path = "/android/bluetooth/PasskeyAgent";
        if (!dbus_connection_register_object_path(nat->conn, path,
                                                  &passkey_agent_vtable, NULL)) {
            LOGE("%s: Can't register object path %s for agent!",
                 __FUNCTION__, path);
            return JNI_FALSE;
        }

        // RegisterDefaultPasskeyAgent() will fail until hcid is up, so keep
        // trying for 10 seconds.
        int attempt;
        for (attempt = 1000; attempt > 0; attempt--) {
            DBusMessage *reply = dbus_func_args_error(env, nat->conn, &err,
                    BLUEZ_DBUS_BASE_PATH,
                    "org.bluez.Security", "RegisterDefaultPasskeyAgent",
                    DBUS_TYPE_STRING, &path,
                    DBUS_TYPE_INVALID);
            if (reply) {
                // Success
                dbus_message_unref(reply);
                return JNI_TRUE;
            } else if (dbus_error_has_name(&err,
                    "org.freedesktop.DBus.Error.ServiceUnknown")) {
                // hcid is still down, retry
                dbus_error_free(&err);
                usleep(10000);  // 10 ms
            } else {
                // Some other error we weren't expecting
                LOG_AND_FREE_DBUS_ERROR(&err);
                return JNI_FALSE;
            }
        }
        LOGE("Time-out trying to call RegisterDefaultPasskeyAgent(), "
             "is hcid running?");
        return JNI_FALSE;
    }

#endif
    return JNI_FALSE;
}

static void tearDownEventLoopNative(JNIEnv *env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat != NULL && nat->conn != NULL) {

        DBusError err;
        dbus_error_init(&err);

        const char *path = "/android/bluetooth/PasskeyAgent";
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, BLUEZ_DBUS_BASE_PATH,
                           "org.bluez.Security", "UnregisterDefaultPasskeyAgent",
                           DBUS_TYPE_STRING, &path,
                           DBUS_TYPE_INVALID);
        if (reply) dbus_message_unref(reply);

        dbus_connection_unregister_object_path(nat->conn, path);

        dbus_bus_remove_match(nat->conn,
                "type='signal',interface='org.bluez.audio.Sink'",
                &err);
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR(&err);
        }
        dbus_bus_remove_match(nat->conn,
                "type='signal',interface='org.bluez.audio.Device'",
                &err);
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR(&err);
        }
        dbus_bus_remove_match(nat->conn,
                "type='signal',interface='org.bluez.audio.Manager'",
                &err);
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR(&err);
        }
        dbus_bus_remove_match(nat->conn,
                "type='signal',interface='"BLUEZ_DBUS_BASE_IFC".Adapter'",
                &err);
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR(&err);
        }

        dbus_connection_remove_filter(nat->conn, event_filter, nat);
    }
#endif
}

#ifdef HAVE_BLUETOOTH
extern DBusHandlerResult a2dp_event_filter(DBusMessage *msg, JNIEnv *env);

// Called by dbus during WaitForAndDispatchEventNative()
static DBusHandlerResult event_filter(DBusConnection *conn, DBusMessage *msg,
                                      void *data) {
    native_data_t *nat;
    JNIEnv *env;
    DBusError err;

    dbus_error_init(&err);

    nat = (native_data_t *)data;
    env = nat->env;
    if (dbus_message_get_type(msg) != DBUS_MESSAGE_TYPE_SIGNAL) {
        LOGV("%s: not interested (not a signal).", __FUNCTION__);
        return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    }

    LOGV("%s: Received signal %s:%s from %s", __FUNCTION__,
         dbus_message_get_interface(msg), dbus_message_get_member(msg),
         dbus_message_get_path(msg));

    if (dbus_message_is_signal(msg,
                               "org.bluez.Adapter",
                               "RemoteDeviceFound")) {
        char *c_address;
        int n_class;
        short n_rssi;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_UINT32, &n_class,
                                  DBUS_TYPE_INT16, &n_rssi,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s class = %#X rssi = %hd", c_address, n_class,
                 n_rssi);
            env->CallVoidMethod(nat->me,
                                method_onRemoteDeviceFound,
                                env->NewStringUTF(c_address),
                                (jint)n_class,
                                (jshort)n_rssi);
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "DiscoveryStarted")) {
        LOGI("DiscoveryStarted signal received");
        env->CallVoidMethod(nat->me, method_onDiscoveryStarted);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                    "org.bluez.Adapter",
                                    "DiscoveryCompleted")) {
        LOGI("DiscoveryCompleted signal received");
        env->CallVoidMethod(nat->me, method_onDiscoveryCompleted);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteDeviceDisappeared")) {
        char *c_address;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me, method_onRemoteDeviceDisappeared,
                                env->NewStringUTF(c_address));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteClassUpdated")) {
        char *c_address;
        int n_class;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_UINT32, &n_class,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me, method_onRemoteClassUpdated,
                                env->NewStringUTF(c_address), (jint)n_class);
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteNameUpdated")) {
        char *c_address;
        char *c_name;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_STRING, &c_name,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s, name = %s", c_address, c_name);
            env->CallVoidMethod(nat->me,
                                method_onRemoteNameUpdated,
                                env->NewStringUTF(c_address),
                                env->NewStringUTF(c_name));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteNameFailed")) {
        char *c_address;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me,
                                method_onRemoteNameFailed,
                                env->NewStringUTF(c_address));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteAliasChanged")) {
        char *c_address, *c_alias;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_STRING, &c_alias,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s, alias = %s", c_address, c_alias);
            env->CallVoidMethod(nat->me,
                                method_onRemoteAliasChanged,
                                env->NewStringUTF(c_address),
                                env->NewStringUTF(c_alias));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteAliasCleared")) {
        char *c_address;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me,
                                method_onRemoteAliasCleared,
                                env->NewStringUTF(c_address));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteDeviceConnected")) {
        char *c_address;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me,
                                method_onRemoteDeviceConnected,
                                env->NewStringUTF(c_address));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteDeviceDisconnectRequested")) {
        char *c_address;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me,
                                method_onRemoteDeviceDisconnectRequested,
                                env->NewStringUTF(c_address));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "RemoteDeviceDisconnected")) {
        char *c_address;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me,
                                method_onRemoteDeviceDisconnected,
                                env->NewStringUTF(c_address));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "BondingCreated")) {
        char *c_address;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me,
                                method_onBondingCreated,
                                env->NewStringUTF(c_address));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "BondingRemoved")) {
        char *c_address;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_address,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... address = %s", c_address);
            env->CallVoidMethod(nat->me,
                                method_onBondingRemoved,
                                env->NewStringUTF(c_address));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "ModeChanged")) {
        char *c_mode;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_mode,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... mode = %s", c_mode);
            env->CallVoidMethod(nat->me,
                                method_onModeChanged,
                                env->NewStringUTF(c_mode));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    } else if (dbus_message_is_signal(msg,
                                      "org.bluez.Adapter",
                                      "NameChanged")) {
        char *c_name;
        if (dbus_message_get_args(msg, &err,
                                  DBUS_TYPE_STRING, &c_name,
                                  DBUS_TYPE_INVALID)) {
            LOGV("... name = %s", c_name);
            env->CallVoidMethod(nat->me,
                                method_onNameChanged,
                                env->NewStringUTF(c_name));
        } else LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        return DBUS_HANDLER_RESULT_HANDLED;
    }

    return a2dp_event_filter(msg, env);
}

// Called by dbus during WaitForAndDispatchEventNative()
static DBusHandlerResult passkey_agent_event_filter(DBusConnection *conn,
                                                    DBusMessage *msg,
                                                    void *data) {
    native_data_t *nat = event_loop_nat;
    JNIEnv *env;


    env = nat->env;
    if (dbus_message_get_type(msg) != DBUS_MESSAGE_TYPE_METHOD_CALL) {
        LOGV("%s: not interested (not a method call).", __FUNCTION__);
        return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    }
    LOGV("%s: Received method %s:%s", __FUNCTION__,
         dbus_message_get_interface(msg), dbus_message_get_member(msg));

    if (dbus_message_is_method_call(msg,
            "org.bluez.PasskeyAgent", "Request")) {

        const char *adapter;
        const char *address;
        if (!dbus_message_get_args(msg, NULL,
                                   DBUS_TYPE_STRING, &adapter,
                                   DBUS_TYPE_STRING, &address,
                                   DBUS_TYPE_INVALID)) {
            LOGE("%s: Invalid arguments for Request() method", __FUNCTION__);
            return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
        }

        LOGV("... address = %s", address);

        dbus_message_ref(msg);  // increment refcount because we pass to java

        env->CallVoidMethod(nat->me, method_onPasskeyAgentRequest,
                            env->NewStringUTF(address), (int)msg);

        return DBUS_HANDLER_RESULT_HANDLED;

    } else if (dbus_message_is_method_call(msg,
            "org.bluez.PasskeyAgent", "Cancel")) {

        const char *adapter;
        const char *address;
        if (!dbus_message_get_args(msg, NULL,
                                   DBUS_TYPE_STRING, &adapter,
                                   DBUS_TYPE_STRING, &address,
                                   DBUS_TYPE_INVALID)) {
            LOGE("%s: Invalid arguments for Cancel() method", __FUNCTION__);
            return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
        }

        LOGV("... address = %s", address);

        env->CallVoidMethod(nat->me, method_onPasskeyAgentCancel,
                            env->NewStringUTF(address));

        return DBUS_HANDLER_RESULT_HANDLED;

    } else if (dbus_message_is_method_call(msg,
            "org.bluez.PasskeyAgent", "Release")) {
        LOGE("We are no longer the passkey agent!");
    } else {
        LOGV("... ignored");
    }

    return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
}
#endif

static jboolean waitForAndDispatchEventNative(JNIEnv *env, jobject object,
                                               jint timeout_ms) {
#ifdef HAVE_BLUETOOTH
    //LOGV("%s: %8d (pid %d tid %d)",__FUNCTION__, time(NULL), getpid(), gettid()); // too chatty
    native_data_t *nat = get_native_data(env, object);
    if (nat != NULL && nat->conn != NULL) {
        jboolean ret;
        nat->me = object;
        nat->env = env;
        event_loop_nat = nat;
        ret = dbus_connection_read_write_dispatch_greedy(nat->conn,
                                                         timeout_ms) == TRUE ?
              JNI_TRUE : JNI_FALSE;
        event_loop_nat = NULL;
        nat->me = NULL;
        nat->env = NULL;
        return ret;
    }
#endif
    return JNI_FALSE;
}

#ifdef HAVE_BLUETOOTH
//TODO: Unify result codes in a header
#define BOND_RESULT_ERROR -1000
#define BOND_RESULT_SUCCESS 0
#define BOND_RESULT_AUTH_FAILED 1
#define BOND_RESULT_AUTH_REJECTED 2
#define BOND_RESULT_REMOTE_DEVICE_DOWN 3
void onCreateBondingResult(DBusMessage *msg, void *user) {
    LOGV(__FUNCTION__);

    const char *address = (const char *)user;
    DBusError err;
    dbus_error_init(&err);
    JNIEnv *env = event_loop_nat->env;

    LOGV("... address = %s", address);

    jint result = BOND_RESULT_SUCCESS;
    if (dbus_set_error_from_message(&err, msg)) {
        if (!strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.AuthenticationFailed")) {
            // Pins did not match, or remote device did not respond to pin
            // request in time
            LOGV("... error = %s (%s)\n", err.name, err.message);
            result = BOND_RESULT_AUTH_FAILED;
        } else if (!strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.AuthenticationRejected")) {
            // We rejected pairing, or the remote side rejected pairing. This
            // happens if either side presses 'cancel' at the pairing dialog.
            LOGV("... error = %s (%s)\n", err.name, err.message);
            result = BOND_RESULT_AUTH_REJECTED;
        } else if (!strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".ConnectionAttemptFailed")) {
            // Other device is not responding at all
            LOGV("... error = %s (%s)\n", err.name, err.message);
            result = BOND_RESULT_REMOTE_DEVICE_DOWN;
        } else {
            LOGE("%s: D-Bus error: %s (%s)\n", __FUNCTION__, err.name, err.message);
            result = BOND_RESULT_ERROR;
        }
        dbus_error_free(&err);
    }

    env->CallVoidMethod(event_loop_nat->me,
                        method_onCreateBondingResult,
                        env->NewStringUTF(address),
                        result);
    free(user);
}

void onGetRemoteServiceChannelResult(DBusMessage *msg, void *user) {
    LOGV(__FUNCTION__);

    const char *address = (const char *) user;
    DBusError err;
    dbus_error_init(&err);
    JNIEnv *env = event_loop_nat->env;
    jint channel = -2;

    LOGV("... address = %s", address);

    if (dbus_set_error_from_message(&err, msg) ||
        !dbus_message_get_args(msg, &err,
                               DBUS_TYPE_INT32, &channel,
                               DBUS_TYPE_INVALID)) {
        /* if (!strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.AuthenticationFailed")) */
        LOGE("%s: D-Bus error: %s (%s)\n", __FUNCTION__, err.name, err.message);
        dbus_error_free(&err);
    }

done:
    env->CallVoidMethod(event_loop_nat->me,
                        method_onGetRemoteServiceChannelResult,
                        env->NewStringUTF(address),
                        channel);
    free(user);
}
#endif

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"classInitNative", "()V", (void *)classInitNative},
    {"initializeNativeDataNative", "()V", (void *)initializeNativeDataNative},
    {"cleanupNativeDataNative", "()V", (void *)cleanupNativeDataNative},
    {"setUpEventLoopNative", "()Z", (void *)setUpEventLoopNative},
    {"tearDownEventLoopNative", "()V", (void *)tearDownEventLoopNative},
    {"waitForAndDispatchEventNative", "(I)Z", (void *)waitForAndDispatchEventNative}
};

int register_android_server_BluetoothEventLoop(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
            "android/server/BluetoothEventLoop", sMethods, NELEM(sMethods));
}

} /* namespace android */
