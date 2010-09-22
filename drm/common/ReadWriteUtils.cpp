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

#include <ReadWriteUtils.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <utils/FileMap.h>
#include <utils/String8.h>

using namespace android;

#define FAILURE -1

String8 ReadWriteUtils::readBytes(const String8& filePath) {
    FILE* file = NULL;
    file = fopen(filePath.string(), "r");

    String8 string("");
    if (NULL != file) {
        int fd = fileno(file);
        struct stat sb;

        if (fstat(fd, &sb) == 0 && sb.st_size > 0) {
            FileMap* fileMap = new FileMap();
            if (fileMap->create(filePath.string(), fd, 0, sb.st_size, true)) {
                char* addr = (char*)fileMap->getDataPtr();
                string.append(addr, sb.st_size);
                fileMap->release();
            }
        }
        fclose(file);
    }
    return string;
}

void ReadWriteUtils::writeToFile(const String8& filePath, const String8& data) {
    FILE* file = NULL;
    file = fopen(filePath.string(), "w+");

    if (NULL != file) {
        int fd = fileno(file);

        int size = data.size();
        if (FAILURE != ftruncate(fd, size)) {
            FileMap* fileMap = NULL;
            fileMap = new FileMap();
            if (fileMap->create(filePath.string(), fd, 0, size, false)) {
                char* addr = (char*)fileMap->getDataPtr();
                memcpy(addr, data.string(), size);
                fileMap->release();
            }
        }
        fclose(file);
    }
}

void ReadWriteUtils::appendToFile(const String8& filePath, const String8& data) {
    FILE* file = NULL;
    file = fopen(filePath.string(), "a+");

    if (NULL != file) {
        int fd = fileno(file);

        int offset = lseek(fd, 0, SEEK_END);
        if (FAILURE != offset) {
            int newEntrySize = data.size();
            int fileSize = offset + newEntrySize;

            if (FAILURE != ftruncate(fd, fileSize)) {
                FileMap* fileMap = NULL;
                fileMap = new FileMap();
                if (fileMap->create(filePath.string(), fd, offset, fileSize, false)) {
                    char* addr = (char*)fileMap->getDataPtr();
                    memcpy(addr, data.string(), data.size());
                    fileMap->release();
                }
            }
        }
        fclose(file);
    }
}

