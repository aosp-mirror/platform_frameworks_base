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

#include "AppInfo.h"
#include "Debug.h"
#include "Flags.h"
#include "JavaClassGenerator.h"
#include "NameMangler.h"
#include "ProguardRules.h"
#include "XmlDom.h"

#include "compile/IdAssigner.h"
#include "flatten/Archive.h"
#include "flatten/TableFlattener.h"
#include "flatten/XmlFlattener.h"
#include "link/Linkers.h"
#include "link/TableMerger.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "unflatten/BinaryResourceParser.h"
#include "unflatten/FileExportHeaderReader.h"
#include "util/Files.h"
#include "util/StringPiece.h"

#include <fstream>
#include <sys/stat.h>
#include <utils/FileMap.h>
#include <vector>

namespace aapt {

struct LinkOptions {
    std::string outputPath;
    std::string manifestPath;
    std::vector<std::string> includePaths;
    Maybe<std::string> generateJavaClassPath;
    Maybe<std::string> generateProguardRulesPath;
    bool noAutoVersion = false;
    bool staticLib = false;
    bool verbose = false;
    bool outputToDirectory = false;
    Maybe<std::u16string> privateSymbols;
};

struct LinkContext : public IAaptContext {
    StdErrDiagnostics mDiagnostics;
    std::unique_ptr<NameMangler> mNameMangler;
    std::u16string mCompilationPackage;
    uint8_t mPackageId;
    std::unique_ptr<ISymbolTable> mSymbols;

    IDiagnostics* getDiagnostics() override {
        return &mDiagnostics;
    }

    NameMangler* getNameMangler() override {
        return mNameMangler.get();
    }

    StringPiece16 getCompilationPackage() override {
        return mCompilationPackage;
    }

    uint8_t getPackageId() override {
        return mPackageId;
    }

    ISymbolTable* getExternalSymbols() override {
        return mSymbols.get();
    }
};

struct LinkCommand {
    LinkOptions mOptions;
    LinkContext mContext;

    std::string buildResourceFileName(const ResourceFile& resFile) {
        std::stringstream out;
        out << "res/" << resFile.name.type;
        if (resFile.config != ConfigDescription{}) {
            out << "-" << resFile.config;
        }
        out << "/";

        if (mContext.getNameMangler()->shouldMangle(resFile.name.package)) {
            out << NameMangler::mangleEntry(resFile.name.package, resFile.name.entry);
        } else {
            out << resFile.name.entry;
        }
        out << file::getExtension(resFile.source.path);
        return out.str();
    }

    /**
     * Creates a SymbolTable that loads symbols from the various APKs and caches the
     * results for faster lookup.
     */
    std::unique_ptr<ISymbolTable> createSymbolTableFromIncludePaths() {
        AssetManagerSymbolTableBuilder builder;
        for (const std::string& path : mOptions.includePaths) {
            if (mOptions.verbose) {
                mContext.getDiagnostics()->note(
                        DiagMessage(Source{ path }) << "loading include path");
            }

            std::unique_ptr<android::AssetManager> assetManager =
                    util::make_unique<android::AssetManager>();
            int32_t cookie = 0;
            if (!assetManager->addAssetPath(android::String8(path.data(), path.size()), &cookie)) {
                mContext.getDiagnostics()->error(
                        DiagMessage(Source{ path }) << "failed to load include path");
                return {};
            }
            builder.add(std::move(assetManager));
        }
        return builder.build();
    }

    /**
     * Loads the resource table (not inside an apk) at the given path.
     */
    std::unique_ptr<ResourceTable> loadTable(const std::string& input) {
        std::string errorStr;
        Maybe<android::FileMap> map = file::mmapPath(input, &errorStr);
        if (!map) {
            mContext.getDiagnostics()->error(DiagMessage(Source{ input }) << errorStr);
            return {};
        }

        std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();
        BinaryResourceParser parser(&mContext, table.get(), Source{ input },
                                    map.value().getDataPtr(), map.value().getDataLength());
        if (!parser.parse()) {
            return {};
        }
        return table;
    }

