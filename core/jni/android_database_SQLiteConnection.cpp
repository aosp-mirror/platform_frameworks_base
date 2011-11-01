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
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/String16.h>
#include <cutils/ashmem.h>
#include <sys/mman.h>

#include <string.h>
#include <unistd.h>

#include "binder/CursorWindow.h"

#include <sqlite3.h>
#include <sqlite3_android.h>

#include "android_database_SQLiteCommon.h"

#define UTF16_STORAGE 0
#define ANDROID_TABLE "android_metadata"

namespace android {

static struct {
    jfieldID name;
    jfieldID numArgs;
    jmethodID dispatchCallback;
} gSQLiteCustomFunctionClassInfo;

static struct {
    jclass clazz;
} gStringClassInfo;

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

    SQLiteConnection(sqlite3* db, int openFlags, const String8& path, const String8& label) :
        db(db), openFlags(openFlags), path(path), label(label) { }
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


static jint nativeOpen(JNIEnv* env, jclass clazz, jstring pathStr, jint openFlags,
        jstring labelStr, jboolean enableTrace, jboolean enableProfile) {
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

    // Set the default busy handler to retry for 1000ms and then return SQLITE_BUSY
    err = sqlite3_busy_timeout(db, 1000 /* ms */);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, db, "Could not set busy timeout");
        sqlite3_close(db);
        return 0;
    }

    // Enable WAL auto-checkpointing after a commit whenever at least one frame is in the log.
    // This ensures that a checkpoint will occur after each transaction if needed.
    err = sqlite3_wal_autocheckpoint(db, 1);
    if (err) {
        throw_sqlite3_exception(env, db, "Could not enable auto-checkpointing.");
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
    return reinterpret_cast<jint>(connection);
}

static void nativeClose(JNIEnv* env, jclass clazz, jint connectionPtr) {
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

// Called each time a custom function is evaluated.
static void sqliteCustomFunctionCallback(sqlite3_context *context,
        int argc, sqlite3_value **argv) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    // Get the callback function object.
    // Create a new local reference to it in case the callback tries to do something
    // dumb like unregister the function (thereby destroying the global ref) while it is running.
    jobject functionObjGlobal = reinterpret_cast<jobject>(sqlite3_user_data(context));
    jobject functionObj = env->NewLocalRef(functionObjGlobal);

    jobjectArray argsArray = env->NewObjectArray(argc, gStringClassInfo.clazz, NULL);
    if (argsArray) {
        for (int i = 0; i < argc; i++) {
            const jchar* arg = static_cast<const jchar*>(sqlite3_value_text16(argv[i]));
            if (!arg) {
                ALOGW("NULL argument in custom_function_callback.  This should not happen.");
            } else {
                size_t argLen = sqlite3_value_bytes16(argv[i]) / sizeof(jchar);
                jstring argStr = env->NewString(arg, argLen);
                if (!argStr) {
                    goto error; // out of memory error
                }
                env->SetObjectArrayElement(argsArray, i, argStr);
                env->DeleteLocalRef(argStr);
            }
        }

        // TODO: Support functions that return values.
        env->CallVoidMethod(functionObj,
                gSQLiteCustomFunctionClassInfo.dispatchCallback, argsArray);

error:
        env->DeleteLocalRef(argsArray);
    }

    env->DeleteLocalRef(functionObj);

    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by custom SQLite function.");
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

// Called when a custom function is destroyed.
static void sqliteCustomFunctionDestructor(void* data) {
    jobject functionObjGlobal = reinterpret_cast<jobject>(data);

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(functionObjGlobal);
}

static void nativeRegisterCustomFunction(JNIEnv* env, jclass clazz, jint connectionPtr,
        jobject functionObj) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    jstring nameStr = jstring(env->GetObjectField(
            functionObj, gSQLiteCustomFunctionClassInfo.name));
    jint numArgs = env->GetIntField(functionObj, gSQLiteCustomFunctionClassInfo.numArgs);

    jobject functionObjGlobal = env->NewGlobalRef(functionObj);

    const char* name = env->GetStringUTFChars(nameStr, NULL);
    int err = sqlite3_create_function_v2(connection->db, name, numArgs, SQLITE_UTF16,
            reinterpret_cast<void*>(functionObjGlobal),
            &sqliteCustomFunctionCallback, NULL, NULL, &sqliteCustomFunctionDestructor);
    env->ReleaseStringUTFChars(nameStr, name);

    if (err != SQLITE_OK) {
        ALOGE("sqlite3_create_function returned %d", err);
        env->DeleteGlobalRef(functionObjGlobal);
        throw_sqlite3_exception(env, connection->db);
        return;
    }
}

