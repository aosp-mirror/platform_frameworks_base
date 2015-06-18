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
#include "BigBuffer.h"
#include "BinaryResourceParser.h"
#include "BindingXmlPullParser.h"
#include "Debug.h"
#include "Files.h"
#include "Flag.h"
#include "JavaClassGenerator.h"
#include "Linker.h"
#include "ManifestMerger.h"
#include "ManifestParser.h"
#include "ManifestValidator.h"
#include "NameMangler.h"
#include "Png.h"
#include "ProguardRules.h"
#include "ResourceParser.h"
#include "ResourceTable.h"
#include "ResourceTableResolver.h"
#include "ResourceValues.h"
#include "SdkConstants.h"
#include "SourceXmlPullParser.h"
#include "StringPiece.h"
#include "TableFlattener.h"
#include "Util.h"
#include "XmlFlattener.h"
#include "ZipFile.h"

#include <algorithm>
#include <androidfw/AssetManager.h>
#include <cstdlib>
#include <dirent.h>
#include <errno.h>
#include <fstream>
#include <iostream>
#include <sstream>
#include <sys/stat.h>
#include <unordered_set>
#include <utils/Errors.h>

constexpr const char* kAaptVersionStr = "2.0-alpha";

using namespace aapt;

/**
 * Used with smart pointers to free malloc'ed memory.
 */
struct DeleteMalloc {
    void operator()(void* ptr) {
        free(ptr);
    }
};

struct StaticLibraryData {
    Source source;
    std::unique_ptr<ZipFile> apk;
};

/**
 * Collect files from 'root', filtering out any files that do not
 * match the FileFilter 'filter'.
 */
bool walkTree(const Source& root, const FileFilter& filter,
              std::vector<Source>* outEntries) {
    bool error = false;

    for (const std::string& dirName : listFiles(root.path)) {
        std::string dir = root.path;
        appendPath(&dir, dirName);

        FileType ft = getFileType(dir);
        if (!filter(dirName, ft)) {
            continue;
        }

        if (ft != FileType::kDirectory) {
            continue;
        }

        for (const std::string& fileName : listFiles(dir)) {
            std::string file(dir);
            appendPath(&file, fileName);

            FileType ft = getFileType(file);
            if (!filter(fileName, ft)) {
                continue;
            }

            if (ft != FileType::kRegular) {
                Logger::error(Source{ file }) << "not a regular file." << std::endl;
                error = true;
                continue;
            }
            outEntries->push_back(Source{ file });
        }
    }
    return !error;
}

void versionStylesForCompat(const std::shared_ptr<ResourceTable>& table) {
    for (auto& type : *table) {
        if (type->type != ResourceType::kStyle) {
            continue;
        }

        for (auto& entry : type->entries) {
            // Add the versioned styles we want to create
            // here. They are added to the table after
            // iterating over the original set of styles.
            //
            // A stack is used since auto-generated styles
            // from later versions should override
            // auto-generated styles from earlier versions.
            // Iterating over the styles is done in order,
            // so we will always visit sdkVersions from smallest
            // to largest.
            std::stack<ResourceConfigValue> addStack;

            for (ResourceConfigValue& configValue : entry->values) {
                visitFunc<Style>(*configValue.value, [&](Style& style) {
                    // Collect which entries we've stripped and the smallest
                    // SDK level which was stripped.
                    size_t minSdkStripped = std::numeric_limits<size_t>::max();
                    std::vector<Style::Entry> stripped;

                    // Iterate over the style's entries and erase/record the
                    // attributes whose SDK level exceeds the config's sdkVersion.
                    auto iter = style.entries.begin();
                    while (iter != style.entries.end()) {
                        if (iter->key.name.package == u"android") {
                            size_t sdkLevel = findAttributeSdkLevel(iter->key.name);
                            if (sdkLevel > 1 && sdkLevel > configValue.config.sdkVersion) {
                                // Record that we are about to strip this.
                                stripped.emplace_back(std::move(*iter));
                                minSdkStripped = std::min(minSdkStripped, sdkLevel);

                                // Erase this from this style.
                                iter = style.entries.erase(iter);
                                continue;
                            }
                        }
                        ++iter;
                    }

                    if (!stripped.empty()) {
                        // We have stripped attributes, so let's create a new style to hold them.
                        ConfigDescription versionConfig(configValue.config);
                        versionConfig.sdkVersion = minSdkStripped;

                        ResourceConfigValue value = {
                                versionConfig,
                                configValue.source,
                                {},

                                // Create a copy of the original style.
                                std::unique_ptr<Value>(configValue.value->clone(
                                            &table->getValueStringPool()))
                        };

                        Style& newStyle = static_cast<Style&>(*value.value);

                        // Move the recorded stripped attributes into this new style.
                        std::move(stripped.begin(), stripped.end(),
                                  std::back_inserter(newStyle.entries));

                        // We will add this style to the table later. If we do it now, we will
                        // mess up iteration.
                        addStack.push(std::move(value));
                    }
                });
            }

            auto comparator =
                    [](const ResourceConfigValue& lhs, const ConfigDescription& rhs) -> bool {
                        return lhs.config < rhs;
                    };

            while (!addStack.empty()) {
                ResourceConfigValue& value = addStack.top();
                auto iter = std::lower_bound(entry->values.begin(), entry->values.end(),
                                             value.config, comparator);
                if (iter == entry->values.end() || iter->config != value.config) {
                    entry->values.insert(iter, std::move(value));
                }
                addStack.pop();
            }
        }
    }
}