    /**
     * Inflates an XML file from the source path.
     */
    std::unique_ptr<XmlResource> loadXml(const std::string& path) {
        std::ifstream fin(path, std::ifstream::binary);
        if (!fin) {
            mContext.getDiagnostics()->error(DiagMessage(Source{ path }) << strerror(errno));
            return {};
        }

        return xml::inflate(&fin, mContext.getDiagnostics(), Source{ path });
    }

    /**
     * Inflates a binary XML file from the source path.
     */
    std::unique_ptr<XmlResource> loadBinaryXmlSkipFileExport(const std::string& path) {
        // Read header for symbol info and export info.
        std::string errorStr;
        Maybe<android::FileMap> maybeF = file::mmapPath(path, &errorStr);
        if (!maybeF) {
            mContext.getDiagnostics()->error(DiagMessage(path) << errorStr);
            return {};
        }

        ssize_t offset = getWrappedDataOffset(maybeF.value().getDataPtr(),
                                              maybeF.value().getDataLength(), &errorStr);
        if (offset < 0) {
            mContext.getDiagnostics()->error(DiagMessage(path) << errorStr);
            return {};
        }

        std::unique_ptr<XmlResource> xmlRes = xml::inflate(
                (const uint8_t*) maybeF.value().getDataPtr() + (size_t) offset,
                maybeF.value().getDataLength() - offset,
                mContext.getDiagnostics(), Source(path));
        if (!xmlRes) {
            return {};
        }
        return xmlRes;
    }

    Maybe<ResourceFile> loadFileExportHeader(const std::string& path) {
        // Read header for symbol info and export info.
        std::string errorStr;
        Maybe<android::FileMap> maybeF = file::mmapPath(path, &errorStr);
        if (!maybeF) {
            mContext.getDiagnostics()->error(DiagMessage(path) << errorStr);
            return {};
        }

        ResourceFile resFile;
        ssize_t offset = unwrapFileExportHeader(maybeF.value().getDataPtr(),
                                                maybeF.value().getDataLength(),
                                                &resFile, &errorStr);
        if (offset < 0) {
            mContext.getDiagnostics()->error(DiagMessage(path) << errorStr);
            return {};
        }
        return std::move(resFile);
    }

    bool copyFileToArchive(const std::string& path, const std::string& outPath, uint32_t flags,
                           IArchiveWriter* writer) {
        std::string errorStr;
        Maybe<android::FileMap> maybeF = file::mmapPath(path, &errorStr);
        if (!maybeF) {
            mContext.getDiagnostics()->error(DiagMessage(path) << errorStr);
            return false;
        }

        ssize_t offset = getWrappedDataOffset(maybeF.value().getDataPtr(),
                                              maybeF.value().getDataLength(),
                                              &errorStr);
        if (offset < 0) {
            mContext.getDiagnostics()->error(DiagMessage(path) << errorStr);
            return false;
        }

        ArchiveEntry* entry = writer->writeEntry(outPath, flags, &maybeF.value(),
                                                 offset, maybeF.value().getDataLength() - offset);
        if (!entry) {
            mContext.getDiagnostics()->error(
                    DiagMessage(mOptions.outputPath) << "failed to write file " << outPath);
            return false;
        }
        return true;
    }

    Maybe<AppInfo> extractAppInfoFromManifest(XmlResource* xmlRes) {
        xml::Node* node = xmlRes->root.get();

        // Find the first xml::Element.
        while (node && !xml::nodeCast<xml::Element>(node)) {
            node = !node->children.empty() ? node->children.front().get() : nullptr;
        }

        // Make sure the first element is <manifest> with package attribute.
        if (xml::Element* manifestEl = xml::nodeCast<xml::Element>(node)) {
            if (manifestEl->namespaceUri.empty() && manifestEl->name == u"manifest") {
                if (xml::Attribute* packageAttr = manifestEl->findAttribute({}, u"package")) {
                    return AppInfo{ packageAttr->value };
                }
            }
        }
        return {};
    }

