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
#pragma once

#include <elf.h>
#include <sys/types.h>

#include <vector>

namespace android {

/*
 * This function deallocates space used by zero padding at the end of LOAD segments in given
 * uncompressed ELF file. Read ELF headers and find out the offset and sizes of LOAD segments.
 * [fallocate(2)](http://man7.org/linux/man-pages/man2/fallocate.2.html) is used to deallocate the
 * zero ranges at the end of LOAD segments. If ELF file is present inside of ApK/Zip file, offset to
 * the start of the ELF file should be provided.
 */
bool punchHolesInElf64(const char* filePath, uint64_t offset);

/*
 * This function punches holes in zero segments of Apk file which are introduced during the
 * alignment. Alignment tools add padding inside of extra field in local file header. punch holes in
 * extra field for zero stretches till the actual file content.
 */
bool punchHolesInZip(const char* filePath, uint64_t offset, uint16_t extraFieldLen);

/*
 * This function reads program headers from ELF file. ELF can be specified with file path directly
 * or it should be at offset inside Apk. Program headers passed to function is populated.
 */
bool getLoadSegmentPhdrs(const char* filePath, const uint64_t offset,
                         std::vector<Elf64_Phdr>& programHeaders);

} // namespace android