/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "SQLiteConnection"

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>

#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/String16.h>
#include <cutils/ashmem.h>
#include <sys/mman.h>

#include <string.h>
#include <unistd.h>

#include <androidfw/CursorWindow.h>

#include <sqlite3.h>
#include <sqlite3_android.h>

#include "android_database_SQLiteCommon.h"

#include "core_jni_helpers.h"

// Set to 1 to use UTF16 storage for localized indexes.
#define UTF16_STORAGE 0

namespace android {

/* Busy timeout in milliseconds.
 * If another connection (possibly in another process) has the database locked for
 * longer than this amount of time then SQLite will generate a SQLITE_BUSY error.
 * The SQLITE_BUSY error is then raised as a SQLiteDatabaseLockedException.
 *
 * In ordinary usage, busy timeouts are quite rare.  Most databases only ever
 * have a single open connection at a time unless they are using WAL.  When using
 * WAL, a timeout could occur if one connection is busy performing an auto-checkpoint
 * operation.  The busy timeout needs to be long enough to tolerate slow I/O write
 * operations but not so long as to cause the application to hang indefinitely if
 * there is a problem acquiring a database lock.
 */
static const int BUSY_TIMEOUT_MS = 2500;

static struct {
    jmethodID apply;
} gUnaryOperator;

static struct {
    jmethodID apply;
} gBinaryOperator;

struct SQLiteConnection {
    // Open flags.
    // Must be kept in sync with the constants defined in SQLiteDatabase.java.
    enum {
        OPEN_READWRITE          = 0x00000000,
        OPEN_READONLY           = 0x00000001,
        OPEN_READ_MASK          = 0x00000001,
        NO_LOCALIZED_COLLATORS  = 0x00000010,
        CREATE_IF_NECESSARY     = 0x10000000,
    };

    sqlite3* const db;
    const int openFlags;
    const String8 path;
    const String8 label;

    volatile bool canceled;

    SQLiteConnection(sqlite3* db, int openFlags, const String8& path, const String8& label) :
        db(db), openFlags(openFlags), path(path), label(label), canceled(false) { }
};

// Called each time a statement begins execution, when tracing is enabled.
static void sqliteTraceCallback(void *data, const char *sql) {
    SQLiteConnection* connection = static_cast<SQLiteConnection*>(data);
    ALOG(LOG_VERBOSE, SQLITE_TRACE_TAG, "%s: \"%s\"\n",
            connection->label.string(), sql);
}

// Called each time a statement finishes execution, when profiling is enabled.
static void sqliteProfileCallback(void *data, const char *sql, sqlite3_uint64 tm) {
    SQLiteConnection* connection = static_cast<SQLiteConnection*>(data);
    ALOG(LOG_VERBOSE, SQLITE_PROFILE_TAG, "%s: \"%s\" took %0.3f ms\n",
            connection->label.string(), sql, tm * 0.000001f);
}

// Called after each SQLite VM instruction when cancelation is enabled.
static int sqliteProgressHandlerCallback(void* data) {
    SQLiteConnection* connection = static_cast<SQLiteConnection*>(data);
    return connection->canceled;
}


static jlong nativeOpen(JNIEnv* env, jclass clazz, jstring pathStr, jint openFlags,
        jstring labelStr, jboolean enableTrace, jboolean enableProfile, jint lookasideSz,
        jint lookasideCnt) {
    int sqliteFlags;
    if (openFlags & SQLiteConnection::CREATE_IF_NECESSARY) {
        sqliteFlags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE;
    } else if (openFlags & SQLiteConnection::OPEN_READONLY) {
        sqliteFlags = SQLITE_OPEN_READONLY;
    } else {
        sqliteFlags = SQLITE_OPEN_READWRITE;
    }

    const char* pathChars = env->GetStringUTFChars(pathStr, NULL);
    String8 path(pathChars);
    env->ReleaseStringUTFChars(pathStr, pathChars);

    const char* labelChars = env->GetStringUTFChars(labelStr, NULL);
    String8 label(labelChars);
    env->ReleaseStringUTFChars(labelStr, labelChars);

    sqlite3* db;
    int err = sqlite3_open_v2(path.string(), &db, sqliteFlags, NULL);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception_errcode(env, err, "Could not open database");
        return 0;
    }