struct CompileItem {
    ResourceName name;
    ConfigDescription config;
    Source source;
    std::string extension;
};

struct LinkItem {
    ResourceName name;
    ConfigDescription config;
    Source source;
    std::string originalPath;
    ZipFile* apk;
    std::u16string originalPackage;
};

template <typename TChar>
static BasicStringPiece<TChar> getExtension(const BasicStringPiece<TChar>& str) {
    auto iter = std::find(str.begin(), str.end(), static_cast<TChar>('.'));
    if (iter == str.end()) {
        return BasicStringPiece<TChar>();
    }
    size_t offset = (iter - str.begin()) + 1;
    return str.substr(offset, str.size() - offset);
}

std::string buildFileReference(const ResourceNameRef& name, const ConfigDescription& config,
                               const StringPiece& extension) {
    std::stringstream path;
    path << "res/" << name.type;
    if (config != ConfigDescription{}) {
        path << "-" << config;
    }
    path << "/" << util::utf16ToUtf8(name.entry);
    if (!extension.empty()) {
        path << "." << extension;
    }
    return path.str();
}

std::string buildFileReference(const CompileItem& item) {
    return buildFileReference(item.name, item.config, item.extension);
}

std::string buildFileReference(const LinkItem& item) {
    return buildFileReference(item.name, item.config, getExtension<char>(item.originalPath));
}

template <typename T>
bool addFileReference(const std::shared_ptr<ResourceTable>& table, const T& item) {
    StringPool& pool = table->getValueStringPool();
    StringPool::Ref ref = pool.makeRef(util::utf8ToUtf16(buildFileReference(item)),
                                       StringPool::Context{ 0, item.config });
    return table->addResource(item.name, item.config, item.source.line(0),
                              util::make_unique<FileReference>(ref));
}

struct AaptOptions {
    enum class Phase {
        Link,
        Compile,
        Dump,
        DumpStyleGraph,
    };

    enum class PackageType {
        StandardApp,
        StaticLibrary,
    };

    // The phase to process.
    Phase phase;

    // The type of package to produce.
    PackageType packageType = PackageType::StandardApp;

    // Details about the app.
    AppInfo appInfo;

    // The location of the manifest file.
    Source manifest;

    // The APK files to link.
    std::vector<Source> input;

    // The libraries these files may reference.
    std::vector<Source> libraries;

    // Output path. This can be a directory or file
    // depending on the phase.
    Source output;

    // Directory in which to write binding xml files.
    Source bindingOutput;

    // Directory to in which to generate R.java.
    Maybe<Source> generateJavaClass;

    // File in which to produce proguard rules.
    Maybe<Source> generateProguardRules;

    // Whether to output verbose details about
    // compilation.
    bool verbose = false;

    // Whether or not to auto-version styles or layouts
    // referencing attributes defined in a newer SDK
    // level than the style or layout is defined for.
    bool versionStylesAndLayouts = true;

    // The target style that will have it's style hierarchy dumped
    // when the phase is DumpStyleGraph.
    ResourceName dumpStyleTarget;
};

struct IdCollector : public xml::Visitor {
    IdCollector(const Source& source, const std::shared_ptr<ResourceTable>& table) :
            mSource(source), mTable(table) {
    }

    virtual void visit(xml::Text* node) override {}

    virtual void visit(xml::Namespace* node) override {
        for (const auto& child : node->children) {
            child->accept(this);
        }
    }

    virtual void visit(xml::Element* node) override {
        for (const xml::Attribute& attr : node->attributes) {
            bool create = false;
            bool priv = false;
            ResourceNameRef nameRef;
            if (ResourceParser::tryParseReference(attr.value, &nameRef, &create, &priv)) {
                if (create) {
                    mTable->addResource(nameRef, {}, mSource.line(node->lineNumber),
                                        util::make_unique<Id>());
                }
            }
        }

        for (const auto& child : node->children) {
            child->accept(this);
        }
    }

private:
    Source mSource;
    std::shared_ptr<ResourceTable> mTable;
};

