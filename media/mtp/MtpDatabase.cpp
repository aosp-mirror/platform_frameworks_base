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

#include "MtpDatabase.h"
#include "MtpDataPacket.h"
#include "SqliteDatabase.h"
#include "SqliteStatement.h"

#include <stdio.h>
#include <sqlite3.h>

namespace android {

#define ID_COLUMN       1
#define PATH_COLUMN     2
#define FORMAT_COLUMN   3
#define PARENT_COLUMN   4
#define STORAGE_COLUMN  5
#define SIZE_COLUMN     6
#define CREATED_COLUMN  7
#define MODIFIED_COLUMN 8

#define TABLE_CREATE    "CREATE TABLE IF NOT EXISTS files ("    \
                        "_id INTEGER PRIMARY KEY,"              \
                        "path TEXT,"                            \
                        "format INTEGER,"                       \
                        "parent INTEGER,"                       \
                        "storage INTEGER,"                      \
                        "size INTEGER,"                         \
                        "date_created INTEGER,"                 \
                        "date_modified INTEGER"                 \
                        ");"

#define PATH_INDEX_CREATE "CREATE INDEX IF NOT EXISTS path_index on files(path);"

#define FILE_ID_QUERY   "SELECT _id FROM files WHERE path = ?;"
#define FILE_PATH_QUERY "SELECT path,size FROM files WHERE _id = ?"

#define GET_OBJECT_INFO_QUERY   "SELECT storage,format,parent,path,size,date_created,date_modified FROM files WHERE _id = ?;"
#define FILE_INSERT     "INSERT INTO files VALUES(?,?,?,?,?,?,?,?);"
#define FILE_DELETE     "DELETE FROM files WHERE path = ?;"

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
    {   MTP_PROPERTY_DATE_CREATED,      MTP_TYPE_STR,       "date_created"  },
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


MtpDatabase::MtpDatabase()
    :   mFileIdQuery(NULL),
        mObjectInfoQuery(NULL),
        mFileInserter(NULL),
        mFileDeleter(NULL)
{
}

MtpDatabase::~MtpDatabase() {
}

bool MtpDatabase::open(const char* path, bool create) {
    if (!SqliteDatabase::open(path, create))
        return false;

    // create the table if necessary
    if (!exec(TABLE_CREATE)) {
        fprintf(stderr, "could not create table\n");
        return false;
    }
    if (!exec(PATH_INDEX_CREATE)) {
        fprintf(stderr, "could not path index\n");
        return false;
    }
    return true;
}

MtpObjectHandle MtpDatabase::addFile(const char* path,
                                    MtpObjectFormat format,
                                    MtpObjectHandle parent,
                                    MtpStorageID storage,
                                    uint64_t size,
                                    time_t created,
                                    time_t modified) {

    // first check to see if the file exists
    if (mFileIdQuery)
        mFileIdQuery->reset();
    else {
        mFileIdQuery = new SqliteStatement(this);
        if (!mFileIdQuery->prepare(FILE_ID_QUERY)) {
            fprintf(stderr, "could not compile FILE_ID_QUERY\n");
            delete mFileIdQuery;
            mFileIdQuery = NULL;
            return kInvalidObjectHandle;
        }
    }

    mFileIdQuery->bind(1, path);
    if (mFileIdQuery->step()) {
        int row = mFileIdQuery->getColumnInt(0);
        if (row > 0)
            return row;
    }

    if (!mFileInserter) {
        mFileInserter = new SqliteStatement(this);
        if (!mFileInserter->prepare(FILE_INSERT)) {
            fprintf(stderr, "could not compile FILE_INSERT\n");
            delete mFileInserter;
            mFileInserter = NULL;
            return kInvalidObjectHandle;
        }
    }
    mFileInserter->bind(PATH_COLUMN, path);
    mFileInserter->bind(FORMAT_COLUMN, format);
    mFileInserter->bind(PARENT_COLUMN, parent);
    mFileInserter->bind(STORAGE_COLUMN, storage);
    mFileInserter->bind(SIZE_COLUMN, size);
    mFileInserter->bind(CREATED_COLUMN, created);
    mFileInserter->bind(MODIFIED_COLUMN, modified);
    mFileInserter->step();
    mFileInserter->reset();
    int row = lastInsertedRow();
    return (row > 0 ? row : kInvalidObjectHandle);
}

MtpObjectHandleList* MtpDatabase::getObjectList(MtpStorageID storageID,
                                                MtpObjectFormat format,
                                                MtpObjectHandle parent) {
    bool                whereStorage = (storageID != 0xFFFFFFFF);
    bool                whereFormat = (format != 0);
    bool                whereParent = (parent != 0);
    char                intBuffer[20];

    MtpString  query("SELECT _id FROM files");
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
        snprintf(intBuffer, sizeof(intBuffer), "%d", parent);
        if (whereStorage || whereFormat)
            query += " AND";
        query += " parent = ";
        query += intBuffer;
    }
    query += ";";

