/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "SqliteDatabase"

#include "MtpDebug.h"
#include "SqliteDatabase.h"
#include "SqliteStatement.h"

#include <stdio.h>
#include <sqlite3.h>

namespace android {

SqliteDatabase::SqliteDatabase()
    :   mDatabaseHandle(NULL)
{
}

SqliteDatabase::~SqliteDatabase() {
    close();
}

bool SqliteDatabase::open(const char* path, bool create) {
    int flags = SQLITE_OPEN_READWRITE;
    if (create) flags |= SQLITE_OPEN_CREATE;
    // SQLITE_OPEN_NOMUTEX?
    int ret = sqlite3_open_v2(path, &mDatabaseHandle, flags, NULL);
    if (ret) {
        LOGE("could not open database\n");
        return false;
    }
    return true;
}

void SqliteDatabase::close() {
    if (mDatabaseHandle) {
        sqlite3_close(mDatabaseHandle);
        mDatabaseHandle = NULL;
    }
}

bool SqliteDatabase::exec(const char* sql) {
    return (sqlite3_exec(mDatabaseHandle, sql, NULL, NULL, NULL) == 0);
}

int SqliteDatabase::lastInsertedRow() {
    return sqlite3_last_insert_rowid(mDatabaseHandle);
}

void SqliteDatabase::beginTransaction() {
    exec("BEGIN TRANSACTION");
}

void SqliteDatabase::commitTransaction() {
    exec("COMMIT TRANSACTION");
}

void SqliteDatabase::rollbackTransaction() {
    exec("ROLLBACK TRANSACTION");
}

int SqliteDatabase::getVersion() {
    SqliteStatement stmt(this);
    stmt.prepare("PRAGMA user_version;");
    stmt.step();
    return stmt.getColumnInt(0);
}
void SqliteDatabase::setVersion(int version) {
    char    buffer[40];
    snprintf(buffer, sizeof(buffer), "PRAGMA user_version = %d", version);
    exec(buffer);
}

}  // namespace android
