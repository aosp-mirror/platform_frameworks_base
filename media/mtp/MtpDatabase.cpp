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

#define AUDIO_ID_COLUMN                 1
#define AUDIO_TITLE_COLUMN              2
#define AUDIO_ARTIST_COLUMN             3
#define AUDIO_ALBUM_COLUMN              4
#define AUDIO_ALBUM_ARTIST_COLUMN       5
#define AUDIO_GENRE_COLUMN              6
#define AUDIO_COMPOSER_COLUMN           7
#define AUDIO_TRACK_NUMBER_COLUMN       8
#define AUDIO_YEAR_COLUMN               9
#define AUDIO_DURATION_COLUMN           10
#define AUDIO_USE_COUNT_COLUMN          11
#define AUDIO_SAMPLE_RATE_COLUMN        12
#define AUDIO_NUM_CHANNELS_COLUMN       13
#define AUDIO_AUDIO_WAVE_CODEC_COLUMN   14
#define AUDIO_AUDIO_BIT_RATE_COLUMN     15

#define FILE_TABLE_CREATE    "CREATE TABLE IF NOT EXISTS files ("    \
                        "_id INTEGER PRIMARY KEY,"              \
                        "path TEXT,"                            \
                        "format INTEGER,"                       \
                        "parent INTEGER,"                       \
                        "storage INTEGER,"                      \
                        "size INTEGER,"                         \
                        "date_modified INTEGER"                \
                        ");"

#define AUDIO_TABLE_CREATE    "CREATE TABLE IF NOT EXISTS audio ("    \
                        "id INTEGER PRIMARY KEY,"               \
                        "title TEXT,"                           \
                        "artist TEXT,"                          \
                        "album TEXT,"                           \
                        "album_artist TEXT,"                    \
                        "genre TEXT,"                           \
                        "composer TEXT,"                        \
                        "track_number INTEGER,"                 \
                        "year INTEGER,"                         \
                        "duration INTEGER,"                     \
                        "use_count INTEGER,"                    \
                        "sample_rate INTEGER,"                  \
                        "num_channels INTEGER,"                 \
                        "audio_wave_codec TEXT,"                \
                        "audio_bit_rate INTEGER"                \
                        ");"

#define PATH_INDEX_CREATE "CREATE INDEX IF NOT EXISTS path_index on files(path);"

#define FILE_ID_QUERY   "SELECT _id,format FROM files WHERE path = ?;"
#define FILE_PATH_QUERY "SELECT path,size FROM files WHERE _id = ?"

#define GET_OBJECT_INFO_QUERY   "SELECT storage,format,parent,path,size,date_modified FROM files WHERE _id = ?;"
#define FILE_INSERT     "INSERT INTO files VALUES(?,?,?,?,?,?,?);"
#define FILE_DELETE     "DELETE FROM files WHERE _id = ?;"

#define AUDIO_INSERT    "INSERT INTO audio VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);"
#define AUDIO_DELETE    "DELETE FROM audio WHERE id = ?;"

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



MtpDatabase::MtpDatabase()
    :   mFileIdQuery(NULL),
        mFilePathQuery(NULL),
        mObjectInfoQuery(NULL),
        mFileInserter(NULL),
        mFileDeleter(NULL),
        mAudioInserter(NULL),
        mAudioDeleter(NULL)
{
}

MtpDatabase::~MtpDatabase() {
}

