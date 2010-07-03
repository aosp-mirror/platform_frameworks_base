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

#ifndef _MTP_MEDIA_SCANNER_H
#define _MTP_MEDIA_SCANNER_H

struct stat;

namespace android {

class MtpDatabase;
class SqliteStatement;

class MtpMediaScanner {
private:
    MtpStorageID            mStorageID;
    const char*             mFilePath;
    MtpDatabase*            mDatabase;

    // for garbage collecting missing files
    MtpObjectHandle*        mFileList;
    int                     mFileCount;

public:
                            MtpMediaScanner(MtpStorageID id, const char* filePath, MtpDatabase* db);
    virtual                 ~MtpMediaScanner();

    bool                    scanFiles();

private:
    MtpObjectFormat         getFileFormat(const char* path);
    int                     scanDirectory(const char* path, MtpObjectHandle parent);
    void                    scanFile(const char* path, MtpObjectHandle parent, struct stat& statbuf);
    void                    markFile(MtpObjectHandle handle);
};

}; // namespace android

#endif // _MTP_MEDIA_SCANNER_H
