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
#include "Files.h"
#include "Flag.h"
#include "JavaClassGenerator.h"
#include "Linker.h"
#include "ManifestParser.h"
#include "ManifestValidator.h"
#include "Png.h"
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
#include <utils/Errors.h>

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

    bool result = table->add(buf, dataSize, -1, true) == android::NO_ERROR;

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
                const ResourceName& name, const ConfigDescription& config) {
    std::ifstream in(source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(source) << strerror(errno) << std::endl;
        return false;
    }

    std::set<size_t> sdkLevels;

    SourceXmlPullParser parser(in);
    while (XmlPullParser::isGoodEvent(parser.next())) {
        if (parser.getEvent() != XmlPullParser::Event::kStartElement) {
            continue;
        }

        const auto endIter = parser.endAttributes();
        for (auto iter = parser.beginAttributes(); iter != endIter; ++iter) {
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
                table->addResource(refName, {}, source.line(parser.getLineNumber()),
                                   util::make_unique<Id>());
            }
        }
    }

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

struct CompileItem {
    Source source;
    ResourceName name;
    ConfigDescription config;
    std::string extension;
};

bool compileXml(std::shared_ptr<Resolver> resolver, const CompileItem& item,
                const Source& outputSource, std::queue<CompileItem>* queue) {
    std::ifstream in(item.source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(item.source) << strerror(errno) << std::endl;
        return false;
    }

    std::shared_ptr<BindingXmlPullParser> binding;
    std::shared_ptr<XmlPullParser> xmlParser = std::make_shared<SourceXmlPullParser>(in);
    if (item.name.type == ResourceType::kLayout) {
        binding = std::make_shared<BindingXmlPullParser>(xmlParser);
        xmlParser = binding;
    }

    BigBuffer outBuffer(1024);
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
        CompileItem newWork = item;
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

    if (binding) {
        // We generated a binding xml file, write it out beside the output file.
        Source bindingOutput = outputSource;
        bindingOutput.path += ".bind.xml";
        std::ofstream bout(bindingOutput.path);
        if (!bout) {
            Logger::error(bindingOutput) << strerror(errno) << std::endl;
            return false;
        }

        if (!binding->writeToFile(bout)) {
            Logger::error(bindingOutput) << strerror(errno) << std::endl;
            return false;
        }
    }
    return true;
}

bool compilePng(const Source& source, const Source& output) {
    std::ifstream in(source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(source) << strerror(errno) << std::endl;
        return false;
    }

    std::ofstream out(output.path, std::ofstream::binary);
    if (!out) {
        Logger::error(output) << strerror(errno) << std::endl;
        return false;
    }

    std::string err;
    Png png;
    if (!png.process(source, in, out, {}, &err)) {
        Logger::error(source) << err << std::endl;
        return false;
    }
    return true;
}

bool copyFile(const Source& source, const Source& output) {
    std::ifstream in(source.path, std::ifstream::binary);
    if (!in) {
        Logger::error(source) << strerror(errno) << std::endl;
        return false;
    }

    std::ofstream out(output.path, std::ofstream::binary);
    if (!out) {
        Logger::error(output) << strerror(errno) << std::endl;
        return false;
    }

    if (out << in.rdbuf()) {
        Logger::error(output) << strerror(errno) << std::endl;
        return true;
    }
    return false;
}

struct AaptOptions {
    enum class Phase {
        Full,
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

    // The source directories to walk and find resource files.
    std::vector<Source> sourceDirs;

    // The resource files to process and collect.
    std::vector<Source> collectFiles;

    // The binary table files to link.
    std::vector<Source> linkFiles;

    // The resource files to compile.
    std::vector<Source> compileFiles;

    // The libraries these files may reference.
    std::vector<Source> libraries;

    // Output path. This can be a directory or file
    // depending on the phase.
    Source output;

    // Directory to in which to generate R.java.
    Maybe<Source> generateJavaClass;

