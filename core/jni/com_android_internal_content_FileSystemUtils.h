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

#include <sys/types.h>

namespace android {

/*
 * This function deallocates space used by zero padding at the end of LOAD segments in given
 * uncompressed ELF file. Read ELF headers and find out the offset and sizes of LOAD segments.
 * [fallocate(2)](http://man7.org/linux/man-pages/man2/fallocate.2.html) is used to deallocate the
 * zero ranges at the end of LOAD segments. If ELF file is present inside of ApK/Zip file, offset to
 * the start of the ELF file should be provided.
 */
bool punchHolesInElf64(const char* filePath, uint64_t offset);

} // namespace android