// Set locale in the android_metadata table, install localized collators, and rebuild indexes
static void nativeSetLocale(JNIEnv* env, jclass clazz, jint connectionPtr, jstring localeStr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    if (connection->openFlags & SQLiteConnection::NO_LOCALIZED_COLLATORS) {
        // We should probably throw IllegalStateException but the contract for
        // setLocale says that we just do nothing.  Oh well.
        return;
    }

    int err;
    char const* locale = env->GetStringUTFChars(localeStr, NULL);
    sqlite3_stmt* stmt = NULL;
    char** meta = NULL;
    int rowCount, colCount;
    char* dbLocale = NULL;

    // create the table, if necessary and possible
    if (!(connection->openFlags & SQLiteConnection::OPEN_READONLY)) {
        err = sqlite3_exec(connection->db,
                "CREATE TABLE IF NOT EXISTS " ANDROID_TABLE " (locale TEXT)",
                NULL, NULL, NULL);
        if (err != SQLITE_OK) {
            ALOGE("CREATE TABLE " ANDROID_TABLE " failed");
            throw_sqlite3_exception(env, connection->db);
            goto done;
        }
    }

    // try to read from the table
    err = sqlite3_get_table(connection->db,
            "SELECT locale FROM " ANDROID_TABLE " LIMIT 1",
            &meta, &rowCount, &colCount, NULL);
    if (err != SQLITE_OK) {
        ALOGE("SELECT locale FROM " ANDROID_TABLE " failed");
        throw_sqlite3_exception(env, connection->db);
        goto done;
    }

    dbLocale = (rowCount >= 1) ? meta[colCount] : NULL;

    if (dbLocale != NULL && !strcmp(dbLocale, locale)) {
        // database locale is the same as the desired locale; set up the collators and go
        err = register_localized_collators(connection->db, locale, UTF16_STORAGE);
        if (err != SQLITE_OK) {
            throw_sqlite3_exception(env, connection->db);
        }
        goto done;   // no database changes needed
    }

    if (connection->openFlags & SQLiteConnection::OPEN_READONLY) {
        // read-only database, so we're going to have to put up with whatever we got
        // For registering new index. Not for modifing the read-only database.
        err = register_localized_collators(connection->db, locale, UTF16_STORAGE);
        if (err != SQLITE_OK) {
            throw_sqlite3_exception(env, connection->db);
        }
        goto done;
    }

    // need to update android_metadata and indexes atomically, so use a transaction...
    err = sqlite3_exec(connection->db, "BEGIN TRANSACTION", NULL, NULL, NULL);
    if (err != SQLITE_OK) {
        ALOGE("BEGIN TRANSACTION failed setting locale");
        throw_sqlite3_exception(env, connection->db);
        goto done;
    }

    err = register_localized_collators(connection->db, locale, UTF16_STORAGE);
    if (err != SQLITE_OK) {
        ALOGE("register_localized_collators() failed setting locale");
        throw_sqlite3_exception(env, connection->db);
        goto rollback;
    }

    err = sqlite3_exec(connection->db, "DELETE FROM " ANDROID_TABLE, NULL, NULL, NULL);
    if (err != SQLITE_OK) {
        ALOGE("DELETE failed setting locale");
        throw_sqlite3_exception(env, connection->db);
        goto rollback;
    }

    static const char *sql = "INSERT INTO " ANDROID_TABLE " (locale) VALUES(?);";
    err = sqlite3_prepare_v2(connection->db, sql, -1, &stmt, NULL);
    if (err != SQLITE_OK) {
        ALOGE("sqlite3_prepare_v2(\"%s\") failed", sql);
        throw_sqlite3_exception(env, connection->db);
        goto rollback;
    }

    err = sqlite3_bind_text(stmt, 1, locale, -1, SQLITE_TRANSIENT);
    if (err != SQLITE_OK) {
        ALOGE("sqlite3_bind_text() failed setting locale");
        throw_sqlite3_exception(env, connection->db);
        goto rollback;
    }

    err = sqlite3_step(stmt);
    if (err != SQLITE_OK && err != SQLITE_DONE) {
        ALOGE("sqlite3_step(\"%s\") failed setting locale", sql);
        throw_sqlite3_exception(env, connection->db);
        goto rollback;
    }

    err = sqlite3_exec(connection->db, "REINDEX LOCALIZED", NULL, NULL, NULL);
    if (err != SQLITE_OK) {
        ALOGE("REINDEX LOCALIZED failed");
        throw_sqlite3_exception(env, connection->db);
        goto rollback;
    }

    // all done, yay!
    err = sqlite3_exec(connection->db, "COMMIT TRANSACTION", NULL, NULL, NULL);
    if (err != SQLITE_OK) {
        ALOGE("COMMIT TRANSACTION failed setting locale");
        throw_sqlite3_exception(env, connection->db);
        goto done;
    }

rollback:
    if (err != SQLITE_OK) {
        sqlite3_exec(connection->db, "ROLLBACK TRANSACTION", NULL, NULL, NULL);
    }

done:
    if (stmt) {
        sqlite3_finalize(stmt);
    }
    if (meta) {
        sqlite3_free_table(meta);
    }
    if (locale) {
        env->ReleaseStringUTFChars(localeStr, locale);
    }
}

