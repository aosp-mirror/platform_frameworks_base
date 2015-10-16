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

#ifndef AAPT_FLATTEN_TABLEFLATTENER_H
#define AAPT_FLATTEN_TABLEFLATTENER_H

#include "process/IResourceTableConsumer.h"

namespace aapt {

class BigBuffer;
class ResourceTable;

struct TableFlattenerOptions {
    /**
     * Specifies whether to output extended chunks, like
     * source information and missing symbol entries. Default
     * is false.
     *
     * Set this to true when emitting intermediate resource table.
     */
    bool useExtendedChunks = false;
};

class TableFlattener : public IResourceTableConsumer {
public:
    TableFlattener(BigBuffer* buffer, TableFlattenerOptions options) :
            mBuffer(buffer), mOptions(options) {
    }

    bool consume(IAaptContext* context, ResourceTable* table) override;

private:
    BigBuffer* mBuffer;
    TableFlattenerOptions mOptions;
};

} // namespace aapt

#endif /* AAPT_FLATTEN_TABLEFLATTENER_H */
