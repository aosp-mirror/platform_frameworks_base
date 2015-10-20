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
#include "XmlDom.h"
#include "XmlPullParser.h"

#include "compile/IdAssigner.h"
#include "compile/Png.h"
#include "compile/XmlIdCollector.h"
#include "flatten/FileExportWriter.h"
#include "flatten/TableFlattener.h"
#include "flatten/XmlFlattener.h"
#include "util/Files.h"
#include "util/Maybe.h"
#include "util/Util.h"

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
            Source{ path },
            util::utf8ToUtf16(dirStr),
            util::utf8ToUtf16(name),
            extension.toString(),
            configStr.toString(),
            config
    };
}

struct CompileOptions {
    std::string outputPath;
    Maybe<std::u16string> product;
    bool verbose = false;
};

static std::string buildIntermediateFilename(const std::string outDir,
                                             const ResourcePathData& data) {
    std::stringstream name;
    name << data.resourceDir;
    if (!data.configStr.empty()) {
        name << "-" << data.configStr;
    }
    name << "_" << data.name << "." << data.extension << ".flat";
    std::string outPath = outDir;
    file::appendPath(&outPath, name.str());
    return outPath;
}

static bool compileTable(IAaptContext* context, const CompileOptions& options,
                         const ResourcePathData& pathData, const std::string& outputPath) {
    ResourceTable table;
    {
        std::ifstream fin(pathData.source.path, std::ifstream::binary);
        if (!fin) {
            context->getDiagnostics()->error(DiagMessage(pathData.source) << strerror(errno));
            return false;
        }


        // Parse the values file from XML.
        XmlPullParser xmlParser(fin);
        ResourceParser resParser(context->getDiagnostics(), &table, pathData.source,
                                 pathData.config, ResourceParserOptions{ options.product });
        if (!resParser.parse(&xmlParser)) {
            return false;
        }

        fin.close();
    }

    ResourceTablePackage* pkg = table.createPackage(context->getCompilationPackage());
    if (!pkg->id) {
        // If no package ID was set while parsing (public identifiers), auto assign an ID.
        pkg->id = context->getPackageId();
    }

    // Assign IDs to prepare the table for flattening.
    IdAssigner idAssigner;
    if (!idAssigner.consume(context, &table)) {
        return false;
    }

    // Flatten the table.
    BigBuffer buffer(1024);
    TableFlattenerOptions tableFlattenerOptions;
    tableFlattenerOptions.useExtendedChunks = true;
    TableFlattener flattener(&buffer, tableFlattenerOptions);
    if (!flattener.consume(context, &table)) {
        return false;
    }

    // Build the output filename.
    std::ofstream fout(outputPath, std::ofstream::binary);
    if (!fout) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }

    // Write it to disk.
    if (!util::writeAll(fout, buffer)) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }
    return true;
}

static bool compileXml(IAaptContext* context, const CompileOptions& options,
                       const ResourcePathData& pathData, const std::string& outputPath) {

    std::unique_ptr<XmlResource> xmlRes;

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

    xmlRes->file.name = ResourceName{ {}, *parseResourceType(pathData.resourceDir), pathData.name };
    xmlRes->file.config = pathData.config;
    xmlRes->file.source = pathData.source;

    BigBuffer buffer(1024);
    ChunkWriter fileExportWriter = wrapBufferWithFileExportHeader(&buffer, &xmlRes->file);

    XmlFlattenerOptions xmlFlattenerOptions;
    xmlFlattenerOptions.keepRawValues = true;
    XmlFlattener flattener(fileExportWriter.getBuffer(), xmlFlattenerOptions);
    if (!flattener.consume(context, xmlRes.get())) {
        return false;
    }

    fileExportWriter.finish();

    std::ofstream fout(outputPath, std::ofstream::binary);
    if (!fout) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }

    // Write it to disk.
    if (!util::writeAll(fout, buffer)) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }
    return true;
}

