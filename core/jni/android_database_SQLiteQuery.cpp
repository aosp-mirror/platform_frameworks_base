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
#define LOG_TAG "Cursor"

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <sqlite3.h>

#include <utils/Log.h>

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "CursorWindow.h"
#include "sqlite3_exception.h"


namespace android {

sqlite3_stmt * compile(JNIEnv* env, jobject object,
                       sqlite3 * handle, jstring sqlString);

// From android_database_CursorWindow.cpp
CursorWindow * get_window_from_object(JNIEnv * env, jobject javaWindow);

static jfieldID gHandleField;
static jfieldID gStatementField;


#define GET_STATEMENT(env, object) \
        (sqlite3_stmt *)env->GetIntField(object, gStatementField)
#define GET_HANDLE(env, object) \
        (sqlite3 *)env->GetIntField(object, gHandleField)

static int skip_rows(sqlite3_stmt *statement, int maxRows) {
    int retryCount = 0;
    for (int i = 0; i < maxRows; i++) {
        int err = sqlite3_step(statement);
        if (err == SQLITE_ROW){
            // do nothing
        } else if (err == SQLITE_DONE) {
            return i;
        } else if (err == SQLITE_LOCKED || err == SQLITE_BUSY) {
            // The table is locked, retry
            LOG_WINDOW("Database locked, retrying");
           if (retryCount > 50) {
                LOGE("Bailing on database busy rety");
                break;
            }
            // Sleep to give the thread holding the lock a chance to finish
            usleep(1000);
            retryCount++;
            continue;
        } else {
            return -1;
        }
    }
    LOGD("skip_rows row %d", maxRows);
    return maxRows;
}

static int finish_program_and_get_row_count(sqlite3_stmt *statement) {
    int numRows = 0;
    int retryCount = 0;
    while (true) {
        int err = sqlite3_step(statement);
        if (err == SQLITE_ROW){
            numRows++;
        } else if (err == SQLITE_LOCKED || err == SQLITE_BUSY) {
            // The table is locked, retry
            LOG_WINDOW("Database locked, retrying");
            if (retryCount > 50) {
                LOGE("Bailing on database busy rety");
                break;
            }
            // Sleep to give the thread holding the lock a chance to finish
            usleep(1000);
            retryCount++;
            continue;
        } else {
            // no need to throw exception
            break;
        }
    }
    sqlite3_reset(statement);
    LOGD("finish_program_and_get_row_count row %d", numRows);
    return numRows;
}

static jint native_fill_window(JNIEnv* env, jobject object, jobject javaWindow,
                               jint startPos, jint offsetParam, jint maxRead, jint lastPos)
{
    int err;
    sqlite3_stmt * statement = GET_STATEMENT(env, object);
    int numRows = lastPos;
    maxRead += lastPos;
    int numColumns;
    int retryCount;
    int boundParams;
    CursorWindow * window;
    
    if (statement == NULL) {
        LOGE("Invalid statement in fillWindow()");
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Attempting to access a deactivated, closed, or empty cursor");
        return 0;
    }

    // Only do the binding if there is a valid offsetParam. If no binding needs to be done
    // offsetParam will be set to 0, an invliad value.
    if(offsetParam > 0) {
        // Bind the offset parameter, telling the program which row to start with
        err = sqlite3_bind_int(statement, offsetParam, startPos);
        if (err != SQLITE_OK) {
            LOGE("Unable to bind offset position, offsetParam = %d", offsetParam);
            jniThrowException(env, "java/lang/IllegalArgumentException",
                              sqlite3_errmsg(GET_HANDLE(env, object)));
            return 0;
        }
        LOG_WINDOW("Bound to startPos %d", startPos);
    } else {
        LOG_WINDOW("Not binding to startPos %d", startPos);
    }

    // Get the native window
    window = get_window_from_object(env, javaWindow);
    if (!window) {
        LOGE("Invalid CursorWindow");
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Bad CursorWindow");
        return 0;
    }
    LOG_WINDOW("Window: numRows = %d, size = %d, freeSpace = %d", window->getNumRows(), window->size(), window->freeSpace());

    numColumns = sqlite3_column_count(statement);
    if (!window->setNumColumns(numColumns)) {
        LOGE("Failed to change column count from %d to %d", window->getNumColumns(), numColumns);
        jniThrowException(env, "java/lang/IllegalStateException", "numColumns mismatch");
        return 0;
    }

    retryCount = 0;
    if (startPos > 0) {
        int num = skip_rows(statement, startPos);
        if (num < 0) {
            throw_sqlite3_exception(env, GET_HANDLE(env, object));
            return 0;
        } else if (num < startPos) {
            LOGE("startPos %d > actual rows %d", startPos, num);
            return num;
        }
    } 
    
    while(startPos != 0 || numRows < maxRead) {
        err = sqlite3_step(statement);
        if (err == SQLITE_ROW) {
            LOG_WINDOW("\nStepped statement %p to row %d", statement, startPos + numRows);
            retryCount = 0;

            // Allocate a new field directory for the row. This pointer is not reused
            // since it mey be possible for it to be relocated on a call to alloc() when
            // the field data is being allocated.
            {
                field_slot_t * fieldDir = window->allocRow();
                if (!fieldDir) {
                    LOGE("Failed allocating fieldDir at startPos %d row %d", startPos, numRows);
                    return startPos + numRows + finish_program_and_get_row_count(statement) + 1;
                }
            }

            // Pack the row into the window
            int i;
            for (i = 0; i < numColumns; i++) {
                int type = sqlite3_column_type(statement, i);
                if (type == SQLITE_TEXT) {
                    // TEXT data
#if WINDOW_STORAGE_UTF8
                    uint8_t const * text = (uint8_t const *)sqlite3_column_text(statement, i);
                    // SQLite does not include the NULL terminator in size, but does
                    // ensure all strings are NULL terminated, so increase size by
                    // one to make sure we store the terminator.
                    size_t size = sqlite3_column_bytes(statement, i) + 1;
#else
                    uint8_t const * text = (uint8_t const *)sqlite3_column_text16(statement, i);
                    size_t size = sqlite3_column_bytes16(statement, i);
#endif
                    int offset = window->alloc(size);
                    if (!offset) {
                        window->freeLastRow();
                        LOGE("Failed allocating %u bytes for text/blob at %d,%d", size,
                                   startPos + numRows, i);
                        return startPos + numRows + finish_program_and_get_row_count(statement) + 1;
                    }

                    window->copyIn(offset, text, size);

                    // This must be updated after the call to alloc(), since that
                    // may move the field around in the window
                    field_slot_t * fieldSlot = window->getFieldSlot(numRows, i);
                    fieldSlot->type = FIELD_TYPE_STRING;
                    fieldSlot->data.buffer.offset = offset;
                    fieldSlot->data.buffer.size = size;

                    LOG_WINDOW("%d,%d is TEXT with %u bytes", startPos + numRows, i, size);
                } else if (type == SQLITE_INTEGER) {
                    // INTEGER data
                    int64_t value = sqlite3_column_int64(statement, i);
                    if (!window->putLong(numRows, i, value)) {
                        window->freeLastRow();
                        LOGE("Failed allocating space for a long in column %d", i);
                        return startPos + numRows + finish_program_and_get_row_count(statement) + 1;
                    }
                    LOG_WINDOW("%d,%d is INTEGER 0x%016llx", startPos + numRows, i, value);
                } else if (type == SQLITE_FLOAT) {
                    // FLOAT data
                    double value = sqlite3_column_double(statement, i);
                    if (!window->putDouble(numRows, i, value)) {
                        window->freeLastRow();
                        LOGE("Failed allocating space for a double in column %d", i);
                        return startPos + numRows + finish_program_and_get_row_count(statement) + 1;
                    }
                    LOG_WINDOW("%d,%d is FLOAT %lf", startPos + numRows, i, value);
                } else if (type == SQLITE_BLOB) {
                    // BLOB data
                    uint8_t const * blob = (uint8_t const *)sqlite3_column_blob(statement, i);
                    size_t size = sqlite3_column_bytes16(statement, i);
                    int offset = window->alloc(size);
                    if (!offset) {
                        window->freeLastRow();
                        LOGE("Failed allocating %u bytes for blob at %d,%d", size,
                                   startPos + numRows, i);
                        return startPos + numRows + finish_program_and_get_row_count(statement) + 1;
                    }

                    window->copyIn(offset, blob, size);

                    // This must be updated after the call to alloc(), since that
                    // may move the field around in the window
                    field_slot_t * fieldSlot = window->getFieldSlot(numRows, i);
                    fieldSlot->type = FIELD_TYPE_BLOB;
                    fieldSlot->data.buffer.offset = offset;
                    fieldSlot->data.buffer.size = size;

                    LOG_WINDOW("%d,%d is Blob with %u bytes @ %d", startPos + numRows, i, size, offset);
                } else if (type == SQLITE_NULL) {
                    // NULL field
                    window->putNull(numRows, i);

                    LOG_WINDOW("%d,%d is NULL", startPos + numRows, i);
                } else {
                    // Unknown data
                    LOGE("Unknown column type when filling database window");
                    throw_sqlite3_exception(env, "Unknown column type when filling window");
                    break;
                }
            }

            if (i < numColumns) {
                // Not all the fields fit in the window
                // Unknown data error happened
                break;
            }

            // Mark the row as complete in the window
            numRows++;
        } else if (err == SQLITE_DONE) {
            // All rows processed, bail
            LOG_WINDOW("Processed all rows");
            break;
        } else if (err == SQLITE_LOCKED || err == SQLITE_BUSY) {
            // The table is locked, retry
            LOG_WINDOW("Database locked, retrying");
            if (retryCount > 50) {
                LOGE("Bailing on database busy rety");
                break;
            }

            // Sleep to give the thread holding the lock a chance to finish
            usleep(1000);

            retryCount++;
            continue;
        } else {
            throw_sqlite3_exception(env, GET_HANDLE(env, object));
            break;
        }
    }

    LOG_WINDOW("Resetting statement %p after fetching %d rows in %d bytes\n\n\n\n", statement,
            numRows, window->size() - window->freeSpace());
//    LOGI("Filled window with %d rows in %d bytes", numRows, window->size() - window->freeSpace());
    if (err == SQLITE_ROW) {
        return -1;
    } else {
        sqlite3_reset(statement);
        return startPos + numRows;
    }
}

static jint native_column_count(JNIEnv* env, jobject object)
{
    sqlite3_stmt * statement = GET_STATEMENT(env, object);

    return sqlite3_column_count(statement);
}

static jstring native_column_name(JNIEnv* env, jobject object, jint columnIndex)
{
    sqlite3_stmt * statement = GET_STATEMENT(env, object);
    char const * name;

    name = sqlite3_column_name(statement, columnIndex);

    return env->NewStringUTF(name);
}


static JNINativeMethod sMethods[] =
{
     /* name, signature, funcPtr */
    {"native_fill_window", "(Landroid/database/CursorWindow;IIII)I", (void *)native_fill_window},
    {"native_column_count", "()I", (void*)native_column_count},
    {"native_column_name", "(I)Ljava/lang/String;", (void *)native_column_name},
};

int register_android_database_SQLiteQuery(JNIEnv * env)
{
    jclass clazz;

    clazz = env->FindClass("android/database/sqlite/SQLiteQuery");
    if (clazz == NULL) {
        LOGE("Can't find android/database/sqlite/SQLiteQuery");
        return -1;
    }

    gHandleField = env->GetFieldID(clazz, "nHandle", "I");
    gStatementField = env->GetFieldID(clazz, "nStatement", "I");

    if (gHandleField == NULL || gStatementField == NULL) {
        LOGE("Error locating fields");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
        "android/database/sqlite/SQLiteQuery", sMethods, NELEM(sMethods));
}

} // namespace android
