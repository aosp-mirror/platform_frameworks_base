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
#include "NameMangler.h"
#include "compile/IdAssigner.h"
#include "flatten/Archive.h"
#include "flatten/TableFlattener.h"
#include "flatten/XmlFlattener.h"
#include "io/FileSystem.h"
#include "io/ZipArchive.h"
#include "java/JavaClassGenerator.h"
#include "java/ManifestClassGenerator.h"
#include "java/ProguardRules.h"
#include "link/Linkers.h"
#include "link/ReferenceLinker.h"
#include "link/ManifestFixer.h"
#include "link/TableMerger.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "unflatten/BinaryResourceParser.h"
#include "unflatten/FileExportHeaderReader.h"
#include "util/Files.h"
#include "util/StringPiece.h"
#include "xml/XmlDom.h"

#include <fstream>
#include <sys/stat.h>
#include <vector>

namespace aapt {

struct LinkOptions {
    std::string outputPath;
    std::string manifestPath;
    std::vector<std::string> includePaths;
    std::vector<std::string> overlayFiles;
    Maybe<std::string> generateJavaClassPath;
    Maybe<std::u16string> customJavaPackage;
    std::set<std::u16string> extraJavaPackages;
    Maybe<std::string> generateProguardRulesPath;
    bool noAutoVersion = false;
    bool staticLib = false;
    bool verbose = false;
    bool outputToDirectory = false;
    bool autoAddOverlay = false;
    bool doNotCompressAnything = false;
    std::vector<std::string> extensionsToNotCompress;
    Maybe<std::u16string> privateSymbols;
    ManifestFixerOptions manifestFixerOptions;

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

class LinkCommand {
public:
    LinkCommand(const LinkOptions& options) :
            mOptions(options), mContext(), mFinalTable(), mFileCollection(nullptr) {
        std::unique_ptr<io::FileCollection> fileCollection =
                util::make_unique<io::FileCollection>();

        // Get a pointer to the FileCollection for convenience, but it will be owned by the vector.
        mFileCollection = fileCollection.get();

        // Move it to the collection.
        mCollections.push_back(std::move(fileCollection));
    }

    /**
     * Creates a SymbolTable that loads symbols from the various APKs and caches the
     * results for faster lookup.
     */
    std::unique_ptr<ISymbolTable> createSymbolTableFromIncludePaths() {
        AssetManagerSymbolTableBuilder builder;
        for (const std::string& path : mOptions.includePaths) {
            if (mOptions.verbose) {
                mContext.getDiagnostics()->note(DiagMessage(path) << "loading include path");
            }

            std::unique_ptr<android::AssetManager> assetManager =
                    util::make_unique<android::AssetManager>();
            int32_t cookie = 0;
            if (!assetManager->addAssetPath(android::String8(path.data(), path.size()), &cookie)) {
                mContext.getDiagnostics()->error(
                        DiagMessage(path) << "failed to load include path");
                return {};
            }
            builder.add(std::move(assetManager));
        }
        return builder.build();
    }

    std::unique_ptr<ResourceTable> loadTable(const Source& source, const void* data, size_t len) {
        std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();
        BinaryResourceParser parser(&mContext, table.get(), source, data, len);
        if (!parser.parse()) {
            return {};
        }
        return table;
    }

    /**
     * Inflates an XML file from the source path.
     */
    static std::unique_ptr<xml::XmlResource> loadXml(const std::string& path, IDiagnostics* diag) {
        std::ifstream fin(path, std::ifstream::binary);
        if (!fin) {
            diag->error(DiagMessage(path) << strerror(errno));
            return {};
        }

        return xml::inflate(&fin, diag, Source(path));
    }

    static std::unique_ptr<xml::XmlResource> loadBinaryXmlSkipFileExport(
            const Source& source,
            const void* data, size_t len,
            IDiagnostics* diag) {
        std::string errorStr;
        ssize_t offset = getWrappedDataOffset(data, len, &errorStr);
        if (offset < 0) {
            diag->error(DiagMessage(source) << errorStr);
            return {};
        }

        std::unique_ptr<xml::XmlResource> xmlRes = xml::inflate(
                reinterpret_cast<const uint8_t*>(data) + static_cast<size_t>(offset),
                len - static_cast<size_t>(offset),
                diag,
                source);
        if (!xmlRes) {
            return {};
        }
        return xmlRes;
    }