    if (lookasideSz >= 0 && lookasideCnt >= 0) {
        int err = sqlite3_db_config(db, SQLITE_DBCONFIG_LOOKASIDE, NULL, lookasideSz, lookasideCnt);
        if (err != SQLITE_OK) {
            ALOGE("sqlite3_db_config(..., %d, %d) failed: %d", lookasideSz, lookasideCnt, err);
            throw_sqlite3_exception(env, db, "Cannot set lookaside");
            sqlite3_close(db);
            return 0;
        }
    }

    // Check that the database is really read/write when that is what we asked for.
    if ((sqliteFlags & SQLITE_OPEN_READWRITE) && sqlite3_db_readonly(db, NULL)) {
        throw_sqlite3_exception(env, db, "Could not open the database in read/write mode.");
        sqlite3_close(db);
        return 0;
    }

    // Set the default busy handler to retry automatically before returning SQLITE_BUSY.
    err = sqlite3_busy_timeout(db, BUSY_TIMEOUT_MS);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, db, "Could not set busy timeout");
        sqlite3_close(db);
        return 0;
    }

    // Register custom Android functions.
    err = register_android_functions(db, UTF16_STORAGE);
    if (err) {
        throw_sqlite3_exception(env, db, "Could not register Android SQL functions.");
        sqlite3_close(db);
        return 0;
    }

    // Create wrapper object.
    SQLiteConnection* connection = new SQLiteConnection(db, openFlags, path, label);

    // Enable tracing and profiling if requested.
    if (enableTrace) {
        sqlite3_trace(db, &sqliteTraceCallback, connection);
    }
    if (enableProfile) {
        sqlite3_profile(db, &sqliteProfileCallback, connection);
    }

    ALOGV("Opened connection %p with label '%s'", db, label.string());
    return reinterpret_cast<jlong>(connection);
}

static void nativeClose(JNIEnv* env, jclass clazz, jlong connectionPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    if (connection) {
        ALOGV("Closing connection %p", connection->db);
        int err = sqlite3_close(connection->db);
        if (err != SQLITE_OK) {
            // This can happen if sub-objects aren't closed first.  Make sure the caller knows.
            ALOGE("sqlite3_close(%p) failed: %d", connection->db, err);
            throw_sqlite3_exception(env, connection->db, "Count not close db.");
            return;
        }

        delete connection;
    }
}

static void sqliteCustomScalarFunctionCallback(sqlite3_context *context,
        int argc, sqlite3_value **argv) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject functionObjGlobal = reinterpret_cast<jobject>(sqlite3_user_data(context));
    ScopedLocalRef<jobject> functionObj(env, env->NewLocalRef(functionObjGlobal));
    ScopedLocalRef<jstring> argString(env,
            env->NewStringUTF(reinterpret_cast<const char*>(sqlite3_value_text(argv[0]))));
    ScopedLocalRef<jstring> resString(env,
            (jstring) env->CallObjectMethod(functionObj.get(), gUnaryOperator.apply, argString.get()));

    if (env->ExceptionCheck()) {
        ALOGE("Exception thrown by custom scalar function");
        sqlite3_result_error(context, "Exception thrown by custom scalar function", -1);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }

    if (resString.get() == nullptr) {
        sqlite3_result_null(context);
    } else {
        ScopedUtfChars res(env, resString.get());
        sqlite3_result_text(context, res.c_str(), -1, SQLITE_TRANSIENT);
    }
}

static void sqliteCustomScalarFunctionDestructor(void* data) {
    jobject functionObjGlobal = reinterpret_cast<jobject>(data);

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(functionObjGlobal);
}

static void nativeRegisterCustomScalarFunction(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jstring functionName, jobject functionObj) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    jobject functionObjGlobal = env->NewGlobalRef(functionObj);
    ScopedUtfChars functionNameChars(env, functionName);
    int err = sqlite3_create_function_v2(connection->db,
            functionNameChars.c_str(), 1, SQLITE_UTF8,
            reinterpret_cast<void*>(functionObjGlobal),
            &sqliteCustomScalarFunctionCallback,
            nullptr,
            nullptr,
            &sqliteCustomScalarFunctionDestructor);

    if (err != SQLITE_OK) {
        ALOGE("sqlite3_create_function returned %d", err);
        env->DeleteGlobalRef(functionObjGlobal);
        throw_sqlite3_exception(env, connection->db);
        return;
    }
}

