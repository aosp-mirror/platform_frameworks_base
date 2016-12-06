/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "Flags.h"
#include "ResourceTable.h"
#include "io/ZipArchive.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "unflatten/BinaryResourceParser.h"

#include <android-base/macros.h>

namespace aapt {

class DiffContext : public IAaptContext {
public:
    const std::u16string& getCompilationPackage() override {
        return mEmpty;
    }

    uint8_t getPackageId() override {
        return 0x0;
    }

    IDiagnostics* getDiagnostics() override {
        return &mDiagnostics;
    }

    NameMangler* getNameMangler() override {
        return &mNameMangler;
    }

    SymbolTable* getExternalSymbols() override {
        return &mSymbolTable;
    }

    bool verbose() override {
        return false;
    }

private:
    std::u16string mEmpty;
    StdErrDiagnostics mDiagnostics;
    NameMangler mNameMangler = NameMangler(NameManglerPolicy{});
    SymbolTable mSymbolTable;
};

class LoadedApk {
public:
    LoadedApk(const Source& source, std::unique_ptr<io::IFileCollection> apk,
              std::unique_ptr<ResourceTable> table) :
            mSource(source), mApk(std::move(apk)), mTable(std::move(table)) {
    }

    io::IFileCollection* getFileCollection() {
        return mApk.get();
    }

    ResourceTable* getResourceTable() {
        return mTable.get();
    }

    const Source& getSource() {
        return mSource;
    }

private:
    Source mSource;
    std::unique_ptr<io::IFileCollection> mApk;
    std::unique_ptr<ResourceTable> mTable;