bool MtpDatabase::open(const char* path, bool create) {
    if (!SqliteDatabase::open(path, create))
        return false;

    // create tables and indices if necessary
    if (!exec(FILE_TABLE_CREATE)) {
        fprintf(stderr, "could not create file table\n");
        return false;
    }
    if (!exec(PATH_INDEX_CREATE)) {
        fprintf(stderr, "could not path index on file table\n");
        return false;
    }
    if (!exec(AUDIO_TABLE_CREATE)) {
        fprintf(stderr, "could not create file table\n");
        return false;
    }

    if (!mFileIdQuery) {
        mFileIdQuery = new SqliteStatement(this);
        if (!mFileIdQuery->prepare(FILE_ID_QUERY)) {
            fprintf(stderr, "could not compile FILE_ID_QUERY\n");
            exit(-1);
        }
    }
    if (!mFilePathQuery) {
        mFilePathQuery = new SqliteStatement(this);
        if (!mFilePathQuery->prepare(FILE_PATH_QUERY)) {
            fprintf(stderr, "could not compile FILE_PATH_QUERY\n");
            exit(-1);
        }
    }
    if (!mObjectInfoQuery) {
        mObjectInfoQuery = new SqliteStatement(this);
        if (!mObjectInfoQuery->prepare(GET_OBJECT_INFO_QUERY)) {
            fprintf(stderr, "could not compile GET_OBJECT_INFO_QUERY\n");
            exit(-1);
        }
    }
    if (!mFileInserter) {
        mFileInserter = new SqliteStatement(this);
        if (!mFileInserter->prepare(FILE_INSERT)) {
            fprintf(stderr, "could not compile FILE_INSERT\n");
            exit(-1);
        }
    }
    if (!mFileDeleter) {
        mFileDeleter = new SqliteStatement(this);
        if (!mFileDeleter->prepare(FILE_DELETE)) {
            fprintf(stderr, "could not compile FILE_DELETE\n");
            exit(-1);
        }
    }
    if (!mAudioInserter) {
        mAudioInserter = new SqliteStatement(this);
        if (!mAudioInserter->prepare(AUDIO_INSERT)) {
            fprintf(stderr, "could not compile AUDIO_INSERT\n");
            exit(-1);
        }
    }
    if (!mAudioDeleter) {
        mAudioDeleter = new SqliteStatement(this);
        if (!mAudioDeleter->prepare(AUDIO_DELETE)) {
            fprintf(stderr, "could not compile AUDIO_DELETE\n");
            exit(-1);
        }
    }

    return true;
}

uint32_t MtpDatabase::getTableForFile(MtpObjectFormat format) {
    switch (format) {
        case MTP_FORMAT_AIFF:
        case MTP_FORMAT_WAV:
        case MTP_FORMAT_MP3:
        case MTP_FORMAT_FLAC:
        case MTP_FORMAT_UNDEFINED_AUDIO:
        case MTP_FORMAT_WMA:
        case MTP_FORMAT_OGG:
        case MTP_FORMAT_AAC:
        case MTP_FORMAT_AUDIBLE:
            return kObjectHandleTableAudio;
        case MTP_FORMAT_AVI:
        case MTP_FORMAT_MPEG:
        case MTP_FORMAT_ASF:
        case MTP_FORMAT_UNDEFINED_VIDEO:
        case MTP_FORMAT_WMV:
        case MTP_FORMAT_MP4_CONTAINER:
        case MTP_FORMAT_MP2:
        case MTP_FORMAT_3GP_CONTAINER:
            return kObjectHandleTableVideo;
        case MTP_FORMAT_DEFINED:
        case MTP_FORMAT_EXIF_JPEG:
        case MTP_FORMAT_TIFF_EP:
        case MTP_FORMAT_FLASHPIX:
        case MTP_FORMAT_BMP:
        case MTP_FORMAT_CIFF:
        case MTP_FORMAT_GIF:
        case MTP_FORMAT_JFIF:
        case MTP_FORMAT_CD:
        case MTP_FORMAT_PICT:
        case MTP_FORMAT_PNG:
        case MTP_FORMAT_TIFF:
        case MTP_FORMAT_TIFF_IT:
        case MTP_FORMAT_JP2:
        case MTP_FORMAT_JPX:
        case MTP_FORMAT_WINDOWS_IMAGE_FORMAT:
            return kObjectHandleTableImage;
        case MTP_FORMAT_ABSTRACT_AUDIO_PLAYLIST:
        case MTP_FORMAT_ABSTRACT_AV_PLAYLIST:
        case MTP_FORMAT_ABSTRACT_VIDEO_PLAYLIST:
        case MTP_FORMAT_WPL_PLAYLIST:
        case MTP_FORMAT_M3U_PLAYLIST:
        case MTP_FORMAT_MPL_PLAYLIST:
        case MTP_FORMAT_ASX_PLAYLIST:
        case MTP_FORMAT_PLS_PLAYLIST:
            return kObjectHandleTablePlaylist;
        default:
            return kObjectHandleTableFile;
    }
}

MtpObjectHandle MtpDatabase::getObjectHandle(const char* path) {
    mFileIdQuery->reset();
    mFileIdQuery->bind(1, path);
    if (mFileIdQuery->step()) {
        int row = mFileIdQuery->getColumnInt(0);
        if (row > 0) {
            MtpObjectFormat format = mFileIdQuery->getColumnInt(1);
            row |= getTableForFile(format);
            return row;
        }
    }

    return 0;
}

