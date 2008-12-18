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

#ifndef ANDROID_BLUETOOTH_COMMON_H
#define ANDROID_BLUETOOTH_COMMON_H

// Set to 0 to enable verbose bluetooth logging
#define LOG_NDEBUG 1

#include "jni.h"
#include "utils/Log.h"

#include <errno.h>
#include <stdint.h>

#ifdef HAVE_BLUETOOTH
#include <dbus/dbus.h>
#include <bluetooth/bluetooth.h>
#endif

namespace android {

#ifdef HAVE_BLUETOOTH
#define BLUEZ_DBUS_BASE_PATH      "/org/bluez"
#define BLUEZ_DBUS_BASE_IFC       "org.bluez"

// It would be nicer to retrieve this from bluez using GetDefaultAdapter,
// but this is only possible when the adapter is up (and hcid is running).
// It is much easier just to hardcode bluetooth adapter to hci0
#define BLUETOOTH_ADAPTER_HCI_NUM 0
#define BLUEZ_ADAPTER_OBJECT_NAME BLUEZ_DBUS_BASE_PATH "/hci0"

#define BTADDR_SIZE 18   // size of BT address character array (including null)

jfieldID get_field(JNIEnv *env,
                   jclass clazz,
                   const char *member,
                   const char *mtype);

// LOGE and free a D-Bus error
// Using #define so that __FUNCTION__ resolves usefully
#define LOG_AND_FREE_DBUS_ERROR_WITH_MSG(err, msg) \
    {   LOGE("%s: D-Bus error in %s: %s (%s)", __FUNCTION__, \
        dbus_message_get_member((msg)), (err)->name, (err)->message); \
         dbus_error_free((err)); }
#define LOG_AND_FREE_DBUS_ERROR(err) \
    {   LOGE("%s: D-Bus error: %s (%s)", __FUNCTION__, \
        (err)->name, (err)->message); \
        dbus_error_free((err)); }

struct event_loop_native_data_t {
    DBusConnection *conn;
    /* These variables are set in waitForAndDispatchEventNative() and are
       valid only within the scope of this function.  At any other time, they
       are NULL. */
    jobject me;
    JNIEnv *env;
};

dbus_bool_t dbus_func_args_async_valist(JNIEnv *env,
                                        DBusConnection *conn,
                                        int timeout_ms,
                                        void (*reply)(DBusMessage *, void *),
                                        void *user,
                                        const char *path,
                                        const char *ifc,
                                        const char *func,
                                        int first_arg_type,
                                        va_list args);

dbus_bool_t dbus_func_args_async(JNIEnv *env,
                                 DBusConnection *conn,
                                 int timeout_ms,
                                 void (*reply)(DBusMessage *, void *),
                                 void *user,
                                 const char *path,
                                 const char *ifc,
                                 const char *func,
                                 int first_arg_type,
                                 ...);

DBusMessage * dbus_func_args(JNIEnv *env,
                             DBusConnection *conn,
                             const char *path,
                             const char *ifc,
                             const char *func,
                             int first_arg_type,
                             ...);

DBusMessage * dbus_func_args_error(JNIEnv *env,
                                   DBusConnection *conn,
                                   DBusError *err,
                                   const char *path,
                                   const char *ifc,
                                   const char *func,
                                   int first_arg_type,
                                   ...);

DBusMessage * dbus_func_args_timeout(JNIEnv *env,
                                     DBusConnection *conn,
                                     int timeout_ms,
                                     const char *path,
                                     const char *ifc,
                                     const char *func,
                                     int first_arg_type,
                                     ...);

DBusMessage * dbus_func_args_timeout_valist(JNIEnv *env,
                                            DBusConnection *conn,
                                            int timeout_ms,
                                            DBusError *err,
                                            const char *path,
                                            const char *ifc,
                                            const char *func,
                                            int first_arg_type,
                                            va_list args);

jint dbus_returns_int32(JNIEnv *env, DBusMessage *reply);
jint dbus_returns_uint32(JNIEnv *env, DBusMessage *reply);
jstring dbus_returns_string(JNIEnv *env, DBusMessage *reply);
jboolean dbus_returns_boolean(JNIEnv *env, DBusMessage *reply);
jobjectArray dbus_returns_array_of_strings(JNIEnv *env, DBusMessage *reply);
jbyteArray dbus_returns_array_of_bytes(JNIEnv *env, DBusMessage *reply);

void get_bdaddr(const char *str, bdaddr_t *ba);
void get_bdaddr_as_string(const bdaddr_t *ba, char *str);

bool debug_no_encrypt();

#endif
} /* namespace android */

#endif/*ANDROID_BLUETOOTH_COMMON_H*/