bool compileXml(const AaptOptions& options, const std::shared_ptr<ResourceTable>& table,
                const CompileItem& item, ZipFile* outApk) {
    std::ifstream in(item.source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(item.source) << strerror(errno) << std::endl;
        return false;
    }

    SourceLogger logger(item.source);
    std::unique_ptr<xml::Node> root = xml::inflate(&in, &logger);
    if (!root) {
        return false;
    }

    // Collect any resource ID's declared here.
    IdCollector idCollector(item.source, table);
    root->accept(&idCollector);

    BigBuffer outBuffer(1024);
    if (!xml::flatten(root.get(), options.appInfo.package, &outBuffer)) {
        logger.error() << "failed to encode XML." << std::endl;
        return false;
    }

    // Write the resulting compiled XML file to the output APK.
    if (outApk->add(outBuffer, buildFileReference(item).data(), ZipEntry::kCompressStored,
                nullptr) != android::NO_ERROR) {
        Logger::error(options.output) << "failed to write compiled '" << item.source
                                      << "' to apk." << std::endl;
        return false;
    }
    return true;
}

/**
 * Determines if a layout should be auto generated based on SDK level. We do not
 * generate a layout if there is already a layout defined whose SDK version is greater than
 * the one we want to generate.
 */
bool shouldGenerateVersionedResource(const std::shared_ptr<const ResourceTable>& table,
                                     const ResourceName& name, const ConfigDescription& config,
                                     int sdkVersionToGenerate) {
    assert(sdkVersionToGenerate > config.sdkVersion);
    const ResourceTableType* type;
    const ResourceEntry* entry;
    std::tie(type, entry) = table->findResource(name);
    assert(type && entry);

    auto iter = std::lower_bound(entry->values.begin(), entry->values.end(), config,
            [](const ResourceConfigValue& lhs, const ConfigDescription& config) -> bool {
        return lhs.config < config;
    });

    assert(iter != entry->values.end());
    ++iter;

    if (iter == entry->values.end()) {
        return true;
    }

    ConfigDescription newConfig = config;
    newConfig.sdkVersion = sdkVersionToGenerate;
    return newConfig < iter->config;
}

bool linkXml(const AaptOptions& options, const std::shared_ptr<ResourceTable>& table,
             const std::shared_ptr<IResolver>& resolver, const LinkItem& item,
             const void* data, size_t dataLen, ZipFile* outApk, std::queue<LinkItem>* outQueue,
             proguard::KeepSet* keepSet) {
    SourceLogger logger(item.source);
    std::unique_ptr<xml::Node> root = xml::inflate(data, dataLen, &logger);
    if (!root) {
        return false;
    }

    xml::FlattenOptions xmlOptions;
    if (options.packageType == AaptOptions::PackageType::StaticLibrary) {
        xmlOptions.keepRawValues = true;
    }

    if (options.versionStylesAndLayouts) {
        // We strip attributes that do not belong in this version of the resource.
        // Non-version qualified resources have an implicit version 1 requirement.
        xmlOptions.maxSdkAttribute = item.config.sdkVersion ? item.config.sdkVersion : 1;
    }

    if (options.generateProguardRules) {
        proguard::collectProguardRules(item.name.type, item.source, root.get(), keepSet);
    }

    BigBuffer outBuffer(1024);
    Maybe<size_t> minStrippedSdk = xml::flattenAndLink(item.source, root.get(),
                                                       item.originalPackage, resolver,
                                                       xmlOptions, &outBuffer);
    if (!minStrippedSdk) {
        logger.error() << "failed to encode XML." << std::endl;
        return false;
    }

    if (minStrippedSdk.value() > 0) {
        // Something was stripped, so let's generate a new file
        // with the version of the smallest SDK version stripped.
        // We can only generate a versioned layout if there doesn't exist a layout
        // with sdk version greater than the current one but less than the one we
        // want to generate.
        if (shouldGenerateVersionedResource(table, item.name, item.config,
                    minStrippedSdk.value())) {
            LinkItem newWork = item;
            newWork.config.sdkVersion = minStrippedSdk.value();
            outQueue->push(newWork);

            if (!addFileReference(table, newWork)) {
                Logger::error(options.output) << "failed to add auto-versioned resource '"
                                              << newWork.name << "'." << std::endl;
                return false;
            }
        }
    }

    if (outApk->add(outBuffer, buildFileReference(item).data(), ZipEntry::kCompressDeflated,
                nullptr) != android::NO_ERROR) {
        Logger::error(options.output) << "failed to write linked file '"
                                      << buildFileReference(item) << "' to apk." << std::endl;
        return false;
    }
    return true;
}

bool compilePng(const AaptOptions& options, const CompileItem& item, ZipFile* outApk) {
    std::ifstream in(item.source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(item.source) << strerror(errno) << std::endl;
        return false;
    }

    BigBuffer outBuffer(4096);
    std::string err;
    Png png;
    if (!png.process(item.source, in, &outBuffer, {}, &err)) {
        Logger::error(item.source) << err << std::endl;
        return false;
    }

    if (outApk->add(outBuffer, buildFileReference(item).data(), ZipEntry::kCompressStored,
                nullptr) != android::NO_ERROR) {
        Logger::error(options.output) << "failed to write compiled '" << item.source
                                      << "' to apk." << std::endl;
        return false;
    }
    return true;
}

