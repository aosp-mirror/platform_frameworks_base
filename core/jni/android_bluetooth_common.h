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
#include <pthread.h>
#include <stdint.h>
#include <sys/poll.h>

#ifdef HAVE_BLUETOOTH
#include <dbus/dbus.h>
#include <bluetooth/bluetooth.h>
#endif

namespace android {

#ifdef HAVE_BLUETOOTH
#define BLUEZ_DBUS_BASE_PATH      "/org/bluez"
#define BLUEZ_DBUS_BASE_IFC       "org.bluez"
#define BLUEZ_ERROR_IFC           "org.bluez.Error"

// It would be nicer to retrieve this from bluez using GetDefaultAdapter,
// but this is only possible when the adapter is up (and hcid is running).
// It is much easier just to hardcode bluetooth adapter to hci0
#define BLUETOOTH_ADAPTER_HCI_NUM 0
#define BLUEZ_ADAPTER_OBJECT_NAME BLUEZ_DBUS_BASE_PATH "/hci0"

#define BTADDR_SIZE 18   // size of BT address character array (including null)

// size of the dbus event loops pollfd structure, hopefully never to be grown
#define DEFAULT_INITIAL_POLLFD_COUNT 8

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
    const char *adapter;

    /* protects the thread */
    pthread_mutex_t thread_mutex;
    pthread_t thread;
    /* our comms socket */
    /* mem for the list of sockets to listen to */
    struct pollfd *pollData;
    int pollMemberCount;
    int pollDataSize;
    /* mem for matching set of dbus watch ptrs */
    DBusWatch **watchData;
    /* pair of sockets for event loop control, Reader and Writer */
    int controlFdR;
    int controlFdW;
    /* our vm and env Version for future env generation */
    JavaVM *vm;
    int envVer;
    /* reference to our java self */
    jobject me;
    /* flag to indicate if the event loop thread is running */
    bool running;
};

struct _Properties {
    char name[32];
    int type;
};
typedef struct _Properties Properties;

dbus_bool_t dbus_func_args_async(JNIEnv *env,
                                 DBusConnection *conn,
                                 int timeout_ms,
                                 void (*reply)(DBusMessage *, void *, void *),
                                 void *user,
                                 void *nat,
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
jint dbus_returns_unixfd(JNIEnv *env, DBusMessage *reply);
jstring dbus_returns_string(JNIEnv *env, DBusMessage *reply);
jboolean dbus_returns_boolean(JNIEnv *env, DBusMessage *reply);
jobjectArray dbus_returns_array_of_strings(JNIEnv *env, DBusMessage *reply);
jobjectArray dbus_returns_array_of_object_path(JNIEnv *env, DBusMessage *reply);
jbyteArray dbus_returns_array_of_bytes(JNIEnv *env, DBusMessage *reply);

jobjectArray parse_properties(JNIEnv *env, DBusMessageIter *iter, Properties *properties,
                              const int max_num_properties);
jobjectArray parse_property_change(JNIEnv *env, DBusMessage *msg,
                                   Properties *properties, int max_num_properties);
jobjectArray parse_adapter_properties(JNIEnv *env, DBusMessageIter *iter);
jobjectArray parse_remote_device_properties(JNIEnv *env, DBusMessageIter *iter);
jobjectArray parse_remote_device_property_change(JNIEnv *env, DBusMessage *msg);
jobjectArray parse_adapter_property_change(JNIEnv *env, DBusMessage *msg);
jobjectArray parse_input_properties(JNIEnv *env, DBusMessageIter *iter);
jobjectArray parse_health_device_properties(JNIEnv *env, DBusMessageIter *iter);
jobjectArray parse_health_channel_properties(JNIEnv *env, DBusMessageIter *iter);
jobjectArray parse_input_property_change(JNIEnv *env, DBusMessage *msg);
jobjectArray parse_pan_property_change(JNIEnv *env, DBusMessage *msg);
jobjectArray parse_health_device_property_change(JNIEnv *env, DBusMessage *msg);

void append_dict_args(DBusMessage *reply, const char *first_key, ...);
void append_variant(DBusMessageIter *iter, int type, void *val);
int get_bdaddr(const char *str, bdaddr_t *ba);
void get_bdaddr_as_string(const bdaddr_t *ba, char *str);

bool debug_no_encrypt();


// Result codes from Bluez DBus calls
#define BOND_RESULT_ERROR                      -1
#define BOND_RESULT_SUCCESS                     0
#define BOND_RESULT_AUTH_FAILED                 1
#define BOND_RESULT_AUTH_REJECTED               2
#define BOND_RESULT_AUTH_CANCELED               3
#define BOND_RESULT_REMOTE_DEVICE_DOWN          4
#define BOND_RESULT_DISCOVERY_IN_PROGRESS       5
#define BOND_RESULT_AUTH_TIMEOUT                6
#define BOND_RESULT_REPEATED_ATTEMPTS           7

#define PAN_DISCONNECT_FAILED_NOT_CONNECTED  1000
#define PAN_CONNECT_FAILED_ALREADY_CONNECTED 1001
#define PAN_CONNECT_FAILED_ATTEMPT_FAILED    1002
#define PAN_OPERATION_GENERIC_FAILURE        1003
#define PAN_OPERATION_SUCCESS                1004

#define INPUT_DISCONNECT_FAILED_NOT_CONNECTED  5000
#define INPUT_CONNECT_FAILED_ALREADY_CONNECTED 5001
#define INPUT_CONNECT_FAILED_ATTEMPT_FAILED    5002
#define INPUT_OPERATION_GENERIC_FAILURE        5003
#define INPUT_OPERATION_SUCCESS                5004

#define HEALTH_OPERATION_SUCCESS               6000
#define HEALTH_OPERATION_ERROR                 6001
#define HEALTH_OPERATION_INVALID_ARGS          6002
#define HEALTH_OPERATION_GENERIC_FAILURE       6003
#define HEALTH_OPERATION_NOT_FOUND             6004
#define HEALTH_OPERATION_NOT_ALLOWED           6005

#endif
} /* namespace android */

#endif/*ANDROID_BLUETOOTH_COMMON_H*/