static jint nativePrepareStatement(JNIEnv* env, jclass clazz, jint connectionPtr,
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
    return reinterpret_cast<jint>(statement);
}

static void nativeFinalizeStatement(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    ALOGV("Finalized statement %p on connection %p", statement, connection->db);
    int err = sqlite3_finalize(statement);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static jint nativeGetParameterCount(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    return sqlite3_bind_parameter_count(statement);
}

static jboolean nativeIsReadOnly(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    return sqlite3_stmt_readonly(statement) != 0;
}

static jint nativeGetColumnCount(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    return sqlite3_column_count(statement);
}

static jstring nativeGetColumnName(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr, jint index) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
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

static void nativeBindNull(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr, jint index) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_null(statement, index);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static void nativeBindLong(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr, jint index, jlong value) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_int64(statement, index, value);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static void nativeBindDouble(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr, jint index, jdouble value) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_double(statement, index, value);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, connection->db, NULL);
    }
}

static void nativeBindString(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr, jint index, jstring valueString) {
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

static void nativeBindBlob(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr, jint index, jbyteArray valueArray) {
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

static void nativeResetStatementAndClearBindings(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr) {
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
        throw_sqlite3_exception_errcode(env, err, sqlite3_errmsg(connection->db));
    }
    return err;
}

static void nativeExecute(JNIEnv* env, jclass clazz, jint connectionPtr,
        jint statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    executeNonQuery(env, connection, statement);
}

static jint nativeExecuteForChangedRowCount(JNIEnv* env, jclass clazz,
        jint connectionPtr, jint statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeNonQuery(env, connection, statement);
    return err == SQLITE_DONE ? sqlite3_changes(connection->db) : -1;
}

static jlong nativeExecuteForLastInsertedRowId(JNIEnv* env, jclass clazz,
        jint connectionPtr, jint statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeNonQuery(env, connection, statement);
    return err == SQLITE_DONE && sqlite3_changes(connection->db) > 0
            ? sqlite3_last_insert_rowid(connection->db) : -1;
}

static int executeOneRowQuery(JNIEnv* env, SQLiteConnection* connection, sqlite3_stmt* statement) {
    int err = sqlite3_step(statement);
    if (err != SQLITE_ROW) {
        throw_sqlite3_exception_errcode(env, err, sqlite3_errmsg(connection->db));
    }
    return err;
}

static jlong nativeExecuteForLong(JNIEnv* env, jclass clazz,
        jint connectionPtr, jint statementPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeOneRowQuery(env, connection, statement);
    if (err == SQLITE_ROW && sqlite3_column_count(statement) >= 1) {
        return sqlite3_column_int64(statement, 0);
    }
    return -1;
}

static jstring nativeExecuteForString(JNIEnv* env, jclass clazz,
        jint connectionPtr, jint statementPtr) {
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
        jint connectionPtr, jint statementPtr) {
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
                LOG_WINDOW("Failed allocating %u bytes for text at %d,%d, error=%d",
                        sizeIncludingNull, startPos + addedRows, i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is TEXT with %u bytes",
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
            LOG_WINDOW("%d,%d is INTEGER 0x%016llx", startPos + addedRows, i, value);
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
                LOG_WINDOW("Failed allocating %u bytes for blob at %d,%d, error=%d",
                        size, startPos + addedRows, i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is Blob with %u bytes",
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
        jint connectionPtr, jint statementPtr, jint windowPtr,
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
            if (cpr == CPR_FULL && addedRows && startPos + addedRows < requiredPos) {
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

    LOG_WINDOW("Resetting statement %p after fetching %d rows and adding %d rows"
            "to the window in %d bytes",
            statement, totalRows, addedRows, window->size() - window->freeSpace());
    sqlite3_reset(statement);

    // Report the total number of rows on request.
    if (startPos > totalRows) {
        ALOGE("startPos %d > actual rows %d", startPos, totalRows);
    }
    jlong result = jlong(startPos) << 32 | jlong(totalRows);
    return result;
}

static jint nativeGetDbLookaside(JNIEnv* env, jobject clazz, jint connectionPtr) {
    SQLiteConnection* connection = reinterpret_cast<SQLiteConnection*>(connectionPtr);

    int cur = -1;
    int unused;
    sqlite3_db_status(connection->db, SQLITE_DBSTATUS_LOOKASIDE_USED, &cur, &unused, 0);
    return cur;
}


static JNINativeMethod sMethods[] =
{
    /* name, signature, funcPtr */
    { "nativeOpen", "(Ljava/lang/String;ILjava/lang/String;ZZ)I",
            (void*)nativeOpen },
    { "nativeClose", "(I)V",
            (void*)nativeClose },
    { "nativeRegisterCustomFunction", "(ILandroid/database/sqlite/SQLiteCustomFunction;)V",
            (void*)nativeRegisterCustomFunction },
    { "nativeSetLocale", "(ILjava/lang/String;)V",
            (void*)nativeSetLocale },
    { "nativePrepareStatement", "(ILjava/lang/String;)I",
            (void*)nativePrepareStatement },
    { "nativeFinalizeStatement", "(II)V",
            (void*)nativeFinalizeStatement },
    { "nativeGetParameterCount", "(II)I",
            (void*)nativeGetParameterCount },
    { "nativeIsReadOnly", "(II)Z",
            (void*)nativeIsReadOnly },
    { "nativeGetColumnCount", "(II)I",
            (void*)nativeGetColumnCount },
    { "nativeGetColumnName", "(III)Ljava/lang/String;",
            (void*)nativeGetColumnName },
    { "nativeBindNull", "(III)V",
            (void*)nativeBindNull },
    { "nativeBindLong", "(IIIJ)V",
            (void*)nativeBindLong },
    { "nativeBindDouble", "(IIID)V",
            (void*)nativeBindDouble },
    { "nativeBindString", "(IIILjava/lang/String;)V",
            (void*)nativeBindString },
    { "nativeBindBlob", "(III[B)V",
            (void*)nativeBindBlob },
    { "nativeResetStatementAndClearBindings", "(II)V",
            (void*)nativeResetStatementAndClearBindings },
    { "nativeExecute", "(II)V",
            (void*)nativeExecute },
    { "nativeExecuteForLong", "(II)J",
            (void*)nativeExecuteForLong },
    { "nativeExecuteForString", "(II)Ljava/lang/String;",
            (void*)nativeExecuteForString },
    { "nativeExecuteForBlobFileDescriptor", "(II)I",
            (void*)nativeExecuteForBlobFileDescriptor },
    { "nativeExecuteForChangedRowCount", "(II)I",
            (void*)nativeExecuteForChangedRowCount },
    { "nativeExecuteForLastInsertedRowId", "(II)J",
            (void*)nativeExecuteForLastInsertedRowId },
    { "nativeExecuteForCursorWindow", "(IIIIIZ)J",
            (void*)nativeExecuteForCursorWindow },
    { "nativeGetDbLookaside", "(I)I",
            (void*)nativeGetDbLookaside },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_database_SQLiteConnection(JNIEnv *env)
{
    jclass clazz;
    FIND_CLASS(clazz, "android/database/sqlite/SQLiteCustomFunction");

    GET_FIELD_ID(gSQLiteCustomFunctionClassInfo.name, clazz,
            "name", "Ljava/lang/String;");
    GET_FIELD_ID(gSQLiteCustomFunctionClassInfo.numArgs, clazz,
            "numArgs", "I");
    GET_METHOD_ID(gSQLiteCustomFunctionClassInfo.dispatchCallback,
            clazz, "dispatchCallback", "([Ljava/lang/String;)V");

    FIND_CLASS(clazz, "java/lang/String");
    gStringClassInfo.clazz = jclass(env->NewGlobalRef(clazz));

    return AndroidRuntime::registerNativeMethods(env, "android/database/sqlite/SQLiteConnection",
            sMethods, NELEM(sMethods));
}

} // namespace android
