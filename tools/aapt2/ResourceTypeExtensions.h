/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_RESOURCE_TYPE_EXTENSIONS_H
#define AAPT_RESOURCE_TYPE_EXTENSIONS_H

#include <androidfw/ResourceTypes.h>

namespace aapt {

/**
 * New android::ResChunk_header types defined
 * for AAPT to use.
 *
 * TODO(adamlesinski): Consider reserving these
 * enums in androidfw/ResourceTypes.h to avoid
 * future collisions.
 */
enum {
    RES_TABLE_PUBLIC_TYPE = 0x000d,

    /**
     * A chunk that holds the string pool
     * for source entries (path/to/source:line).
     */
    RES_TABLE_SOURCE_POOL_TYPE = 0x000e,

    /**
     * A chunk holding names of externally
     * defined symbols and offsets to where
     * they are referenced in the table.
     */
    RES_TABLE_SYMBOL_TABLE_TYPE = 0x000f,
};

/**
 * New resource types that are meant to only be used
 * by AAPT and will not end up on the device.
 */
struct ExtendedTypes {
    enum {
        /**
         * A raw string value that hasn't had its escape sequences
         * processed nor whitespace removed.
         */
        TYPE_RAW_STRING = 0xfe
    };
};

struct Public_header {
    android::ResChunk_header header;

    /**
     * The ID of the type this structure refers to.
     */
    uint8_t typeId;

    /**
     * Reserved. Must be 0.
     */
    uint8_t res0;

    /**
     * Reserved. Must be 0.
     */
    uint16_t res1;

    /**
     * Number of public entries.
     */
    uint32_t count;
};

struct Public_entry {
    uint16_t entryId;
    uint16_t res0;
    android::ResStringPool_ref key;
    android::ResStringPool_ref source;
    uint32_t sourceLine;
};

/**
 * A chunk with type RES_TABLE_SYMBOL_TABLE_TYPE.
 * Following the header are count number of SymbolTable_entry
 * structures, followed by an android::ResStringPool_header.
 */
struct SymbolTable_header {
    android::ResChunk_header header;

    /**
     * Number of SymbolTable_entry structures following
     * this header.
     */
    uint32_t count;
};

struct SymbolTable_entry {
    /**
     * Offset from the beginning of the resource table
     * where the symbol entry is referenced.
     */
    uint32_t offset;

    /**
     * The index into the string pool where the name of this
     * symbol exists.
     */
    uint32_t stringIndex;
};

/**
 * A structure representing the source of a resourc entry.
 * Appears after an android::ResTable_entry or android::ResTable_map_entry.
 *
 * TODO(adamlesinski): This causes some issues when runtime code checks
 * the size of an android::ResTable_entry. It assumes it is an
 * android::ResTable_map_entry if the size is bigger than an android::ResTable_entry
 * which may not be true if this structure is present.
 */
struct ResTable_entry_source {
    /**
     * Index into the source string pool.
     */
    uint32_t pathIndex;

    /**
     * Line number this resource was defined on.
     */
    uint32_t line;
};

} // namespace aapt

#endif // AAPT_RESOURCE_TYPE_EXTENSIONS_H
