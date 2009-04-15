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

#define DBUS_CLASS_NAME BLUEZ_DBUS_BASE_IFC ".Adapter"
#define LOG_TAG "BluetoothDeviceService.cpp"

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

#ifdef HAVE_BLUETOOTH
// We initialize these variables when we load class
// android.server.BluetoothDeviceService
static jfieldID field_mNativeData;

typedef struct {
    JNIEnv *env;
    DBusConnection *conn;
    const char *adapter;  // dbus object name of the local adapter
} native_data_t;

void onCreateBondingResult(DBusMessage *msg, void *user);
void onGetRemoteServiceChannelResult(DBusMessage *msg, void *user);

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

    nat->adapter = BLUEZ_ADAPTER_OBJECT_NAME;
#endif  /*HAVE_BLUETOOTH*/
    return true;
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

static jstring getNameNative(JNIEnv *env, jobject object){
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply = dbus_func_args(env, nat->conn, nat->adapter,
                                            DBUS_CLASS_NAME, "GetName",
                                            DBUS_TYPE_INVALID);
        return reply ? dbus_returns_string(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jstring getAdapterPathNative(JNIEnv *env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        return (env->NewStringUTF(nat->adapter));
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
    msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC, nat->adapter,
                                       DBUS_CLASS_NAME, "DiscoverDevices");

    if (msg == NULL) {
        LOGE("%s: Could not allocate D-Bus message object!", __FUNCTION__);
        goto done;
    }

    /* Send the command. */
    reply = dbus_connection_send_with_reply_and_block(nat->conn, msg, -1, &err);
    if (dbus_error_is_set(&err)) {
        /* We treat the in-progress error code as success. */
        if(strcmp(err.message, BLUEZ_DBUS_BASE_IFC ".Error.InProgress") == 0) {
            LOGW("%s: D-Bus error: %s, treating as startDiscoveryNative success\n",
                 __FUNCTION__, err.message);
            ret = JNI_TRUE;
            goto done;
        } else {
            LOGE("%s: D-Bus error: %s (%s)\n", __FUNCTION__, err.name, err.message);
            ret = JNI_FALSE;
            goto done;
        }
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

static void cancelDiscoveryNative(JNIEnv *env, jobject object) {
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
    msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC, nat->adapter,
                                       DBUS_CLASS_NAME, "CancelDiscovery");

    if (msg == NULL) {
        LOGE("%s: Could not allocate D-Bus message object!", __FUNCTION__);
        goto done;
    }

    /* Send the command. */
    reply = dbus_connection_send_with_reply_and_block(nat->conn, msg, -1, &err);
    if (dbus_error_is_set(&err)) {
        if(strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.NotAuthorized") == 0) {
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

static jboolean startPeriodicDiscoveryNative(JNIEnv *env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    DBusMessage *msg = NULL;
    DBusMessage *reply = NULL;
    DBusError err;
    jboolean ret = JNI_FALSE;

    native_data_t *nat = get_native_data(env, object);
    if (nat == NULL) {
        goto done;
    }

    dbus_error_init(&err);
    msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC, nat->adapter,
            DBUS_CLASS_NAME, "StartPeriodicDiscovery");
    if (msg == NULL) {
        LOGE("%s: Could not allocate DBUS message object\n", __FUNCTION__);
        goto done;
    }

    /* Send the command. */
    reply = dbus_connection_send_with_reply_and_block(nat->conn, msg, -1, &err);
    if (dbus_error_is_set(&err)) {
        /* We treat the in-progress error code as success. */
        if(strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.InProgress") == 0) {
            LOGW("%s: D-Bus error: %s (%s), treating as "
                 "startPeriodicDiscoveryNative success\n",
                 __FUNCTION__, err.name, err.message);
        } else {
            LOGE("%s: D-Bus error: %s (%s)\n", __FUNCTION__, err.name, err.message);
            ret = JNI_FALSE;
            goto done;
        }
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

static jboolean stopPeriodicDiscoveryNative(JNIEnv *env, jobject object) {
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
    msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC, nat->adapter,
            DBUS_CLASS_NAME, "StopPeriodicDiscovery");
    if (msg == NULL) {
        LOGE("%s: Could not allocate DBUS message object\n", __FUNCTION__);
        goto done;
    }

    /* Send the command. */
    reply = dbus_connection_send_with_reply_and_block(nat->conn, msg, -1, &err);
    if (dbus_error_is_set(&err)) {
        /* We treat the in-progress error code as success. */
        if(strcmp(err.name, BLUEZ_DBUS_BASE_IFC ".Error.InProgress") == 0) {
            LOGW("%s: D-Bus error: %s (%s), treating as "
                 "stopPeriodicDiscoveryNative success\n",
                 __FUNCTION__, err.name, err.message);
        } else {
            LOGE("%s: D-Bus error: %s (%s)\n", __FUNCTION__, err.name, err.message);
            ret = JNI_FALSE;
            goto done;
        }
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

static jboolean isPeriodicDiscoveryNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "IsPeriodicDiscovery",
                           DBUS_TYPE_INVALID);
        return reply ? dbus_returns_boolean(env, reply) : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean setDiscoverableTimeoutNative(JNIEnv *env, jobject object, jint timeout_s) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);

    if (timeout_s < 0) {
        return JNI_FALSE;
    }

    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "SetDiscoverableTimeout",
                           DBUS_TYPE_UINT32, &timeout_s,
                           DBUS_TYPE_INVALID);
        if (reply != NULL) {
            dbus_message_unref(reply);
            return JNI_TRUE;
        }
    }
#endif
    return JNI_FALSE;
}

static jint getDiscoverableTimeoutNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "GetDiscoverableTimeout",
                           DBUS_TYPE_INVALID);
        return reply ? dbus_returns_uint32(env, reply) : -1;
    }
