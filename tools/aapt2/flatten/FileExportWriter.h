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

#ifndef AAPT_FLATTEN_FILEEXPORTWRITER_H
#define AAPT_FLATTEN_FILEEXPORTWRITER_H

#include "StringPool.h"

#include "flatten/ResourceTypeExtensions.h"
#include "flatten/ChunkWriter.h"
#include "process/IResourceTableConsumer.h"
#include "util/BigBuffer.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <utils/misc.h>

namespace aapt {

static ChunkWriter wrapBufferWithFileExportHeader(BigBuffer* buffer, ResourceFile* res) {
    ChunkWriter fileExportWriter(buffer);
    FileExport_header* fileExport = fileExportWriter.startChunk<FileExport_header>(
            RES_FILE_EXPORT_TYPE);

    ExportedSymbol* symbolRefs = nullptr;
    if (!res->exportedSymbols.empty()) {
        symbolRefs = fileExportWriter.nextBlock<ExportedSymbol>(
                res->exportedSymbols.size());
    }
    fileExport->exportedSymbolCount = util::hostToDevice32(res->exportedSymbols.size());

    StringPool symbolExportPool;
    memcpy(fileExport->magic, "AAPT", NELEM(fileExport->magic));
    fileExport->config = res->config;
    fileExport->config.swapHtoD();
    fileExport->name.index = util::hostToDevice32(symbolExportPool.makeRef(res->name.toString())
                                                  .getIndex());
    fileExport->source.index = util::hostToDevice32(symbolExportPool.makeRef(util::utf8ToUtf16(
            res->source.path)).getIndex());

    for (const SourcedResourceName& name : res->exportedSymbols) {
        symbolRefs->name.index = util::hostToDevice32(symbolExportPool.makeRef(name.name.toString())
                                                      .getIndex());
        symbolRefs->line = util::hostToDevice32(name.line);
        symbolRefs++;
    }

    StringPool::flattenUtf16(fileExportWriter.getBuffer(), symbolExportPool);
    return fileExportWriter;
}

} // namespace aapt

#endif /* AAPT_FLATTEN_FILEEXPORTWRITER_H */
