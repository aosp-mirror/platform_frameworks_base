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

#ifndef _MTP_SQLITE_DATABASE_H
#define _MTP_SQLITE_DATABASE_H

#include "MtpTypes.h"
#include "MtpDatabase.h"

class SqliteDatabase;

namespace android {

class MtpDataPacket;
class SqliteStatement;

class MtpSqliteDatabase : public MtpDatabase {
private:
    SqliteDatabase*             mDatabase;
    SqliteStatement*            mFileIdQuery;
    SqliteStatement*            mFilePathQuery;
    SqliteStatement*            mObjectInfoQuery;
    SqliteStatement*            mFileInserter;
    SqliteStatement*            mFileDeleter;

public:
                                MtpSqliteDatabase();
    virtual                     ~MtpSqliteDatabase();

    bool                        open(const char* path, bool create);
    void                        close();

    virtual MtpObjectHandle     getObjectHandle(const char* path);
    virtual MtpObjectHandle     addFile(const char* path,
                                        MtpObjectFormat format,
                                        MtpObjectHandle parent,
                                        MtpStorageID storage,
                                        uint64_t size,
                                        time_t modified);

    virtual MtpObjectHandleList* getObjectList(MtpStorageID storageID,
                                            MtpObjectFormat format,
                                            MtpObjectHandle parent);

    virtual MtpResponseCode     getObjectProperty(MtpObjectHandle handle,
                                            MtpObjectProperty property,
                                            MtpDataPacket& packet);

    virtual MtpResponseCode     getObjectInfo(MtpObjectHandle handle,
                                            MtpDataPacket& packet);

    virtual bool                getObjectFilePath(MtpObjectHandle handle,
                                            MtpString& filePath,
                                            int64_t& fileLength);
    virtual bool                deleteFile(MtpObjectHandle handle);

    // helper for media scanner
    virtual MtpObjectHandle*    getFileList(int& outCount);

    virtual void                beginTransaction();
    virtual void                commitTransaction();
    virtual void                rollbackTransaction();
};

}; // namespace android

#endif // _MTP_SQLITE_DATABASE_H
