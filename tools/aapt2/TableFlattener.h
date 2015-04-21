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

#ifndef AAPT_TABLE_FLATTENER_H
#define AAPT_TABLE_FLATTENER_H

#include "BigBuffer.h"
#include "ResourceTable.h"

namespace aapt {

using SymbolEntryVector = std::vector<std::pair<ResourceNameRef, uint32_t>>;

struct FlatEntry;

/**
 * Flattens a ResourceTable into a binary format suitable
 * for loading into a ResTable on the host or device.
 */
struct TableFlattener {
    /**
     * A set of options for this TableFlattener.
     */
    struct Options {
        /**
         * Specifies whether to output extended chunks, like
         * source information and mising symbol entries. Default
         * is true.
         *
         * Set this to false when emitting the final table to be used
         * on device.
         */
        bool useExtendedChunks = true;
    };

    TableFlattener(Options options);

    bool flatten(BigBuffer* out, const ResourceTable& table);

private:
    bool flattenValue(BigBuffer* out, const FlatEntry& flatEntry, SymbolEntryVector* symbols);

    Options mOptions;
};

} // namespace aapt

#endif // AAPT_TABLE_FLATTENER_H
