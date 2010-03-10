/*
** Copyright 2006, The Android Open Source Project
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

#define DBUS_ADAPTER_IFACE BLUEZ_DBUS_BASE_IFC ".Adapter"
#define DBUS_DEVICE_IFACE BLUEZ_DBUS_BASE_IFC ".Device"
#define LOG_TAG "BluetoothService.cpp"

#include "android_bluetooth_common.h"
#include "android_runtime/AndroidRuntime.h"
#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>

#include <sys/socket.h>
#include <sys/ioctl.h>
#include <fcntl.h>

#ifdef HAVE_BLUETOOTH
#include <dbus/dbus.h>
#include <bluedroid/bluetooth.h>
#endif

#include <cutils/properties.h>

namespace android {

#define BLUETOOTH_CLASS_ERROR 0xFF000000
#define PROPERTIES_NREFS 10

#ifdef HAVE_BLUETOOTH
// We initialize these variables when we load class
// android.server.BluetoothService
static jfieldID field_mNativeData;
static jfieldID field_mEventLoop;

typedef struct {
    JNIEnv *env;
    DBusConnection *conn;
    const char *adapter;  // dbus object name of the local adapter
} native_data_t;

extern event_loop_native_data_t *get_EventLoop_native_data(JNIEnv *,
                                                           jobject);
extern DBusHandlerResult agent_event_filter(DBusConnection *conn,
                                            DBusMessage *msg,
                                            void *data);
void onCreatePairedDeviceResult(DBusMessage *msg, void *user, void *nat);
void onDiscoverServicesResult(DBusMessage *msg, void *user, void *nat);
void onCreateDeviceResult(DBusMessage *msg, void *user, void *nat);


/** Get native data stored in the opaque (Java code maintained) pointer mNativeData
 *  Perform quick sanity check, if there are any problems return NULL
 */
static inline native_data_t * get_native_data(JNIEnv *env, jobject object) {
    native_data_t *nat =
            (native_data_t *)(env->GetIntField(object, field_mNativeData));
    if (nat == NULL || nat->conn == NULL) {
        LOGE("Uninitialized native data\n");
        return NULL;
    }
    return nat;
}
#endif

static void classInitNative(JNIEnv* env, jclass clazz) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    field_mNativeData = get_field(env, clazz, "mNativeData", "I");
    field_mEventLoop = get_field(env, clazz, "mEventLoop",
            "Landroid/server/BluetoothEventLoop;");
#endif
}

/* Returns true on success (even if adapter is present but disabled).
 * Return false if dbus is down, or another serious error (out of memory)
*/
static bool initializeNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = (native_data_t *)calloc(1, sizeof(native_data_t));
    if (NULL == nat) {
        LOGE("%s: out of memory!", __FUNCTION__);
        return false;
    }
    nat->env = env;

    env->SetIntField(object, field_mNativeData, (jint)nat);
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

static const char *get_adapter_path(JNIEnv* env, jobject object) {
#ifdef HAVE_BLUETOOTH
    event_loop_native_data_t *event_nat =
        get_EventLoop_native_data(env, env->GetObjectField(object,
                                                           field_mEventLoop));
    if (event_nat == NULL)
        return NULL;
    return event_nat->adapter;
#else
    return NULL;
#endif
}

// This function is called when the adapter is enabled.
static jboolean setupNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat =
        (native_data_t *)env->GetIntField(object, field_mNativeData);
    event_loop_native_data_t *event_nat =
        get_EventLoop_native_data(env, env->GetObjectField(object,
                                                           field_mEventLoop));
    // Register agent for remote devices.
    const char *device_agent_path = "/android/bluetooth/remote_device_agent";
    static const DBusObjectPathVTable agent_vtable = {
                 NULL, agent_event_filter, NULL, NULL, NULL, NULL };

    if (!dbus_connection_register_object_path(nat->conn, device_agent_path,
                                              &agent_vtable, event_nat)) {
        LOGE("%s: Can't register object path %s for remote device agent!",
                               __FUNCTION__, device_agent_path);
        return JNI_FALSE;
    }