static void sqliteCustomAggregateFunctionStep(sqlite3_context *context,
        int argc, sqlite3_value **argv) {
    char** agg = reinterpret_cast<char**>(
            sqlite3_aggregate_context(context, sizeof(const char**)));
    if (agg == nullptr) {
        return;
    } else if (*agg == nullptr) {
        // During our first call the best we can do is allocate our result
        // holder and populate it with our first value; we'll reduce it
        // against any additional values in future calls
        const char* res = reinterpret_cast<const char*>(sqlite3_value_text(argv[0]));
        if (res == nullptr) {
            *agg = nullptr;
        } else {
            *agg = strdup(res);
        }
        return;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject functionObjGlobal = reinterpret_cast<jobject>(sqlite3_user_data(context));
    ScopedLocalRef<jobject> functionObj(env, env->NewLocalRef(functionObjGlobal));
    ScopedLocalRef<jstring> arg0String(env,
            env->NewStringUTF(reinterpret_cast<const char*>(*agg)));
    ScopedLocalRef<jstring> arg1String(env,
            env->NewStringUTF(reinterpret_cast<const char*>(sqlite3_value_text(argv[0]))));
    ScopedLocalRef<jstring> resString(env,
            (jstring) env->CallObjectMethod(functionObj.get(), gBinaryOperator.apply,
                    arg0String.get(), arg1String.get()));

    if (env->ExceptionCheck()) {
        ALOGE("Exception thrown by custom aggregate function");
        sqlite3_result_error(context, "Exception thrown by custom aggregate function", -1);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }

    // One way or another, we have a new value to collect, and we need to
    // free our previous value
    if (*agg != nullptr) {
        free(*agg);
    }
    if (resString.get() == nullptr) {
        *agg = nullptr;
    } else {
        ScopedUtfChars res(env, resString.get());
        *agg = strdup(res.c_str());
    }
}

static void sqliteCustomAggregateFunctionFinal(sqlite3_context *context) {
    // We pass zero size here to avoid allocating for empty sets
    char** agg = reinterpret_cast<char**>(
            sqlite3_aggregate_context(context, 0));
    if (agg == nullptr) {
        return;
    } else if (*agg == nullptr) {
        sqlite3_result_null(context);
    } else {
        sqlite3_result_text(context, *agg, -1, SQLITE_TRANSIENT);
        free(*agg);
    }
}

static void sqliteCustomAggregateFunctionDestructor(void* data) {
    jobject functionObjGlobal = reinterpret_cast<jobject>(data);

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(functionObjGlobal);
}

static void nativeRegisterCustomAggregateFunction(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jstring functionName, jobject functionObj) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    jobject functionObjGlobal = env->NewGlobalRef(functionObj);
    ScopedUtfChars functionNameChars(env, functionName);
    int err = sqlite3_create_function_v2(connection->db,
            functionNameChars.c_str(), 1, SQLITE_UTF8,
            reinterpret_cast<void*>(functionObjGlobal),
            nullptr,
            &sqliteCustomAggregateFunctionStep,
            &sqliteCustomAggregateFunctionFinal,
            &sqliteCustomAggregateFunctionDestructor);

    if (err != SQLITE_OK) {
        ALOGE("sqlite3_create_function returned %d", err);
        env->DeleteGlobalRef(functionObjGlobal);
        throw_sqlite3_exception(env, connection->db);
        return;
    }
}

static void nativeRegisterLocalizedCollators(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jstring localeStr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    const char* locale = env->GetStringUTFChars(localeStr, NULL);
    int err = register_localized_collators(connection->db, locale, UTF16_STORAGE);
    env->ReleaseStringUTFChars(localeStr, locale);

    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db);
    }
}

static jlong nativePrepareStatement(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jstring sqlString) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    jsize sqlLength = env->GetStringLength(sqlString);
    const jchar* sql = env->GetStringCritical(sqlString, NULL);
    sqlite3_stmt* statement;
    int err = sqlite3_prepare16_v2(connection->db,
            sql, sqlLength * sizeof(jchar), &statement, NULL);
    env->ReleaseStringCritical(sqlString, sql);

    if (err != SQLITE_OK) {
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
        throw_sqlite3_exception(env, connection->db, message);
        free(message);
        return 0;
    }

    ALOGV("Prepared statement %p on connection %p", statement, connection->db);
    return reinterpret_cast<jlong>(statement);
}