    static std::unique_ptr<ResourceFile> loadFileExportHeader(const Source& source,
                                                              const void* data, size_t len,
                                                              IDiagnostics* diag) {
        std::unique_ptr<ResourceFile> resFile = util::make_unique<ResourceFile>();
        std::string errorStr;
        ssize_t offset = unwrapFileExportHeader(data, len, resFile.get(), &errorStr);
        if (offset < 0) {
            diag->error(DiagMessage(source) << errorStr);
            return {};
        }
        return resFile;
    }

    uint32_t getCompressionFlags(const StringPiece& str) {
        if (mOptions.doNotCompressAnything) {
            return 0;
        }

        for (const std::string& extension : mOptions.extensionsToNotCompress) {
            if (util::stringEndsWith<char>(str, extension)) {
                return 0;
            }
        }
        return ArchiveEntry::kCompress;
    }

    bool copyFileToArchive(io::IFile* file, const std::string& outPath,
                           IArchiveWriter* writer) {
        std::unique_ptr<io::IData> data = file->openAsData();
        if (!data) {
            mContext.getDiagnostics()->error(DiagMessage(file->getSource())
                                             << "failed to open file");
            return false;
        }

        std::string errorStr;
        ssize_t offset = getWrappedDataOffset(data->data(), data->size(), &errorStr);
        if (offset < 0) {
            mContext.getDiagnostics()->error(DiagMessage(file->getSource()) << errorStr);
            return false;
        }

        if (writer->startEntry(outPath, getCompressionFlags(outPath))) {
            if (writer->writeEntry(reinterpret_cast<const uint8_t*>(data->data()) + offset,
                                   data->size() - static_cast<size_t>(offset))) {
                if (writer->finishEntry()) {
                    return true;
                }
            }
        }

        mContext.getDiagnostics()->error(
                DiagMessage(mOptions.outputPath) << "failed to write file " << outPath);
        return false;
    }

    Maybe<AppInfo> extractAppInfoFromManifest(xml::XmlResource* xmlRes) {
        // Make sure the first element is <manifest> with package attribute.
        if (xml::Element* manifestEl = xml::findRootElement(xmlRes->root.get())) {
            if (manifestEl->namespaceUri.empty() && manifestEl->name == u"manifest") {
                if (xml::Attribute* packageAttr = manifestEl->findAttribute({}, u"package")) {
                    return AppInfo{ packageAttr->value };
                }
            }
        }
        return {};
    }

    /**
     * Precondition: ResourceTable doesn't have any IDs assigned yet, nor is it linked.
     * Postcondition: ResourceTable has only one package left. All others are stripped, or there
     *                is an error and false is returned.
     */
    bool verifyNoExternalPackages() {
        auto isExtPackageFunc = [&](const std::unique_ptr<ResourceTablePackage>& pkg) -> bool {
            return mContext.getCompilationPackage() != pkg->name ||
                    !pkg->id ||
                    pkg->id.value() != mContext.getPackageId();
        };

        bool error = false;
        for (const auto& package : mFinalTable.packages) {
            if (isExtPackageFunc(package)) {
                // We have a package that is not related to the one we're building!
                for (const auto& type : package->types) {
                    for (const auto& entry : type->entries) {
                        ResourceNameRef resName(package->name, type->type, entry->name);

                        for (const auto& configValue : entry->values) {
                            // Special case the occurrence of an ID that is being generated for the
                            // 'android' package. This is due to legacy reasons.
                            if (valueCast<Id>(configValue.value.get()) &&
                                    package->name == u"android") {
                                mContext.getDiagnostics()->warn(
                                        DiagMessage(configValue.value->getSource())
                                        << "generated id '" << resName
                                        << "' for external package '" << package->name
                                        << "'");
                            } else {
                                mContext.getDiagnostics()->error(
                                        DiagMessage(configValue.value->getSource())
                                        << "defined resource '" << resName
                                        << "' for external package '" << package->name
                                        << "'");
                                error = true;
                            }
                        }
                    }
                }
            }
        }

        auto newEndIter = std::remove_if(mFinalTable.packages.begin(), mFinalTable.packages.end(),
                                         isExtPackageFunc);
        mFinalTable.packages.erase(newEndIter, mFinalTable.packages.end());
        return !error;
    }