#endif /*HAVE_BLUETOOTH*/
    return JNI_TRUE;
}

static jboolean tearDownNativeDataNative(JNIEnv *env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat =
               (native_data_t *)env->GetIntField(object, field_mNativeData);
    if (nat != NULL) {
        const char *device_agent_path =
            "/android/bluetooth/remote_device_agent";
        dbus_connection_unregister_object_path (nat->conn, device_agent_path);
    }
#endif /*HAVE_BLUETOOTH*/
    return JNI_TRUE;
}

static void cleanupNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat =
        (native_data_t *)env->GetIntField(object, field_mNativeData);
    if (nat) {
        free(nat);
        nat = NULL;
    }
#endif
}

static jstring getAdapterPathNative(JNIEnv *env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        return (env->NewStringUTF(get_adapter_path(env, object)));
    }
#endif
    return NULL;
}


static jboolean startDiscoveryNative(JNIEnv *env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    DBusMessage *msg = NULL;
    DBusMessage *reply = NULL;
    DBusError err;
    const char *name;
    jboolean ret = JNI_FALSE;

    native_data_t *nat = get_native_data(env, object);
    if (nat == NULL) {
        goto done;
    }

    dbus_error_init(&err);

    /* Compose the command */
    msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC,
                                       get_adapter_path(env, object),
                                       DBUS_ADAPTER_IFACE, "StartDiscovery");

    if (msg == NULL) {
        if (dbus_error_is_set(&err)) {
            LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        }
        goto done;
    }

    /* Send the command. */
    reply = dbus_connection_send_with_reply_and_block(nat->conn, msg, -1, &err);
    if (dbus_error_is_set(&err)) {
         LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
         ret = JNI_FALSE;
         goto done;
    }

    ret = JNI_TRUE;
done:
    if (reply) dbus_message_unref(reply);
    if (msg) dbus_message_unref(msg);
    return ret;
#else
    return JNI_FALSE;
#endif
}

static void stopDiscoveryNative(JNIEnv *env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    DBusMessage *msg = NULL;
    DBusMessage *reply = NULL;
    DBusError err;
    const char *name;
    jstring ret;
    native_data_t *nat;

    dbus_error_init(&err);

    nat = get_native_data(env, object);
    if (nat == NULL) {
        goto done;
    }

    /* Compose the command */
    msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC,
                                       get_adapter_path(env, object),
                                       DBUS_ADAPTER_IFACE, "StopDiscovery");
    if (msg == NULL) {
        if (dbus_error_is_set(&err))
            LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        goto done;
    }

    /* Send the command. */
    reply = dbus_connection_send_with_reply_and_block(nat->conn, msg, -1, &err);
    if (dbus_error_is_set(&err)) {
        if(strncmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.NotAuthorized",
                   strlen(BLUEZ_DBUS_BASE_IFC ".Error.NotAuthorized")) == 0) {
            // hcid sends this if there is no active discovery to cancel
            LOGV("%s: There was no active discovery to cancel", __FUNCTION__);
            dbus_error_free(&err);
        } else {
            LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, msg);
        }
    }

done:
    if (msg) dbus_message_unref(msg);
    if (reply) dbus_message_unref(reply);
#endif
}

static jboolean createPairedDeviceNative(JNIEnv *env, jobject object,
                                         jstring address, jint timeout_ms) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    jobject eventLoop = env->GetObjectField(object, field_mEventLoop);
    struct event_loop_native_data_t *eventLoopNat =
            get_EventLoop_native_data(env, eventLoop);

    if (nat && eventLoopNat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        LOGV("... address = %s", c_address);
        char *context_address = (char *)calloc(BTADDR_SIZE, sizeof(char));
        const char *capabilities = "DisplayYesNo";
        const char *agent_path = "/android/bluetooth/remote_device_agent";

        strlcpy(context_address, c_address, BTADDR_SIZE);  // for callback
        bool ret = dbus_func_args_async(env, nat->conn, (int)timeout_ms,
                                        onCreatePairedDeviceResult, // callback
                                        context_address,
                                        eventLoopNat,
                                        get_adapter_path(env, object),
                                        DBUS_ADAPTER_IFACE,
                                        "CreatePairedDevice",
                                        DBUS_TYPE_STRING, &c_address,
                                        DBUS_TYPE_OBJECT_PATH, &agent_path,
                                        DBUS_TYPE_STRING, &capabilities,
                                        DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        return ret ? JNI_TRUE : JNI_FALSE;

    }
#endif
    return JNI_FALSE;
}