#endif
    return -1;
}

static jboolean isConnectedNative(JNIEnv *env, jobject object, jstring address) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "IsConnected",
                           DBUS_TYPE_STRING, &c_address,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        return reply ? dbus_returns_boolean(env, reply) : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static void disconnectRemoteDeviceNative(JNIEnv *env, jobject object, jstring address) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        // Set a timeout of 5 seconds.  Specifying the default timeout is
        // not long enough, as a remote-device disconnect results in
        // signal RemoteDisconnectRequested being sent, followed by a
        // delay of 2 seconds, after which the actual disconnect takes
        // place.
        DBusMessage *reply =
            dbus_func_args_timeout(env, nat->conn, 60000, nat->adapter,
                                   DBUS_CLASS_NAME, "DisconnectRemoteDevice",
                                   DBUS_TYPE_STRING, &c_address,
                                   DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        if (reply) dbus_message_unref(reply);
    }
#endif
}

static jstring getModeNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "GetMode",
                           DBUS_TYPE_INVALID);
        return reply ? dbus_returns_string(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jboolean setModeNative(JNIEnv *env, jobject object, jstring mode) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_mode = env->GetStringUTFChars(mode, NULL);
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "SetMode",
                           DBUS_TYPE_STRING, &c_mode,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(mode, c_mode);
        if (reply) {
            dbus_message_unref(reply);
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

static jboolean createBondingNative(JNIEnv *env, jobject object,
                                    jstring address, jint timeout_ms) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        LOGV("... address = %s", c_address);
        char *context_address = (char *)calloc(BTADDR_SIZE, sizeof(char));
        strlcpy(context_address, c_address, BTADDR_SIZE);  // for callback
        bool ret = dbus_func_args_async(env, nat->conn, (int)timeout_ms,
                                        onCreateBondingResult, // callback
                                        context_address, // user data
                                        nat->adapter,
                                        DBUS_CLASS_NAME, "CreateBonding",
                                        DBUS_TYPE_STRING, &c_address,
                                        DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        return ret ? JNI_TRUE : JNI_FALSE;

    }
#endif
    return JNI_FALSE;
}

static jboolean cancelBondingProcessNative(JNIEnv *env, jobject object,
                                       jstring address) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        LOGV("... address = %s", c_address);
        DBusMessage *reply =
            dbus_func_args_timeout(env, nat->conn, -1, nat->adapter,
                                   DBUS_CLASS_NAME, "CancelBondingProcess",
                                   DBUS_TYPE_STRING, &c_address,
                                   DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        if (reply) {
            dbus_message_unref(reply);
        }
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean removeBondingNative(JNIEnv *env, jobject object, jstring address) {
    LOGV(__FUNCTION__);
    jboolean result = JNI_FALSE;
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        LOGV("... address = %s", c_address);
        DBusError err;
        dbus_error_init(&err);
        DBusMessage *reply =
            dbus_func_args_error(env, nat->conn, &err, nat->adapter,
                                 DBUS_CLASS_NAME, "RemoveBonding",
                                 DBUS_TYPE_STRING, &c_address,
                                 DBUS_TYPE_INVALID);
        if (dbus_error_is_set(&err)) {
            if (dbus_error_has_name(&err,
                    BLUEZ_DBUS_BASE_IFC ".Error.DoesNotExist")) {
                LOGW("%s: Warning: %s (%s)", __FUNCTION__, err.message,
                     c_address);
                result = JNI_TRUE;
            } else {
                LOGE("%s: D-Bus error %s (%s)", __FUNCTION__, err.name,
                        err.message);
            }
        } else {
            result = JNI_TRUE;
        }

        env->ReleaseStringUTFChars(address, c_address);
        dbus_error_free(&err);
        if (reply) dbus_message_unref(reply);
    }
#endif
    return result;
}

static jobjectArray listBondingsNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "ListBondings",
                           DBUS_TYPE_INVALID);
        // return String[]
        return reply ? dbus_returns_array_of_strings(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jobjectArray listConnectionsNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "ListConnections",
                           DBUS_TYPE_INVALID);
        // return String[]
        return reply ? dbus_returns_array_of_strings(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jobjectArray listRemoteDevicesNative(JNIEnv *env, jobject object) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "ListRemoteDevices",
                           DBUS_TYPE_INVALID);
        return reply ? dbus_returns_array_of_strings(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jstring common_Get(JNIEnv *env, jobject object, const char *func) {
    LOGV("%s:%s", __FUNCTION__, func);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusError err;
        dbus_error_init(&err);
        DBusMessage *reply =
            dbus_func_args_error(env, nat->conn, &err, nat->adapter,
                                 DBUS_CLASS_NAME, func,
                                 DBUS_TYPE_INVALID);
        if (reply) {
            return dbus_returns_string(env, reply);
        } else {
            LOG_AND_FREE_DBUS_ERROR(&err);
            return NULL;
        }
    }
#endif
    return NULL;
}

