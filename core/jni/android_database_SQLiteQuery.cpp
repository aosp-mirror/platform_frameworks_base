/*
 * Copyright (C) 2006 The Android Open Source Project
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
#define LOG_TAG "SqliteCursor.cpp"

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <sqlite3.h>

#include <utils/Log.h>

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "binder/CursorWindow.h"
#include "sqlite3_exception.h"


namespace android {

static jint nativeFillWindow(JNIEnv* env, jclass clazz, jint databasePtr,
        jint statementPtr, jint windowPtr, jint startPos, jint offsetParam) {
    sqlite3* database = reinterpret_cast<sqlite3*>(databasePtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);

    // Only do the binding if there is a valid offsetParam. If no binding needs to be done
    // offsetParam will be set to 0, an invalid value.
    if (offsetParam > 0) {
        // Bind the offset parameter, telling the program which row to start with
        int err = sqlite3_bind_int(statement, offsetParam, startPos);
        if (err != SQLITE_OK) {
            ALOGE("Unable to bind offset position, offsetParam = %d", offsetParam);
            throw_sqlite3_exception(env, database);
            return 0;
        }
        LOG_WINDOW("Bound to startPos %d", startPos);
    } else {
        LOG_WINDOW("Not binding to startPos %d", startPos);
    }

    // We assume numRows is initially 0.
    LOG_WINDOW("Window: numRows = %d, size = %d, freeSpace = %d",
            window->getNumRows(), window->size(), window->freeSpace());

    int numColumns = sqlite3_column_count(statement);
    status_t status = window->setNumColumns(numColumns);
    if (status) {
        ALOGE("Failed to change column count from %d to %d", window->getNumColumns(), numColumns);
        jniThrowException(env, "java/lang/IllegalStateException", "numColumns mismatch");
        return 0;
    }

    int retryCount = 0;
    int totalRows = 0;
    int addedRows = 0;
    bool windowFull = false;
    bool gotException = false;
    const bool countAllRows = (startPos == 0); // when startPos is 0, we count all rows
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

            // Allocate a new field directory for the row. This pointer is not reused
            // since it may be possible for it to be relocated on a call to alloc() when
            // the field data is being allocated.
            status = window->allocRow();
            if (status) {
                LOG_WINDOW("Failed allocating fieldDir at startPos %d row %d, error=%d",
                        startPos, addedRows, status);
                windowFull = true;
                continue;
            }

            // Pack the row into the window.
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
                        windowFull = true;
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
                        windowFull = true;
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
                        windowFull = true;
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
                        windowFull = true;
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
                        windowFull = true;
                        break;
                    }

                    LOG_WINDOW("%d,%d is NULL", startPos + addedRows, i);
                } else {
                    // Unknown data
                    ALOGE("Unknown column type when filling database window");
                    throw_sqlite3_exception(env, "Unknown column type when filling window");
                    gotException = true;
                    break;
                }
            }

            // Update the final row tally.
            if (windowFull || gotException) {
                window->freeLastRow();
            } else {
                addedRows += 1;
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
                throw_sqlite3_exception(env, database, "retrycount exceeded");
                gotException = true;
            } else {
                // Sleep to give the thread holding the lock a chance to finish
                usleep(1000);
                retryCount++;
            }
        } else {
            throw_sqlite3_exception(env, database);
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
    return countAllRows ? totalRows : 0;
}

static jint nativeColumnCount(JNIEnv* env, jclass clazz, jint statementPtr) {
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    return sqlite3_column_count(statement);
}

static jstring nativeColumnName(JNIEnv* env, jclass clazz, jint statementPtr,
        jint columnIndex) {
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    const char* name = sqlite3_column_name(statement, columnIndex);
    return env->NewStringUTF(name);
}


static JNINativeMethod sMethods[] =
{
     /* name, signature, funcPtr */
    { "nativeFillWindow", "(IIIII)I",
            (void*)nativeFillWindow },
    { "nativeColumnCount", "(I)I",
            (void*)nativeColumnCount},
    { "nativeColumnName", "(II)Ljava/lang/String;",
            (void*)nativeColumnName},
};

int register_android_database_SQLiteQuery(JNIEnv * env)
{
    return AndroidRuntime::registerNativeMethods(env,
        "android/database/sqlite/SQLiteQuery", sMethods, NELEM(sMethods));
}

} // namespace android