static jint getDeviceServiceChannelNative(JNIEnv *env, jobject object,
                                          jstring path,
                                          jstring pattern, jint attr_id) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    jobject eventLoop = env->GetObjectField(object, field_mEventLoop);
    struct event_loop_native_data_t *eventLoopNat =
            get_EventLoop_native_data(env, eventLoop);
    if (nat && eventLoopNat) {
        const char *c_pattern = env->GetStringUTFChars(pattern, NULL);
        const char *c_path = env->GetStringUTFChars(path, NULL);
        LOGV("... pattern = %s", c_pattern);
        LOGV("... attr_id = %#X", attr_id);
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, c_path,
                           DBUS_DEVICE_IFACE, "GetServiceAttributeValue",
                           DBUS_TYPE_STRING, &c_pattern,
                           DBUS_TYPE_UINT16, &attr_id,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(pattern, c_pattern);
        env->ReleaseStringUTFChars(path, c_path);
        return reply ? dbus_returns_int32(env, reply) : -1;
    }
#endif
    return -1;
}

static jboolean cancelDeviceCreationNative(JNIEnv *env, jobject object,
                                           jstring address) {
    LOGV(__FUNCTION__);
    jboolean result = JNI_FALSE;
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        DBusError err;
        dbus_error_init(&err);
        LOGV("... address = %s", c_address);
        DBusMessage *reply =
            dbus_func_args_timeout(env, nat->conn, -1,
                                   get_adapter_path(env, object),
                                   DBUS_ADAPTER_IFACE, "CancelDeviceCreation",
                                   DBUS_TYPE_STRING, &c_address,
                                   DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        if (!reply) {
            if (dbus_error_is_set(&err)) {
                LOG_AND_FREE_DBUS_ERROR(&err);
            } else
                LOGE("DBus reply is NULL in function %s", __FUNCTION__);
            return JNI_FALSE;
        } else {
            result = JNI_TRUE;
        }
        dbus_message_unref(reply);
    }
#endif
    return JNI_FALSE;
}

static jboolean removeDeviceNative(JNIEnv *env, jobject object, jstring object_path) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_object_path = env->GetStringUTFChars(object_path, NULL);
        bool ret = dbus_func_args_async(env, nat->conn, -1,
                                        NULL,
                                        NULL,
                                        NULL,
                                        get_adapter_path(env, object),
                                        DBUS_ADAPTER_IFACE,
                                        "RemoveDevice",
                                        DBUS_TYPE_OBJECT_PATH, &c_object_path,
                                        DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(object_path, c_object_path);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jint enableNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    return bt_enable();
#endif
    return -1;
}

static jint disableNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    return bt_disable();
#endif
    return -1;
}

static jint isEnabledNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    return bt_is_enabled();
#endif
    return -1;
}