MtpObjectHandle MtpDatabase::addFile(const char* path,
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
    int result = lastInsertedRow();
    return (result <= 0 ? kInvalidObjectHandle : result);
}

MtpObjectHandle MtpDatabase::addAudioFile(MtpObjectHandle handle) {
    mAudioInserter->bind(AUDIO_ID_COLUMN, handle);
    mAudioInserter->step();
    mAudioInserter->reset();
    int result = lastInsertedRow();
    handle |= kObjectHandleTableAudio;
    return (result > 0 ? handle : kInvalidObjectHandle);
}

MtpObjectHandle MtpDatabase::addAudioFile(MtpObjectHandle handle,
                                    const char* title,
                                    const char* artist,
                                    const char* album,
                                    const char* albumArtist,
                                    const char* genre,
                                    const char* composer,
                                    const char* mimeType,
                                    int track,
                                    int year,
                                    int duration) {
    mAudioInserter->bind(AUDIO_ID_COLUMN, handle);
    if (title) mAudioInserter->bind(AUDIO_TITLE_COLUMN, title);
    if (artist) mAudioInserter->bind(AUDIO_ARTIST_COLUMN, artist);
    if (album) mAudioInserter->bind(AUDIO_ALBUM_COLUMN, album);
    if (albumArtist) mAudioInserter->bind(AUDIO_ALBUM_ARTIST_COLUMN, albumArtist);
    if (genre) mAudioInserter->bind(AUDIO_GENRE_COLUMN, genre);
    if (composer) mAudioInserter->bind(AUDIO_COMPOSER_COLUMN, composer);
    if (track) mAudioInserter->bind(AUDIO_TRACK_NUMBER_COLUMN, track);
    if (year) mAudioInserter->bind(AUDIO_YEAR_COLUMN, year);
    if (duration) mAudioInserter->bind(AUDIO_DURATION_COLUMN, duration);
    mAudioInserter->step();
    mAudioInserter->reset();
    int result = lastInsertedRow();
    if (result <= 0)
        return kInvalidObjectHandle;
    result |= kObjectHandleTableAudio;
    return result;
}

MtpObjectHandleList* MtpDatabase::getObjectList(MtpStorageID storageID,
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

    SqliteStatement stmt(this);
    printf("%s\n", (const char *)query);
    stmt.prepare(query);

    MtpObjectHandleList* list = new MtpObjectHandleList();
    while (!stmt.isDone()) {
        if (stmt.step()) {
            int index = stmt.getColumnInt(0);
            printf("stmt.getColumnInt returned %d\n", index);
            if (index > 0) {
                MtpObjectFormat format = stmt.getColumnInt(1);
                index |= getTableForFile(format);
                list->push(index);
            }
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
    packet.putEmptyString();
    formatDateTime(modified, date, sizeof(date));
    packet.putString(date);   // date modified
    packet.putEmptyString();   // keywords

    return MTP_RESPONSE_OK;
}

bool MtpDatabase::getObjectFilePath(MtpObjectHandle handle,
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

bool MtpDatabase::deleteFile(MtpObjectHandle handle) {
    uint32_t table = handle & kObjectHandleTableMask;
    handle &= kObjectHandleIndexMask;
    mFileDeleter->bind(1, handle);
    mFileDeleter->step();
    mFileDeleter->reset();
    if (table == kObjectHandleTableAudio) {
        mAudioDeleter->bind(1, handle);
        mAudioDeleter->step();
        mAudioDeleter->reset();
    }

    return true;
}

MtpObjectHandle* MtpDatabase::getFileList(int& outCount) {
    MtpObjectHandle* result = NULL;
    int count = 0;
    SqliteStatement stmt(this);
    stmt.prepare("SELECT count(*) FROM files;");

    MtpObjectHandleList* list = new MtpObjectHandleList();
    if (stmt.step())
        count = stmt.getColumnInt(0);

    if (count > 0) {
        result = new MtpObjectHandle[count];
        memset(result, 0, count * sizeof(*result));
        SqliteStatement stmt2(this);
        stmt2.prepare("SELECT _id,format FROM files;");

        for (int i = 0; i < count; i++) {
            if (!stmt2.step()) {
                printf("getFileList ended early\n");
                count = i;
                break;
            }
            MtpObjectHandle handle = stmt2.getColumnInt(0);
            MtpObjectFormat format = stmt2.getColumnInt(1);
            handle |= getTableForFile(format);
            result[i] = handle;
        }
    }
    outCount = count;
    return result;
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
