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
#include "Files.h"
#include "JavaClassGenerator.h"
#include "Linker.h"
#include "ManifestParser.h"
#include "ManifestValidator.h"
#include "ResourceParser.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "SdkConstants.h"
#include "SourceXmlPullParser.h"
#include "StringPiece.h"
#include "TableFlattener.h"
#include "Util.h"
#include "XmlFlattener.h"

#include <algorithm>
#include <androidfw/AssetManager.h>
#include <cstdlib>
#include <dirent.h>
#include <errno.h>
#include <fstream>
#include <iostream>
#include <sstream>
#include <sys/stat.h>

using namespace aapt;

void printTable(const ResourceTable& table) {
    std::cout << "ResourceTable package=" << table.getPackage();
    if (table.getPackageId() != ResourceTable::kUnsetPackageId) {
        std::cout << " id=" << std::hex << table.getPackageId() << std::dec;
    }
    std::cout << std::endl
         << "---------------------------------------------------------" << std::endl;

    for (const auto& type : table) {
        std::cout << "Type " << type->type;
        if (type->typeId != ResourceTableType::kUnsetTypeId) {
            std::cout << " [" << type->typeId << "]";
        }
        std::cout << " (" << type->entries.size() << " entries)" << std::endl;
        for (const auto& entry : type->entries) {
            std::cout << "  " << entry->name;
            if (entry->entryId != ResourceEntry::kUnsetEntryId) {
                std::cout << " [" << entry->entryId << "]";
            }
            std::cout << " (" << entry->values.size() << " configurations)";
            if (entry->publicStatus.isPublic) {
                std::cout << " PUBLIC";
            }
            std::cout << std::endl;
            for (const auto& value : entry->values) {
                std::cout << "    " << value.config << " (" << value.source << ") : ";
                value.value->print(std::cout);
                std::cout << std::endl;
            }
        }
    }
}

void printStringPool(const StringPool& pool) {
    std::cout << "String pool of length " << pool.size() << std::endl
         << "---------------------------------------------------------" << std::endl;

    size_t i = 0;
    for (const auto& entry : pool) {
        std::cout << "[" << i << "]: "
             << entry->value
             << " (Priority " << entry->context.priority
             << ", Config '" << entry->context.config << "')"
             << std::endl;
        i++;
    }
}

std::unique_ptr<FileReference> makeFileReference(StringPool& pool, const StringPiece& filename,
        ResourceType type, const ConfigDescription& config) {
    std::stringstream path;
    path << "res/" << type;
    if (config != ConfigDescription{}) {
        path << "-" << config;
    }
    path << "/" << filename;
    return util::make_unique<FileReference>(pool.makeRef(util::utf8ToUtf16(path.str())));
}

/**
 * Collect files from 'root', filtering out any files that do not
 * match the FileFilter 'filter'.
 */
bool walkTree(const StringPiece& root, const FileFilter& filter,
        std::vector<Source>& outEntries) {
    bool error = false;

    for (const std::string& dirName : listFiles(root)) {
        std::string dir(root.toString());
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
                Logger::error(Source{ file })
                    << "not a regular file."
                    << std::endl;
                error = true;
                continue;
            }
            outEntries.emplace_back(Source{ file });
        }
    }
    return !error;
}

bool loadBinaryResourceTable(std::shared_ptr<ResourceTable> table, const Source& source) {
    std::ifstream ifs(source.path, std::ifstream::in | std::ifstream::binary);
    if (!ifs) {
        Logger::error(source) << strerror(errno) << std::endl;
        return false;
    }

    std::streampos fsize = ifs.tellg();
    ifs.seekg(0, std::ios::end);
    fsize = ifs.tellg() - fsize;
    ifs.seekg(0, std::ios::beg);

    assert(fsize >= 0);
    size_t dataSize = static_cast<size_t>(fsize);
    char* buf = new char[dataSize];
    ifs.read(buf, dataSize);

    BinaryResourceParser parser(table, source, buf, dataSize);
    bool result = parser.parse();

    delete [] buf;
    return result;
}

