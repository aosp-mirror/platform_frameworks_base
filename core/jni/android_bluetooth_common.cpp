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

#define LOG_TAG "bluetooth_common.cpp"

#include "android_bluetooth_common.h"
#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <cutils/properties.h>

#ifdef HAVE_BLUETOOTH
#include <dbus/dbus.h>
#endif

namespace android {

#ifdef HAVE_BLUETOOTH
jfieldID get_field(JNIEnv *env, jclass clazz, const char *member,
                   const char *mtype) {
    jfieldID field = env->GetFieldID(clazz, member, mtype);
    if (field == NULL) {
        LOGE("Can't find member %s", member);
    }
    return field;
}

typedef struct {
    void (*user_cb)(DBusMessage *, void *);
    void *user;
    JNIEnv *env;
} dbus_async_call_t;

void dbus_func_args_async_callback(DBusPendingCall *call, void *data) {

    dbus_async_call_t *req = (dbus_async_call_t *)data;
    DBusMessage *msg;

    /* This is guaranteed to be non-NULL, because this function is called only
       when once the remote method invokation returns. */
    msg = dbus_pending_call_steal_reply(call);

    if (msg) {
        if (req->user_cb) {
            // The user may not deref the message object.
            req->user_cb(msg, req->user);
        }
        dbus_message_unref(msg);
    }

    //dbus_message_unref(req->method);
    dbus_pending_call_cancel(call);
    dbus_pending_call_unref(call);
    free(req);
}

dbus_bool_t dbus_func_args_async_valist(JNIEnv *env,
                                        DBusConnection *conn,
                                        int timeout_ms,
                                        void (*user_cb)(DBusMessage *, void *),
                                        void *user,
                                        const char *path,
                                        const char *ifc,
                                        const char *func,
                                        int first_arg_type,
                                        va_list args) {
    DBusMessage *msg = NULL;
    const char *name;
    dbus_async_call_t *pending;
    dbus_bool_t reply = FALSE;

    /* Compose the command */
    msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC, path, ifc, func);

    if (msg == NULL) {
        LOGE("Could not allocate D-Bus message object!");
        goto done;
    }

    /* append arguments */
    if (!dbus_message_append_args_valist(msg, first_arg_type, args)) {
        LOGE("Could not append argument to method call!");
        goto done;
    }

    /* Make the call. */
    pending = (dbus_async_call_t *)malloc(sizeof(dbus_async_call_t));
    if (pending) {
        DBusPendingCall *call;

        pending->env = env;
        pending->user_cb = user_cb;
        pending->user = user;
        //pending->method = msg;

        reply = dbus_connection_send_with_reply(conn, msg,
                                                &call,
                                                timeout_ms);
        if (reply == TRUE) {
            dbus_pending_call_set_notify(call,
                                         dbus_func_args_async_callback,
                                         pending,
                                         NULL);
        }
    }

done:
    if (msg) dbus_message_unref(msg);
    return reply;
}

dbus_bool_t dbus_func_args_async(JNIEnv *env,
                                 DBusConnection *conn,
                                 int timeout_ms,
                                 void (*reply)(DBusMessage *, void *),
                                 void *user,
                                 const char *path,
                                 const char *ifc,
                                 const char *func,
                                 int first_arg_type,
                                 ...) {
    dbus_bool_t ret;
    va_list lst;
    va_start(lst, first_arg_type);
    ret = dbus_func_args_async_valist(env, conn,
                                      timeout_ms,
                                      reply, user,
                                      path, ifc, func,
                                      first_arg_type, lst);
    va_end(lst);
    return ret;
}