static bool compilePng(IAaptContext* context, const CompileOptions& options,
                       const ResourcePathData& pathData, const std::string& outputPath) {
    BigBuffer buffer(4096);
    ResourceFile resFile;
    resFile.name = ResourceName{ {}, *parseResourceType(pathData.resourceDir), pathData.name };
    resFile.config = pathData.config;
    resFile.source = pathData.source;

    ChunkWriter fileExportWriter = wrapBufferWithFileExportHeader(&buffer, &resFile);

    {
        std::ifstream fin(pathData.source.path, std::ifstream::binary);
        if (!fin) {
            context->getDiagnostics()->error(DiagMessage(pathData.source) << strerror(errno));
            return false;
        }

        Png png(context->getDiagnostics());
        if (!png.process(pathData.source, &fin, fileExportWriter.getBuffer(), {})) {
            return false;
        }
    }

    fileExportWriter.finish();

    std::ofstream fout(outputPath, std::ofstream::binary);
    if (!fout) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }

    if (!util::writeAll(fout, buffer)) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }
    return true;
}

static bool compileFile(IAaptContext* context, const CompileOptions& options,
                        const ResourcePathData& pathData, const std::string& outputPath) {
    BigBuffer buffer(256);
    ResourceFile resFile;
    resFile.name = ResourceName{ {}, *parseResourceType(pathData.resourceDir), pathData.name };
    resFile.config = pathData.config;
    resFile.source = pathData.source;

    ChunkWriter fileExportWriter = wrapBufferWithFileExportHeader(&buffer, &resFile);

    std::string errorStr;
    Maybe<android::FileMap> f = file::mmapPath(pathData.source.path, &errorStr);
    if (!f) {
        context->getDiagnostics()->error(DiagMessage(pathData.source) << errorStr);
        return false;
    }

    std::ofstream fout(outputPath, std::ofstream::binary);
    if (!fout) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }

    // Manually set the size and don't call finish(). This is because we are not copying from
    // the buffer the entire file.
    fileExportWriter.getChunkHeader()->size =
            util::hostToDevice32(buffer.size() + f.value().getDataLength());
    if (!util::writeAll(fout, buffer)) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }

    if (!fout.write((const char*) f.value().getDataPtr(), f.value().getDataLength())) {
        context->getDiagnostics()->error(DiagMessage(Source{ outputPath }) << strerror(errno));
        return false;
    }
    return true;
}

class CompileContext : public IAaptContext {
private:
    StdErrDiagnostics mDiagnostics;

public:
    IDiagnostics* getDiagnostics() override {
       return &mDiagnostics;
    }

    NameMangler* getNameMangler() override {
       abort();
       return nullptr;
    }

    StringPiece16 getCompilationPackage() override {
       return {};
    }

    uint8_t getPackageId() override {
       return 0x0;
    }

    ISymbolTable* getExternalSymbols() override {
       abort();
       return nullptr;
    }
};

/**
 * Entry point for compilation phase. Parses arguments and dispatches to the correct steps.
 */
int compile(const std::vector<StringPiece>& args) {
    CompileOptions options;

    Maybe<std::string> product;
    Flags flags = Flags()
            .requiredFlag("-o", "Output path", &options.outputPath)
            .optionalFlag("--product", "Product type to compile", &product)
            .optionalSwitch("-v", "Enables verbose logging", &options.verbose);
    if (!flags.parse("aapt2 compile", args, &std::cerr)) {
        return 1;
    }

    if (product) {
        options.product = util::utf8ToUtf16(product.value());
    }

    CompileContext context;

    std::vector<ResourcePathData> inputData;
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

    bool error = false;
    for (ResourcePathData& pathData : inputData) {
        if (options.verbose) {
            context.getDiagnostics()->note(DiagMessage(pathData.source) << "processing");
        }

        if (pathData.resourceDir == u"values") {
            // Overwrite the extension.
            pathData.extension = "arsc";

            const std::string outputFilename = buildIntermediateFilename(
                    options.outputPath, pathData);
            if (!compileTable(&context, options, pathData, outputFilename)) {
                error = true;
            }

        } else {
            const std::string outputFilename = buildIntermediateFilename(options.outputPath,
                                                                         pathData);
            if (const ResourceType* type = parseResourceType(pathData.resourceDir)) {
                if (*type != ResourceType::kRaw) {
                    if (pathData.extension == "xml") {
                        if (!compileXml(&context, options, pathData, outputFilename)) {
                            error = true;
                        }
                    } else if (pathData.extension == "png" || pathData.extension == "9.png") {
                        if (!compilePng(&context, options, pathData, outputFilename)) {
                            error = true;
                        }
                    } else {
                        if (!compileFile(&context, options, pathData, outputFilename)) {
                            error = true;
                        }
                    }
                } else {
                    if (!compileFile(&context, options, pathData, outputFilename)) {
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