    DISALLOW_COPY_AND_ASSIGN(LoadedApk);
};

static std::unique_ptr<LoadedApk> loadApkFromPath(IAaptContext* context, const StringPiece& path) {
    Source source(path);
    std::string error;
    std::unique_ptr<io::ZipFileCollection> apk = io::ZipFileCollection::create(path, &error);
    if (!apk) {
        context->getDiagnostics()->error(DiagMessage(source) << error);
        return {};
    }

    io::IFile* file = apk->findFile("resources.arsc");
    if (!file) {
        context->getDiagnostics()->error(DiagMessage(source) << "no resources.arsc found");
        return {};
    }

    std::unique_ptr<io::IData> data = file->openAsData();
    if (!data) {
        context->getDiagnostics()->error(DiagMessage(source) << "could not open resources.arsc");
        return {};
    }

    std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();
    BinaryResourceParser parser(context, table.get(), source, data->data(), data->size());
    if (!parser.parse()) {
        return {};
    }

    return util::make_unique<LoadedApk>(source, std::move(apk), std::move(table));
}

static void emitDiffLine(const Source& source, const StringPiece& message) {
    std::cerr << source << ": " << message << "\n";
}

static bool isSymbolVisibilityDifferent(const Symbol& symbolA, const Symbol& symbolB) {
    return symbolA.state != symbolB.state;
}

template <typename Id>
static bool isIdDiff(const Symbol& symbolA, const Maybe<Id>& idA,
                     const Symbol& symbolB, const Maybe<Id>& idB) {
    if (symbolA.state == SymbolState::kPublic || symbolB.state == SymbolState::kPublic) {
        return idA != idB;
    }
    return false;
}

static bool emitResourceConfigValueDiff(IAaptContext* context,
                                        LoadedApk* apkA,
                                        ResourceTablePackage* pkgA,
                                        ResourceTableType* typeA,
                                        ResourceEntry* entryA,
                                        ResourceConfigValue* configValueA,
                                        LoadedApk* apkB,
                                        ResourceTablePackage* pkgB,
                                        ResourceTableType* typeB,
                                        ResourceEntry* entryB,
                                        ResourceConfigValue* configValueB) {
    Value* valueA = configValueA->value.get();
    Value* valueB = configValueB->value.get();
    if (!valueA->equals(valueB)) {
        std::stringstream strStream;
        strStream << "value " << pkgA->name << ":" << typeA->type << "/" << entryA->name
                << " config=" << configValueA->config << " does not match:\n";
        valueA->print(&strStream);
        strStream << "\n vs \n";
        valueB->print(&strStream);
        emitDiffLine(apkB->getSource(), strStream.str());
        return true;
    }
    return false;
}

static bool emitResourceEntryDiff(IAaptContext* context,
                                  LoadedApk* apkA,
                                  ResourceTablePackage* pkgA,
                                  ResourceTableType* typeA,
                                  ResourceEntry* entryA,
                                  LoadedApk* apkB,
                                  ResourceTablePackage* pkgB,
                                  ResourceTableType* typeB,
                                  ResourceEntry* entryB) {
    bool diff = false;
    for (std::unique_ptr<ResourceConfigValue>& configValueA : entryA->values) {
        ResourceConfigValue* configValueB = entryB->findValue(configValueA->config);
        if (!configValueB) {
            std::stringstream strStream;
            strStream << "missing " << pkgA->name << ":" << typeA->type << "/" << entryA->name
                    << " config=" << configValueA->config;
            emitDiffLine(apkB->getSource(), strStream.str());
            diff = true;
        } else {
            diff |= emitResourceConfigValueDiff(context, apkA, pkgA, typeA, entryA,
                                                configValueA.get(), apkB, pkgB, typeB, entryB,
                                                configValueB);
        }
    }

    // Check for any newly added config values.
    for (std::unique_ptr<ResourceConfigValue>& configValueB : entryB->values) {
        ResourceConfigValue* configValueA = entryA->findValue(configValueB->config);
        if (!configValueA) {
            std::stringstream strStream;
            strStream << "new config " << pkgB->name << ":" << typeB->type << "/" << entryB->name
                    << " config=" << configValueB->config;
            emitDiffLine(apkB->getSource(), strStream.str());
            diff = true;
        }
    }
    return false;
}

static bool emitResourceTypeDiff(IAaptContext* context,
                                 LoadedApk* apkA,
                                 ResourceTablePackage* pkgA,
                                 ResourceTableType* typeA,
                                 LoadedApk* apkB,
                                 ResourceTablePackage* pkgB,
                                 ResourceTableType* typeB) {
    bool diff = false;
    for (std::unique_ptr<ResourceEntry>& entryA : typeA->entries) {
        ResourceEntry* entryB = typeB->findEntry(entryA->name);
        if (!entryB) {
            std::stringstream strStream;
            strStream << "missing " << pkgA->name << ":" << typeA->type << "/" << entryA->name;
            emitDiffLine(apkB->getSource(), strStream.str());
            diff = true;
        } else {
            if (isSymbolVisibilityDifferent(entryA->symbolStatus, entryB->symbolStatus)) {
                std::stringstream strStream;
                strStream << pkgA->name << ":" << typeA->type << "/" << entryA->name
                        << " has different visibility (";
                if (entryB->symbolStatus.state == SymbolState::kPublic) {
                    strStream << "PUBLIC";
                } else {
                    strStream << "PRIVATE";
                }
                strStream << " vs ";
                if (entryA->symbolStatus.state == SymbolState::kPublic) {
                    strStream << "PUBLIC";
                } else {
                    strStream << "PRIVATE";
                }
                strStream << ")";
                emitDiffLine(apkB->getSource(), strStream.str());
                diff = true;
            } else if (isIdDiff(entryA->symbolStatus, entryA->id,
                                entryB->symbolStatus, entryB->id)) {
                std::stringstream strStream;
                strStream << pkgA->name << ":" << typeA->type << "/" << entryA->name
                        << " has different public ID (";
                if (entryB->id) {
                    strStream << "0x" << std::hex << entryB->id.value();
                } else {
                    strStream << "none";
                }
                strStream << " vs ";
                if (entryA->id) {
                    strStream << "0x " << std::hex << entryA->id.value();
                } else {
                    strStream << "none";
                }
                strStream << ")";
                emitDiffLine(apkB->getSource(), strStream.str());
                diff = true;
            }
            diff |= emitResourceEntryDiff(context, apkA, pkgA, typeA, entryA.get(),
                                          apkB, pkgB, typeB, entryB);
        }
    }

    // Check for any newly added entries.
    for (std::unique_ptr<ResourceEntry>& entryB : typeB->entries) {
        ResourceEntry* entryA = typeA->findEntry(entryB->name);
        if (!entryA) {
            std::stringstream strStream;
            strStream << "new entry " << pkgB->name << ":" << typeB->type << "/" << entryB->name;
            emitDiffLine(apkB->getSource(), strStream.str());
            diff = true;
        }
    }
    return diff;
}

static bool emitResourcePackageDiff(IAaptContext* context, LoadedApk* apkA,
                                    ResourceTablePackage* pkgA,
                                    LoadedApk* apkB, ResourceTablePackage* pkgB) {
    bool diff = false;
    for (std::unique_ptr<ResourceTableType>& typeA : pkgA->types) {
        ResourceTableType* typeB = pkgB->findType(typeA->type);
        if (!typeB) {
            std::stringstream strStream;
            strStream << "missing " << pkgA->name << ":" << typeA->type;
            emitDiffLine(apkA->getSource(), strStream.str());
            diff = true;
        } else {
            if (isSymbolVisibilityDifferent(typeA->symbolStatus, typeB->symbolStatus)) {
                std::stringstream strStream;
                strStream << pkgA->name << ":" << typeA->type << " has different visibility (";
                if (typeB->symbolStatus.state == SymbolState::kPublic) {
                    strStream << "PUBLIC";
                } else {
                    strStream << "PRIVATE";
                }
                strStream << " vs ";
                if (typeA->symbolStatus.state == SymbolState::kPublic) {
                    strStream << "PUBLIC";
                } else {
                    strStream << "PRIVATE";
                }
                strStream << ")";
                emitDiffLine(apkB->getSource(), strStream.str());
                diff = true;
            } else if (isIdDiff(typeA->symbolStatus, typeA->id, typeB->symbolStatus, typeB->id)) {
                std::stringstream strStream;
                strStream << pkgA->name << ":" << typeA->type << " has different public ID (";
                if (typeB->id) {
                    strStream << "0x" << std::hex << typeB->id.value();
                } else {
                    strStream << "none";
                }
                strStream << " vs ";
                if (typeA->id) {
                    strStream << "0x " << std::hex << typeA->id.value();
                } else {
                    strStream << "none";
                }
                strStream << ")";
                emitDiffLine(apkB->getSource(), strStream.str());
                diff = true;
            }
            diff |= emitResourceTypeDiff(context, apkA, pkgA, typeA.get(), apkB, pkgB, typeB);
        }
    }

    // Check for any newly added types.
    for (std::unique_ptr<ResourceTableType>& typeB : pkgB->types) {
        ResourceTableType* typeA = pkgA->findType(typeB->type);
        if (!typeA) {
            std::stringstream strStream;
            strStream << "new type " << pkgB->name << ":" << typeB->type;
            emitDiffLine(apkB->getSource(), strStream.str());
            diff = true;
        }
    }
    return diff;
}

static bool emitResourceTableDiff(IAaptContext* context, LoadedApk* apkA, LoadedApk* apkB) {
    ResourceTable* tableA = apkA->getResourceTable();
    ResourceTable* tableB = apkB->getResourceTable();

    bool diff = false;
    for (std::unique_ptr<ResourceTablePackage>& pkgA : tableA->packages) {
        ResourceTablePackage* pkgB = tableB->findPackage(pkgA->name);
        if (!pkgB) {
            std::stringstream strStream;
            strStream << "missing package " << pkgA->name;
            emitDiffLine(apkB->getSource(), strStream.str());
            diff = true;
        } else {
            if (pkgA->id != pkgB->id) {
                std::stringstream strStream;
                strStream << "package '" << pkgA->name << "' has different id (";
                if (pkgB->id) {
                    strStream << "0x" << std::hex << pkgB->id.value();
                } else {
                    strStream << "none";
                }
                strStream << " vs ";
                if (pkgA->id) {
                    strStream << "0x" << std::hex << pkgA->id.value();
                } else {
                    strStream << "none";
                }
                strStream << ")";
                emitDiffLine(apkB->getSource(), strStream.str());
                diff = true;
            }
            diff |= emitResourcePackageDiff(context, apkA, pkgA.get(), apkB, pkgB);
        }
    }

    // Check for any newly added packages.
    for (std::unique_ptr<ResourceTablePackage>& pkgB : tableB->packages) {
        ResourceTablePackage* pkgA = tableA->findPackage(pkgB->name);
        if (!pkgA) {
            std::stringstream strStream;
            strStream << "new package " << pkgB->name;
            emitDiffLine(apkB->getSource(), strStream.str());
            diff = true;
        }
    }
    return diff;
}

int diff(const std::vector<StringPiece>& args) {
    DiffContext context;

    Flags flags;
    if (!flags.parse("aapt2 diff", args, &std::cerr)) {
        return 1;
    }

    if (flags.getArgs().size() != 2u) {
        std::cerr << "must have two apks as arguments.\n\n";
        flags.usage("aapt2 diff", &std::cerr);
        return 1;
    }

    std::unique_ptr<LoadedApk> apkA = loadApkFromPath(&context, flags.getArgs()[0]);
    std::unique_ptr<LoadedApk> apkB = loadApkFromPath(&context, flags.getArgs()[1]);
    if (!apkA || !apkB) {
        return 1;
    }

    if (emitResourceTableDiff(&context, apkA.get(), apkB.get())) {
        // We emitted a diff, so return 1 (failure).
        return 1;
    }
    return 0;
}

} // namespace aapt
