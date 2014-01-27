/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef _UTILS_BACKUP_HELPERS_H
#define _UTILS_BACKUP_HELPERS_H

#include <utils/Errors.h>
#include <utils/String8.h>
#include <utils/KeyedVector.h>

namespace android {

enum {
    BACKUP_HEADER_ENTITY_V1 = 0x61746144, // Data (little endian)
};

typedef struct {
    int type; // BACKUP_HEADER_ENTITY_V1
    int keyLen; // length of the key name, not including the null terminator
    int dataSize; // size of the data, not including the padding, -1 means delete
} entity_header_v1;

struct SnapshotHeader {
    int magic0;
    int fileCount;
    int magic1;
    int totalSize;
};

struct FileState {
    int modTime_sec;
    int modTime_nsec;
    int mode;
    int size;
    int crc32;
    int nameLen;
};

struct FileRec {
    String8 file;
    bool deleted;
    FileState s;
};


/**
 * Writes the data.
 *
 * If an error occurs, it poisons this object and all write calls will fail
 * with the error that occurred.
 */
class BackupDataWriter
{
public:
    BackupDataWriter(int fd);
    // does not close fd
    ~BackupDataWriter();

    status_t WriteEntityHeader(const String8& key, size_t dataSize);

    /* Note: WriteEntityData will write arbitrary data into the file without
     * validation or a previously-supplied header.  The full backup implementation
     * uses it this way to generate a controlled binary stream that is not
     * entity-structured.  If the implementation here is changed, either this
     * use case must remain valid, or the full backup implementation should be
     * adjusted to use some other appropriate mechanism.
     */
    status_t WriteEntityData(const void* data, size_t size);

    void SetKeyPrefix(const String8& keyPrefix);

private:
    explicit BackupDataWriter();
    status_t write_padding_for(int n);
    
    int m_fd;
    status_t m_status;
    ssize_t m_pos;
    int m_entityCount;
    String8 m_keyPrefix;
};

/**
 * Reads the data.
 *
 * If an error occurs, it poisons this object and all write calls will fail
 * with the error that occurred.
 */
class BackupDataReader
{
public:
    BackupDataReader(int fd);
    // does not close fd
    ~BackupDataReader();

    status_t Status();
    status_t ReadNextHeader(bool* done, int* type);

    bool HasEntities();
    status_t ReadEntityHeader(String8* key, size_t* dataSize);
    status_t SkipEntityData(); // must be called with the pointer at the beginning of the data.
    ssize_t ReadEntityData(void* data, size_t size);

private:
    explicit BackupDataReader();
    status_t skip_padding();
    
    int m_fd;
    bool m_done;
    status_t m_status;
    ssize_t m_pos;
    ssize_t m_dataEndPos;
    int m_entityCount;
    union {
        int type;
        entity_header_v1 entity;
    } m_header;
    String8 m_key;
};

int back_up_files(int oldSnapshotFD, BackupDataWriter* dataStream, int newSnapshotFD,
        char const* const* files, char const* const *keys, int fileCount);

int write_tarfile(const String8& packageName, const String8& domain,
        const String8& rootPath, const String8& filePath, BackupDataWriter* outputStream);

class RestoreHelperBase
{
public:
    RestoreHelperBase();
    ~RestoreHelperBase();

    status_t WriteFile(const String8& filename, BackupDataReader* in);
    status_t WriteSnapshot(int fd);

private:
    void* m_buf;
    bool m_loggedUnknownMetadata;
    KeyedVector<String8,FileRec> m_files;
};

#define TEST_BACKUP_HELPERS 1

#if TEST_BACKUP_HELPERS
int backup_helper_test_empty();
int backup_helper_test_four();
int backup_helper_test_files();
int backup_helper_test_null_base();
int backup_helper_test_missing_file();
int backup_helper_test_data_writer();
int backup_helper_test_data_reader();
#endif

} // namespace android

#endif // _UTILS_BACKUP_HELPERS_H