bool loadResTable(android::ResTable* table, const Source& source) {
    // For NO_ERROR (which on Windows is a MACRO).
    using namespace android;

    std::ifstream ifs(source.path, std::ifstream::in | std::ifstream::binary);
    if (!ifs) {
        Logger::error(source) << strerror(errno) << std::endl;
        return false;
    }

    std::streampos fsize = ifs.tellg();
    ifs.seekg(0, std::ios::end);
    fsize = ifs.tellg() - fsize;
    ifs.seekg(0, std::ios::beg);

    assert(fsize >= 0);
    size_t dataSize = static_cast<size_t>(fsize);
    char* buf = new char[dataSize];
    ifs.read(buf, dataSize);

    bool result = table->add(buf, dataSize, -1, true) == NO_ERROR;

    delete [] buf;
    return result;
}

void versionStylesForCompat(std::shared_ptr<ResourceTable> table) {
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
                            size_t sdkLevel = findAttributeSdkLevel(iter->key.name.entry);
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
                                std::unique_ptr<Value>(configValue.value->clone())
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

bool collectXml(std::shared_ptr<ResourceTable> table, const Source& source,
                const ResourceName& name,
                const ConfigDescription& config) {
    std::ifstream in(source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(source) << strerror(errno) << std::endl;
        return false;
    }

    std::set<size_t> sdkLevels;

    SourceXmlPullParser pullParser(in);
    while (XmlPullParser::isGoodEvent(pullParser.next())) {
        if (pullParser.getEvent() != XmlPullParser::Event::kStartElement) {
            continue;
        }

        const auto endIter = pullParser.endAttributes();
        for (auto iter = pullParser.beginAttributes(); iter != endIter; ++iter) {
            if (iter->namespaceUri == u"http://schemas.android.com/apk/res/android") {
                size_t sdkLevel = findAttributeSdkLevel(iter->name);
                if (sdkLevel > 1) {
                    sdkLevels.insert(sdkLevel);
                }
            }

            ResourceNameRef refName;
            bool create = false;
            bool privateRef = false;
            if (ResourceParser::tryParseReference(iter->value, &refName, &create, &privateRef) &&
                    create) {
                table->addResource(refName, {}, source.line(pullParser.getLineNumber()),
                                   util::make_unique<Id>());
            }
        }
    }

    std::unique_ptr<FileReference> fileResource = makeFileReference(
            table->getValueStringPool(),
            util::utf16ToUtf8(name.entry) + ".xml",
            name.type,
            config);
    table->addResource(name, config, source.line(0), std::move(fileResource));

    for (size_t level : sdkLevels) {
        Logger::note(source)
                << "creating v" << level << " versioned file."
                << std::endl;
        ConfigDescription newConfig = config;
        newConfig.sdkVersion = level;

        std::unique_ptr<FileReference> fileResource = makeFileReference(
                table->getValueStringPool(),
                util::utf16ToUtf8(name.entry) + ".xml",
                name.type,
                newConfig);
        table->addResource(name, newConfig, source.line(0), std::move(fileResource));
    }
    return true;
}

struct CompileXml {
    Source source;
    ResourceName name;
    ConfigDescription config;
};

bool compileXml(std::shared_ptr<Resolver> resolver, const CompileXml& item,
                const Source& outputSource, std::queue<CompileXml>* queue) {
    std::ifstream in(item.source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(item.source) << strerror(errno) << std::endl;
        return false;
    }

    BigBuffer outBuffer(1024);
    std::shared_ptr<XmlPullParser> xmlParser = std::make_shared<SourceXmlPullParser>(in);
    XmlFlattener flattener(resolver);

    // We strip attributes that do not belong in this version of the resource.
    // Non-version qualified resources have an implicit version 1 requirement.
    XmlFlattener::Options options = { item.config.sdkVersion ? item.config.sdkVersion : 1 };
    Maybe<size_t> minStrippedSdk = flattener.flatten(item.source, xmlParser, &outBuffer, options);
    if (!minStrippedSdk) {
        return false;
    }

    if (minStrippedSdk.value() > 0) {
        // Something was stripped, so let's generate a new file
        // with the version of the smallest SDK version stripped.
        CompileXml newWork = item;
        newWork.config.sdkVersion = minStrippedSdk.value();
        queue->push(newWork);
    }

    std::ofstream out(outputSource.path, std::ofstream::binary);
    if (!out) {
        Logger::error(outputSource) << strerror(errno) << std::endl;
        return false;
    }

    if (!util::writeAll(out, outBuffer)) {
        Logger::error(outputSource) << strerror(errno) << std::endl;
        return false;
    }
    return true;
}

struct AaptOptions {
    enum class Phase {
        LegacyFull,
        Collect,
        Link,
        Compile,
    };

    // The phase to process.
    Phase phase;

    // Details about the app.
    AppInfo appInfo;

    // The location of the manifest file.
    Source manifest;

    // The files to process.
    std::vector<Source> sources;

    // The libraries these files may reference.
    std::vector<Source> libraries;

    // Output directory.
    Source output;

    // Whether to generate a Java Class.
    Maybe<Source> generateJavaClass;

    // Whether to output verbose details about
    // compilation.
    bool verbose = false;
};

bool compileAndroidManifest(std::shared_ptr<Resolver> resolver, const AaptOptions& options) {
    using namespace android;

    Source outSource = options.output;
    appendPath(&outSource.path, "AndroidManifest.xml");

    if (options.verbose) {
        Logger::note(outSource) << "compiling AndroidManifest.xml." << std::endl;
    }

    std::ifstream in(options.manifest.path, std::ifstream::binary);
    if (!in) {
        Logger::error(options.manifest) << strerror(errno) << std::endl;
        return false;
    }

    BigBuffer outBuffer(1024);
    std::shared_ptr<XmlPullParser> xmlParser = std::make_shared<SourceXmlPullParser>(in);
    XmlFlattener flattener(resolver);

    Maybe<size_t> result = flattener.flatten(options.manifest, xmlParser, &outBuffer,
                                             XmlFlattener::Options{});
    if (!result) {
        return false;
    }

    std::unique_ptr<uint8_t[]> data = std::unique_ptr<uint8_t[]>(new uint8_t[outBuffer.size()]);
    uint8_t* p = data.get();
    for (const auto& b : outBuffer) {
        memcpy(p, b.buffer.get(), b.size);
        p += b.size;
    }

    ResXMLTree tree;
    if (tree.setTo(data.get(), outBuffer.size()) != NO_ERROR) {
        return false;
    }

    ManifestValidator validator(resolver->getResTable());
    if (!validator.validate(options.manifest, &tree)) {
        return false;
    }

    std::ofstream out(outSource.path, std::ofstream::binary);
    if (!out) {
        Logger::error(outSource) << strerror(errno) << std::endl;
        return false;
    }

    if (!util::writeAll(out, outBuffer)) {
        Logger::error(outSource) << strerror(errno) << std::endl;
        return false;
    }
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

/**
 * Parses legacy options and walks the source directories collecting
 * files to process.
 */
bool prepareLegacy(std::vector<StringPiece>::const_iterator argsIter,
        const std::vector<StringPiece>::const_iterator argsEndIter,
        AaptOptions &options) {
    options.phase = AaptOptions::Phase::LegacyFull;

    std::vector<StringPiece> sourceDirs;
    while (argsIter != argsEndIter) {
        if (*argsIter == "-S") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-S missing argument." << std::endl;
                return false;
            }
            sourceDirs.push_back(*argsIter);
        } else if (*argsIter == "-I") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-I missing argument." << std::endl;
                return false;
            }
            options.libraries.push_back(Source{ argsIter->toString() });
        } else if (*argsIter == "-M") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-M missing argument." << std::endl;
                return false;
            }

            if (!options.manifest.path.empty()) {
                Logger::error() << "multiple -M flags are not allowed." << std::endl;
                return false;
            }
            options.manifest.path = argsIter->toString();
        } else if (*argsIter == "-o") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-o missing argument." << std::endl;
                return false;
            }
            options.output = Source{ argsIter->toString() };
        } else if (*argsIter == "-J") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-J missing argument." << std::endl;
                return false;
            }
            options.generateJavaClass = make_value<Source>(Source{ argsIter->toString() });
        } else if (*argsIter == "-v") {
            options.verbose = true;
        } else {
            Logger::error() << "unrecognized option '" << *argsIter << "'." << std::endl;
            return false;
        }

        ++argsIter;
    }

    if (options.manifest.path.empty()) {
        Logger::error() << "must specify manifest file with -M." << std::endl;
        return false;
    }

    // Load the App's package name, etc.
    if (!loadAppInfo(options.manifest, &options.appInfo)) {
        return false;
    }

    /**
     * Set up the file filter to ignore certain files.
     */
    const char* customIgnore = getenv("ANDROID_AAPT_IGNORE");
    FileFilter fileFilter;
    if (customIgnore && customIgnore[0]) {
        fileFilter.setPattern(customIgnore);
    } else {
        fileFilter.setPattern(
                "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~");
    }

    /*
     * Enumerate the files in each source directory.
     */
    for (const StringPiece& source : sourceDirs) {
        if (!walkTree(source, fileFilter, options.sources)) {
            return false;
        }
    }
    return true;
}