static void nativeFinalizeStatement(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    // We ignore the result of sqlite3_finalize because it is really telling us about
    // whether any errors occurred while executing the statement.  The statement itself
    // is always finalized regardless.
    ALOGV("Finalized statement %p on connection %p", statement, connection->db);
    sqlite3_finalize(statement);
}

static jint nativeGetParameterCount(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    return sqlite3_bind_parameter_count(statement);
}

static jboolean nativeIsReadOnly(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    return sqlite3_stmt_readonly(statement) != 0;
}

static jint nativeGetColumnCount(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    return sqlite3_column_count(statement);
}

static jstring nativeGetColumnName(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index) {
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    const jchar* name = static_cast<const jchar*>(sqlite3_column_name16(statement, index));
    if (name) {
        size_t length = 0;
        while (name[length]) {
            length += 1;
        }
        return env->NewString(name, length);
    }
    return NULL;
}

static void nativeBindNull(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_null(statement, index);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static void nativeBindLong(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index, jlong value) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_int64(statement, index, value);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static void nativeBindDouble(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index, jdouble value) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_double(statement, index, value);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static void nativeBindString(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index, jstring valueString) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    jsize valueLength = env->GetStringLength(valueString);
    const jchar* value = env->GetStringCritical(valueString, NULL);
    int err = sqlite3_bind_text16(statement, index, value, valueLength * sizeof(jchar),
            SQLITE_TRANSIENT);
    env->ReleaseStringCritical(valueString, value);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static void nativeBindBlob(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index, jbyteArray valueArray) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    jsize valueLength = env->GetArrayLength(valueArray);
    jbyte* value = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(valueArray, NULL));
    int err = sqlite3_bind_blob(statement, index, value, valueLength, SQLITE_TRANSIENT);
    env->ReleasePrimitiveArrayCritical(valueArray, value, JNI_ABORT);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static void nativeResetStatementAndClearBindings(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_reset(statement);
    if (err == SQLITE_OK) {
        err = sqlite3_clear_bindings(statement);
    }
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static int executeNonQuery(JNIEnv* env, SQLiteConnection* connection, sqlite3_stmt* statement) {
    int err = sqlite3_step(statement);
    if (err == SQLITE_ROW) {
        throw_sqlite3_exception(env,
                "Queries can be performed using SQLiteDatabase query or rawQuery methods only.");
    } else if (err != SQLITE_DONE) {
        throw_sqlite3_exception(env, connection->db);
    }
    return err;
}

static void nativeExecute(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    executeNonQuery(env, connection, statement);
}

static jint nativeExecuteForChangedRowCount(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeNonQuery(env, connection, statement);
    return err == SQLITE_DONE ? sqlite3_changes(connection->db) : -1;
}

static jlong nativeExecuteForLastInsertedRowId(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeNonQuery(env, connection, statement);
    return err == SQLITE_DONE && sqlite3_changes(connection->db) > 0
            ? sqlite3_last_insert_rowid(connection->db) : -1;
}

static int executeOneRowQuery(JNIEnv* env, SQLiteConnection* connection, sqlite3_stmt* statement) {
    int err = sqlite3_step(statement);
    if (err != SQLITE_ROW) {
        throw_sqlite3_exception(env, connection->db);
    }
    return err;
}

static jlong nativeExecuteForLong(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeOneRowQuery(env, connection, statement);
    if (err == SQLITE_ROW && sqlite3_column_count(statement) >= 1) {
        return sqlite3_column_int64(statement, 0);
    }
    return -1;
}

static jstring nativeExecuteForString(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeOneRowQuery(env, connection, statement);
    if (err == SQLITE_ROW && sqlite3_column_count(statement) >= 1) {
        const jchar* text = static_cast<const jchar*>(sqlite3_column_text16(statement, 0));
        if (text) {
            size_t length = sqlite3_column_bytes16(statement, 0) / sizeof(jchar);
            return env->NewString(text, length);
        }
    }
    return NULL;
}

static int createAshmemRegionWithData(JNIEnv* env, const void* data, size_t length) {
    int error = 0;
    int fd = ashmem_create_region(NULL, length);
    if (fd < 0) {
        error = errno;
        ALOGE("ashmem_create_region failed: %s", strerror(error));
    } else {
        if (length > 0) {
            void* ptr = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (ptr == MAP_FAILED) {
                error = errno;
                ALOGE("mmap failed: %s", strerror(error));
            } else {
                memcpy(ptr, data, length);
                munmap(ptr, length);
            }
        }

        if (!error) {
            if (ashmem_set_prot_region(fd, PROT_READ) < 0) {
                error = errno;
                ALOGE("ashmem_set_prot_region failed: %s", strerror(errno));
            } else {
                return fd;
            }
        }

        close(fd);
    }

    jniThrowIOException(env, error);
    return -1;
}

static jint nativeExecuteForBlobFileDescriptor(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeOneRowQuery(env, connection, statement);
    if (err == SQLITE_ROW && sqlite3_column_count(statement) >= 1) {
        const void* blob = sqlite3_column_blob(statement, 0);
        if (blob) {
            int length = sqlite3_column_bytes(statement, 0);
            if (length >= 0) {
                return createAshmemRegionWithData(env, blob, length);
            }
        }
    }
    return -1;
}

enum CopyRowResult {
    CPR_OK,
    CPR_FULL,
    CPR_ERROR,
};

static CopyRowResult copyRow(JNIEnv* env, CursorWindow* window,
        sqlite3_stmt* statement, int numColumns, int startPos, int addedRows) {
    // Allocate a new field directory for the row.
    status_t status = window->allocRow();
    if (status) {
        LOG_WINDOW("Failed allocating fieldDir at startPos %d row %d, error=%d",
                startPos, addedRows, status);
        return CPR_FULL;
    }

    // Pack the row into the window.
    CopyRowResult result = CPR_OK;
    for (int i = 0; i < numColumns; i++) {
        int type = sqlite3_column_type(statement, i);
        if (type == SQLITE_TEXT) {
            // TEXT data
            const char* text = reinterpret_cast<const char*>(
                    sqlite3_column_text(statement, i));
            // SQLite does not include the NULL terminator in size, but does
            // ensure all strings are NULL terminated, so increase size by
            // one to make sure we store the terminator.
            size_t sizeIncludingNull = sqlite3_column_bytes(statement, i) + 1;
            status = window->putString(addedRows, i, text, sizeIncludingNull);
            if (status) {
                LOG_WINDOW("Failed allocating %zu bytes for text at %d,%d, error=%d",
                        sizeIncludingNull, startPos + addedRows, i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is TEXT with %zu bytes",
                    startPos + addedRows, i, sizeIncludingNull);
        } else if (type == SQLITE_INTEGER) {
            // INTEGER data
            int64_t value = sqlite3_column_int64(statement, i);
            status = window->putLong(addedRows, i, value);
            if (status) {
                LOG_WINDOW("Failed allocating space for a long in column %d, error=%d",
                        i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is INTEGER %" PRId64, startPos + addedRows, i, value);
        } else if (type == SQLITE_FLOAT) {
            // FLOAT data
            double value = sqlite3_column_double(statement, i);
            status = window->putDouble(addedRows, i, value);
            if (status) {
                LOG_WINDOW("Failed allocating space for a double in column %d, error=%d",
                        i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is FLOAT %lf", startPos + addedRows, i, value);
        } else if (type == SQLITE_BLOB) {
            // BLOB data
            const void* blob = sqlite3_column_blob(statement, i);
            size_t size = sqlite3_column_bytes(statement, i);
            status = window->putBlob(addedRows, i, blob, size);
            if (status) {
                LOG_WINDOW("Failed allocating %zu bytes for blob at %d,%d, error=%d",
                        size, startPos + addedRows, i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is Blob with %zu bytes",
                    startPos + addedRows, i, size);
        } else if (type == SQLITE_NULL) {
            // NULL field
            status = window->putNull(addedRows, i);
            if (status) {
                LOG_WINDOW("Failed allocating space for a null in column %d, error=%d",
                        i, status);
                result = CPR_FULL;
                break;
            }

            LOG_WINDOW("%d,%d is NULL", startPos + addedRows, i);
        } else {
            // Unknown data
            ALOGE("Unknown column type when filling database window");
            throw_sqlite3_exception(env, "Unknown column type when filling window");
            result = CPR_ERROR;
            break;
        }
    }

    // Free the last row if if was not successfully copied.
    if (result != CPR_OK) {
        window->freeLastRow();
    }
    return result;
}

static jlong nativeExecuteForCursorWindow(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr, jlong windowPtr,
        jint startPos, jint requiredPos, jboolean countAllRows) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);

    status_t status = window->clear();
    if (status) {
        String8 msg;
        msg.appendFormat("Failed to clear the cursor window, status=%d", status);
        throw_sqlite3_exception(env, connection->db, msg.string());
        return 0;
    }

    int numColumns = sqlite3_column_count(statement);
    status = window->setNumColumns(numColumns);
    if (status) {
        String8 msg;
        msg.appendFormat("Failed to set the cursor window column count to %d, status=%d",
                numColumns, status);
        throw_sqlite3_exception(env, connection->db, msg.string());
        return 0;
    }

    int retryCount = 0;
    int totalRows = 0;
    int addedRows = 0;
    bool windowFull = false;
    bool gotException = false;
    while (!gotException && (!windowFull || countAllRows)) {
        int err = sqlite3_step(statement);
        if (err == SQLITE_ROW) {
            LOG_WINDOW("Stepped statement %p to row %d", statement, totalRows);
            retryCount = 0;
            totalRows += 1;

            // Skip the row if the window is full or we haven't reached the start position yet.
            if (startPos >= totalRows || windowFull) {
                continue;
            }

            CopyRowResult cpr = copyRow(env, window, statement, numColumns, startPos, addedRows);
            if (cpr == CPR_FULL && addedRows && startPos + addedRows <= requiredPos) {
                // We filled the window before we got to the one row that we really wanted.
                // Clear the window and start filling it again from here.
                // TODO: Would be nicer if we could progressively replace earlier rows.
                window->clear();
                window->setNumColumns(numColumns);
                startPos += addedRows;
                addedRows = 0;
                cpr = copyRow(env, window, statement, numColumns, startPos, addedRows);
            }

            if (cpr == CPR_OK) {
                addedRows += 1;
            } else if (cpr == CPR_FULL) {
                windowFull = true;
            } else {
                gotException = true;
            }
        } else if (err == SQLITE_DONE) {
            // All rows processed, bail
            LOG_WINDOW("Processed all rows");
            break;
        } else if (err == SQLITE_LOCKED || err == SQLITE_BUSY) {
            // The table is locked, retry
            LOG_WINDOW("Database locked, retrying");
            if (retryCount > 50) {
                ALOGE("Bailing on database busy retry");
                throw_sqlite3_exception(env, connection->db, "retrycount exceeded");
                gotException = true;
            } else {
                // Sleep to give the thread holding the lock a chance to finish
                usleep(1000);
                retryCount++;
            }
        } else {
            throw_sqlite3_exception(env, connection->db);
            gotException = true;
        }
    }

    LOG_WINDOW("Resetting statement %p after fetching %d rows and adding %d rows "
            "to the window in %zu bytes",
            statement, totalRows, addedRows, window->size() - window->freeSpace());
    sqlite3_reset(statement);

    // Report the total number of rows on request.
    if (startPos > totalRows) {
        ALOGE("startPos %d > actual rows %d", startPos, totalRows);
    }
    if (totalRows > 0 && addedRows == 0) {
        String8 msg;
        msg.appendFormat("Row too big to fit into CursorWindow requiredPos=%d, totalRows=%d",
                requiredPos, totalRows);
        throw_sqlite3_exception(env, SQLITE_TOOBIG, NULL, msg.string());
        return 0;
    }

    jlong result = jlong(startPos) << 32 | jlong(totalRows);
    return result;
}

static jint nativeGetDbLookaside(JNIEnv* env, jobject clazz, jlong connectionPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    int cur = -1;
    int unused;
    sqlite3_db_status(connection->db, SQLITE_DBSTATUS_LOOKASIDE_USED, &cur, &unused, 0);
    return cur;
}

static void nativeCancel(JNIEnv* env, jobject clazz, jlong connectionPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    connection->canceled = true;
}

static void nativeResetCancel(JNIEnv* env, jobject clazz, jlong connectionPtr,
        jboolean cancelable) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    connection->canceled = false;

    if (cancelable) {
        sqlite3_progress_handler(connection->db, 4, sqliteProgressHandlerCallback,
                connection);
    } else {
        sqlite3_progress_handler(connection->db, 0, NULL, NULL);
    }
}


static const JNINativeMethod sMethods[] =
{
    /* name, signature, funcPtr */
    { "nativeOpen", "(Ljava/lang/String;ILjava/lang/String;ZZII)J",
            (void*)nativeOpen },
    { "nativeClose", "(J)V",
            (void*)nativeClose },
    { "nativeRegisterCustomScalarFunction", "(JLjava/lang/String;Ljava/util/function/UnaryOperator;)V",
            (void*)nativeRegisterCustomScalarFunction },
    { "nativeRegisterCustomAggregateFunction", "(JLjava/lang/String;Ljava/util/function/BinaryOperator;)V",
            (void*)nativeRegisterCustomAggregateFunction },
    { "nativeRegisterLocalizedCollators", "(JLjava/lang/String;)V",
            (void*)nativeRegisterLocalizedCollators },
    { "nativePrepareStatement", "(JLjava/lang/String;)J",
            (void*)nativePrepareStatement },
    { "nativeFinalizeStatement", "(JJ)V",
            (void*)nativeFinalizeStatement },
    { "nativeGetParameterCount", "(JJ)I",
            (void*)nativeGetParameterCount },
    { "nativeIsReadOnly", "(JJ)Z",
            (void*)nativeIsReadOnly },
    { "nativeGetColumnCount", "(JJ)I",
            (void*)nativeGetColumnCount },
    { "nativeGetColumnName", "(JJI)Ljava/lang/String;",
            (void*)nativeGetColumnName },
    { "nativeBindNull", "(JJI)V",
            (void*)nativeBindNull },
    { "nativeBindLong", "(JJIJ)V",
            (void*)nativeBindLong },
    { "nativeBindDouble", "(JJID)V",
            (void*)nativeBindDouble },
    { "nativeBindString", "(JJILjava/lang/String;)V",
            (void*)nativeBindString },
    { "nativeBindBlob", "(JJI[B)V",
            (void*)nativeBindBlob },
    { "nativeResetStatementAndClearBindings", "(JJ)V",
            (void*)nativeResetStatementAndClearBindings },
    { "nativeExecute", "(JJ)V",
            (void*)nativeExecute },
    { "nativeExecuteForLong", "(JJ)J",
            (void*)nativeExecuteForLong },
    { "nativeExecuteForString", "(JJ)Ljava/lang/String;",
            (void*)nativeExecuteForString },
    { "nativeExecuteForBlobFileDescriptor", "(JJ)I",
            (void*)nativeExecuteForBlobFileDescriptor },
    { "nativeExecuteForChangedRowCount", "(JJ)I",
            (void*)nativeExecuteForChangedRowCount },
    { "nativeExecuteForLastInsertedRowId", "(JJ)J",
            (void*)nativeExecuteForLastInsertedRowId },
    { "nativeExecuteForCursorWindow", "(JJJIIZ)J",
            (void*)nativeExecuteForCursorWindow },
    { "nativeGetDbLookaside", "(J)I",
            (void*)nativeGetDbLookaside },
    { "nativeCancel", "(J)V",
            (void*)nativeCancel },
    { "nativeResetCancel", "(JZ)V",
            (void*)nativeResetCancel },
};

int register_android_database_SQLiteConnection(JNIEnv *env)
{
    jclass unaryClazz = FindClassOrDie(env, "java/util/function/UnaryOperator");
    gUnaryOperator.apply = GetMethodIDOrDie(env, unaryClazz,
            "apply", "(Ljava/lang/Object;)Ljava/lang/Object;");

    jclass binaryClazz = FindClassOrDie(env, "java/util/function/BinaryOperator");
    gBinaryOperator.apply = GetMethodIDOrDie(env, binaryClazz,
            "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    return RegisterMethodsOrDie(env, "android/database/sqlite/SQLiteConnection", sMethods,
                                NELEM(sMethods));
}

} // namespace android
