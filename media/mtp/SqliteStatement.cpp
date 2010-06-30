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

#define LOG_TAG "SqliteStatement"

#include "SqliteStatement.h"
#include "SqliteDatabase.h"

#include <stdio.h>
#include <sqlite3.h>

namespace android {

SqliteStatement::SqliteStatement(SqliteDatabase* db)
    :   mDatabaseHandle(db->getDatabaseHandle()),
        mStatement(NULL),
        mDone(false)
{
}

SqliteStatement::~SqliteStatement() {
    finalize();
}

bool SqliteStatement::prepare(const char* sql) {
    return (sqlite3_prepare_v2(mDatabaseHandle, sql, -1, &mStatement, NULL) == 0);
}

bool SqliteStatement::step() {
    int ret = sqlite3_step(mStatement);
    if (ret == SQLITE_DONE) {
        mDone = true;
        return true;
    }
    return (ret == SQLITE_OK || ret == SQLITE_ROW);
}

void SqliteStatement::reset() {
    sqlite3_reset(mStatement);
    mDone = false;
}

void SqliteStatement::finalize() {
    if (mStatement) {
        sqlite3_finalize(mStatement);
        mStatement = NULL;
    }
}

void SqliteStatement::bind(int column, int value) {
    sqlite3_bind_int(mStatement, column, value);
}

void SqliteStatement::bind(int column, const char* value) {
    sqlite3_bind_text(mStatement, column, value, -1, SQLITE_TRANSIENT);
}

int SqliteStatement::getColumnInt(int column) {
    return sqlite3_column_int(mStatement, column);
}

int64_t SqliteStatement::getColumnInt64(int column) {
    return sqlite3_column_int64(mStatement, column);
}

const char* SqliteStatement::getColumnString(int column) {
    return (const char *)sqlite3_column_text(mStatement, column);
}

}  // namespace android