static jstring getAddressNative(JNIEnv *env, jobject obj) {
    return common_Get(env, obj, "GetAddress");
}

static jstring getVersionNative(JNIEnv *env, jobject obj) {
    return common_Get(env, obj, "GetVersion");
}

static jstring getRevisionNative(JNIEnv *env, jobject obj) {
    return common_Get(env, obj, "GetRevision");
}

static jstring getManufacturerNative(JNIEnv *env, jobject obj) {
    return common_Get(env, obj, "GetManufacturer");
}

static jstring getCompanyNative(JNIEnv *env, jobject obj) {
    return common_Get(env, obj, "GetCompany");
}

static jboolean setNameNative(JNIEnv *env, jobject obj, jstring name) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, obj);
    if (nat) {
        const char *c_name = env->GetStringUTFChars(name, NULL);
        DBusMessage *reply = dbus_func_args(env, nat->conn, nat->adapter,
                                            DBUS_CLASS_NAME, "SetName",
                                            DBUS_TYPE_STRING, &c_name,
                                            DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(name, c_name);
        if (reply) {
            dbus_message_unref(reply);
            return JNI_TRUE;
        }
    }
#endif
    return JNI_FALSE;
}

static jstring common_getRemote(JNIEnv *env, jobject object, const char *func,
                                jstring address) {
    LOGV("%s:%s", __FUNCTION__, func);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        DBusError err;
        dbus_error_init(&err);

        LOGV("... address = %s", c_address);

        DBusMessage *reply =
            dbus_func_args_error(env, nat->conn, &err, nat->adapter,
                                 DBUS_CLASS_NAME, func,
                                 DBUS_TYPE_STRING, &c_address,
                                 DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        if (reply) {
            return dbus_returns_string(env, reply);
        } else if (!strcmp(func, "GetRemoteName") &&
                dbus_error_has_name(&err, "org.bluez.Error.RequestDeferred")) {
            // This error occurs if we request name during device discovery,
            // its fine
            LOGV("... %s: %s", func, err.message);
            dbus_error_free(&err);
            return NULL;
        } else {
            LOG_AND_FREE_DBUS_ERROR(&err);
            return NULL;
        }
    }
#endif
    return NULL;
}

static jstring getRemoteVersionNative(JNIEnv *env, jobject obj, jstring address) {
    return common_getRemote(env, obj, "GetRemoteVersion", address);
}

static jstring getRemoteRevisionNative(JNIEnv *env, jobject obj, jstring address) {
    return common_getRemote(env, obj, "GetRemoteRevision", address);
}

static jstring getRemoteManufacturerNative(JNIEnv *env, jobject obj, jstring address) {
    return common_getRemote(env, obj, "GetRemoteManufacturer", address);
}

static jstring getRemoteCompanyNative(JNIEnv *env, jobject obj, jstring address) {
    return common_getRemote(env, obj, "GetRemoteCompany", address);
}

