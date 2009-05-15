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

int back_up_files(int oldSnapshotFD, int oldDataStream, int newSnapshotFD,
        char const* fileBase, char const* const* files, int fileCount);

/**
 * Reads the data.
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

    status_t WriteAppHeader(const String8& packageName);

    status_t WriteEntityHeader(const String8& key, size_t dataSize);
    status_t WriteEntityData(const void* data, size_t size);

    status_t WriteAppFooter();

private:
    explicit BackupDataWriter();
    status_t write_padding_for(int n);
    
    int m_fd;
    status_t m_status;
    ssize_t m_pos;
    int m_entityCount;
};

#define TEST_BACKUP_HELPERS 0

#if TEST_BACKUP_HELPERS
int backup_helper_test_empty();
int backup_helper_test_four();
int backup_helper_test_files();
int backup_helper_test_data_writer();
#endif

} // namespace android

#endif // _UTILS_BACKUP_HELPERS_H
