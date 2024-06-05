/*
 * Copyright (C) 2023 The Android Open Source Project
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

#define LOG_TAG "SQLiteRawStatement"

#include <string.h>
#include <algorithm>

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_primitive_array.h>
#include <nativehelper/scoped_string_chars.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <android-base/stringprintf.h>
#include <core_jni_helpers.h>

#include <utils/Log.h>
#include <utils/Unicode.h>

#include <sqlite3.h>
#include <sqlite3_android.h>

#include "android_database_SQLiteCommon.h"

/**
 * JNI functions supporting the android.database.sqlite.SQLiteRawStatement class.
 */
namespace android {

// A zero-length byte array that can be returned by getColumnBlob().  The theory is that
// zero-length blobs are common enough that it is worth having a single, global instance. The
// object is created in the jni registration function.  It is never destroyed.
static jbyteArray emptyArray = nullptr;

// Helper functions.
static sqlite3 *db(long statementPtr) {
    return sqlite3_db_handle(reinterpret_cast<sqlite3_stmt*>(statementPtr));
}

static sqlite3_stmt* stmt(long statementPtr) {
    return reinterpret_cast<sqlite3_stmt*>(statementPtr);
}

// This throws a SQLiteBindOrColumnIndexOutOfRangeException if the parameter index is out
// of bounds.  The function exists to construct an error message that includes
// the bounds.
static void throwInvalidParameter(JNIEnv *env, jlong stmtPtr, jint index) {
    if (sqlite3_extended_errcode(db(stmtPtr)) == SQLITE_RANGE) {
        int count = sqlite3_bind_parameter_count(stmt(stmtPtr));
        std::string message = android::base::StringPrintf(
            "parameter index %d out of bounds [1,%d]", index, count);
        char const * errmsg = sqlite3_errstr(SQLITE_RANGE);
        throw_sqlite3_exception(env, SQLITE_RANGE, errmsg, message.c_str());
    } else {
        throw_sqlite3_exception(env, db(stmtPtr), nullptr);
    }
}


// This throws a SQLiteBindOrColumnIndexOutOfRangeException if the column index is out
// of bounds.
static void throwIfInvalidColumn(JNIEnv *env, jlong stmtPtr, jint col) {
    if (col < 0 || col >= sqlite3_data_count(stmt(stmtPtr))) {
        int count = sqlite3_data_count(stmt(stmtPtr));
        std::string message = android::base::StringPrintf(
            "column index %d out of bounds [0,%d]", col, count - 1);
        char const * errmsg = sqlite3_errstr(SQLITE_RANGE);
        throw_sqlite3_exception(env, SQLITE_RANGE, errmsg, message.c_str());
    }
}


static jint bindParameterCount(JNIEnv* env, jclass, jlong stmtPtr) {
    return sqlite3_bind_parameter_count(stmt(stmtPtr));
}

// jname must be in standard UTF-8.  This throws an NPE if jname is null.
static jint bindParameterIndex(JNIEnv *env, jclass, jlong stmtPtr, jstring jname) {
    ScopedStringChars name(env, jname);
    if (name.get() == nullptr) {
        return 0;
    }
    size_t len16 = env->GetStringLength(jname);
    size_t len8 = utf16_to_utf8_length(reinterpret_cast<const char16_t*>(name.get()), len16);
    // The extra byte is for the terminating null.
    char *utf8Name = new char[len8 + 1];
    utf16_to_utf8(reinterpret_cast<const char16_t*>(name.get()), len16, utf8Name, len8 + 1);
    int r = sqlite3_bind_parameter_index(stmt(stmtPtr), utf8Name);
    delete [] utf8Name;
    return r;
}

// The name returned from the database is UTF-8.  If there is no matching name,
// null is returned.
static jstring bindParameterName(JNIEnv *env, jclass, jlong stmtPtr, jint param) {
    char const *src = sqlite3_bind_parameter_name(stmt(stmtPtr), param);
    if (src == nullptr) {
        return NULL;
    }
    return env->NewStringUTF(src);
}

static jint columnCount(JNIEnv* env, jclass, jlong stmtPtr) {
    return sqlite3_column_count(stmt(stmtPtr));
}

// Step the prepared statement.  If the result is other than ROW, DONE, BUSY, or LOCKED, throw an
// exception if throwOnError is true.  The advantage of throwing from the native latyer is that
// all the error codes and error strings are easily visible.
static jint step(JNIEnv* env, jclass, jlong stmtPtr, jboolean throwOnError) {
    sqlite3_stmt* statement = stmt(stmtPtr);
    int err = sqlite3_step(statement);
    switch (err) {
        case SQLITE_ROW:
        case SQLITE_DONE:
        case SQLITE_BUSY:
        case SQLITE_LOCKED:
            return err;
    }
    if (throwOnError) {
        throw_sqlite3_exception(env, db(stmtPtr), "failure in step()");
    }
    return err;
}

static void reset(JNIEnv*, jclass, jlong stmtPtr, jboolean clear) {
    if (clear) sqlite3_clear_bindings(stmt(stmtPtr));
    // The return value is ignored.
    sqlite3_reset(stmt(stmtPtr));
}

static void clearBindings(JNIEnv*, jclass, jlong stmtPtr) {
    sqlite3_clear_bindings(stmt(stmtPtr));
}


// This binds null to the parameter if the incoming array is null.
static void bindBlob(JNIEnv* env, jclass obj, jlong stmtPtr, jint index, jbyteArray val,
        jint offset, jint length) {
    ScopedByteArrayRO value(env, val);
    int err;
    if (value.get() == nullptr) {
        err = sqlite3_bind_null(stmt(stmtPtr), index);
    } else {
        err = sqlite3_bind_blob(stmt(stmtPtr), index, value.get() + offset,
                                length, SQLITE_TRANSIENT);
    }
    if (err != SQLITE_OK) {
        throwInvalidParameter(env, stmtPtr, index);
    }
}

static void bindDouble(JNIEnv* env, jclass, jlong stmtPtr, jint index, jdouble val) {
    if (sqlite3_bind_double(stmt(stmtPtr), index, val) != SQLITE_OK) {
        throwInvalidParameter(env, stmtPtr, index);
    }
}

static void bindInt(JNIEnv* env, jclass, jlong stmtPtr, jint index, jint val) {
    if (sqlite3_bind_int(stmt(stmtPtr), index, val) != SQLITE_OK) {
        throwInvalidParameter(env, stmtPtr, index);
    }
}

static void bindLong(JNIEnv* env, jclass, jlong stmtPtr, jint index, jlong val) {
    if (sqlite3_bind_int64(stmt(stmtPtr), index, val) != SQLITE_OK) {
        throwInvalidParameter(env, stmtPtr, index);
    }
}

static void bindNull(JNIEnv* env, jclass, jlong stmtPtr, jint index) {
    if (sqlite3_bind_null(stmt(stmtPtr), index) != SQLITE_OK) {
        throwInvalidParameter(env, stmtPtr, index);
    }
}

// This binds null to the parameter if the string is null.
static void bindText(JNIEnv* env, jclass, jlong stmtPtr, jint index, jstring val) {
    ScopedStringChars value(env, val);
    int err;
    if (value.get() == nullptr) {
        err = sqlite3_bind_null(stmt(stmtPtr), index);
    } else {
        jsize valueLength = env->GetStringLength(val);
        err = sqlite3_bind_text16(stmt(stmtPtr), index, value.get(),
            valueLength * sizeof(jchar), SQLITE_TRANSIENT);
    }
    if (err != SQLITE_OK) {
        throwInvalidParameter(env, stmtPtr, index);
    }
}


static jint columnType(JNIEnv* env, jclass, jlong stmtPtr, jint col) {
    throwIfInvalidColumn(env, stmtPtr, col);
    return sqlite3_column_type(stmt(stmtPtr), col);
}

static jstring columnName(JNIEnv* env, jclass, jlong stmtPtr, jint col) {
    throwIfInvalidColumn(env, stmtPtr, col);
    const jchar* name = static_cast<const jchar*>(sqlite3_column_name16(stmt(stmtPtr), col));
    if (name == nullptr) {
        throw_sqlite3_exception(env, db(stmtPtr), "error fetching columnName()");
        return NULL;
    }
    size_t length = strlen16(reinterpret_cast<const char16_t*>(name));
    return env->NewString(name, length);
}

static jint columnBytes(JNIEnv* env, jclass, jlong stmtPtr, jint col) {
    throwIfInvalidColumn(env, stmtPtr, col);
    return sqlite3_column_bytes16(stmt(stmtPtr), col);
}


static jbyteArray columnBlob(JNIEnv* env, jclass, jlong stmtPtr, jint col) {
    throwIfInvalidColumn(env, stmtPtr, col);
    const void* blob = sqlite3_column_blob(stmt(stmtPtr), col);
    if (blob == nullptr) {
        return (sqlite3_column_type(stmt(stmtPtr), col) == SQLITE_NULL) ? NULL : emptyArray;
    }
    size_t size = sqlite3_column_bytes(stmt(stmtPtr), col);
    jbyteArray result = env->NewByteArray(size);
    if (result == nullptr) {
        // An OutOfMemory exception will have been thrown.
        return NULL;
    }
    env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(blob));
    return result;
}

