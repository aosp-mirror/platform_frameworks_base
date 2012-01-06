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

//#define LOG_NDEBUG 0
#define LOG_TAG "ReadWriteUtils"
#include <utils/Log.h>

#include <ReadWriteUtils.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
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
            off64_t length = sb.st_size;
            char* bytes = new char[length];
            if (length == read(fd, (void*) bytes, length)) {
                string.append(bytes, length);
            }
            delete bytes;
        }
        fclose(file);
    }
    return string;
}

int ReadWriteUtils::readBytes(const String8& filePath, char** buffer) {
    FILE* file = NULL;
    file = fopen(filePath.string(), "r");
    off64_t length = 0;

    if (NULL != file) {
        int fd = fileno(file);
        struct stat sb;

        if (fstat(fd, &sb) == 0 && sb.st_size > 0) {
            length = sb.st_size;
            *buffer = new char[length];
            if (length != read(fd, (void*) *buffer, length)) {
                length = FAILURE;
            }
        }
        fclose(file);
    }
    return length;
}

void ReadWriteUtils::writeToFile(const String8& filePath, const String8& data) {
    FILE* file = NULL;
    file = fopen(filePath.string(), "w+");

    if (NULL != file) {
        int fd = fileno(file);

        int size = data.size();
        if (FAILURE != ftruncate(fd, size)) {
            if (size != write(fd, data.string(), size)) {
                ALOGE("Failed to write the data to: %s", filePath.string());
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

        int size = data.size();
        if (size != write(fd, data.string(), size)) {
            ALOGE("Failed to write the data to: %s", filePath.string());
        }
        fclose(file);
    }
}