static jstring getRemoteNameNative(JNIEnv *env, jobject obj, jstring address) {
    return common_getRemote(env, obj, "GetRemoteName", address);
}

static jstring lastSeenNative(JNIEnv *env, jobject obj, jstring address) {
    return common_getRemote(env, obj, "LastSeen", address);
}

static jstring lastUsedNative(JNIEnv *env, jobject obj, jstring address) {
    return common_getRemote(env, obj, "LastUsed", address);
}

static jint getRemoteClassNative(JNIEnv *env, jobject object, jstring address) {
    jint result = BLUETOOTH_CLASS_ERROR;
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);

        LOGV("... address = %s", c_address);

        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "GetRemoteClass",
                           DBUS_TYPE_STRING, &c_address,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        if (reply)
        {
            DBusError err;
            dbus_error_init(&err);
            if (!dbus_message_get_args(reply, &err,
                                      DBUS_TYPE_UINT32, &result,
                                      DBUS_TYPE_INVALID)) {
                LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
            }
            dbus_message_unref(reply);
        }
    }
#endif
    return result;
}

static jbyteArray getRemoteFeaturesNative(JNIEnv *env, jobject object,
                                          jstring address) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);

        LOGV("... address = %s", c_address);

        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "GetRemoteFeatures",
                           DBUS_TYPE_STRING, &c_address,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        /* array of DBUS_TYPE_BYTE_AS_STRING */
        return reply ? dbus_returns_array_of_bytes(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jintArray getRemoteServiceHandlesNative(JNIEnv *env, jobject object,
                                               jstring address, jstring match) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        jintArray intArray = NULL;
        const char *c_address = env->GetStringUTFChars(address, NULL);
        const char *c_match = env->GetStringUTFChars(match, NULL);

        LOGV("... address = %s match = %s", c_address, c_match);

        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "GetRemoteServiceHandles",
                           DBUS_TYPE_STRING, &c_address,
                           DBUS_TYPE_STRING, &c_match,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        env->ReleaseStringUTFChars(match, c_match);
        if (reply)
        {
            DBusError err;
            jint *list;
            int i, len;

            dbus_error_init(&err);
            if (dbus_message_get_args (reply, &err,
                                       DBUS_TYPE_ARRAY, DBUS_TYPE_UINT32,
                                       &list, &len,
                                       DBUS_TYPE_INVALID)) {
                if (len) {
                    intArray = env->NewIntArray(len);
                    if (intArray)
                        env->SetIntArrayRegion(intArray, 0, len, list);
                }
            } else {
                LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
            }

            dbus_message_unref(reply);
        }
        return intArray;
    }
#endif
    return NULL;
}

static jbyteArray getRemoteServiceRecordNative(JNIEnv *env, jobject object,
                                                 jstring address, jint handle) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);

        LOGV("... address = %s", c_address);

        DBusMessage *reply =
            dbus_func_args(env, nat->conn, nat->adapter,
                           DBUS_CLASS_NAME, "GetRemoteServiceRecord",
                           DBUS_TYPE_STRING, &c_address,
                           DBUS_TYPE_UINT32, &handle,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
        return reply ? dbus_returns_array_of_bytes(env, reply) : NULL;
    }
#endif
    return NULL;
}

