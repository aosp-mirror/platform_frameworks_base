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

#define LOG_TAG "MtpSqliteDatabase"

#include "MtpDebug.h"
#include "MtpSqliteDatabase.h"
#include "MtpDataPacket.h"
#include "MtpUtils.h"
#include "SqliteDatabase.h"
#include "SqliteStatement.h"

#include <stdio.h>
#include <stdlib.h>
#include <sqlite3.h>

namespace android {

#define FILE_ID_COLUMN                  1
#define FILE_PATH_COLUMN                2
#define FILE_FORMAT_COLUMN              3
#define FILE_PARENT_COLUMN              4
#define FILE_STORAGE_COLUMN             5
#define FILE_SIZE_COLUMN                6
#define FILE_MODIFIED_COLUMN            7

#define FILE_TABLE_CREATE    "CREATE TABLE IF NOT EXISTS files ("    \
                        "_id INTEGER PRIMARY KEY,"              \
                        "path TEXT,"                            \
                        "format INTEGER,"                       \
                        "parent INTEGER,"                       \
                        "storage INTEGER,"                      \
                        "size INTEGER,"                         \
                        "date_modified INTEGER"                \
                        ");"

#define PATH_INDEX_CREATE "CREATE INDEX IF NOT EXISTS path_index on files(path);"

#define FILE_ID_QUERY   "SELECT _id,format FROM files WHERE path = ?;"
#define FILE_PATH_QUERY "SELECT path,size FROM files WHERE _id = ?"

#define GET_OBJECT_INFO_QUERY   "SELECT storage,format,parent,path,size,date_modified FROM files WHERE _id = ?;"
#define FILE_INSERT     "INSERT INTO files VALUES(?,?,?,?,?,?,?);"
#define FILE_DELETE     "DELETE FROM files WHERE _id = ?;"

struct PropertyTableEntry {
    MtpObjectProperty   property;
    int                 type;
    const char*         columnName;
};

static const PropertyTableEntry   kPropertyTable[] = {
    {   MTP_PROPERTY_PARENT_OBJECT,     MTP_TYPE_UINT32,    "parent"        },
    {   MTP_PROPERTY_STORAGE_ID,        MTP_TYPE_UINT32,    "storage"       },
    {   MTP_PROPERTY_OBJECT_FORMAT,     MTP_TYPE_UINT32,    "format"        },
    {   MTP_PROPERTY_OBJECT_FILE_NAME,  MTP_TYPE_STR,       "path"          },
    {   MTP_PROPERTY_OBJECT_SIZE,       MTP_TYPE_UINT64,    "size"          },
    {   MTP_PROPERTY_DATE_MODIFIED,     MTP_TYPE_STR,       "date_modified" },
};

static bool getPropertyInfo(MtpObjectProperty property, int& type, const char*& columnName) {
    int count = sizeof(kPropertyTable) / sizeof(kPropertyTable[0]);
    const PropertyTableEntry* entry = kPropertyTable;
    for (int i = 0; i < count; i++, entry++) {
        if (entry->property == property) {
            type = entry->type;
            columnName = entry->columnName;
            return true;
        }
    }
    return false;
}

MtpSqliteDatabase::MtpSqliteDatabase()
    :   mDatabase(NULL),
        mFileIdQuery(NULL),
        mFilePathQuery(NULL),
        mObjectInfoQuery(NULL),
        mFileInserter(NULL),
        mFileDeleter(NULL)
{
}

MtpSqliteDatabase::~MtpSqliteDatabase() {
    delete mDatabase;
    delete mFileIdQuery;
    delete mFilePathQuery;
    delete mObjectInfoQuery;
    delete mFileInserter;
    delete mFileDeleter;
}

bool MtpSqliteDatabase::open(const char* path, bool create) {
    mDatabase = new SqliteDatabase;

    if (!mDatabase->open(path, create))
        goto fail;

    // create tables and indices if necessary
    if (!mDatabase->exec(FILE_TABLE_CREATE)) {
        LOGE("could not create file table");
        goto fail;
    }
    if (!mDatabase->exec(PATH_INDEX_CREATE)) {
        LOGE("could not path index on file table");
        goto fail;
    }

    if (!mFileIdQuery) {
        mFileIdQuery = new SqliteStatement(mDatabase);
        if (!mFileIdQuery->prepare(FILE_ID_QUERY)) {
            LOGE("could not compile FILE_ID_QUERY");
            goto fail;
        }
    }
    if (!mFilePathQuery) {
        mFilePathQuery = new SqliteStatement(mDatabase);
        if (!mFilePathQuery->prepare(FILE_PATH_QUERY)) {
            LOGE("could not compile FILE_PATH_QUERY");
            goto fail;
        }
    }
    if (!mObjectInfoQuery) {
        mObjectInfoQuery = new SqliteStatement(mDatabase);
        if (!mObjectInfoQuery->prepare(GET_OBJECT_INFO_QUERY)) {
            LOGE("could not compile GET_OBJECT_INFO_QUERY");
            goto fail;
        }
    }
    if (!mFileInserter) {
        mFileInserter = new SqliteStatement(mDatabase);
        if (!mFileInserter->prepare(FILE_INSERT)) {
            LOGE("could not compile FILE_INSERT\n");
            goto fail;
        }
    }
    if (!mFileDeleter) {
        mFileDeleter = new SqliteStatement(mDatabase);
        if (!mFileDeleter->prepare(FILE_DELETE)) {
            LOGE("could not compile FILE_DELETE\n");
            goto fail;
        }
    }

    return true;

fail:
    delete mDatabase;
    delete mFileIdQuery;
    delete mFilePathQuery;
    delete mObjectInfoQuery;
    delete mFileInserter;
    delete mFileDeleter;
    mDatabase = NULL;
    mFileIdQuery = NULL;
    mFilePathQuery = NULL;
    mObjectInfoQuery = NULL;
    mFileInserter = NULL;
    mFileDeleter = NULL;
    return false;
}

void MtpSqliteDatabase::close() {
    if (mDatabase) {
        mDatabase->close();
        mDatabase = NULL;
    }
}

MtpObjectHandle MtpSqliteDatabase::getObjectHandle(const char* path) {
    mFileIdQuery->reset();
    mFileIdQuery->bind(1, path);
    if (mFileIdQuery->step()) {
        int row = mFileIdQuery->getColumnInt(0);
        if (row > 0) {
            MtpObjectFormat format = mFileIdQuery->getColumnInt(1);
            return row;
        }
    }

    return 0;
}

MtpObjectHandle MtpSqliteDatabase::addFile(const char* path,
                                    MtpObjectFormat format,
                                    MtpObjectHandle parent,
                                    MtpStorageID storage,
                                    uint64_t size,
                                    time_t modified) {
    mFileInserter->bind(FILE_PATH_COLUMN, path);
    mFileInserter->bind(FILE_FORMAT_COLUMN, format);
    mFileInserter->bind(FILE_PARENT_COLUMN, parent);
    mFileInserter->bind(FILE_STORAGE_COLUMN, storage);
    mFileInserter->bind(FILE_SIZE_COLUMN, size);
    mFileInserter->bind(FILE_MODIFIED_COLUMN, modified);
    mFileInserter->step();
    mFileInserter->reset();
    int result = mDatabase->lastInsertedRow();
    return (result <= 0 ? kInvalidObjectHandle : result);
}

MtpObjectHandleList* MtpSqliteDatabase::getObjectList(MtpStorageID storageID,
                                                MtpObjectFormat format,
                                                MtpObjectHandle parent) {
    bool                whereStorage = (storageID != 0xFFFFFFFF);
    bool                whereFormat = (format != 0);
    bool                whereParent = (parent != 0);
    char                intBuffer[20];

    MtpString  query("SELECT _id,format FROM files");
    if (whereStorage || whereFormat || whereParent)
        query += " WHERE";
    if (whereStorage) {
        snprintf(intBuffer, sizeof(intBuffer), "%d", storageID);
        query += " storage = ";
        query += intBuffer;
    }
    if (whereFormat) {
        snprintf(intBuffer, sizeof(intBuffer), "%d", format);
        if (whereStorage)
            query += " AND";
        query += " format = ";
        query += intBuffer;
    }
    if (whereParent) {
        if (parent != MTP_PARENT_ROOT)
            parent &= kObjectHandleIndexMask;
        snprintf(intBuffer, sizeof(intBuffer), "%d", parent);
        if (whereStorage || whereFormat)
            query += " AND";
        query += " parent = ";
        query += intBuffer;
    }
    query += ";";

    SqliteStatement stmt(mDatabase);
    LOGV("%s", (const char *)query);
    stmt.prepare(query);

    MtpObjectHandleList* list = new MtpObjectHandleList();
    while (!stmt.isDone()) {
        if (stmt.step()) {
            int index = stmt.getColumnInt(0);
            LOGV("stmt.getColumnInt returned %d", index);
            if (index > 0) {
                MtpObjectFormat format = stmt.getColumnInt(1);
                list->push(index);
            }
        }
    }
    LOGV("list size: %d", list->size());
    return list;
}


MtpResponseCode MtpSqliteDatabase::getObjectProperty(MtpObjectHandle handle,
                                    MtpObjectProperty property,
                                    MtpDataPacket& packet) {
    int         type;
    const char* columnName;
    char        intBuffer[20];

    if (handle != MTP_PARENT_ROOT)
        handle &= kObjectHandleIndexMask;

    if (!getPropertyInfo(property, type, columnName))
        return MTP_RESPONSE_INVALID_OBJECT_PROP_CODE;
    snprintf(intBuffer, sizeof(intBuffer), "%d", handle);

    MtpString  query("SELECT ");
    query += columnName;
    query += " FROM files WHERE _id = ";
    query += intBuffer;
    query += ";";

    SqliteStatement stmt(mDatabase);
    LOGV("%s", (const char *)query);
    stmt.prepare(query);

    if (!stmt.step())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;

    switch (type) {
        case MTP_TYPE_INT8:
            packet.putInt8(stmt.getColumnInt(0));
            break;
        case MTP_TYPE_UINT8:
            packet.putUInt8(stmt.getColumnInt(0));
            break;
        case MTP_TYPE_INT16:
            packet.putInt16(stmt.getColumnInt(0));
            break;
        case MTP_TYPE_UINT16:
            packet.putUInt16(stmt.getColumnInt(0));
            break;
        case MTP_TYPE_INT32:
            packet.putInt32(stmt.getColumnInt(0));
            break;
        case MTP_TYPE_UINT32:
            packet.putUInt32(stmt.getColumnInt(0));
            break;
        case MTP_TYPE_INT64:
            packet.putInt64(stmt.getColumnInt64(0));
            break;
        case MTP_TYPE_UINT64:
            packet.putUInt64(stmt.getColumnInt64(0));
            break;
        case MTP_TYPE_STR:
            packet.putString(stmt.getColumnString(0));
            break;
        default:
            LOGE("unsupported object type\n");
            return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    }
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpSqliteDatabase::getObjectInfo(MtpObjectHandle handle,
                                        MtpDataPacket& packet) {
    char    date[20];

    if (handle != MTP_PARENT_ROOT)
        handle &= kObjectHandleIndexMask;

    mObjectInfoQuery->reset();
    mObjectInfoQuery->bind(1, handle);
    if (!mObjectInfoQuery->step())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;

    MtpStorageID storageID = mObjectInfoQuery->getColumnInt(0);
    MtpObjectFormat format = mObjectInfoQuery->getColumnInt(1);
    MtpObjectHandle parent = mObjectInfoQuery->getColumnInt(2);
    // extract name from path.  do we want a separate database entry for this?
    const char* name = mObjectInfoQuery->getColumnString(3);
    const char* lastSlash = strrchr(name, '/');
    if (lastSlash)
        name = lastSlash + 1;
    int64_t size = mObjectInfoQuery->getColumnInt64(4);
    time_t modified = mObjectInfoQuery->getColumnInt(5);
    int associationType = (format == MTP_FORMAT_ASSOCIATION ?
                            MTP_ASSOCIATION_TYPE_GENERIC_FOLDER :
                            MTP_ASSOCIATION_TYPE_UNDEFINED);

    LOGV("storageID: %d, format: %d, parent: %d", storageID, format, parent);

    packet.putUInt32(storageID);
    packet.putUInt16(format);
    packet.putUInt16(0);   // protection status
    packet.putUInt32((size > 0xFFFFFFFFLL ? 0xFFFFFFFF : size));
    packet.putUInt16(0);   // thumb format
    packet.putUInt32(0);   // thumb compressed size
    packet.putUInt32(0);   // thumb pix width
    packet.putUInt32(0);   // thumb pix height
    packet.putUInt32(0);   // image pix width
    packet.putUInt32(0);   // image pix height
    packet.putUInt32(0);   // image bit depth
    packet.putUInt32(parent);
    packet.putUInt16(associationType);
    packet.putUInt32(0);   // association desc
    packet.putUInt32(0);   // sequence number
    packet.putString(name);   // file name
    packet.putEmptyString();
    formatDateTime(modified, date, sizeof(date));
    packet.putString(date);   // date modified
    packet.putEmptyString();   // keywords

    return MTP_RESPONSE_OK;
}

bool MtpSqliteDatabase::getObjectFilePath(MtpObjectHandle handle,
                                    MtpString& filePath,
                                    int64_t& fileLength) {
    if (handle != MTP_PARENT_ROOT)
        handle &= kObjectHandleIndexMask;
    mFilePathQuery->reset();
    mFilePathQuery->bind(1, handle);
    if (!mFilePathQuery->step())
        return false;

    const char* path = mFilePathQuery->getColumnString(0);
    if (!path)
        return false;
    filePath = path;
    fileLength = mFilePathQuery->getColumnInt64(1);
    return true;
}

bool MtpSqliteDatabase::deleteFile(MtpObjectHandle handle) {
    uint32_t table = handle & kObjectHandleTableMask;
    handle &= kObjectHandleIndexMask;
    mFileDeleter->bind(1, handle);
    mFileDeleter->step();
    mFileDeleter->reset();

    return true;
}

MtpObjectHandle* MtpSqliteDatabase::getFileList(int& outCount) {
    MtpObjectHandle* result = NULL;
    int count = 0;
    SqliteStatement stmt(mDatabase);
    stmt.prepare("SELECT count(*) FROM files;");

    if (stmt.step())
        count = stmt.getColumnInt(0);

    if (count > 0) {
        result = new MtpObjectHandle[count];
        memset(result, 0, count * sizeof(*result));
        SqliteStatement stmt2(mDatabase);
        stmt2.prepare("SELECT _id,format FROM files;");

        for (int i = 0; i < count; i++) {
            if (!stmt2.step()) {
                LOGW("getFileList ended early");
                count = i;
                break;
            }
            MtpObjectHandle handle = stmt2.getColumnInt(0);
            MtpObjectFormat format = stmt2.getColumnInt(1);
            result[i] = handle;
        }
    }
    outCount = count;
    return result;
}

void MtpSqliteDatabase::beginTransaction() {
    mDatabase->beginTransaction();
}

void MtpSqliteDatabase::commitTransaction() {
    mDatabase->commitTransaction();
}

void MtpSqliteDatabase::rollbackTransaction() {
    mDatabase->rollbackTransaction();
}

}  // namespace android