    SqliteStatement stmt(this);
    printf("%s\n", (const char *)query);
    stmt.prepare(query);

    MtpObjectHandleList* list = new MtpObjectHandleList();
    while (!stmt.isDone()) {
        if (stmt.step()) {
            int index = stmt.getColumnInt(0);
            printf("stmt.getColumnInt returned %d\n", index);
            if (index > 0)
                list->push(index);
        }
    }
    printf("list size: %d\n", list->size());
    return list;
}

MtpResponseCode MtpDatabase::getObjectProperty(MtpObjectHandle handle,
                                    MtpObjectProperty property,
                                    MtpDataPacket& packet) {
    int         type;
    const char* columnName;
    char        intBuffer[20];

    if (!getPropertyInfo(property, type, columnName))
        return MTP_RESPONSE_INVALID_OBJECT_PROP_CODE;
    snprintf(intBuffer, sizeof(intBuffer), "%d", handle);

    MtpString  query("SELECT ");
    query += columnName;
    query += " FROM files WHERE _id = ";
    query += intBuffer;
    query += ";";

    SqliteStatement stmt(this);
    printf("%s\n", (const char *)query);
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
            fprintf(stderr, "unsupported object type\n");
            return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    }
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpDatabase::getObjectInfo(MtpObjectHandle handle,
                                        MtpDataPacket& packet) {
    char    date[20];

    if (mObjectInfoQuery)
        mObjectInfoQuery->reset();
    else {
        mObjectInfoQuery = new SqliteStatement(this);
        if (!mObjectInfoQuery->prepare(GET_OBJECT_INFO_QUERY)) {
            fprintf(stderr, "could not compile FILE_ID_QUERY\n");
            delete mObjectInfoQuery;
            mObjectInfoQuery = NULL;
            return MTP_RESPONSE_GENERAL_ERROR;
        }
    }

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
    time_t created = mObjectInfoQuery->getColumnInt(5);
    time_t modified = mObjectInfoQuery->getColumnInt(6);
    int associationType = (format == MTP_FORMAT_ASSOCIATION ?
                            MTP_ASSOCIATION_TYPE_GENERIC_FOLDER :
                            MTP_ASSOCIATION_TYPE_UNDEFINED);

    printf("storageID: %d, format: %d, parent: %d\n", storageID, format, parent);

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
    formatDateTime(created, date, sizeof(date));
    packet.putString(date);   // date created
    formatDateTime(modified, date, sizeof(date));
    packet.putString(date);   // date modified
    packet.putEmptyString();   // keywords

    return MTP_RESPONSE_OK;
}

bool MtpDatabase::getObjectFilePath(MtpObjectHandle handle,
                                    MtpString& filePath,
                                    int64_t& fileLength) {
    if (mFilePathQuery)
        mFilePathQuery->reset();
    else {
        mFilePathQuery = new SqliteStatement(this);
        if (!mFilePathQuery->prepare(FILE_PATH_QUERY)) {
            fprintf(stderr, "could not compile FILE_ID_QUERY\n");
            delete mFilePathQuery;
            mFilePathQuery = NULL;
            return kInvalidObjectHandle;
        }
    }

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

bool MtpDatabase::deleteFile(MtpObjectHandle handle) {
    if (!mFileDeleter) {
        mFileDeleter = new SqliteStatement(this);
        if (!mFileDeleter->prepare(FILE_DELETE)) {
            fprintf(stderr, "could not compile FILE_DELETE\n");
            delete mFileDeleter;
            mFileDeleter = NULL;
            return false;
        }
    }
printf("deleteFile %d\n", handle);
    mFileDeleter->bind(1, handle);
    mFileDeleter->step();
    mFileDeleter->reset();
    return true;
}

/*
    for getObjectPropDesc

    packet.putUInt16(property);
    packet.putUInt16(dataType);
    packet.putUInt8(getSet);
    // default value DTS
    packet.putUInt32(groupCode);
    packet.putUInt8(formFlag);
    // form, variable
*/

}  // namespace android
