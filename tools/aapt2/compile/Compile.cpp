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

#include "ConfigDescription.h"
#include "Diagnostics.h"
#include "Flags.h"
#include "ResourceParser.h"
#include "ResourceTable.h"
#include "compile/IdAssigner.h"
#include "compile/Png.h"
#include "compile/PseudolocaleGenerator.h"
#include "compile/XmlIdCollector.h"
#include "flatten/Archive.h"
#include "flatten/XmlFlattener.h"
#include "proto/ProtoSerialize.h"
#include "util/Files.h"
#include "util/Maybe.h"
#include "util/Util.h"
#include "xml/XmlDom.h"
#include "xml/XmlPullParser.h"

#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
#include <google/protobuf/io/coded_stream.h>

#include <dirent.h>
#include <fstream>
#include <string>

namespace aapt {

struct ResourcePathData {
    Source source;
    std::u16string resourceDir;
    std::u16string name;
    std::string extension;

    // Original config str. We keep this because when we parse the config, we may add on
    // version qualifiers. We want to preserve the original input so the output is easily
    // computed before hand.
    std::string configStr;
    ConfigDescription config;
};

/**
 * Resource file paths are expected to look like:
 * [--/res/]type[-config]/name
 */
static Maybe<ResourcePathData> extractResourcePathData(const std::string& path,
                                                       std::string* outError) {
    std::vector<std::string> parts = util::split(path, file::sDirSep);
    if (parts.size() < 2) {
        if (outError) *outError = "bad resource path";
        return {};
    }

    std::string& dir = parts[parts.size() - 2];
    StringPiece dirStr = dir;

    StringPiece configStr;
    ConfigDescription config;
    size_t dashPos = dir.find('-');
    if (dashPos != std::string::npos) {
        configStr = dirStr.substr(dashPos + 1, dir.size() - (dashPos + 1));
        if (!ConfigDescription::parse(configStr, &config)) {
            if (outError) {
                std::stringstream errStr;
                errStr << "invalid configuration '" << configStr << "'";
                *outError = errStr.str();
            }
            return {};
        }
        dirStr = dirStr.substr(0, dashPos);
    }

    std::string& filename = parts[parts.size() - 1];
    StringPiece name = filename;
    StringPiece extension;
    size_t dotPos = filename.find('.');
    if (dotPos != std::string::npos) {
        extension = name.substr(dotPos + 1, filename.size() - (dotPos + 1));
        name = name.substr(0, dotPos);
    }

    return ResourcePathData{
            Source(path),
            util::utf8ToUtf16(dirStr),
            util::utf8ToUtf16(name),
            extension.toString(),
            configStr.toString(),
            config
    };
}

struct CompileOptions {
    std::string outputPath;
    Maybe<std::string> resDir;
    bool pseudolocalize = false;
    bool legacyMode = false;
    bool verbose = false;
};

static std::string buildIntermediateFilename(const ResourcePathData& data) {
    std::stringstream name;
    name << data.resourceDir;
    if (!data.configStr.empty()) {
        name << "-" << data.configStr;
    }
    name << "_" << data.name;
    if (!data.extension.empty()) {
        name << "." << data.extension;
    }
    name << ".flat";
    return name.str();
}

static bool isHidden(const StringPiece& filename) {
    return util::stringStartsWith<char>(filename, ".");
}

/**
 * Walks the res directory structure, looking for resource files.
 */
static bool loadInputFilesFromDir(IAaptContext* context, const CompileOptions& options,
                                  std::vector<ResourcePathData>* outPathData) {
    const std::string& rootDir = options.resDir.value();
    std::unique_ptr<DIR, decltype(closedir)*> d(opendir(rootDir.data()), closedir);
    if (!d) {
        context->getDiagnostics()->error(DiagMessage() << strerror(errno));
        return false;
    }

    while (struct dirent* entry = readdir(d.get())) {
        if (isHidden(entry->d_name)) {
            continue;
        }

        std::string prefixPath = rootDir;
        file::appendPath(&prefixPath, entry->d_name);

        if (file::getFileType(prefixPath) != file::FileType::kDirectory) {
            continue;
        }

        std::unique_ptr<DIR, decltype(closedir)*> subDir(opendir(prefixPath.data()), closedir);
        if (!subDir) {
            context->getDiagnostics()->error(DiagMessage() << strerror(errno));
            return false;
        }

        while (struct dirent* leafEntry = readdir(subDir.get())) {
            if (isHidden(leafEntry->d_name)) {
                continue;
            }

            std::string fullPath = prefixPath;
            file::appendPath(&fullPath, leafEntry->d_name);

            std::string errStr;
            Maybe<ResourcePathData> pathData = extractResourcePathData(fullPath, &errStr);
            if (!pathData) {
                context->getDiagnostics()->error(DiagMessage() << errStr);
                return false;
            }

            outPathData->push_back(std::move(pathData.value()));
        }
    }
    return true;
}

static bool compileTable(IAaptContext* context, const CompileOptions& options,
                         const ResourcePathData& pathData, IArchiveWriter* writer,
                         const std::string& outputPath) {
    ResourceTable table;
    {
        std::ifstream fin(pathData.source.path, std::ifstream::binary);
        if (!fin) {
            context->getDiagnostics()->error(DiagMessage(pathData.source) << strerror(errno));
            return false;
        }


        // Parse the values file from XML.
        xml::XmlPullParser xmlParser(fin);

        ResourceParserOptions parserOptions;
        parserOptions.errorOnPositionalArguments = !options.legacyMode;

        // If the filename includes donottranslate, then the default translatable is false.
        parserOptions.translatable = pathData.name.find(u"donottranslate") == std::string::npos;

        ResourceParser resParser(context->getDiagnostics(), &table, pathData.source,
                                 pathData.config, parserOptions);
        if (!resParser.parse(&xmlParser)) {
            return false;
        }

        fin.close();
    }

    if (options.pseudolocalize) {
        // Generate pseudo-localized strings (en-XA and ar-XB).
        // These are created as weak symbols, and are only generated from default configuration
        // strings and plurals.
        PseudolocaleGenerator pseudolocaleGenerator;
        if (!pseudolocaleGenerator.consume(context, &table)) {
            return false;
        }
    }

    // Ensure we have the compilation package at least.
    table.createPackage(context->getCompilationPackage());

    // Assign an ID to any package that has resources.
    for (auto& pkg : table.packages) {
        if (!pkg->id) {
            // If no package ID was set while parsing (public identifiers), auto assign an ID.
            pkg->id = context->getPackageId();
        }
    }

    // Create the file/zip entry.
    if (!writer->startEntry(outputPath, 0)) {
        context->getDiagnostics()->error(DiagMessage(outputPath) << "failed to open");
        return false;
    }

    std::unique_ptr<pb::ResourceTable> pbTable = serializeTableToPb(&table);

    // Wrap our IArchiveWriter with an adaptor that implements the ZeroCopyOutputStream interface.
    {
        google::protobuf::io::CopyingOutputStreamAdaptor adaptor(writer);

        if (!pbTable->SerializeToZeroCopyStream(&adaptor)) {
            context->getDiagnostics()->error(DiagMessage(outputPath) << "failed to write");
            return false;
        }
    }

    if (!writer->finishEntry()) {
        context->getDiagnostics()->error(DiagMessage(outputPath) << "failed to finish entry");
        return false;
    }
    return true;
}

static bool writeHeaderAndBufferToWriter(const StringPiece& outputPath, const ResourceFile& file,
                                         const BigBuffer& buffer, IArchiveWriter* writer,
                                         IDiagnostics* diag) {
    // Start the entry so we can write the header.
    if (!writer->startEntry(outputPath, 0)) {
        diag->error(DiagMessage(outputPath) << "failed to open file");
        return false;
    }

    // Create the header.
    std::unique_ptr<pb::CompiledFile> pbCompiledFile = serializeCompiledFileToPb(file);

    {
        // The stream must be destroyed before we finish the entry, or else
        // some data won't be flushed.
        // Wrap our IArchiveWriter with an adaptor that implements the ZeroCopyOutputStream
        // interface.
        google::protobuf::io::CopyingOutputStreamAdaptor adaptor(writer);
        CompiledFileOutputStream outputStream(&adaptor, pbCompiledFile.get());
        for (const BigBuffer::Block& block : buffer) {
            if (!outputStream.Write(block.buffer.get(), block.size)) {
                diag->error(DiagMessage(outputPath) << "failed to write data");
                return false;
            }
        }
    }

    if (!writer->finishEntry()) {
        diag->error(DiagMessage(outputPath) << "failed to finish writing data");
        return false;
    }
    return true;
}

static bool writeHeaderAndMmapToWriter(const StringPiece& outputPath, const ResourceFile& file,
                                       const android::FileMap& map, IArchiveWriter* writer,
                                       IDiagnostics* diag) {
    // Start the entry so we can write the header.
    if (!writer->startEntry(outputPath, 0)) {
        diag->error(DiagMessage(outputPath) << "failed to open file");
        return false;
    }

    // Create the header.
    std::unique_ptr<pb::CompiledFile> pbCompiledFile = serializeCompiledFileToPb(file);

    {
        // The stream must be destroyed before we finish the entry, or else
        // some data won't be flushed.
        // Wrap our IArchiveWriter with an adaptor that implements the ZeroCopyOutputStream
        // interface.
        google::protobuf::io::CopyingOutputStreamAdaptor adaptor(writer);
        CompiledFileOutputStream outputStream(&adaptor, pbCompiledFile.get());
        if (!outputStream.Write(map.getDataPtr(), map.getDataLength())) {
            diag->error(DiagMessage(outputPath) << "failed to write data");
            return false;
        }
    }

    if (!writer->finishEntry()) {
        diag->error(DiagMessage(outputPath) << "failed to finish writing data");
        return false;
    }
    return true;
}

static bool compileXml(IAaptContext* context, const CompileOptions& options,
                       const ResourcePathData& pathData, IArchiveWriter* writer,
                       const std::string& outputPath) {

    std::unique_ptr<xml::XmlResource> xmlRes;
    {
        std::ifstream fin(pathData.source.path, std::ifstream::binary);
        if (!fin) {
            context->getDiagnostics()->error(DiagMessage(pathData.source) << strerror(errno));
            return false;
        }

        xmlRes = xml::inflate(&fin, context->getDiagnostics(), pathData.source);

        fin.close();
    }

    if (!xmlRes) {
        return false;
    }

    // Collect IDs that are defined here.
    XmlIdCollector collector;
    if (!collector.consume(context, xmlRes.get())) {
        return false;
    }

    xmlRes->file.name = ResourceName({}, *parseResourceType(pathData.resourceDir), pathData.name);
    xmlRes->file.config = pathData.config;
    xmlRes->file.source = pathData.source;

    BigBuffer buffer(1024);
    XmlFlattenerOptions xmlFlattenerOptions;
    xmlFlattenerOptions.keepRawValues = true;
    XmlFlattener flattener(&buffer, xmlFlattenerOptions);
    if (!flattener.consume(context, xmlRes.get())) {
        return false;
    }

    if (!writeHeaderAndBufferToWriter(outputPath, xmlRes->file, buffer, writer,
                                      context->getDiagnostics())) {
        return false;
    }
    return true;
}

static bool compilePng(IAaptContext* context, const CompileOptions& options,
                       const ResourcePathData& pathData, IArchiveWriter* writer,
                       const std::string& outputPath) {
    BigBuffer buffer(4096);
    ResourceFile resFile;
    resFile.name = ResourceName({}, *parseResourceType(pathData.resourceDir), pathData.name);
    resFile.config = pathData.config;
    resFile.source = pathData.source;

    {
        std::ifstream fin(pathData.source.path, std::ifstream::binary);
        if (!fin) {
            context->getDiagnostics()->error(DiagMessage(pathData.source) << strerror(errno));
            return false;
        }

        Png png(context->getDiagnostics());
        if (!png.process(pathData.source, &fin, &buffer, {})) {
            return false;
        }
    }

    if (!writeHeaderAndBufferToWriter(outputPath, resFile, buffer, writer,
                                      context->getDiagnostics())) {
        return false;
    }
    return true;
}

static bool compileFile(IAaptContext* context, const CompileOptions& options,
                        const ResourcePathData& pathData, IArchiveWriter* writer,
                        const std::string& outputPath) {
    BigBuffer buffer(256);
    ResourceFile resFile;
    resFile.name = ResourceName({}, *parseResourceType(pathData.resourceDir), pathData.name);
    resFile.config = pathData.config;
    resFile.source = pathData.source;

    std::string errorStr;
    Maybe<android::FileMap> f = file::mmapPath(pathData.source.path, &errorStr);
    if (!f) {
        context->getDiagnostics()->error(DiagMessage(pathData.source) << errorStr);
        return false;
    }

    if (!writeHeaderAndMmapToWriter(outputPath, resFile, f.value(), writer,
                                    context->getDiagnostics())) {
        return false;
    }
    return true;
}

class CompileContext : public IAaptContext {
public:
    void setVerbose(bool val) {
        mVerbose = val;
    }

