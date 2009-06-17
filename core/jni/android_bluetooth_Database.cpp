/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define DBUS_CLASS_NAME BLUEZ_DBUS_BASE_IFC ".Database"
#define LOG_TAG "bluetooth_Database.cpp"

#include "android_bluetooth_common.h"
#include "android_runtime/AndroidRuntime.h"
#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"

#ifdef HAVE_BLUETOOTH
#include <dbus/dbus.h>
#endif

namespace android {

#ifdef HAVE_BLUETOOTH
static DBusConnection* conn = NULL;   // Singleton thread-safe connection
#endif

static void classInitNative(JNIEnv* env, jclass clazz) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    conn = NULL;
#endif
}

static void initializeNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);

#ifdef HAVE_BLUETOOTH
    if (conn == NULL) {
        DBusError err;
        dbus_error_init(&err);
        dbus_threads_init_default();
        conn = dbus_bus_get(DBUS_BUS_SYSTEM, &err);
        if (dbus_error_is_set(&err)) {
            LOGE("Could not get onto the system bus!");
            dbus_error_free(&err);
        }
        dbus_connection_set_exit_on_disconnect(conn, FALSE);
    }
#endif
}

static void cleanupNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
}

static jint addServiceRecordNative(JNIEnv *env, jobject object,
                                   jbyteArray record) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    if (conn != NULL) {
        jbyte* c_record = env->GetByteArrayElements(record, NULL);
        DBusMessage *reply = dbus_func_args(env,
                                            conn,
                                            BLUEZ_DBUS_BASE_PATH,
                                            DBUS_CLASS_NAME,
                                            "AddServiceRecord",
                                            DBUS_TYPE_ARRAY, DBUS_TYPE_BYTE,
                                            &c_record,
                                            env->GetArrayLength(record),
                                            DBUS_TYPE_INVALID);
        env->ReleaseByteArrayElements(record, c_record, JNI_ABORT);
        return reply ? dbus_returns_uint32(env, reply) : -1;
    }
#endif
    return -1;
}

static jint addServiceRecordFromXmlNative(JNIEnv *env, jobject object,
                                          jstring record) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    if (conn != NULL) {
        const char *c_record = env->GetStringUTFChars(record, NULL);
        DBusMessage *reply = dbus_func_args(env,
                                            conn,
                                            BLUEZ_DBUS_BASE_PATH,
                                            DBUS_CLASS_NAME,
                                            "AddServiceRecordFromXML",
                                            DBUS_TYPE_STRING, &c_record,
                                            DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(record, c_record);
        return reply ? dbus_returns_uint32(env, reply) : -1;
    }
#endif
    return -1;
}

static void updateServiceRecordNative(JNIEnv *env, jobject object,
                                      jint handle,
                                      jbyteArray record) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    if (conn != NULL) {
        jbyte* c_record = env->GetByteArrayElements(record, NULL);
        DBusMessage *reply = dbus_func_args(env,
                                            conn,
                                            BLUEZ_DBUS_BASE_PATH,
                                            DBUS_CLASS_NAME,
                                            "UpdateServiceRecord",
                                            DBUS_TYPE_UINT32, &handle,
                                            DBUS_TYPE_ARRAY, DBUS_TYPE_BYTE,
                                            &c_record,
                                            env->GetArrayLength(record),
                                            DBUS_TYPE_INVALID);
        env->ReleaseByteArrayElements(record, c_record, JNI_ABORT);
    }
#endif
}

static void updateServiceRecordFromXmlNative(JNIEnv *env, jobject object,
                                             jint handle,
                                             jstring record) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    if (conn != NULL) {
        const char *c_record = env->GetStringUTFChars(record, NULL);
        DBusMessage *reply = dbus_func_args(env,
                                            conn,
                                            BLUEZ_DBUS_BASE_PATH,
                                            DBUS_CLASS_NAME,
                                            "UpdateServiceRecordFromXML",
                                            DBUS_TYPE_UINT32, &handle,
                                            DBUS_TYPE_STRING, &c_record,
                                            DBUS_TYPE_INVALID);
        env->ReleaseStringUTFChars(record, c_record);
    }
#endif
}

/* private static native void removeServiceRecordNative(int handle); */
static void removeServiceRecordNative(JNIEnv *env, jobject object,
                                      jint handle) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    if (conn != NULL) {
        DBusMessage *reply = dbus_func_args(env,
                                            conn,
                                            BLUEZ_DBUS_BASE_PATH,
                                            DBUS_CLASS_NAME,
                                            "RemoveServiceRecord",
                                            DBUS_TYPE_UINT32, &handle,
                                            DBUS_TYPE_INVALID);
    }
#endif
}


static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"classInitNative", "()V", (void*)classInitNative},
    {"initializeNativeDataNative", "()V", (void *)initializeNativeDataNative},
    {"cleanupNativeDataNative", "()V", (void *)cleanupNativeDataNative},
    {"addServiceRecordNative", "([B)I", (void*)addServiceRecordNative},
    {"addServiceRecordFromXmlNative", "(Ljava/lang/String;)I", (void*)addServiceRecordFromXmlNative},
    {"updateServiceRecordNative", "(I[B)V", (void*)updateServiceRecordNative},
    {"updateServiceRecordFromXmlNative", "(ILjava/lang/String;)V", (void*)updateServiceRecordFromXmlNative},
    {"removeServiceRecordNative", "(I)V", (void*)removeServiceRecordNative},
};

int register_android_bluetooth_Database(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
            "android/bluetooth/Database", sMethods, NELEM(sMethods));
}

} /* namespace android */