bool prepareCollect(std::vector<StringPiece>::const_iterator argsIter,
        const std::vector<StringPiece>::const_iterator argsEndIter,
        AaptOptions& options) {
    options.phase = AaptOptions::Phase::Collect;

    while (argsIter != argsEndIter) {
        if (*argsIter == "--package") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "--package missing argument." << std::endl;
                return false;
            }
            options.appInfo.package = util::utf8ToUtf16(*argsIter);
        } else if (*argsIter == "-o") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-o missing argument." << std::endl;
                return false;
            }
            options.output = Source{ argsIter->toString() };
        } else if (*argsIter == "-v") {
            options.verbose = true;
        } else if (argsIter->data()[0] != '-') {
            options.sources.push_back(Source{ argsIter->toString() });
        } else {
            Logger::error()
                    << "unknown option '"
                    << *argsIter
                    << "'."
                    << std::endl;
            return false;
        }
        ++argsIter;
    }
    return true;
}

bool prepareLink(std::vector<StringPiece>::const_iterator argsIter,
        const std::vector<StringPiece>::const_iterator argsEndIter,
        AaptOptions& options) {
    options.phase = AaptOptions::Phase::Link;

    while (argsIter != argsEndIter) {
        if (*argsIter == "--package") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "--package missing argument." << std::endl;
                return false;
            }
            options.appInfo.package = util::utf8ToUtf16(*argsIter);
        } else if (*argsIter == "-o") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-o missing argument." << std::endl;
                return false;
            }
            options.output = Source{ argsIter->toString() };
        } else if (*argsIter == "-I") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-I missing argument." << std::endl;
                return false;
            }
            options.libraries.push_back(Source{ argsIter->toString() });
        } else if (*argsIter == "--java") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "--java missing argument." << std::endl;
                return false;
            }
            options.generateJavaClass = make_value<Source>(Source{ argsIter->toString() });
        } else if (*argsIter == "-v") {
            options.verbose = true;
        } else if (argsIter->data()[0] != '-') {
            options.sources.push_back(Source{ argsIter->toString() });
        } else {
            Logger::error()
                    << "unknown option '"
                    << *argsIter
                    << "'."
                    << std::endl;
            return false;
        }
        ++argsIter;
    }
    return true;
}