bool copyFile(const AaptOptions& options, const CompileItem& item, ZipFile* outApk) {
    if (outApk->add(item.source.path.data(), buildFileReference(item).data(),
                ZipEntry::kCompressStored, nullptr) != android::NO_ERROR) {
        Logger::error(options.output) << "failed to copy file '" << item.source << "' to apk."
                                      << std::endl;
        return false;
    }
    return true;
}

bool compileManifest(const AaptOptions& options, const std::shared_ptr<IResolver>& resolver,
                     const std::map<std::shared_ptr<ResourceTable>, StaticLibraryData>& libApks,
                     const android::ResTable& table, ZipFile* outApk, proguard::KeepSet* keepSet) {
    if (options.verbose) {
        Logger::note(options.manifest) << "compiling AndroidManifest.xml." << std::endl;
    }

    std::ifstream in(options.manifest.path, std::ifstream::binary);
    if (!in) {
        Logger::error(options.manifest) << strerror(errno) << std::endl;
        return false;
    }

    SourceLogger logger(options.manifest);
    std::unique_ptr<xml::Node> root = xml::inflate(&in, &logger);
    if (!root) {
        return false;
    }

    ManifestMerger merger({});
    if (!merger.setAppManifest(options.manifest, options.appInfo.package, std::move(root))) {
        return false;
    }

    for (const auto& entry : libApks) {
        ZipFile* libApk = entry.second.apk.get();
        const std::u16string& libPackage = entry.first->getPackage();
        const Source& libSource = entry.second.source;

        ZipEntry* zipEntry = libApk->getEntryByName("AndroidManifest.xml");
        if (!zipEntry) {
            continue;
        }

        std::unique_ptr<void, DeleteMalloc> uncompressedData = std::unique_ptr<void, DeleteMalloc>(
                libApk->uncompress(zipEntry));
        assert(uncompressedData);

        SourceLogger logger(libSource);
        std::unique_ptr<xml::Node> libRoot = xml::inflate(uncompressedData.get(),
                                                          zipEntry->getUncompressedLen(), &logger);
        if (!libRoot) {
            return false;
        }

        if (!merger.mergeLibraryManifest(libSource, libPackage, std::move(libRoot))) {
            return false;
        }
    }

    if (options.generateProguardRules) {
        proguard::collectProguardRulesForManifest(options.manifest, merger.getMergedXml(),
                                                  keepSet);
    }

    BigBuffer outBuffer(1024);
    if (!xml::flattenAndLink(options.manifest, merger.getMergedXml(), options.appInfo.package,
                resolver, {}, &outBuffer)) {
        return false;
    }

    std::unique_ptr<uint8_t[]> data = util::copy(outBuffer);

    android::ResXMLTree tree;
    if (tree.setTo(data.get(), outBuffer.size(), false) != android::NO_ERROR) {
        return false;
    }

    ManifestValidator validator(table);
    if (!validator.validate(options.manifest, &tree)) {
        return false;
    }

    if (outApk->add(data.get(), outBuffer.size(), "AndroidManifest.xml",
                ZipEntry::kCompressStored, nullptr) != android::NO_ERROR) {
        Logger::error(options.output) << "failed to write 'AndroidManifest.xml' to apk."
                                      << std::endl;
        return false;
    }
    return true;
}

static bool compileValues(const std::shared_ptr<ResourceTable>& table, const Source& source,
                          const ConfigDescription& config) {
    std::ifstream in(source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(source) << strerror(errno) << std::endl;
        return false;
    }

    std::shared_ptr<XmlPullParser> xmlParser = std::make_shared<SourceXmlPullParser>(in);
    ResourceParser parser(table, source, config, xmlParser);
    return parser.parse();
}

struct ResourcePathData {
    std::u16string resourceDir;
    std::u16string name;
    std::string extension;
    ConfigDescription config;
};

/**
 * Resource file paths are expected to look like:
 * [--/res/]type[-config]/name
 */