static jboolean setPairingConfirmationNative(JNIEnv *env, jobject object,
                                             jstring address, bool confirm,
                                             int nativeData) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *msg = (DBusMessage *)nativeData;
        DBusMessage *reply;
        if (confirm) {
            reply = dbus_message_new_method_return(msg);
        } else {
            reply = dbus_message_new_error(msg,
                "org.bluez.Error.Rejected", "User rejected confirmation");
        }

        if (!reply) {
            LOGE("%s: Cannot create message reply to RequestPasskeyConfirmation or"
                  "RequestPairingConsent to D-Bus\n", __FUNCTION__);
            dbus_message_unref(msg);
            return JNI_FALSE;
        }

        dbus_connection_send(nat->conn, reply, NULL);
        dbus_message_unref(msg);
        dbus_message_unref(reply);
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean setPasskeyNative(JNIEnv *env, jobject object, jstring address,
                         int passkey, int nativeData) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *msg = (DBusMessage *)nativeData;
        DBusMessage *reply = dbus_message_new_method_return(msg);
        if (!reply) {
            LOGE("%s: Cannot create message reply to return Passkey code to "
                 "D-Bus\n", __FUNCTION__);
            dbus_message_unref(msg);
            return JNI_FALSE;
        }

        dbus_message_append_args(reply, DBUS_TYPE_UINT32, (uint32_t *)&passkey,
                                 DBUS_TYPE_INVALID);

        dbus_connection_send(nat->conn, reply, NULL);
        dbus_message_unref(msg);
        dbus_message_unref(reply);
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean setPinNative(JNIEnv *env, jobject object, jstring address,
                         jstring pin, int nativeData) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *msg = (DBusMessage *)nativeData;
        DBusMessage *reply = dbus_message_new_method_return(msg);
        if (!reply) {
            LOGE("%s: Cannot create message reply to return PIN code to "
                 "D-Bus\n", __FUNCTION__);
            dbus_message_unref(msg);
            return JNI_FALSE;
        }

        const char *c_pin = env->GetStringUTFChars(pin, NULL);

        dbus_message_append_args(reply, DBUS_TYPE_STRING, &c_pin,
                                 DBUS_TYPE_INVALID);

        dbus_connection_send(nat->conn, reply, NULL);
        dbus_message_unref(msg);
        dbus_message_unref(reply);
        env->ReleaseStringUTFChars(pin, c_pin);
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean cancelPairingUserInputNative(JNIEnv *env, jobject object,
                                            jstring address, int nativeData) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *msg = (DBusMessage *)nativeData;
        DBusMessage *reply = dbus_message_new_error(msg,
                "org.bluez.Error.Canceled", "Pairing User Input was canceled");
        if (!reply) {
            LOGE("%s: Cannot create message reply to return cancelUserInput to"
                 "D-BUS\n", __FUNCTION__);
            dbus_message_unref(msg);
            return JNI_FALSE;
        }

        dbus_connection_send(nat->conn, reply, NULL);
        dbus_message_unref(msg);
        dbus_message_unref(reply);
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jobjectArray getDevicePropertiesNative(JNIEnv *env, jobject object,
                                                    jstring path)
{
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *msg, *reply;
        DBusError err;
        dbus_error_init(&err);

        const char *c_path = env->GetStringUTFChars(path, NULL);
        reply = dbus_func_args_timeout(env,
                                   nat->conn, -1, c_path,
                                   DBUS_DEVICE_IFACE, "GetProperties",
                                   DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);

        if (!reply) {
            if (dbus_error_is_set(&err)) {
                LOG_AND_FREE_DBUS_ERROR(&err);
            } else
                LOGE("DBus reply is NULL in function %s", __FUNCTION__);
            return NULL;
        }
        env->PushLocalFrame(PROPERTIES_NREFS);

        DBusMessageIter iter;
        jobjectArray str_array = NULL;
        if (dbus_message_iter_init(reply, &iter))
           str_array =  parse_remote_device_properties(env, &iter);
        dbus_message_unref(reply);

        env->PopLocalFrame(NULL);

        return str_array;
    }
#endif
    return NULL;
}

static jobjectArray getAdapterPropertiesNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *msg, *reply;
        DBusError err;
        dbus_error_init(&err);

        reply = dbus_func_args_timeout(env,
                                   nat->conn, -1, get_adapter_path(env, object),
                                   DBUS_ADAPTER_IFACE, "GetProperties",
                                   DBUS_TYPE_INVALID);
        if (!reply) {
            if (dbus_error_is_set(&err)) {
                LOG_AND_FREE_DBUS_ERROR(&err);
            } else
                LOGE("DBus reply is NULL in function %s", __FUNCTION__);
            return NULL;
        }
        env->PushLocalFrame(PROPERTIES_NREFS);

        DBusMessageIter iter;
        jobjectArray str_array = NULL;
        if (dbus_message_iter_init(reply, &iter))
            str_array = parse_adapter_properties(env, &iter);
        dbus_message_unref(reply);

        env->PopLocalFrame(NULL);
        return str_array;
    }