static jboolean getRemoteServiceChannelNative(JNIEnv *env, jobject object,
                                          jstring address, jshort uuid16) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        const char *c_address = env->GetStringUTFChars(address, NULL);
        char *context_address = (char *)calloc(BTADDR_SIZE, sizeof(char));
        strlcpy(context_address, c_address, BTADDR_SIZE);

        LOGV("... address = %s", c_address);
        LOGV("... uuid16 = %#X", uuid16);

        bool ret = dbus_func_args_async(env, nat->conn, 20000,  // ms
                           onGetRemoteServiceChannelResult, context_address,
                           nat->adapter,
                           DBUS_CLASS_NAME, "GetRemoteServiceChannel",
                           DBUS_TYPE_STRING, &c_address,
                           DBUS_TYPE_UINT16, &uuid16,
                           DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(address, c_address);
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

static jboolean cancelPinNative(JNIEnv *env, jobject object, jstring address,
                            int nativeData) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        DBusMessage *msg = (DBusMessage *)nativeData;
        DBusMessage *reply = dbus_message_new_error(msg,
                "org.bluez.Error.Canceled", "PIN Entry was canceled");
        if (!reply) {
            LOGE("%s: Cannot create message reply to return PIN cancel to "
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

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"classInitNative", "()V", (void*)classInitNative},
    {"initializeNativeDataNative", "()V", (void *)initializeNativeDataNative},
    {"cleanupNativeDataNative", "()V", (void *)cleanupNativeDataNative},
    {"getAdapterPathNative", "()Ljava/lang/String;", (void*)getAdapterPathNative},

    {"isEnabledNative", "()I", (void *)isEnabledNative},
    {"enableNative", "()I", (void *)enableNative},
    {"disableNative", "()I", (void *)disableNative},

    {"getAddressNative", "()Ljava/lang/String;", (void *)getAddressNative},
    {"getNameNative", "()Ljava/lang/String;", (void*)getNameNative},
    {"setNameNative", "(Ljava/lang/String;)Z", (void *)setNameNative},
    {"getVersionNative", "()Ljava/lang/String;", (void *)getVersionNative},
    {"getRevisionNative", "()Ljava/lang/String;", (void *)getRevisionNative},
    {"getManufacturerNative", "()Ljava/lang/String;", (void *)getManufacturerNative},
    {"getCompanyNative", "()Ljava/lang/String;", (void *)getCompanyNative},

    {"getModeNative", "()Ljava/lang/String;", (void *)getModeNative},
    {"setModeNative", "(Ljava/lang/String;)Z", (void *)setModeNative},

    {"getDiscoverableTimeoutNative", "()I", (void *)getDiscoverableTimeoutNative},
    {"setDiscoverableTimeoutNative", "(I)Z", (void *)setDiscoverableTimeoutNative},

    {"startDiscoveryNative", "(Z)Z", (void*)startDiscoveryNative},
    {"cancelDiscoveryNative", "()Z", (void *)cancelDiscoveryNative},
    {"startPeriodicDiscoveryNative", "()Z", (void *)startPeriodicDiscoveryNative},
    {"stopPeriodicDiscoveryNative", "()Z", (void *)stopPeriodicDiscoveryNative},
    {"isPeriodicDiscoveryNative", "()Z", (void *)isPeriodicDiscoveryNative},
    {"listRemoteDevicesNative", "()[Ljava/lang/String;", (void *)listRemoteDevicesNative},

    {"listConnectionsNative", "()[Ljava/lang/String;", (void *)listConnectionsNative},
    {"isConnectedNative", "(Ljava/lang/String;)Z", (void *)isConnectedNative},
    {"disconnectRemoteDeviceNative", "(Ljava/lang/String;)Z", (void *)disconnectRemoteDeviceNative},

    {"createBondingNative", "(Ljava/lang/String;I)Z", (void *)createBondingNative},
    {"cancelBondingProcessNative", "(Ljava/lang/String;)Z", (void *)cancelBondingProcessNative},
    {"listBondingsNative", "()[Ljava/lang/String;", (void *)listBondingsNative},
    {"removeBondingNative", "(Ljava/lang/String;)Z", (void *)removeBondingNative},

    {"getRemoteNameNative", "(Ljava/lang/String;)Ljava/lang/String;", (void *)getRemoteNameNative},
    {"getRemoteVersionNative", "(Ljava/lang/String;)Ljava/lang/String;", (void *)getRemoteVersionNative},
    {"getRemoteRevisionNative", "(Ljava/lang/String;)Ljava/lang/String;", (void *)getRemoteRevisionNative},
    {"getRemoteClassNative", "(Ljava/lang/String;)I", (void *)getRemoteClassNative},
    {"getRemoteManufacturerNative", "(Ljava/lang/String;)Ljava/lang/String;", (void *)getRemoteManufacturerNative},
    {"getRemoteCompanyNative", "(Ljava/lang/String;)Ljava/lang/String;", (void *)getRemoteCompanyNative},
    {"getRemoteServiceChannelNative", "(Ljava/lang/String;S)Z", (void *)getRemoteServiceChannelNative},
    {"getRemoteFeaturesNative", "(Ljava/lang/String;)[B", (void *)getRemoteFeaturesNative},
    {"lastSeenNative", "(Ljava/lang/String;)Ljava/lang/String;", (void *)lastSeenNative},
    {"lastUsedNative", "(Ljava/lang/String;)Ljava/lang/String;", (void *)lastUsedNative},
    {"setPinNative", "(Ljava/lang/String;Ljava/lang/String;I)Z", (void *)setPinNative},
    {"cancelPinNative", "(Ljava/lang/String;I)Z", (void *)cancelPinNative},
};

int register_android_server_BluetoothDeviceService(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/server/BluetoothDeviceService", sMethods, NELEM(sMethods));
}

} /* namespace android */
