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

#ifndef _SQLITE_DATABASE_H
#define _SQLITE_DATABASE_H

typedef struct sqlite3 sqlite3;

namespace android {

class SqliteDatabase {
private:
    sqlite3*        mDatabaseHandle;

public:
                    SqliteDatabase();
    virtual         ~SqliteDatabase();

    bool            open(const char* path, bool create);
    void            close();

    bool            exec(const char* sql);
    int             lastInsertedRow();

    void            beginTransaction();
    void            commitTransaction();
    void            rollbackTransaction();

    int             getVersion();
    void            setVersion(int version);

    inline sqlite3* getDatabaseHandle() const { return mDatabaseHandle; }
};

}; // namespace android

#endif // _SQLITE_DATABASE_H