static int columnBuffer(JNIEnv* env, jclass, jlong stmtPtr, jint col,
        jbyteArray buffer, jint offset, jint length, jint srcOffset) {
    throwIfInvalidColumn(env, stmtPtr, col);
    const void* blob = sqlite3_column_blob(stmt(stmtPtr), col);
    if (blob == nullptr) {
        return 0;
    }
    jsize bsize = sqlite3_column_bytes(stmt(stmtPtr), col);
    if (bsize == 0 || bsize <= srcOffset) {
        return 0;
    }
    jsize want = std::min(bsize - srcOffset, length);
    env->SetByteArrayRegion(buffer, offset, want, reinterpret_cast<const jbyte*>(blob) + srcOffset);
    return want;
}

static jdouble columnDouble(JNIEnv* env, jclass, jlong stmtPtr, jint col) {
    throwIfInvalidColumn(env, stmtPtr, col);
    return sqlite3_column_double(stmt(stmtPtr), col);
}

static jint columnInt(JNIEnv* env, jclass, jlong stmtPtr, jint col) {
    throwIfInvalidColumn(env, stmtPtr, col);
    return sqlite3_column_int(stmt(stmtPtr), col);
}

static jlong columnLong(JNIEnv* env, jclass, jlong stmtPtr, jint col) {
    throwIfInvalidColumn(env, stmtPtr, col);
    return sqlite3_column_int64(stmt(stmtPtr), col);
}

