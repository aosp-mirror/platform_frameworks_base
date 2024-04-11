/*
 * Copyright (C) 2024 The Android Open Source Project
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

#define LOG_TAG "FileSystemUtils"

#include "com_android_internal_content_FileSystemUtils.h"

#include <android-base/file.h>
#include <android-base/hex.h>
#include <android-base/unique_fd.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/fs.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <utils/Log.h>

#include <array>
#include <fstream>
#include <vector>

using android::base::HexString;
using android::base::ReadFullyAtOffset;

namespace android {
bool punchHoles(const char *filePath, const uint64_t offset,
                const std::vector<Elf64_Phdr> &programHeaders) {
    struct stat64 beforePunch;
    lstat64(filePath, &beforePunch);
    uint64_t blockSize = beforePunch.st_blksize;
    IF_ALOGD() {
        ALOGD("Total number of LOAD segments %zu", programHeaders.size());

        ALOGD("Size before punching holes st_blocks: %" PRIu64
              ", st_blksize: %ld, st_size: %" PRIu64 "",
              beforePunch.st_blocks, beforePunch.st_blksize,
              static_cast<uint64_t>(beforePunch.st_size));
    }

    android::base::unique_fd fd(open(filePath, O_RDWR | O_CLOEXEC));
    if (!fd.ok()) {
        ALOGE("Can't open file to punch %s", filePath);
        return false;
    }

    // read in chunks of 64KB
    constexpr uint64_t kChunkSize = 64 * 1024;

    // malloc is used to gracefully handle oom which might occur during the allocation of buffer.
    // allocating using new or vector here results in oom/exception on failure where as malloc will
    // return nullptr.
    std::unique_ptr<uint8_t, decltype(&free)> buffer(static_cast<uint8_t *>(malloc(kChunkSize)),
                                                     &free);
    if (buffer == nullptr) {
        ALOGE("Failed to allocate read buffer");
        return false;
    }

    for (size_t index = 0; programHeaders.size() >= 2 && index < programHeaders.size() - 1;
         index++) {
        // find LOAD segments from program headers, calculate padding and punch holes
        uint64_t punchOffset;
        if (__builtin_add_overflow(programHeaders[index].p_offset, programHeaders[index].p_filesz,
                                   &punchOffset)) {
            ALOGE("Overflow occurred when adding offset and filesize");
            return false;
        }

        uint64_t punchLen;
        if (__builtin_sub_overflow(programHeaders[index + 1].p_offset, punchOffset, &punchLen)) {
            ALOGE("Overflow occurred when calculating length");
            return false;
        }

        if (punchLen < blockSize) {
            continue;
        }

        uint64_t punchStartOffset;
        if (__builtin_add_overflow(offset, punchOffset, &punchStartOffset)) {
            ALOGE("Overflow occurred when calculating length");
            return false;
        }

        uint64_t position = punchStartOffset;
        uint64_t endPosition;
        if (__builtin_add_overflow(position, punchLen, &endPosition)) {
            ALOGE("Overflow occurred when calculating length");
            return false;
        }

        // Read content in kChunkSize and verify it is zero
        while (position <= endPosition) {
            uint64_t uncheckedChunkEnd;
            if (__builtin_add_overflow(position, kChunkSize, &uncheckedChunkEnd)) {
                ALOGE("Overflow occurred when calculating uncheckedChunkEnd");
                return false;
            }

            uint64_t readLength;
            if (__builtin_sub_overflow(std::min(uncheckedChunkEnd, endPosition), position,
                                       &readLength)) {
                ALOGE("Overflow occurred when calculating readLength");
                return false;
            }

            if (!ReadFullyAtOffset(fd, buffer.get(), readLength, position)) {
                ALOGE("Failed to read content to punch holes");
                return false;
            }

            IF_ALOGD() {
                ALOGD("Punching holes for length:%" PRIu64 " content which should be zero: %s",
                      readLength, HexString(buffer.get(), readLength).c_str());
            }

            bool isZero = std::all_of(buffer.get(), buffer.get() + readLength,
                                      [](uint8_t i) constexpr { return i == 0; });
            if (!isZero) {
                ALOGE("Found non zero content while trying to punch hole. Skipping operation");
                return false;
            }

            position = uncheckedChunkEnd;
        }

        // if we have a uncompressed file which is being opened from APK, use the offset to
        // punch native lib inside Apk.
        int result = fallocate(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, punchStartOffset,
                               punchLen);
        if (result < 0) {
            ALOGE("fallocate failed to punch hole, error:%d", errno);
            return false;
        }
    }

    IF_ALOGD() {
        struct stat64 afterPunch;
        lstat64(filePath, &afterPunch);
        ALOGD("Size after punching holes st_blocks: %" PRIu64 ", st_blksize: %ld, st_size: %" PRIu64
              "",
              afterPunch.st_blocks, afterPunch.st_blksize,
              static_cast<uint64_t>(afterPunch.st_size));
    }

    return true;
}

bool punchHolesInElf64(const char *filePath, const uint64_t offset) {
    // Open Elf file
    Elf64_Ehdr ehdr;
    std::ifstream inputStream(filePath, std::ifstream::in);

    // If this is a zip file, set the offset so that we can read elf file directly
    inputStream.seekg(offset);
    // read executable headers
    inputStream.read((char *)&ehdr, sizeof(ehdr));
    if (!inputStream.good()) {
        return false;
    }

    // only consider elf64 for punching holes
    if (ehdr.e_ident[EI_CLASS] != ELFCLASS64) {
        ALOGE("Provided file is not ELF64");
        return false;
    }

    // read the program headers from elf file
    uint64_t programHeaderOffset = ehdr.e_phoff;
    uint16_t programHeaderNum = ehdr.e_phnum;

    IF_ALOGD() {
        ALOGD("Punching holes in file: %s programHeaderOffset: %" PRIu64 " programHeaderNum: %hu",
              filePath, programHeaderOffset, programHeaderNum);
    }

    // if this is a zip file, also consider elf offset inside a file
    uint64_t phOffset;
    if (__builtin_add_overflow(offset, programHeaderOffset, &phOffset)) {
        ALOGE("Overflow occurred when calculating phOffset");
        return false;
    }
    inputStream.seekg(phOffset);

    std::vector<Elf64_Phdr> programHeaders;
    for (int headerIndex = 0; headerIndex < programHeaderNum; headerIndex++) {
        Elf64_Phdr header;
        inputStream.read((char *)&header, sizeof(header));
        if (!inputStream.good()) {
            return false;
        }

        if (header.p_type != PT_LOAD) {
            continue;
        }
        programHeaders.push_back(header);
    }

    return punchHoles(filePath, offset, programHeaders);
}

}; // namespace android