// If err is NULL, then any errors will be LOGE'd, and free'd and the reply
// will be NULL.
// If err is not NULL, then it is assumed that dbus_error_init was already
// called, and error's will be returned to the caller without logging. The
// return value is NULL iff an error was set. The client must free the error if
// set.
DBusMessage * dbus_func_args_timeout_valist(JNIEnv *env,
                                            DBusConnection *conn,
                                            int timeout_ms,
                                            DBusError *err,
                                            const char *path,
                                            const char *ifc,
                                            const char *func,
                                            int first_arg_type,
                                            va_list args) {

    DBusMessage *msg = NULL, *reply = NULL;
    const char *name;
    bool return_error = (err != NULL);

    if (!return_error) {
        err = (DBusError*)malloc(sizeof(DBusError));
        dbus_error_init(err);
    }

    /* Compose the command */
    msg = dbus_message_new_method_call(BLUEZ_DBUS_BASE_IFC, path, ifc, func);

    if (msg == NULL) {
        LOGE("Could not allocate D-Bus message object!");
        goto done;
    }

    /* append arguments */
    if (!dbus_message_append_args_valist(msg, first_arg_type, args)) {
        LOGE("Could not append argument to method call!");
        goto done;
    }

    /* Make the call. */
    reply = dbus_connection_send_with_reply_and_block(conn, msg, timeout_ms, err);
    if (!return_error && dbus_error_is_set(err)) {
        LOG_AND_FREE_DBUS_ERROR_WITH_MSG(err, msg);
    }

done:
    if (!return_error) {
        free(err);
    }
    if (msg) dbus_message_unref(msg);
    return reply;
}

DBusMessage * dbus_func_args_timeout(JNIEnv *env,
                                     DBusConnection *conn,
                                     int timeout_ms,
                                     const char *path,
                                     const char *ifc,
                                     const char *func,
                                     int first_arg_type,
                                     ...) {
    DBusMessage *ret;
    va_list lst;
    va_start(lst, first_arg_type);
    ret = dbus_func_args_timeout_valist(env, conn, timeout_ms, NULL,
                                        path, ifc, func,
                                        first_arg_type, lst);
    va_end(lst);
    return ret;
}

DBusMessage * dbus_func_args(JNIEnv *env,
                             DBusConnection *conn,
                             const char *path,
                             const char *ifc,
                             const char *func,
                             int first_arg_type,
                             ...) {
    DBusMessage *ret;
    va_list lst;
    va_start(lst, first_arg_type);
    ret = dbus_func_args_timeout_valist(env, conn, -1, NULL,
                                        path, ifc, func,
                                        first_arg_type, lst);
    va_end(lst);
    return ret;
}

DBusMessage * dbus_func_args_error(JNIEnv *env,
                                   DBusConnection *conn,
                                   DBusError *err,
                                   const char *path,
                                   const char *ifc,
                                   const char *func,
                                   int first_arg_type,
                                   ...) {
    DBusMessage *ret;
    va_list lst;
    va_start(lst, first_arg_type);
    ret = dbus_func_args_timeout_valist(env, conn, -1, err,
                                        path, ifc, func,
                                        first_arg_type, lst);
    va_end(lst);
    return ret;
}

jint dbus_returns_int32(JNIEnv *env, DBusMessage *reply) {

    DBusError err;
    jint ret = -1;

    dbus_error_init(&err);
    if (!dbus_message_get_args(reply, &err,
                               DBUS_TYPE_INT32, &ret,
                               DBUS_TYPE_INVALID)) {
        LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
    }
    dbus_message_unref(reply);
    return ret;
}

jint dbus_returns_uint32(JNIEnv *env, DBusMessage *reply) {

    DBusError err;
    jint ret = -1;

    dbus_error_init(&err);
    if (!dbus_message_get_args(reply, &err,
                               DBUS_TYPE_UINT32, &ret,
                               DBUS_TYPE_INVALID)) {
        LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
    }
    dbus_message_unref(reply);
    return ret;
}

jstring dbus_returns_string(JNIEnv *env, DBusMessage *reply) {

    DBusError err;
    jstring ret = NULL;
    const char *name;

    dbus_error_init(&err);
    if (dbus_message_get_args(reply, &err,
                               DBUS_TYPE_STRING, &name,
                               DBUS_TYPE_INVALID)) {
        ret = env->NewStringUTF(name);
    } else {
        LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
    }
    dbus_message_unref(reply);

    return ret;
}

