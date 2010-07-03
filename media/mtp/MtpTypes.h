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

#ifndef _MTP_TYPES_H
#define _MTP_TYPES_H

#include <stdint.h>
#include "utils/String8.h"
#include "utils/Vector.h"

namespace android {

typedef int32_t int128_t[4];
typedef uint32_t uint128_t[4];

typedef uint16_t MtpOperationCode;
typedef uint16_t MtpResponseCode;
typedef uint32_t MtpSessionID;
typedef uint32_t MtpStorageID;
typedef uint32_t MtpTransactionID;
typedef uint16_t MtpPropertyCode;
typedef uint16_t MtpDataType;
typedef uint16_t MtpObjectFormat;
typedef MtpPropertyCode MtpDeviceProperty;
typedef MtpPropertyCode MtpObjectProperty;

// object handles are unique across all storage but only within a single session.
// object handles cannot be reused after an object is deleted.
// values 0x00000000 and 0xFFFFFFFF are reserved for special purposes.
typedef uint32_t MtpObjectHandle;

union MtpPropertyValue {
    int8_t          i8;
    uint8_t         u8;
    int16_t         i16;
    uint16_t        u16;
    int32_t         i32;
    uint32_t        u32;
    int64_t         i64;
    uint64_t        u64;
    int128_t        i128;
    uint128_t       u128;
    char*           str;
};

// Special values
#define MTP_PARENT_ROOT         0xFFFFFFFF       // parent is root of the storage
#define kInvalidObjectHandle    0xFFFFFFFF

// MtpObjectHandle bits and masks
#define kObjectHandleMarkBit        0x80000000      // used for mark & sweep by MtpMediaScanner
#define kObjectHandleTableMask      0x70000000      // mask for object table
#define kObjectHandleIndexMask      0x0FFFFFFF      // mask for object index in file table

class MtpStorage;
class MtpDevice;
class MtpProperty;

typedef Vector<MtpStorage *> MtpStorageList;
typedef Vector<MtpDevice*> MtpDeviceList;
typedef Vector<MtpProperty*> MtpPropertyList;

typedef Vector<uint8_t> UInt8List;
typedef Vector<uint16_t> UInt16List;
typedef Vector<uint32_t> UInt32List;
typedef Vector<uint64_t> UInt64List;
typedef Vector<int8_t> Int8List;
typedef Vector<int16_t> Int16List;
typedef Vector<int32_t> Int32List;
typedef Vector<int64_t> Int64List;

typedef UInt16List MtpDevicePropertyList;
typedef UInt16List MtpObjectFormatList;
typedef UInt32List MtpObjectHandleList;
typedef UInt16List MtpObjectPropertyList;
typedef UInt32List MtpStorageIDList;

typedef String8    MtpString;

}; // namespace android

#endif // _MTP_TYPES_H