static Maybe<ResourcePathData> extractResourcePathData(const Source& source) {
    // TODO(adamlesinski): Use Windows path separator on windows.
    std::vector<std::string> parts = util::splitAndLowercase(source.path, '/');
    if (parts.size() < 2) {
        Logger::error(source) << "bad resource path." << std::endl;
        return {};
    }

    std::string& dir = parts[parts.size() - 2];
    StringPiece dirStr = dir;

    ConfigDescription config;
    size_t dashPos = dir.find('-');
    if (dashPos != std::string::npos) {
        StringPiece configStr = dirStr.substr(dashPos + 1, dir.size() - (dashPos + 1));
        if (!ConfigDescription::parse(configStr, &config)) {
            Logger::error(source)
                    << "invalid configuration '"
                    << configStr
                    << "'."
                    << std::endl;
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
            util::utf8ToUtf16(dirStr),
            util::utf8ToUtf16(name),
            extension.toString(),
            config
    };
}

bool writeResourceTable(const AaptOptions& options, const std::shared_ptr<ResourceTable>& table,
                        const TableFlattener::Options& flattenerOptions, ZipFile* outApk) {
    if (table->begin() != table->end()) {
        BigBuffer buffer(1024);
        TableFlattener flattener(flattenerOptions);
        if (!flattener.flatten(&buffer, *table)) {
            Logger::error() << "failed to flatten resource table." << std::endl;
            return false;
        }

        if (options.verbose) {
            Logger::note() << "Final resource table size=" << util::formatSize(buffer.size())
                           << std::endl;
        }

        if (outApk->add(buffer, "resources.arsc", ZipEntry::kCompressStored, nullptr) !=
                android::NO_ERROR) {
            Logger::note(options.output) << "failed to store resource table." << std::endl;
            return false;
        }
    }
    return true;
}

/**
 * For each FileReference in the table, adds a LinkItem to the link queue for processing.
 */
static void addApkFilesToLinkQueue(const std::u16string& package, const Source& source,
                                   const std::shared_ptr<ResourceTable>& table,
                                   const std::unique_ptr<ZipFile>& apk,
                                   std::queue<LinkItem>* outLinkQueue) {
    bool mangle = package != table->getPackage();
    for (auto& type : *table) {
        for (auto& entry : type->entries) {
            ResourceName name = { package, type->type, entry->name };
            if (mangle) {
                NameMangler::mangle(table->getPackage(), &name.entry);
            }

            for (auto& value : entry->values) {
                visitFunc<FileReference>(*value.value, [&](FileReference& ref) {
                    std::string pathUtf8 = util::utf16ToUtf8(*ref.path);
                    Source newSource = source;
                    newSource.path += "/";
                    newSource.path += pathUtf8;
                    outLinkQueue->push(LinkItem{
                            name, value.config, newSource, pathUtf8, apk.get(),
                            table->getPackage() });
                    // Now rewrite the file path.
                    if (mangle) {
                        ref.path = table->getValueStringPool().makeRef(util::utf8ToUtf16(
                                    buildFileReference(name, value.config,
                                                       getExtension<char>(pathUtf8))));
                    }
                });
            }
        }
    }
}

static constexpr int kOpenFlags = ZipFile::kOpenCreate | ZipFile::kOpenTruncate |
        ZipFile::kOpenReadWrite;

bool link(const AaptOptions& options, const std::shared_ptr<ResourceTable>& outTable,
          const std::shared_ptr<IResolver>& resolver) {
    std::map<std::shared_ptr<ResourceTable>, StaticLibraryData> apkFiles;
    std::unordered_set<std::u16string> linkedPackages;

    // Populate the linkedPackages with our own.
    linkedPackages.insert(options.appInfo.package);

    // Load all APK files.
    for (const Source& source : options.input) {
        std::unique_ptr<ZipFile> zipFile = util::make_unique<ZipFile>();
        if (zipFile->open(source.path.data(), ZipFile::kOpenReadOnly) != android::NO_ERROR) {
            Logger::error(source) << "failed to open: " << strerror(errno) << std::endl;
            return false;
        }

        std::shared_ptr<ResourceTable> table = std::make_shared<ResourceTable>();

        ZipEntry* entry = zipFile->getEntryByName("resources.arsc");
        if (!entry) {
            Logger::error(source) << "missing 'resources.arsc'." << std::endl;
            return false;
        }

        std::unique_ptr<void, DeleteMalloc> uncompressedData = std::unique_ptr<void, DeleteMalloc>(
                zipFile->uncompress(entry));
        assert(uncompressedData);

        BinaryResourceParser parser(table, resolver, source, options.appInfo.package, 
                                    uncompressedData.get(), entry->getUncompressedLen());
        if (!parser.parse()) {
            return false;
        }

        // Keep track of where this table came from.
        apkFiles[table] = StaticLibraryData{ source, std::move(zipFile) };

        // Add the package to the set of linked packages.
        linkedPackages.insert(table->getPackage());
    }

    std::queue<LinkItem> linkQueue;
    for (auto& p : apkFiles) {
        const std::shared_ptr<ResourceTable>& inTable = p.first;

        // Collect all FileReferences and add them to the queue for processing.
        addApkFilesToLinkQueue(options.appInfo.package, p.second.source, inTable, p.second.apk,
                               &linkQueue);

        // Merge the tables.
        if (!outTable->merge(std::move(*inTable))) {
            return false;
        }
    }

    // Version all styles referencing attributes outside of their specified SDK version.
    if (options.versionStylesAndLayouts) {
        versionStylesForCompat(outTable);
    }

    {
        // Now that everything is merged, let's link it.
        Linker::Options linkerOptions;
        if (options.packageType == AaptOptions::PackageType::StaticLibrary) {
            linkerOptions.linkResourceIds = false;
        }
        Linker linker(outTable, resolver, linkerOptions);
        if (!linker.linkAndValidate()) {
            return false;
        }

        // Verify that all symbols exist.
        const auto& unresolvedRefs = linker.getUnresolvedReferences();
        if (!unresolvedRefs.empty()) {
            for (const auto& entry : unresolvedRefs) {
                for (const auto& source : entry.second) {
                    Logger::error(source) << "unresolved symbol '" << entry.first << "'."
                                          << std::endl;
                }
            }
            return false;
        }
    }

    // Open the output APK file for writing.
    ZipFile outApk;
    if (outApk.open(options.output.path.data(), kOpenFlags) != android::NO_ERROR) {
        Logger::error(options.output) << "failed to open: " << strerror(errno) << std::endl;
        return false;
    }

    proguard::KeepSet keepSet;

    android::ResTable binTable;
    if (!compileManifest(options, resolver, apkFiles, binTable, &outApk, &keepSet)) {
        return false;
    }

    for (; !linkQueue.empty(); linkQueue.pop()) {
        const LinkItem& item = linkQueue.front();

        assert(!item.originalPackage.empty());
        ZipEntry* entry = item.apk->getEntryByName(item.originalPath.data());
        if (!entry) {
            Logger::error(item.source) << "failed to find '" << item.originalPath << "'."
                                       << std::endl;
            return false;
        }

        if (util::stringEndsWith<char>(item.originalPath, ".xml")) {
            void* uncompressedData = item.apk->uncompress(entry);
            assert(uncompressedData);

            if (!linkXml(options, outTable, resolver, item, uncompressedData,
                        entry->getUncompressedLen(), &outApk, &linkQueue, &keepSet)) {
                Logger::error(options.output) << "failed to link '" << item.originalPath << "'."
                                              << std::endl;
                return false;
            }
        } else {
            if (outApk.add(item.apk, entry, buildFileReference(item).data(), 0, nullptr) !=
                    android::NO_ERROR) {
                Logger::error(options.output) << "failed to copy '" << item.originalPath << "'."
                                              << std::endl;
                return false;
            }
        }
    }

    // Generate the Java class file.
    if (options.generateJavaClass) {
        JavaClassGenerator::Options javaOptions;
        if (options.packageType == AaptOptions::PackageType::StaticLibrary) {
            javaOptions.useFinal = false;
        }
        JavaClassGenerator generator(outTable, javaOptions);

        for (const std::u16string& package : linkedPackages) {
            Source outPath = options.generateJavaClass.value();

            // Build the output directory from the package name.
            // Eg. com.android.app -> com/android/app
            const std::string packageUtf8 = util::utf16ToUtf8(package);
            for (StringPiece part : util::tokenize<char>(packageUtf8, '.')) {
                appendPath(&outPath.path, part);
            }

            if (!mkdirs(outPath.path)) {
                Logger::error(outPath) << strerror(errno) << std::endl;
                return false;
            }

            appendPath(&outPath.path, "R.java");

            if (options.verbose) {
                Logger::note(outPath) << "writing Java symbols." << std::endl;
            }

            std::ofstream fout(outPath.path);
            if (!fout) {
                Logger::error(outPath) << strerror(errno) << std::endl;
                return false;
            }

            if (!generator.generate(package, fout)) {
                Logger::error(outPath) << generator.getError() << "." << std::endl;
                return false;
            }
        }
    }

    // Generate the Proguard rules file.
    if (options.generateProguardRules) {
        const Source& outPath = options.generateProguardRules.value();

        if (options.verbose) {
            Logger::note(outPath) << "writing proguard rules." << std::endl;
        }

        std::ofstream fout(outPath.path);
        if (!fout) {
            Logger::error(outPath) << strerror(errno) << std::endl;
            return false;
        }

        if (!proguard::writeKeepSet(&fout, keepSet)) {
            Logger::error(outPath) << "failed to write proguard rules." << std::endl;
            return false;
        }
    }

    outTable->getValueStringPool().prune();
    outTable->getValueStringPool().sort(
            [](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
                if (a.context.priority < b.context.priority) {
                    return true;
                }

                if (a.context.priority > b.context.priority) {
                    return false;
                }
                return a.value < b.value;
            });


    // Flatten the resource table.
    TableFlattener::Options flattenerOptions;
    if (options.packageType != AaptOptions::PackageType::StaticLibrary) {
        flattenerOptions.useExtendedChunks = false;
    }

    if (!writeResourceTable(options, outTable, flattenerOptions, &outApk)) {
        return false;
    }

    outApk.flush();
    return true;
}

bool compile(const AaptOptions& options, const std::shared_ptr<ResourceTable>& table,
             const std::shared_ptr<IResolver>& resolver) {
    std::queue<CompileItem> compileQueue;
    bool error = false;

    // Compile all the resource files passed in on the command line.
    for (const Source& source : options.input) {
        // Need to parse the resource type/config/filename.
        Maybe<ResourcePathData> maybePathData = extractResourcePathData(source);
        if (!maybePathData) {
            return false;
        }

        const ResourcePathData& pathData = maybePathData.value();
        if (pathData.resourceDir == u"values") {
            // The file is in the values directory, which means its contents will
            // go into the resource table.
            if (options.verbose) {
                Logger::note(source) << "compiling values." << std::endl;
            }

            error |= !compileValues(table, source, pathData.config);
        } else {
            // The file is in a directory like 'layout' or 'drawable'. Find out
            // the type.
            const ResourceType* type = parseResourceType(pathData.resourceDir);
            if (!type) {
                Logger::error(source) << "invalid resource type '" << pathData.resourceDir << "'."
                                      << std::endl;
                return false;
            }

            compileQueue.push(CompileItem{
                    ResourceName{ table->getPackage(), *type, pathData.name },
                    pathData.config,
                    source,
                    pathData.extension
            });
        }
    }

    if (error) {
        return false;
    }
    // Open the output APK file for writing.
    ZipFile outApk;
    if (outApk.open(options.output.path.data(), kOpenFlags) != android::NO_ERROR) {
        Logger::error(options.output) << "failed to open: " << strerror(errno) << std::endl;
        return false;
    }

    // Compile each file.
    for (; !compileQueue.empty(); compileQueue.pop()) {
        const CompileItem& item = compileQueue.front();

        // Add the file name to the resource table.
        error |= !addFileReference(table, item);

        if (item.extension == "xml") {
            error |= !compileXml(options, table, item, &outApk);
        } else if (item.extension == "png" || item.extension == "9.png") {
            error |= !compilePng(options, item, &outApk);
        } else {
            error |= !copyFile(options, item, &outApk);
        }
    }

    if (error) {
        return false;
    }

    // Link and assign resource IDs.
    Linker linker(table, resolver, {});
    if (!linker.linkAndValidate()) {
        return false;
    }

    // Flatten the resource table.
    if (!writeResourceTable(options, table, {}, &outApk)) {
        return false;
    }

    outApk.flush();
    return true;
}

bool loadAppInfo(const Source& source, AppInfo* outInfo) {
    std::ifstream ifs(source.path, std::ifstream::in | std::ifstream::binary);
    if (!ifs) {
        Logger::error(source) << strerror(errno) << std::endl;
        return false;
    }

    ManifestParser parser;
    std::shared_ptr<XmlPullParser> pullParser = std::make_shared<SourceXmlPullParser>(ifs);
    return parser.parse(source, pullParser, outInfo);
}

static void printCommandsAndDie() {
    std::cerr << "The following commands are supported:" << std::endl << std::endl;
    std::cerr << "compile       compiles a subset of resources" << std::endl;
    std::cerr << "link          links together compiled resources and libraries" << std::endl;
    std::cerr << "dump          dumps resource contents to to standard out" << std::endl;
    std::cerr << std::endl;
    std::cerr << "run aapt2 with one of the commands and the -h flag for extra details."
              << std::endl;
    exit(1);
}

static AaptOptions prepareArgs(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "no command specified." << std::endl << std::endl;
        printCommandsAndDie();
    }

    const StringPiece command(argv[1]);
    argc -= 2;
    argv += 2;

    AaptOptions options;

    if (command == "--version" || command == "version") {
        std::cout << kAaptVersionStr << std::endl;
        exit(0);
    } else if (command == "link") {
        options.phase = AaptOptions::Phase::Link;
    } else if (command == "compile") {
        options.phase = AaptOptions::Phase::Compile;
    } else if (command == "dump") {
        options.phase = AaptOptions::Phase::Dump;
    } else if (command == "dump-style-graph") {
        options.phase = AaptOptions::Phase::DumpStyleGraph;
    } else {
        std::cerr << "invalid command '" << command << "'." << std::endl << std::endl;
        printCommandsAndDie();
    }

    bool isStaticLib = false;
    if (options.phase == AaptOptions::Phase::Link) {
        flag::requiredFlag("--manifest", "AndroidManifest.xml of your app",
                [&options](const StringPiece& arg) {
                    options.manifest = Source{ arg.toString() };
                });

        flag::optionalFlag("-I", "add an Android APK to link against",
                [&options](const StringPiece& arg) {
                    options.libraries.push_back(Source{ arg.toString() });
                });

        flag::optionalFlag("--java", "directory in which to generate R.java",
                [&options](const StringPiece& arg) {
                    options.generateJavaClass = Source{ arg.toString() };
                });

        flag::optionalFlag("--proguard", "file in which to output proguard rules",
                [&options](const StringPiece& arg) {
                    options.generateProguardRules = Source{ arg.toString() };
                });

        flag::optionalSwitch("--static-lib", "generate a static Android library", true,
                             &isStaticLib);

        flag::optionalFlag("--binding", "Output directory for binding XML files",
                [&options](const StringPiece& arg) {
                    options.bindingOutput = Source{ arg.toString() };
                });
        flag::optionalSwitch("--no-version", "Disables automatic style and layout versioning",
                             false, &options.versionStylesAndLayouts);
    }

    if (options.phase == AaptOptions::Phase::Compile ||
            options.phase == AaptOptions::Phase::Link) {
        // Common flags for all steps.
        flag::requiredFlag("-o", "Output path", [&options](const StringPiece& arg) {
            options.output = Source{ arg.toString() };
        });
    }

    if (options.phase == AaptOptions::Phase::DumpStyleGraph) {
        flag::requiredFlag("--style", "Name of the style to dump",
                [&options](const StringPiece& arg, std::string* outError) -> bool {
                    Reference styleReference;
                    if (!ResourceParser::parseStyleParentReference(util::utf8ToUtf16(arg),
                                &styleReference, outError)) {
                        return false;
                    }
                    options.dumpStyleTarget = styleReference.name;
                    return true;
                });
    }

    bool help = false;
    flag::optionalSwitch("-v", "enables verbose logging", true, &options.verbose);
    flag::optionalSwitch("-h", "displays this help menu", true, &help);

    // Build the command string for output (eg. "aapt2 compile").
    std::string fullCommand = "aapt2";
    fullCommand += " ";
    fullCommand += command.toString();

    // Actually read the command line flags.
    flag::parse(argc, argv, fullCommand);

    if (help) {
        flag::usageAndDie(fullCommand);
    }

    if (isStaticLib) {
        options.packageType = AaptOptions::PackageType::StaticLibrary;
    }

    // Copy all the remaining arguments.
    for (const std::string& arg : flag::getArgs()) {
        options.input.push_back(Source{ arg });
    }
    return options;
}

