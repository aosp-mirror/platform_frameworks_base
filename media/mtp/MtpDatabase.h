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

#include "MtpTypes.h"
#include "SqliteDatabase.h"

namespace android {

class MtpDataPacket;
class SqliteStatement;

class MtpDatabase : public SqliteDatabase {
private:
    SqliteStatement*        mFileIdQuery;
    SqliteStatement*        mFilePathQuery;
    SqliteStatement*        mObjectInfoQuery;
    SqliteStatement*        mFileInserter;
    SqliteStatement*        mFileDeleter;
    SqliteStatement*        mAudioInserter;
    SqliteStatement*        mAudioDeleter;

public:
                            MtpDatabase();
    virtual                 ~MtpDatabase();

    static uint32_t         getTableForFile(MtpObjectFormat format);

    bool                    open(const char* path, bool create);
    MtpObjectHandle         getObjectHandle(const char* path);
    MtpObjectHandle         addFile(const char* path,
                                    MtpObjectFormat format,
                                    MtpObjectHandle parent,
                                    MtpStorageID storage,
                                    uint64_t size,
                                    time_t modified);

    MtpObjectHandle         addAudioFile(MtpObjectHandle id);

    MtpObjectHandle         addAudioFile(MtpObjectHandle id,
                                    const char* title,
                                    const char* artist,
                                    const char* album,
                                    const char* albumArtist,
                                    const char* genre,
                                    const char* composer,
                                    const char* mimeType,
                                    int track,
                                    int year,
                                    int duration);

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

    // helper for media scanner
    MtpObjectHandle*        getFileList(int& outCount);
};

}; // namespace android

#endif // _MTP_DATABASE_H
