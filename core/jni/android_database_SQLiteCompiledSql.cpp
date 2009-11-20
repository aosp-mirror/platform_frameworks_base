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


sqlite3_stmt * compile(JNIEnv* env, jobject object,
                       sqlite3 * handle, jstring sqlString)
{
    int err;
    jchar const * sql;
    jsize sqlLen;
    sqlite3_stmt * statement = GET_STATEMENT(env, object);

    // Make sure not to leak the statement if it already exists
    if (statement != NULL) {
        sqlite3_finalize(statement);
        env->SetIntField(object, gStatementField, 0);
    }

    // Compile the SQL
    sql = env->GetStringChars(sqlString, NULL);
    sqlLen = env->GetStringLength(sqlString);
    err = sqlite3_prepare16_v2(handle, sql, sqlLen * 2, &statement, NULL);
    env->ReleaseStringChars(sqlString, sql);

    if (err == SQLITE_OK) {
        // Store the statement in the Java object for future calls
        LOGV("Prepared statement %p on %p", statement, handle);
        env->SetIntField(object, gStatementField, (int)statement);
        return statement;
    } else {
        // Error messages like 'near ")": syntax error' are not
        // always helpful enough, so construct an error string that
        // includes the query itself.
        const char *query = env->GetStringUTFChars(sqlString, NULL);
        char *message = (char*) malloc(strlen(query) + 50);
        if (message) {
            strcpy(message, ", while compiling: "); // less than 50 chars
            strcat(message, query);
        }
        env->ReleaseStringUTFChars(sqlString, query);
        throw_sqlite3_exception(env, handle, message);
        free(message);
        return NULL;
    }
}

static void native_compile(JNIEnv* env, jobject object, jstring sqlString)
{
    compile(env, object, GET_HANDLE(env, object), sqlString);
}

static void native_finalize(JNIEnv* env, jobject object)
{
    int err;
    sqlite3_stmt * statement = GET_STATEMENT(env, object);

    if (statement != NULL) {
        sqlite3_finalize(statement);
        env->SetIntField(object, gStatementField, 0);
    }
}

static JNINativeMethod sMethods[] =
{
     /* name, signature, funcPtr */
    {"native_compile", "(Ljava/lang/String;)V", (void *)native_compile},
    {"native_finalize", "()V", (void *)native_finalize},
};

int register_android_database_SQLiteCompiledSql(JNIEnv * env)
{
    jclass clazz;

    clazz = env->FindClass("android/database/sqlite/SQLiteCompiledSql");
    if (clazz == NULL) {
        LOGE("Can't find android/database/sqlite/SQLiteCompiledSql");
        return -1;
    }

    gHandleField = env->GetFieldID(clazz, "nHandle", "I");
    gStatementField = env->GetFieldID(clazz, "nStatement", "I");

    if (gHandleField == NULL || gStatementField == NULL) {
        LOGE("Error locating fields");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
        "android/database/sqlite/SQLiteCompiledSql", sMethods, NELEM(sMethods));
}

} // namespace android
