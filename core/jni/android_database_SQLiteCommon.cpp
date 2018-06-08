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

#include "android_database_SQLiteCommon.h"

#include <utils/String8.h>

#include <map>

namespace android {

static const std::map<int, std::string> sErrorCodesMap = {
    // Primary Result Code List
    {4,     "SQLITE_ABORT"},
    {23,    "SQLITE_AUTH"},
    {5,     "SQLITE_BUSY"},
    {14,    "SQLITE_CANTOPEN"},
    {19,    "SQLITE_CONSTRAINT"},
    {11,    "SQLITE_CORRUPT"},
    {101,   "SQLITE_DONE"},
    {16,    "SQLITE_EMPTY"},
    {1,     "SQLITE_ERROR"},
    {24,    "SQLITE_FORMAT"},
    {13,    "SQLITE_FULL"},
    {2,     "SQLITE_INTERNAL"},
    {9,     "SQLITE_INTERRUPT"},
    {10,    "SQLITE_IOERR"},
    {6,     "SQLITE_LOCKED"},
    {20,    "SQLITE_MISMATCH"},
    {21,    "SQLITE_MISUSE"},
    {22,    "SQLITE_NOLFS"},
    {7,     "SQLITE_NOMEM"},
    {26,    "SQLITE_NOTADB"},
    {12,    "SQLITE_NOTFOUND"},
    {27,    "SQLITE_NOTICE"},
    {0,     "SQLITE_OK"},
    {3,     "SQLITE_PERM"},
    {15,    "SQLITE_PROTOCOL"},
    {25,    "SQLITE_RANGE"},
    {8,     "SQLITE_READONLY"},
    {100,   "SQLITE_ROW"},
    {17,    "SQLITE_SCHEMA"},
    {18,    "SQLITE_TOOBIG"},
    {28,    "SQLITE_WARNING"},
    // Extended Result Code List
    {516,   "SQLITE_ABORT_ROLLBACK"},
    {261,   "SQLITE_BUSY_RECOVERY"},
    {517,   "SQLITE_BUSY_SNAPSHOT"},
    {1038,  "SQLITE_CANTOPEN_CONVPATH"},
    {782,   "SQLITE_CANTOPEN_FULLPATH"},
    {526,   "SQLITE_CANTOPEN_ISDIR"},
    {270,   "SQLITE_CANTOPEN_NOTEMPDIR"},
    {275,   "SQLITE_CONSTRAINT_CHECK"},
    {531,   "SQLITE_CONSTRAINT_COMMITHOOK"},
    {787,   "SQLITE_CONSTRAINT_FOREIGNKEY"},
    {1043,  "SQLITE_CONSTRAINT_FUNCTION"},
    {1299,  "SQLITE_CONSTRAINT_NOTNULL"},
    {1555,  "SQLITE_CONSTRAINT_PRIMARYKEY"},
    {2579,  "SQLITE_CONSTRAINT_ROWID"},
    {1811,  "SQLITE_CONSTRAINT_TRIGGER"},
    {2067,  "SQLITE_CONSTRAINT_UNIQUE"},
    {2323,  "SQLITE_CONSTRAINT_VTAB"},
    {267,   "SQLITE_CORRUPT_VTAB"},
    {3338,  "SQLITE_IOERR_ACCESS"},
    {2826,  "SQLITE_IOERR_BLOCKED"},
    {3594,  "SQLITE_IOERR_CHECKRESERVEDLOCK"},
    {4106,  "SQLITE_IOERR_CLOSE"},
    {6666,  "SQLITE_IOERR_CONVPATH"},
    {2570,  "SQLITE_IOERR_DELETE"},
    {5898,  "SQLITE_IOERR_DELETE_NOENT"},
    {4362,  "SQLITE_IOERR_DIR_CLOSE"},
    {1290,  "SQLITE_IOERR_DIR_FSYNC"},
    {1802,  "SQLITE_IOERR_FSTAT"},
    {1034,  "SQLITE_IOERR_FSYNC"},
    {6410,  "SQLITE_IOERR_GETTEMPPATH"},
    {3850,  "SQLITE_IOERR_LOCK"},
    {6154,  "SQLITE_IOERR_MMAP"},
    {3082,  "SQLITE_IOERR_NOMEM"},
    {2314,  "SQLITE_IOERR_RDLOCK"},
    {266,   "SQLITE_IOERR_READ"},
    {5642,  "SQLITE_IOERR_SEEK"},
    {5130,  "SQLITE_IOERR_SHMLOCK"},
    {5386,  "SQLITE_IOERR_SHMMAP"},
    {4618,  "SQLITE_IOERR_SHMOPEN"},
    {4874,  "SQLITE_IOERR_SHMSIZE"},
    {522,   "SQLITE_IOERR_SHORT_READ"},
    {1546,  "SQLITE_IOERR_TRUNCATE"},
    {2058,  "SQLITE_IOERR_UNLOCK"},
    {778,   "SQLITE_IOERR_WRITE"},
    {262,   "SQLITE_LOCKED_SHAREDCACHE"},
    {539,   "SQLITE_NOTICE_RECOVER_ROLLBACK"},
    {283,   "SQLITE_NOTICE_RECOVER_WAL"},
    {256,   "SQLITE_OK_LOAD_PERMANENTLY"},
    {520,   "SQLITE_READONLY_CANTLOCK"},
    {1032,  "SQLITE_READONLY_DBMOVED"},
    {264,   "SQLITE_READONLY_RECOVERY"},
    {776,   "SQLITE_READONLY_ROLLBACK"},
    {284,   "SQLITE_WARNING_AUTOINDEX"},
};

static std::string sqlite3_error_code_to_msg(int errcode) {
    auto it = sErrorCodesMap.find(errcode);
    if (it != sErrorCodesMap.end()) {
        return std::to_string(errcode) + " " + it->second;
    } else {
        return std::to_string(errcode);
    }
}

/* throw a SQLiteException with a message appropriate for the error in handle */
void throw_sqlite3_exception(JNIEnv* env, sqlite3* handle) {
    throw_sqlite3_exception(env, handle, NULL);
}

/* throw a SQLiteException with the given message */
void throw_sqlite3_exception(JNIEnv* env, const char* message) {
    throw_sqlite3_exception(env, NULL, message);
}

/* throw a SQLiteException with a message appropriate for the error in handle
   concatenated with the given message
 */
void throw_sqlite3_exception(JNIEnv* env, sqlite3* handle, const char* message) {
    if (handle) {
        // get the error code and message from the SQLite connection
        // the error message may contain more information than the error code
        // because it is based on the extended error code rather than the simplified
        // error code that SQLite normally returns.
        throw_sqlite3_exception(env, sqlite3_extended_errcode(handle),
                sqlite3_errmsg(handle), message);
    } else {
        // we use SQLITE_OK so that a generic SQLiteException is thrown;
        // any code not specified in the switch statement below would do.
        throw_sqlite3_exception(env, SQLITE_OK, "unknown error", message);
    }
}

/* throw a SQLiteException for a given error code
 * should only be used when the database connection is not available because the
 * error information will not be quite as rich */
void throw_sqlite3_exception_errcode(JNIEnv* env, int errcode, const char* message) {
    throw_sqlite3_exception(env, errcode, "unknown error", message);
}

/* throw a SQLiteException for a given error code, sqlite3message, and
   user message
 */
void throw_sqlite3_exception(JNIEnv* env, int errcode,
                             const char* sqlite3Message, const char* message) {
    const char* exceptionClass;
    switch (errcode & 0xff) { /* mask off extended error code */
        case SQLITE_IOERR:
            exceptionClass = "android/database/sqlite/SQLiteDiskIOException";
            break;
        case SQLITE_CORRUPT:
        case SQLITE_NOTADB: // treat "unsupported file format" error as corruption also
            exceptionClass = "android/database/sqlite/SQLiteDatabaseCorruptException";
            break;
        case SQLITE_CONSTRAINT:
            exceptionClass = "android/database/sqlite/SQLiteConstraintException";
            break;
        case SQLITE_ABORT:
            exceptionClass = "android/database/sqlite/SQLiteAbortException";
            break;
        case SQLITE_DONE:
            exceptionClass = "android/database/sqlite/SQLiteDoneException";
            sqlite3Message = NULL; // SQLite error message is irrelevant in this case
            break;
        case SQLITE_FULL:
            exceptionClass = "android/database/sqlite/SQLiteFullException";
            break;
        case SQLITE_MISUSE:
            exceptionClass = "android/database/sqlite/SQLiteMisuseException";
            break;
        case SQLITE_PERM:
            exceptionClass = "android/database/sqlite/SQLiteAccessPermException";
            break;
        case SQLITE_BUSY:
            exceptionClass = "android/database/sqlite/SQLiteDatabaseLockedException";
            break;
        case SQLITE_LOCKED:
            exceptionClass = "android/database/sqlite/SQLiteTableLockedException";
            break;
        case SQLITE_READONLY:
            exceptionClass = "android/database/sqlite/SQLiteReadOnlyDatabaseException";
            break;
        case SQLITE_CANTOPEN:
            exceptionClass = "android/database/sqlite/SQLiteCantOpenDatabaseException";
            break;
        case SQLITE_TOOBIG:
            exceptionClass = "android/database/sqlite/SQLiteBlobTooBigException";
            break;
        case SQLITE_RANGE:
            exceptionClass = "android/database/sqlite/SQLiteBindOrColumnIndexOutOfRangeException";
            break;
        case SQLITE_NOMEM:
            exceptionClass = "android/database/sqlite/SQLiteOutOfMemoryException";
            break;
        case SQLITE_MISMATCH:
            exceptionClass = "android/database/sqlite/SQLiteDatatypeMismatchException";
            break;
        case SQLITE_INTERRUPT:
            exceptionClass = "android/os/OperationCanceledException";
            break;
        default:
            exceptionClass = "android/database/sqlite/SQLiteException";
            break;
    }

    if (sqlite3Message) {
        String8 fullMessage;
        fullMessage.append(sqlite3Message);
        std::string errcode_msg = sqlite3_error_code_to_msg(errcode);
        fullMessage.appendFormat(" (code %s)", errcode_msg.c_str()); // print extended error code
        if (message) {
            fullMessage.append(": ");
            fullMessage.append(message);
        }
        jniThrowException(env, exceptionClass, fullMessage.string());
    } else {
        jniThrowException(env, exceptionClass, message);
    }
}


} // namespace android
