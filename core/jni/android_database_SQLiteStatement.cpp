/* //device/libs/android_runtime/android_database_SQLiteCursor.cpp
**
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

#undef LOG_TAG
#define LOG_TAG "SQLiteStatementCpp"

#include "android_util_Binder.h"

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <sqlite3.h>

#include <cutils/ashmem.h>
#include <utils/Log.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "sqlite3_exception.h"

namespace android {


sqlite3_stmt * compile(JNIEnv* env, jobject object,
                       sqlite3 * handle, jstring sqlString);

static jfieldID gHandleField;
static jfieldID gStatementField;


#define GET_STATEMENT(env, object) \
        (sqlite3_stmt *)env->GetIntField(object, gStatementField)
#define GET_HANDLE(env, object) \
        (sqlite3 *)env->GetIntField(object, gHandleField)


static jint native_execute(JNIEnv* env, jobject object)
{
    int err;
    sqlite3 * handle = GET_HANDLE(env, object);
    sqlite3_stmt * statement = GET_STATEMENT(env, object);
    int numChanges = -1;

    // Execute the statement
    err = sqlite3_step(statement);

    // Throw an exception if an error occurred
    if (err == SQLITE_ROW) {
        throw_sqlite3_exception(env,
                "Queries can be performed using SQLiteDatabase query or rawQuery methods only.");
    } else if (err != SQLITE_DONE) {
        throw_sqlite3_exception_errcode(env, err, sqlite3_errmsg(handle));
    } else {
        numChanges = sqlite3_changes(handle);
    }

    // Reset the statement so it's ready to use again
    sqlite3_reset(statement);
    return numChanges;
}

static jlong native_executeInsert(JNIEnv* env, jobject object)
{
    sqlite3 * handle = GET_HANDLE(env, object);
    jint numChanges = native_execute(env, object);
    if (numChanges > 0) {
        return sqlite3_last_insert_rowid(handle);
    } else {
        return -1;
    }
}

static jlong native_1x1_long(JNIEnv* env, jobject object)
{
    int err;
    sqlite3 * handle = GET_HANDLE(env, object);
    sqlite3_stmt * statement = GET_STATEMENT(env, object);
    jlong value = -1;

    // Execute the statement
    err = sqlite3_step(statement);

    // Handle the result
    if (err == SQLITE_ROW) {
        // No errors, read the data and return it
        value = sqlite3_column_int64(statement, 0);
    } else {
        throw_sqlite3_exception_errcode(env, err, sqlite3_errmsg(handle));
    }

    // Reset the statment so it's ready to use again
    sqlite3_reset(statement);

    return value;
}

static jstring native_1x1_string(JNIEnv* env, jobject object)
{
    int err;
    sqlite3 * handle = GET_HANDLE(env, object);
    sqlite3_stmt * statement = GET_STATEMENT(env, object);
    jstring value = NULL;

    // Execute the statement
    err = sqlite3_step(statement);

    // Handle the result
    if (err == SQLITE_ROW) {
        // No errors, read the data and return it
        char const * text = (char const *)sqlite3_column_text(statement, 0);
        value = env->NewStringUTF(text);
    } else {
        throw_sqlite3_exception_errcode(env, err, sqlite3_errmsg(handle));
    }

    // Reset the statment so it's ready to use again
    sqlite3_reset(statement);

    return value;
}

static jobject createParcelFileDescriptor(JNIEnv * env, int fd)
{
    // Create FileDescriptor object
    jobject fileDesc = jniCreateFileDescriptor(env, fd);
    if (fileDesc == NULL) {
        // FileDescriptor constructor has thrown an exception
        close(fd);
        return NULL;
    }

    // Wrap it in a ParcelFileDescriptor
    jobject parcelFileDesc = newParcelFileDescriptor(env, fileDesc);
    if (parcelFileDesc == NULL) {
        // ParcelFileDescriptor constructor has thrown an exception
        close(fd);
        return NULL;
    }

    return parcelFileDesc;
}

// Creates an ashmem area, copies some data into it, and returns
// a ParcelFileDescriptor for the ashmem area.
static jobject create_ashmem_region_with_data(JNIEnv * env,
                                              const void * data, int length)
{
    // Create ashmem area
    int fd = ashmem_create_region(NULL, length);
    if (fd < 0) {
        ALOGE("ashmem_create_region failed: %s", strerror(errno));
        jniThrowIOException(env, errno);
        return NULL;
    }

    if (length > 0) {
        // mmap the ashmem area
        void * ashmem_ptr =
                mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (ashmem_ptr == MAP_FAILED) {
            ALOGE("mmap failed: %s", strerror(errno));
            jniThrowIOException(env, errno);
            close(fd);
            return NULL;
        }

        // Copy data to ashmem area
        memcpy(ashmem_ptr, data, length);

        // munmap ashmem area
        if (munmap(ashmem_ptr, length) < 0) {
            ALOGE("munmap failed: %s", strerror(errno));
            jniThrowIOException(env, errno);
            close(fd);
            return NULL;
        }
    }

    // Make ashmem area read-only
    if (ashmem_set_prot_region(fd, PROT_READ) < 0) {
        ALOGE("ashmem_set_prot_region failed: %s", strerror(errno));
        jniThrowIOException(env, errno);
        close(fd);
        return NULL;
    }

    // Wrap it in a ParcelFileDescriptor
    return createParcelFileDescriptor(env, fd);
}

static jobject native_1x1_blob_ashmem(JNIEnv* env, jobject object)
{
    int err;
    sqlite3 * handle = GET_HANDLE(env, object);
    sqlite3_stmt * statement = GET_STATEMENT(env, object);
    jobject value = NULL;

    // Execute the statement
    err = sqlite3_step(statement);

    // Handle the result
    if (err == SQLITE_ROW) {
        // No errors, read the data and return it
        const void * blob = sqlite3_column_blob(statement, 0);
        if (blob != NULL) {
            int len = sqlite3_column_bytes(statement, 0);
            if (len >= 0) {
                value = create_ashmem_region_with_data(env, blob, len);
            }
        }
    } else {
        throw_sqlite3_exception_errcode(env, err, sqlite3_errmsg(handle));
    }

    // Reset the statment so it's ready to use again
    sqlite3_reset(statement);

    return value;
}

static void native_executeSql(JNIEnv* env, jobject object, jstring sql)
{
    char const* sqlString = env->GetStringUTFChars(sql, NULL);
    sqlite3 * handle = GET_HANDLE(env, object);
    int err = sqlite3_exec(handle, sqlString, NULL, NULL, NULL);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, handle);
    }
    env->ReleaseStringUTFChars(sql, sqlString);
}

static JNINativeMethod sMethods[] =
{
     /* name, signature, funcPtr */
    {"native_execute", "()I", (void *)native_execute},
    {"native_executeInsert", "()J", (void *)native_executeInsert},
    {"native_1x1_long", "()J", (void *)native_1x1_long},
    {"native_1x1_string", "()Ljava/lang/String;", (void *)native_1x1_string},
    {"native_1x1_blob_ashmem", "()Landroid/os/ParcelFileDescriptor;", (void *)native_1x1_blob_ashmem},
    {"native_executeSql", "(Ljava/lang/String;)V", (void *)native_executeSql},
};

int register_android_database_SQLiteStatement(JNIEnv * env)
{
    jclass clazz;

    clazz = env->FindClass("android/database/sqlite/SQLiteStatement");
    if (clazz == NULL) {
        ALOGE("Can't find android/database/sqlite/SQLiteStatement");
        return -1;
    }

    gHandleField = env->GetFieldID(clazz, "nHandle", "I");
    gStatementField = env->GetFieldID(clazz, "nStatement", "I");

    if (gHandleField == NULL || gStatementField == NULL) {
        ALOGE("Error locating fields");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
        "android/database/sqlite/SQLiteStatement", sMethods, NELEM(sMethods));
}

} // namespace android