static bool doDump(const AaptOptions& options) {
    for (const Source& source : options.input) {
        std::unique_ptr<ZipFile> zipFile = util::make_unique<ZipFile>();
        if (zipFile->open(source.path.data(), ZipFile::kOpenReadOnly) != android::NO_ERROR) {
            Logger::error(source) << "failed to open: " << strerror(errno) << std::endl;
            return false;
        }

        std::shared_ptr<ResourceTable> table = std::make_shared<ResourceTable>();
        std::shared_ptr<ResourceTableResolver> resolver =
                std::make_shared<ResourceTableResolver>(
                        table, std::vector<std::shared_ptr<const android::AssetManager>>());

        ZipEntry* entry = zipFile->getEntryByName("resources.arsc");
        if (!entry) {
            Logger::error(source) << "missing 'resources.arsc'." << std::endl;
            return false;
        }

        std::unique_ptr<void, DeleteMalloc> uncompressedData = std::unique_ptr<void, DeleteMalloc>(
                zipFile->uncompress(entry));
        assert(uncompressedData);

        BinaryResourceParser parser(table, resolver, source, {}, uncompressedData.get(),
                                    entry->getUncompressedLen());
        if (!parser.parse()) {
            return false;
        }

        if (options.phase == AaptOptions::Phase::Dump) {
            Debug::printTable(table);
        } else if (options.phase == AaptOptions::Phase::DumpStyleGraph) {
            Debug::printStyleGraph(table, options.dumpStyleTarget);
        }
    }
    return true;
}