bool prepareCompile(std::vector<StringPiece>::const_iterator argsIter,
        const std::vector<StringPiece>::const_iterator argsEndIter,
        AaptOptions& options) {
    options.phase = AaptOptions::Phase::Compile;

    while (argsIter != argsEndIter) {
        if (*argsIter == "--package") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "--package missing argument." << std::endl;
                return false;
            }
            options.appInfo.package = util::utf8ToUtf16(*argsIter);
        } else if (*argsIter == "-o") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-o missing argument." << std::endl;
                return false;
            }
            options.output = Source{ argsIter->toString() };
        } else if (*argsIter == "-I") {
            ++argsIter;
            if (argsIter == argsEndIter) {
                Logger::error() << "-I missing argument." << std::endl;
                return false;
            }
            options.libraries.push_back(Source{ argsIter->toString() });
        } else if (*argsIter == "-v") {
            options.verbose = true;
        } else if (argsIter->data()[0] != '-') {
            options.sources.push_back(Source{ argsIter->toString() });
        } else {
            Logger::error()
                    << "unknown option '"
                    << *argsIter
                    << "'."
                    << std::endl;
            return false;
        }
        ++argsIter;
    }
    return true;
}

struct CollectValuesItem {
    Source source;
    ConfigDescription config;
};

bool collectValues(std::shared_ptr<ResourceTable> table, const CollectValuesItem& item) {
    std::ifstream in(item.source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(item.source) << strerror(errno) << std::endl;
        return false;
    }

    std::shared_ptr<XmlPullParser> xmlParser = std::make_shared<SourceXmlPullParser>(in);
    ResourceParser parser(table, item.source, item.config, xmlParser);
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
Maybe<ResourcePathData> extractResourcePathData(const Source& source) {
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

static bool doLegacy(std::shared_ptr<ResourceTable> table, std::shared_ptr<Resolver> resolver,
                     const AaptOptions& options) {
    bool error = false;
    std::queue<CompileXml> xmlCompileQueue;

    //
    // Read values XML files and XML/PNG files.
    // Need to parse the resource type/config/filename.
    //
    for (const Source& source : options.sources) {
        Maybe<ResourcePathData> maybePathData = extractResourcePathData(source);
        if (!maybePathData) {
            return false;
        }

        const ResourcePathData& pathData = maybePathData.value();
        if (pathData.resourceDir == u"values") {
            if (options.verbose) {
                Logger::note(source) << "collecting values..." << std::endl;
            }

            error |= !collectValues(table, CollectValuesItem{ source, pathData.config });
            continue;
        }

        const ResourceType* type = parseResourceType(pathData.resourceDir);
        if (!type) {
            Logger::error(source)
                    << "invalid resource type '"
                    << pathData.resourceDir
                    << "'."
                    << std::endl;
            return false;
        }

        ResourceName resourceName = { table->getPackage(), *type, pathData.name };
        if (pathData.extension == "xml") {
            if (options.verbose) {
                Logger::note(source) << "collecting XML..." << std::endl;
            }

            error |= !collectXml(table, source, resourceName, pathData.config);
            xmlCompileQueue.push(CompileXml{
                    source,
                    resourceName,
                    pathData.config
            });
        } else {
            std::unique_ptr<FileReference> fileReference = makeFileReference(
                    table->getValueStringPool(),
                    util::utf16ToUtf8(pathData.name) + "." + pathData.extension,
                    *type, pathData.config);

            error |= !table->addResource(resourceName, pathData.config, source.line(0),
                                         std::move(fileReference));
        }
    }

    if (error) {
        return false;
    }

    versionStylesForCompat(table);

    //
    // Verify all references and data types.
    //
    Linker linker(table, resolver);
    if (!linker.linkAndValidate()) {
        Logger::error()
                << "linking failed."
                << std::endl;
        return false;
    }

    const auto& unresolvedRefs = linker.getUnresolvedReferences();
    if (!unresolvedRefs.empty()) {
        for (const auto& entry : unresolvedRefs) {
            for (const auto& source : entry.second) {
                Logger::error(source)
                        << "unresolved symbol '"
                        << entry.first
                        << "'."
                        << std::endl;
            }
        }
        return false;
    }

    //
    // Compile the XML files.
    //
    while (!xmlCompileQueue.empty()) {
        const CompileXml& item = xmlCompileQueue.front();

        // Create the output path from the resource name.
        std::stringstream outputPath;
        outputPath << item.name.type;
        if (item.config != ConfigDescription{}) {
            outputPath << "-" << item.config.toString();
        }

        Source outSource = options.output;
        appendPath(&outSource.path, "res");
        appendPath(&outSource.path, outputPath.str());

        if (!mkdirs(outSource.path)) {
            Logger::error(outSource) << strerror(errno) << std::endl;
            return false;
        }

        appendPath(&outSource.path, util::utf16ToUtf8(item.name.entry) + ".xml");

        if (options.verbose) {
            Logger::note(outSource) << "compiling XML file." << std::endl;
        }

        error |= !compileXml(resolver, item, outSource, &xmlCompileQueue);
        xmlCompileQueue.pop();
    }

    if (error) {
        return false;
    }

    //
    // Compile the AndroidManifest.xml file.
    //
    if (!compileAndroidManifest(resolver, options)) {
        return false;
    }

    //
    // Generate the Java R class.
    //
    if (options.generateJavaClass) {
        Source outPath = options.generateJavaClass.value();
        if (options.verbose) {
            Logger::note()
                    << "writing symbols to "
                    << outPath
                    << "."
                    << std::endl;
        }

        for (std::string& part : util::split(util::utf16ToUtf8(table->getPackage()), '.')) {
            appendPath(&outPath.path, part);
        }

        if (!mkdirs(outPath.path)) {
            Logger::error(outPath) << strerror(errno) << std::endl;
            return false;
        }

        appendPath(&outPath.path, "R.java");

        std::ofstream fout(outPath.path);
        if (!fout) {
            Logger::error(outPath) << strerror(errno) << std::endl;
            return false;
        }

        JavaClassGenerator generator(table, JavaClassGenerator::Options{});
        if (!generator.generate(fout)) {
            Logger::error(outPath)
                    << generator.getError()
                    << "."
                    << std::endl;
            return false;
        }
    }

    //
    // Flatten resource table.
    //
    if (table->begin() != table->end()) {
        BigBuffer buffer(1024);
        TableFlattener::Options tableOptions;
        tableOptions.useExtendedChunks = false;
        TableFlattener flattener(tableOptions);
        if (!flattener.flatten(&buffer, *table)) {
            Logger::error()
                    << "failed to flatten resource table->"
                    << std::endl;
            return false;
        }

        if (options.verbose) {
            Logger::note()
                    << "Final resource table size="
                    << util::formatSize(buffer.size())
                    << std::endl;
        }

        std::string outTable(options.output.path);
        appendPath(&outTable, "resources.arsc");

        std::ofstream fout(outTable, std::ofstream::binary);
        if (!fout) {
            Logger::error(Source{outTable})
                    << strerror(errno)
                    << "."
                    << std::endl;
            return false;
        }

        if (!util::writeAll(fout, buffer)) {
            Logger::error(Source{outTable})
                    << strerror(errno)
                    << "."
                    << std::endl;
            return false;
        }
        fout.flush();
    }
    return true;
}

static bool doCollect(std::shared_ptr<ResourceTable> table, std::shared_ptr<Resolver> resolver,
                      const AaptOptions& options) {
    bool error = false;

    //
    // Read values XML files and XML/PNG files.
    // Need to parse the resource type/config/filename.
    //
    for (const Source& source : options.sources) {
        Maybe<ResourcePathData> maybePathData = extractResourcePathData(source);
        if (!maybePathData) {
            return false;
        }

        const ResourcePathData& pathData = maybePathData.value();
        if (pathData.resourceDir == u"values") {
            if (options.verbose) {
                Logger::note(source) << "collecting values..." << std::endl;
            }

            error |= !collectValues(table, CollectValuesItem{ source, pathData.config });
            continue;
        }

        const ResourceType* type = parseResourceType(pathData.resourceDir);
        if (!type) {
            Logger::error(source)
                    << "invalid resource type '"
                    << pathData.resourceDir
                    << "'."
                    << std::endl;
            return false;
        }

        ResourceName resourceName = { table->getPackage(), *type, pathData.name };
        if (pathData.extension == "xml") {
            if (options.verbose) {
                Logger::note(source) << "collecting XML..." << std::endl;
            }

            error |= !collectXml(table, source, resourceName, pathData.config);
        } else {
            std::unique_ptr<FileReference> fileReference = makeFileReference(
                    table->getValueStringPool(),
                    util::utf16ToUtf8(pathData.name) + "." + pathData.extension,
                    *type,
                    pathData.config);
            error |= !table->addResource(resourceName, pathData.config, source.line(0),
                                         std::move(fileReference));
        }
    }

    if (error) {
        return false;
    }

    Linker linker(table, resolver);
    if (!linker.linkAndValidate()) {
        return false;
    }

    //
    // Flatten resource table->
    //
    if (table->begin() != table->end()) {
        BigBuffer buffer(1024);
        TableFlattener::Options tableOptions;
        tableOptions.useExtendedChunks = true;
        TableFlattener flattener(tableOptions);
        if (!flattener.flatten(&buffer, *table)) {
            Logger::error()
                    << "failed to flatten resource table->"
                    << std::endl;
            return false;
        }

        std::ofstream fout(options.output.path, std::ofstream::binary);
        if (!fout) {
            Logger::error(options.output)
                    << strerror(errno)
                    << "."
                    << std::endl;
            return false;
        }

        if (!util::writeAll(fout, buffer)) {
            Logger::error(options.output)
                    << strerror(errno)
                    << "."
                    << std::endl;
            return false;
        }
        fout.flush();
    }
    return true;
}

static bool doLink(std::shared_ptr<ResourceTable> table, std::shared_ptr<Resolver> resolver,
                   const AaptOptions& options) {
    bool error = false;

    for (const Source& source : options.sources) {
        error |= !loadBinaryResourceTable(table, source);
    }

    if (error) {
        return false;
    }

    versionStylesForCompat(table);

    Linker linker(table, resolver);
    if (!linker.linkAndValidate()) {
        return false;
    }

    const auto& unresolvedRefs = linker.getUnresolvedReferences();
    if (!unresolvedRefs.empty()) {
        for (const auto& entry : unresolvedRefs) {
            for (const auto& source : entry.second) {
                Logger::error(source)
                        << "unresolved symbol '"
                        << entry.first
                        << "'."
                        << std::endl;
            }
        }
        return false;
    }

    //
    // Generate the Java R class.
    //
    if (options.generateJavaClass) {
        Source outPath = options.generateJavaClass.value();
        if (options.verbose) {
            Logger::note()
                    << "writing symbols to "
                    << outPath
                    << "."
                    << std::endl;
        }

        for (std::string& part : util::split(util::utf16ToUtf8(table->getPackage()), '.')) {
            appendPath(&outPath.path, part);
        }

        if (!mkdirs(outPath.path)) {
            Logger::error(outPath) << strerror(errno) << std::endl;
            return false;
        }

        appendPath(&outPath.path, "R.java");

        std::ofstream fout(outPath.path);
        if (!fout) {
            Logger::error(outPath) << strerror(errno) << std::endl;
            return false;
        }

        JavaClassGenerator generator(table, JavaClassGenerator::Options{});
        if (!generator.generate(fout)) {
            Logger::error(outPath)
                    << generator.getError()
                    << "."
                    << std::endl;
            return false;
        }
    }

    //
    // Flatten resource table.
    //
    if (table->begin() != table->end()) {
        BigBuffer buffer(1024);
        TableFlattener::Options tableOptions;
        tableOptions.useExtendedChunks = false;
        TableFlattener flattener(tableOptions);
        if (!flattener.flatten(&buffer, *table)) {
            Logger::error()
                    << "failed to flatten resource table->"
                    << std::endl;
            return false;
        }

        if (options.verbose) {
            Logger::note()
                    << "Final resource table size="
                    << util::formatSize(buffer.size())
                    << std::endl;
        }

        std::ofstream fout(options.output.path, std::ofstream::binary);
        if (!fout) {
            Logger::error(options.output)
                    << strerror(errno)
                    << "."
                    << std::endl;
            return false;
        }

        if (!util::writeAll(fout, buffer)) {
            Logger::error(options.output)
                    << strerror(errno)
                    << "."
                    << std::endl;
            return false;
        }
        fout.flush();
    }
    return true;
}

static bool doCompile(std::shared_ptr<ResourceTable> table, std::shared_ptr<Resolver> resolver,
                      const AaptOptions& options) {
    std::queue<CompileXml> xmlCompileQueue;

    for (const Source& source : options.sources) {
        Maybe<ResourcePathData> maybePathData = extractResourcePathData(source);
        if (!maybePathData) {
            return false;
        }

        ResourcePathData& pathData = maybePathData.value();
        const ResourceType* type = parseResourceType(pathData.resourceDir);
        if (!type) {
            Logger::error(source)
                    << "invalid resource type '"
                    << pathData.resourceDir
                    << "'."
                    << std::endl;
            return false;
        }

        ResourceName resourceName = { table->getPackage(), *type, pathData.name };
        if (pathData.extension == "xml") {
            xmlCompileQueue.push(CompileXml{
                    source,
                    resourceName,
                    pathData.config
            });
        } else {
            // TODO(adamlesinski): Handle images here.
        }
    }

    bool error = false;
    while (!xmlCompileQueue.empty()) {
        const CompileXml& item = xmlCompileQueue.front();

        // Create the output path from the resource name.
        std::stringstream outputPath;
        outputPath << item.name.type;
        if (item.config != ConfigDescription{}) {
            outputPath << "-" << item.config.toString();
        }

        Source outSource = options.output;
        appendPath(&outSource.path, "res");
        appendPath(&outSource.path, outputPath.str());

        if (!mkdirs(outSource.path)) {
            Logger::error(outSource) << strerror(errno) << std::endl;
            return false;
        }

        appendPath(&outSource.path, util::utf16ToUtf8(item.name.entry) + ".xml");

        if (options.verbose) {
            Logger::note(outSource) << "compiling XML file." << std::endl;
        }

        error |= !compileXml(resolver, item, outSource, &xmlCompileQueue);
        xmlCompileQueue.pop();
    }
    return !error;
}

int main(int argc, char** argv) {
    Logger::setLog(std::make_shared<Log>(std::cerr, std::cerr));

    std::vector<StringPiece> args;
    args.reserve(argc - 1);
    for (int i = 1; i < argc; i++) {
        args.emplace_back(argv[i], strlen(argv[i]));
    }

    if (args.empty()) {
        Logger::error() << "no command specified." << std::endl;
        return 1;
    }

    AaptOptions options;

    // Check the command we're running.
    const StringPiece& command = args.front();
    if (command == "package") {
        if (!prepareLegacy(std::begin(args) + 1, std::end(args), options)) {
            return 1;
        }
    } else if (command == "collect") {
        if (!prepareCollect(std::begin(args) + 1, std::end(args), options)) {
            return 1;
        }
    } else if (command == "link") {
        if (!prepareLink(std::begin(args) + 1, std::end(args), options)) {
            return 1;
        }
    } else if (command == "compile") {
        if (!prepareCompile(std::begin(args) + 1, std::end(args), options)) {
            return 1;
        }
    } else {
        Logger::error() << "unknown command '" << command << "'." << std::endl;
        return 1;
    }

    //
    // Verify we have some common options set.
    //

    if (options.sources.empty()) {
        Logger::error() << "no sources specified." << std::endl;
        return false;
    }

    if (options.output.path.empty()) {
        Logger::error() << "no output directory specified." << std::endl;
        return false;
    }

    if (options.appInfo.package.empty()) {
        Logger::error() << "no package name specified." << std::endl;
        return false;
    }


    //
    // Every phase needs a resource table and a resolver/linker.
    //

    std::shared_ptr<ResourceTable> table = std::make_shared<ResourceTable>();
    table->setPackage(options.appInfo.package);
    if (options.appInfo.package == u"android") {
        table->setPackageId(0x01);
    } else {
        table->setPackageId(0x7f);
    }

    //
    // Load the included libraries.
    //
    std::shared_ptr<android::AssetManager> libraries = std::make_shared<android::AssetManager>();
    for (const Source& source : options.libraries) {
        if (util::stringEndsWith(source.path, ".arsc")) {
            // We'll process these last so as to avoid a cookie issue.
            continue;
        }

        int32_t cookie;
        if (!libraries->addAssetPath(android::String8(source.path.data()), &cookie)) {
            Logger::error(source) << "failed to load library." << std::endl;
            return false;
        }
    }

    for (const Source& source : options.libraries) {
        if (!util::stringEndsWith(source.path, ".arsc")) {
            // We've already processed this.
            continue;
        }

        // Dirty hack but there is no other way to get a
        // writeable ResTable.
        if (!loadResTable(const_cast<android::ResTable*>(&libraries->getResources(false)),
                          source)) {
            return false;
        }
    }

    // Make the resolver that will cache IDs for us.
    std::shared_ptr<Resolver> resolver = std::make_shared<Resolver>(table, libraries);

    //
    // Dispatch to the real phase here.
    //

    bool result = true;
    switch (options.phase) {
        case AaptOptions::Phase::LegacyFull:
            result = doLegacy(table, resolver, options);
            break;

        case AaptOptions::Phase::Collect:
            result = doCollect(table, resolver, options);
            break;

        case AaptOptions::Phase::Link:
            result = doLink(table, resolver, options);
            break;

        case AaptOptions::Phase::Compile:
            result = doCompile(table, resolver, options);
            break;
    }

    if (!result) {
        Logger::error()
                << "aapt exiting with failures."
                << std::endl;
        return 1;
    }
    return 0;
}
