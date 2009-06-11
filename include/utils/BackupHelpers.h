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

namespace android {

enum {
    BACKUP_HEADER_APP_V1 = 0x31707041, // App1 (little endian)
    BACKUP_HEADER_ENTITY_V1 = 0x61746144, // Data (little endian)
    BACKUP_FOOTER_APP_V1 = 0x746f6f46, // Foot (little endian)
};

// the sizes of all of these match.
typedef struct {
    int type; // == BACKUP_HEADER_APP_V1
    int packageLen; // length of the name of the package that follows, not including the null.
    int cookie;
} app_header_v1;

typedef struct {
    int type; // BACKUP_HEADER_ENTITY_V1
    int keyLen; // length of the key name, not including the null terminator
    int dataSize; // size of the data, not including the padding, -1 means delete
} entity_header_v1;

typedef struct {
    int type; // BACKUP_FOOTER_APP_V1
    int entityCount; // the number of entities that were written
    int cookie;
} app_footer_v1;


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

    status_t WriteAppHeader(const String8& packageName, int cookie);

    status_t WriteEntityHeader(const String8& key, size_t dataSize);
    status_t WriteEntityData(const void* data, size_t size);

    status_t WriteAppFooter(int cookie);

private:
    explicit BackupDataWriter();
    status_t write_padding_for(int n);
    
    int m_fd;
    status_t m_status;
    ssize_t m_pos;
    int m_entityCount;
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
    status_t ReadNextHeader(int* type = NULL);

    status_t ReadAppHeader(String8* packageName, int* cookie);
    bool HasEntities();
    status_t ReadEntityHeader(String8* key, size_t* dataSize);
    status_t SkipEntityData(); // must be called with the pointer at the begining of the data.
    status_t ReadEntityData(void* data, size_t size);
    status_t ReadAppFooter(int* cookie);

private:
    explicit BackupDataReader();
    status_t skip_padding();
    
    int m_fd;
    status_t m_status;
    ssize_t m_pos;
    int m_entityCount;
    union {
        int type;
        app_header_v1 app;
        entity_header_v1 entity;
        app_footer_v1 footer;
    } m_header;
};

int back_up_files(int oldSnapshotFD, BackupDataWriter* dataStream, int newSnapshotFD,
        char const* const* files, char const* const *keys, int fileCount);


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