jboolean dbus_returns_boolean(JNIEnv *env, DBusMessage *reply) {
    DBusError err;
    jboolean ret = JNI_FALSE;
    dbus_bool_t val = FALSE;

    dbus_error_init(&err);

    /* Check the return value. */
    if (dbus_message_get_args(reply, &err,
                               DBUS_TYPE_BOOLEAN, &val,
                               DBUS_TYPE_INVALID)) {
        ret = val == TRUE ? JNI_TRUE : JNI_FALSE;
    } else {
        LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
    }

    dbus_message_unref(reply);
    return ret;
}

jobjectArray dbus_returns_array_of_strings(JNIEnv *env, DBusMessage *reply) {

    DBusError err;
    char **list;
    int i, len;
    jobjectArray strArray = NULL;

    dbus_error_init(&err);
    if (dbus_message_get_args (reply,
                               &err,
                               DBUS_TYPE_ARRAY, DBUS_TYPE_STRING,
                               &list, &len,
                               DBUS_TYPE_INVALID)) {
        jclass stringClass;
        jstring classNameStr;

        //LOGV("%s: there are %d elements in string array!", __FUNCTION__, len);

        stringClass = env->FindClass("java/lang/String");
        strArray = env->NewObjectArray(len, stringClass, NULL);

        for (i = 0; i < len; i++) {
            //LOGV("%s:    array[%d] = [%s]", __FUNCTION__, i, list[i]);
            env->SetObjectArrayElement(strArray, i,
                                       env->NewStringUTF(list[i]));
        }
    } else {
        LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
    }

    dbus_message_unref(reply);
    return strArray;
}

jbyteArray dbus_returns_array_of_bytes(JNIEnv *env, DBusMessage *reply) {

    DBusError err;
    int i, len;
    jbyte *list;
    jbyteArray byteArray = NULL;

    dbus_error_init(&err);
    if (dbus_message_get_args(reply, &err,
                              DBUS_TYPE_ARRAY, DBUS_TYPE_BYTE, &list, &len,
                              DBUS_TYPE_INVALID)) {
        //LOGV("%s: there are %d elements in byte array!", __FUNCTION__, len);
        byteArray = env->NewByteArray(len);
        if (byteArray)
            env->SetByteArrayRegion(byteArray, 0, len, list);

    } else {
        LOG_AND_FREE_DBUS_ERROR_WITH_MSG(&err, reply);
    }

    dbus_message_unref(reply);
    return byteArray;
}

int get_bdaddr(const char *str, bdaddr_t *ba) {
    char *d = ((char *)ba) + 5, *endp;
    int i;
    for(i = 0; i < 6; i++) {
        *d-- = strtol(str, &endp, 16);
        if (*endp != ':' && i != 5) {
            memset(ba, 0, sizeof(bdaddr_t));
            return -1;
        }
        str = endp + 1;
    }
    return 0;
}

void get_bdaddr_as_string(const bdaddr_t *ba, char *str) {
    const uint8_t *b = (const uint8_t *)ba;
    sprintf(str, "%2.2X:%2.2X:%2.2X:%2.2X:%2.2X:%2.2X",
            b[5], b[4], b[3], b[2], b[1], b[0]);
}

bool debug_no_encrypt() {
    return false;
#if 0
    char value[PROPERTY_VALUE_MAX] = "";

    property_get("debug.bt.no_encrypt", value, "");
    if (!strncmp("true", value, PROPERTY_VALUE_MAX) ||
        !strncmp("1", value, PROPERTY_VALUE_MAX)) {
        LOGD("mandatory bluetooth encryption disabled");
        return true;
    } else {
        return false;
    }
#endif
}
#endif

} /* namespace android */
