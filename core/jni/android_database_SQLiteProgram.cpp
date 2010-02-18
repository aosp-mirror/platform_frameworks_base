/*
 * Copyright (C) 2006-2008 The Android Open Source Project
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

#undef LOG_TAG
#define LOG_TAG "Cursor"

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <sqlite3.h>

#include <utils/Log.h>

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "sqlite3_exception.h"


namespace android {

static jfieldID gHandleField;
static jfieldID gStatementField;


#define GET_STATEMENT(env, object) \
        (sqlite3_stmt *)env->GetIntField(object, gStatementField)
#define GET_HANDLE(env, object) \
        (sqlite3 *)env->GetIntField(object, gHandleField)

static void native_compile(JNIEnv* env, jobject object, jstring sqlString)
{
    char buf[65];
    strcpy(buf, "android_database_SQLiteProgram->native_compile() not implemented");
    throw_sqlite3_exception(env, GET_HANDLE(env, object), buf);
    return;
}

static void native_bind_null(JNIEnv* env, jobject object,
                             jint index)
{
    int err;
    sqlite3_stmt * statement = GET_STATEMENT(env, object);

    err = sqlite3_bind_null(statement, index);
    if (err != SQLITE_OK) {
        char buf[32];
        sprintf(buf, "handle %p", statement);
        throw_sqlite3_exception(env, GET_HANDLE(env, object), buf);
        return;
    }
}

static void native_bind_long(JNIEnv* env, jobject object,
                             jint index, jlong value)
{
    int err;
    sqlite3_stmt * statement = GET_STATEMENT(env, object);

    err = sqlite3_bind_int64(statement, index, value);
    if (err != SQLITE_OK) {
        char buf[32];
        sprintf(buf, "handle %p", statement);
        throw_sqlite3_exception(env, GET_HANDLE(env, object), buf);
        return;
    }
}

static void native_bind_double(JNIEnv* env, jobject object,
                             jint index, jdouble value)
{
    int err;
    sqlite3_stmt * statement = GET_STATEMENT(env, object);

    err = sqlite3_bind_double(statement, index, value);
    if (err != SQLITE_OK) {
        char buf[32];
        sprintf(buf, "handle %p", statement);
        throw_sqlite3_exception(env, GET_HANDLE(env, object), buf);
        return;
    }
}

static void native_bind_string(JNIEnv* env, jobject object,
                               jint index, jstring sqlString)
{
    int err;
    jchar const * sql;
    jsize sqlLen;
    sqlite3_stmt * statement= GET_STATEMENT(env, object);

    sql = env->GetStringChars(sqlString, NULL);
    sqlLen = env->GetStringLength(sqlString);
    err = sqlite3_bind_text16(statement, index, sql, sqlLen * 2, SQLITE_TRANSIENT);
    env->ReleaseStringChars(sqlString, sql);
    if (err != SQLITE_OK) {
        char buf[32];
        sprintf(buf, "handle %p", statement);
        throw_sqlite3_exception(env, GET_HANDLE(env, object), buf);
        return;
    }
}

static void native_bind_blob(JNIEnv* env, jobject object,
                               jint index, jbyteArray value)
{
    int err;
    jchar const * sql;
    jsize sqlLen;
    sqlite3_stmt * statement= GET_STATEMENT(env, object);

    jint len = env->GetArrayLength(value);
    jbyte * bytes = env->GetByteArrayElements(value, NULL);

    err = sqlite3_bind_blob(statement, index, bytes, len, SQLITE_TRANSIENT);
    env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);

    if (err != SQLITE_OK) {
        char buf[32];
        sprintf(buf, "statement %p", statement);
        throw_sqlite3_exception(env, GET_HANDLE(env, object), buf);
        return;
    }
}

static void native_clear_bindings(JNIEnv* env, jobject object)
{
    int err;
    sqlite3_stmt * statement = GET_STATEMENT(env, object);

    err = sqlite3_clear_bindings(statement);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, GET_HANDLE(env, object));
        return;
    }
}

static void native_finalize(JNIEnv* env, jobject object)
{
    char buf[66];
    strcpy(buf, "android_database_SQLiteProgram->native_finalize() not implemented");
    throw_sqlite3_exception(env, GET_HANDLE(env, object), buf);
    return;
}


static JNINativeMethod sMethods[] =
{
     /* name, signature, funcPtr */
    {"native_bind_null", "(I)V", (void *)native_bind_null},
    {"native_bind_long", "(IJ)V", (void *)native_bind_long},
    {"native_bind_double", "(ID)V", (void *)native_bind_double},
    {"native_bind_string", "(ILjava/lang/String;)V", (void *)native_bind_string},
    {"native_bind_blob", "(I[B)V", (void *)native_bind_blob},
    {"native_clear_bindings", "()V", (void *)native_clear_bindings},
};

int register_android_database_SQLiteProgram(JNIEnv * env)
{
    jclass clazz;

    clazz = env->FindClass("android/database/sqlite/SQLiteProgram");
    if (clazz == NULL) {
        LOGE("Can't find android/database/sqlite/SQLiteProgram");
        return -1;
    }

    gHandleField = env->GetFieldID(clazz, "nHandle", "I");
    gStatementField = env->GetFieldID(clazz, "nStatement", "I");

    if (gHandleField == NULL || gStatementField == NULL) {
        LOGE("Error locating fields");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
        "android/database/sqlite/SQLiteProgram", sMethods, NELEM(sMethods));
}

} // namespace android