#endif
    return NULL;
}

static jboolean setAdapterPropertyNative(JNIEnv *env, jobject object, jstring key,
                                         void *value, jint type) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply, *msg;
        DBusMessageIter iter;
        DBusError err;
        const char *c_key = env->GetStringUTFChars(key, NULL);
        dbus_error_init(&err);

        msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC,
                                           get_adapter_path(env, object),
                                           DBUS_ADAPTER_IFACE, "SetProperty");
        if (!msg) {
            LOGE("%s: Can't allocate new method call for GetProperties!",
                  __FUNCTION__);
            env->ReleaseStringUTFChars(key, c_key);
            return JNI_FALSE;
        }

        dbus_message_append_args(msg, DBUS_TYPE_STRING, &c_key, DBUS_TYPE_INVALID);
        dbus_message_iter_init_append(msg, &iter);
        append_variant(&iter, type, value);

        reply = dbus_connection_send_with_reply_and_block(nat->conn, msg, -1, &err);
        dbus_message_unref(msg);

        env->ReleaseStringUTFChars(key, c_key);

        if (!reply) {
            if (dbus_error_is_set(&err)) {
                LOG_AND_FREE_DBUS_ERROR(&err);
            } else
                LOGE("DBus reply is NULL in function %s", __FUNCTION__);
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean setAdapterPropertyStringNative(JNIEnv *env, jobject object, jstring key,
                                               jstring value) {
#ifdef HAVE_BLUETOOTH
    const char *c_value = env->GetStringUTFChars(value, NULL);
    jboolean ret =  setAdapterPropertyNative(env, object, key, (void *)&c_value, DBUS_TYPE_STRING);
    env->ReleaseStringUTFChars(value, (char *)c_value);
    return ret;
#else
    return JNI_FALSE;
#endif
}

static jboolean setAdapterPropertyIntegerNative(JNIEnv *env, jobject object, jstring key,
                                               jint value) {
#ifdef HAVE_BLUETOOTH
    return setAdapterPropertyNative(env, object, key, (void *)&value, DBUS_TYPE_UINT32);
#else
    return JNI_FALSE;
#endif
}

static jboolean setAdapterPropertyBooleanNative(JNIEnv *env, jobject object, jstring key,
                                               jint value) {
#ifdef HAVE_BLUETOOTH
    return setAdapterPropertyNative(env, object, key, (void *)&value, DBUS_TYPE_BOOLEAN);
#else
    return JNI_FALSE;
#endif
}

static jboolean setDevicePropertyNative(JNIEnv *env, jobject object, jstring path,
                                               jstring key, void *value, jint type) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply, *msg;
        DBusMessageIter iter;
        DBusError err;

        const char *c_key = env->GetStringUTFChars(key, NULL);
        const char *c_path = env->GetStringUTFChars(path, NULL);

        dbus_error_init(&err);
        msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC,
                                          c_path, DBUS_DEVICE_IFACE, "SetProperty");
        if (!msg) {
            LOGE("%s: Can't allocate new method call for device SetProperty!", __FUNCTION__);
            env->ReleaseStringUTFChars(key, c_key);
            env->ReleaseStringUTFChars(path, c_path);
            return JNI_FALSE;
        }

        dbus_message_append_args(msg, DBUS_TYPE_STRING, &c_key, DBUS_TYPE_INVALID);
        dbus_message_iter_init_append(msg, &iter);
        append_variant(&iter, type, value);

        reply = dbus_connection_send_with_reply_and_block(nat->conn, msg, -1, &err);
        dbus_message_unref(msg);

        env->ReleaseStringUTFChars(key, c_key);
        env->ReleaseStringUTFChars(path, c_path);
        if (!reply) {
            if (dbus_error_is_set(&err)) {
                LOG_AND_FREE_DBUS_ERROR(&err);
            } else
            LOGE("DBus reply is NULL in function %s", __FUNCTION__);
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean setDevicePropertyBooleanNative(JNIEnv *env, jobject object,
                                                     jstring path, jstring key, jint value) {
#ifdef HAVE_BLUETOOTH
    return setDevicePropertyNative(env, object, path, key,
                                        (void *)&value, DBUS_TYPE_BOOLEAN);
#else
    return JNI_FALSE;
#endif
}


static jboolean createDeviceNative(JNIEnv *env, jobject object,
                                                jstring address) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    jobject eventLoop = env->GetObjectField(object, field_mEventLoop);
    struct event_loop_native_data_t *eventLoopNat =
            get_EventLoop_native_data(env, eventLoop);

    if (nat && eventLoopNat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        LOGV("... address = %s", c_address);
        char *context_address = (char *)calloc(BTADDR_SIZE, sizeof(char));
        strlcpy(context_address, c_address, BTADDR_SIZE);  // for callback

        bool ret = dbus_func_args_async(env, nat->conn, -1,
                                        onCreateDeviceResult,
                                        context_address,
                                        eventLoopNat,
                                        get_adapter_path(env, object),
                                        DBUS_ADAPTER_IFACE,
                                        "CreateDevice",
                                        DBUS_TYPE_STRING, &c_address,
                                        DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean discoverServicesNative(JNIEnv *env, jobject object,
                                               jstring path, jstring pattern) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    jobject eventLoop = env->GetObjectField(object, field_mEventLoop);
    struct event_loop_native_data_t *eventLoopNat =
            get_EventLoop_native_data(env, eventLoop);

    if (nat && eventLoopNat) {
        const char *c_path = env->GetStringUTFChars(path, NULL);
        const char *c_pattern = env->GetStringUTFChars(pattern, NULL);
        int len = env->GetStringLength(path) + 1;
        char *context_path = (char *)calloc(len, sizeof(char));
        strlcpy(context_path, c_path, len);  // for callback

        LOGV("... Object Path = %s", c_path);
        LOGV("... Pattern = %s, strlen = %d", c_pattern, strlen(c_pattern));

        bool ret = dbus_func_args_async(env, nat->conn, -1,
                                        onDiscoverServicesResult,
                                        context_path,
                                        eventLoopNat,
                                        c_path,
                                        DBUS_DEVICE_IFACE,
                                        "DiscoverServices",
                                        DBUS_TYPE_STRING, &c_pattern,
                                        DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(path, c_path);
        env->ReleaseStringUTFChars(pattern, c_pattern);
        return ret ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jint addRfcommServiceRecordNative(JNIEnv *env, jobject object,
        jstring name, jlong uuidMsb, jlong uuidLsb, jshort channel) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_name = env->GetStringUTFChars(name, NULL);
        LOGV("... name = %s", c_name);
        LOGV("... uuid1 = %llX", uuidMsb);
        LOGV("... uuid2 = %llX", uuidLsb);
        LOGV("... channel = %d", channel);
        DBusMessage *reply = dbus_func_args(env, nat->conn,
                           get_adapter_path(env, object),
                           DBUS_ADAPTER_IFACE, "AddRfcommServiceRecord",
                           DBUS_TYPE_STRING, &c_name,
                           DBUS_TYPE_UINT64, &uuidMsb,
                           DBUS_TYPE_UINT64, &uuidLsb,
                           DBUS_TYPE_UINT16, &channel,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(name, c_name);
        return reply ? dbus_returns_uint32(env, reply) : -1;
    }
#endif
    return -1;
}

static jboolean removeServiceRecordNative(JNIEnv *env, jobject object, jint handle) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        LOGV("... handle = %X", handle);
        DBusMessage *reply = dbus_func_args(env, nat->conn,
                           get_adapter_path(env, object),
                           DBUS_ADAPTER_IFACE, "RemoveServiceRecord",
                           DBUS_TYPE_UINT32, &handle,
                           DBUS_TYPE_INVALID);
        return reply ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean setLinkTimeoutNative(JNIEnv *env, jobject object, jstring object_path,
                                     jint num_slots) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_object_path = env->GetStringUTFChars(object_path, NULL);
        DBusMessage *reply = dbus_func_args(env, nat->conn,
                           get_adapter_path(env, object),
                           DBUS_ADAPTER_IFACE, "SetLinkTimeout",
                           DBUS_TYPE_OBJECT_PATH, &c_object_path,
                           DBUS_TYPE_UINT32, &num_slots,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(object_path, c_object_path);
        return reply ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"classInitNative", "()V", (void*)classInitNative},
    {"initializeNativeDataNative", "()V", (void *)initializeNativeDataNative},
    {"setupNativeDataNative", "()Z", (void *)setupNativeDataNative},
    {"tearDownNativeDataNative", "()Z", (void *)tearDownNativeDataNative},
    {"cleanupNativeDataNative", "()V", (void *)cleanupNativeDataNative},
    {"getAdapterPathNative", "()Ljava/lang/String;", (void*)getAdapterPathNative},

    {"isEnabledNative", "()I", (void *)isEnabledNative},
    {"enableNative", "()I", (void *)enableNative},
    {"disableNative", "()I", (void *)disableNative},

    {"getAdapterPropertiesNative", "()[Ljava/lang/Object;", (void *)getAdapterPropertiesNative},
    {"getDevicePropertiesNative", "(Ljava/lang/String;)[Ljava/lang/Object;",
      (void *)getDevicePropertiesNative},
    {"setAdapterPropertyStringNative", "(Ljava/lang/String;Ljava/lang/String;)Z",
      (void *)setAdapterPropertyStringNative},
    {"setAdapterPropertyBooleanNative", "(Ljava/lang/String;I)Z",
      (void *)setAdapterPropertyBooleanNative},
    {"setAdapterPropertyIntegerNative", "(Ljava/lang/String;I)Z",
      (void *)setAdapterPropertyIntegerNative},

    {"startDiscoveryNative", "()Z", (void*)startDiscoveryNative},
    {"stopDiscoveryNative", "()Z", (void *)stopDiscoveryNative},

    {"createPairedDeviceNative", "(Ljava/lang/String;I)Z", (void *)createPairedDeviceNative},
    {"cancelDeviceCreationNative", "(Ljava/lang/String;)Z", (void *)cancelDeviceCreationNative},
    {"removeDeviceNative", "(Ljava/lang/String;)Z", (void *)removeDeviceNative},
    {"getDeviceServiceChannelNative", "(Ljava/lang/String;Ljava/lang/String;I)I",
      (void *)getDeviceServiceChannelNative},

    {"setPairingConfirmationNative", "(Ljava/lang/String;ZI)Z",
            (void *)setPairingConfirmationNative},
    {"setPasskeyNative", "(Ljava/lang/String;II)Z", (void *)setPasskeyNative},
    {"setPinNative", "(Ljava/lang/String;Ljava/lang/String;I)Z", (void *)setPinNative},
    {"cancelPairingUserInputNative", "(Ljava/lang/String;I)Z",
            (void *)cancelPairingUserInputNative},
    {"setDevicePropertyBooleanNative", "(Ljava/lang/String;Ljava/lang/String;I)Z",
            (void *)setDevicePropertyBooleanNative},
    {"createDeviceNative", "(Ljava/lang/String;)Z", (void *)createDeviceNative},
    {"discoverServicesNative", "(Ljava/lang/String;Ljava/lang/String;)Z", (void *)discoverServicesNative},
    {"addRfcommServiceRecordNative", "(Ljava/lang/String;JJS)I", (void *)addRfcommServiceRecordNative},
    {"removeServiceRecordNative", "(I)Z", (void *)removeServiceRecordNative},
    {"setLinkTimeoutNative", "(Ljava/lang/String;I)Z", (void *)setLinkTimeoutNative},
};

int register_android_server_BluetoothService(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/server/BluetoothService", sMethods, NELEM(sMethods));
}

} /* namespace android */
