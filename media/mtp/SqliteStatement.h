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

#ifndef _SQLITE_STATEMENT_H
#define _SQLITE_STATEMENT_H

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;
class SqliteDatabase;

#include <stdint.h>

class SqliteStatement {
private:
    sqlite3*        mDatabaseHandle;
    sqlite3_stmt*   mStatement;
    bool            mDone;

public:
                    SqliteStatement(SqliteDatabase* db);
    virtual         ~SqliteStatement();

    bool            prepare(const char* sql);
    bool            step();
    void            reset();
    void            finalize();

    void            bind(int column, int value);
    void            bind(int column, const char* value);

    int             getColumnInt(int column);
    int64_t         getColumnInt64(int column);
    const char*     getColumnString(int column);

    inline bool     isDone() const { return mDone; }
};

#endif // _SQLITE_STATEMENT_H