    // Whether to output verbose details about
    // compilation.
    bool verbose = false;
};

bool compileAndroidManifest(const std::shared_ptr<Resolver>& resolver,
                            const AaptOptions& options) {
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

    android::ResXMLTree tree;
    if (tree.setTo(data.get(), outBuffer.size()) != android::NO_ERROR) {
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

static AaptOptions prepareArgs(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "no command specified." << std::endl;
        exit(1);
    }

    const StringPiece command(argv[1]);
    argc -= 2;
    argv += 2;

    AaptOptions options;

    StringPiece outputDescription = "place output in file";
    if (command == "package") {
        options.phase = AaptOptions::Phase::Full;
        outputDescription = "place output in directory";
    } else if (command == "collect") {
        options.phase = AaptOptions::Phase::Collect;
    } else if (command == "link") {
        options.phase = AaptOptions::Phase::Link;
    } else if (command == "compile") {
        options.phase = AaptOptions::Phase::Compile;
        outputDescription = "place output in directory";
    } else {
        std::cerr << "invalid command '" << command << "'." << std::endl;
        exit(1);
    }

    if (options.phase == AaptOptions::Phase::Full) {
        flag::requiredFlag("-S", "add a directory in which to find resources",
                [&options](const StringPiece& arg) {
                    options.sourceDirs.push_back(Source{ arg.toString() });
                });

        flag::requiredFlag("-M", "path to AndroidManifest.xml",
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

    } else {
        flag::requiredFlag("--package", "Android package name",
                [&options](const StringPiece& arg) {
                    options.appInfo.package = util::utf8ToUtf16(arg);
                });

        if (options.phase != AaptOptions::Phase::Collect) {
            flag::optionalFlag("-I", "add an Android APK to link against",
                    [&options](const StringPiece& arg) {
                        options.libraries.push_back(Source{ arg.toString() });
                    });
        }

        if (options.phase == AaptOptions::Phase::Link) {
            flag::optionalFlag("--java", "directory in which to generate R.java",
                    [&options](const StringPiece& arg) {
                        options.generateJavaClass = Source{ arg.toString() };
                    });
        }
    }

    // Common flags for all steps.
    flag::requiredFlag("-o", outputDescription, [&options](const StringPiece& arg) {
        options.output = Source{ arg.toString() };
    });
    flag::optionalSwitch("-v", "enables verbose logging", &options.verbose);

    // Build the command string for output (eg. "aapt2 compile").
    std::string fullCommand = "aapt2";
    fullCommand += " ";
    fullCommand += command.toString();

    // Actually read the command line flags.
    flag::parse(argc, argv, fullCommand);

    // Copy all the remaining arguments.
    if (options.phase == AaptOptions::Phase::Collect) {
        for (const std::string& arg : flag::getArgs()) {
            options.collectFiles.push_back(Source{ arg });
        }
    } else if (options.phase == AaptOptions::Phase::Compile) {
        for (const std::string& arg : flag::getArgs()) {
            options.compileFiles.push_back(Source{ arg });
        }
    } else if (options.phase == AaptOptions::Phase::Link) {
        for (const std::string& arg : flag::getArgs()) {
            options.linkFiles.push_back(Source{ arg });
        }
    }
    return options;
}

static bool collectValues(const std::shared_ptr<ResourceTable>& table, const Source& source,
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

bool doAll(AaptOptions* options, const std::shared_ptr<ResourceTable>& table,
           const std::shared_ptr<Resolver>& resolver) {
    const bool versionStyles = (options->phase == AaptOptions::Phase::Full ||
            options->phase == AaptOptions::Phase::Link);
    const bool verifyNoMissingSymbols = (options->phase == AaptOptions::Phase::Full ||
            options->phase == AaptOptions::Phase::Link);
    const bool compileFiles = (options->phase == AaptOptions::Phase::Full ||
            options->phase == AaptOptions::Phase::Compile);
    const bool flattenTable = (options->phase == AaptOptions::Phase::Full ||
            options->phase == AaptOptions::Phase::Collect ||
            options->phase == AaptOptions::Phase::Link);
    const bool useExtendedChunks = options->phase == AaptOptions::Phase::Collect;

    // Build the output table path.
    Source outputTable = options->output;
    if (options->phase == AaptOptions::Phase::Full) {
        appendPath(&outputTable.path, "resources.arsc");
    }

    bool error = false;
    std::queue<CompileItem> compileQueue;

    // If source directories were specified, walk them looking for resource files.
    if (!options->sourceDirs.empty()) {
        const char* customIgnore = getenv("ANDROID_AAPT_IGNORE");
        FileFilter fileFilter;
        if (customIgnore && customIgnore[0]) {
            fileFilter.setPattern(customIgnore);
        } else {
            fileFilter.setPattern(
                    "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~");
        }

        for (const Source& source : options->sourceDirs) {
            if (!walkTree(source, fileFilter, &options->collectFiles)) {
                return false;
            }
        }
    }

    // Load all binary resource tables.
    for (const Source& source : options->linkFiles) {
        error |= !loadBinaryResourceTable(table, source);
    }

    if (error) {
        return false;
    }

    // Collect all the resource files.
    // Need to parse the resource type/config/filename.
    for (const Source& source : options->collectFiles) {
        Maybe<ResourcePathData> maybePathData = extractResourcePathData(source);
        if (!maybePathData) {
            return false;
        }

        const ResourcePathData& pathData = maybePathData.value();
        if (pathData.resourceDir == u"values") {
            if (options->verbose) {
                Logger::note(source) << "collecting values..." << std::endl;
            }

            error |= !collectValues(table, source, pathData.config);
            continue;
        }

        const ResourceType* type = parseResourceType(pathData.resourceDir);
        if (!type) {
            Logger::error(source) << "invalid resource type '" << pathData.resourceDir << "'."
                                  << std::endl;
            return false;
        }

        ResourceName resourceName = { table->getPackage(), *type, pathData.name };

        // Add the file name to the resource table.
        std::unique_ptr<FileReference> fileReference = makeFileReference(
                table->getValueStringPool(),
                util::utf16ToUtf8(pathData.name) + "." + pathData.extension,
                *type, pathData.config);
        error |= !table->addResource(resourceName, pathData.config, source.line(0),
                                     std::move(fileReference));

        if (pathData.extension == "xml") {
            error |= !collectXml(table, source, resourceName, pathData.config);
        }

        compileQueue.push(
                CompileItem{ source, resourceName, pathData.config, pathData.extension });
    }

    if (error) {
        return false;
    }

    // Version all styles referencing attributes outside of their specified SDK version.
    if (versionStyles) {
        versionStylesForCompat(table);
    }

    // Verify that all references are valid.
    Linker linker(table, resolver);
    if (!linker.linkAndValidate()) {
        return false;
    }

    // Verify that all symbols exist.
    if (verifyNoMissingSymbols) {
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

    // Compile files.
    if (compileFiles) {
        // First process any input compile files.
        for (const Source& source : options->compileFiles) {
            Maybe<ResourcePathData> maybePathData = extractResourcePathData(source);
            if (!maybePathData) {
                return false;
            }

            const ResourcePathData& pathData = maybePathData.value();
            const ResourceType* type = parseResourceType(pathData.resourceDir);
            if (!type) {
                Logger::error(source) << "invalid resource type '" << pathData.resourceDir
                                      << "'." << std::endl;
                return false;
            }

            ResourceName resourceName = { table->getPackage(), *type, pathData.name };
            compileQueue.push(
                    CompileItem{ source, resourceName, pathData.config, pathData.extension });
        }

        // Now process the actual compile queue.
        for (; !compileQueue.empty(); compileQueue.pop()) {
            const CompileItem& item = compileQueue.front();

            // Create the output directory path from the resource type and config.
            std::stringstream outputPath;
            outputPath << item.name.type;
            if (item.config != ConfigDescription{}) {
                outputPath << "-" << item.config.toString();
            }

            Source outSource = options->output;
            appendPath(&outSource.path, "res");
            appendPath(&outSource.path, outputPath.str());

            // Make the directory.
            if (!mkdirs(outSource.path)) {
                Logger::error(outSource) << strerror(errno) << std::endl;
                return false;
            }

            // Add the file name to the directory path.
            appendPath(&outSource.path, util::utf16ToUtf8(item.name.entry) + "." + item.extension);

            if (item.extension == "xml") {
                if (options->verbose) {
                    Logger::note(outSource) << "compiling XML file." << std::endl;
                }

                error |= !compileXml(resolver, item, outSource, &compileQueue);
            } else if (item.extension == "png" || item.extension == "9.png") {
                if (options->verbose) {
                    Logger::note(outSource) << "compiling png file." << std::endl;
                }

                error |= !compilePng(item.source, outSource);
            } else {
                error |= !copyFile(item.source, outSource);
            }
        }

        if (error) {
            return false;
        }
    }

    // Compile and validate the AndroidManifest.xml.
    if (!options->manifest.path.empty()) {
        if (!compileAndroidManifest(resolver, *options)) {
            return false;
        }
    }

    // Generate the Java class file.
    if (options->generateJavaClass) {
        Source outPath = options->generateJavaClass.value();
        if (options->verbose) {
            Logger::note() << "writing symbols to " << outPath << "." << std::endl;
        }

        // Build the output directory from the package name.
        // Eg. com.android.app -> com/android/app
        const std::string packageUtf8 = util::utf16ToUtf8(table->getPackage());
        for (StringPiece part : util::tokenize<char>(packageUtf8, '.')) {
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

        JavaClassGenerator generator(table, {});
        if (!generator.generate(fout)) {
            Logger::error(outPath) << generator.getError() << "." << std::endl;
            return false;
        }
    }

    // Flatten the resource table.
    if (flattenTable && table->begin() != table->end()) {
        BigBuffer buffer(1024);
        TableFlattener::Options tableOptions;
        tableOptions.useExtendedChunks = useExtendedChunks;
        TableFlattener flattener(tableOptions);
        if (!flattener.flatten(&buffer, *table)) {
            Logger::error() << "failed to flatten resource table." << std::endl;
            return false;
        }

        if (options->verbose) {
            Logger::note() << "Final resource table size=" << util::formatSize(buffer.size())
                           << std::endl;
        }

        std::ofstream fout(outputTable.path, std::ofstream::binary);
        if (!fout) {
            Logger::error(outputTable) << strerror(errno) << "." << std::endl;
            return false;
        }

        if (!util::writeAll(fout, buffer)) {
            Logger::error(outputTable) << strerror(errno) << "." << std::endl;
            return false;
        }
        fout.flush();
    }
    return true;
}

int main(int argc, char** argv) {
    Logger::setLog(std::make_shared<Log>(std::cerr, std::cerr));
    AaptOptions options = prepareArgs(argc, argv);

    // If we specified a manifest, go ahead and load the package name from the manifest.
    if (!options.manifest.path.empty()) {
        if (!loadAppInfo(options.manifest, &options.appInfo)) {
            return false;
        }
    }

    // Verify we have some common options set.
    if (options.appInfo.package.empty()) {
        Logger::error() << "no package name specified." << std::endl;
        return false;
    }

    // Every phase needs a resource table.
    std::shared_ptr<ResourceTable> table = std::make_shared<ResourceTable>();
    table->setPackage(options.appInfo.package);
    if (options.appInfo.package == u"android") {
        table->setPackageId(0x01);
    } else {
        table->setPackageId(0x7f);
    }

    // Load the included libraries.
    std::shared_ptr<android::AssetManager> libraries = std::make_shared<android::AssetManager>();
    for (const Source& source : options.libraries) {
        if (util::stringEndsWith<char>(source.path, ".arsc")) {
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
        if (!util::stringEndsWith<char>(source.path, ".arsc")) {
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

    // Do the work.
    if (!doAll(&options, table, resolver)) {
        Logger::error() << "aapt exiting with failures." << std::endl;
        return 1;
    }
    return 0;
}