    std::unique_ptr<IArchiveWriter> makeArchiveWriter() {
        if (mOptions.outputToDirectory) {
            return createDirectoryArchiveWriter(mContext.getDiagnostics(), mOptions.outputPath);
        } else {
            return createZipFileArchiveWriter(mContext.getDiagnostics(), mOptions.outputPath);
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

        if (writer->startEntry("resources.arsc", ArchiveEntry::kAlign)) {
            if (writer->writeEntry(buffer)) {
                if (writer->finishEntry()) {
                    return true;
                }
            }
        }

        mContext.getDiagnostics()->error(
                DiagMessage() << "failed to write resources.arsc to archive");
        return false;
    }

    bool flattenXml(xml::XmlResource* xmlRes, const StringPiece& path, Maybe<size_t> maxSdkLevel,
                    IArchiveWriter* writer) {
        BigBuffer buffer(1024);
        XmlFlattenerOptions options = {};
        options.keepRawValues = mOptions.staticLib;
        options.maxSdkLevel = maxSdkLevel;
        XmlFlattener flattener(&buffer, options);
        if (!flattener.consume(&mContext, xmlRes)) {
            return false;
        }

        if (writer->startEntry(path, ArchiveEntry::kCompress)) {
            if (writer->writeEntry(buffer)) {
                if (writer->finishEntry()) {
                    return true;
                }
            }
        }
        mContext.getDiagnostics()->error(
                DiagMessage() << "failed to write " << path << " to archive");
        return false;
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

    bool writeManifestJavaFile(xml::XmlResource* manifestXml) {
        if (!mOptions.generateJavaClassPath) {
            return true;
        }

        std::string outPath = mOptions.generateJavaClassPath.value();
        file::appendPath(&outPath,
                         file::packageToPath(util::utf16ToUtf8(mContext.getCompilationPackage())));
        file::mkdirs(outPath);
        file::appendPath(&outPath, "Manifest.java");

        std::ofstream fout(outPath, std::ofstream::binary);
        if (!fout) {
            mContext.getDiagnostics()->error(DiagMessage() << strerror(errno));
            return false;
        }

        ManifestClassGenerator generator;
        if (!generator.generate(mContext.getDiagnostics(), mContext.getCompilationPackage(),
                                manifestXml, &fout)) {
            return false;
        }

        if (!fout) {
            mContext.getDiagnostics()->error(DiagMessage() << strerror(errno));
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

    bool mergeStaticLibrary(const std::string& input) {
        // TODO(adamlesinski): Load resources from a static library APK and merge the table into
        // TableMerger.
        mContext.getDiagnostics()->warn(DiagMessage()
                                        << "linking static libraries not supported yet: "
                                        << input);
        return true;
    }

    bool mergeResourceTable(io::IFile* file, bool override) {
        if (mOptions.verbose) {
            mContext.getDiagnostics()->note(DiagMessage() << "linking " << file->getSource());
        }

        std::unique_ptr<io::IData> data = file->openAsData();
        if (!data) {
            mContext.getDiagnostics()->error(DiagMessage(file->getSource())
                                             << "failed to open file");
            return false;
        }

        std::unique_ptr<ResourceTable> table = loadTable(file->getSource(), data->data(),
                                                         data->size());
        if (!table) {
            return false;
        }

        bool result = false;
        if (override) {
            result = mTableMerger->mergeOverlay(file->getSource(), table.get());
        } else {
            result = mTableMerger->merge(file->getSource(), table.get());
        }
        return result;
    }

    bool mergeCompiledFile(io::IFile* file, std::unique_ptr<ResourceFile> fileDesc, bool overlay) {
        if (mOptions.verbose) {
            mContext.getDiagnostics()->note(DiagMessage() << "adding " << file->getSource());
        }

        bool result = false;
        if (overlay) {
            result = mTableMerger->mergeFileOverlay(*fileDesc, file);
        } else {
            result = mTableMerger->mergeFile(*fileDesc, file);
        }

        if (!result) {
            return false;
        }

        // Add the exports of this file to the table.
        for (SourcedResourceName& exportedSymbol : fileDesc->exportedSymbols) {
            if (exportedSymbol.name.package.empty()) {
                exportedSymbol.name.package = mContext.getCompilationPackage().toString();
            }

            ResourceNameRef resName = exportedSymbol.name;

            Maybe<ResourceName> mangledName = mContext.getNameMangler()->mangleName(
                    exportedSymbol.name);
            if (mangledName) {
                resName = mangledName.value();
            }

            std::unique_ptr<Id> id = util::make_unique<Id>();
            id->setSource(fileDesc->source.withLine(exportedSymbol.line));
            bool result = mFinalTable.addResourceAllowMangled(resName, {}, std::move(id),
                                                              mContext.getDiagnostics());
            if (!result) {
                return false;
            }
        }
        return true;
    }

    /**
     * Creates an io::IFileCollection from the ZIP archive and processes the files within.
     */
    bool mergeArchive(const std::string& input, bool override) {
        std::string errorStr;
        std::unique_ptr<io::ZipFileCollection> collection = io::ZipFileCollection::create(
                input, &errorStr);
        if (!collection) {
            mContext.getDiagnostics()->error(DiagMessage(input) << errorStr);
            return false;
        }

        bool error = false;
        for (auto iter = collection->iterator(); iter->hasNext(); ) {
            if (!processFile(iter->next(), override)) {
                error = true;
            }
        }

        // Make sure to move the collection into the set of IFileCollections.
        mCollections.push_back(std::move(collection));
        return !error;
    }

    bool processFile(const std::string& path, bool override) {
        if (util::stringEndsWith<char>(path, ".flata")) {
            return mergeArchive(path, override);
        }

        io::IFile* file = mFileCollection->insertFile(path);
        return processFile(file, override);
    }

    bool processFile(io::IFile* file, bool override) {
        const Source& src = file->getSource();
        if (util::stringEndsWith<char>(src.path, ".arsc.flat")) {
            return mergeResourceTable(file, override);
        } else if (util::stringEndsWith<char>(src.path, ".flat")){
            // Try opening the file and looking for an Export header.
            std::unique_ptr<io::IData> data = file->openAsData();
            if (!data) {
                mContext.getDiagnostics()->error(DiagMessage(src) << "failed to open");
                return false;
            }

            std::unique_ptr<ResourceFile> resourceFile = loadFileExportHeader(
                    src, data->data(), data->size(), mContext.getDiagnostics());
            if (resourceFile) {
                return mergeCompiledFile(file, std::move(resourceFile), override);
            }
        } else {
            // Ignore non .flat files. This could be classes.dex or something else that happens
            // to be in an archive.
        }

        return false;
    }

    int run(const std::vector<std::string>& inputFiles) {
        // Load the AndroidManifest.xml
        std::unique_ptr<xml::XmlResource> manifestXml = loadXml(mOptions.manifestPath,
                                                                mContext.getDiagnostics());
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

        TableMergerOptions tableMergerOptions;
        tableMergerOptions.autoAddOverlay = mOptions.autoAddOverlay;
        mTableMerger = util::make_unique<TableMerger>(&mContext, &mFinalTable, tableMergerOptions);

        if (mOptions.verbose) {
            mContext.getDiagnostics()->note(
                    DiagMessage() << "linking package '" << mContext.mCompilationPackage << "' "
                                  << "with package ID " << std::hex << (int) mContext.mPackageId);
        }


        for (const std::string& input : inputFiles) {
            if (!processFile(input, false)) {
                mContext.getDiagnostics()->error(DiagMessage() << "failed parsing input");
                return 1;
            }
        }

        for (const std::string& input : mOptions.overlayFiles) {
            if (!processFile(input, true)) {
                mContext.getDiagnostics()->error(DiagMessage() << "failed parsing overlays");
                return 1;
            }
        }

        if (!verifyNoExternalPackages()) {
            return 1;
        }

        if (!mOptions.staticLib) {
            PrivateAttributeMover mover;
            if (!mover.consume(&mContext, &mFinalTable)) {
                mContext.getDiagnostics()->error(
                        DiagMessage() << "failed moving private attributes");
                return 1;
            }
        }

        {
            IdAssigner idAssigner;
            if (!idAssigner.consume(&mContext, &mFinalTable)) {
                mContext.getDiagnostics()->error(DiagMessage() << "failed assigning IDs");
                return 1;
            }
        }

        mContext.mNameMangler = util::make_unique<NameMangler>(NameManglerPolicy{
                mContext.mCompilationPackage, mTableMerger->getMergedPackages() });
        mContext.mSymbols = JoinedSymbolTableBuilder()
                .addSymbolTable(util::make_unique<SymbolTableWrapper>(&mFinalTable))
                .addSymbolTable(std::move(mContext.mSymbols))
                .build();

        {
            ReferenceLinker linker;
            if (!linker.consume(&mContext, &mFinalTable)) {
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

        bool error = false;
        {
            ManifestFixer manifestFixer(mOptions.manifestFixerOptions);
            if (!manifestFixer.consume(&mContext, manifestXml.get())) {
                error = true;
            }

            // AndroidManifest.xml has no resource name, but the CallSite is built from the name
            // (aka, which package the AndroidManifest.xml is coming from).
            // So we give it a package name so it can see local resources.
            manifestXml->file.name.package = mContext.getCompilationPackage().toString();

            XmlReferenceLinker manifestLinker;
            if (manifestLinker.consume(&mContext, manifestXml.get())) {
                if (!proguard::collectProguardRulesForManifest(Source(mOptions.manifestPath),
                                                               manifestXml.get(),
                                                               &proguardKeepSet)) {
                    error = true;
                }

                if (mOptions.generateJavaClassPath) {
                    if (!writeManifestJavaFile(manifestXml.get())) {
                        error = true;
                    }
                }

                if (!flattenXml(manifestXml.get(), "AndroidManifest.xml", {},
                                archiveWriter.get())) {
                    error = true;
                }
            } else {
                error = true;
            }
        }

        if (error) {
            mContext.getDiagnostics()->error(DiagMessage() << "failed processing manifest");
            return 1;
        }

        for (auto& mergeEntry : mTableMerger->getFilesToMerge()) {
            const ResourceKeyRef& key = mergeEntry.first;
            const FileToMerge& fileToMerge = mergeEntry.second;

            const StringPiece path = fileToMerge.file->getSource().path;

            if (key.name.type != ResourceType::kRaw &&
                    (util::stringEndsWith<char>(path, ".xml.flat") ||
                    util::stringEndsWith<char>(path, ".xml"))) {
                if (mOptions.verbose) {
                    mContext.getDiagnostics()->note(DiagMessage() << "linking " << path);
                }

                io::IFile* file = fileToMerge.file;
                std::unique_ptr<io::IData> data = file->openAsData();
                if (!data) {
                    mContext.getDiagnostics()->error(DiagMessage(file->getSource())
                                                     << "failed to open file");
                    return 1;
                }

                std::unique_ptr<xml::XmlResource> xmlRes;
                if (util::stringEndsWith<char>(path, ".flat")) {
                    xmlRes = loadBinaryXmlSkipFileExport(file->getSource(),
                                                         data->data(), data->size(),
                                                         mContext.getDiagnostics());
                } else {
                    xmlRes = xml::inflate(data->data(), data->size(), mContext.getDiagnostics(),
                                          file->getSource());
                }

                if (!xmlRes) {
                    return 1;
                }

                // Create the file description header.
                xmlRes->file = ResourceFile{
                        key.name.toResourceName(),
                        key.config,
                        fileToMerge.originalSource,
                };

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

                    if (!flattenXml(xmlRes.get(), fileToMerge.dstPath, maxSdkLevel,
                                    archiveWriter.get())) {
                        error = true;
                    }

                    if (!mOptions.noAutoVersion) {
                        Maybe<ResourceTable::SearchResult> result = mFinalTable.findResource(
                                xmlRes->file.name);
                        for (int sdkLevel : xmlLinker.getSdkLevels()) {
                            if (sdkLevel > xmlRes->file.config.sdkVersion &&
                                    shouldGenerateVersionedResource(result.value().entry,
                                                                    xmlRes->file.config,
                                                                    sdkLevel)) {
                                xmlRes->file.config.sdkVersion = sdkLevel;

                                std::string genResourcePath = ResourceUtils::buildResourceFileName(
                                        xmlRes->file, mContext.getNameMangler());

                                bool added = mFinalTable.addFileReference(
                                        xmlRes->file.name,
                                        xmlRes->file.config,
                                        xmlRes->file.source,
                                        util::utf8ToUtf16(genResourcePath),
                                        mContext.getDiagnostics());
                                if (!added) {
                                    error = true;
                                    continue;
                                }

                                if (!flattenXml(xmlRes.get(), genResourcePath, sdkLevel,
                                                archiveWriter.get())) {
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
                    mContext.getDiagnostics()->note(DiagMessage() << "copying " << path);
                }

                if (!copyFileToArchive(fileToMerge.file, fileToMerge.dstPath,
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
            if (!versioner.consume(&mContext, &mFinalTable)) {
                mContext.getDiagnostics()->error(DiagMessage() << "failed versioning styles");
                return 1;
            }
        }

        if (!flattenTable(&mFinalTable, archiveWriter.get())) {
            mContext.getDiagnostics()->error(DiagMessage() << "failed to write resources.arsc");
            return 1;
        }

        if (mOptions.generateJavaClassPath) {
            JavaClassGeneratorOptions options;
            options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;

            if (mOptions.staticLib) {
                options.useFinal = false;
            }

            const StringPiece16 actualPackage = mContext.getCompilationPackage();
            StringPiece16 outputPackage = mContext.getCompilationPackage();
            if (mOptions.customJavaPackage) {
                // Override the output java package to the custom one.
                outputPackage = mOptions.customJavaPackage.value();
            }

            if (mOptions.privateSymbols) {
                // If we defined a private symbols package, we only emit Public symbols
                // to the original package, and private and public symbols to the private package.

                options.types = JavaClassGeneratorOptions::SymbolTypes::kPublic;
                if (!writeJavaFile(&mFinalTable, mContext.getCompilationPackage(),
                                   outputPackage, options)) {
                    return 1;
                }

                options.types = JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate;
                outputPackage = mOptions.privateSymbols.value();
            }

            if (!writeJavaFile(&mFinalTable, actualPackage, outputPackage, options)) {
                return 1;
            }

            for (const std::u16string& extraPackage : mOptions.extraJavaPackages) {
                if (!writeJavaFile(&mFinalTable, actualPackage, extraPackage, options)) {
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
            Debug::printTable(&mFinalTable);
        }
        return 0;
    }

private:
    LinkOptions mOptions;
    LinkContext mContext;
    ResourceTable mFinalTable;

    ResourceTable mLocalFileTable;
    std::unique_ptr<TableMerger> mTableMerger;

    // A pointer to the FileCollection representing the filesystem (not archives).
    io::FileCollection* mFileCollection;

    // A vector of IFileCollections. This is mainly here to keep ownership of the collections.
    std::vector<std::unique_ptr<io::IFileCollection>> mCollections;
};

int link(const std::vector<StringPiece>& args) {
    LinkOptions options;
    Maybe<std::string> privateSymbolsPackage;
    Maybe<std::string> minSdkVersion, targetSdkVersion;
    Maybe<std::string> renameManifestPackage, renameInstrumentationTargetPackage;
    Maybe<std::string> versionCode, versionName;
    Maybe<std::string> customJavaPackage;
    std::vector<std::string> extraJavaPackages;
    Flags flags = Flags()
            .requiredFlag("-o", "Output path", &options.outputPath)
            .requiredFlag("--manifest", "Path to the Android manifest to build",
                          &options.manifestPath)
            .optionalFlagList("-I", "Adds an Android APK to link against", &options.includePaths)
            .optionalFlagList("-R", "Compilation unit to link, using `overlay` semantics.\n"
                              "The last conflicting resource given takes precedence.",
                              &options.overlayFiles)
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
            .optionalFlag("--min-sdk-version", "Default minimum SDK version to use for "
                          "AndroidManifest.xml", &minSdkVersion)
            .optionalFlag("--target-sdk-version", "Default target SDK version to use for "
                          "AndroidManifest.xml", &targetSdkVersion)
            .optionalFlag("--version-code", "Version code (integer) to inject into the "
                          "AndroidManifest.xml if none is present", &versionCode)
            .optionalFlag("--version-name", "Version name to inject into the AndroidManifest.xml "
                          "if none is present", &versionName)
            .optionalSwitch("--static-lib", "Generate a static Android library", &options.staticLib)
            .optionalFlag("--private-symbols", "Package name to use when generating R.java for "
                          "private symbols.\n"
                          "If not specified, public and private symbols will use the application's "
                          "package name", &privateSymbolsPackage)
            .optionalFlag("--custom-package", "Custom Java package under which to generate R.java",
                          &customJavaPackage)
            .optionalFlagList("--extra-packages", "Generate the same R.java but with different "
                              "package names", &extraJavaPackages)
            .optionalSwitch("--auto-add-overlay", "Allows the addition of new resources in "
                            "overlays without <add-resource> tags", &options.autoAddOverlay)
            .optionalFlag("--rename-manifest-package", "Renames the package in AndroidManifest.xml",
                          &renameManifestPackage)
            .optionalFlag("--rename-instrumentation-target-package",
                          "Changes the name of the target package for instrumentation. Most useful "
                          "when used\nin conjunction with --rename-manifest-package",
                          &renameInstrumentationTargetPackage)
            .optionalFlagList("-0", "File extensions not to compress",
                              &options.extensionsToNotCompress)
            .optionalSwitch("-v", "Enables verbose logging", &options.verbose);

    if (!flags.parse("aapt2 link", args, &std::cerr)) {
        return 1;
    }

    if (privateSymbolsPackage) {
        options.privateSymbols = util::utf8ToUtf16(privateSymbolsPackage.value());
    }

    if (minSdkVersion) {
        options.manifestFixerOptions.minSdkVersionDefault =
                util::utf8ToUtf16(minSdkVersion.value());
    }

    if (targetSdkVersion) {
        options.manifestFixerOptions.targetSdkVersionDefault =
                util::utf8ToUtf16(targetSdkVersion.value());
    }

    if (renameManifestPackage) {
        options.manifestFixerOptions.renameManifestPackage =
                util::utf8ToUtf16(renameManifestPackage.value());
    }

    if (renameInstrumentationTargetPackage) {
        options.manifestFixerOptions.renameInstrumentationTargetPackage =
                util::utf8ToUtf16(renameInstrumentationTargetPackage.value());
    }

    if (versionCode) {
        options.manifestFixerOptions.versionCodeDefault = util::utf8ToUtf16(versionCode.value());
    }

    if (versionName) {
        options.manifestFixerOptions.versionNameDefault = util::utf8ToUtf16(versionName.value());
    }

    if (customJavaPackage) {
        options.customJavaPackage = util::utf8ToUtf16(customJavaPackage.value());
    }

    // Populate the set of extra packages for which to generate R.java.
    for (std::string& extraPackage : extraJavaPackages) {
        // A given package can actually be a colon separated list of packages.
        for (StringPiece package : util::split(extraPackage, ':')) {
            options.extraJavaPackages.insert(util::utf8ToUtf16(package));
        }
    }

    LinkCommand cmd(options);
    return cmd.run(flags.getArgs());
}

} // namespace aapt