    bool verifyNoExternalPackages(ResourceTable* table) {
        bool error = false;
        for (const auto& package : table->packages) {
            if (mContext.getCompilationPackage() != package->name ||
                    !package->id || package->id.value() != mContext.getPackageId()) {
                // We have a package that is not related to the one we're building!
                for (const auto& type : package->types) {
                    for (const auto& entry : type->entries) {
                        for (const auto& configValue : entry->values) {
                            mContext.getDiagnostics()->error(
                                    DiagMessage(configValue.value->getSource())
                                                << "defined resource '"
                                                << ResourceNameRef(package->name,
                                                                   type->type,
                                                                   entry->name)
                                                << "' for external package '"
                                                << package->name << "'");
                            error = true;
                        }
                    }
                }
            }
        }
        return !error;
    }

    std::unique_ptr<IArchiveWriter> makeArchiveWriter() {
        if (mOptions.outputToDirectory) {
            return createDirectoryArchiveWriter(mOptions.outputPath);
        } else {
            return createZipFileArchiveWriter(mOptions.outputPath);
        }
    }

    bool flattenTable(ResourceTable* table, IArchiveWriter* writer) {
        BigBuffer buffer(1024);
        TableFlattenerOptions options = {};
        options.useExtendedChunks = mOptions.staticLib;
        TableFlattener flattener(&buffer, options);
        if (!flattener.consume(&mContext, table)) {
            return false;
        }

        ArchiveEntry* entry = writer->writeEntry("resources.arsc", ArchiveEntry::kAlign, buffer);
        if (!entry) {
            mContext.getDiagnostics()->error(
                    DiagMessage() << "failed to write resources.arsc to archive");
            return false;
        }
        return true;
    }

    bool flattenXml(XmlResource* xmlRes, const StringPiece& path, Maybe<size_t> maxSdkLevel,
                    IArchiveWriter* writer) {
        BigBuffer buffer(1024);
        XmlFlattenerOptions options = {};
        options.keepRawValues = mOptions.staticLib;
        options.maxSdkLevel = maxSdkLevel;
        XmlFlattener flattener(&buffer, options);
        if (!flattener.consume(&mContext, xmlRes)) {
            return false;
        }

        ArchiveEntry* entry = writer->writeEntry(path, ArchiveEntry::kCompress, buffer);
        if (!entry) {
            mContext.getDiagnostics()->error(
                    DiagMessage() << "failed to write " << path << " to archive");
            return false;
        }
        return true;
    }

    bool writeJavaFile(ResourceTable* table, const StringPiece16& packageNameToGenerate,
                       const StringPiece16& outPackage, JavaClassGeneratorOptions javaOptions) {
        if (!mOptions.generateJavaClassPath) {
            return true;
        }

        std::string outPath = mOptions.generateJavaClassPath.value();
        file::appendPath(&outPath, file::packageToPath(util::utf16ToUtf8(outPackage)));
        file::mkdirs(outPath);
        file::appendPath(&outPath, "R.java");

        std::ofstream fout(outPath, std::ofstream::binary);
        if (!fout) {
            mContext.getDiagnostics()->error(DiagMessage() << strerror(errno));
            return false;
        }

        JavaClassGenerator generator(table, javaOptions);
        if (!generator.generate(packageNameToGenerate, outPackage, &fout)) {
            mContext.getDiagnostics()->error(DiagMessage(outPath) << generator.getError());
            return false;
        }
        return true;
    }

    bool writeProguardFile(const proguard::KeepSet& keepSet) {
        if (!mOptions.generateProguardRulesPath) {
            return true;
        }

        std::ofstream fout(mOptions.generateProguardRulesPath.value(), std::ofstream::binary);
        if (!fout) {
            mContext.getDiagnostics()->error(DiagMessage() << strerror(errno));
            return false;
        }

        proguard::writeKeepSet(&fout, keepSet);
        if (!fout) {
            mContext.getDiagnostics()->error(DiagMessage() << strerror(errno));
            return false;
        }
        return true;
    }