    bool verbose() override {
        return mVerbose;
    }

    IDiagnostics* getDiagnostics() override {
       return &mDiagnostics;
    }

    NameMangler* getNameMangler() override {
       abort();
       return nullptr;
    }

    const std::u16string& getCompilationPackage() override {
        static std::u16string empty;
        return empty;
    }

    uint8_t getPackageId() override {
       return 0x0;
    }

    SymbolTable* getExternalSymbols() override {
       abort();
       return nullptr;
    }

private:
    StdErrDiagnostics mDiagnostics;
    bool mVerbose = false;

};

/**
 * Entry point for compilation phase. Parses arguments and dispatches to the correct steps.
 */
int compile(const std::vector<StringPiece>& args) {
    CompileContext context;
    CompileOptions options;

    bool verbose = false;
    Flags flags = Flags()
            .requiredFlag("-o", "Output path", &options.outputPath)
            .optionalFlag("--dir", "Directory to scan for resources", &options.resDir)
            .optionalSwitch("--pseudo-localize", "Generate resources for pseudo-locales "
                            "(en-XA and ar-XB)", &options.pseudolocalize)
            .optionalSwitch("--legacy", "Treat errors that used to be valid in AAPT as warnings",
                            &options.legacyMode)
            .optionalSwitch("-v", "Enables verbose logging", &verbose);
    if (!flags.parse("aapt2 compile", args, &std::cerr)) {
        return 1;
    }

    context.setVerbose(verbose);

    std::unique_ptr<IArchiveWriter> archiveWriter;

    std::vector<ResourcePathData> inputData;
    if (options.resDir) {
        if (!flags.getArgs().empty()) {
            // Can't have both files and a resource directory.
            context.getDiagnostics()->error(DiagMessage() << "files given but --dir specified");
            flags.usage("aapt2 compile", &std::cerr);
            return 1;
        }

        if (!loadInputFilesFromDir(&context, options, &inputData)) {
            return 1;
        }

        archiveWriter = createZipFileArchiveWriter(context.getDiagnostics(), options.outputPath);

    } else {
        inputData.reserve(flags.getArgs().size());

        // Collect data from the path for each input file.
        for (const std::string& arg : flags.getArgs()) {
            std::string errorStr;
            if (Maybe<ResourcePathData> pathData = extractResourcePathData(arg, &errorStr)) {
                inputData.push_back(std::move(pathData.value()));
            } else {
                context.getDiagnostics()->error(DiagMessage() << errorStr << " (" << arg << ")");
                return 1;
            }
        }

        archiveWriter = createDirectoryArchiveWriter(context.getDiagnostics(), options.outputPath);
    }

    if (!archiveWriter) {
        return false;
    }

    bool error = false;
    for (ResourcePathData& pathData : inputData) {
        if (options.verbose) {
            context.getDiagnostics()->note(DiagMessage(pathData.source) << "processing");
        }

        if (pathData.resourceDir == u"values") {
            // Overwrite the extension.
            pathData.extension = "arsc";

            const std::string outputFilename = buildIntermediateFilename(pathData);
            if (!compileTable(&context, options, pathData, archiveWriter.get(), outputFilename)) {
                error = true;
            }

        } else {
            const std::string outputFilename = buildIntermediateFilename(pathData);
            if (const ResourceType* type = parseResourceType(pathData.resourceDir)) {
                if (*type != ResourceType::kRaw) {
                    if (pathData.extension == "xml") {
                        if (!compileXml(&context, options, pathData, archiveWriter.get(),
                                        outputFilename)) {
                            error = true;
                        }
                    } else if (pathData.extension == "png" || pathData.extension == "9.png") {
                        if (!compilePng(&context, options, pathData, archiveWriter.get(),
                                        outputFilename)) {
                            error = true;
                        }
                    } else {
                        if (!compileFile(&context, options, pathData, archiveWriter.get(),
                                         outputFilename)) {
                            error = true;
                        }
                    }
                } else {
                    if (!compileFile(&context, options, pathData, archiveWriter.get(),
                                     outputFilename)) {
                        error = true;
                    }
                }
            } else {
                context.getDiagnostics()->error(
                        DiagMessage() << "invalid file path '" << pathData.source << "'");
                error = true;
            }
        }
    }

    if (error) {
        return 1;
    }
    return 0;
}

} // namespace aapt