static jstring columnText(JNIEnv* env, jclass, jlong stmtPtr, jint col) {
    throwIfInvalidColumn(env, stmtPtr, col);
    const jchar* text = static_cast<const jchar*>(sqlite3_column_text16(stmt(stmtPtr), col));
    if (text == nullptr) {
        return NULL;
    }
    size_t length = sqlite3_column_bytes16(stmt(stmtPtr), col) / sizeof(jchar);
    return env->NewString(text, length);
}

static const JNINativeMethod sStatementMethods[] =
{
    // Metadata
    { "nativeBindParameterCount", "(J)I", (void*) bindParameterCount },
    { "nativeBindParameterIndex", "(JLjava/lang/String;)I", (void*) bindParameterIndex },
    { "nativeBindParameterName", "(JI)Ljava/lang/String;", (void*) bindParameterName },

    // Operations on a statement
    { "nativeStep", "(JZ)I", (void*) step },
    { "nativeReset", "(JZ)V", (void*) reset },
    { "nativeClearBindings", "(J)V", (void*) clearBindings },

    // Methods that bind values to parameters
    { "nativeBindBlob", "(JI[BII)V", (void*) bindBlob },
    { "nativeBindDouble", "(JID)V", (void*) bindDouble },
    { "nativeBindInt", "(JII)V", (void*) bindInt },
    { "nativeBindLong", "(JIJ)V", (void*) bindLong },
    { "nativeBindNull", "(JI)V", (void*) bindNull },
    { "nativeBindText", "(JILjava/lang/String;)V", (void*) bindText },

    // Methods that return information about columns in a result row.
    { "nativeColumnCount", "(J)I", (void*) columnCount },
    { "nativeColumnType", "(JI)I", (void*) columnType },
    { "nativeColumnName", "(JI)Ljava/lang/String;", (void*) columnName },

    { "nativeColumnBytes", "(JI)I", (void*) columnBytes },

    { "nativeColumnBlob", "(JI)[B", (void*) columnBlob },
    { "nativeColumnBuffer", "(JI[BIII)I", (void*) columnBuffer },
    { "nativeColumnDouble", "(JI)D", (void*) columnDouble },
    { "nativeColumnInt", "(JI)I", (void*) columnInt },
    { "nativeColumnLong", "(JI)J", (void*) columnLong },
    { "nativeColumnText", "(JI)Ljava/lang/String;", (void*) columnText },
};

int register_android_database_SQLiteRawStatement(JNIEnv *env)
{
    RegisterMethodsOrDie(env, "android/database/sqlite/SQLiteRawStatement",
                         sStatementMethods, NELEM(sStatementMethods));
    emptyArray = MakeGlobalRefOrDie(env, env->NewByteArray(0));
    return 0;
}

} // namespace android