    int run(const std::vector<std::string>& inputFiles) {
        // Load the AndroidManifest.xml
        std::unique_ptr<XmlResource> manifestXml = loadXml(mOptions.manifestPath);
        if (!manifestXml) {
            return 1;
        }

        if (Maybe<AppInfo> maybeAppInfo = extractAppInfoFromManifest(manifestXml.get())) {
            mContext.mCompilationPackage = maybeAppInfo.value().package;
        } else {
            mContext.getDiagnostics()->error(DiagMessage(mOptions.manifestPath)
                                             << "no package specified in <manifest> tag");
            return 1;
        }

        if (!util::isJavaPackageName(mContext.mCompilationPackage)) {
            mContext.getDiagnostics()->error(DiagMessage(mOptions.manifestPath)
                                             << "invalid package name '"
                                             << mContext.mCompilationPackage
                                             << "'");
            return 1;
        }

        mContext.mNameMangler = util::make_unique<NameMangler>(
                NameManglerPolicy{ mContext.mCompilationPackage });

        if (mContext.mCompilationPackage == u"android") {
            mContext.mPackageId = 0x01;
        } else {
            mContext.mPackageId = 0x7f;
        }

        mContext.mSymbols = createSymbolTableFromIncludePaths();
        if (!mContext.mSymbols) {
            return 1;
        }

        if (mOptions.verbose) {
            mContext.getDiagnostics()->note(
                    DiagMessage() << "linking package '" << mContext.mCompilationPackage << "' "
                                  << "with package ID " << std::hex << (int) mContext.mPackageId);
        }

        ResourceTable mergedTable;
        TableMerger tableMerger(&mContext, &mergedTable);

        struct FilesToProcess {
            Source source;
            ResourceFile file;
        };

        bool error = false;
        std::queue<FilesToProcess> filesToProcess;
        for (const std::string& input : inputFiles) {
            if (util::stringEndsWith<char>(input, ".apk")) {
                // TODO(adamlesinski): Load resources from a static library APK
                //                     Merge the table into TableMerger.

            } else if (util::stringEndsWith<char>(input, ".arsc.flat")) {
                if (mOptions.verbose) {
                    mContext.getDiagnostics()->note(DiagMessage() << "linking " << input);
                }

                std::unique_ptr<ResourceTable> table = loadTable(input);
                if (!table) {
                    return 1;
                }

                if (!tableMerger.merge(Source(input), table.get())) {
                    return 1;
                }

            } else {
                // Extract the exported IDs here so we can build the resource table.
                if (Maybe<ResourceFile> maybeF = loadFileExportHeader(input)) {
                    ResourceFile& f = maybeF.value();

                    if (f.name.package.empty()) {
                        f.name.package = mContext.getCompilationPackage().toString();
                    }

                    Maybe<ResourceName> mangledName = mContext.getNameMangler()->mangleName(f.name);

                    // Add this file to the table.
                    if (!mergedTable.addFileReference(mangledName ? mangledName.value() : f.name,
                                                      f.config, f.source,
                                                      util::utf8ToUtf16(buildResourceFileName(f)),
                                                      mContext.getDiagnostics())) {
                        error = true;
                    }

                    // Add the exports of this file to the table.
                    for (SourcedResourceName& exportedSymbol : f.exportedSymbols) {
                        if (exportedSymbol.name.package.empty()) {
                            exportedSymbol.name.package = mContext.getCompilationPackage()
                                    .toString();
                        }

                        Maybe<ResourceName> mangledName = mContext.getNameMangler()->mangleName(
                                exportedSymbol.name);
                        std::unique_ptr<Id> id = util::make_unique<Id>();
                        id->setSource(f.source.withLine(exportedSymbol.line));
                        if (!mergedTable.addResourceAllowMangled(
                                mangledName ? mangledName.value() : exportedSymbol.name,
                                {}, std::move(id), mContext.getDiagnostics())) {
                            error = true;
                        }
                    }

                    filesToProcess.push(FilesToProcess{ Source(input), std::move(f) });
                } else {
                    return 1;
                }
            }
        }

        if (error) {
            mContext.getDiagnostics()->error(DiagMessage() << "failed parsing input");
            return 1;
        }

        if (!verifyNoExternalPackages(&mergedTable)) {
            return 1;
        }

        if (!mOptions.staticLib) {
            PrivateAttributeMover mover;
            if (!mover.consume(&mContext, &mergedTable)) {
                mContext.getDiagnostics()->error(
                        DiagMessage() << "failed moving private attributes");
                return 1;
            }
        }

        {
            IdAssigner idAssigner;
            if (!idAssigner.consume(&mContext, &mergedTable)) {
                mContext.getDiagnostics()->error(DiagMessage() << "failed assigning IDs");
                return 1;
            }
        }

        mContext.mNameMangler = util::make_unique<NameMangler>(
                NameManglerPolicy{ mContext.mCompilationPackage, tableMerger.getMergedPackages() });
        mContext.mSymbols = JoinedSymbolTableBuilder()
                .addSymbolTable(util::make_unique<SymbolTableWrapper>(&mergedTable))
                .addSymbolTable(std::move(mContext.mSymbols))
                .build();

        {
            ReferenceLinker linker;
            if (!linker.consume(&mContext, &mergedTable)) {
                mContext.getDiagnostics()->error(DiagMessage() << "failed linking references");
                return 1;
            }
        }

        proguard::KeepSet proguardKeepSet;

        std::unique_ptr<IArchiveWriter> archiveWriter = makeArchiveWriter();
        if (!archiveWriter) {
            mContext.getDiagnostics()->error(DiagMessage() << "failed to create archive");
            return 1;
        }

        {
            XmlReferenceLinker manifestLinker;
            if (manifestLinker.consume(&mContext, manifestXml.get())) {

                if (!proguard::collectProguardRulesForManifest(Source(mOptions.manifestPath),
                                                               manifestXml.get(),
                                                               &proguardKeepSet)) {
                    error = true;
                }

                if (!flattenXml(manifestXml.get(), "AndroidManifest.xml", {},
                                archiveWriter.get())) {
                    error = true;
                }
            } else {
                error = true;
            }
        }

        for (; !filesToProcess.empty(); filesToProcess.pop()) {
            FilesToProcess& f = filesToProcess.front();
            if (f.file.name.type != ResourceType::kRaw &&
                    util::stringEndsWith<char>(f.source.path, ".xml.flat")) {
                if (mOptions.verbose) {
                    mContext.getDiagnostics()->note(DiagMessage() << "linking " << f.source.path);
                }

                std::unique_ptr<XmlResource> xmlRes = loadBinaryXmlSkipFileExport(f.source.path);
                if (!xmlRes) {
                    return 1;
                }

                xmlRes->file = std::move(f.file);

                XmlReferenceLinker xmlLinker;
                if (xmlLinker.consume(&mContext, xmlRes.get())) {
                    if (!proguard::collectProguardRules(xmlRes->file.source, xmlRes.get(),
                                                        &proguardKeepSet)) {
                        error = true;
                    }

                    Maybe<size_t> maxSdkLevel;
                    if (!mOptions.noAutoVersion) {
                        maxSdkLevel = std::max<size_t>(xmlRes->file.config.sdkVersion, 1u);
                    }

                    if (!flattenXml(xmlRes.get(), buildResourceFileName(xmlRes->file), maxSdkLevel,
                                    archiveWriter.get())) {
                        error = true;
                    }

                    if (!mOptions.noAutoVersion) {
                        Maybe<ResourceTable::SearchResult> result = mergedTable.findResource(
                                xmlRes->file.name);
                        for (int sdkLevel : xmlLinker.getSdkLevels()) {
                            if (sdkLevel > xmlRes->file.config.sdkVersion &&
                                    shouldGenerateVersionedResource(result.value().entry,
                                                                    xmlRes->file.config,
                                                                    sdkLevel)) {
                                xmlRes->file.config.sdkVersion = sdkLevel;
                                if (!mergedTable.addFileReference(xmlRes->file.name,
                                                                  xmlRes->file.config,
                                                                  xmlRes->file.source,
                                                                  util::utf8ToUtf16(
                                                                     buildResourceFileName(xmlRes->file)),
                                                             mContext.getDiagnostics())) {
                                    error = true;
                                    continue;
                                }

                                if (!flattenXml(xmlRes.get(), buildResourceFileName(xmlRes->file),
                                                sdkLevel, archiveWriter.get())) {
                                    error = true;
                                }
                            }
                        }
                    }

                } else {
                    error = true;
                }
            } else {
                if (mOptions.verbose) {
                    mContext.getDiagnostics()->note(DiagMessage() << "copying " << f.source.path);
                }

                if (!copyFileToArchive(f.source.path, buildResourceFileName(f.file), 0,
                                       archiveWriter.get())) {
                    error = true;
                }
            }
        }

        if (error) {
            mContext.getDiagnostics()->error(DiagMessage() << "failed linking file resources");
            return 1;
        }

        if (!mOptions.noAutoVersion) {
            AutoVersioner versioner;
            if (!versioner.consume(&mContext, &mergedTable)) {
                mContext.getDiagnostics()->error(DiagMessage() << "failed versioning styles");
                return 1;
            }
        }

        if (!flattenTable(&mergedTable, archiveWriter.get())) {
            mContext.getDiagnostics()->error(DiagMessage() << "failed to write resources.arsc");
            return 1;
        }

        if (mOptions.generateJavaClassPath) {
            JavaClassGeneratorOptions options;
            if (mOptions.staticLib) {
                options.useFinal = false;
            }

            if (mOptions.privateSymbols) {
                // If we defined a private symbols package, we only emit Public symbols
                // to the original package, and private and public symbols to the private package.

                options.types = JavaClassGeneratorOptions::SymbolTypes::kPublic;
                if (!writeJavaFile(&mergedTable, mContext.getCompilationPackage(),
                                   mContext.getCompilationPackage(), options)) {
                    return 1;
                }

                options.types = JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate;
                if (!writeJavaFile(&mergedTable, mContext.getCompilationPackage(),
                                   mOptions.privateSymbols.value(), options)) {
                    return 1;
                }

            } else {
                // Emit Everything.

                if (!writeJavaFile(&mergedTable, mContext.getCompilationPackage(),
                                   mContext.getCompilationPackage(), options)) {
                    return 1;
                }
            }
        }

        if (mOptions.generateProguardRulesPath) {
            if (!writeProguardFile(proguardKeepSet)) {
                return 1;
            }
        }

        if (mOptions.verbose) {
            Debug::printTable(&mergedTable);
            for (; !tableMerger.getFileMergeQueue()->empty();
                    tableMerger.getFileMergeQueue()->pop()) {
                const FileToMerge& f = tableMerger.getFileMergeQueue()->front();
                mContext.getDiagnostics()->note(
                        DiagMessage() << f.srcPath << " -> " << f.dstPath << " from (0x"
                                      << std::hex << (uintptr_t) f.srcTable << std::dec);
            }
        }

        return 0;
    }
};

int link(const std::vector<StringPiece>& args) {
    LinkOptions options;
    Maybe<std::string> privateSymbolsPackage;
    Flags flags = Flags()
            .requiredFlag("-o", "Output path", &options.outputPath)
            .requiredFlag("--manifest", "Path to the Android manifest to build",
                          &options.manifestPath)
            .optionalFlagList("-I", "Adds an Android APK to link against", &options.includePaths)
            .optionalFlag("--java", "Directory in which to generate R.java",
                          &options.generateJavaClassPath)
            .optionalFlag("--proguard", "Output file for generated Proguard rules",
                          &options.generateProguardRulesPath)
            .optionalSwitch("--no-auto-version",
                            "Disables automatic style and layout SDK versioning",
                            &options.noAutoVersion)
            .optionalSwitch("--output-to-dir", "Outputs the APK contents to a directory specified "
                            "by -o",
                            &options.outputToDirectory)
            .optionalSwitch("--static-lib", "Generate a static Android library", &options.staticLib)
            .optionalFlag("--private-symbols", "Package name to use when generating R.java for "
                          "private symbols. If not specified, public and private symbols will "
                          "use the application's package name", &privateSymbolsPackage)
            .optionalSwitch("-v", "Enables verbose logging", &options.verbose);

    if (!flags.parse("aapt2 link", args, &std::cerr)) {
        return 1;
    }

    if (privateSymbolsPackage) {
        options.privateSymbols = util::utf8ToUtf16(privateSymbolsPackage.value());
    }

    LinkCommand cmd = { options };
    return cmd.run(flags.getArgs());
}

} // namespace aapt
