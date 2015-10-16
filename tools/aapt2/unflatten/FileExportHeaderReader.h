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

#ifndef AAPT_UNFLATTEN_FILEEXPORTHEADERREADER_H
#define AAPT_UNFLATTEN_FILEEXPORTHEADERREADER_H

#include "ResChunkPullParser.h"
#include "Resource.h"
#include "ResourceUtils.h"

#include "flatten/ResourceTypeExtensions.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <sstream>
#include <string>

namespace aapt {

static ssize_t parseFileExportHeaderImpl(const void* data, const size_t len,
                                         const FileExport_header** outFileExport,
                                         const ExportedSymbol** outExportedSymbolIndices,
                                         android::ResStringPool* outStringPool,
                                         std::string* outError) {
    ResChunkPullParser parser(data, len);
    if (!ResChunkPullParser::isGoodEvent(parser.next())) {
        if (outError) *outError = parser.getLastError();
        return -1;
    }

    if (util::deviceToHost16(parser.getChunk()->type) != RES_FILE_EXPORT_TYPE) {
        if (outError) *outError = "no FileExport_header found";
        return -1;
    }

    const FileExport_header* fileExport = convertTo<FileExport_header>(parser.getChunk());
    if (!fileExport) {
        if (outError) *outError = "corrupt FileExport_header";
        return -1;
    }

    if (memcmp(fileExport->magic, "AAPT", sizeof(fileExport->magic)) != 0) {
        if (outError) *outError = "invalid magic value";
        return -1;
    }

    const size_t exportedSymbolCount = util::deviceToHost32(fileExport->exportedSymbolCount);

    // Verify that we have enough space for all those symbols.
    size_t dataLen = getChunkDataLen(&fileExport->header);
    if (exportedSymbolCount > dataLen / sizeof(ExportedSymbol)) {
        if (outError) *outError = "too many symbols";
        return -1;
    }

    const size_t symbolIndicesSize = exportedSymbolCount * sizeof(ExportedSymbol);

    const void* strPoolData = getChunkData(&fileExport->header) + symbolIndicesSize;
    const size_t strPoolDataLen = dataLen - symbolIndicesSize;
    if (outStringPool->setTo(strPoolData, strPoolDataLen, false) != android::NO_ERROR) {
        if (outError) *outError = "corrupt string pool";
        return -1;
    }

    *outFileExport = fileExport;
    *outExportedSymbolIndices = (const ExportedSymbol*) getChunkData(
            &fileExport->header);
    return util::deviceToHost16(fileExport->header.headerSize) + symbolIndicesSize +
            outStringPool->bytes();
}

static ssize_t getWrappedDataOffset(const void* data, size_t len, std::string* outError) {
    const FileExport_header* header = nullptr;
    const ExportedSymbol* entries = nullptr;
    android::ResStringPool pool;
    return parseFileExportHeaderImpl(data, len, &header, &entries, &pool, outError);
}

/**
 * Reads the FileExport_header and populates outRes with the values in that header.
 */
static ssize_t unwrapFileExportHeader(const void* data, size_t len, ResourceFile* outRes,
                                      std::string* outError) {

    const FileExport_header* fileExport = nullptr;
    const ExportedSymbol* entries = nullptr;
    android::ResStringPool symbolPool;
    const ssize_t offset = parseFileExportHeaderImpl(data, len, &fileExport, &entries, &symbolPool,
                                                     outError);
    if (offset < 0) {
        return offset;
    }

    const size_t exportedSymbolCount = util::deviceToHost32(fileExport->exportedSymbolCount);
    outRes->exportedSymbols.clear();
    outRes->exportedSymbols.reserve(exportedSymbolCount);

    for (size_t i = 0; i < exportedSymbolCount; i++) {
        const StringPiece16 str = util::getString(symbolPool,
                                                  util::deviceToHost32(entries[i].name.index));
        StringPiece16 packageStr, typeStr, entryStr;
        ResourceUtils::extractResourceName(str, &packageStr, &typeStr, &entryStr);
        const ResourceType* resType = parseResourceType(typeStr);
        if (!resType || entryStr.empty()) {
            if (outError) {
                std::stringstream errorStr;
                errorStr << "invalid exported symbol at index="
                         << util::deviceToHost32(entries[i].name.index)
                         << " (" << str << ")";
                *outError = errorStr.str();
            }
            return -1;
        }

        outRes->exportedSymbols.push_back(SourcedResourceName{
                ResourceName{ packageStr.toString(), *resType, entryStr.toString() },
                util::deviceToHost32(entries[i].line) });
    }

    const StringPiece16 str = util::getString(symbolPool,
                                              util::deviceToHost32(fileExport->name.index));
    StringPiece16 packageStr, typeStr, entryStr;
    ResourceUtils::extractResourceName(str, &packageStr, &typeStr, &entryStr);
    const ResourceType* resType = parseResourceType(typeStr);
    if (!resType || entryStr.empty()) {
        if (outError) {
            std::stringstream errorStr;
            errorStr << "invalid resource name at index="
                     << util::deviceToHost32(fileExport->name.index)
                     << " (" << str << ")";
            *outError = errorStr.str();
        }
        return -1;
    }

    outRes->name = ResourceName{ packageStr.toString(), *resType, entryStr.toString() };
    outRes->source.path = util::utf16ToUtf8(
            util::getString(symbolPool, util::deviceToHost32(fileExport->source.index)));
    outRes->config.copyFromDtoH(fileExport->config);
    return offset;
}

} // namespace aapt

#endif /* AAPT_UNFLATTEN_FILEEXPORTHEADERREADER_H */