int main(int argc, char** argv) {
    Logger::setLog(std::make_shared<Log>(std::cerr, std::cerr));
    AaptOptions options = prepareArgs(argc, argv);

    if (options.phase == AaptOptions::Phase::Dump ||
            options.phase == AaptOptions::Phase::DumpStyleGraph) {
        if (!doDump(options)) {
            return 1;
        }
        return 0;
    }

    // If we specified a manifest, go ahead and load the package name from the manifest.
    if (!options.manifest.path.empty()) {
        if (!loadAppInfo(options.manifest, &options.appInfo)) {
            return false;
        }

        if (options.appInfo.package.empty()) {
            Logger::error() << "no package name specified." << std::endl;
            return false;
        }
    }

    // Every phase needs a resource table.
    std::shared_ptr<ResourceTable> table = std::make_shared<ResourceTable>();

    // The package name is empty when in the compile phase.
    table->setPackage(options.appInfo.package);
    if (options.appInfo.package == u"android") {
        table->setPackageId(0x01);
    } else {
        table->setPackageId(0x7f);
    }

    // Load the included libraries.
    std::vector<std::shared_ptr<const android::AssetManager>> sources;
    for (const Source& source : options.libraries) {
        std::shared_ptr<android::AssetManager> assetManager =
                std::make_shared<android::AssetManager>();
        int32_t cookie;
        if (!assetManager->addAssetPath(android::String8(source.path.data()), &cookie)) {
            Logger::error(source) << "failed to load library." << std::endl;
            return false;
        }

        if (cookie == 0) {
            Logger::error(source) << "failed to load library." << std::endl;
            return false;
        }
        sources.push_back(assetManager);
    }

    // Make the resolver that will cache IDs for us.
    std::shared_ptr<ResourceTableResolver> resolver = std::make_shared<ResourceTableResolver>(
            table, sources);

    if (options.phase == AaptOptions::Phase::Compile) {
        if (!compile(options, table, resolver)) {
            Logger::error() << "aapt exiting with failures." << std::endl;
            return 1;
        }
    } else if (options.phase == AaptOptions::Phase::Link) {
        if (!link(options, table, resolver)) {
            Logger::error() << "aapt exiting with failures." << std::endl;
            return 1;
        }
    }
    return 0;
}
