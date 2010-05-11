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

#ifndef _MTP_DATABASE_H
#define _MTP_DATABASE_H

#include "MtpUtils.h"
#include "SqliteDatabase.h"
#include "mtp.h"

class MtpDataPacket;
class SqliteStatement;

class MtpDatabase : public SqliteDatabase {
private:
    SqliteStatement*        mFileIdQuery;
    SqliteStatement*        mFilePathQuery;
    SqliteStatement*        mObjectInfoQuery;
    SqliteStatement*        mFileInserter;
    SqliteStatement*        mFileDeleter;

public:
                            MtpDatabase();
    virtual                 ~MtpDatabase();

    bool                    open(const char* path, bool create);
    MtpObjectHandle         addFile(const char* path,
                                    MtpObjectFormat format,
                                    MtpObjectHandle parent,
                                    MtpStorageID storage,
                                    uint64_t size,
                                    time_t created,
                                    time_t modified);

    MtpObjectHandleList*    getObjectList(MtpStorageID storageID,
                                    MtpObjectFormat format,
                                    MtpObjectHandle parent);

    MtpResponseCode         getObjectProperty(MtpObjectHandle handle,
                                    MtpObjectProperty property,
                                    MtpDataPacket& packet);

    MtpResponseCode         getObjectInfo(MtpObjectHandle handle,
                                    MtpDataPacket& packet);

    bool                    getObjectFilePath(MtpObjectHandle handle,
                                    MtpString& filePath,
                                    int64_t& fileLength);
    bool                    deleteFile(MtpObjectHandle handle);
};

#endif // _MTP_DATABASE_H
